package dev.lammertsma.parkingblues.car.car

import android.content.Context
import dev.lammertsma.parkingblues.shared.model.ParkedSpot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * On-device persistence for the one "I parked here" record, separate from
 * ParkingSessionRepository's in-memory session state -- this needs to
 * survive process death and outlive the backend session (which stays
 * server-side, polled live), since the whole point is the phone app
 * reading it back later, after Android Auto has disconnected and nothing
 * is actively driving ParkingSessionRepository anymore. Plain
 * SharedPreferences + JSON is enough for a single record; no need for a
 * real database here.
 *
 * Also observable ([parkedSpotFlow]): the record is written from places that are not the phone
 * screen (the car session, the Android Auto connection watcher, a notification), and the phone
 * screen has to follow them the moment they happen, e.g. when reconnecting to Android Auto clears
 * it while the phone app is open.
 */
private const val PREFS_NAME = "parking_blues_parked_spot"
private const val KEY_SPOT_JSON = "spot_json"

private val json = Json { ignoreUnknownKeys = true }

private val observable = MutableStateFlow<ParkedSpot?>(null)
private var primed = false

fun saveParkedSpot(context: Context, spot: ParkedSpot) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SPOT_JSON, json.encodeToString(spot))
        .apply()
    synchronized(observable) {
        primed = true
        observable.value = spot
    }
}

fun loadParkedSpot(context: Context): ParkedSpot? {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SPOT_JSON, null) ?: return null
    return runCatching { json.decodeFromString<ParkedSpot>(raw) }.getOrNull()
}

fun clearParkedSpot(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_SPOT_JSON)
        .apply()
    synchronized(observable) {
        primed = true
        observable.value = null
    }
}

/** The saved spot, kept current as it is saved or cleared from anywhere in the process. */
fun parkedSpotFlow(context: Context): StateFlow<ParkedSpot?> {
    synchronized(observable) {
        if (!primed) {
            observable.value = loadParkedSpot(context)
            primed = true
        }
    }
    return observable.asStateFlow()
}
