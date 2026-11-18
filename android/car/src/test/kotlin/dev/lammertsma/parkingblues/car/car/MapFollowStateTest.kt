package dev.lammertsma.parkingblues.car.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapFollowStateTest {
    private var now = 1_000L
    private val state = MapFollowState(clock = { now }, idleMs = 30_000L)

    @Test
    fun followsByDefault() {
        assertTrue(state.isFollowing)
        assertFalse(state.resumeIfIdle())
    }

    @Test
    fun aPanStopsFollowing_andOnlyTheFirstOneReportsTheChange() {
        assertTrue(state.onUserInteraction())
        assertFalse(state.isFollowing)
        // Dozens of scroll events per drag must not each look like a transition.
        assertFalse(state.onUserInteraction())
    }

    @Test
    fun followingResumesOnlyAfterTheFullIdleTime() {
        state.onUserInteraction()
        now += 29_999
        assertFalse(state.resumeIfIdle())
        assertFalse(state.isFollowing)
        now += 1
        assertTrue(state.resumeIfIdle())
        assertTrue(state.isFollowing)
    }

    @Test
    fun everyGestureRestartsTheIdleClock() {
        state.onUserInteraction()
        now += 20_000
        state.onUserInteraction() // still exploring
        now += 20_000 // 40 s since the first gesture, 20 s since the last
        assertFalse(state.resumeIfIdle())
        now += 10_000
        assertTrue(state.resumeIfIdle())
    }

    @Test
    fun recenterResumesAtOnce() {
        state.onUserInteraction()
        state.resume()
        assertTrue(state.isFollowing)
        // A later pan is a fresh transition again.
        assertTrue(state.onUserInteraction())
    }

    @Test
    fun theIdleTimeIsThirtySeconds() {
        assertEquals(30_000L, MapFollowState.IDLE_RESUME_MS)
    }
}
