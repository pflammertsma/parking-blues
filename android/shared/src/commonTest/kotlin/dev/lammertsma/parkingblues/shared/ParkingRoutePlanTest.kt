package dev.lammertsma.parkingblues.shared

import dev.lammertsma.parkingblues.shared.api.ParkingApiClient
import dev.lammertsma.parkingblues.shared.model.LatLon
import dev.lammertsma.parkingblues.shared.model.ZoneFilter
import dev.lammertsma.parkingblues.shared.model.ZoneType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParkingRoutePlanTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val from = LatLon(47.3640, 8.5300)
    private val to = LatLon(47.3720, 8.5445)

    private val body = """
        {"experimental": true,
         "start": {"lat": 47.364, "lon": 8.53},
         "destination": {"lat": 47.372, "lon": 8.5445},
         "stops": [{"rank": 1, "zone_type": "white", "lat": 47.371, "lon": 8.543, "capacity": 29,
                    "spot_count": 20, "distance_m": 120.0, "chance": 0.95, "eta_s": 347,
                    "max_duration_minutes": 120, "estimated_fee_chf_per_hour": 2.0}],
         "route": {"distance_m": 2765, "duration_s": 390, "path": [[47.364, 8.53], [47.372, 8.5445]]},
         "chance_of_parking": 0.95, "expected_minutes": 8.6}
    """.trimIndent()

    private fun repository(status: HttpStatusCode, reply: String, seen: MutableList<String> = mutableListOf()) =
        ParkingSessionRepository(
            ParkingApiClient(
                "https://test.invalid",
                MockEngine { request ->
                    seen += request.url.encodedPath + " " + request.body.toByteArray().decodeToString()
                    respond(reply, status, jsonHeaders)
                },
            )
        )

    @Test
    fun theAnswerIsDecodedIncludingFieldsThisClientDoesNotUse() = runTest {
        val route = repository(HttpStatusCode.OK, body).planRoute(from, to, ZoneFilter.BOTH).getOrThrow()

        assertEquals(1, route.stops.size)
        assertEquals(ZoneType.WHITE, route.stops.single().zoneType)
        assertEquals(29, route.stops.single().capacity)
        assertEquals(347, route.stops.single().etaSeconds)
        assertEquals(listOf(47.364, 8.53), route.route.path.first())
        assertEquals(0.95, route.chanceOfParking)
        assertEquals(8.6, route.expectedMinutes)
    }

    @Test
    fun theRequestCarriesTheEndpointsAndTheZone() = runTest {
        val seen = mutableListOf<String>()
        repository(HttpStatusCode.OK, body, seen).planRoute(from, to, ZoneFilter.BLUE).getOrThrow()

        val (path, sent) = seen.single().split(" ", limit = 2)
        assertEquals("/api/route", path)
        assertTrue("\"zone\":\"blue\"" in sent, sent)
        assertTrue("\"from\":{\"lat\":47.364,\"lon\":8.53}" in sent, sent)
        assertTrue("\"to\":{\"lat\":47.372,\"lon\":8.5445}" in sent, sent)
    }

    @Test
    fun failuresBecomeMessagesFitForTheUser() = runTest {
        // Without a `code` the status alone decides: 404 and 405 mean the server has no route endpoint.
        val cases = mapOf(
            HttpStatusCode.NotFound to "Route planning isn't available on the server yet.",
            HttpStatusCode.MethodNotAllowed to "Route planning isn't available on the server yet.",
            HttpStatusCode.BadRequest to "That route can't be planned from here.",
            HttpStatusCode.TooManyRequests to "Too many route requests. Try again in a minute.",
            HttpStatusCode.InternalServerError to "Couldn't plan the route.",
        )
        for ((status, message) in cases) {
            val result = repository(status, """{"error":"x"}""").planRoute(from, to, ZoneFilter.BOTH)
            assertEquals(message, result.exceptionOrNull()?.message, status.toString())
        }
    }

    @Test
    fun theServersReasonCodeDecidesTheMessageAndWhetherToRetry() = runTest {
        // code -> (HTTP status the server uses, message, retry is worth offering)
        val cases = mapOf(
            "no_road_near_start" to Triple(422, "You don't seem to be near a road.", false),
            "no_road_near_destination" to Triple(422, "There is no road near that destination.", false),
            "not_connected" to Triple(422, "There is no drivable route from here to there.", false),
            "no_parking_nearby" to Triple(404, "No parking known near that destination.", false),
            "routing_busy" to Triple(503, "Routing is busy right now. Try again in a moment.", true),
            "routing_unavailable" to Triple(502, "Routing is unavailable right now. Try again later.", true),
        )
        for ((code, expected) in cases) {
            val (status, message, retryable) = expected
            val failure = repository(HttpStatusCode.fromValue(status), "{\"error\":\"x\",\"code\":\"$code\"}")
                .planRoute(from, to, ZoneFilter.BOTH).exceptionOrNull()
            assertEquals(message, failure?.message, code)
            assertEquals(retryable, (failure as RoutePlanException).retryable, code)
        }
    }

    @Test
    fun aMissingEndpointIsNotWorthRetryingButAFailingServerIs() = runTest {
        val missing = repository(HttpStatusCode.MethodNotAllowed, "x").planRoute(from, to, ZoneFilter.BOTH).exceptionOrNull()
        val failing = repository(HttpStatusCode.InternalServerError, "x").planRoute(from, to, ZoneFilter.BOTH).exceptionOrNull()

        assertEquals(false, (missing as RoutePlanException).retryable)
        assertEquals(true, (failing as RoutePlanException).retryable)
    }

    @Test
    fun whereTheRoadStartsIsDecodedWhenTheServerSendsIt() = runTest {
        val withGap = body.replace(
            "\"chance_of_parking\"",
            "\"start_snap\": {\"lat\": 47.3645, \"lon\": 8.5304, \"distance_m\": 120, \"name\": \"Seestrasse\"}, \"chance_of_parking\"",
        )
        val route = repository(HttpStatusCode.OK, withGap).planRoute(from, to, ZoneFilter.BOTH).getOrThrow()

        assertEquals(120, route.startSnap?.distanceM)
        assertEquals("Seestrasse", route.startSnap?.name)
    }

    @Test
    fun noStartSnapMeansTheRoadStartsWhereTheDriverIs() = runTest {
        val route = repository(HttpStatusCode.OK, body).planRoute(from, to, ZoneFilter.BOTH).getOrThrow()
        assertEquals(null, route.startSnap)
    }

    @Test
    fun planningARouteLeavesTheSessionAndTheErrorBannerAlone() = runTest {
        val repository = repository(HttpStatusCode.BadGateway, """{"error":"x"}""")
        repository.planRoute(from, to, ZoneFilter.BOTH)

        assertEquals(null, repository.error.value)
        assertEquals(null, repository.session.value)
    }
}
