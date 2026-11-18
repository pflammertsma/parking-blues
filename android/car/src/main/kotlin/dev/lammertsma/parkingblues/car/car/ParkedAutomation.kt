package dev.lammertsma.parkingblues.car.car

import android.app.Application
import androidx.car.app.connection.CarConnection
import dev.lammertsma.parkingblues.shared.ParkingSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the saved parked spot in step with Android Auto, for as long as the app process lives:
 *
 *  - Android Auto **disconnects** without "Parked here" having been pressed: the driver stopped
 *    where the last position of the drive was, so that is saved as an *assumed* spot (and the
 *    usual notification and expiry reminder follow).
 *  - Android Auto **connects** again: the driver is back in the car and the spot is stale, so it
 *    is cleared everywhere (this device, its reminders, the account's copy).
 *
 * The decisions live in [CarConnectionTracker]; this class only feeds it and carries them out.
 * Phone and Android Auto run in one process, which is why this can see both. It is not started in
 * the Automotive OS app: there the app runs in the car itself and closing it is not parking (see
 * [clearOnAutomotiveStart]).
 */
class ParkedAutomation(
    private val app: Application,
    private val repository: ParkingSessionRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    clock: () -> Long = System::currentTimeMillis,
) {
    private val tracker = CarConnectionTracker(clock)
    private val clock = clock

    /** Call once, on the main thread (Application.onCreate). */
    fun start() {
        scope.launch {
            repository.livePosition.collect { position ->
                if (position != null) tracker.onFix(position.lat, position.lon)
            }
        }
        CarConnection(app).type.observeForever { type ->
            handle(tracker.onConnection(projected = type == CarConnection.CONNECTION_TYPE_PROJECTION))
        }
    }

    private fun handle(event: CarConnectionTracker.Event) {
        when (event) {
            CarConnectionTracker.Event.None -> Unit
            CarConnectionTracker.Event.Connected -> clearEverywhere(app, repository, scope)
            is CarConnectionTracker.Event.Disconnected -> event.lastFix?.let { assumeParked(it) }
        }
    }

    private fun assumeParked(fix: CarConnectionTracker.Fix) {
        // The driver pressed "Parked here" (or set a spot on the map): that is what they said.
        if (loadParkedSpot(app) != null) return
        val spot = ParkedSpotFactory.at(
            snapshot = repository.session.value,
            lat = fix.lat,
            lon = fix.lon,
            nowMillis = clock(),
            assumed = true,
            destination = repository.destination.value,
        )
        saveParkedSpot(app, spot)
        notifyParkingSaved(app, spot)
        if (spot.expiresAtEpochMillis != null) scheduleExpiryReminder(app, spot)
        // Not synced to the account: it is a guess, and only this phone needs it.
    }
}

/** Forgets the parked spot on this device, cancels its reminders and best-effort clears the account's copy. */
fun clearEverywhere(app: Application, repository: ParkingSessionRepository, scope: CoroutineScope) {
    clearParkedSpot(app)
    cancelExpiryReminder(app)
    scope.launch { repository.clearParkedSpotSync() }
}

/**
 * The Automotive OS app has no phone to disconnect from, but starting it means the driver is
 * back in the car, so a spot left from the last drive is cleared then.
 */
fun clearOnAutomotiveStart(app: Application, repository: ParkingSessionRepository, scope: CoroutineScope) =
    clearEverywhere(app, repository, scope)
