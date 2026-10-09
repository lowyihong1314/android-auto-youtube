package my.kang.carvideo

import android.app.Application

class CarVideoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        CarMediaSession.init(this)
    }
}
