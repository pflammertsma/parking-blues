package com.parkingblues.car.location

import android.content.Context
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Same fixed point as web/app.js's DEFAULT_ORIGIN -- central Zurich, where
 * the real parking data actually is. Real GPS on a phone/tablet being
 * developed on is almost never actually in Zurich, so, exactly like the web
 * MVP (which starts on this point and only switches to real geolocation if
 * you click "Use my location"), this is the default here too: real GPS is
 * the opt-in, not the default.
 */
private val DEFAULT_ORIGIN: Pair<Double, Double> = 47.379198 to 8.531307

// Ad-hoc test-drive offset: change these two and reinstall to move the
// simulated position for testing movement-dependent behavior (backend
// auto-rejection/retargeting, camera-follow). A single static offset like
// this has no derivable GPS bearing (that needs consecutive fixes), so it
// moves the "you" marker's position but won't turn the heading triangle --
// that needs actual simulated continuous movement, not a one-shot jump.
private const val TEST_MOVE_METERS = 100.0
private const val TEST_MOVE_BEARING_DEGREES = 157.5 // SSE (S=180, SE=135)

val TEST_LOCATION: Pair<Double, Double> =
    offsetMeters(DEFAULT_ORIGIN.first, DEFAULT_ORIGIN.second, TEST_MOVE_METERS, TEST_MOVE_BEARING_DEGREES)

/** Great-circle destination point given a start, distance, and bearing --
 *  not a flat-earth approximation (see MapSearchScreen's boundsForRadius
 *  for where that approximation IS fine, at a much smaller/local scale). */
private fun offsetMeters(lat: Double, lon: Double, meters: Double, bearingDegrees: Double): Pair<Double, Double> {
    val earthRadiusM = 6_371_000.0
    val angularDistance = meters / earthRadiusM
    val bearingRad = Math.toRadians(bearingDegrees)
    val latRad = Math.toRadians(lat)

    val newLatRad = asin(
        sin(latRad) * cos(angularDistance) + cos(latRad) * sin(angularDistance) * cos(bearingRad)
    )
    val newLonRad = Math.toRadians(lon) + atan2(
        sin(bearingRad) * sin(angularDistance) * cos(latRad),
        cos(angularDistance) - sin(latRad) * sin(newLatRad),
    )
    return Math.toDegrees(newLatRad) to Math.toDegrees(newLonRad)
}

private const val PREFS_NAME = "parking_blues_debug"
private const val KEY_USE_TEST_LOCATION = "use_test_location"

/** Per-install (SharedPreferences), so :app/Android Auto and :automotive
 *  each remember their own choice -- they're separate processes/APKs. */
fun isTestLocationEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_USE_TEST_LOCATION, true)

fun setTestLocationEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_USE_TEST_LOCATION, enabled)
        .apply()
}
