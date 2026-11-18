package dev.lammertsma.parkingblues.car.car

import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteProgressTest {

    private val lat = 47.3769
    private val metersPerDegreeLon = 111_320.0 * kotlin.math.cos(Math.toRadians(47.3769))

    /** A point [eastM] metres east of the route's start and [northM] north. */
    private fun at(eastM: Double, northM: Double = 0.0) =
        GeoPoint(lat + northM / 111_320.0, 8.5417 + eastM / metersPerDegreeLon)

    /** A straight 400 m route east, as five points 100 m apart. */
    private val straight = (0..4).map { at(it * 100.0) }

    @Test
    fun atTheStartNothingHasBeenDriven() {
        val split = RouteProgress.split(straight, at(0.0))
        assertEquals(1, split.traveled.size)
        assertEquals(5, split.remaining.size)
    }

    @Test
    fun midwayTheRouteIsCutAtTheDriversPosition() {
        val split = RouteProgress.split(straight, at(250.0, northM = 8.0))

        assertEquals(2, split.index)
        // Traveled ends exactly where remaining begins, on the line (not at the off-line GPS fix).
        assertEquals(split.traveled.last(), split.remaining.first())
        assertTrue(abs(split.traveled.last().longitude - at(250.0).longitude) < 1e-6)
        assertEquals(4, split.traveled.size) // 3 points passed + the cut
        assertEquals(3, split.remaining.size) // the cut + 2 points to come
    }

    @Test
    fun progressNeverGoesBackwards() {
        val ahead = RouteProgress.split(straight, at(350.0))
        val back = RouteProgress.split(straight, at(120.0), previousIndex = ahead.index)
        assertTrue(back.index >= ahead.index)
    }

    @Test
    fun aFarOffRoutePositionKeepsThePreviousProgress() {
        val onRoute = RouteProgress.split(straight, at(250.0))
        val lost = RouteProgress.split(straight, at(260.0, northM = 400.0), previousIndex = onRoute.index)

        assertEquals(onRoute.index, lost.index)
        assertEquals(straight.subList(0, onRoute.index + 1), lost.traveled)
        assertEquals(straight.subList(onRoute.index, straight.size), lost.remaining)
    }

    @Test
    fun noPositionKeepsThePreviousProgress() {
        val split = RouteProgress.split(straight, null, previousIndex = 2)
        assertEquals(2, split.index)
        assertEquals(3, split.traveled.size)
        assertEquals(3, split.remaining.size)
    }

    @Test
    fun aRouteThatDoublesBackDoesNotJumpToItsSecondHalf() {
        // Out 300 m east, round the block, and back along the same street: the first and last
        // legs run over the same ground.
        val loop = listOf(at(0.0), at(150.0), at(300.0), at(300.0, 40.0), at(150.0, 40.0), at(0.0, 40.0), at(0.0))
        val split = RouteProgress.split(loop, at(150.0, 3.0))
        // The first leg or the start of the second (a tie at the corner), never the return leg.
        assertTrue(split.index <= 1, "index ${split.index}")
    }

    @Test
    fun theWindowKeepsTheMatchNearThePreviousProgress() {
        val long = (0..400).map { at(it * 10.0) }
        // Standing at the far end while progress is still at the start: out of the window, so
        // it is treated as off the route instead of jumping 4 km ahead.
        val split = RouteProgress.split(long, at(4000.0), previousIndex = 0)
        assertEquals(0, split.index)
    }

    @Test
    fun aRouteTooShortToSplitIsAllRemaining() {
        val split = RouteProgress.split(listOf(at(0.0)), at(0.0))
        assertTrue(split.traveled.isEmpty())
        assertEquals(1, split.remaining.size)
    }
}
