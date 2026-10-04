package dev.lammertsma.parkingblues.car.location

import kotlin.math.max

/**
 * Turns the raw, noisy stream of GPS fixes into a position that glides.
 *
 *  - Fixes with a poor accuracy radius are rejected once there is a good
 *    position to compare against.
 *  - While essentially stationary, movement smaller than the fix's own
 *    uncertainty is ignored, so standing still doesn't make the dot wander.
 *  - Otherwise the position moves toward each new fix by at most a maximum
 *    speed times the elapsed time, so a spurious 50 m hop becomes a short
 *    slide instead of a jump (and is largely undone by the next real fix).
 *    The cap scales with the reported speed so genuine fast driving is never
 *    held back.
 *
 * The output is what both the map and the backend should see, so a glitch
 * can't make the server think the driver passed (or missed) a parking spot.
 */
class PositionFilter(private val clock: () -> Long = System::currentTimeMillis) {

    var position: Pair<Double, Double>? = null
        private set

    private var lastAt = 0L
    private var seededFromPoorFix = false

    /** Feed every fix; returns the filtered position to use. */
    fun onFix(fix: GpsFix): Pair<Double, Double> {
        val now = clock()
        val elapsedS = ((now - lastAt) / 1000f).coerceIn(MIN_ELAPSED_S, MAX_ELAPSED_S)
        lastAt = now

        val current = position
        val raw = fix.lat to fix.lon
        val accuracy = fix.accuracyM

        if (current == null) {
            position = raw
            seededFromPoorFix = accuracy == null || accuracy > MAX_ACCURACY_M
            return raw
        }

        val poor = accuracy != null && accuracy > MAX_ACCURACY_M
        if (poor) return current

        // The first fix of the session may have been a rough one; the first
        // good fix replaces it outright rather than sliding there.
        if (seededFromPoorFix && accuracy != null) {
            seededFromPoorFix = false
            position = raw
            return raw
        }

        val distance = calculateDistanceMeters(current.first, current.second, fix.lat, fix.lon)
        val speed = fix.speedMps

        if (speed != null && speed < STATIONARY_MPS &&
            distance < max(accuracy ?: DEFAULT_NOISE_M, MIN_HOLD_M)
        ) {
            return current
        }

        val maxStep = max(MIN_SLEW_MPS, (speed ?: 0f) * SLEW_SPEED_FACTOR + SLEW_HEADROOM_MPS) * elapsedS
        val next = if (distance <= maxStep) {
            raw
        } else {
            val f = (maxStep / distance)
            (current.first + (fix.lat - current.first) * f) to (current.second + (fix.lon - current.second) * f)
        }
        position = next
        return next
    }

    companion object {
        const val MAX_ACCURACY_M = 100f
        const val STATIONARY_MPS = 0.8f
        const val DEFAULT_NOISE_M = 15f
        const val MIN_HOLD_M = 8f
        const val MIN_SLEW_MPS = 12f
        const val SLEW_SPEED_FACTOR = 1.5f
        const val SLEW_HEADROOM_MPS = 6f
        private const val MIN_ELAPSED_S = 0.2f
        private const val MAX_ELAPSED_S = 5f
    }
}
