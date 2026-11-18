package dev.lammertsma.parkingblues.car.car

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageAreaTest {
    @Test
    fun theCityCentreAndItsOutskirtsAreInside() {
        assertTrue(CoverageArea.contains(47.3769, 8.5417)) // main station
        assertTrue(CoverageArea.contains(47.4105, 8.5440)) // Oerlikon
        assertTrue(CoverageArea.contains(47.3300, 8.4700)) // the data's south-west corner
    }

    @Test
    fun otherCitiesAndNearbyTownsAreOutside() {
        assertFalse(CoverageArea.contains(52.52, 13.405)) // Berlin
        assertFalse(CoverageArea.contains(47.4990, 8.7240)) // Winterthur
        assertFalse(CoverageArea.contains(47.1660, 8.5155)) // Zug
    }

    @Test
    fun aPointJustBeyondAnEdgeIsOutside() {
        assertFalse(CoverageArea.contains(CoverageArea.SOUTH - 0.001, 8.5))
        assertFalse(CoverageArea.contains(47.4, CoverageArea.EAST + 0.001))
    }
}
