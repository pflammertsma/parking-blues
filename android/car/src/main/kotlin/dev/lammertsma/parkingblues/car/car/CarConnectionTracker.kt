package dev.lammertsma.parkingblues.car.car

/**
 * Turns Android Auto's connected / not connected changes into what the app should do about the
 * parked spot, as plain state so it can be tested without a car:
 *
 *  - **Connected** (the driver is back in the car): the saved spot is out of date, so clear it.
 *    Also when the app first starts and finds Android Auto already connected.
 *  - **Disconnected** after being connected: the driver stopped. If a position was seen *during
 *    that connection* and recently, that is where they parked, unless they said otherwise by
 *    pressing "Parked here" (the caller checks for an existing spot).
 *  - Anything else (first sight of "not connected", repeats): nothing.
 *
 * Positions are only remembered while connected, so a fix from earlier in the day on the phone
 * cannot be mistaken for where the car was left.
 */
class CarConnectionTracker(private val clock: () -> Long = System::currentTimeMillis) {

    data class Fix(val lat: Double, val lon: Double, val atMillis: Long)

    sealed interface Event {
        data object None : Event
        data object Connected : Event

        /** [lastFix] is where the driver was when the car went away; null if there is nothing to go on. */
        data class Disconnected(val lastFix: Fix?) : Event
    }

    private var connected: Boolean? = null
    private var lastFix: Fix? = null

    /** A position fix from the device, wherever it is feeding from. */
    fun onFix(lat: Double, lon: Double) {
        if (connected == true) lastFix = Fix(lat, lon, clock())
    }

    fun onConnection(projected: Boolean): Event {
        val was = connected
        connected = projected
        return when {
            projected && was != true -> {
                lastFix = null
                Event.Connected
            }
            !projected && was == true -> {
                val fix = lastFix?.takeIf { clock() - it.atMillis <= MAX_FIX_AGE_MS }
                lastFix = null
                Event.Disconnected(fix)
            }
            else -> Event.None
        }
    }

    companion object {
        /** A fix older than this before the disconnect says nothing about where the car stopped. */
        const val MAX_FIX_AGE_MS = 5 * 60 * 1000L
    }
}
