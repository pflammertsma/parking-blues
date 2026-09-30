package com.parkingblues.shared

import com.parkingblues.shared.api.ParkingApiClient
import com.parkingblues.shared.model.LatLon
import com.parkingblues.shared.model.ParkingSegment
import com.parkingblues.shared.model.SessionSnapshot
import com.parkingblues.shared.model.SessionState
import com.parkingblues.shared.model.ZoneFilter
import com.parkingblues.shared.model.ZoneType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simulates a driver "driving past" a parking spot without stopping, and
 * confirms ParkingSessionRepository correctly reflects the server's
 * resulting current/rejected transition in its observable StateFlow --
 * i.e. that the client correctly renders a spot as visited once the
 * server says so, without needing a phone, emulator, or DHU at all.
 *
 * This mocks the SERVER's responses (a scripted fixture per step below),
 * so it's testing OUR client's request/response/state handling, not
 * re-verifying the backend's own rejection algorithm -- that's the
 * server's job, already covered by tests/test_session.py's own simulated
 * drive-by sequences (see README §5.2 for why the algorithm intentionally
 * doesn't live here too).
 */
class ParkingSessionRepositoryDriveByTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun segment(id: String, distanceFromYouM: Double) = ParkingSegment(
        id = id,
        lat = 47.3793,
        lon = 8.5314,
        zoneType = ZoneType.WHITE,
        addressLabel = "Street parking spot #$id",
        estimatedCapacity = 1,
        maxDurationMinutes = 120,
        distanceM = distanceFromYouM,
        distanceFromYouM = distanceFromYouM,
        estimatedFeeChfPerHour = 2.0,
    )

    private fun snapshot(
        current: ParkingSegment?,
        upcoming: List<ParkingSegment>,
        rejected: List<ParkingSegment>,
        you: LatLon,
    ) = SessionSnapshot(
        sessionId = "test-session",
        state = SessionState.SEARCHING,
        radiusM = 300.0,
        origin = LatLon(47.379198, 8.531307),
        you = you,
        current = current,
        upcoming = upcoming,
        rejected = rejected,
        rejectedCount = rejected.size,
    )

    @Test
    fun drivingPastASpotWithoutStopping_marksItRejected() = runTest {
        val spotPassed = segment("zh-near", distanceFromYouM = 55.0)
        val spotAhead = segment("zh-far", distanceFromYouM = 180.0)

        var positionCallCount = 0
        val engine = MockEngine { request ->
            val body: SessionSnapshot = when {
                request.url.encodedPath == "/api/session" -> snapshot(
                    current = spotPassed,
                    upcoming = listOf(spotAhead),
                    rejected = emptyList(),
                    you = LatLon(47.379198, 8.531307),
                )
                request.url.encodedPath.endsWith("/position") -> {
                    positionCallCount++
                    if (positionCallCount == 1) {
                        // Still approaching -- nothing's changed yet.
                        snapshot(
                            current = spotPassed,
                            upcoming = listOf(spotAhead),
                            rejected = emptyList(),
                            you = LatLon(47.379250, 8.531350),
                        )
                    } else {
                        // Driven past spotPassed without stopping -- the
                        // server promotes the next candidate and marks the
                        // passed one rejected.
                        snapshot(
                            current = spotAhead,
                            upcoming = emptyList(),
                            rejected = listOf(spotPassed),
                            you = LatLon(47.379450, 8.531600),
                        )
                    }
                }
                else -> error("Unexpected request in drive-by test: ${request.url}")
            }
            respond(
                content = json.encodeToString(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val repository = ParkingSessionRepository(ParkingApiClient("https://test.invalid", engine))

        repository.startSearch(lat = 47.379198, lon = 8.531307, zone = ZoneFilter.BOTH)
        assertEquals("zh-near", repository.session.value?.current?.id)
        assertTrue(repository.session.value?.rejected.orEmpty().isEmpty())

        // Approaching -- still not rejected yet.
        repository.updatePosition(lat = 47.379250, lon = 8.531350)
        assertEquals("zh-near", repository.session.value?.current?.id)
        assertTrue(repository.session.value?.rejected.orEmpty().isEmpty())

        // Driven past it without stopping.
        repository.updatePosition(lat = 47.379450, lon = 8.531600)
        val finalSnapshot = repository.session.value
        assertEquals("zh-far", finalSnapshot?.current?.id)
        assertEquals(listOf("zh-near"), finalSnapshot?.rejected?.map { it.id })
    }
}
