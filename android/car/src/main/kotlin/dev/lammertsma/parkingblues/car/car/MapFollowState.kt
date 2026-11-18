package dev.lammertsma.parkingblues.car.car

import android.os.SystemClock

/**
 * Whether the car map is following the vehicle or the driver is exploring it freely.
 *
 *  - It follows by default.
 *  - Any pan or zoom by the driver stops following (the Recenter button appears).
 *  - Following resumes when the driver presses Recenter, or once the map has been left alone for
 *    [idleMs] since the last gesture, so a stray touch can never leave the map stuck.
 *
 * Pure state with an injectable clock; the screen owns the timer and the camera animation.
 */
class MapFollowState(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val idleMs: Long = IDLE_RESUME_MS,
) {
    var isFollowing = true
        private set

    private var lastInteractionAt = 0L

    /** A pan or zoom. Restarts the idle clock; returns true if this is what stopped following. */
    fun onUserInteraction(): Boolean {
        lastInteractionAt = clock()
        val wasFollowing = isFollowing
        isFollowing = false
        return wasFollowing
    }

    /** Recenter pressed. */
    fun resume() {
        isFollowing = true
    }

    /** Resumes following if the map has been idle for [idleMs]; returns true if it did. */
    fun resumeIfIdle(): Boolean {
        if (isFollowing || clock() - lastInteractionAt < idleMs) return false
        isFollowing = true
        return true
    }

    companion object {
        const val IDLE_RESUME_MS = 30_000L
    }
}
