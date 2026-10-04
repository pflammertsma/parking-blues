package dev.lammertsma.parkingblues.car.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadingTrackerTest {

    private var now = 0L
    private fun tracker(useCompass: Boolean) = HeadingTracker(useCompass) { now }

    private fun moving(course: Float, speed: Float = 8f, accuracy: Float? = null) =
        GpsFix(47.37, 8.54, course, speed, accuracy)

    private fun standing(course: Float? = 123f) = GpsFix(47.37, 8.54, course, 0f)

    private fun assertHeading(expected: Float, actual: Float?, tolerance: Float = 1f) {
        assertNotNull(actual, "heading should be known")
        val diff = kotlin.math.abs(HeadingTracker.shortestDelta(expected, actual))
        assertTrue(diff <= tolerance, "expected ~$expected but was $actual")
    }

    @Test
    fun unknownUntilThereIsEvidence() {
        val t = tracker(useCompass = true)
        assertNull(t.heading)
        t.onFix(standing())
        assertNull(t.heading)
    }

    @Test
    fun whileMoving_followsTheGpsCourse() {
        val t = tracker(useCompass = true)
        assertHeading(90f, t.onFix(moving(90f)))
    }

    @Test
    fun atAStandstill_gpsCourseNoiseIsIgnored() {
        val t = tracker(useCompass = false)
        t.onFix(moving(90f))
        repeat(10) { t.onFix(standing(course = (it * 37f) % 360f)) }
        assertHeading(90f, t.heading)
    }

    @Test
    fun inTheCar_compassIsNeverUsed() {
        val t = tracker(useCompass = false)
        t.onFix(moving(90f))
        t.onFix(standing())
        repeat(30) { t.onCompass(270f) }
        assertHeading(90f, t.heading)
    }

    @Test
    fun onThePhone_stationaryFollowsTheCompass() {
        val t = tracker(useCompass = true)
        t.onFix(moving(90f))
        t.onFix(standing())
        repeat(60) { t.onCompass(180f) }
        assertHeading(180f, t.heading, tolerance = 3f)
    }

    @Test
    fun onThePhone_movingIgnoresTheCompass() {
        val t = tracker(useCompass = true)
        t.onFix(moving(90f))
        repeat(60) { t.onCompass(270f) }
        assertHeading(90f, t.heading)
    }

    @Test
    fun compassWorksBeforeAnyFixArrives() {
        val t = tracker(useCompass = true)
        t.onCompass(45f)
        assertHeading(45f, t.heading)
    }

    @Test
    fun hysteresis_aSlowRollAfterMovingStillCountsAsMoving() {
        val t = tracker(useCompass = true)
        t.onFix(moving(90f, speed = 5f))
        // 1.5 m/s is below the 2 m/s entry threshold but above the 1 m/s exit threshold.
        t.onFix(moving(100f, speed = 1.5f))
        repeat(30) { t.onCompass(270f) }
        assertHeading(100f, t.heading, tolerance = 15f)
    }

    @Test
    fun hysteresis_belowTheExitThresholdReleasesTheCompass() {
        val t = tracker(useCompass = true)
        t.onFix(moving(90f, speed = 5f))
        t.onFix(moving(90f, speed = 0.5f))
        repeat(80) { t.onCompass(270f) }
        assertHeading(270f, t.heading, tolerance = 3f)
    }

    @Test
    fun hysteresis_slowSpeedNeverStartsMoving() {
        val t = tracker(useCompass = true)
        t.onFix(moving(90f, speed = 1.5f))
        assertNull(t.heading)
    }

    @Test
    fun turningTakesTheShortWayAcrossNorth() {
        val t = tracker(useCompass = false)
        t.onFix(moving(350f))
        val seen = mutableListOf<Float>()
        repeat(6) { t.onFix(moving(10f)).let { h -> seen += h!! } }
        // Every intermediate value stays within 350..360 or 0..10, never swinging through 180.
        assertTrue(seen.all { it >= 349f || it <= 11f }, "swung the long way: $seen")
        assertHeading(10f, t.heading, tolerance = 2f)
    }

    @Test
    fun easesTowardsANewCourseInsteadOfSnapping() {
        val t = tracker(useCompass = false)
        t.onFix(moving(0f))
        val after = t.onFix(moving(90f))!!
        assertTrue(after > 20f && after < 90f, "should be partway to 90, was $after")
    }

    @Test
    fun aPoorCourseIsRejected() {
        val t = tracker(useCompass = false)
        t.onFix(moving(90f, accuracy = 10f))
        t.onFix(moving(270f, accuracy = 80f))
        assertHeading(90f, t.heading)
    }

    @Test
    fun withoutACourse_headingIsDerivedFromMovementAndSpeedFromTimeDelta() {
        val t = tracker(useCompass = false)
        // Network-style fixes: no bearing, no speed. ~11 m north in 1 s => ~11 m/s, heading ~0.
        now = 0L
        t.onFix(GpsFix(47.3700, 8.5400, null))
        now = 1000L
        t.onFix(GpsFix(47.3701, 8.5400, null))
        now = 2000L
        t.onFix(GpsFix(47.3702, 8.5400, null))
        assertHeading(0f, t.heading, tolerance = 2f)
    }

    @Test
    fun withoutACourse_jitterWhileStationaryDoesNotProduceAHeading() {
        val t = tracker(useCompass = false)
        now = 0L
        t.onFix(GpsFix(47.37000, 8.54000, null))
        now = 1000L
        t.onFix(GpsFix(47.37001, 8.54001, null)) // ~1.4 m in 1 s: below 2 m/s
        now = 2000L
        t.onFix(GpsFix(47.36999, 8.53999, null))
        assertNull(t.heading)
    }

    @Test
    fun shortestDelta_wrapsCorrectly() {
        assertEquals(20f, HeadingTracker.shortestDelta(350f, 10f))
        assertEquals(-20f, HeadingTracker.shortestDelta(10f, 350f))
        assertEquals(180f, HeadingTracker.shortestDelta(0f, 180f))
    }

    @Test
    fun normalize_keepsDegreesInRange() {
        assertEquals(10f, HeadingTracker.normalize(370f))
        assertEquals(350f, HeadingTracker.normalize(-10f))
    }
}
