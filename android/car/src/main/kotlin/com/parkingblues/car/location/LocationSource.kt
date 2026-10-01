package com.parkingblues.car.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
 * Automatically switches between simulated route playback (when test mode is
 * enabled) and real device GPS (when test mode is disabled).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SuppressLint("MissingPermission")
fun locationUpdates(context: Context): Flow<GpsFix> {
    isTestLocationEnabled(context)
    return testModeFlow.flatMapLatest { isTest ->
        if (isTest == true) {
            flow {
                val route = getSimulatedRouteSteps()
                var stepIndex = 0
                while (true) {
                    emit(route[stepIndex % route.size])
                    stepIndex++
                    delay(SIMULATED_STEP_INTERVAL_MS)
                }
            }
        } else {
            callbackFlow {
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
        }
    }
}

/**
 * One-shot fix, needed to start the very first search before any session exists.
 * Queries lastLocation and falls back to getCurrentLocation so the app
 * accurately centers on the user's actual location when test mode is disabled.
 */
@SuppressLint("MissingPermission")
suspend fun lastKnownLocation(context: Context): Pair<Double, Double>? {
    if (isTestLocationEnabled(context)) return getSimulatedRouteStart()
    val client = LocationServices.getFusedLocationProviderClient(context)
    return suspendCancellableCoroutine { continuation ->
        client.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    continuation.resume(location.latitude to location.longitude)
                } else {
                    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { fresh ->
                            continuation.resume(fresh?.let { it.latitude to it.longitude })
                        }
                        .addOnFailureListener { continuation.resume(null) }
                }
            }
            .addOnFailureListener {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { fresh ->
                        continuation.resume(fresh?.let { it.latitude to it.longitude })
                    }
                    .addOnFailureListener { continuation.resume(null) }
            }
    }
}
