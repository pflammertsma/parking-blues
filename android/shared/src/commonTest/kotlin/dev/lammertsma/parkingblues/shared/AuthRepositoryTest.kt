package dev.lammertsma.parkingblues.shared

import dev.lammertsma.parkingblues.shared.api.ParkingApiClient
import dev.lammertsma.parkingblues.shared.model.AccountInfo
import dev.lammertsma.parkingblues.shared.model.AuthSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val account = AccountInfo(sub = "sub-1", email = "a@example.com", name = "Alice")

    private class FakeStore(var saved: StoredAuth? = null) : TokenStore {
        override fun load() = saved
        override fun save(auth: StoredAuth?) {
            saved = auth
        }
    }

    private fun session(n: Int, expiresIn: Int = 900) = AuthSession(
        accessToken = "access-$n", expiresIn = expiresIn, refreshToken = "refresh-$n", account = account,
    )

    private fun stored(expiresAt: Long) = StoredAuth("refresh-0", "access-0", expiresAt, account)

    private fun repository(
        store: TokenStore,
        clock: () -> Long,
        handler: (path: String) -> Pair<HttpStatusCode, String>,
        calls: MutableList<String> = mutableListOf(),
    ): AuthRepository {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += path
            val (status, body) = handler(path)
            respond(body, status, jsonHeaders)
        }
        return AuthRepository(ParkingApiClient("https://test.invalid", engine), store, clock)
    }

    @Test
    fun startsSignedOutAndAnonymousByDefault() = runTest {
        val repo = repository(FakeStore(), { 0 }, { error("no request expected") })
        assertEquals(AuthState.SignedOut, repo.state.value)
        assertNull(repo.accessToken())
    }

    @Test
    fun restoresASavedLoginAndKeepsUsingAValidAccessToken() = runTest {
        val calls = mutableListOf<String>()
        val repo = repository(FakeStore(stored(expiresAt = 10_000)), { 1_000 }, { error("no refresh needed") }, calls)
        assertIs<AuthState.SignedIn>(repo.state.value)
        assertEquals("access-0", repo.accessToken())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun refreshesAnExpiredAccessTokenAndRotatesTheRefreshToken() = runTest {
        val store = FakeStore(stored(expiresAt = 1_000))
        val repo = repository(store, { 5_000 }, { HttpStatusCode.OK to json.encodeToString(session(1)) })

        assertEquals("access-1", repo.accessToken())
        assertEquals("refresh-1", store.saved?.refreshToken)
        assertEquals(5_000 + 900, store.saved?.accessExpiresAtSeconds)
    }

    @Test
    fun aTokenAboutToExpireIsRefreshedEarly() = runTest {
        val repo = repository(FakeStore(stored(expiresAt = 5_030)), { 5_000 }, {
            HttpStatusCode.OK to json.encodeToString(session(1))
        })
        assertEquals("access-1", repo.accessToken()) // 30 s left is inside the safety margin
    }

    @Test
    fun concurrentCallersShareOneRefresh() = runTest {
        val calls = mutableListOf<String>()
        val repo = repository(FakeStore(stored(expiresAt = 0)), { 5_000 }, {
            HttpStatusCode.OK to json.encodeToString(session(1))
        }, calls)

        val tokens = (1..5).map { async { repo.accessToken() } }.awaitAll()

        assertEquals(List(5) { "access-1" }, tokens)
        assertEquals(1, calls.count { it == "/api/auth/refresh" })
    }

    @Test
    fun aRevokedLoginSignsOutAndExplainsWhy() = runTest {
        val store = FakeStore(stored(expiresAt = 0))
        val repo = repository(store, { 5_000 }, {
            HttpStatusCode.Unauthorized to """{"error":"Sign in again","code":"invalid_refresh_token"}"""
        })

        assertNull(repo.accessToken())
        assertEquals(AuthState.SignedOut, repo.state.value)
        assertNull(store.saved)
        assertEquals("Please sign in again", repo.notice.value)
    }

    @Test
    fun aBlockedAccountSignsOutWithTheBlockMessage() = runTest {
        val repo = repository(FakeStore(stored(expiresAt = 0)), { 5_000 }, {
            HttpStatusCode.Forbidden to """{"error":"This account is blocked","code":"account_blocked"}"""
        })
        assertNull(repo.accessToken())
        assertTrue(repo.notice.value.orEmpty().contains("blocked"))
    }

    @Test
    fun beingOfflineKeepsTheLoginAndGoesAnonymousForThatCall() = runTest {
        val store = FakeStore(stored(expiresAt = 0))
        val engine = MockEngine { throw RuntimeException("offline") }
        val repo = AuthRepository(ParkingApiClient("https://test.invalid", engine), store, { 5_000 })

        assertNull(repo.accessToken())
        assertIs<AuthState.SignedIn>(repo.state.value)
        assertNotNull(store.saved)
    }

    @Test
    fun signInStoresTheSessionAndUpdatesState() = runTest {
        val store = FakeStore()
        val repo = repository(store, { 100 }, { HttpStatusCode.OK to json.encodeToString(session(1)) })

        val result = repo.signIn("google-id-token")

        assertEquals(account, result.getOrNull())
        assertEquals(AuthState.SignedIn(account), repo.state.value)
        assertEquals("refresh-1", store.saved?.refreshToken)
    }

    @Test
    fun aFailedSignInChangesNothing() = runTest {
        val repo = repository(FakeStore(), { 100 }, {
            HttpStatusCode.Unauthorized to """{"error":"x","code":"invalid_google_token"}"""
        })
        assertTrue(repo.signIn("bad").isFailure)
        assertEquals(AuthState.SignedOut, repo.state.value)
    }

    @Test
    fun signOutClearsLocallyAndRevokesOnTheServer() = runTest {
        val calls = mutableListOf<String>()
        val store = FakeStore(stored(expiresAt = 10_000))
        val repo = repository(store, { 100 }, { HttpStatusCode.NoContent to "" }, calls)

        repo.signOut()

        assertEquals(AuthState.SignedOut, repo.state.value)
        assertNull(store.saved)
        assertEquals(listOf("/api/auth/logout"), calls)
    }

    @Test
    fun deletingTheAccountSignsOut() = runTest {
        val calls = mutableListOf<String>()
        val store = FakeStore(stored(expiresAt = 10_000))
        val repo = repository(store, { 100 }, { HttpStatusCode.NoContent to "" }, calls)

        assertTrue(repo.deleteAccount().isSuccess)

        assertEquals("/api/me", calls.single())
        assertEquals(AuthState.SignedOut, repo.state.value)
        assertNull(store.saved)
    }

    @Test
    fun aFailedAccountDeletionKeepsTheUserSignedIn() = runTest {
        val repo = repository(FakeStore(stored(expiresAt = 10_000)), { 100 }, {
            HttpStatusCode.InternalServerError to "{}"
        })
        assertTrue(repo.deleteAccount().isFailure)
        assertIs<AuthState.SignedIn>(repo.state.value)
    }

    @Test
    fun apiRequestsCarryTheBearerTokenOnlyWhenSignedIn() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seen += request.headers[HttpHeaders.Authorization]
            respond("{}", HttpStatusCode.NotFound, jsonHeaders)
        }
        val anonymous = ParkingApiClient("https://test.invalid", engine)
        val signedIn = ParkingApiClient("https://test.invalid", engine, accessToken = { "tok-123" })

        runCatching { anonymous.getSession("s") }
        runCatching { signedIn.getSession("s") }

        assertEquals(listOf(null, "Bearer tok-123"), seen)
    }

    @Test
    fun aRejectedAccessTokenTriggersTheUnauthorizedCallback() = runTest {
        var rejected = 0
        val engine = MockEngine {
            respond("""{"error":"expired","code":"token_expired"}""", HttpStatusCode.Unauthorized, jsonHeaders)
        }
        val client = ParkingApiClient("https://test.invalid", engine, accessToken = { "old" }, onUnauthorized = { rejected++ })

        val failure = runCatching { client.getSession("s") }.exceptionOrNull()

        assertEquals(1, rejected)
        assertEquals("Please sign in again", failure?.message)
    }
}
