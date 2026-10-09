package my.kang.carvideo

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat

/**
 * The car screen's only controls: a round remote button in the bottom-right corner,
 * and the [CarRemoteView] it opens.
 *
 *   idle      nothing shown; the video has the whole screen
 *   button    any tap or swipe shows the button; it hides again after [BUTTON_IDLE_MS]
 *   remote    tapping the button opens the remote; it folds away after [REMOTE_IDLE_MS]
 *             without a key press, or on its own "收起"
 *
 * Touch reaches us as host coordinates, not as real touches, so [onTap] does the hit
 * testing and clicks the button underneath itself.
 */
class RemoteOverlay(
    private val host: FrameLayout,
    keySize: Int,
    margin: Int,
    private val onKey: (Int) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    private val remote = CarRemoteView(host.context, keySize).apply {
        visibility = View.GONE
        onKey = { keyCode ->
            this@RemoteOverlay.onKey(keyCode)
            scheduleRemoteFold()
        }
        onHide = { closeRemote() }
    }

    private val button = ImageView(host.context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_remote))
        // FIT_CENTER: the vector is 24dp, a fraction of the key; let it scale up.
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = keySize / 5
        setPadding(pad, pad, pad, pad)
        // Visible on a black page as well as on bright video.
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BUTTON_BG)
            setStroke((keySize / 40).coerceAtLeast(2), BUTTON_RIM)
        }
        setOnClickListener { openRemote() }
        isFocusable = false
    }

    private val hideButton = Runnable { button.visibility = View.GONE }
    private val foldRemote = Runnable { closeRemote() }

    init {
        val corner = Gravity.BOTTOM or Gravity.END
        host.addView(
            button,
            FrameLayout.LayoutParams(keySize, keySize, corner).apply { setMargins(margin, margin, margin, margin) }
        )
        host.addView(
            remote,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                corner
            ).apply { setMargins(margin, margin, margin, margin) }
        )
        showButton()
    }

    val isRemoteOpen: Boolean get() = remote.visibility == View.VISIBLE

    /**
     * A tap on the car screen.
     * @return true if the overlay used it: it pressed one of our buttons, or the
     *   controls were hidden and the tap only brought the button back. false means
     *   the tap is for the page.
     */
    fun onTap(x: Float, y: Float): Boolean {
        if (isRemoteOpen) {
            clickAt(remote, x, y)?.let { it.performClick(); return true }
        } else if (button.visibility == View.VISIBLE) {
            if (hits(button, x, y)) { button.performClick(); return true }
        } else {
            showButton()
            return true
        }
        showButton()
        return false
    }

    /** A swipe is activity too: bring the button back without taking the swipe. */
    fun onActivity() {
        if (isRemoteOpen) scheduleRemoteFold() else showButton()
    }

    fun toggleRemote() {
        if (isRemoteOpen) closeRemote() else openRemote()
    }

    fun release() {
        main.removeCallbacks(hideButton)
        main.removeCallbacks(foldRemote)
    }

    private fun openRemote() {
        main.removeCallbacks(hideButton)
        button.visibility = View.GONE
        remote.visibility = View.VISIBLE
        scheduleRemoteFold()
    }

    private fun closeRemote() {
        main.removeCallbacks(foldRemote)
        remote.visibility = View.GONE
        showButton()
    }

    private fun showButton() {
        if (isRemoteOpen) return
        button.visibility = View.VISIBLE
        main.removeCallbacks(hideButton)
        main.postDelayed(hideButton, BUTTON_IDLE_MS)
    }

    private fun scheduleRemoteFold() {
        main.removeCallbacks(foldRemote)
        main.postDelayed(foldRemote, REMOTE_IDLE_MS)
    }

    /** The deepest clickable view under (x, y), in display coordinates. */
    private fun clickAt(view: View, x: Float, y: Float): View? {
        if (view.visibility != View.VISIBLE || !hits(view, x, y)) return null
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                clickAt(view.getChildAt(i), x, y)?.let { return it }
            }
        }
        return view.takeIf { it.isClickable }
    }

    private fun hits(view: View, x: Float, y: Float): Boolean {
        val at = IntArray(2)
        view.getLocationInWindow(at)
        return x >= at[0] && x < at[0] + view.width && y >= at[1] && y < at[1] + view.height
    }

    private companion object {
        const val BUTTON_IDLE_MS = 4_000L
        const val REMOTE_IDLE_MS = 8_000L
        val BUTTON_BG = Color.argb(170, 70, 74, 82)
        val BUTTON_RIM = Color.argb(200, 255, 255, 255)
    }
}
