package my.kang.carvideo

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * The phone screen and adb talk to the car screen through here.
 *
 * [CarVideoRenderer] publishes what it is showing into [status] and registers itself
 * as the [controller] while it has a display; everyone else reads the status and
 * sends commands without holding a reference to the renderer.
 */
object CarLink {

    data class Status(
        /** The host has given us a surface, i.e. our screen is on the car right now. */
        val onScreen: Boolean = false,
        /** The renderer exists (Android Auto session is running our app). */
        val alive: Boolean = false,
        val surfaceWidth: Int = 0,
        val surfaceHeight: Int = 0,
        val dpi: Int = 0,
        val refreshRate: Float = 0f,
        val url: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("on_screen", onScreen)
            put("alive", alive)
            put("surface", "${surfaceWidth}x$surfaceHeight")
            put("dpi", dpi)
            put("refresh_rate", refreshRate.toDouble())
            put("url", url)
        }
    }

    interface Controller {
        fun reload()
        fun goHome()
        fun goBack()
        fun loadUrl(url: String)
        fun rebuildDisplay()
        fun toggleRemote()
        /** A real key press into the page; youtube.com/tv is driven by D-pad keys. */
        fun sendKey(keyCode: Int)

        /**
         * Edit the car page's focused text field from the phone keyboard.
         * [onResult] gets false when no text field is selected on the car screen.
         */
        fun editText(edit: TextEdit, onResult: (Boolean) -> Unit = {})

        /** Debugging: run [js] in the car page and hand back its JSON-encoded result. */
        fun evalJs(js: String, onResult: (String?) -> Unit)
    }

    sealed interface TextEdit {
        data class Type(val text: String) : TextEdit
        /** Replace the field's whole value: keeps it in sync with the phone's field. */
        data class Set(val text: String) : TextEdit
        /** Move to the next text field (username -> password). */
        data object Next : TextEdit
        data object Backspace : TextEdit
        data object Clear : TextEdit
        data object Enter : TextEdit
        /** The phone keyboard opened: forget what was typed as keys before. */
        data object Reset : TextEdit
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Status) -> Unit>()

    @Volatile
    var status = Status()
        private set

    @Volatile
    var controller: Controller? = null

    fun update(transform: (Status) -> Status) {
        status = transform(status)
        val snapshot = status
        main.post { listeners.toList().forEach { it(snapshot) } }
    }

    fun addListener(l: (Status) -> Unit) {
        listeners += l
        l(status)
    }

    fun removeListener(l: (Status) -> Unit) {
        listeners -= l
    }
}
