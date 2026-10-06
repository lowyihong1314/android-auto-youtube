package my.kang.carvideo

import android.app.Presentation
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import kotlin.math.hypot

/**
 * Owns everything drawn on the car screen.
 *
 * The host hands us a plain Surface, which cannot host a View hierarchy, so we
 * wrap it in a VirtualDisplay and put a Presentation on that display:
 *
 *     Presentation
 *       └─ column (vertical LinearLayout)
 *            ├─ webHost (weight 1)   WebView, or YouTube's fullscreen view
 *            └─ keyboard             CarKeyboardView, GONE until asked for
 *
 * The VirtualDisplay, the Presentation and the WebView OUTLIVE the Surface. When the
 * host takes the surface away (you switch to Maps, the car screen sleeps) we only
 * detach it from the display; when it comes back we reattach it. The page, the video
 * position and the sign-in all survive, and the picture is back instantly instead of
 * reloading YouTube. Only a change of size or density forces a rebuild.
 *
 * Touch does not arrive through the Surface either, so onClick/onScroll are replayed
 * as touchscreen events into the Presentation.
 *
 * Everything here runs on the main thread (see [onMain]).
 */
class CarVideoRenderer(private val carContext: CarContext) : SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webHost: FrameLayout? = null
    private var keyboard: CarKeyboardView? = null
    private var webView: WebView? = null
    private var chromeClient: FullscreenWebChromeClient? = null

    /** The host's surface currently feeding the virtual display, if any. */
    private var surface: Surface? = null

    /** What the surface looked like when the display was built; a change forces a rebuild. */
    private var builtWidth = 0
    private var builtHeight = 0
    private var builtDpi = 0

    /** Size of the Surface the host gave us. Touch arrives in these coordinates. */
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Size we actually render at; equal to the surface unless RENDER_SCALE < 1. */
    private var displayWidth = 0
    private var displayHeight = 0
    private var touchScaleX = 1f
    private var touchScaleY = 1f

    /**
     * One drag spans many onScroll callbacks; see [onScroll].
     *
     * [dragActive] means a gesture is in progress; [dragDownTime] is 0 until the DOWN
     * has actually been sent, which we hold back until the finger has moved past the
     * touch slop.
     */
    private var dragActive = false
    private var dragDownTime = 0L
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var lastDragEndAt = 0L
    private var touchSlop = DEFAULT_TOUCH_SLOP_PX
    private val finishDrag = Runnable { endDrag() }

    /** Remembered across a full rebuild. */
    private var webUrl = Config.START_URL

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
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = onMain {
        endDrag()
        // Detach, don't destroy: the page and the video keep their state, and audio keeps
        // playing while you are on another Android Auto screen.
        virtualDisplay?.surface = null
        setKeepAwake(false)
        surface?.release()
        surface = null
        WebKit.flushCookies()
    }

    /** A tap, replayed as a real finger: DOWN now, UP a moment later. */
    override fun onClick(x: Float, y: Float) = onMain {
        if (presentation == null) return@onMain
        // Some hosts report a click as part of a drag, or right as it ends. Replaying it
        // would open whatever happens to be under the finger - "dragging triggers a tap".
        val sinceDrag = SystemClock.uptimeMillis() - lastDragEndAt
        if (dragActive || sinceDrag < CLICK_AFTER_DRAG_GUARD_MS) {
            Log.d(TAG, "ignoring click during/just after a drag (${sinceDrag}ms)")
            return@onMain
        }
        // Touch arrives in car-surface coordinates; the views live on the virtual
        // display, which is smaller when RENDER_SCALE < 1.
        val tx = x * touchScaleX
        val ty = y * touchScaleY
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(MotionEvent.ACTION_DOWN, tx, ty, downTime, downTime)
        mainHandler.postDelayed({
            dispatchTouch(MotionEvent.ACTION_UP, tx, ty, downTime, SystemClock.uptimeMillis())
        }, TAP_DURATION_MS)
    }

    /**
     * The host reports a swipe as a burst of onScroll callbacks. Replaying each as its
     * own DOWN/MOVE/UP throws away the velocity, so the page lurches and never flings.
     * Instead keep ONE gesture open: DOWN, a MOVE per callback, and UP when it ends.
     *
     * The DOWN is held back until the accumulated movement is past the touch slop. A
     * replayed drag that never left the slop would reach the WebView as "pressed and
     * released in place" - a TAP on whatever sits in the middle of the screen. Holding
     * the DOWN back means a drag can never be mistaken for one: either it moves far
     * enough to be an unambiguous scroll, or nothing is sent at all.
     */
    override fun onScroll(distanceX: Float, distanceY: Float) = onMain {
        if (presentation == null || displayWidth <= 2 || displayHeight <= 2) return@onMain
        val now = SystemClock.uptimeMillis()
        if (!dragActive) {
            dragActive = true
            dragDownTime = 0L
            dragStartX = displayWidth / 2f
            dragStartY = displayHeight / 2f
            dragX = dragStartX
            dragY = dragStartY
        }
        dragX = (dragX - distanceX * Config.SCROLL_GAIN * touchScaleX)
            .coerceIn(1f, displayWidth - 1f)
        dragY = (dragY - distanceY * Config.SCROLL_GAIN * touchScaleY)
            .coerceIn(1f, displayHeight - 1f)

        if (dragDownTime == 0L) {
            val moved = hypot(dragX - dragStartX, dragY - dragStartY)
            if (moved > touchSlop + SLOP_MARGIN_PX) {
                // Back-date the DOWN by a frame so the first MOVE has a sane time delta
                // for the velocity tracker, instead of a jump in zero milliseconds.
                dragDownTime = now - DOWN_BACKDATE_MS
                dispatchTouch(MotionEvent.ACTION_DOWN, dragStartX, dragStartY, dragDownTime, dragDownTime)
                dispatchTouch(MotionEvent.ACTION_MOVE, dragX, dragY, dragDownTime, now)
            }
        } else {
            dispatchTouch(MotionEvent.ACTION_MOVE, dragX, dragY, dragDownTime, now)
        }

        mainHandler.removeCallbacks(finishDrag)
        mainHandler.postDelayed(finishDrag, DRAG_END_DELAY_MS)
    }

    /**
     * The finger left the screen moving fast. Lift NOW: waiting out DRAG_END_DELAY_MS
     * would show the velocity tracker a finger that came to rest, and the fling would
     * die. Lifting right after the last MOVE keeps the momentum.
     */
    override fun onFling(velocityX: Float, velocityY: Float) = onMain {
        endDrag()
    }

    // ------------------------------------------------------------------ public actions

    /** ActionStrip "键盘": show or hide the on-screen keyboard. */
    fun toggleKeyboard() = onMain {
        val keys = keyboard ?: return@onMain
        keys.visibility = if (keys.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** ActionStrip "主页": back to the YouTube start page. */
    fun goHome() = onMain {
        hideKeyboard()
        chromeClient?.exit()
        webUrl = Config.START_URL
        webView?.loadUrl(Config.START_URL)
    }

    /** ActionStrip "刷新": reload, picking up a sign-in that just happened on the phone. */
    fun reload() = onMain {
        chromeClient?.exit()
        webView?.reload()
    }

    /** ActionStrip "返回": leave fullscreen, hide the keyboard, or go back in history. */
    fun goBack() = onMain {
        when {
            chromeClient?.isFullscreen == true -> chromeClient?.exit()
            keyboard?.visibility == View.VISIBLE -> hideKeyboard()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> Log.d(TAG, "nothing to go back to")
        }
    }

    /** The Screen is gone for good: now it really is time to destroy everything. */
    fun release() = onMain {
        endDrag()
        rememberState()
        tearDownDisplay()
        surface?.release()
        surface = null
    }

    // ------------------------------------------------------------------ display setup

    private fun buildDisplay(target: Surface, width: Int, height: Int, dpi: Int) {
        surfaceWidth = width
        surfaceHeight = height
        val scale = Config.RENDER_SCALE.coerceIn(0.5f, 1f)
        displayWidth = (width * scale).toInt().coerceAtLeast(1)
        displayHeight = (height * scale).toInt().coerceAtLeast(1)
        touchScaleX = displayWidth.toFloat() / width
        touchScaleY = displayHeight.toFloat() / height

        val displayManager = carContext.getSystemService(DisplayManager::class.java)
        if (displayManager == null) {
            Log.e(TAG, "no DisplayManager")
            return
        }

        // PRESENTATION marks the display as one that should host Presentation windows,
        // which is what we are about to put on it.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        val vd = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Asking for a refresh rate pins the vsync cadence Choreographer paces
                // this display's windows to, instead of leaving it to the default.
                displayManager.createVirtualDisplay(
                    VirtualDisplayConfig.Builder(
                        VIRTUAL_DISPLAY_NAME, displayWidth, displayHeight, dpi
                    )
                        .setSurface(target)
                        .setFlags(flags)
                        .setRequestedRefreshRate(Config.REQUESTED_REFRESH_RATE)
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
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
            }

            // One opaque fill per frame: the window background is already black.
            val column = LinearLayout(p.context).apply { orientation = LinearLayout.VERTICAL }
            val host = FrameLayout(p.context)
            val keys = CarKeyboardView(p.context).apply { visibility = View.GONE }
            wireKeyboard(keys)

            column.addView(
                host,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            column.addView(
                keys,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (displayHeight * Config.KEYBOARD_HEIGHT_FRACTION).toInt()
                )
            )
            p.setContentView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            p.show()

            presentation = p
            webHost = host
            keyboard = keys
            // In virtual-display pixels, the same space our replayed coordinates live in.
            touchSlop = ViewConfiguration.get(p.context).scaledTouchSlop.toFloat()
            builtWidth = width
            builtHeight = height
            builtDpi = dpi
            setKeepAwake(true)
            logRenderingSetup(display, dpi, alreadyAccelerated)
        } catch (e: Exception) {
            Log.e(TAG, "could not show Presentation on the virtual display", e)
            tearDownDisplay()
            return
        }

        attachWebView()
    }

    /**
     * The system PAUSES a virtual display when the phone's display group sleeps, and a
     * paused display gets no more buffers: the car picture freezes while audio keeps
     * playing. There is no public API to give a virtual display its own display group,
     * so the lever is keeping the phone awake - but only while our surface is actually
     * on the car screen, never while you are on Maps.
     */
    private fun setKeepAwake(on: Boolean) {
        if (!Config.KEEP_PHONE_AWAKE) return
        presentation?.window?.let { window ->
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ------------------------------------------------------------------ touch replay

    /** One finger event shaped like a real touchscreen's, so the WebView treats it as one. */
    private fun dispatchTouch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val target = presentation?.window?.decorView ?: return
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            target.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun endDrag() {
        mainHandler.removeCallbacks(finishDrag)
        if (!dragActive) return
        dragActive = false
        lastDragEndAt = SystemClock.uptimeMillis()
        // Never got past the slop: nothing was sent, so there is nothing to lift.
        if (dragDownTime == 0L) return
        val downTime = dragDownTime
        dragDownTime = 0L
        dispatchTouch(MotionEvent.ACTION_UP, dragX, dragY, downTime, SystemClock.uptimeMillis())
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
                        "(KEEP_PHONE_AWAKE=${Config.KEEP_PHONE_AWAKE})."
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

    // ------------------------------------------------------------------------ keyboard

    private fun wireKeyboard(keys: CarKeyboardView) {
        keys.onText = { text -> runJs(WebKit.typeJs(text)) }
        keys.onBackspace = { runJs(WebKit.BACKSPACE_JS) }
        keys.onClear = { runJs(WebKit.CLEAR_JS) }
        keys.onEnter = {
            runJs(WebKit.ENTER_JS)
            hideKeyboard()
        }
        keys.onHide = { hideKeyboard() }
        keys.onKeyCode = { keyCode -> sendKeyCode(keyCode) }
    }

    /** youtube.com/tv is driven by arrow keys, so send it real key events. */
    private fun sendKeyCode(keyCode: Int) {
        val wv = webView ?: return
        wv.requestFocus()
        val now = SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        wv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun hideKeyboard() {
        keyboard?.visibility = View.GONE
    }

    private fun runJs(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    // ------------------------------------------------------------------------ WebView

    private fun attachWebView() {
        val host = webHost ?: return
        if (webView != null) return

        val context = presentation?.context ?: carContext
        val wv = WebView(context)
        WebKit.configure(wv, offscreenPreRaster = true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = WebKit.shouldBlockNavigation(request)

            override fun onPageFinished(view: WebView, url: String) {
                if (url.isNotBlank() && url != BLANK_URL) webUrl = url
                // Re-injected per page: the bridge lives in the page's JS context.
                view.evaluateJavascript(WebKit.KEYBOARD_BRIDGE_JS, null)
                WebKit.flushCookies()
            }
        }
        chromeClient = FullscreenWebChromeClient(
            host = host,
            webView = { webView },
            onChanged = { fullscreen -> if (fullscreen) hideKeyboard() }
        ).also { wv.webChromeClient = it }

        host.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webView = wv
        wv.loadUrl(webUrl)
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
        endDrag()
        detachWebView()
        keyboard = null
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
            "surface=${surfaceWidth}x$surfaceHeight render=${displayWidth}x$displayHeight " +
                "scale=${Config.RENDER_SCALE} dpi=$dpi refreshRate=${display.refreshRate} " +
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

        /** How long a replayed tap holds the finger down. */
        const val TAP_DURATION_MS = 60L

        /** Quiet time after the last onScroll before the replayed finger lifts. */
        const val DRAG_END_DELAY_MS = 150L

        /** A host click this soon after a drag ended belongs to that drag; drop it. */
        const val CLICK_AFTER_DRAG_GUARD_MS = 200L

        /** Required movement beyond the touch slop before the DOWN is sent. */
        const val SLOP_MARGIN_PX = 2f

        /** Used until the presentation exists and the real slop can be read. */
        const val DEFAULT_TOUCH_SLOP_PX = 16f

        /** How far before the first MOVE the replayed DOWN is timestamped. */
        const val DOWN_BACKDATE_MS = 16L
    }
}
