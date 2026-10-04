package dev.lammertsma.parkingblues.car

import dev.lammertsma.parkingblues.shared.ParkingSessionRepository

/**
 * Implemented by each platform's own Application subclass (:app's
 * ParkingBluesApp for the phone/Android Auto, :automotive's
 * ParkingAutomotiveApp for Android Automotive OS) -- they're genuinely
 * separate installed processes/APKs that can't share a JVM instance, but
 * ParkingCarSession needs one common way to reach whichever one is running.
 */
interface HasParkingRepository {
    val repository: ParkingSessionRepository
}
