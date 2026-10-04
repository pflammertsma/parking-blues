package dev.lammertsma.parkingblues.car.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PositionFilterTest {

    private var now = 0L
    private fun filter() = PositionFilter { now }

    private val lat0 = 47.3700
    private val lon0 = 8.5400
    private val metersPerDegLat = 111_320.0

    private fun north(meters: Double) = lat0 + meters / metersPerDegLat

    private fun fix(northM: Double, speed: Float? = 0f, accuracy: Float? = 5f) =
        GpsFix(north(northM), lon0, null, speed, null, accuracy)

    private fun metersFromStart(p: Pair<Double, Double>) =
        calculateDistanceMeters(lat0, lon0, p.first, p.second)

    @Test
    fun firstFixIsAcceptedAsIs() {
        val f = filter()
        val p = f.onFix(fix(0.0))
        assertEquals(lat0, p.first, 1e-9)
    }

    @Test
    fun aSpuriousHopIsSlewedNotJumped() {
        val f = filter()
        now = 0; f.onFix(fix(0.0, speed = 8f))
        now = 1000
        // GPS claims we are 50 m away after one second at ~8 m/s.
        val p = f.onFix(fix(50.0, speed = 8f))
        // Cap is max(12, 8*1.5+6=18) m/s * 1 s = 18 m.
        assertTrue(metersFromStart(p) in 17.0..19.0, "moved ${metersFromStart(p)} m")
    }

    @Test
    fun hopAndBackNeverMovesFartherThanTheSpeedCap() {
        val f = filter()
        now = 0; f.onFix(fix(0.0, speed = 8f))
        var farthest = 0.0
        for (i in 1..6) {
            now = i * 500L
            val noisy = if (i % 2 == 1) 50.0 else 0.0
            farthest = maxOf(farthest, metersFromStart(f.onFix(fix(noisy, speed = 8f))))
        }
        assertTrue(farthest < 20.0, "arrow wandered $farthest m during a 50 m glitch")
    }

    @Test
    fun genuineFastDrivingIsNotHeldBack() {
        val f = filter()
        now = 0; f.onFix(fix(0.0, speed = 30f))
        now = 1000
        // 30 m in 1 s at a reported 30 m/s; cap is 30*1.5+6 = 51 m.
        val p = f.onFix(fix(30.0, speed = 30f))
        assertEquals(30.0, metersFromStart(p), 0.5)
    }

    @Test
    fun smallMovementWhileStationaryIsIgnored() {
        val f = filter()
        now = 0; f.onFix(fix(0.0, speed = 0f, accuracy = 10f))
        now = 1000
        val p = f.onFix(fix(6.0, speed = 0f, accuracy = 10f))
        assertEquals(0.0, metersFromStart(p), 0.01)
    }

    @Test
    fun realMovementWhileStationaryIsStillFollowed() {
        val f = filter()
        now = 0; f.onFix(fix(0.0, speed = 0f, accuracy = 5f))
        now = 1000
        val p = f.onFix(fix(8.5, speed = 0f, accuracy = 5f))
        assertTrue(metersFromStart(p) > 8.0)
    }

    @Test
    fun poorAccuracyFixesAreRejectedOnceWeHaveAGoodPosition() {
        val f = filter()
        now = 0; f.onFix(fix(0.0, speed = 8f, accuracy = 5f))
        now = 1000
        val p = f.onFix(fix(40.0, speed = 8f, accuracy = 300f))
        assertEquals(0.0, metersFromStart(p), 0.01)
    }

    @Test
    fun aPoorFirstFixIsReplacedOutrightByTheFirstGoodOne() {
        val f = filter()
        now = 0; f.onFix(fix(500.0, speed = null, accuracy = 400f))
        now = 1000
        val p = f.onFix(fix(0.0, speed = 5f, accuracy = 6f))
        assertEquals(0.0, metersFromStart(p), 0.01)
    }

    @Test
    fun fixesWithoutSpeedStillGetTheSlewLimit() {
        val f = filter()
        now = 0; f.onFix(fix(0.0, speed = null))
        now = 1000
        val p = f.onFix(fix(100.0, speed = null))
        assertTrue(metersFromStart(p) in 11.0..13.0, "moved ${metersFromStart(p)} m")
    }
}
