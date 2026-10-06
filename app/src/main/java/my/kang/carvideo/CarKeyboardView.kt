package my.kang.carvideo

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * On-screen keyboard drawn into the car Presentation.
 *
 * The system IME never shows on an app-owned VirtualDisplay, so if we want text
 * entry on the car screen we have to draw the keys ourselves and push the
 * characters into the page.
 *
 * Keys are deliberately NOT focusable. If a key took Android focus the WebView
 * would lose it and the page's focused <input> would blur - which is precisely
 * the element we are trying to type into.
 */
class CarKeyboardView(context: Context) : LinearLayout(context) {

    var onText: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onHide: (() -> Unit)? = null

    /** Raw key codes, for driving the youtube.com/tv interface, which wants a D-pad. */
    var onKeyCode: ((Int) -> Unit)? = null

    private enum class Layout { LETTERS, SYMBOLS, DPAD }

    private var shifted = false
    private var layout = if (Config.SITE == Config.Site.TV) Layout.DPAD else Layout.LETTERS

    init {
        orientation = VERTICAL
        setBackgroundColor(BACKGROUND)
        val pad = dp(4f)
        setPadding(pad, pad, pad, pad)
        isFocusable = false
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        when (layout) {
            Layout.DPAD -> buildDpad()
            Layout.SYMBOLS -> buildSymbols()
            Layout.LETTERS -> buildLetters()
        }
    }

    /**
     * youtube.com/tv listens for arrow keys, not taps, so this sends real key codes
     * into the WebView instead of synthesising touches.
     */
    private fun buildDpad() {
        val blank = { spacer() }
        addRow(listOf(blank(), keyCodeKey("▲", 2f, KeyEvent.KEYCODE_DPAD_UP), blank()))
        addRow(
            listOf(
                keyCodeKey("◀", 2f, KeyEvent.KEYCODE_DPAD_LEFT),
                keyCodeKey("OK", 2f, KeyEvent.KEYCODE_DPAD_CENTER, accent = true),
                keyCodeKey("▶", 2f, KeyEvent.KEYCODE_DPAD_RIGHT)
            )
        )
        addRow(
            listOf(
                keyCodeKey("返回", 2f, KeyEvent.KEYCODE_BACK),
                keyCodeKey("▼", 2f, KeyEvent.KEYCODE_DPAD_DOWN),
                keyCodeKey("播放/暂停", 2f, KeyEvent.KEYCODE_SPACE)
            )
        )
        addRow(
            listOf(
                actionKey("键盘", 2f) { layout = Layout.LETTERS; rebuild() },
                actionKey("收起", 2f) { onHide?.invoke() }
            )
        )
    }

    private fun buildSymbols() {
        addRow(SYMBOL_ROW_1.map { charKey(it) })
        addRow(SYMBOL_ROW_2.map { charKey(it) })
        addRow(
            listOf(actionKey("ABC", 1.8f) { layout = Layout.LETTERS; rebuild() }) +
                SYMBOL_ROW_3.map { charKey(it) } +
                listOf(actionKey("⌫", 1.8f) { onBackspace?.invoke() })
        )
        addBottomRow()
    }

    private fun buildLetters() {
        addRow(DIGITS.map { charKey(it) })
        addRow(LETTER_ROW_1.map { charKey(it) })
        addRow(LETTER_ROW_2.map { charKey(it) })
        addRow(
            listOf(actionKey(if (shifted) "⬆" else "⇧", 1.8f, shifted) { shifted = !shifted; rebuild() }) +
                LETTER_ROW_3.map { charKey(it) } +
                listOf(actionKey("⌫", 1.8f) { onBackspace?.invoke() })
        )
        addBottomRow()
    }

    private fun addBottomRow() {
        val toggle = if (layout == Layout.SYMBOLS) "ABC" else "?123"
        val keys = mutableListOf(
            actionKey(toggle, 1.6f) {
                layout = if (layout == Layout.SYMBOLS) Layout.LETTERS else Layout.SYMBOLS
                shifted = false
                rebuild()
            },
            charKey('@'),
            textKey("空格", 3.2f, " "),
            charKey('.'),
            actionKey("清空", 1.6f) { onClear?.invoke() },
            actionKey("⏎", 1.8f, accent = true) { onEnter?.invoke() }
        )
        if (Config.SITE == Config.Site.TV) {
            keys += actionKey("方向键", 1.8f) { layout = Layout.DPAD; rebuild() }
        }
        keys += actionKey("收起", 1.8f) { onHide?.invoke() }
        addRow(keys)
    }

    private fun spacer(): View = View(context).apply {
        isFocusable = false
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
    }

    private fun keyCodeKey(
        label: String,
        weight: Float,
        keyCode: Int,
        accent: Boolean = false
    ): View = key(
        label,
        weight,
        if (accent) ACCENT_BG else KEY_BG,
        KEY_TEXT,
        ACTION_TEXT_SP
    ) { onKeyCode?.invoke(keyCode) }

    private fun addRow(keys: List<View>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isFocusable = false
        }
        keys.forEach { row.addView(it) }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun charKey(c: Char): View {
        val label = if (shifted && layout == Layout.LETTERS) c.uppercaseChar().toString() else c.toString()
        return textKey(label, 1f, label)
    }

    private fun textKey(label: String, weight: Float, emit: String): View =
        key(label, weight, KEY_BG, KEY_TEXT, CHAR_TEXT_SP) {
            onText?.invoke(emit)
            // One-shot shift, like every phone keyboard.
            if (shifted && layout == Layout.LETTERS) {
                shifted = false
                rebuild()
            }
        }

    private fun actionKey(
        label: String,
        weight: Float,
        active: Boolean = false,
        accent: Boolean = false,
        onClick: () -> Unit
    ): View {
        val background = when {
            accent -> ACCENT_BG
            active -> ACTIVE_BG
            else -> ACTION_BG
        }
        return key(label, weight, background, KEY_TEXT, ACTION_TEXT_SP, onClick)
    }

    private fun key(
        label: String,
        weight: Float,
        background: Int,
        textColor: Int,
        textSizeSp: Float,
        onClick: () -> Unit
    ): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        includeFontPadding = false
        setBackground(keyBackground(background))
        setOnClickListener { onClick() }
        // Must come AFTER setOnClickListener, which can flip focusable on.
        isFocusable = false
        isFocusableInTouchMode = false
        val m = dp(2f)
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(m, m, m, m)
        }
    }

    private fun keyBackground(color: Int): StateListDrawable {
        fun shape(fill: Int) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(6f).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(PRESSED_BG))
            addState(IntArray(0), shape(color))
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF101317.toInt()
        const val KEY_BG = 0xFF2B3138.toInt()
        const val ACTION_BG = 0xFF1C2127.toInt()
        const val ACTIVE_BG = 0xFF3D6EA5.toInt()
        const val ACCENT_BG = 0xFF2E6B4F.toInt()
        const val PRESSED_BG = 0xFF4E92D6.toInt()
        val KEY_TEXT = Color.WHITE

        const val CHAR_TEXT_SP = 19f
        const val ACTION_TEXT_SP = 15f

        val DIGITS = "1234567890".toList()
        val LETTER_ROW_1 = "qwertyuiop".toList()
        val LETTER_ROW_2 = "asdfghjkl".toList()
        val LETTER_ROW_3 = "zxcvbnm".toList()

        val SYMBOL_ROW_1 = "1234567890".toList()
        val SYMBOL_ROW_2 = "-_/:;()$&\"".toList()
        val SYMBOL_ROW_3 = "#%*+=!?,'".toList()
    }
}
