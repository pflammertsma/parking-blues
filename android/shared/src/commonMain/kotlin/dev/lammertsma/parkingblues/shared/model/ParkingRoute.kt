package dev.lammertsma.parkingblues.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * EXPERIMENT. The answer of POST /api/route (backend/route_planner.py): a drive from the user to a
 * destination that passes the parking areas most likely to have a space, as numbered [stops].
 */
@Serializable
data class ParkingRoute(
    val start: LatLon,
    val destination: LatLon,
    val stops: List<RouteStop>,
    val route: RoutePath,
    @SerialName("chance_of_parking") val chanceOfParking: Double,
    @SerialName("expected_minutes") val expectedMinutes: Double,
    /** Where the road starts, when that is away from the driver (off a road, or the nearest one was cut off). */
    @SerialName("start_snap") val startSnap: StartSnap? = null,
)

/** The road the route really starts on, [distanceM] from where the driver is. */
@Serializable
data class StartSnap(
    val lat: Double,
    val lon: Double,
    @SerialName("distance_m") val distanceM: Int,
    val name: String = "",
)

/** One parking area on the route. [chance] is the planner's guess that a space is free there. */
@Serializable
data class RouteStop(
    val rank: Int,
    @SerialName("zone_type") val zoneType: ZoneType,
    val lat: Double,
    val lon: Double,
    val capacity: Int,
    @SerialName("spot_count") val spotCount: Int,
    val chance: Double,
    @SerialName("eta_s") val etaSeconds: Int,
    @SerialName("max_duration_minutes") val maxDurationMinutes: Int? = null,
    @SerialName("legal_until") val legalUntil: String? = null,
)

/** The road route, as [lat, lon] pairs. */
@Serializable
data class RoutePath(
    @SerialName("distance_m") val distanceM: Int,
    @SerialName("duration_s") val durationSeconds: Int,
    val path: List<List<Double>>,
)
