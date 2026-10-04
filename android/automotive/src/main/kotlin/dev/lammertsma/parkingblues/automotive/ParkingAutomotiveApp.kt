package dev.lammertsma.parkingblues.automotive

import android.app.Application
import dev.lammertsma.parkingblues.car.HasParkingRepository
import dev.lammertsma.parkingblues.car.car.configureOsmdroid
import dev.lammertsma.parkingblues.shared.ParkingSessionRepository
import dev.lammertsma.parkingblues.shared.api.ParkingApiClient

/**
 * Separate from :app's ParkingBluesApp -- this is a genuinely different
 * installed APK (Android Automotive OS module, see build.gradle.kts), not
 * something that can share a JVM process/class with the phone app, so each
 * needs its own Application subclass to own a ParkingSessionRepository.
 */
class ParkingAutomotiveApp : Application(), HasParkingRepository {
    override lateinit var repository: ParkingSessionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid(this)
        repository = ParkingSessionRepository(ParkingApiClient())
    }
}
