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

    // True only while the initial createSession call (startSearch) is in
    // flight -- the car screen uses this to show "Searching..." instead of
    // its empty-state message during that gap (which can be a real wait on
    // a Cloud Run cold start, not just a flash). Other calls (position
    // updates, reject, confirm, expand) already have a live session to keep
    // showing while they're in flight, so they don't need this.
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

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
        _loading.value = true
        runCatching { api.createSession(lat, lon, zone, durationMinutes) }
            .onSuccess { _session.value = it; _error.value = null }
            .onFailure { _error.value = it.message ?: "Failed to start search" }
        _loading.value = false
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

    // -- parked spot sync (best-effort; see backend/app.py's /api/me/parked) --
    // A nicety on top of the on-device record (ParkedSpotStore on Android),
    // not a replacement for it: these never touch _error/_session, so a
    // failure here (not signed in, offline, server hiccup) is silent and
    // never interrupts anything the driver is actually doing. :automotive
    // constructs its ParkingApiClient with no access token at all (no sign-in
    // there yet -- see AGENTS.md section 2, "optional sign-in"), so every
    // call here from that process predictably 401s and no-ops; that's
    // expected until Automotive OS gets its own sign-in flow, not a bug.

    suspend fun syncParkedSpot(
        lat: Double,
        lon: Double,
        zoneType: String?,
        addressLabel: String?,
        expiresAtEpochMillis: Long?,
        estimatedFeeChfPerHour: Double?,
    ) {
        runCatching {
            api.saveParkedSpot(lat, lon, zoneType, addressLabel, expiresAtEpochMillis, estimatedFeeChfPerHour)
        }
    }

    /** The account's remembered parked spot, or null if signed out, nothing
     *  is on record, or the call simply failed -- see this section's doc. */
    suspend fun fetchParkedSpot() = runCatching { api.getParkedSpot() }.getOrNull()

    suspend fun clearParkedSpotSync() {
        runCatching { api.deleteParkedSpot() }
    }

    private companion object {
        const val DEFAULT_BACKOFF_SECONDS = 10
    }
}
