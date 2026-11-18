package dev.lammertsma.parkingblues.car.car

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportFitTest {
    private val padding = ViewportFit.Padding(top = 400.0, bottom = 250.0, sides = 100.0)

    // Two points about 1.5 km apart, north-south, in Zurich.
    private val points = listOf(47.3779 to 8.5403, 47.3910 to 8.5430)

    private fun fit(width: Int = 1280, height: Int = 2400, pad: ViewportFit.Padding = padding, maxZoom: Double = 20.0) =
        ViewportFit.fit(points, width, height, pad, minZoom = 14.0, maxZoom = maxZoom)

    /** Where a latitude falls on screen when the view is centred on the box and then scrolled. */
    private fun screenY(result: ViewportFit.Result, lat: Double, height: Int): Double {
        fun y(l: Double): Double {
            val s = kotlin.math.sin(Math.toRadians(l))
            return 0.5 - ln((1 + s) / (1 - s)) / (4 * Math.PI)
        }
        val world = 256.0 * Math.pow(2.0, result.zoom)
        return height / 2.0 + (y(lat) - y(result.centerLat)) * world + result.scrollDownPx
    }

    @Test
    fun theTopPointStaysBelowTheTopPaddingAndTheBottomOneAboveTheBottomPadding() {
        val result = fit()
        val north = screenY(result, 47.3910, 2400)
        val south = screenY(result, 47.3779, 2400)

        assertTrue(north >= padding.top - 1, "north point at $north")
        assertTrue(south <= 2400 - padding.bottom + 1, "south point at $south")
    }

    @Test
    fun theBoxFillsTheFreeAreaInTheLimitingDirection() {
        val result = fit()
        val north = screenY(result, 47.3910, 2400)
        val south = screenY(result, 47.3779, 2400)
        // Tall and narrow box on a tall free area: height is what limits the zoom.
        assertEquals(2400 - padding.top - padding.bottom, south - north, 2.0)
    }

    @Test
    fun morePaddingOnTopMeansMoreZoomedOut() {
        val roomy = fit(pad = ViewportFit.Padding(top = 200.0, bottom = 250.0, sides = 100.0))
        val tight = fit(pad = ViewportFit.Padding(top = 900.0, bottom = 250.0, sides = 100.0))
        assertTrue(tight.zoom < roomy.zoom)
    }

    @Test
    fun widePointsAreLimitedByTheWidthNotTheHeight() {
        val wide = listOf(47.3779 to 8.50, 47.3780 to 8.58)
        val result = ViewportFit.fit(wide, 1280, 2400, padding, 14.0, 20.0)
        val worldWidth = 256.0 * Math.pow(2.0, result.zoom)
        assertEquals(1280 - 2 * padding.sides, 0.08 / 360.0 * worldWidth, 2.0)
    }

    @Test
    fun theZoomIsCappedSoTwoNearbyPointsAreNotZoomedToTheMaximum() {
        val near = listOf(47.37790 to 8.54030, 47.37795 to 8.54035)
        assertEquals(18.0, ViewportFit.fit(near, 1280, 2400, padding, 14.0, 18.0).zoom)
    }

    @Test
    fun theZoomNeverGoesBelowTheMapsMinimum() {
        val far = listOf(47.30 to 8.45, 47.46 to 8.64)
        assertEquals(14.0, ViewportFit.fit(far, 400, 600, padding, 14.0, 20.0).zoom)
    }

    @Test
    fun theBoxIsCentredInTheFreeAreaNotTheWholeScreen() {
        val result = fit()
        // Free area centre is (400 - 250) / 2 below the screen centre.
        assertEquals(75.0, result.scrollDownPx)
        assertTrue(abs(result.centerLat - (47.3779 + 47.3910) / 2) < 1e-9)
    }

    @Test
    fun aPaddingBiggerThanTheViewStillGivesAUsableZoom() {
        val result = fit(width = 300, height = 500, pad = ViewportFit.Padding(400.0, 400.0, 200.0))
        assertTrue(result.zoom >= 14.0)
    }
}
