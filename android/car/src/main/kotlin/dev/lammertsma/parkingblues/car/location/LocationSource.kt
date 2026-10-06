package dev.lammertsma.parkingblues.car.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import androidx.core.content.ContextCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class GpsFix(
  val lat: Double,
  val lon: Double,
  val bearingDegrees: Float?,
  val speedMps: Float? = null,
  val bearingAccuracyDegrees: Float? = null,
  /** Horizontal accuracy radius in meters (68% confidence), when the provider reports one. */
  val accuracyM: Float? = null,
)

private fun Location.toFix() = GpsFix(
  lat = latitude,
  lon = longitude,
  bearingDegrees = if (hasBearing()) bearing else null,
  speedMps = if (hasSpeed()) speed else null,
  bearingAccuracyDegrees = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
  accuracyM = if (hasAccuracy()) accuracy else null,
)

private const val INITIAL_FIX_MAX_AGE_MS = 30_000L
private const val INITIAL_FIX_MAX_ACCURACY_M = 100f

@OptIn(ExperimentalCoroutinesApi::class)
@SuppressLint("MissingPermission")
fun locationUpdates(context: Context): Flow<GpsFix> {
  isTestLocationEnabled(context)
  return kotlinx.coroutines.flow.combine(testModeFlow, testModeTrigger) { isTest, _ -> isTest }
    .flatMapLatest { isTest ->
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
          // Fused provider only. Subscribing to the raw GPS *and* network
          // providers as well and merging everything into one stream let
          // coarse network fixes (often 30-100+ m off) interleave with good
          // GPS fixes, so the position hopped back and forth.
          val client = LocationServices.getFusedLocationProviderClient(context)
          val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .build()
          val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
              result.lastLocation?.let { trySend(it.toFix()) }
            }
          }
          client.requestLocationUpdates(request, callback, context.mainLooper)

          // A recent, reasonably accurate last-known fix gets the first draw
          // on screen before the first fused result arrives.
          val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
          val initial = listOfNotNull(
            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER),
            lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER),
          ).filter {
            System.currentTimeMillis() - it.time < INITIAL_FIX_MAX_AGE_MS &&
              (!it.hasAccuracy() || it.accuracy <= INITIAL_FIX_MAX_ACCURACY_M)
          }.maxByOrNull { it.time }
          initial?.let { trySend(it.toFix()) }

          awaitClose {
            client.removeLocationUpdates(callback)
          }
        }
      }
    }
}

/** Whether the app may read the device location (fine or coarse). */
fun hasLocationPermission(context: Context): Boolean =
  ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
    PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
    PackageManager.PERMISSION_GRANTED

/**
 * The best recent position, or null when there is none *or* the app has not been
 * granted location permission yet. Callers fall back to central Zurich, so a fresh
 * install (permission not yet answered) or a denied permission still shows a map
 * instead of crashing: getLastKnownLocation throws SecurityException without it.
 */
@SuppressLint("MissingPermission")
suspend fun lastKnownLocation(context: Context): Pair<Double, Double>? {
  if (isTestLocationEnabled(context)) return getSimulatedRouteStart()
  if (!hasLocationPermission(context)) return null
  return try {
    lastKnownLocationWithPermission(context)
  } catch (_: SecurityException) {
    null // permission revoked between the check and the call
  }
}

@SuppressLint("MissingPermission")
private suspend fun lastKnownLocationWithPermission(context: Context): Pair<Double, Double>? {

  val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
  val bestFromLm = listOfNotNull(
    lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER),
    lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
    lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER),
  ).maxByOrNull { it.time }

  if (bestFromLm != null && System.currentTimeMillis() - bestFromLm.time < 120_000L) {
    return bestFromLm.latitude to bestFromLm.longitude
  }

  val client = LocationServices.getFusedLocationProviderClient(context)
  return suspendCancellableCoroutine { continuation ->
    client.lastLocation
      .addOnSuccessListener { location ->
        if (location != null) {
          continuation.resume(location.latitude to location.longitude)
        } else if (bestFromLm != null) {
          continuation.resume(bestFromLm.latitude to bestFromLm.longitude)
        } else {
          client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { fresh ->
              if (fresh != null) {
                continuation.resume(fresh.latitude to fresh.longitude)
              } else {
                continuation.resume(bestFromLm?.let { it.latitude to it.longitude })
              }
            }
            .addOnFailureListener {
              continuation.resume(bestFromLm?.let { it.latitude to it.longitude })
            }
        }
      }
      .addOnFailureListener {
        continuation.resume(bestFromLm?.let { it.latitude to it.longitude })
      }
  }
}
