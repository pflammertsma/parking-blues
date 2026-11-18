package dev.lammertsma.parkingblues.car.car

import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Splits a planned route at where the driver is, into the part already driven and the part still
 * ahead, so the map can draw them differently.
 *
 * Progress only moves forward: each call starts from the previous index, and only the next
 * [SEARCH_WINDOW] segments are looked at. A route that doubles back along the same street (a
 * loop round a block) therefore cannot jump to its later half while the driver is still on the
 * first. A position farther than [MAX_OFF_ROUTE_M] from the line (a detour, a poor fix) keeps the
 * previous progress rather than guessing.
 */
object RouteProgress {
    const val MAX_OFF_ROUTE_M = 60.0
    const val SEARCH_WINDOW = 120

    /** [traveled] and [remaining] share the point where the driver is, so the two lines join up. */
    data class Split(val traveled: List<GeoPoint>, val remaining: List<GeoPoint>, val index: Int)

    fun split(path: List<GeoPoint>, position: GeoPoint?, previousIndex: Int = 0): Split {
        if (path.size < 2) return Split(emptyList(), path, 0)
        val from = previousIndex.coerceIn(0, path.size - 2)
        if (position == null) return at(path, from, null)

        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE
        var bestPoint: GeoPoint? = null
        for (i in from..min(path.size - 2, from + SEARCH_WINDOW)) {
            val (point, distance) = nearestOnSegment(path[i], path[i + 1], position)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
                bestPoint = point
            }
        }
        return if (bestDistance > MAX_OFF_ROUTE_M) at(path, from, null) else at(path, bestIndex, bestPoint)
    }

    private fun at(path: List<GeoPoint>, index: Int, point: GeoPoint?): Split {
        if (point == null) {
            return Split(path.subList(0, index + 1), path.subList(index, path.size), index)
        }
        // The cut point is added to both halves, unless it is a vertex already in them.
        val behind = path.subList(0, index + 1)
        val ahead = path.subList(index + 1, path.size)
        return Split(
            traveled = if (point == behind.last()) behind else behind + point,
            remaining = if (point == ahead.first()) ahead else listOf(point) + ahead,
            index = index,
        )
    }

    /** The closest point of segment a-b to [p] and its distance in metres (flat-earth, city scale). */
    private fun nearestOnSegment(a: GeoPoint, b: GeoPoint, p: GeoPoint): Pair<GeoPoint, Double> {
        val metersPerDegree = 111_320.0
        val scaleX = cos(Math.toRadians(a.latitude)) * metersPerDegree
        val abX = (b.longitude - a.longitude) * scaleX
        val abY = (b.latitude - a.latitude) * metersPerDegree
        val apX = (p.longitude - a.longitude) * scaleX
        val apY = (p.latitude - a.latitude) * metersPerDegree
        val lengthSquared = abX * abX + abY * abY
        val t = if (lengthSquared == 0.0) 0.0 else ((apX * abX + apY * abY) / lengthSquared).coerceIn(0.0, 1.0)
        val point = GeoPoint(
            a.latitude + (b.latitude - a.latitude) * t,
            a.longitude + (b.longitude - a.longitude) * t,
        )
        return point to hypot(apX - abX * t, apY - abY * t)
    }
}
