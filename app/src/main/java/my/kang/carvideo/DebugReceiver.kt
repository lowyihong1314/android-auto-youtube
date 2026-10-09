package my.kang.carvideo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.car.app.SurfaceCallback
import org.json.JSONObject

/**
 * Tune the Play-installed build from a computer, no rebuild, no upload.
 *
 * Protected by android.permission.DUMP in the manifest: only the adb shell (and the
 * system) holds it, so no other app on the phone can reach this.
 *
 *   PKG=com.mediaplayerlow.app/my.kang.carvideo.DebugReceiver
 *   adb shell am broadcast -n $PKG -a my.kang.carvideo.DEBUG --es cmd get
 *   adb shell am broadcast -n $PKG -a my.kang.carvideo.DEBUG --es cmd set --es key refresh_rate --es value 30
 *   adb shell am broadcast -n $PKG -a my.kang.carvideo.DEBUG --es cmd open --es value https://example.com
 *
 * cmd: get | set | reset | reload | home | back | rebuild | remote | open
 *      key up|down|left|right|enter|back|playpause|<keycode>  (real key press)
 *      tap X,Y | swipe DX,DY  (replays head-unit touch input)
 *      eval JS  (runs JS in the car page; its result comes back as result data)
 *      harness start|stop (debug builds: run the car pipeline on the phone, see [Harness])
 * The reply (settings + car status as JSON) comes back as the broadcast's result
 * data, which `am broadcast` prints, and is also logged under the CarVideoDebug tag.
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Settings.init(context)
        val cmd = intent.getStringExtra("cmd") ?: "get"
        if (cmd == "eval") return eval(intent.getStringExtra("value"))
        val key = intent.getStringExtra("key")
        val value = intent.getStringExtra("value")
        val car = CarLink.controller

        // Runs [action] on the live car screen, or reports that there is none.
        fun onCar(action: (CarLink.Controller) -> Unit): String? {
            if (car == null) return NO_CAR
            action(car)
            return null
        }

        var cookieReport: JSONObject? = null
        val error: String? = when (cmd) {
            "get" -> null
            "set" -> if (key == null || value == null) "set needs --es key K --es value V"
            else Settings.setFromString(key, value)
            "reset" -> { Settings.reset(); null }
            "reload" -> onCar { it.reload() }
            "home" -> onCar { it.goHome() }
            "back" -> onCar { it.goBack() }
            "rebuild" -> onCar { it.rebuildDisplay() }
            "remote" -> onCar { it.toggleRemote() }
            "open" -> {
                val url = value?.let(Settings::normalizeUrl)
                if (url == null) "open needs --es value URL" else onCar { it.loadUrl(url) }
            }
            "key" -> {
                val code = value?.let(::keyCode)
                if (code == null) "key needs --es value up|down|left|right|enter|back|playpause|<keycode>"
                else onCar { it.sendKey(code) }
            }
            // Replays car touch input, as the head unit would deliver it.
            "tap" -> {
                val p = xy(value)
                if (p == null) "tap needs --es value X,Y"
                else onCar { (it as? SurfaceCallback)?.onClick(p.first, p.second) }
            }
            "swipe" -> {
                val d = xy(value)
                if (d == null) "swipe needs --es value DX,DY"
                else onCar { car ->
                    val cb = car as? SurfaceCallback ?: return@onCar
                    // onScroll distances are "previous minus current": a finger moving
                    // by (dx, dy) arrives as negative distances, in a burst of steps.
                    repeat(SWIPE_STEPS) {
                        cb.onScroll(-d.first / SWIPE_STEPS, -d.second / SWIPE_STEPS)
                    }
                }
            }
            "type" -> if (value == null) "type needs --es value TEXT"
            else onCar { it.editText(CarLink.TextEdit.Type(value)) }
            "enter" -> onCar { it.editText(CarLink.TextEdit.Enter) }
            "settext" -> onCar { it.editText(CarLink.TextEdit.Set(value.orEmpty())) }
            "clear" -> onCar { it.editText(CarLink.TextEdit.Clear) }
            "next" -> onCar { it.editText(CarLink.TextEdit.Next) }
            "cookies" -> {
                val url = value?.let(Settings::normalizeUrl) ?: "https://www.youtube.com"
                cookieReport = JSONObject().put(url, CookieJar.cookies(url).joinToString(",") { it.name })
                null
            }
            "harness" -> when {
                !Harness.isAllowed(context) -> "harness is debug-build only"
                value == "stop" -> { Harness.stop(); null }
                else -> { Harness.start(context); null }
            }
            else -> "unknown cmd '$cmd'"
        }

        val reply = JSONObject().apply {
            put("ok", error == null)
            if (error != null) put("error", error)
            cookieReport?.let { put("cookies", it) }
            put("settings", Settings.toJson())
            put("car", CarLink.status.toJson())
        }.toString()
        Log.i(TAG, "$cmd ${key.orEmpty()} ${value.orEmpty()} -> $reply")
        // `am broadcast` sends ordered and prints the result data.
        if (isOrderedBroadcast) resultData = reply
    }

    /**
     * `--es cmd eval --es value JS`: the page's answer becomes the broadcast's result
     * data. Asynchronous, so the broadcast is held open (goAsync) until it arrives.
     */
    private fun eval(js: String?) {
        val car = CarLink.controller
        if (js.isNullOrBlank() || car == null) {
            if (isOrderedBroadcast) resultData = if (car == null) NO_CAR else "eval needs --es value JS"
            return
        }
        val pending = goAsync()
        car.evalJs(js) { result ->
            Log.i(TAG, "eval -> $result")
            pending.resultData = result
            pending.finish()
        }
    }

    private fun xy(value: String?): Pair<Float, Float>? {
        val parts = value?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    private fun keyCode(name: String): Int? = when (name.lowercase()) {
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "enter", "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
        // youtube.com/tv takes Escape as back; KEYCODE_BACK does nothing there.
        "back", "esc" -> KeyEvent.KEYCODE_ESCAPE
        "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else -> name.toIntOrNull()
    }

    private companion object {
        const val TAG = "CarVideoDebug"
        const val NO_CAR = "car screen not running (open Car Video on the head unit first)"
        const val SWIPE_STEPS = 6
    }
}
