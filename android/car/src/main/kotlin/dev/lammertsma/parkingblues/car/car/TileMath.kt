package dev.lammertsma.parkingblues.car.car

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/** Web-Mercator slippy-map tile numbers, the scheme the CARTO tiles (and osmdroid) use. */
object TileMath {
    /** The tile (x, y) containing [lat], [lon] at integer [zoom]. */
    fun tileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        val y = floor((1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n).toInt()
        return x.coerceIn(0, n - 1) to y.coerceIn(0, n - 1)
    }
}
