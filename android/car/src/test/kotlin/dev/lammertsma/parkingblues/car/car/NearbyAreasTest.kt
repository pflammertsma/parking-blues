package dev.lammertsma.parkingblues.car.car

import dev.lammertsma.parkingblues.shared.model.LatLon
import dev.lammertsma.parkingblues.shared.model.ParkingSegment
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import dev.lammertsma.parkingblues.shared.model.SessionState
import dev.lammertsma.parkingblues.shared.model.ZoneType
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearbyAreasTest {

    private val baseLat = 47.3769
    private val baseLon = 8.5417
    private val metersPerDegLat = MapClustering.METERS_PER_DEGREE_LAT

    /** A segment [northM] / [eastM] metres from the driver, who stands at the base point. */
    private fun segment(
        id: String,
        northM: Double,
        eastM: Double = 0.0,
        zone: ZoneType = ZoneType.BLUE,
        capacity: Int = 1,
        legalUntil: String? = null,
        maxMinutes: Int? = null,
        fee: Double? = null,
    ) = ParkingSegment(
        id = id,
        lat = baseLat + northM / metersPerDegLat,
        lon = baseLon + eastM / (metersPerDegLat * cos(Math.toRadians(baseLat))),
        zoneType = zone,
        addressLabel = id,
        estimatedCapacity = capacity,
        maxDurationMinutes = maxMinutes,
        distanceM = 0.0,
        distanceFromYouM = kotlin.math.hypot(northM, eastM),
        legalUntil = legalUntil,
        estimatedFeeChfPerHour = fee,
    )

    private fun snapshot(current: ParkingSegment?, upcoming: List<ParkingSegment>, rejected: List<ParkingSegment> = emptyList()) =
        SessionSnapshot(
            sessionId = "s", state = SessionState.SEARCHING, radiusM = 300.0,
            origin = LatLon(baseLat, baseLon), you = LatLon(baseLat, baseLon),
            current = current, upcoming = upcoming, rejected = rejected, rejectedCount = rejected.size,
        )

    @Test
    fun adjacentSpotsBecomeOneAreaAndFarOnesStaySeparate() {
        val near = listOf(segment("a", 100.0), segment("b", 105.0), segment("c", 110.0))
        val far = segment("d", 250.0)
        val areas = NearbyAreas.top(snapshot(near[0], near.drop(1) + far), limit = 5)

        assertEquals(2, areas.size)
        assertEquals(setOf("a", "b", "c"), areas.first().cluster.segments.map { it.id }.toSet())
        assertEquals(3, areas.first().capacity)
    }

    @Test
    fun areasFollowTheBackendsOrderNotJustDistance() {
        // The backend ranks "far" ahead of "close" (say, it is straight ahead); so do we.
        val far = segment("far", 200.0)
        val close = segment("close", 30.0, eastM = 40.0)
        val areas = NearbyAreas.top(snapshot(far, listOf(close)), limit = 5)

        assertEquals(listOf("far", "close"), areas.map { it.cluster.segments.single().id })
    }

    @Test
    fun anAreaIsRankedByItsBestSpotAndMeasuredFromItsNearestSpot() {
        val a = segment("a", 200.0)
        val b = segment("b", 60.0, eastM = 80.0)
        val c = segment("c", 205.0)          // joins a's area, ranked after b
        val areas = NearbyAreas.top(snapshot(a, listOf(b, c)), limit = 5)

        assertEquals(setOf("a", "c"), areas.first().cluster.segments.map { it.id }.toSet())
        assertEquals(0, areas.first().rank)
        assertEquals(200, areas.first().distanceM)
    }

    @Test
    fun theListIsCappedAndSkipsRejectedSpots() {
        val spots = (1..8).map { segment("s$it", it * 40.0) }
        val rejected = listOf(spots[0])
        val areas = NearbyAreas.top(snapshot(spots[0], spots.drop(1), rejected), limit = 3)

        assertEquals(3, areas.size)
        assertTrue(areas.none { area -> area.cluster.segments.any { it.id == "s1" } })
    }

    @Test
    fun noCandidatesMeansNoAreas() {
        assertTrue(NearbyAreas.top(snapshot(null, emptyList()), limit = 5).isEmpty())
    }

    @Test
    fun compassDirectionsCoverTheEightPoints() {
        fun dir(northM: Double, eastM: Double) = NearbyAreas.compassDirection(
            baseLat, baseLon,
            baseLat + northM / metersPerDegLat,
            baseLon + eastM / (metersPerDegLat * cos(Math.toRadians(baseLat))),
        )
        assertEquals("N", dir(100.0, 0.0))
        assertEquals("NE", dir(100.0, 100.0))
        assertEquals("E", dir(0.0, 100.0))
        assertEquals("SE", dir(-100.0, 100.0))
        assertEquals("S", dir(-100.0, 0.0))
        assertEquals("SW", dir(-100.0, -100.0))
        assertEquals("W", dir(0.0, -100.0))
        assertEquals("NW", dir(100.0, -100.0))
    }

    @Test
    fun blueZoneRowsShowTheDeadlineAndWhiteZoneRowsTheCapAndFee() {
        val blue = NearbyAreas.top(
            snapshot(segment("b", 120.0, eastM = 120.0, capacity = 4, legalUntil = "2026-10-06T11:30:00+02:00"), emptyList()), 5,
        ).single()
        assertEquals("Blue zone · 170 m NE", NearbyAreas.title(blue))
        assertEquals("~4 spaces · park until ~11:30", NearbyAreas.summary(blue))

        val white = NearbyAreas.top(
            snapshot(segment("w", -80.0, zone = ZoneType.WHITE, capacity = 1, maxMinutes = 120, fee = 2.0), emptyList()), 5,
        ).single()
        assertEquals("White zone · 80 m S", NearbyAreas.title(white))
        assertEquals("~1 space · 120 min max · ~CHF 2.00/h", NearbyAreas.summary(white))
    }

    @Test
    fun blueZoneWithoutADeadlineIsUnrestricted() {
        val area = NearbyAreas.top(snapshot(segment("b", 50.0, legalUntil = null), emptyList()), 5).single()
        assertEquals("no time limit now", NearbyAreas.rules(area))
    }
}
