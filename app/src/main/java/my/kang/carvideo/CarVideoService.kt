package my.kang.carvideo

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class CarVideoService : CarAppService() {

    /** Research build: trust whatever host connects (head unit, DHU, emulator). */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CarVideoSession()
}
