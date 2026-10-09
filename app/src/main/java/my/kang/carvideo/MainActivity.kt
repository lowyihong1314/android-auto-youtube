package my.kang.carvideo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Phone side, first screen: a remote for youtube.com/tv on the car screen, so a
 * passenger can drive it from the phone. Everything else is behind the gear icon
 * ([SettingsActivity]).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var carStatus: TextView
    private val carListener: (CarLink.Status) -> Unit = { renderCar(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Insets.applySystemBars(findViewById(R.id.root))

        carStatus = findViewById(R.id.car_status)
        // The now-playing notification in the shade needs this from Android 13 on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        findViewById<android.view.View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        mapOf(
            R.id.pad_up to KeyEvent.KEYCODE_DPAD_UP,
            R.id.pad_down to KeyEvent.KEYCODE_DPAD_DOWN,
            R.id.pad_left to KeyEvent.KEYCODE_DPAD_LEFT,
            R.id.pad_right to KeyEvent.KEYCODE_DPAD_RIGHT,
            R.id.pad_ok to KeyEvent.KEYCODE_DPAD_CENTER,
            // youtube.com/tv takes Escape as back.
            R.id.pad_back to KeyEvent.KEYCODE_ESCAPE,
            R.id.pad_play to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        ).forEach { (id, keyCode) -> onCar(id) { it.sendKey(keyCode) } }
        onCar(R.id.pad_home) { it.goHome() }
        bindSearch()
    }

    override fun onStart() {
        super.onStart()
        CarLink.addListener(carListener)
    }

    override fun onStop() {
        CarLink.removeListener(carListener)
        super.onStop()
    }

    /**
     * The search row: what is typed here is the search on the car screen. On
     * youtube.com/tv it becomes #/search?q=<text> (see CarVideoRenderer.searchOnTv), so
     * deleting is just a shorter query - no keys reach the page, whose Backspace is
     * "back". Results follow ~0.6 s after the last keystroke; the keyboard's search
     * key applies it at once.
     */
    private fun bindSearch() {
        val field = findViewById<TextInputEditText>(R.id.search)
        val layout = findViewById<TextInputLayout>(R.id.search_layout)

        fun send(edit: CarLink.TextEdit) {
            val car = CarLink.controller
            layout.helperText = if (car == null) getString(R.string.car_not_running) else null
            car?.editText(edit)
        }

        field.setOnFocusChangeListener { _, focused -> if (focused) send(CarLink.TextEdit.Reset) }
        field.doAfterTextChanged { text -> send(CarLink.TextEdit.Set(text?.toString().orEmpty())) }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                send(CarLink.TextEdit.Enter)
                field.clearFocus()
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
                true
            } else false
        }
    }

    private fun onCar(buttonId: Int, action: (CarLink.Controller) -> Unit) {
        findViewById<Button>(buttonId).setOnClickListener { button ->
            val car = CarLink.controller
            if (car == null) {
                Toast.makeText(this, R.string.car_not_running, Toast.LENGTH_SHORT).show()
            } else {
                button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action(car)
            }
        }
    }

    private fun renderCar(status: CarLink.Status) {
        val (text, color) = when {
            status.onScreen -> R.string.car_on_screen to R.color.ok
            status.alive -> R.string.car_background to R.color.brand
            else -> R.string.car_off to R.color.warn
        }
        carStatus.setText(text)
        carStatus.setTextColor(ContextCompat.getColor(this, color))
    }
}
