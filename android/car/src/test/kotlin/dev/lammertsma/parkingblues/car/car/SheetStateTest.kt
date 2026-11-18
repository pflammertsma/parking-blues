package dev.lammertsma.parkingblues.car.car

import dev.lammertsma.parkingblues.shared.model.LatLon
import dev.lammertsma.parkingblues.shared.model.ParkingSegment
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import dev.lammertsma.parkingblues.shared.model.SessionState
import dev.lammertsma.parkingblues.shared.model.ZoneType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SheetStateTest {

    private fun snapshot(vararg candidates: ParkingSegment) = SessionSnapshot(
        sessionId = "s", state = SessionState.SEARCHING, radiusM = 300.0,
        origin = LatLon(47.3769, 8.5417), you = LatLon(47.3769, 8.5417),
        current = candidates.firstOrNull(), upcoming = candidates.drop(1),
    )

    private fun segment(id: String) = ParkingSegment(
        id = id, lat = 47.3770, lon = 8.5417, zoneType = ZoneType.BLUE, addressLabel = id,
        estimatedCapacity = 2, distanceM = 10.0, distanceFromYouM = 10.0,
    )

    @Test
    fun beforeASessionItWaitsForALocationOrSearches() {
        assertEquals(SheetState.Waiting, sheetState(null, hasLocation = false))
        assertEquals(SheetState.Searching, sheetState(null, hasLocation = true))
    }

    @Test
    fun withLocationDeniedItOffersZurichInsteadOfWaitingForEver() {
        assertEquals(SheetState.NoLocation, sheetState(null, hasLocation = false, locationDenied = true))
        // A fix that did arrive wins (the permission was granted after all).
        assertEquals(SheetState.Searching, sheetState(null, hasLocation = true, locationDenied = true))
    }

    @Test
    fun aSearchThatFoundNothingIsTheOutsideZurichCase() {
        // The backend knows only the City of Zurich; anywhere else it answers with no candidates.
        assertEquals(SheetState.Empty, sheetState(snapshot(), hasLocation = true))
    }

    @Test
    fun aSearchWithCandidatesListsTheirAreas() {
        val state = sheetState(snapshot(segment("a")), hasLocation = true)
        assertIs<SheetState.Found>(state)
        assertEquals(1, state.areas.size)
    }

    @Test
    fun spotsHundredsOfKilometresAwayAreNotNearby() {
        // A visitor in Berlin whose session still holds Zurich's spots.
        val faraway = segment("z").copy(distanceFromYouM = 668_983.0)
        assertEquals(SheetState.Empty, sheetState(snapshot(faraway), hasLocation = true))
    }

    @Test
    fun anAreaJustInsideTheLimitStillCounts() {
        val edge = segment("e").copy(distanceFromYouM = NEARBY_LIMIT_M.toDouble())
        assertIs<SheetState.Found>(sheetState(snapshot(edge), hasLocation = true))
    }
}
