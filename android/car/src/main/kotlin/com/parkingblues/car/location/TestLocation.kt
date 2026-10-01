package com.parkingblues.car.location

import android.content.Context
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Same fixed point as web/app.js's DEFAULT_ORIGIN -- central Zurich, where
 * the real parking data actually is.
 */
val DEFAULT_ORIGIN: Pair<Double, Double> = 47.379198 to 8.531307

/**
 * Multi-leg simulated driving loop through central Zurich past real parking
 * spots on Neugasse, Josefstrasse, and surrounding streets.
 */
val SIMULATED_ROUTE_WAYPOINTS: List<Pair<Double, Double>> = listOf(
    47.378881 to 8.531066, // Lagerstrasse / Kanonengasse (heading SE)
    47.378542 to 8.531642, // Lagerstrasse past stalls
    47.378233 to 8.532135, // Lagerstrasse / Eisgasse past stalls
    47.377585 to 8.533166, // Lagerstrasse / Freischuetzgasse
    47.377482 to 8.533186, // Turn onto Freischuetzgasse
    47.376914 to 8.532383, // Freischuetzgasse / Militaerstrasse
    47.377283 to 8.531853, // Militaerstrasse heading NW
    47.377659 to 8.531307, // Militaerstrasse / Eisgasse (past origin)
    47.378297 to 8.530397, // Militaerstrasse / Kanonengasse
    47.378558 to 8.530718, // Kanonengasse heading NE
    47.378881 to 8.531066, // Back to start of loop
)

fun getSimulatedRouteStart(): Pair<Double, Double> = SIMULATED_ROUTE_WAYPOINTS.first()

fun getSimulatedRouteInitialBearing(): Float = calculateBearingDegrees(
    SIMULATED_ROUTE_WAYPOINTS[0].first,
    SIMULATED_ROUTE_WAYPOINTS[0].second,
    SIMULATED_ROUTE_WAYPOINTS[1].first,
    SIMULATED_ROUTE_WAYPOINTS[1].second,
)

val TEST_LOCATION: Pair<Double, Double> = getSimulatedRouteStart()

/**
 * Speed for test drive simulation in meters per second.
 * 30 km/h = ~4.17 m/s, to allow observing the car marker turning and parking
 * spots/zones auto-rejecting.
 */
const val SIMULATED_SPEED_MPS: Double = 30.0 / 3.6
const val SIMULATED_STEP_INTERVAL_MS: Long = 1000L

/**
 * Precomputes step-by-step GPS fixes along the simulated route with realistic
 * coordinates and bearing (heading) at ~30 km/h.
 */
fun getSimulatedRouteSteps(): List<GpsFix> {
    val steps = mutableListOf<GpsFix>()
    for (leg in 0 until SIMULATED_ROUTE_WAYPOINTS.size - 1) {
        val w1 = SIMULATED_ROUTE_WAYPOINTS[leg]
        val w2 = SIMULATED_ROUTE_WAYPOINTS[leg + 1]
        val dist = calculateDistanceMeters(w1.first, w1.second, w2.first, w2.second)
        val bearing = calculateBearingDegrees(w1.first, w1.second, w2.first, w2.second)
        val count = maxOf(1, round(dist / SIMULATED_SPEED_MPS).toInt())
        for (i in 0 until count) {
            val frac = i.toDouble() / count
            val lat = w1.first + frac * (w2.first - w1.first)
            val lon = w1.second + frac * (w2.second - w1.second)
            steps.add(GpsFix(lat, lon, bearingDegrees = bearing))
        }
    }
    return steps
}

fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    val theta = atan2(y, x)
    return ((Math.toDegrees(theta) + 360.0) % 360.0).toFloat()
}

fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
    val c = 2 * asin(sqrt(a))
    return earthRadiusM * c
}

private const val PREFS_NAME = "parking_blues_debug"
private const val KEY_USE_TEST_LOCATION = "use_test_location"

val testModeFlow = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
val testModeTrigger = kotlinx.coroutines.flow.MutableStateFlow(0)

/**
 * Per-install (SharedPreferences), so :app/Android Auto and :automotive
 * each remember their own choice independently. Default is false so real
 * location is used unless the user explicitly activates test mode.
 */
fun isTestLocationEnabled(context: Context): Boolean {
    val current = testModeFlow.value
    if (current != null) return current
    val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_USE_TEST_LOCATION, false)
    testModeFlow.value = saved
    return saved
}

fun setTestLocationEnabled(context: Context, enabled: Boolean) {
    testModeFlow.value = enabled
    testModeTrigger.value++
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_USE_TEST_LOCATION, enabled)
        .apply()
}
