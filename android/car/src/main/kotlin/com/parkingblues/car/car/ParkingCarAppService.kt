package com.parkingblues.car.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class ParkingCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        // Fine for local development/sideloading; a production build should
        // use HostValidator.Builder(applicationContext) with an allowlist.
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = ParkingCarSession()
}
