package dev.lammertsma.parkingblues.car.car

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * Frames a set of points in the part of the map the driver can actually see.
 *
 * osmdroid's own `zoomToBoundingBox` takes one border for all four sides, but the map is not
 * uncovered evenly: the parked bar and the "Search here" button sit over the top, the bottom sheet
 * over the bottom, and the sides are free. A border big enough for the top squeezes the width to
 * nothing, and one that suits the sides leaves points behind the buttons. So each side has its own
 * padding here, and the points are centred in what is left.
 *
 * Pure Web-Mercator maths (no map view), so it is unit tested.
 */
object ViewportFit {

    data class Padding(val top: Double, val bottom: Double, val sides: Double)

    /**
     * @property zoom the zoom to set (fractional).
     * @property centerLat, [centerLon] the box's centre; the view must then be scrolled by
     *   [scrollDownPx] so that the box sits in the middle of the free area, not of the screen
     *   (positive: the content moves down, i.e. scroll the view up by that many pixels).
     */
    data class Result(val zoom: Double, val centerLat: Double, val centerLon: Double, val scrollDownPx: Double)

    /** [mapSize] is the width in pixels of the whole world at a zoom (256 * 2^zoom for 256 px tiles). */
    fun fit(
        points: List<Pair<Double, Double>>,
        viewWidthPx: Int,
        viewHeightPx: Int,
        padding: Padding,
        minZoom: Double,
        maxZoom: Double,
        mapSize: (Double) -> Double = { zoom -> 256.0 * Math.pow(2.0, zoom) },
    ): Result {
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }

        // The box as fractions of the whole world (0..1), so it scales with the zoom.
        val widthFraction = (maxLon - minLon) / 360.0
        val heightFraction = mercatorY(minLat) - mercatorY(maxLat) // y grows southwards

        val freeWidth = (viewWidthPx - 2 * padding.sides).coerceAtLeast(1.0)
        val freeHeight = (viewHeightPx - padding.top - padding.bottom).coerceAtLeast(1.0)

        // The largest zoom at which the box still fits the free area.
        val unitSize = mapSize(0.0)
        val zoomForWidth = if (widthFraction <= 0.0) maxZoom else log2(freeWidth / (widthFraction * unitSize))
        val zoomForHeight = if (heightFraction <= 0.0) maxZoom else log2(freeHeight / (heightFraction * unitSize))
        val zoom = minOf(zoomForWidth, zoomForHeight, maxZoom).coerceAtLeast(minZoom)

        return Result(
            zoom = zoom,
            centerLat = (minLat + maxLat) / 2.0,
            centerLon = (minLon + maxLon) / 2.0,
            // The free area's centre is (top - bottom) / 2 below the view's centre.
            scrollDownPx = (padding.top - padding.bottom) / 2.0,
        )
    }

    private fun mercatorY(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-85.0511, 85.0511)))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}
