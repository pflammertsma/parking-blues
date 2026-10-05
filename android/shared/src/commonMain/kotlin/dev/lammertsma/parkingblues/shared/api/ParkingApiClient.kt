package dev.lammertsma.parkingblues.shared.api

import dev.lammertsma.parkingblues.shared.model.AccountInfo
import dev.lammertsma.parkingblues.shared.model.AuthSession
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import dev.lammertsma.parkingblues.shared.model.ZoneFilter
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
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

/**
 * The backend rate-limits callers and answers 429 with a Retry-After header.
 * Surfaced as its own type so the repository can pause instead of hammering a
 * server that has just said "slow down".
 */
class RateLimitedException(val retryAfterSeconds: Int?) :
    Exception("Too many requests; slowing down for a moment")

/**
 * Any other non-success response. [code] is the backend's machine-readable
 * error code when it sent one (token_expired, account_blocked,
 * invalid_refresh_token, ...).
 */
class ApiException(val status: Int, val code: String? = null) :
    Exception(friendlyMessage(status, code))

private fun friendlyMessage(status: Int, code: String?): String = when (code) {
    "account_blocked" -> "This account is blocked because of unusual activity. Contact paul@lammertsma.dev if you think this is a mistake."
    "token_expired", "invalid_token", "invalid_refresh_token" -> "Please sign in again"
    "email_not_verified" -> "The Google account's email address is not verified"
    "invalid_google_token" -> "Google sign-in could not be verified"
    "auth_unavailable" -> "Sign-in is not available right now"
    else -> "Server error ($status)"
}

@Serializable
private data class ApiError(val error: String? = null, val code: String? = null)

private val errorJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class CreateSessionRequest(
    val lat: Double,
    val lon: Double,
    val zone: String,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
)

@Serializable
private data class PositionRequest(val lat: Double, val lon: Double)

@Serializable
private data class IdTokenRequest(@SerialName("id_token") val idToken: String)

@Serializable
private data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

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
 *
 * Sign-in is optional: [accessToken] supplies the current access token (or
 * null when signed out, i.e. anonymous), and [onUnauthorized] is told when the
 * server rejected the token it was given so the caller can refresh it.
 */
const val DEFAULT_BASE_URL = "https://api.parking-blues.lammertsma.dev"

class ParkingApiClient private constructor(
    private val baseUrl: String,
    private val http: HttpClient,
    private val accessToken: suspend () -> String?,
    private val onUnauthorized: () -> Unit,
) {
    constructor(
        baseUrl: String = DEFAULT_BASE_URL,
        accessToken: suspend () -> String? = { null },
        onUnauthorized: () -> Unit = {},
    ) : this(baseUrl.trimEnd('/'), HttpClient(block = configureClient), accessToken, onUnauthorized)

    /** Test-only: inject a fake/mock engine (e.g. Ktor's MockEngine) instead
     *  of hitting a real network -- see shared/src/commonTest. */
    constructor(
        baseUrl: String = DEFAULT_BASE_URL,
        engine: HttpClientEngine,
        accessToken: suspend () -> String? = { null },
        onUnauthorized: () -> Unit = {},
    ) : this(baseUrl.trimEnd('/'), HttpClient(engine, configureClient), accessToken, onUnauthorized)

    private suspend fun HttpRequestBuilder.authorize() {
        accessToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /**
     * Turns error statuses into specific exceptions before decoding. Without
     * this, an error body like {"error": "..."} would be force-decoded as the
     * expected type and fail with an unrelated kotlinx.serialization error.
     * [sessionId] is passed for calls on an existing session, where 404 means
     * the session is gone (see [SessionNotFoundException]).
     */
    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(sessionId: String? = null): T {
        failIfNotSuccess(sessionId)
        return body()
    }

    private suspend fun HttpResponse.failIfNotSuccess(sessionId: String? = null) {
        when {
            status == HttpStatusCode.TooManyRequests ->
                throw RateLimitedException(headers["Retry-After"]?.toIntOrNull())
            status == HttpStatusCode.NotFound && sessionId != null ->
                throw SessionNotFoundException(sessionId)
            !status.isSuccess() -> {
                val code = runCatching { errorJson.decodeFromString<ApiError>(bodyAsTextSafe()).code }.getOrNull()
                if (status == HttpStatusCode.Unauthorized) onUnauthorized()
                throw ApiException(status.value, code)
            }
        }
    }

    private suspend fun HttpResponse.bodyAsTextSafe(): String =
        runCatching { bodyAsText() }.getOrDefault("")

    suspend fun createSession(
        lat: Double,
        lon: Double,
        zone: ZoneFilter,
        durationMinutes: Int? = null,
    ): SessionSnapshot = http.post("$baseUrl/api/session") {
        authorize()
        contentType(ContentType.Application.Json)
        setBody(CreateSessionRequest(lat, lon, zone.wireValue, durationMinutes))
    }.bodyOrThrow()

    suspend fun getSession(sessionId: String): SessionSnapshot =
        http.get("$baseUrl/api/session/$sessionId") { authorize() }.bodyOrThrow(sessionId)

    suspend fun updatePosition(sessionId: String, lat: Double, lon: Double): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/position") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(PositionRequest(lat, lon))
        }.bodyOrThrow(sessionId)

    suspend fun rejectCurrent(sessionId: String): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/reject") { authorize() }.bodyOrThrow(sessionId)

    suspend fun confirmCurrent(sessionId: String): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/confirm") { authorize() }.bodyOrThrow(sessionId)

    suspend fun expandRadius(sessionId: String): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/expand") { authorize() }.bodyOrThrow(sessionId)

    // -- optional sign-in (these calls never carry the access token) ----------

    suspend fun signInWithGoogle(googleIdToken: String): AuthSession =
        http.post("$baseUrl/api/auth/google") {
            contentType(ContentType.Application.Json)
            setBody(IdTokenRequest(googleIdToken))
        }.bodyOrThrow()

    suspend fun refresh(refreshToken: String): AuthSession =
        http.post("$baseUrl/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }.bodyOrThrow()

    suspend fun signOut(refreshToken: String) {
        http.post("$baseUrl/api/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }.failIfNotSuccess()
    }

    suspend fun me(): AccountInfo = http.get("$baseUrl/api/me") { authorize() }.bodyOrThrow()

    suspend fun deleteAccount() {
        http.delete("$baseUrl/api/me") { authorize() }.failIfNotSuccess()
    }

    fun close() = http.close()
}
