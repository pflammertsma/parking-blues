package com.parkingblues.car.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestLocationTest {

    @Test
    fun simulatedRoute_generatesValidFixesWithinRequestedSpeed() {
        val steps = getSimulatedRouteSteps()
        assertTrue(steps.size > 50, "Simulated route should have enough steps for a multi-minute drive")

        // Check first step starts near waypoints start
        val firstFix = steps.first()
        assertEquals(SIMULATED_ROUTE_WAYPOINTS.first().first, firstFix.lat, 0.0001)
        assertEquals(SIMULATED_ROUTE_WAYPOINTS.first().second, firstFix.lon, 0.0001)

        // Check speed between consecutive steps: 10 to 20 km/h = ~2.78 to 5.56 m/s
        for (i in 0 until steps.size - 1) {
            val s1 = steps[i]
            val s2 = steps[i + 1]
            assertNotNull(s1.bearingDegrees, "Step $i must have a bearing")
            assertTrue(s1.bearingDegrees in 0.0f..360.0f, "Bearing must be in [0, 360]")

            val dist = calculateDistanceMeters(s1.lat, s1.lon, s2.lat, s2.lon)
            val speedKmh = dist * 3.6 // 1 second per step
            assertTrue(
                speedKmh in 20.0..45.0,
                "Speed at step $i ($speedKmh km/h) should be within ~30 km/h range"
            )
        }
    }

    @Test
    fun calculateBearing_pointsInCorrectDirection() {
        // Heading East
        val bearingEast = calculateBearingDegrees(47.0, 8.0, 47.0, 8.01)
        assertTrue(bearingEast in 80f..100f, "Heading East should be ~90 deg, was $bearingEast")

        // Heading South
        val bearingSouth = calculateBearingDegrees(47.01, 8.0, 47.0, 8.0)
        assertTrue(bearingSouth in 170f..190f, "Heading South should be ~180 deg, was $bearingSouth")
    }
}
