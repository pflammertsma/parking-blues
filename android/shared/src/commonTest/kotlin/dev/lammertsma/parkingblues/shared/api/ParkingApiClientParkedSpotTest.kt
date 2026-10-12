package dev.lammertsma.parkingblues.shared.api

import dev.lammertsma.parkingblues.shared.model.ZoneType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * GET/PUT/DELETE /api/me/parked, exactly as backend/app.py serves them (see
 * tests/test_auth.py's parked-spot tests for the server side of this same
 * contract) -- confirms the client's request/response shapes actually match
 * without needing a real backend or device.
 */
class ParkingApiClientParkedSpotTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun getParkedSpot_returnsNullOnA404_insteadOfThrowing() = runTest {
        val engine = MockEngine { respond("""{"error":"no parked spot on record","code":"no_parked_spot"}""", HttpStatusCode.NotFound, jsonHeaders) }
        val client = ParkingApiClient("https://test.invalid", engine, accessToken = { "token" })
        assertNull(client.getParkedSpot())
    }

    @Test
    fun getParkedSpot_decodesTheSavedSpot() = runTest {
        val body = """{"lat":47.3793,"lon":8.5314,"zone_type":"blue","address_label":"Street parking spot #42",
            |"confirmed_at_epoch_millis":1700000000000,"expires_at_epoch_millis":1700003600000,
            |"estimated_fee_chf_per_hour":null}""".trimMargin()
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/me/parked", request.url.encodedPath)
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val client = ParkingApiClient("https://test.invalid", engine, accessToken = { "token" })

        val spot = client.getParkedSpot()
        assertEquals(47.3793, spot?.lat)
        assertEquals(ZoneType.BLUE, spot?.zoneType)
        assertEquals("Street parking spot #42", spot?.addressLabel)
        assertEquals(1700000000000L, spot?.confirmedAtEpochMillis)
        assertEquals(1700003600000L, spot?.expiresAtEpochMillis)
    }

    @Test
    fun saveParkedSpot_putsToTheRightPathAndDecodesTheResponse() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/me/parked", request.url.encodedPath)
            respond(
                """{"lat":47.37,"lon":8.53,"zone_type":"white","address_label":null,
                |"confirmed_at_epoch_millis":1700000000000,"expires_at_epoch_millis":null,
                |"estimated_fee_chf_per_hour":2.0}""".trimMargin(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val client = ParkingApiClient("https://test.invalid", engine, accessToken = { "token" })

        val spot = client.saveParkedSpot(
            lat = 47.37, lon = 8.53, zoneType = "white", addressLabel = null,
            expiresAtEpochMillis = null, estimatedFeeChfPerHour = 2.0,
        )

        assertEquals(ZoneType.WHITE, spot.zoneType)
        assertEquals(2.0, spot.estimatedFeeChfPerHour)
    }

    @Test
    fun deleteParkedSpot_sendsTheDeleteRequest() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/api/me/parked", request.url.encodedPath)
            respond("", HttpStatusCode.NoContent)
        }
        val client = ParkingApiClient("https://test.invalid", engine, accessToken = { "token" })
        client.deleteParkedSpot()
    }

    @Test
    fun parkedSpotCallsSurfaceA401AsAnApiException() = runTest {
        val engine = MockEngine { respond("""{"error":"sign in required","code":"unauthenticated"}""", HttpStatusCode.Unauthorized, jsonHeaders) }
        val client = ParkingApiClient("https://test.invalid", engine, accessToken = { null })
        val error = assertFailsWith<ApiException> { client.getParkedSpot() }
        assertEquals(401, error.status)
    }
}
