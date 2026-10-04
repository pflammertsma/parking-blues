package com.parkingblues.car.car

import com.parkingblues.shared.model.ParkingSegment
import com.parkingblues.shared.model.SessionSnapshot
import com.parkingblues.shared.model.ZoneType
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nearby same-zone spots grouped into one box+icon instead of one pin per
 * spot (per direct feedback that individual circles, even at a sane size
 * and count, don't read well at a glance -- the actual real-Zurich-street-
 * block shape reads better as one grouped box than as N separate dots
 * along a curb).
 *
 * Extracted out of MapSearchScreen (car-only) so the phone's own map view
 * (ParkingMapView, in :app) can render the exact same clustering/geometry
 * without a second implementation to keep in sync -- this is purely
 * geometry/presentation math (how spots are DRAWN), not the actual
 * candidate ranking/selection algorithm, which stays entirely server-side
 * (see android/README.md's §5.2 architecture note / AGENTS.md).
 */
data class SpotCluster(val zoneType: ZoneType, val segments: List<ParkingSegment>)

object MapClustering {
    // Rough meters-per-degree-latitude at any latitude (longitude's
    // equivalent varies with latitude -- see boundsForRadius, which
    // accounts for that via cos(latitude)).
    const val METERS_PER_DEGREE_LAT = 111_320.0

    // Single-linkage clustering distance for grouping nearby same-zone
    // spots into one box (see clusterByProximity) -- deliberately tight
    // (only genuinely adjacent stalls merge), not a "loosely on the same
    // street" threshold. Chains still let a dense unbroken run form one
    // large box; a single >EPS gap splits it. 3m was tried first and never
    // merged anything at all -- individual stall records here are
    // apparently spaced ~5-6m center-to-center (roughly one car length),
    // so 3m couldn't bridge even genuinely adjacent ones. Tuned up from
    // there by direct feedback (8 -> 10m).
    const val CLUSTER_EPS_M = 10.0

    // Padding beyond the outermost segment centers, so a box reads as a
    // real curb strip rather than a line drawn exactly through the dots --
    // more padding along the row's own direction than across it, since
    // real curb segments are long and narrow.
    const val BOX_PAD_ALONG_M = 6.0
    const val BOX_PAD_ACROSS_M = 4.0

    const val NEARBY_RADIUS_M = 300.0

    /** current + upcoming as one undistinguished list, minus anything
     *  already rejected -- see MapSearchScreen's class doc on why the UI
     *  no longer calls out a single "target" spot. */
    fun activeCandidates(snapshot: SessionSnapshot): List<ParkingSegment> {
        val rejectedIds = snapshot.rejected.map { it.id }.toSet()
        return (listOfNotNull(snapshot.current) + snapshot.upcoming).filter { it.id !in rejectedIds }
    }

    fun clusterByZone(candidates: List<ParkingSegment>): List<SpotCluster> =
        candidates.groupBy { it.zoneType }.flatMap { (zone, segments) ->
            clusterByProximity(segments).map { SpotCluster(zone, it) }
        }

    /**
     * Single-linkage grouping (union-find) of segments within
     * CLUSTER_EPS_M of each other -- chains of nearby segments merge
     * transitively, mirroring how backend/clustering.py groups segments
     * server-side, just applied here purely for how spots are drawn, not
     * for ranking/selection (that stays entirely server-side).
     */
    private fun clusterByProximity(segments: List<ParkingSegment>): List<List<ParkingSegment>> {
        if (segments.size <= 1) return listOf(segments)
        val parent = IntArray(segments.size) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }
        for (i in segments.indices) {
            for (j in i + 1 until segments.size) {
                if (haversineMeters(segments[i], segments[j]) <= CLUSTER_EPS_M) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[ri] = rj
                }
            }
        }
        return segments.indices.groupBy(::find).values.map { idxs -> idxs.map { segments[it] } }
    }

    private fun haversineMeters(a: ParkingSegment, b: ParkingSegment): Double {
        val earthRadiusM = 6_371_000.0
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earthRadiusM * asin(sqrt(h))
    }

    fun centroidLatLng(segments: List<ParkingSegment>): GeoPoint =
        GeoPoint(segments.map { it.lat }.average(), segments.map { it.lon }.average())

    fun clusterTitle(cluster: SpotCluster): String {
        val zoneLabel = if (cluster.zoneType == ZoneType.BLUE) "Blue zone" else "White zone"
        val count = cluster.segments.size
        return "$zoneLabel · $count spot${if (count == 1) "" else "s"}"
    }

    /**
     * A rectangle aligned with the cluster's own spread direction (the two
     * most-distant segments in it), not a plain north-aligned box --
     * parking segments run along streets, which are rarely north-south/
     * east-west, so a plain axis-aligned box would either clip a diagonal
     * row of spots or be far larger than the actual curb it represents.
     * Projected onto the map this renders as a parallelogram whenever the
     * street itself isn't axis-aligned, which is most of the time.
     *
     * Uses a flat local-meters approximation around the cluster's own
     * centroid; fine at the scale these clusters actually span (tens of
     * meters), not meant for anything larger.
     */
    fun orientedBoxCorners(segments: List<ParkingSegment>): List<GeoPoint> {
        val center = centroidLatLng(segments)
        val metersPerDegLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01)

        fun toLocal(segment: ParkingSegment): Pair<Double, Double> =
            (segment.lon - center.longitude) * metersPerDegLon to (segment.lat - center.latitude) * METERS_PER_DEGREE_LAT

        val points = segments.map(::toLocal)

        var axisX = 0.0
        var axisY = 1.0
        if (points.size > 1) {
            var maxDist = -1.0
            for (i in points.indices) {
                for (j in i + 1 until points.size) {
                    val dx = points[j].first - points[i].first
                    val dy = points[j].second - points[i].second
                    val dist = hypot(dx, dy)
                    if (dist > maxDist) {
                        maxDist = dist
                        if (dist > 0) {
                            axisX = dx / dist
                            axisY = dy / dist
                        }
                    }
                }
            }
        }
        val perpX = -axisY
        val perpY = axisX

        var minAlong = 0.0
        var maxAlong = 0.0
        var minAcross = 0.0
        var maxAcross = 0.0
        points.forEachIndexed { i, (x, y) ->
            val along = x * axisX + y * axisY
            val across = x * perpX + y * perpY
            if (i == 0) {
                minAlong = along; maxAlong = along
                minAcross = across; maxAcross = across
            } else {
                minAlong = minOf(minAlong, along); maxAlong = maxOf(maxAlong, along)
                minAcross = minOf(minAcross, across); maxAcross = maxOf(maxAcross, across)
            }
        }
        minAlong -= BOX_PAD_ALONG_M; maxAlong += BOX_PAD_ALONG_M
        minAcross -= BOX_PAD_ACROSS_M; maxAcross += BOX_PAD_ACROSS_M

        fun corner(along: Double, across: Double): GeoPoint {
            val localX = axisX * along + perpX * across
            val localY = axisY * along + perpY * across
            return GeoPoint(
                center.latitude + localY / METERS_PER_DEGREE_LAT,
                center.longitude + localX / metersPerDegLon,
            )
        }
        return listOf(
            corner(minAlong, minAcross),
            corner(maxAlong, minAcross),
            corner(maxAlong, maxAcross),
            corner(minAlong, maxAcross),
        )
    }

    fun boundsForRadius(center: GeoPoint, radiusM: Double): BoundingBox {
        val latDeltaDeg = radiusM / METERS_PER_DEGREE_LAT
        val lonDeltaDeg = radiusM / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01))
        return BoundingBox(
            center.latitude + latDeltaDeg, // north
            center.longitude + lonDeltaDeg, // east
            center.latitude - latDeltaDeg, // south
            center.longitude - lonDeltaDeg, // west
        )
    }
}
