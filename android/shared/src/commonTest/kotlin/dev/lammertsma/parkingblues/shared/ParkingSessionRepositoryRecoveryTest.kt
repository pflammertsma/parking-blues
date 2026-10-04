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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backend's in-memory sessions vanish on a Cloud Run cold start, so a
 * 404 on an existing session must silently restart the search (same zone,
 * same position) rather than surface an error -- while any other failure
 * must still surface as an error and leave the current session alone.
 */
class ParkingSessionRepositoryRecoveryTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun snapshot(id: String, you: LatLon) = SessionSnapshot(
        sessionId = id,
        state = SessionState.SEARCHING,
        radiusM = 300.0,
        origin = you,
        you = you,
        current = null,
        upcoming = emptyList(),
        rejected = emptyList(),
        rejectedCount = 0,
    )

    private class Recorded(val path: String, val body: String?)

    @Test
    fun positionUpdateOnDroppedSession_restartsWithSameZoneAndPosition() = runTest {
        val start = LatLon(47.3769, 8.5417)
        val moved = LatLon(47.3780, 8.5430)
        val requests = mutableListOf<Recorded>()
        var sessionsCreated = 0
        val engine = MockEngine { request ->
            requests += Recorded(request.url.encodedPath, (request.body as? TextContent)?.text)
            when {
                request.url.encodedPath == "/api/session" -> {
                    sessionsCreated++
                    respond(
                        json.encodeToString(snapshot("session-$sessionsCreated", if (sessionsCreated == 1) start else moved)),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                else -> respond("""{"error":"session not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val repository = ParkingSessionRepository(ParkingApiClient("https://test.invalid", engine))

        repository.startSearch(start.lat, start.lon, ZoneFilter.BLUE)
        assertEquals("session-1", repository.session.value?.sessionId)

        repository.updatePosition(moved.lat, moved.lon)

        assertEquals("session-2", repository.session.value?.sessionId)
        assertNull(repository.error.value)
        val restart = requests.last()
        assertEquals("/api/session", restart.path)
        val body = assertNotNull(restart.body)
        assertTrue(""""zone":"${ZoneFilter.BLUE.wireValue}"""" in body, "restart should reuse the zone: $body")
        assertTrue("${moved.lat}" in body && "${moved.lon}" in body, "restart should use the new position: $body")
    }

    @Test
    fun rejectOnDroppedSession_restartsAtTheSessionsLastKnownPosition() = runTest {
        val you = LatLon(47.3769, 8.5417)
        var sessionsCreated = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/api/session") {
                sessionsCreated++
                respond(json.encodeToString(snapshot("session-$sessionsCreated", you)), HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"error":"session not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val repository = ParkingSessionRepository(ParkingApiClient("https://test.invalid", engine))

        repository.startSearch(you.lat, you.lon, ZoneFilter.WHITE)
        repository.rejectCurrent()

        assertEquals("session-2", repository.session.value?.sessionId)
        assertNull(repository.error.value)
    }

    @Test
    fun nonNotFoundFailure_surfacesAsErrorAndKeepsTheCurrentSession() = runTest {
        val you = LatLon(47.3769, 8.5417)
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/api/session") {
                respond(json.encodeToString(snapshot("session-1", you)), HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, jsonHeaders)
            }
        }
        val repository = ParkingSessionRepository(ParkingApiClient("https://test.invalid", engine))

        repository.startSearch(you.lat, you.lon, ZoneFilter.BOTH)
        repository.updatePosition(47.38, 8.55)

        assertNotNull(repository.error.value)
        assertEquals("session-1", repository.session.value?.sessionId)
    }

    @Test
    fun successfulUpdateAfterAnError_clearsTheError() = runTest {
        val you = LatLon(47.3769, 8.5417)
        var positionCalls = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/session" ->
                    respond(json.encodeToString(snapshot("session-1", you)), HttpStatusCode.OK, jsonHeaders)
                ++positionCalls == 1 -> respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, jsonHeaders)
                else -> respond(json.encodeToString(snapshot("session-1", you)), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val repository = ParkingSessionRepository(ParkingApiClient("https://test.invalid", engine))

        repository.startSearch(you.lat, you.lon, ZoneFilter.BOTH)
        repository.updatePosition(47.38, 8.55)
        assertNotNull(repository.error.value)

        repository.updatePosition(47.38, 8.55)
        assertNull(repository.error.value)
    }
}
