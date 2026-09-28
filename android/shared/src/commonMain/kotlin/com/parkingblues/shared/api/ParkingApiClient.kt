package com.parkingblues.shared.api

import com.parkingblues.shared.model.SessionSnapshot
import com.parkingblues.shared.model.ZoneFilter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CreateSessionRequest(
    val lat: Double,
    val lon: Double,
    val zone: String,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
)

@Serializable
private data class PositionRequest(val lat: Double, val lon: Double)

/**
 * Thin wrapper over the exact same JSON API backend/app.py serves to the
 * web MVP (web/app.js calls the same five endpoints). No client-side
 * clustering/rejection logic here on purpose -- see the KMP shared-module
 * decision in the project plan: the algorithm stays server-tunable, this
 * is just transport + DTOs.
 */
class ParkingApiClient(private val baseUrl: String) {
    private val http = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }

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
        http.get("$baseUrl/api/session/$sessionId").body()

    suspend fun updatePosition(sessionId: String, lat: Double, lon: Double): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/position") {
            contentType(ContentType.Application.Json)
            setBody(PositionRequest(lat, lon))
        }.body()

    suspend fun rejectCurrent(sessionId: String): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/reject").body()

    suspend fun confirmCurrent(sessionId: String): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/confirm").body()

    suspend fun expandRadius(sessionId: String): SessionSnapshot =
        http.post("$baseUrl/api/session/$sessionId/expand").body()

    fun close() = http.close()
}
