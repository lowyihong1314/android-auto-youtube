package my.kang.carvideo

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The only screen of the app. NavigationTemplate is what lets a NAVIGATION-category
 * car app draw into the raw car surface, which [CarVideoRenderer] turns into a
 * VirtualDisplay + Presentation hosting youtube.com/tv.
 */
class VideoScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = CarVideoRenderer(carContext)

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        renderer.release()
    }

    override fun onGetTemplate(): Template {
        // NavigationTemplate refuses to build without an action strip, so it carries
        // one small remote icon; the host hides it when idle. The real controls live
        // in the car picture itself (RemoteOverlay, bottom-right).
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_remote)
                        ).build()
                    )
                    .setOnClickListener { renderer.toggleRemote() }
                    .build()
            )
            .build()

        // Without a map action strip containing Action.PAN the host never sends
        // SurfaceCallback.onScroll, and swipes could not become arrow keys.
        // ACTIONS_CONSTRAINTS_MAP allows no custom titles here, so PAN only.
        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapActionStrip)
            .build()
    }
}
