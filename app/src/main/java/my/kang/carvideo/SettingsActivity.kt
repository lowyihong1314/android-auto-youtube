package my.kang.carvideo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

/**
 * Every setting, the car's status, and the account.
 *
 * Every control writes straight into [Settings]; [CarVideoRenderer] listens and
 * applies the change to the live car screen. The screen also listens, so a value
 * changed over adb shows up here immediately.
 */
class SettingsActivity : AppCompatActivity() {

    /** True while we push Settings into the controls, so their listeners stay quiet. */
    private var rendering = false

    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val carListener: (CarLink.Status) -> Unit = { renderCar(it) }

    private lateinit var refreshGroup: MaterialButtonToggleGroup
    private lateinit var swipeStep: Slider
    private lateinit var swipeStepLabel: TextView
    private lateinit var preRaster: MaterialSwitch
    private lateinit var keepAwake: MaterialSwitch
    private lateinit var customUa: TextInputEditText
    private lateinit var uaPreview: TextView
    private lateinit var injectJs: TextInputEditText
    private lateinit var cookieList: LinearLayout
    private lateinit var carStatus: TextView
    private lateinit var carDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Insets.applySystemBars(findViewById(R.id.root))

        findViewById<TextView>(R.id.version).text = getString(R.string.version_line, versionName())
        bindCar()
        bindAccount()
        bindDisplay()
        bindAdvanced()
        renderSettings()
    }

    override fun onStart() {
        super.onStart()
        settingsListener = Settings.addListener { runOnUiThread { renderSettings() } }
        CarLink.addListener(carListener)
    }

    override fun onResume() {
        super.onResume()
        renderCookies()
    }

    override fun onStop() {
        settingsListener?.let(Settings::removeListener)
        settingsListener = null
        CarLink.removeListener(carListener)
        super.onStop()
    }

    // ------------------------------------------------------------------ car

    private fun bindCar() {
        carStatus = findViewById(R.id.car_status)
        carDetail = findViewById(R.id.car_detail)
        onCar(R.id.car_remote) { it.toggleRemote() }
        onCar(R.id.car_home) { it.goHome() }
        onCar(R.id.car_reload) { it.reload() }
        onCar(R.id.car_rebuild) { it.rebuildDisplay() }
    }

    private fun onCar(buttonId: Int, action: (CarLink.Controller) -> Unit) {
        findViewById<Button>(buttonId).setOnClickListener {
            val car = CarLink.controller
            if (car == null) toast(getString(R.string.car_not_running)) else action(car)
        }
    }

    private fun renderCar(status: CarLink.Status) {
        val (text, color) = when {
            status.onScreen -> R.string.car_on_screen to R.color.ok
            status.alive -> R.string.car_background to R.color.brand
            else -> R.string.car_off to null
        }
        carStatus.setText(text)
        if (color != null) carStatus.setTextColor(ContextCompat.getColor(this, color))
        else carStatus.setTextColor(carDetail.currentTextColor)

        carDetail.text = if (status.alive && status.surfaceWidth > 0) {
            getString(
                R.string.car_detail,
                status.surfaceWidth, status.surfaceHeight,
                status.dpi,
                formatHz(status.refreshRate),
                status.url.ifBlank { "—" }
            )
        } else {
            getString(R.string.car_off_detail)
        }
    }

    // ------------------------------------------------------------------ account

    private fun bindAccount() {
        cookieList = findViewById(R.id.cookie_list)
        findViewById<Button>(R.id.activate).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ACTIVATE_URL)))
        }
        findViewById<Button>(R.id.cookie_export).setOnClickListener { exportCookies() }
        findViewById<Button>(R.id.cookie_import).setOnClickListener { importCookies() }
        findViewById<Button>(R.id.cookie_clear_all).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sign_out)
                .setMessage(R.string.sign_out_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sign_out) { _, _ ->
                    CookieJar.clearAll()
                    CarLink.controller?.reload()
                    renderCookies()
                    toast(getString(R.string.signed_out))
                }
                .show()
        }
    }

    private fun renderCookies() {
        cookieList.removeAllViews()
        for (url in CookieJar.urls()) {
            val cookies = CookieJar.cookies(url)
            val host = url.substringAfter("://")
            val row = layoutInflater.inflate(R.layout.row_cookie, cookieList, false)
            row.findViewById<TextView>(R.id.cookie_host).text =
                if (cookies.isEmpty()) getString(R.string.cookie_none, host)
                else getString(R.string.cookie_row, host, cookies.size)
            row.findViewById<TextView>(R.id.cookie_names).apply {
                text = cookies.joinToString("  ") { it.name }
                isVisible = cookies.isNotEmpty()
            }
            row.findViewById<MaterialButton>(R.id.cookie_clear).apply {
                isEnabled = cookies.isNotEmpty()
                setOnClickListener {
                    CookieJar.clear(url)
                    renderCookies()
                    toast(getString(R.string.cookie_cleared, host))
                }
            }
            cookieList.addView(row)
        }
    }

    private fun exportCookies() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Car Video cookies", CookieJar.export()))
        toast(getString(R.string.cookie_exported))
    }

    private fun importCookies() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank() || !text.trimStart().startsWith("{")) {
            toast(getString(R.string.cookie_import_failed))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cookie_import_title)
            .setMessage(R.string.cookie_import_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cookie_import) { _, _ ->
                val count = runCatching { CookieJar.import(text) }.getOrNull()
                if (count == null) toast(getString(R.string.cookie_import_failed))
                else toast(getString(R.string.cookie_imported, count))
                renderCookies()
            }
            .show()
    }

    // ------------------------------------------------------------------ display

    private fun bindDisplay() {
        refreshGroup = findViewById(R.id.refresh_group)
        swipeStep = findViewById(R.id.swipe_step)
        swipeStepLabel = findViewById(R.id.swipe_step_label)
        preRaster = findViewById(R.id.pre_raster)
        keepAwake = findViewById(R.id.keep_awake)

        refreshGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || rendering) return@addOnButtonCheckedListener
            Settings.refreshRate = when (id) {
                R.id.refresh_24 -> 24f
                R.id.refresh_30 -> 30f
                R.id.refresh_60 -> 60f
                else -> 0f
            }
        }
        onSlider(swipeStep, { swipeStepLabel.text = getString(R.string.swipe_step, it.roundToInt()) }) {
            Settings.swipeStep = it
        }
        preRaster.setOnCheckedChangeListener { _, on -> if (!rendering) Settings.preRaster = on }
        keepAwake.setOnCheckedChangeListener { _, on -> if (!rendering) Settings.keepAwake = on }
    }

    // ------------------------------------------------------------------ advanced

    private fun bindAdvanced() {
        customUa = findViewById(R.id.custom_ua)
        uaPreview = findViewById(R.id.ua_preview)
        injectJs = findViewById(R.id.inject_js)
        onApply(findViewById(R.id.custom_ua_layout), customUa) { Settings.customUa = it }
        onApply(findViewById(R.id.inject_js_layout), injectJs) { Settings.injectJs = it }

        val receiver = "$packageName/${DebugReceiver::class.java.name}"
        findViewById<TextView>(R.id.adb_help).text = buildString {
            append("R=$receiver\n")
            append("adb shell am broadcast -n \$R -a my.kang.carvideo.DEBUG --es cmd get\n")
            append("adb shell am broadcast -n \$R -a my.kang.carvideo.DEBUG \\\n")
            append("  --es cmd key --es value down\n\n")
            append("cmd: get set reset reload home back rebuild remote open key\n")
            append("key: ${Settings.Keys.ALL.joinToString(" ")}")
        }

        findViewById<Button>(R.id.reset).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_defaults)
                .setMessage(R.string.reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_defaults) { _, _ ->
                    Settings.reset()
                    toast(getString(R.string.reset_done))
                }
                .show()
        }
    }

    // ------------------------------------------------------------------ settings -> UI

    private fun renderSettings() {
        rendering = true
        try {
            refreshGroup.check(
                when (Settings.refreshRate.roundToInt()) {
                    24 -> R.id.refresh_24
                    30 -> R.id.refresh_30
                    60 -> R.id.refresh_60
                    else -> R.id.refresh_auto
                }
            )
            setSlider(swipeStep, Settings.swipeStep)
            swipeStepLabel.text = getString(R.string.swipe_step, swipeStep.value.roundToInt())
            preRaster.isChecked = Settings.preRaster
            keepAwake.isChecked = Settings.keepAwake
            setIfIdle(customUa, Settings.customUa)
            uaPreview.text = Settings.userAgent
            setIfIdle(injectJs, Settings.injectJs)
        } finally {
            rendering = false
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Commit on the ✓ icon or the keyboard's Done key, not on every keystroke. */
    private fun onApply(layout: TextInputLayout, field: TextInputEditText, commit: (String) -> Unit) {
        val apply = {
            commit(field.text.toString())
            field.clearFocus()
            toast(getString(R.string.applied))
        }
        layout.setEndIconOnClickListener { apply() }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { apply(); true } else false
        }
    }

    /** The label follows the thumb; the setting is written when the finger lifts. */
    private fun onSlider(slider: Slider, label: (Float) -> Unit, commit: (Float) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            label(value)
            // Keyboard / accessibility adjustments have no touch to wait for.
            if (fromUser && !rendering && !tracking.contains(slider)) commit(value)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) { tracking += s }
            override fun onStopTrackingTouch(s: Slider) {
                tracking -= s
                commit(s.value)
            }
        })
    }

    private val tracking = mutableSetOf<Slider>()

    private fun setSlider(slider: Slider, value: Float) {
        if (slider in tracking) return
        val step = slider.stepSize
        val snapped = if (step > 0) {
            slider.valueFrom + ((value - slider.valueFrom) / step).roundToInt() * step
        } else value
        slider.value = snapped.coerceIn(slider.valueFrom, slider.valueTo)
    }

    /** Don't overwrite what the user is in the middle of typing. */
    private fun setIfIdle(field: TextInputEditText, value: String) {
        if (!field.hasFocus() && field.text.toString() != value) field.setText(value)
    }

    private fun formatHz(hz: Float): String =
        if (hz == hz.roundToInt().toFloat()) hz.roundToInt().toString() else "%.1f".format(hz)

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val ACTIVATE_URL = "https://yt.be/activate"
    }
}
