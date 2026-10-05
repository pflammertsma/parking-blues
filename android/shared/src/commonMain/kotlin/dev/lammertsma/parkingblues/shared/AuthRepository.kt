package dev.lammertsma.parkingblues.shared

import dev.lammertsma.parkingblues.shared.api.ApiException
import dev.lammertsma.parkingblues.shared.api.ParkingApiClient
import dev.lammertsma.parkingblues.shared.model.AccountInfo
import dev.lammertsma.parkingblues.shared.model.AuthSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthState {
    /** The default: no account, the app works anonymously. */
    data object SignedOut : AuthState
    data class SignedIn(val account: AccountInfo) : AuthState
}

/** What is persisted between launches (the platform decides how to protect it). */
data class StoredAuth(
    val refreshToken: String,
    val accessToken: String?,
    val accessExpiresAtSeconds: Long,
    val account: AccountInfo,
)

interface TokenStore {
    fun load(): StoredAuth?
    fun save(auth: StoredAuth?)
}

/**
 * Optional Google sign-in on top of the anonymous API. The app starts signed
 * out; once signed in it stays signed in until the user signs out. The refresh
 * token lets requests keep a valid access token without any UI -- including
 * from the car screen with the phone in a pocket.
 *
 * [nowSeconds] is injected (epoch seconds) so expiry is testable.
 */
class AuthRepository(
    private val api: ParkingApiClient,
    private val store: TokenStore,
    private val nowSeconds: () -> Long,
) {
    private val mutex = Mutex()
    private var current: StoredAuth? = store.load()

    private val _state = MutableStateFlow<AuthState>(
        current?.let { AuthState.SignedIn(it.account) } ?: AuthState.SignedOut
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Why the app signed itself out (blocked, session ended), for the UI to show once. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    /**
     * A valid access token, refreshing it first if needed; null when signed out
     * or when refreshing is not possible right now (then the call goes out
     * anonymously, which still works at the lower anonymous limits).
     */
    suspend fun accessToken(): String? = mutex.withLock {
        val auth = current ?: return@withLock null
        val token = auth.accessToken
        if (token != null && nowSeconds() < auth.accessExpiresAtSeconds - SAFETY_MARGIN_S) {
            return@withLock token
        }
        refreshLocked(auth)
    }

    /** The server rejected our access token (e.g. clock skew): refresh on next use. */
    fun markAccessTokenRejected() {
        current = current?.copy(accessExpiresAtSeconds = 0)
    }

    suspend fun signIn(googleIdToken: String): Result<AccountInfo> = mutex.withLock {
        runCatching { api.signInWithGoogle(googleIdToken) }
            .onSuccess { adopt(it) }
            .map { it.account }
    }

    suspend fun signOut() {
        val refreshToken = mutex.withLock { current?.refreshToken.also { clear(notice = null) } }
        if (refreshToken != null) runCatching { api.signOut(refreshToken) }
    }

    /** Deletes the account on the server, then signs out locally. */
    suspend fun deleteAccount(): Result<Unit> =
        runCatching { api.deleteAccount() }.onSuccess { mutex.withLock { clear(notice = null) } }

    private suspend fun refreshLocked(auth: StoredAuth): String? =
        runCatching { api.refresh(auth.refreshToken) }.fold(
            onSuccess = { adopt(it); it.accessToken },
            onFailure = { failure ->
                // The server said the login is over (revoked, expired, blocked):
                // leave the signed-in state. Anything else (offline, server
                // hiccup) keeps the login and just goes anonymous for this call.
                if (failure is ApiException && (failure.status == 401 || failure.status == 403)) {
                    clear(notice = failure.message)
                }
                null
            },
        )

    private fun adopt(session: AuthSession) {
        val stored = StoredAuth(
            refreshToken = session.refreshToken,
            accessToken = session.accessToken,
            accessExpiresAtSeconds = nowSeconds() + session.expiresIn,
            account = session.account,
        )
        current = stored
        store.save(stored)
        _state.value = AuthState.SignedIn(session.account)
        _notice.value = null
    }

    private fun clear(notice: String?) {
        current = null
        store.save(null)
        _state.value = AuthState.SignedOut
        _notice.value = notice
    }

    private companion object {
        const val SAFETY_MARGIN_S = 60L
    }
}
