package dev.lammertsma.parkingblues.car.car

import dev.lammertsma.parkingblues.shared.model.LatLon
import dev.lammertsma.parkingblues.shared.model.ParkingSegment
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import dev.lammertsma.parkingblues.shared.model.SessionState
import dev.lammertsma.parkingblues.shared.model.ZoneType
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParkedSpotFactoryTest {
    private val baseLat = 47.3769
    private val baseLon = 8.5417
    private val metersPerDegLat = MapClustering.METERS_PER_DEGREE_LAT

    private fun segment(
        id: String,
        northM: Double,
        zone: ZoneType = ZoneType.BLUE,
        legalUntil: String? = null,
        fee: Double? = null,
    ) = ParkingSegment(
        id = id,
        lat = baseLat + northM / metersPerDegLat,
        lon = baseLon + 0.0 / (metersPerDegLat * cos(Math.toRadians(baseLat))),
        zoneType = zone,
        addressLabel = "Street $id",
        estimatedCapacity = 1,
        distanceM = 0.0,
        distanceFromYouM = northM,
        legalUntil = legalUntil,
        estimatedFeeChfPerHour = fee,
    )

    private fun snapshot(vararg segments: ParkingSegment, rejected: List<ParkingSegment> = emptyList()) = SessionSnapshot(
        sessionId = "s", state = SessionState.SEARCHING, radiusM = 300.0,
        origin = LatLon(baseLat, baseLon), you = LatLon(baseLat, baseLon),
        current = segments.firstOrNull(), upcoming = segments.drop(1),
        rejected = rejected, rejectedCount = rejected.size,
    )

    @Test
    fun aSpotRightBesideTheCarGivesItsZoneAndTime() {
        val blue = segment("a", northM = 6.0, legalUntil = "2026-11-18T11:30:00+01:00")
        val spot = ParkedSpotFactory.at(snapshot(blue), baseLat, baseLon, nowMillis = 5L, assumed = true)

        assertEquals(ZoneType.BLUE, spot.zoneType)
        assertEquals("Street a", spot.addressLabel)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-18T11:30:00+01:00").toInstant().toEpochMilli(), spot.expiresAtEpochMillis)
        assertTrue(spot.assumed)
        assertEquals(5L, spot.confirmedAtEpochMillis)
        assertEquals(baseLat, spot.lat)
    }

    @Test
    fun aWhiteZoneSpotKeepsItsFeeAndHasNoDeadline() {
        val white = segment("w", northM = 3.0, zone = ZoneType.WHITE, fee = 2.0)
        val spot = ParkedSpotFactory.at(snapshot(white), baseLat, baseLon, 0L, assumed = false)

        assertEquals(ZoneType.WHITE, spot.zoneType)
        assertEquals(2.0, spot.estimatedFeeChfPerHour)
        assertNull(spot.expiresAtEpochMillis)
        assertFalse(spot.assumed)
    }

    @Test
    fun theNearestOfSeveralIsUsed() {
        val far = segment("far", northM = 20.0, zone = ZoneType.WHITE)
        val near = segment("near", northM = 4.0, zone = ZoneType.BLUE)
        assertEquals("Street near", ParkedSpotFactory.at(snapshot(far, near), baseLat, baseLon, 0L, true).addressLabel)
    }

    @Test
    fun nothingCloseEnoughMeansJustAPlace() {
        val spot = ParkedSpotFactory.at(
            snapshot(segment("a", northM = ParkedSpotFactory.MATCH_RADIUS_M + 10)),
            baseLat, baseLon, 0L, assumed = true,
        )
        assertNull(spot.zoneType)
        assertNull(spot.addressLabel)
        assertNull(spot.expiresAtEpochMillis)
    }

    @Test
    fun theDestinationTheDriverWasHeadingToIsRememberedWithTheSpot() {
        val destination = LatLon(47.3801, 8.5455)
        val spot = ParkedSpotFactory.at(null, baseLat, baseLon, 0L, assumed = true, destination = destination)
        assertEquals(destination, spot.destination)
    }

    @Test
    fun withoutAChosenDestinationThereIsNone() {
        assertNull(ParkedSpotFactory.at(null, baseLat, baseLon, 0L, assumed = false).destination)
    }

    @Test
    fun noSessionMeansJustAPlace() {
        val spot = ParkedSpotFactory.at(null, baseLat, baseLon, 0L, assumed = true)
        assertNull(spot.zoneType)
        assertTrue(spot.assumed)
    }

    @Test
    fun aSpotTheDriverAlreadyDroveByDoesNotDescribeThePlace() {
        // Rejected spots are not "active candidates": they are not what the server offers any more.
        val passed = segment("passed", northM = 2.0)
        val spot = ParkedSpotFactory.at(snapshot(rejected = listOf(passed)), baseLat, baseLon, 0L, true)
        assertNull(spot.addressLabel)
    }
}
