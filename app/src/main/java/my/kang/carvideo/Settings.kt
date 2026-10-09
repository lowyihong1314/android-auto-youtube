package my.kang.carvideo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Every runtime-tunable parameter, persisted in SharedPreferences.
 *
 * Three writers: the phone settings screen, [DebugReceiver] (adb), and [reset].
 * One reader that cares about changes as they happen: [CarVideoRenderer], which
 * applies them to the live car screen without a reconnect.
 */
object Settings {

    /** Preference keys; also the names accepted over adb. */
    object Keys {
        const val CUSTOM_UA = "custom_ua"
        const val REFRESH_RATE = "refresh_rate"
        const val SWIPE_STEP = "swipe_step"
        const val KEEP_AWAKE = "keep_awake"
        const val PRE_RASTER = "pre_raster"
        const val INJECT_JS = "inject_js"

        val ALL = listOf(CUSTOM_UA, REFRESH_RATE, SWIPE_STEP, KEEP_AWAKE, PRE_RASTER, INJECT_JS)
    }

    private lateinit var prefs: SharedPreferences

    /** Strong refs: SharedPreferences only holds its listeners weakly. */
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------ values

    /** Empty = [Config.TV_USER_AGENT]. */
    var customUa: String
        get() = prefs.getString(Keys.CUSTOM_UA, "").orEmpty()
        set(value) = prefs.edit().putString(Keys.CUSTOM_UA, value.trim()).apply()

    /** 0 = let the system choose. */
    var refreshRate: Float
        get() = prefs.getFloat(Keys.REFRESH_RATE, Config.DEFAULT_REFRESH_RATE)
        set(value) = prefs.edit().putFloat(Keys.REFRESH_RATE, value.coerceIn(0f, 120f)).apply()

    var swipeStep: Float
        get() = prefs.getFloat(Keys.SWIPE_STEP, Config.DEFAULT_SWIPE_STEP_PX).coerceIn(MIN_STEP, MAX_STEP)
        set(value) = prefs.edit().putFloat(Keys.SWIPE_STEP, value.coerceIn(MIN_STEP, MAX_STEP)).apply()

    var keepAwake: Boolean
        get() = prefs.getBoolean(Keys.KEEP_AWAKE, Config.DEFAULT_KEEP_AWAKE)
        set(value) = prefs.edit().putBoolean(Keys.KEEP_AWAKE, value).apply()

    var preRaster: Boolean
        get() = prefs.getBoolean(Keys.PRE_RASTER, Config.DEFAULT_PRE_RASTER)
        set(value) = prefs.edit().putBoolean(Keys.PRE_RASTER, value).apply()

    /** Run in the car WebView after every page load. Empty = nothing. */
    var injectJs: String
        get() = prefs.getString(Keys.INJECT_JS, "").orEmpty()
        set(value) = prefs.edit().putString(Keys.INJECT_JS, value).apply()

    // ------------------------------------------------------------------ derived

    val userAgent: String
        get() = customUa.ifBlank { Config.TV_USER_AGENT }

    val startUrl: String
        get() = Config.TV_URL

    fun normalizeUrl(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
    }

    // ------------------------------------------------------------------ listeners

    /** [onChanged] gets [ALL_CHANGED] after [reset] (a clear() reports a null key). */
    fun addListener(onChanged: (key: String) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onChanged(key ?: ALL_CHANGED) }
        listeners += l
        prefs.registerOnSharedPreferenceChangeListener(l)
        return l
    }

    fun removeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(l)
        listeners -= l
    }

    // ------------------------------------------------------------------ adb surface

    /**
     * Set one setting from its string form, as typed in an adb command.
     * @return null on success, or why it was rejected.
     */
    fun setFromString(key: String, value: String): String? {
        fun float(): Float? = value.toFloatOrNull()
        fun bool(): Boolean? = when (value.lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> null
        }
        when (key) {
            Keys.CUSTOM_UA -> customUa = value
            Keys.REFRESH_RATE -> refreshRate = float() ?: return "refresh_rate must be a number"
            Keys.SWIPE_STEP -> swipeStep = float() ?: return "swipe_step must be a number"
            Keys.KEEP_AWAKE -> keepAwake = bool() ?: return "keep_awake must be true/false"
            Keys.PRE_RASTER -> preRaster = bool() ?: return "pre_raster must be true/false"
            Keys.INJECT_JS -> injectJs = value
            else -> return "unknown key '$key'; known: ${Keys.ALL}"
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(Keys.CUSTOM_UA, customUa)
        put(Keys.REFRESH_RATE, round2(refreshRate))
        put(Keys.SWIPE_STEP, round2(swipeStep))
        put(Keys.KEEP_AWAKE, keepAwake)
        put(Keys.PRE_RASTER, preRaster)
        put(Keys.INJECT_JS, injectJs)
        put("user_agent", userAgent)
    }

    private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0

    fun reset() {
        prefs.edit().clear().apply()
    }

    const val ALL_CHANGED = "*"
    const val MIN_STEP = 40f
    const val MAX_STEP = 400f
    private const val PREFS_NAME = "car_video_settings"
}
