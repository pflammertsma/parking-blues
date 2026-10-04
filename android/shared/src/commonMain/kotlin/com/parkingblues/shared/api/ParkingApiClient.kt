package com.parkingblues.shared.api

import com.parkingblues.shared.model.SessionSnapshot
import com.parkingblues.shared.model.ZoneFilter
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The backend's in-memory SessionStore (see AGENTS.md §2) doesn't survive
 * a Cloud Run cold start -- an idle instance scaling to zero loses every
 * session, so any client still holding an old session_id gets a 404 on its
 * next call. Without this, that 404's `{"error": "session not found"}` body
 * got force-decoded as a SessionSnapshot anyway (ktor's `.body()` doesn't
 * check status), surfacing a confusing kotlinx.serialization
 * "required fields missing" exception instead of the real cause -- caught
 * specifically in ParkingSessionRepository so it can recover by starting a
 * fresh session instead of just dead-ending on a cryptic error.
 */
class SessionNotFoundException(sessionId: String) :
    Exception("Session $sessionId no longer exists on the server")

private suspend inline fun <reified T> HttpResponse.bodyOrThrowIfSessionGone(sessionId: String): T {
    if (status == HttpStatusCode.NotFound) throw SessionNotFoundException(sessionId)
    return body()
}

@Serializable
private data class CreateSessionRequest(
    val lat: Double,
    val lon: Double,
    val zone: String,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
)

@Serializable
private data class PositionRequest(val lat: Double, val lon: Double)

private val configureClient: HttpClientConfig<*>.() -> Unit = {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(Logging) {
        level = LogLevel.INFO
    }
}

/**
 * Thin wrapper over the exact same JSON API backend/app.py serves to the
 * web MVP (web/app.js calls the same five endpoints). No client-side
 * clustering/rejection logic here on purpose -- see the KMP shared-module
 * decision in the project plan: the algorithm stays server-tunable, this
 * is just transport + DTOs.
 */
const val DEFAULT_BASE_URL = "https://api.parking-blues.lammertsma.dev"

class ParkingApiClient private constructor(
    private val baseUrl: String,
    private val http: HttpClient,
) {
    constructor(baseUrl: String = DEFAULT_BASE_URL) : this(baseUrl.trimEnd('/'), HttpClient(block = configureClient))

    /** Test-only: inject a fake/mock engine (e.g. Ktor's MockEngine) instead
     *  of hitting a real network -- see shared/src/commonTest. */
    constructor(baseUrl: String = DEFAULT_BASE_URL, engine: HttpClientEngine) : this(baseUrl.trimEnd('/'), HttpClient(engine, configureClient))

    suspend fun createSession(
        lat: Double,
        lon: Double,
        zone: ZoneFilter,
        durationMinutes: Int? = null,
    ): SessionSnapshot = http.post("$baseUrl/api/session") {
        contentType(ContentType.Application.Json)
        setBody(CreateSessionRequest(lat, lon, zone.wireValue, durationMinutes))
    }.body()

    suspend fun getSession(sessionId: String): SessionSnapshot =
        http.get("$baseUrl/api/session/$sessionId").bodyOrThrowIfSessionGone(sessionId)

    suspend fun updatePosition(sessionId: String, lat: Double, lon: Double): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/position") {
            contentType(ContentType.Application.Json)
            setBody(PositionRequest(lat, lon))
        }.bodyOrThrowIfSessionGone(sessionId)

    suspend fun rejectCurrent(sessionId: String): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/reject").bodyOrThrowIfSessionGone(sessionId)

    suspend fun confirmCurrent(sessionId: String): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/confirm").bodyOrThrowIfSessionGone(sessionId)

    suspend fun expandRadius(sessionId: String): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/expand").bodyOrThrowIfSessionGone(sessionId)

    fun close() = http.close()
}
