package com.parkingblues.car.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * lat/lon plus heading, when the platform can tell us one. `bearingDegrees`
 * is purely a client-side rendering concern (which way to point the "you"
 * marker) -- the backend/ParkingSessionRepository only ever gets lat/lon
 * (see ParkingCarSession/MainActivity, which destructure just `(lat, lon)`
 * from this and never see bearingDegrees at all).
 */
data class GpsFix(val lat: Double, val lon: Double, val bearingDegrees: Float?)

/**
 * Streams live GPS fixes, feeding exactly what a driver's real movement
 * looks like into ParkingSessionRepository.updatePosition -- the on-device
 * equivalent of dragging the "you" marker in the web MVP. Caller must have
 * already been granted ACCESS_FINE_LOCATION.
 *
 * When the test-location toggle is on (see TestLocation.kt, on by default),
 * this just emits the fixed Zurich point once instead of querying real GPS
 * -- real device GPS almost never reports Zurich during development, which
 * would otherwise make every search return nothing nearby. A fixed point
 * has no meaningful heading, so bearingDegrees is always null in that case.
 */
@SuppressLint("MissingPermission")
fun locationUpdates(context: Context): Flow<GpsFix> = callbackFlow {
    if (isTestLocationEnabled(context)) {
        trySend(GpsFix(TEST_LOCATION.first, TEST_LOCATION.second, bearingDegrees = null))
        awaitClose { }
        return@callbackFlow
    }
    val client = LocationServices.getFusedLocationProviderClient(context)
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L).build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                trySend(GpsFix(it.latitude, it.longitude, if (it.hasBearing()) it.bearing else null))
            }
        }
    }
    client.requestLocationUpdates(request, callback, context.mainLooper)
    awaitClose { client.removeLocationUpdates(callback) }
}

/** One-shot fix, needed to start the very first search before any session exists. */
@SuppressLint("MissingPermission")
suspend fun lastKnownLocation(context: Context): Pair<Double, Double>? {
    if (isTestLocationEnabled(context)) return TEST_LOCATION
    return suspendCancellableCoroutine { continuation ->
        LocationServices.getFusedLocationProviderClient(context).lastLocation
            .addOnSuccessListener { location ->
                continuation.resume(location?.let { it.latitude to it.longitude })
            }
            .addOnFailureListener { continuation.resume(null) }
    }
}
