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
 * WebView setup for the car screen.
 *
 * Sign-in happens on the TV page itself: "使用手机登录" shows a code to enter at
 * yt.be/activate on the phone. The session lives in this app's CookieManager.
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
            // WebView follows the phone's system font size (this phone: 1.3x) by zooming
            // every font but not the layout: youtube.com/tv already scales itself to the
            // screen, so its side bar text overflowed and overlapped the icons.
            textZoom = 100
            applyUserAgent(webView)
            setOffscreenPreRaster(offscreenPreRaster)
        }
        webView.setBackgroundColor(Color.BLACK)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    /** The TV UA from [Settings]; youtube.com/tv bounces anything else to desktop. */
    fun applyUserAgent(webView: WebView) {
        webView.settings.userAgentString = Settings.userAgent
    }

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

    /**
     * Text typed on the phone lands in the car page's text field through this.
     *
     * Finding "the" field is the hard part: opening the phone keyboard takes window
     * focus away from the car page, which blurs its input, and a field the page
     * autofocused before we were injected never fired focusin. So: the focused field,
     * else the last one focused OR blurred, else the first visible text field.
     *
     * Values are written through the native setter because sign-in and search pages
     * are framework-rendered: a plain `el.value = x` is not seen.
     */
    val KEYBOARD_BRIDGE_JS = """
        (function () {
          if (window.__kb) return;
          var last = null;
          function editable(el) {
            return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
          }
          function remember(e) { if (editable(e.target)) last = e.target; }
          document.addEventListener('focusin', remember, true);
          document.addEventListener('focusout', remember, true);
          if (editable(document.activeElement)) last = document.activeElement;
          var FIELDS = 'input:not([type=hidden]):not([type=checkbox]):not([type=radio])' +
            ':not([type=submit]):not([type=button]):not([disabled]),textarea';
          function fields() {
            // Really on screen: pages park hidden inputs (autofill traps, templates)
            // that offsetParent alone does not rule out.
            return Array.prototype.filter.call(document.querySelectorAll(FIELDS), function (el) {
              var r = el.getBoundingClientRect(), st = getComputedStyle(el);
              return r.width > 4 && r.height > 4 && st.visibility !== 'hidden' &&
                st.display !== 'none' && parseFloat(st.opacity || '1') > 0.05 &&
                el.getAttribute('aria-hidden') !== 'true';
            });
          }
          function target() {
            var a = document.activeElement;
            if (editable(a)) return a;
            if (last && document.contains(last)) return last;
            return fields()[0] || null;
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
            set: function (text) {
              var el = target(); if (!el) return 'no-target';
              el.focus();
              if (el.isContentEditable) { el.textContent = text; return 'ok'; }
              setValue(el, text);
              return 'ok';
            },
            next: function () {
              var all = fields(), el = target();
              var i = all.indexOf(el), n = all[i + 1] || all[0];
              if (!n) return 'no-target';
              n.focus(); last = n;
              return 'ok';
            },
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
    fun setJs(text: String) = "window.__kb && window.__kb.set(${JSONObject.quote(text)});"
    const val NEXT_FIELD_JS = "window.__kb && window.__kb.next();"

    /**
     * On the watch page, pin the video to the whole screen with object-fit: contain.
     *
     * Left alone, youtube.com/tv zooms the video in and out by itself - cropped and
     * enlarged with no captions, back to fit when a caption shows, smaller still for
     * two lines - so the car picture kept "breathing". Re-checked every half second
     * because the page is a single-page app: the hash changes, the document doesn't.
     */
    const val FIT_VIDEO_JS = """
        (function () {
          if (window.__cvfit) return;
          window.__cvfit = 1;
          var s = document.createElement('style');
          document.head.appendChild(s);
          var FIT = 'video{position:fixed!important;left:0!important;top:0!important;' +
            'width:100vw!important;height:100vh!important;transform:none!important;' +
            'object-fit:contain!important;}';
          setInterval(function () {
            var css = location.hash.indexOf('#/watch') === 0 ? FIT : '';
            if (s.textContent !== css) s.textContent = css;
          }, 500);
        })();
    """

    /** Signs the car out of YouTube: every cookie this app holds is dropped. */
    fun signOut() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            removeSessionCookies(null)
            flush()
        }
    }
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
