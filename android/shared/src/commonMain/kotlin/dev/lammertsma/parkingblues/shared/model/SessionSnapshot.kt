package dev.lammertsma.parkingblues.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LatLon(val lat: Double, val lon: Double)

/** Mirrors backend/session.py SessionState. */
@Serializable
enum class SessionState {
    @SerialName("searching") SEARCHING,
    @SerialName("exhausted") EXHAUSTED,
    @SerialName("parked") PARKED,
}

/**
 * The full session_json() response from backend/app.py -- one snapshot
 * covers everything a screen needs to render (current target, what's
 * still upcoming, what's already been checked and dimmed, and the live
 * `event` from whichever action produced this snapshot).
 */
@Serializable
data class SessionSnapshot(
    @SerialName("session_id") val sessionId: String,
    val state: SessionState,
    @SerialName("radius_m") val radiusM: Double,
    val origin: LatLon,
    val you: LatLon,
    @SerialName("preferred_duration_minutes") val preferredDurationMinutes: Int? = null,
    val current: ParkingSegment? = null,
    val upcoming: List<ParkingSegment> = emptyList(),
    val rejected: List<ParkingSegment> = emptyList(),
    @SerialName("rejected_count") val rejectedCount: Int = 0,
    val event: String? = null,
)
