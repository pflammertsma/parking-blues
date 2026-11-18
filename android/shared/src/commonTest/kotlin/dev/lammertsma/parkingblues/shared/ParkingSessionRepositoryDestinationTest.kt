package dev.lammertsma.parkingblues.shared

import dev.lammertsma.parkingblues.shared.api.ParkingApiClient
import dev.lammertsma.parkingblues.shared.model.LatLon
import dev.lammertsma.parkingblues.shared.model.ParkedSpot
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

/** The destination the user chose is told apart from a search that simply starts where they are. */
class ParkingSessionRepositoryDestinationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val where = LatLon(47.3769, 8.5417)

    private fun repository() = ParkingSessionRepository(
        ParkingApiClient(
            "https://test.invalid",
            MockEngine {
                respond(
                    json.encodeToString(
                        SessionSnapshot(
                            sessionId = "s", state = SessionState.SEARCHING, radiusM = 300.0,
                            origin = where, you = where, current = null,
                        )
                    ),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
    )

    @Test
    fun aSearchStartedAsADestinationRemembersIt() = runTest {
        val repository = repository()
        repository.startSearch(where.lat, where.lon, ZoneFilter.BOTH, destination = true)
        assertEquals(where, repository.destination.value)
    }

    @Test
    fun anOrdinarySearchHasNoDestination() = runTest {
        val repository = repository()
        repository.startSearch(where.lat, where.lon, ZoneFilter.BOTH, destination = true)
        repository.startSearch(where.lat, where.lon, ZoneFilter.BOTH)
        assertNull(repository.destination.value)
    }

    @Test
    fun resettingForgetsTheDestination() = runTest {
        val repository = repository()
        repository.startSearch(where.lat, where.lon, ZoneFilter.BOTH, destination = true)
        repository.reset()
        assertNull(repository.destination.value)
    }

    @Test
    fun aParkedSpotKeepsItsDestinationThroughTheStoredJson() {
        val spot = ParkedSpot(
            lat = 47.3779, lon = 8.5403, zoneType = null, addressLabel = null,
            confirmedAtEpochMillis = 5L, expiresAtEpochMillis = null,
            assumed = true, destination = LatLon(47.382, 8.546),
        )
        val back = json.decodeFromString<ParkedSpot>(json.encodeToString(spot))
        assertEquals(spot, back)
    }

    @Test
    fun aSpotSavedByAnOlderVersionStillLoads() {
        val old = """{"lat":47.3779,"lon":8.5403,"zone_type":"blue","address_label":"x","confirmed_at_epoch_millis":5,"expires_at_epoch_millis":null}"""
        val spot = json.decodeFromString<ParkedSpot>(old)
        assertEquals(false, spot.assumed)
        assertNull(spot.destination)
    }
}
