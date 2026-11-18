package dev.lammertsma.parkingblues.car.car

import kotlin.test.Test
import kotlin.test.assertEquals

class TileMathTest {
    @Test
    fun zurichAtZoom16() {
        // Checked against the tile server: this tile exists (200); Berlin's does not.
        assertEquals(34322 to 22949, TileMath.tileXY(47.3769, 8.5417, 16))
    }

    @Test
    fun berlinAtZoom16() {
        assertEquals(35208 to 21492, TileMath.tileXY(52.52, 13.405, 16))
    }

    @Test
    fun theWholeWorldIsOneTileAtZoomZero() {
        assertEquals(0 to 0, TileMath.tileXY(47.3769, 8.5417, 0))
    }

    @Test
    fun coordinatesAtTheEdgeStayInsideTheGrid() {
        assertEquals(1 to 0, TileMath.tileXY(85.0, 180.0, 1))
    }
}
