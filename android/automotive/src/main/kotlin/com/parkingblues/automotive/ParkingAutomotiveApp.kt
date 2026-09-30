package com.parkingblues.automotive

import android.app.Application
import com.parkingblues.car.HasParkingRepository
import com.parkingblues.car.car.configureOsmdroid
import com.parkingblues.shared.ParkingSessionRepository
import com.parkingblues.shared.api.ParkingApiClient

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
        repository = ParkingSessionRepository(ParkingApiClient(BuildConfig.BASE_URL))
    }
}
