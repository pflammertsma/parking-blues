package com.parkingblues.shared

import com.parkingblues.shared.api.ParkingApiClient
import com.parkingblues.shared.model.SessionSnapshot
import com.parkingblues.shared.model.ZoneFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive holder for the one active session, shared by both the phone
 * Compose UI and the car screens so they always observe the same state.
 * Mirrors what web/app.js does with its module-level `sessionId` variable
 * and per-action fetch() + render() calls -- just typed and observable.
 */
class ParkingSessionRepository(private val api: ParkingApiClient) {
    private val _session = MutableStateFlow<SessionSnapshot?>(null)
    val session: StateFlow<SessionSnapshot?> = _session.asStateFlow()

    private val _bearing = MutableStateFlow<Float?>(null)
    val bearing: StateFlow<Float?> = _bearing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun startSearch(
        lat: Double,
        lon: Double,
        zone: ZoneFilter,
        durationMinutes: Int? = null,
        bearing: Float? = null,
    ) {
        if (bearing != null) _bearing.value = bearing
        runCatching { api.createSession(lat, lon, zone, durationMinutes) }
            .onSuccess { _session.value = it; _error.value = null }
            .onFailure { _error.value = it.message ?: "Failed to start search" }
    }

    /** Called continuously from live location updates -- see LocationSource on Android. */
    suspend fun updatePosition(lat: Double, lon: Double, bearing: Float? = null) {
        if (bearing != null) _bearing.value = bearing
        val id = _session.value?.sessionId ?: return
        runCatching { api.updatePosition(id, lat, lon) }
            .onSuccess { _session.value = it }
            .onFailure { _error.value = it.message ?: "Failed to update position" }
    }

    suspend fun rejectCurrent() {
        val id = _session.value?.sessionId ?: return
        runCatching { api.rejectCurrent(id) }
            .onSuccess { _session.value = it }
            .onFailure { _error.value = it.message ?: "Failed to reject" }
    }

    suspend fun confirmCurrent() {
        val id = _session.value?.sessionId ?: return
        runCatching { api.confirmCurrent(id) }
            .onSuccess { _session.value = it }
            .onFailure { _error.value = it.message ?: "Failed to confirm" }
    }

    suspend fun expandRadius() {
        val id = _session.value?.sessionId ?: return
        runCatching { api.expandRadius(id) }
            .onSuccess { _session.value = it }
            .onFailure { _error.value = it.message ?: "Failed to expand radius" }
    }

    /** Back to the pristine pre-search state -- mirrors the web UI's Reset button. */
    fun reset() {
        _session.value = null
        _bearing.value = null
        _error.value = null
    }
}
