package my.kang.carvideo

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A TV remote floating over the car picture: ▲ ◀ OK ▶ ▼, back, play/pause, hide.
 *
 * youtube.com/tv is driven by key presses, not touch, so every button sends a real
 * key code through [onKey]. Translucent and parked in a corner so the video stays
 * fullscreen behind it.
 *
 * Buttons are NOT focusable: a button taking Android focus would pull it off the
 * WebView, and the page would stop receiving the keys.
 *
 * @param keySize edge of one key in pixels, picked by the caller from the car
 *   screen size so the remote is the same physical size on any head unit.
 */
class CarRemoteView(context: Context, keySize: Int) : LinearLayout(context) {

    var onKey: ((Int) -> Unit)? = null
    var onHide: (() -> Unit)? = null

    private val gap = keySize / 12

    init {
        orientation = VERTICAL
        isFocusable = false
        val pad = keySize / 6
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(PANEL)
            cornerRadius = keySize / 3f
        }

        row(keySize, null, key("▲", KeyEvent.KEYCODE_DPAD_UP, keySize), null)
        row(
            keySize,
            key("◀", KeyEvent.KEYCODE_DPAD_LEFT, keySize),
            key("OK", KeyEvent.KEYCODE_DPAD_CENTER, keySize, accent = true),
            key("▶", KeyEvent.KEYCODE_DPAD_RIGHT, keySize)
        )
        row(keySize, null, key("▼", KeyEvent.KEYCODE_DPAD_DOWN, keySize), null)
        // youtube.com/tv takes Escape as "back"; Android's KEYCODE_BACK does nothing there.
        row(
            keySize,
            key("返回", KeyEvent.KEYCODE_ESCAPE, keySize, small = true),
            key("⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, keySize),
            button("收起", keySize, small = true) { onHide?.invoke() }
        )
    }

    private fun row(keySize: Int, vararg keys: View?) {
        val line = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (k in keys) {
            line.addView(
                k ?: View(context),
                LayoutParams(keySize, keySize).apply { setMargins(gap, gap, gap, gap) }
            )
        }
        addView(line)
    }

    private fun key(label: String, keyCode: Int, keySize: Int, accent: Boolean = false, small: Boolean = false) =
        button(label, keySize, accent, small) { onKey?.invoke(keyCode) }

    private fun button(
        label: String,
        keySize: Int,
        accent: Boolean = false,
        small: Boolean = false,
        onClick: () -> Unit
    ): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, keySize * if (small) 0.26f else 0.36f)
        background = keyBackground(accent, keySize / 4f)
        setOnClickListener { onClick() }
        // After setOnClickListener, which turns focusable back on.
        isFocusable = false
        isFocusableInTouchMode = false
    }

    private fun keyBackground(accent: Boolean, radius: Float) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(PRESSED, radius))
        addState(intArrayOf(), rounded(if (accent) ACCENT else KEY, radius))
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private companion object {
        val PANEL = Color.argb(150, 0, 0, 0)
        val KEY = Color.argb(170, 60, 64, 72)
        val ACCENT = Color.argb(220, 46, 107, 176)
        val PRESSED = Color.argb(230, 120, 160, 220)
    }
}
