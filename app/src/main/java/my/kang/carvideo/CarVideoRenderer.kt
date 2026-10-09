package my.kang.carvideo

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import org.json.JSONObject
import kotlin.math.abs

/**
 * Owns everything drawn on the car screen: youtube.com/tv, plus a remote.
 *
 * The host hands us a plain Surface, which cannot host a View hierarchy, so we
 * wrap it in a VirtualDisplay and put a Presentation on that display:
 *
 *     Presentation
 *       └─ host (FrameLayout)
 *            ├─ WebView           youtube.com/tv, fullscreen
 *            └─ RemoteOverlay     bottom-right button + the remote it opens
 *
 * The VirtualDisplay, the Presentation and the WebView OUTLIVE the Surface. When the
 * host takes the surface away (you switch to Maps, the car screen sleeps) we only
 * detach it from the display; when it comes back we reattach it. The page, the video
 * position and the sign-in all survive, and the picture is back instantly instead of
 * reloading YouTube. Only a change of size or density forces a rebuild.
 *
 * The TV interface is driven by keys, not touch, so car input is translated:
 *   swipe on the car screen  -> arrow keys, one per [Settings.swipeStep] pixels
 *   tap                      -> OK, unless [RemoteOverlay] takes it (its buttons,
 *                               or bringing the hidden button back)
 *
 * Everything here runs on the main thread (see [onMain]).
 *
 * @param carContext the CarContext in a real session; any Context works, which is
 *   what lets [Harness] drive the same pipeline on the phone without a car.
 */
class CarVideoRenderer(private val carContext: Context) : SurfaceCallback, CarLink.Controller {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webHost: FrameLayout? = null
    private var overlay: RemoteOverlay? = null
    private var webView: WebView? = null
    private var chromeClient: FullscreenWebChromeClient? = null

    /** The host's surface currently feeding the virtual display, if any. */
    private var surface: Surface? = null

    /** What the surface looked like when the display was built; a change forces a rebuild. */
    private var builtWidth = 0
    private var builtHeight = 0
    private var builtDpi = 0

    /**
     * Size of the virtual display, which is always the host's surface size: a
     * VirtualDisplay does NOT scale into a bigger surface, it draws a smaller picture
     * into the top-left corner. Touch arrives in these same coordinates.
     */
    private var displayWidth = 0
    private var displayHeight = 0

    /**
     * Finger travel not yet turned into an arrow key, in the direction the finger
     * moved. A swipe arrives as a burst of onScroll callbacks; each [Settings.swipeStep]
     * of travel along the dominant axis is one key press.
     */
    private var swipeX = 0f
    private var swipeY = 0f
    private var lastSwipeAt = 0L
    private val endSwipe = Runnable { resetSwipe() }

    /** Remembered across a full rebuild. */
    private var webUrl = Settings.startUrl

    /** What the media session (car buttons, phone shade, head unit card) talks to. */
    private val mediaPage = object : CarMediaSession.Page {
        override fun sendKey(keyCode: Int) = onMain { sendKeyCode(keyCode) }
        override fun eval(js: String, onResult: (String?) -> Unit) {
            val wv = webView
            if (wv == null) onResult(null) else wv.evaluateJavascript(js) { onResult(it) }
        }
    }

    /** Settings edited on the phone or over adb are applied to the live car screen. */
    private val settingsListener = Settings.addListener { key -> onMain { applySetting(key) } }

    init {
        CarLink.controller = this
        CarLink.update { CarLink.Status(alive = true) }
        CarMediaSession.init(carContext)
        CarMediaSession.attach(mediaPage)
    }

    // ---------------------------------------------------------------- SurfaceCallback

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = onMain {
        val newSurface = surfaceContainer.surface
        if (newSurface == null) {
            Log.w(TAG, "onSurfaceAvailable with null surface")
            return@onMain
        }
        val width = surfaceContainer.width.coerceAtLeast(1)
        val height = surfaceContainer.height.coerceAtLeast(1)
        val dpi = if (surfaceContainer.dpi > 0) surfaceContainer.dpi else Config.FALLBACK_DPI

        val oldSurface = surface
        surface = newSurface

        val display = virtualDisplay
        if (display == null || width != builtWidth || height != builtHeight || dpi != builtDpi) {
            Log.i(TAG, "building display for ${width}x$height @$dpi")
            rememberState()
            tearDownDisplay()
            buildDisplay(newSurface, width, height, dpi)
        } else {
            // Same geometry: just plug the new surface in. Nothing reloads.
            Log.i(TAG, "reattaching surface, content kept")
            display.surface = newSurface
            setKeepAwake(true)
        }

        if (oldSurface != null && oldSurface !== newSurface) oldSurface.release()
        CarLink.update { it.copy(onScreen = virtualDisplay != null) }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = onMain {
        resetSwipe()
        // Detach, don't destroy: the page and the video keep their state, and audio keeps
        // playing while you are on another Android Auto screen.
        virtualDisplay?.surface = null
        setKeepAwake(false)
        surface?.release()
        surface = null
        WebKit.flushCookies()
        CarLink.update { it.copy(onScreen = false) }
    }

    /** A tap on our controls presses them; anywhere else it is OK. */
    override fun onClick(x: Float, y: Float) = onMain {
        if (presentation == null) return@onMain
        // Some hosts report a click right as a swipe ends; that is part of the swipe.
        if (SystemClock.uptimeMillis() - lastSwipeAt < CLICK_AFTER_SWIPE_GUARD_MS) return@onMain
        if (overlay?.onTap(x, y) != true) sendKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) = onMain {
        if (presentation == null) return@onMain
        overlay?.onActivity()
        swipeToKeys(distanceX, distanceY)
    }

    /** distanceX/Y are "previous minus current", so the finger moved by their negation. */
    private fun swipeToKeys(distanceX: Float, distanceY: Float) {
        swipeX -= distanceX
        swipeY -= distanceY
        lastSwipeAt = SystemClock.uptimeMillis()
        val step = Settings.swipeStep
        while (abs(swipeX) >= step || abs(swipeY) >= step) {
            if (abs(swipeX) >= abs(swipeY)) {
                sendKeyCode(if (swipeX > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                swipeX -= step * Math.signum(swipeX)
            } else {
                sendKeyCode(if (swipeY > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                swipeY -= step * Math.signum(swipeY)
            }
        }
        mainHandler.removeCallbacks(endSwipe)
        mainHandler.postDelayed(endSwipe, SWIPE_END_DELAY_MS)
    }

    override fun onFling(velocityX: Float, velocityY: Float) = onMain { resetSwipe() }

    // ------------------------------------------------------------------ public actions

    /** ActionStrip remote icon: show or hide the remote. */
    override fun toggleRemote() = onMain { overlay?.toggleRemote() }

    /** Back to the youtube.com/tv start page. */
    override fun goHome() = loadUrl(Settings.startUrl)

    override fun loadUrl(url: String) = onMain {
        chromeClient?.exit()
        webUrl = url
        webView?.loadUrl(url)
    }

    /** Reload, picking up a sign-in that just happened. */
    override fun reload() = onMain {
        chromeClient?.exit()
        webView?.reload()
    }

    /** Leave fullscreen, or Escape, which is "back" to the TV page. */
    override fun goBack() = onMain {
        if (chromeClient?.exit() != true) sendKeyCode(KeyEvent.KEYCODE_ESCAPE)
    }

    override fun sendKey(keyCode: Int) = onMain { sendKeyCode(keyCode) }

    override fun evalJs(js: String, onResult: (String?) -> Unit) = onMain {
        val wv = webView ?: return@onMain onResult(null)
        wv.evaluateJavascript(js) { onResult(it) }
    }

    /**
     * youtube.com/tv search draws its own keyboard: there is no text field to fill, and
     * Backspace is its "back" key (holding delete on the phone walked all the way out).
     * So on the TV page the phone's text becomes the search itself: #/search?q=<text>,
     * which the page answers with results and shows in its search box. Applied once
     * the phone has been quiet for [SEARCH_DEBOUNCE_MS], or at once on Enter.
     */
    private var searchText = ""
    private val applySearch = Runnable { searchNow() }

    override fun editText(edit: CarLink.TextEdit, onResult: (Boolean) -> Unit) = onMain {
        val wv = webView ?: return@onMain onResult(false)
        if (edit == CarLink.TextEdit.Reset) {
            searchText = ""
            return@onMain onResult(true)
        }
        if (onTvPage()) return@onMain onResult(searchOnTv(edit))
        val call = when (edit) {
            is CarLink.TextEdit.Type -> WebKit.typeJs(edit.text)
            is CarLink.TextEdit.Set -> WebKit.setJs(edit.text)
            CarLink.TextEdit.Next -> WebKit.NEXT_FIELD_JS
            CarLink.TextEdit.Backspace -> WebKit.BACKSPACE_JS
            CarLink.TextEdit.Clear -> WebKit.CLEAR_JS
            CarLink.TextEdit.Enter -> WebKit.ENTER_JS
            CarLink.TextEdit.Reset -> ""
        }
        // The bridge is idempotent; injecting again covers pages that never finished
        // loading, and single-page apps that swapped their document.
        wv.evaluateJavascript(WebKit.KEYBOARD_BRIDGE_JS + "\n" + call) { result ->
            Log.d(TAG, "text edit ${edit::class.simpleName} -> $result")
            onResult(result?.contains("ok") == true)
        }
    }

    private fun onTvPage(): Boolean = webView?.url?.contains("youtube.com/tv") == true

    private fun searchOnTv(edit: CarLink.TextEdit): Boolean {
        searchText = when (edit) {
            is CarLink.TextEdit.Set -> edit.text
            is CarLink.TextEdit.Type -> searchText + edit.text
            CarLink.TextEdit.Backspace -> searchText.dropLast(1)
            CarLink.TextEdit.Clear -> ""
            CarLink.TextEdit.Enter -> searchText
            CarLink.TextEdit.Next, CarLink.TextEdit.Reset -> return false
        }
        mainHandler.removeCallbacks(applySearch)
        if (edit == CarLink.TextEdit.Enter) searchNow()
        else mainHandler.postDelayed(applySearch, SEARCH_DEBOUNCE_MS)
        return true
    }

    private fun searchNow() {
        mainHandler.removeCallbacks(applySearch)
        val q = searchText.trim()
        val hash = if (q.isEmpty()) "#/search" else "#/search?q=" + Uri.encode(q)
        webView?.evaluateJavascript("location.hash=${JSONObject.quote(hash)};", null)
    }

    /** The Screen is gone for good: now it really is time to destroy everything. */
    fun release() = onMain {
        Settings.removeListener(settingsListener)
        CarMediaSession.detach(mediaPage)
        if (CarLink.controller === this) CarLink.controller = null
        resetSwipe()
        rememberState()
        tearDownDisplay()
        surface?.release()
        surface = null
        CarLink.update { CarLink.Status() }
    }

    /**
     * Rebuilds the virtual display on the current surface, keeping the page. Needed
     * when the refresh rate changes: it is fixed at creation.
     */
    override fun rebuildDisplay() = rebuild(keepPage = true)

    /** @param keepPage false = come back on [webUrl] as set, not on the current page. */
    private fun rebuild(keepPage: Boolean) = onMain {
        val target = surface ?: return@onMain
        val width = builtWidth.takeIf { it > 0 } ?: displayWidth
        val height = builtHeight.takeIf { it > 0 } ?: displayHeight
        val dpi = builtDpi.takeIf { it > 0 } ?: Config.FALLBACK_DPI
        if (width <= 0 || height <= 0) return@onMain
        Log.i(TAG, "rebuilding display: refresh=${Settings.refreshRate} keepPage=$keepPage")
        if (keepPage) rememberState() else WebKit.flushCookies()
        tearDownDisplay()
        buildDisplay(target, width, height, dpi)
    }

    // ------------------------------------------------------------------ live settings

    private fun applySetting(key: String) {
        when (key) {
            Settings.Keys.REFRESH_RATE -> rebuildDisplay()
            // A fresh WebView, not a reload: a UA change on a live one did not stick,
            // and youtube.com/tv bounced to the desktop site.
            Settings.Keys.CUSTOM_UA, Settings.ALL_CHANGED -> {
                webUrl = Settings.startUrl
                rebuild(keepPage = false)
            }
            Settings.Keys.KEEP_AWAKE -> setKeepAwake(surface != null)
            Settings.Keys.PRE_RASTER -> webView?.settings?.offscreenPreRaster = Settings.preRaster
            Settings.Keys.INJECT_JS -> runInjectedJs()
            Settings.Keys.SWIPE_STEP -> Unit // read on every swipe
        }
    }

    private fun runInjectedJs() {
        val js = Settings.injectJs
        if (js.isNotBlank()) webView?.evaluateJavascript(js, null)
    }

    // ------------------------------------------------------------------ display setup

    private fun buildDisplay(target: Surface, width: Int, height: Int, dpi: Int) {
        displayWidth = width
        displayHeight = height

        val displayManager = carContext.getSystemService(DisplayManager::class.java)
        if (displayManager == null) {
            Log.e(TAG, "no DisplayManager")
            return
        }

        // PRESENTATION marks the display as one that should host Presentation windows,
        // which is what we are about to put on it.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        val refreshRate = Settings.refreshRate
        val vd = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                displayManager.createVirtualDisplay(
                    VirtualDisplayConfig.Builder(
                        VIRTUAL_DISPLAY_NAME, displayWidth, displayHeight, dpi
                    )
                        .setSurface(target)
                        .setFlags(flags)
                        .apply { if (refreshRate > 0f) setRequestedRefreshRate(refreshRate) }
                        .build(),
                    mainHandler,
                    displayCallback
                )
            } else {
                displayManager.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME, displayWidth, displayHeight, dpi, target, flags,
                    displayCallback, mainHandler
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }
        val display = vd?.display
        if (vd == null || display == null) {
            Log.e(TAG, "virtual display unavailable")
            vd?.release()
            return
        }
        virtualDisplay = vd

        try {
            val p = Presentation(carContext, display)
            // Read this BEFORE we force it on, so the log below says whether the
            // Presentation was already accelerated or whether this is the fix.
            val alreadyAccelerated = (p.window?.attributes?.flags ?: 0) and
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0
            p.window?.apply {
                // A Dialog built from a Service context does not reliably pick up the
                // manifest's android:hardwareAccelerated. It can only be turned on at
                // window level, never off, so forcing it is safe.
                addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                if (refreshRate > 0f) {
                    attributes = attributes.apply { preferredRefreshRate = refreshRate }
                }
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
            }

            val host = FrameLayout(p.context)

            p.setContentView(
                host,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            p.show()
            pinFrameRate(host, refreshRate)

            presentation = p
            webHost = host
            builtWidth = width
            builtHeight = height
            builtDpi = dpi
            setKeepAwake(true)
            logRenderingSetup(display, dpi, alreadyAccelerated)
            CarLink.update {
                it.copy(
                    onScreen = true,
                    surfaceWidth = width,
                    surfaceHeight = height,
                    dpi = dpi,
                    refreshRate = display.refreshRate
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not show Presentation on the virtual display", e)
            tearDownDisplay()
            return
        }

        attachWebView()
        // Created after the WebView so it draws on top of it. Same physical size on
        // any head unit: a fraction of the screen height.
        overlay = webHost?.let { host ->
            RemoteOverlay(
                host,
                keySize = (displayHeight * REMOTE_KEY_FRACTION).toInt(),
                margin = (displayHeight * REMOTE_MARGIN_FRACTION).toInt(),
                onKey = { keyCode -> sendKeyCode(keyCode) }
            )
        }
    }

    /**
     * The system PAUSES a virtual display when the phone's display group sleeps, and a
     * paused display gets no more buffers: the car picture freezes while audio keeps
     * playing. There is no public API to give a virtual display its own display group,
     * so the lever is keeping the phone awake - but only while our surface is actually
     * on the car screen, never while you are on Maps.
     */
    private fun setKeepAwake(on: Boolean) {
        presentation?.window?.let { window ->
            if (on && Settings.keepAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ------------------------------------------------------------------ input

    /** youtube.com/tv is driven by key presses: send it real key events. */
    private fun sendKeyCode(keyCode: Int) {
        val wv = webView ?: return
        wv.requestFocus()
        val now = SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        wv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun resetSwipe() {
        mainHandler.removeCallbacks(endSwipe)
        swipeX = 0f
        swipeY = 0f
    }

    // ------------------------------------------------------- virtual display state

    /** Tells us, in logcat, exactly when frames stop reaching the car and why. */
    private val displayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            if (surface == null) {
                Log.i(TAG, "virtual display paused: surface detached (another screen is showing)")
            } else {
                Log.w(
                    TAG,
                    "virtual display PAUSED by the system while on screen - the car picture " +
                        "is frozen. The phone went to sleep " +
                        "(keep_awake=${Settings.keepAwake})."
                )
            }
        }

        override fun onResumed() {
            Log.i(TAG, "virtual display resumed")
            webView?.invalidate()
        }

        override fun onStopped() {
            Log.w(TAG, "virtual display stopped")
        }
    }

    // ------------------------------------------------------------------------ WebView

    private fun attachWebView() {
        val host = webHost ?: return
        if (webView != null) return

        val context = presentation?.context ?: carContext
        val wv = WebView(context)
        WebKit.configure(wv, offscreenPreRaster = Settings.preRaster)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = WebKit.shouldBlockNavigation(request)

            override fun onPageFinished(view: WebView, url: String) {
                if (url.isNotBlank() && url != BLANK_URL) {
                    webUrl = url
                    CarLink.update { it.copy(url = url) }
                }
                view.evaluateJavascript(WebKit.FIT_VIDEO_JS, null)
                view.evaluateJavascript(WebKit.KEYBOARD_BRIDGE_JS, null)
                runInjectedJs()
                WebKit.flushCookies()
            }
        }
        chromeClient = FullscreenWebChromeClient(
            host = host,
            webView = { webView }
        ).also { wv.webChromeClient = it }

        host.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webView = wv
        pinFrameRate(wv, Settings.refreshRate)
        wv.requestFocus()
        wv.loadUrl(webUrl)
    }

    /**
     * The UI thread has ONE Choreographer, and it ticks with the phone's own panel
     * (120 Hz on this phone), not with the virtual display. Left alone the car page is
     * redrawn ~120 times a second.
     *
     * An explicit vote (API 35+), together with the window's preferredRefreshRate,
     * gets our frames paced to the requested rate. Measured with [Harness]: 24 Hz ->
     * 24 frames/s, 30 Hz -> 30, unpinned -> 120. In the car, though, unpinned was the
     * smooth one (see [Config.DEFAULT_REFRESH_RATE]).
     *
     * Chromium's own HIGH-category vote reaches View through WebView's private
     * super_ path, so subclassing WebView to intercept it does nothing.
     */
    private fun pinFrameRate(view: View, rate: Float) {
        if (rate <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        view.setRequestedFrameRate(rate)
    }

    private fun detachWebView() {
        chromeClient?.exit()
        chromeClient = null
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl(BLANK_URL)
            wv.destroy()
        }
        webView = null
    }

    // ------------------------------------------------------------------------ teardown

    private fun rememberState() {
        webView?.url?.let { if (it.isNotBlank() && it != BLANK_URL) webUrl = it }
        WebKit.flushCookies()
    }

    /** Destroys the display and everything on it. The host's surface is left alone. */
    private fun tearDownDisplay() {
        resetSwipe()
        overlay?.release()
        overlay = null
        detachWebView()
        webHost = null
        presentation?.let { p -> runCatching { p.dismiss() } }
        presentation = null
        virtualDisplay?.let { vd -> runCatching { vd.release() } }
        virtualDisplay = null
        builtWidth = 0
        builtHeight = 0
        builtDpi = 0
    }

    /** One line in logcat that answers "what are we actually rendering, and how". */
    private fun logRenderingSetup(display: Display, dpi: Int, alreadyAccelerated: Boolean) {
        Log.i(
            TAG,
            "surface=${displayWidth}x$displayHeight " +
                "dpi=$dpi requestedRefresh=${Settings.refreshRate} " +
                "refreshRate=${display.refreshRate} " +
                "hwAccelWasAlreadyOn=$alreadyAccelerated (now forced on)"
        )
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private companion object {
        const val TAG = "CarVideoRenderer"
        const val VIRTUAL_DISPLAY_NAME = "CarVideoDisplay"
        const val BLANK_URL = "about:blank"

        /** Quiet time after the last onScroll before leftover travel is dropped. */
        const val SWIPE_END_DELAY_MS = 200L

        /** Quiet time after the last phone keystroke before the TV search updates. */
        const val SEARCH_DEBOUNCE_MS = 600L

        /** A host click this soon after a swipe belongs to that swipe; drop it. */
        const val CLICK_AFTER_SWIPE_GUARD_MS = 200L

        /** One remote key, as a share of the car screen height. */
        const val REMOTE_KEY_FRACTION = 0.105f

        /** Gap between the remote and the screen edge, as a share of the height. */
        const val REMOTE_MARGIN_FRACTION = 0.03f
    }
}
