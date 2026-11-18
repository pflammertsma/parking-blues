package dev.lammertsma.parkingblues.car.car

import dev.lammertsma.parkingblues.car.car.CarConnectionTracker.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CarConnectionTrackerTest {
    private var now = 1_000_000L
    private val tracker = CarConnectionTracker { now }

    @Test
    fun connectingClearsTheOldSpot() {
        assertEquals(Event.Connected, tracker.onConnection(projected = true))
    }

    @Test
    fun beingConnectedAlreadyWhenTheAppStartsCountsAsConnecting() {
        // First sight of the connection is "connected": the driver is in the car.
        assertEquals(Event.Connected, tracker.onConnection(true))
        assertEquals(Event.None, tracker.onConnection(true))
    }

    @Test
    fun theFirstSightOfNotConnectedDoesNothing() {
        // The app was opened on the phone later, long after the drive.
        assertEquals(Event.None, tracker.onConnection(false))
    }

    @Test
    fun disconnectingAssumesTheLastPositionSeenDuringTheDrive() {
        tracker.onConnection(true)
        tracker.onFix(47.37, 8.54)
        now += 4_000
        tracker.onFix(47.3711, 8.5412)
        now += 2_000

        val event = tracker.onConnection(false)

        assertIs<Event.Disconnected>(event)
        assertEquals(47.3711, event.lastFix?.lat)
        assertEquals(8.5412, event.lastFix?.lon)
    }

    @Test
    fun aPositionFromBeforeTheConnectionIsNotWhereTheCarStopped() {
        tracker.onFix(47.0, 8.0) // earlier in the day, phone only
        tracker.onConnection(true)
        now += 60_000

        val event = tracker.onConnection(false)

        assertIs<Event.Disconnected>(event)
        assertNull(event.lastFix)
    }

    @Test
    fun aStalePositionIsNotUsed() {
        tracker.onConnection(true)
        tracker.onFix(47.37, 8.54)
        now += CarConnectionTracker.MAX_FIX_AGE_MS + 1

        assertNull((tracker.onConnection(false) as Event.Disconnected).lastFix)
    }

    @Test
    fun aPositionJustInsideTheLimitIsUsed() {
        tracker.onConnection(true)
        tracker.onFix(47.37, 8.54)
        now += CarConnectionTracker.MAX_FIX_AGE_MS

        assertEquals(47.37, (tracker.onConnection(false) as Event.Disconnected).lastFix?.lat)
    }

    @Test
    fun reconnectingAfterADisconnectIsConnectingAgainAndForgetsTheOldFix() {
        tracker.onConnection(true)
        tracker.onFix(47.37, 8.54)
        tracker.onConnection(false)

        assertEquals(Event.Connected, tracker.onConnection(true))
        // No fix yet on this connection, so a quick drop has nothing to assume.
        assertNull((tracker.onConnection(false) as Event.Disconnected).lastFix)
    }

    @Test
    fun repeatedDisconnectsAreOnlyReportedOnce() {
        tracker.onConnection(true)
        tracker.onFix(47.37, 8.54)
        assertIs<Event.Disconnected>(tracker.onConnection(false))
        assertEquals(Event.None, tracker.onConnection(false))
    }
}
