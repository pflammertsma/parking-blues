package dev.lammertsma.parkingblues.car.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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

data class GpsFix(
  val lat: Double,
  val lon: Double,
  val bearingDegrees: Float?,
  val speedMps: Float? = null,
  val bearingAccuracyDegrees: Float? = null,
)

private fun Location.toFix() = GpsFix(
  lat = latitude,
  lon = longitude,
  bearingDegrees = if (hasBearing()) bearing else null,
  speedMps = if (hasSpeed()) speed else null,
  bearingAccuracyDegrees = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
)

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
          val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
          val lmListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
              trySend(loc.toFix())
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
          }

          if (lm != null) {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
              lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, lmListener, context.mainLooper)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
              lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, lmListener, context.mainLooper)
            }
          }

          val client = LocationServices.getFusedLocationProviderClient(context)
          val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .build()
          val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
              result.lastLocation?.let {
                trySend(it.toFix())
              }
            }
          }
          client.requestLocationUpdates(request, callback, context.mainLooper)

          val initial = listOfNotNull(
            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER),
            lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER),
          ).maxByOrNull { it.time }
          initial?.let {
            trySend(it.toFix())
          }

          awaitClose {
            client.removeLocationUpdates(callback)
            lm?.removeUpdates(lmListener)
          }
        }
      }
    }
}

@SuppressLint("MissingPermission")
suspend fun lastKnownLocation(context: Context): Pair<Double, Double>? {
  if (isTestLocationEnabled(context)) return getSimulatedRouteStart()

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
