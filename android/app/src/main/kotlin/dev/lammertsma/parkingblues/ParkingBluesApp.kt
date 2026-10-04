package dev.lammertsma.parkingblues

import android.app.Application
import dev.lammertsma.parkingblues.car.HasParkingRepository
import dev.lammertsma.parkingblues.car.car.configureOsmdroid
import dev.lammertsma.parkingblues.shared.ParkingSessionRepository
import dev.lammertsma.parkingblues.shared.api.ParkingApiClient

/**
 * Holds the one [ParkingSessionRepository] instance for the process, so the
 * phone Activity and the car Session/Screens (Android Auto, via
 * ParkingCarSession in :car) all observe the same live state instead of
 * each keeping their own copy.
 */
class ParkingBluesApp : Application(), HasParkingRepository {
    override lateinit var repository: ParkingSessionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid(this)
        repository = ParkingSessionRepository(ParkingApiClient())
    }
}
