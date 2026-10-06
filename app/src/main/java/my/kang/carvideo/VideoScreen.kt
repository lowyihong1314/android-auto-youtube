package my.kang.carvideo

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The only screen of the app. NavigationTemplate is what lets a NAVIGATION-category
 * car app draw into the raw car surface, which [CarVideoRenderer] turns into a
 * VirtualDisplay + Presentation hosting the YouTube WebView.
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
        // ACTIONS_CONSTRAINTS_NAVIGATION: at most 4 actions, at most 4 custom titles.
        val actionStrip = ActionStrip.Builder()
            .addAction(titleAction(R.string.action_keyboard) { renderer.toggleKeyboard() })
            .addAction(titleAction(R.string.action_back) { renderer.goBack() })
            .addAction(titleAction(R.string.action_home) { renderer.goHome() })
            .addAction(titleAction(R.string.action_reload) { renderer.reload() })
            .build()

        // Without a map action strip containing Action.PAN the host never sends
        // SurfaceCallback.onScroll, so the page could never be scrolled.
        // ACTIONS_CONSTRAINTS_MAP allows no custom titles here, so PAN only.
        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapActionStrip)
            .build()
    }

    private fun titleAction(titleRes: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setTitle(carContext.getString(titleRes))
            .setOnClickListener(onClick)
            .build()
}
