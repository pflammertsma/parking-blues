package dev.lammertsma.parkingblues.shared

import dev.lammertsma.parkingblues.shared.api.ParkingApiClient
import dev.lammertsma.parkingblues.shared.api.RateLimitedException
import dev.lammertsma.parkingblues.shared.api.SessionNotFoundException
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot
import dev.lammertsma.parkingblues.shared.model.ZoneFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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

    // Remembered purely so a dropped session (see SessionNotFoundException's
    // doc) can be silently restarted with the same zone, rather than
    // needing the caller to re-supply it.
    private var lastZone: ZoneFilter = ZoneFilter.BOTH

    // After the server answers 429, live position updates pause until its
    // Retry-After has passed instead of retrying every second.
    private var resumeUpdatesAt: TimeMark? = null
    private var lastDurationMinutes: Int? = null

    suspend fun startSearch(
        lat: Double,
        lon: Double,
        zone: ZoneFilter,
        durationMinutes: Int? = null,
        bearing: Float? = null,
    ) {
        if (bearing != null) _bearing.value = bearing
        lastZone = zone
        lastDurationMinutes = durationMinutes
        runCatching { api.createSession(lat, lon, zone, durationMinutes) }
            .onSuccess { _session.value = it; _error.value = null }
            .onFailure { _error.value = it.message ?: "Failed to start search" }
    }

    /** Called continuously from live location updates -- see LocationSource on Android. */
    suspend fun updatePosition(lat: Double, lon: Double, bearing: Float? = null) {
        if (bearing != null) _bearing.value = bearing
        val id = _session.value?.sessionId ?: return
        if (resumeUpdatesAt?.hasNotPassedNow() == true) return
        runCatching { api.updatePosition(id, lat, lon) }
            .onSuccess { _session.value = it; _error.value = null }
            .onFailure { recoverOrSetError(it, lat, lon, "Failed to update position") }
    }

    suspend fun rejectCurrent() {
        val id = _session.value?.sessionId ?: return
        val you = _session.value?.you
        runCatching { api.rejectCurrent(id) }
            .onSuccess { _session.value = it; _error.value = null }
            .onFailure { recoverOrSetError(it, you?.lat, you?.lon, "Failed to reject") }
    }

    suspend fun confirmCurrent() {
        val id = _session.value?.sessionId ?: return
        runCatching { api.confirmCurrent(id) }
            .onSuccess { _session.value = it; _error.value = null }
            .onFailure { _error.value = it.message ?: "Failed to confirm" }
    }

    suspend fun expandRadius() {
        val id = _session.value?.sessionId ?: return
        val you = _session.value?.you
        runCatching { api.expandRadius(id) }
            .onSuccess { _session.value = it; _error.value = null }
            .onFailure { recoverOrSetError(it, you?.lat, you?.lon, "Failed to expand radius") }
    }

    /**
     * The backend's in-memory session store doesn't survive a Cloud Run
     * cold start (see SessionNotFoundException's doc) -- when that's what
     * actually failed, the right move is a fresh startSearch using the
     * same zone/position, not surfacing a dead-end error for something the
     * driver had no part in and can't act on.
     */
    private suspend fun recoverOrSetError(failure: Throwable, lat: Double?, lon: Double?, fallbackMessage: String) {
        if (failure is RateLimitedException) {
            resumeUpdatesAt = TimeSource.Monotonic.markNow() + (failure.retryAfterSeconds ?: DEFAULT_BACKOFF_SECONDS).seconds
            _error.value = failure.message
        } else if (failure is SessionNotFoundException && lat != null && lon != null) {
            startSearch(lat, lon, lastZone, lastDurationMinutes)
        } else {
            _error.value = failure.message ?: fallbackMessage
        }
    }

    /** Back to the pristine pre-search state -- mirrors the web UI's Reset button. */
    fun reset() {
        _session.value = null
        _bearing.value = null
        _error.value = null
    }

    private companion object {
        const val DEFAULT_BACKOFF_SECONDS = 10
    }
}
