package com.parkingblues.app

import android.app.Application
import com.parkingblues.car.HasParkingRepository
import com.parkingblues.shared.ParkingSessionRepository
import com.parkingblues.shared.api.ParkingApiClient

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
        repository = ParkingSessionRepository(ParkingApiClient(BuildConfig.BASE_URL))
    }
}
