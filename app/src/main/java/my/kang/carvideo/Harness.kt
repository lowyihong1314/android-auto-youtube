package my.kang.carvideo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.car.app.SurfaceContainer

/**
 * Runs the real car pipeline on the phone, with no car: [CarVideoRenderer] draws into
 * an ImageReader shaped like the head unit's surface, and the frames are thrown away.
 *
 * A sideloaded build never gets to run in a real car, so this is how rendering
 * changes are measured before they go through Play. Debug builds only; driven by
 * `--es cmd harness --es value start|stop` (see [DebugReceiver]).
 */
object Harness {

    private const val WIDTH = 1802
    private const val HEIGHT = 1052
    private const val DPI = 180

    private val main = Handler(Looper.getMainLooper())
    private var reader: ImageReader? = null
    private var renderer: CarVideoRenderer? = null

    fun isAllowed(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun start(context: Context) = main.post {
        if (renderer != null) return@post
        val r = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
        // Drain every frame so the producer never blocks on a full queue.
        r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, main)
        reader = r
        renderer = CarVideoRenderer(context.applicationContext).also {
            it.onSurfaceAvailable(SurfaceContainer(r.surface, WIDTH, HEIGHT, DPI))
        }
    }

    fun stop() = main.post {
        renderer?.release()
        renderer = null
        // After release() has run (it posts to this same queue).
        main.post {
            reader?.close()
            reader = null
        }
    }
}
