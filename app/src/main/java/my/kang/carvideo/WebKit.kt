package my.kang.carvideo

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONObject

/**
 * Shared WebView setup for both screens.
 *
 * Both WebViews live in the same app process, so they share one CookieManager.
 * That is the whole login story: sign in on the phone, where a real keyboard
 * exists, and the car WebView is already signed in.
 */
object WebKit {

    /**
     * @param offscreenPreRaster for the car WebView. Its window lives on a virtual
     *   display, which the framework can treat as not-visible and then stop
     *   rasterising tiles for; this keeps them coming at the cost of some memory.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, offscreenPreRaster: Boolean = false) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = when {
                Config.FORCE_USER_AGENT.isNotBlank() -> Config.FORCE_USER_AGENT
                Config.SITE == Config.Site.TV -> Config.TV_USER_AGENT
                else -> browserLikeUserAgent(userAgentString)
            }
            setOffscreenPreRaster(offscreenPreRaster)
        }
        webView.setBackgroundColor(Color.BLACK)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    /**
     * Google refuses to sign you in from an embedded WebView, and detects one by the
     * "; wv" and "Version/x.y" tokens the system puts in the default user agent.
     * Strip exactly those two and leave the rest alone, so the string stays current
     * as the system WebView updates itself.
     */
    fun browserLikeUserAgent(default: String): String =
        default.replace("; wv", "")
            .replace(Regex("""\s*Version/\d+(\.\d+)*"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

    /**
     * Pages hand off to other apps through intent://, vnd.youtube://, market:// and so on.
     * Inside our WebView those can only end in an error page, and on the phone they would
     * leave our cookie jar - so keep every navigation on http(s).
     */
    fun shouldBlockNavigation(request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme?.lowercase() ?: return false
        return scheme != "http" && scheme != "https"
    }

    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /** Best-effort: are we carrying a Google session cookie? */
    fun isSignedIn(): Boolean {
        val cookieManager = CookieManager.getInstance()
        val jar = buildString {
            append(cookieManager.getCookie("https://www.youtube.com").orEmpty()).append(';')
            append(cookieManager.getCookie("https://m.youtube.com").orEmpty()).append(';')
            append(cookieManager.getCookie("https://accounts.google.com").orEmpty())
        }
        return SESSION_COOKIES.any { jar.contains("$it=") }
    }

    fun signOut() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            removeSessionCookies(null)
            flush()
        }
    }

    private val SESSION_COOKIES = listOf("SAPISID", "__Secure-1PSID", "__Secure-3PSID", "SID")

    // ------------------------------------------------------------------ keyboard bridge

    /**
     * Injected once per page. Tapping a key must not move Android focus away from the
     * WebView, but the page can still shuffle its own DOM focus, so we remember the
     * last text field that was focused and fall back to it.
     *
     * Values are written through the native setter because YouTube and the Google
     * sign-in page are framework-rendered: a plain `el.value = x` is not seen.
     */
    val KEYBOARD_BRIDGE_JS = """
        (function () {
          if (window.__kb) return;
          var last = null;
          function editable(el) {
            return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
          }
          document.addEventListener('focusin', function (e) {
            if (editable(e.target)) last = e.target;
          }, true);
          function target() {
            var a = document.activeElement;
            if (editable(a)) return a;
            if (last && document.contains(last)) return last;
            return null;
          }
          function setValue(el, value) {
            var proto = el.tagName === 'TEXTAREA'
              ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            var desc = Object.getOwnPropertyDescriptor(proto, 'value');
            if (desc && desc.set) desc.set.call(el, value); else el.value = value;
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
          }
          window.__kb = {
            type: function (text) {
              var el = target(); if (!el) return 'no-target';
              el.focus();
              if (el.isContentEditable) { document.execCommand('insertText', false, text); return 'ok'; }
              setValue(el, (el.value || '') + text);
              return 'ok';
            },
            back: function () {
              var el = target(); if (!el) return 'no-target';
              el.focus();
              if (el.isContentEditable) { document.execCommand('delete', false, null); return 'ok'; }
              setValue(el, (el.value || '').slice(0, -1));
              return 'ok';
            },
            clear: function () {
              var el = target(); if (!el) return 'no-target';
              el.focus();
              if (el.isContentEditable) { el.textContent = ''; return 'ok'; }
              setValue(el, '');
              return 'ok';
            },
            enter: function () {
              var el = target(); if (!el) return 'no-target';
              el.focus();
              ['keydown', 'keypress', 'keyup'].forEach(function (type) {
                el.dispatchEvent(new KeyboardEvent(type, {
                  key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                  bubbles: true, cancelable: true
                }));
              });
              if (el.form && el.form.requestSubmit) { try { el.form.requestSubmit(); } catch (e) {} }
              return 'ok';
            }
          };
        })();
    """.trimIndent()

    fun typeJs(text: String) = "window.__kb && window.__kb.type(${JSONObject.quote(text)});"
    const val BACKSPACE_JS = "window.__kb && window.__kb.back();"
    const val CLEAR_JS = "window.__kb && window.__kb.clear();"
    const val ENTER_JS = "window.__kb && window.__kb.enter();"
}

/**
 * Handles the fullscreen handoff YouTube does through WebChromeClient rather than
 * the DOM: the player hands us a view to show instead of the page.
 */
class FullscreenWebChromeClient(
    private val host: ViewGroup,
    private val webView: () -> WebView?,
    private val onChanged: (Boolean) -> Unit = {}
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    val isFullscreen: Boolean get() = customView != null

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        webView()?.visibility = View.GONE
        view.setBackgroundColor(Color.BLACK)
        host.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        onChanged(true)
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
        webView()?.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        onChanged(false)
    }

    /** Same as onHideCustomView but safe to call when nothing is fullscreen. */
    fun exit(): Boolean {
        if (customView == null) return false
        onHideCustomView()
        return true
    }
}
