package dev.lammertsma.parkingblues.shared

import dev.lammertsma.parkingblues.shared.api.ParkingApiClient
import dev.lammertsma.parkingblues.shared.model.LatLon
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import dev.lammertsma.parkingblues.shared.model.SessionState
import dev.lammertsma.parkingblues.shared.model.ZoneFilter
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
import kotlin.test.assertNull

/**
 * The map follows the vehicle from the live fix, so it must keep moving when the server is slow,
 * failing or rate limiting: the position is published before (and regardless of) the round trip.
 */
class ParkingSessionRepositoryLivePositionTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun snapshot(you: LatLon) = SessionSnapshot(
        sessionId = "s1",
        state = SessionState.SEARCHING,
        radiusM = 300.0,
        origin = you,
        you = you,
        current = null,
        upcoming = emptyList(),
        rejected = emptyList(),
        rejectedCount = 0,
    )

    private fun repository(updateStatus: HttpStatusCode, start: LatLon) = ParkingSessionRepository(
        ParkingApiClient(
            "https://test.invalid",
            MockEngine { request ->
                if (request.url.encodedPath == "/api/session") {
                    respond(json.encodeToString(snapshot(start)), HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond("""{"error":"nope"}""", updateStatus, jsonHeaders)
                }
            },
        )
    )

    @Test
    fun startingASearchPublishesItsStartAsTheLivePosition() = runTest {
        val start = LatLon(47.3769, 8.5417)
        val repository = repository(HttpStatusCode.OK, start)
        assertNull(repository.livePosition.value)

        repository.startSearch(start.lat, start.lon, ZoneFilter.BOTH)

        assertEquals(start, repository.livePosition.value)
    }

    @Test
    fun theLivePositionAdvancesEvenWhenTheServerRejectsTheUpdate() = runTest {
        val start = LatLon(47.3769, 8.5417)
        val moved = LatLon(47.3790, 8.5440)
        val repository = repository(HttpStatusCode.InternalServerError, start)
        repository.startSearch(start.lat, start.lon, ZoneFilter.BOTH)

        repository.updatePosition(moved.lat, moved.lon)

        assertEquals(moved, repository.livePosition.value)
        // The snapshot is still the server's older answer; only the live position moved.
        assertEquals(start, repository.session.value?.you)
    }

    @Test
    fun theLivePositionAdvancesWhileUpdatesArePausedAfterARateLimit() = runTest {
        val start = LatLon(47.3769, 8.5417)
        val repository = repository(HttpStatusCode.TooManyRequests, start)
        repository.startSearch(start.lat, start.lon, ZoneFilter.BOTH)

        repository.updatePosition(47.3780, 8.5420) // 429: updates now pause
        val later = LatLon(47.3800, 8.5450)
        repository.updatePosition(later.lat, later.lon) // never sent, but still live

        assertEquals(later, repository.livePosition.value)
    }

    @Test
    fun resetClearsTheLivePosition() = runTest {
        val start = LatLon(47.3769, 8.5417)
        val repository = repository(HttpStatusCode.OK, start)
        repository.startSearch(start.lat, start.lon, ZoneFilter.BOTH)

        repository.reset()

        assertNull(repository.livePosition.value)
    }
}
