package dev.lammertsma.parkingblues.car.car

import dev.lammertsma.parkingblues.shared.model.LatLon
import dev.lammertsma.parkingblues.shared.model.ParkingSegment
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import dev.lammertsma.parkingblues.shared.model.SessionState
import dev.lammertsma.parkingblues.shared.model.ZoneType
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapClusteringTest {

    private val baseLat = 47.3769
    private val baseLon = 8.5417
    private val metersPerDegLat = MapClustering.METERS_PER_DEGREE_LAT

    /** A segment [northM] meters north of the base point. */
    private fun segment(id: String, northM: Double, zone: ZoneType = ZoneType.BLUE, eastM: Double = 0.0) =
        ParkingSegment(
            id = id,
            lat = baseLat + northM / metersPerDegLat,
            lon = baseLon + eastM / (metersPerDegLat * cos(Math.toRadians(baseLat))),
            zoneType = zone,
            addressLabel = id,
            estimatedCapacity = 1,
            distanceM = 0.0,
            distanceFromYouM = 0.0,
        )

    private fun snapshot(
        current: ParkingSegment?,
        upcoming: List<ParkingSegment>,
        rejected: List<ParkingSegment>,
    ) = SessionSnapshot(
        sessionId = "s",
        state = SessionState.SEARCHING,
        radiusM = 300.0,
        origin = LatLon(baseLat, baseLon),
        you = LatLon(baseLat, baseLon),
        current = current,
        upcoming = upcoming,
        rejected = rejected,
        rejectedCount = rejected.size,
    )

    @Test
    fun activeCandidates_combinesCurrentAndUpcomingMinusRejected() {
        val a = segment("a", 0.0)
        val b = segment("b", 100.0)
        val c = segment("c", 200.0)
        val result = MapClustering.activeCandidates(snapshot(current = a, upcoming = listOf(b, c), rejected = listOf(b)))
        assertEquals(listOf("a", "c"), result.map { it.id })
    }

    @Test
    fun activeCandidates_handlesNoCurrent() {
        val b = segment("b", 100.0)
        val result = MapClustering.activeCandidates(snapshot(current = null, upcoming = listOf(b), rejected = emptyList()))
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun adjacentSpotsMergeIntoOneCluster() {
        val clusters = MapClustering.clusterByZone(listOf(segment("a", 0.0), segment("b", 5.0)))
        assertEquals(1, clusters.size)
        assertEquals(2, clusters.single().segments.size)
    }

    @Test
    fun distantSpotsStaySeparate() {
        val clusters = MapClustering.clusterByZone(listOf(segment("a", 0.0), segment("b", 50.0)))
        assertEquals(2, clusters.size)
    }

    @Test
    fun chainsMergeTransitively_evenWhenEndsAreFartherThanEps() {
        // 8m apart each: a-b and b-c are within CLUSTER_EPS_M, a-c (16m) is not.
        val clusters = MapClustering.clusterByZone(
            listOf(segment("a", 0.0), segment("b", 8.0), segment("c", 16.0)),
        )
        assertEquals(1, clusters.size)
        assertEquals(setOf("a", "b", "c"), clusters.single().segments.map { it.id }.toSet())
    }

    @Test
    fun aSingleGapLargerThanEpsSplitsAChain() {
        val clusters = MapClustering.clusterByZone(
            listOf(segment("a", 0.0), segment("b", 8.0), segment("c", 30.0), segment("d", 38.0)),
        )
        assertEquals(2, clusters.size)
    }

    @Test
    fun differentZonesNeverMerge_evenWhenOverlapping() {
        val clusters = MapClustering.clusterByZone(
            listOf(segment("a", 0.0, ZoneType.BLUE), segment("b", 0.0, ZoneType.WHITE)),
        )
        assertEquals(2, clusters.size)
        assertEquals(setOf(ZoneType.BLUE, ZoneType.WHITE), clusters.map { it.zoneType }.toSet())
    }

    @Test
    fun clusterByZone_ofNothingIsEmpty() {
        assertTrue(MapClustering.clusterByZone(emptyList()).isEmpty())
    }

    @Test
    fun clusterTitle_pluralizesAndNamesTheZone() {
        val one = SpotCluster(ZoneType.BLUE, listOf(segment("a", 0.0)))
        val three = SpotCluster(ZoneType.WHITE, listOf(segment("a", 0.0), segment("b", 5.0), segment("c", 10.0)))
        assertEquals("Blue zone · 1 spot", MapClustering.clusterTitle(one))
        assertEquals("White zone · 3 spots", MapClustering.clusterTitle(three))
    }

    @Test
    fun centroid_isTheMeanOfTheSegments() {
        val centroid = MapClustering.centroidLatLng(listOf(segment("a", 0.0), segment("b", 10.0)))
        assertEquals(baseLat + 5.0 / metersPerDegLat, centroid.latitude, 1e-9)
        assertEquals(baseLon, centroid.longitude, 1e-9)
    }

    @Test
    fun orientedBox_hasFourCornersEnclosingEverySegment() {
        val segments = listOf(segment("a", 0.0), segment("b", 10.0), segment("c", 20.0))
        val corners = MapClustering.orientedBoxCorners(segments)
        assertEquals(4, corners.size)

        // North-south row: every segment must fall inside the corners' lat/lon extent.
        val minLat = corners.minOf { it.latitude }
        val maxLat = corners.maxOf { it.latitude }
        val minLon = corners.minOf { it.longitude }
        val maxLon = corners.maxOf { it.longitude }
        segments.forEach {
            assertTrue(it.lat in minLat..maxLat, "lat ${it.lat} outside [$minLat, $maxLat]")
            assertTrue(it.lon in minLon..maxLon, "lon ${it.lon} outside [$minLon, $maxLon]")
        }
    }

    @Test
    fun orientedBox_followsTheRowDirectionAndIsPaddedMoreAlongThanAcross() {
        val corners = MapClustering.orientedBoxCorners(
            listOf(segment("a", 0.0), segment("b", 10.0), segment("c", 20.0)),
        )
        val heightM = (corners.maxOf { it.latitude } - corners.minOf { it.latitude }) * metersPerDegLat
        val widthM = (corners.maxOf { it.longitude } - corners.minOf { it.longitude }) *
            metersPerDegLat * cos(Math.toRadians(baseLat))
        assertEquals(20.0 + 2 * MapClustering.BOX_PAD_ALONG_M, heightM, 0.05)
        assertEquals(2 * MapClustering.BOX_PAD_ACROSS_M, widthM, 0.05)
    }

    @Test
    fun orientedBox_ofASingleSpotIsAPaddedBoxAroundIt() {
        val corners = MapClustering.orientedBoxCorners(listOf(segment("a", 0.0)))
        assertEquals(4, corners.size)
        val heightM = (corners.maxOf { it.latitude } - corners.minOf { it.latitude }) * metersPerDegLat
        assertEquals(2 * MapClustering.BOX_PAD_ALONG_M, heightM, 0.05)
    }

    @Test
    fun orientedBox_ofAnEastWestRowIsWiderThanTall() {
        val corners = MapClustering.orientedBoxCorners(
            listOf(segment("a", 0.0, eastM = 0.0), segment("b", 0.0, eastM = 20.0)),
        )
        val heightM = (corners.maxOf { it.latitude } - corners.minOf { it.latitude }) * metersPerDegLat
        val widthM = (corners.maxOf { it.longitude } - corners.minOf { it.longitude }) *
            metersPerDegLat * cos(Math.toRadians(baseLat))
        assertTrue(widthM > heightM, "width $widthM should exceed height $heightM")
    }

    @Test
    fun boundsForRadius_isSymmetricAboutTheCenterAndAccountsForLongitudeShrinkage() {
        val center = GeoPoint(baseLat, baseLon)
        val bounds = MapClustering.boundsForRadius(center, 300.0)

        assertEquals(300.0 / metersPerDegLat, bounds.latNorth - baseLat, 1e-9)
        assertEquals(300.0 / metersPerDegLat, baseLat - bounds.latSouth, 1e-9)
        assertEquals(bounds.lonEast - baseLon, baseLon - bounds.lonWest, 1e-9)

        // A degree of longitude is shorter than a degree of latitude at this
        // latitude, so the same radius spans more degrees east-west.
        val latSpanDeg = bounds.latNorth - baseLat
        val lonSpanDeg = bounds.lonEast - baseLon
        assertEquals(1 / cos(Math.toRadians(baseLat)), lonSpanDeg / latSpanDeg, 1e-6)
        assertTrue(abs(lonSpanDeg) > abs(latSpanDeg))
    }
}
