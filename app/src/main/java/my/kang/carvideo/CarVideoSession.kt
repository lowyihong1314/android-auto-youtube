package my.kang.carvideo

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class CarVideoSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = VideoScreen(carContext)
}
