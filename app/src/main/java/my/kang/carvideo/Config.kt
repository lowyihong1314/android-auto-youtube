package my.kang.carvideo

/**
 * Fixed constants and the DEFAULT value of every runtime setting.
 *
 * Everything a user might want to tweak is a [Settings] entry, editable on the phone
 * screen or over adb (see [DebugReceiver]). This file only says what a fresh install
 * starts with.
 */
object Config {

    /**
     * youtube.com/tv, the interface every smart TV and console uses: built for weak TV
     * SoCs, laid out for a 16:9 landscape screen, video fullscreen by default, signs in
     * with a pairing code instead of a web form. Driven by D-pad keys, not touch.
     */
    const val TV_URL = "https://www.youtube.com/tv"

    /**
     * youtube.com/tv only serves the TV interface to something that looks like a TV;
     * anything else is bounced to the desktop site. This one is a Samsung Tizen set,
     * which is also the name the car shows up under in your account's devices.
     */
    const val TV_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36"

    /** Fallback density if the host reports a bogus dpi for the car surface. */
    const val FALLBACK_DPI = 160

    // ------------------------------------------------------------ setting defaults

    /**
     * Refresh rate for the virtual display, and the rate our frames are pinned to.
     * 0 = leave both alone, which measured smoothest in the car: the page renders at
     * the phone panel's rate and the display always has a fresh frame to sample.
     * Pinning (24/30/60) cuts render work ~4x but the two clocks beat against each
     * other, and the head unit got visibly uneven frames.
     */
    const val DEFAULT_REFRESH_RATE = 0f

    /** Finger travel on the car screen, in pixels, that counts as one arrow key. */
    const val DEFAULT_SWIPE_STEP_PX = 140f

    /**
     * Keep the phone awake while the car surface is alive.
     *
     * The system PAUSES a virtual display when the phone's display group sleeps, and a
     * paused virtual display receives no further buffers - the car picture freezes
     * while audio keeps playing. There is no public API to put a virtual display in its
     * own display group, so the only way to keep frames flowing is to stop the phone
     * from sleeping.
     */
    const val DEFAULT_KEEP_AWAKE = true

    /**
     * The car WebView's window lives on a virtual display, which the framework can
     * treat as not-visible and stop rasterising tiles for; pre-raster keeps them
     * coming at the cost of some memory.
     */
    const val DEFAULT_PRE_RASTER = true
}
