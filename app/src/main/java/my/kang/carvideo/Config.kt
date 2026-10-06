package my.kang.carvideo

/** Everything you might want to tweak lives here. */
object Config {

    enum class Site { MOBILE, TV }

    /**
     * MOBILE = m.youtube.com, touch driven. Known good.
     *
     * TV = youtube.com/tv, the interface every smart TV and console uses. Worth
     * trying because it is built for weak TV SoCs, lays out for a 16:9 landscape
     * screen, and signs in with a pairing code instead of a web form - which dodges
     * Google's "this browser may not be secure" block entirely. It is driven by a
     * D-pad, not touch, so the input panel switches to a D-pad (the "方向键" key).
     * Needs [TV_USER_AGENT] or YouTube redirects you back to the normal site.
     */
    val SITE = Site.MOBILE

    val START_URL: String
        get() = when (SITE) {
            Site.MOBILE -> "https://m.youtube.com"
            Site.TV -> "https://www.youtube.com/tv"
        }

    /** youtube.com/tv only serves the TV interface to something that looks like a TV. */
    const val TV_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36"

    /**
     * Keep the phone awake while the car surface is alive.
     *
     * The system PAUSES a virtual display when the phone's display group sleeps, and
     * a paused virtual display receives no further buffers - the car picture freezes
     * while audio keeps playing, because audio is a separate pipeline. There is no
     * public API to put a virtual display in its own display group, so the only way
     * to keep frames flowing is to stop the phone from sleeping.
     *
     * Cost: the phone screen stays on while projecting. Set false if you would rather
     * have the battery and accept that video freezes when you lock the phone.
     */
    const val KEEP_PHONE_AWAKE = true

    /**
     * Multiplier applied to SurfaceCallback.onScroll distances. The drag is replayed as
     * one continuous gesture now, so 1:1 already feels natural; raise it if you want
     * each swipe to travel further.
     */
    const val SCROLL_GAIN = 1.0f

    /** Fallback density if the host reports a bogus dpi for the car surface. */
    const val FALLBACK_DPI = 160

    /** Share of the car screen height taken by the on-screen keyboard when it is up. */
    const val KEYBOARD_HEIGHT_FRACTION = 0.46f

    /**
     * Render the car screen at this fraction of its native pixel size; the Surface
     * scales the result back up. 1.0f = native.
     *
     * Rasterising and compositing cost scales with pixel count, and on a wide head
     * unit that is the dominant per-frame cost - which is why playback can stutter
     * identically at 360p and 1080p. If it still stutters after the other fixes,
     * try 0.8f or 0.75f: noticeably softer, noticeably smoother.
     *
     * Touch coordinates are rescaled accordingly, so taps stay aligned.
     */
    const val RENDER_SCALE = 1.0f

    /** Refresh rate asked of the virtual display (Android 14+ only). */
    const val REQUESTED_REFRESH_RATE = 60f

    /**
     * Leave empty to take the system WebView's own user agent and strip the two
     * tokens Google uses to detect an embedded WebView ("; wv" and "Version/x.y").
     *
     * If Google still refuses to sign you in ("This browser or app may not be
     * secure"), put a full desktop Chrome UA string here instead, e.g.
     * "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
     */
    const val FORCE_USER_AGENT = ""
}
