package dev.lammertsma.parkingblues.car.location

import kotlin.math.abs

/**
 * Decides which way the "you" arrow points, the way navigation apps do:
 *
 *  - **Moving** (GPS speed above [ENTER_MOVING_MPS]): direction of travel,
 *    from the GPS course. GPS course is meaningless at a standstill, so it
 *    is ignored once the speed drops, rather than letting noise spin the arrow.
 *  - **Stationary, phone in hand** ([useCompass]): wherever the phone is
 *    pointing, from the compass.
 *  - **Stationary, in the car** (not [useCompass]): hold the last direction of
 *    travel. A phone's compass says nothing about the car's orientation when
 *    the phone sits in a cradle or cupholder.
 *
 * Switching between moving and stationary uses hysteresis (enter at 2 m/s,
 * leave below 1 m/s) so the arrow doesn't flap around a traffic-light stop,
 * and every update is eased toward its target along the short way around the
 * circle instead of snapping.
 */
class HeadingTracker(
    private val useCompass: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Current smoothed heading in degrees clockwise from true north, or null if unknown. */
    var heading: Float? = null
        private set

    private var moving = false
    private var lastFixLat = Double.NaN
    private var lastFixLon = Double.NaN
    private var lastFixAt = 0L
    private var courseAnchorLat = Double.NaN
    private var courseAnchorLon = Double.NaN

    /** Feed every location fix. Returns the (possibly unchanged) heading. */
    fun onFix(fix: GpsFix): Float? {
        val now = clock()
        val speed = fix.speedMps ?: estimatedSpeed(fix, now)
        lastFixLat = fix.lat
        lastFixLon = fix.lon
        lastFixAt = now

        if (speed != null) {
            moving = if (moving) speed >= EXIT_MOVING_MPS else speed >= ENTER_MOVING_MPS
        }

        if (!moving) {
            courseAnchorLat = fix.lat
            courseAnchorLon = fix.lon
            return heading
        }

        val course = reliableCourse(fix) ?: derivedCourse(fix)
        if (course != null) steer(course, COURSE_SMOOTHING, COURSE_DEADBAND_DEG)
        return heading
    }

    /** Feed compass readings (degrees clockwise from true north). Ignored while moving or in the car. */
    fun onCompass(azimuthDegrees: Float): Float? {
        if (!useCompass || moving) return heading
        steer(normalize(azimuthDegrees), COMPASS_SMOOTHING, COMPASS_DEADBAND_DEG)
        return heading
    }

    private fun estimatedSpeed(fix: GpsFix, now: Long): Float? {
        if (lastFixLat.isNaN() || now <= lastFixAt) return null
        val meters = calculateDistanceMeters(lastFixLat, lastFixLon, fix.lat, fix.lon)
        return (meters / ((now - lastFixAt) / 1000.0)).toFloat()
    }

    private fun reliableCourse(fix: GpsFix): Float? {
        val course = fix.bearingDegrees ?: return null
        val error = fix.bearingAccuracyDegrees
        return if (error == null || error <= MAX_COURSE_ERROR_DEG) normalize(course) else null
    }

    /** For fixes without a usable course (e.g. network location): bearing between points a few meters apart. */
    private fun derivedCourse(fix: GpsFix): Float? {
        if (courseAnchorLat.isNaN()) {
            courseAnchorLat = fix.lat
            courseAnchorLon = fix.lon
            return null
        }
        val meters = calculateDistanceMeters(courseAnchorLat, courseAnchorLon, fix.lat, fix.lon)
        if (meters < MIN_DERIVED_COURSE_M) return null
        val course = calculateBearingDegrees(courseAnchorLat, courseAnchorLon, fix.lat, fix.lon)
        courseAnchorLat = fix.lat
        courseAnchorLon = fix.lon
        return course
    }

    private fun steer(target: Float, smoothing: Float, deadband: Float) {
        val current = heading
        if (current == null) {
            heading = target
            return
        }
        val delta = shortestDelta(current, target)
        if (abs(delta) < deadband) return
        heading = normalize(current + delta * smoothing)
    }

    companion object {
        const val ENTER_MOVING_MPS = 2.0f
        const val EXIT_MOVING_MPS = 1.0f
        private const val MIN_DERIVED_COURSE_M = 5.0
        private const val MAX_COURSE_ERROR_DEG = 45f
        private const val COURSE_SMOOTHING = 0.6f
        private const val COURSE_DEADBAND_DEG = 1f
        private const val COMPASS_SMOOTHING = 0.2f
        private const val COMPASS_DEADBAND_DEG = 2f

        /** Signed shortest rotation from [from] to [to], in (-180, 180]. */
        fun shortestDelta(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }

        fun normalize(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
    }
}
