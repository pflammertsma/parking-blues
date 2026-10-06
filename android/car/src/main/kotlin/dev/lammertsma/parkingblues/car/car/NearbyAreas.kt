package dev.lammertsma.parkingblues.car.car

import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import dev.lammertsma.parkingblues.shared.model.ZoneType
import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * One parking *area* as drawn on the map (a run of adjacent same-zone spots),
 * described for a list: how far, which way, how many spaces, what the rules are.
 * [rank] is the best position of any of its spots in the backend's own ordering,
 * so the list follows the server's ranking rather than inventing a second one.
 */
data class NearbyArea(
    val cluster: SpotCluster,
    val rank: Int,
    val distanceM: Int,
    val direction: String,
    val capacity: Int,
    val center: GeoPoint,
)

object NearbyAreas {
    /** The areas around [snapshot]'s driver, best first, at most [limit]. */
    fun top(snapshot: SessionSnapshot, limit: Int): List<NearbyArea> {
        val active = MapClustering.activeCandidates(snapshot)
        val rankById = active.withIndex().associate { (index, segment) -> segment.id to index }
        return MapClustering.clusterByZone(active)
            .map { cluster ->
                val center = MapClustering.centroidLatLng(cluster.segments)
                NearbyArea(
                    cluster = cluster,
                    rank = cluster.segments.minOf { rankById.getValue(it.id) },
                    distanceM = cluster.segments.minOf { it.distanceFromYouM }.roundToInt(),
                    direction = compassDirection(snapshot.you.lat, snapshot.you.lon, center.latitude, center.longitude),
                    capacity = cluster.segments.sumOf { it.estimatedCapacity.coerceAtLeast(1) },
                    center = center,
                )
            }
            .sortedBy { it.rank }
            .take(limit)
    }

    /** "Blue zone · 120 m NE" */
    fun title(area: NearbyArea): String {
        val zone = if (area.cluster.zoneType == ZoneType.BLUE) "Blue zone" else "White zone"
        return "$zone · ${area.distanceM} m ${area.direction}"
    }

    /** "~12 spaces · park until ~11:30" (blue) or "~6 spaces · 120 min max · ~CHF 2.00/h" (white). */
    fun summary(area: NearbyArea): String = "${spaces(area)} · ${rules(area)}"

    fun spaces(area: NearbyArea): String = "~${area.capacity} space${if (area.capacity == 1) "" else "s"}"

    /** The time rule of the area's first spot (same-zone neighbours share the rule). */
    fun rules(area: NearbyArea): String {
        val segment = area.cluster.segments.minBy { it.distanceFromYouM }
        return if (segment.zoneType == ZoneType.BLUE) {
            segment.legalUntil?.let { "park until ~${clock(it)}" } ?: "no time limit now"
        } else {
            val cap = segment.maxDurationMinutes?.let { "$it min max" } ?: "no fixed limit"
            segment.estimatedFeeChfPerHour?.let { "$cap · ~CHF ${"%.2f".format(it)}/h" } ?: cap
        }
    }

    /** "2026-10-06T11:30:00+02:00" -> "11:30" */
    private fun clock(isoDateTime: String): String =
        if (isoDateTime.length >= 16) isoDateTime.substring(11, 16) else isoDateTime

    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** 8-point compass direction from one position to another (flat-earth, fine at city scale). */
    fun compassDirection(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
        val dy = toLat - fromLat
        val dx = (toLon - fromLon) * cos(Math.toRadians(fromLat))
        val degrees = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
        return COMPASS[((degrees + 22.5) / 45.0).toInt() % 8]
    }
}
