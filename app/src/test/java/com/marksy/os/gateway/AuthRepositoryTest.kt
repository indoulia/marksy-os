package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

private class FakeAuthApiClient(
    private val loginResult: SessionResponseDto? = null,
    private val loginError: Throwable? = null,
    private val refreshResult: SessionResponseDto? = null,
    private val refreshError: Throwable? = null,
    private val refreshDelayMs: Long = 0
) : AuthApiClient {
    var refreshCallCount = 0
    val logins = mutableListOf<Pair<String, String>>()
    override suspend fun login(userId: String, password: String): SessionResponseDto {
        logins += userId to password
        return loginError?.let { throw it } ?: loginResult!!
    }
    override suspend fun refresh(currentToken: String): SessionResponseDto {
        refreshCallCount++
        if (refreshDelayMs > 0) delay(refreshDelayMs)
        return refreshError?.let { throw it } ?: refreshResult!!
    }
    override suspend fun logout(currentToken: String): Boolean = true
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // See AuthSessionStoreTest's identical @Before for why this is needed:
    // AuthSessionStore's in-memory holder is a process-wide singleton.
    @Before
    fun resetSession() {
        AuthSessionStore(context).clearSession()
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.addProvider(TestAndroidKeyStoreProvider())
            }
        }
    }

    // Timestamps follow the clock: a fixed expiresAt started failing once the real date reached it.
    private fun session(expiresInMs: Long) = System.currentTimeMillis().let { now ->
        SessionResponseDto(
            sessionToken = "sess_new", userId = "prsingh",
            issuedAt = java.time.Instant.ofEpochMilli(now).toString(), expiresAt = java.time.Instant.ofEpochMilli(now + expiresInMs).toString(), readOnly = false
        ) to (now + expiresInMs)
    }

    @Test
    fun neverLoggedInYieldsNullToken() = runBlocking {
        val store = AuthSessionStore(context)
        val repository = AuthRepository(FakeAuthApiClient(), store)

        assertNull(repository.currentToken())
    }

    @Test
    fun freshSessionReturnsStoredTokenWithoutRefreshing() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_fresh", "prsingh", System.currentTimeMillis() + 6 * 60 * 60 * 1000L, remember = true)
        val fake = FakeAuthApiClient()
        val repository = AuthRepository(fake, store)

        val token = repository.currentToken()

        assertEquals("sess_fresh", token)
        assertEquals(0, fake.refreshCallCount)
    }

    @Test
    fun nearExpirySessionRefreshesAndReturnsNewToken() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_old", "prsingh", System.currentTimeMillis() + 60_000L, remember = true)
        val (refreshed, _) = session(8 * 60 * 60 * 1000L)
        val fake = FakeAuthApiClient(refreshResult = refreshed)
        val repository = AuthRepository(fake, store)

        val token = repository.currentToken()

        assertEquals("sess_new", token)
        assertEquals(1, fake.refreshCallCount)
        assertEquals("sess_new", store.getToken())
    }

    @Test
    fun refreshFailureClearsSessionAndReturnsNull() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_old", "prsingh", System.currentTimeMillis() + 60_000L, remember = true)
        val fake = FakeAuthApiClient(refreshError = AuthApiException("HTTP 401"))
        val repository = AuthRepository(fake, store)

        val token = repository.currentToken()

        assertNull(token)
        assertNull(store.getToken())
    }

    // marksy-api rotates and revokes the old token on every successful refresh: a second,
    // concurrent caller refreshing with that now-stale token would otherwise clear a session
    // another caller just renewed. Two Market-screen loads racing near expiry must not sign
    // the user out.
    @Test
    fun concurrentRefreshesOnlyCallRefreshOnceAndBothSeeTheNewToken() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_old", "prsingh", System.currentTimeMillis() + 60_000L, remember = true)
        val (refreshed, _) = session(8 * 60 * 60 * 1000L)
        val fake = FakeAuthApiClient(refreshResult = refreshed, refreshDelayMs = 150L)
        val repository = AuthRepository(fake, store)

        val results = listOf(
            async { repository.currentToken() },
            async { repository.currentToken() }
        ).awaitAll()

        assertEquals(1, fake.refreshCallCount)
        assertEquals(listOf("sess_new", "sess_new"), results)
        assertEquals("sess_new", store.getToken())
    }

    // POST /auth/refresh only fails deterministically (MRA_SESSION_EXPIRED / 401) on a truly
    // expired token -- that is the only case AuthApiException models. A transient network/5xx
    // failure must not destroy a session that may still be valid; the background worker often
    // runs before connectivity returns.
    @Test
    fun transientRefreshFailureKeepsExistingSessionInsteadOfClearingIt() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_old", "prsingh", System.currentTimeMillis() + 60_000L, remember = true)
        val fake = FakeAuthApiClient(refreshError = java.io.IOException("timeout"))
        val repository = AuthRepository(fake, store)

        val token = repository.currentToken()

        assertEquals("sess_old", token)
        assertNotNull(store.getToken())
    }

    // A JSON-parse or Keystore-crypto failure during refresh is not an IOException -- it must
    // not escape currentToken() and crash the caller (a Compose screen's produceState block).
    @Test
    fun nonIoExceptionDuringRefreshDoesNotCrashAndKeepsSession() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_old", "prsingh", System.currentTimeMillis() + 60_000L, remember = true)
        val fake = FakeAuthApiClient(refreshError = IllegalStateException("boom"))
        val repository = AuthRepository(fake, store)

        val token = repository.currentToken()

        assertEquals("sess_old", token)
        assertNotNull(store.getToken())
    }

    // Same non-IOException-must-not-crash requirement, on the login() path.
    @Test
    fun loginNonIoExceptionDoesNotCrashAndReturnsFailure() = runBlocking {
        val store = AuthSessionStore(context)
        val repository = AuthRepository(FakeAuthApiClient(loginError = IllegalStateException("boom")), store)

        val result = repository.login("prsingh", "correct-password", remember = true)

        assertTrue(result.isFailure)
        assertNull(store.getToken())
    }

    @Test
    fun loginSavesSessionWithRememberChoice() = runBlocking {
        val store = AuthSessionStore(context)
        val (loggedIn, _) = session(8 * 60 * 60 * 1000L)
        val repository = AuthRepository(FakeAuthApiClient(loginResult = loggedIn), store)

        val result = repository.login("prsingh", "correct-password", remember = false)

        assertTrue(result.isSuccess)
        assertEquals("sess_new", store.getToken())
        assertTrue(!store.isRemembered())
    }

    @Test
    fun loginFailurePropagatesAsResultFailure() = runBlocking {
        val store = AuthSessionStore(context)
        val repository = AuthRepository(FakeAuthApiClient(loginError = AuthApiException("HTTP 401: Invalid credentials.")), store)

        val result = repository.login("prsingh", "wrong-password", remember = true)

        assertTrue(result.isFailure)
        assertNull(store.getToken())
    }

    @Test
    fun logoutClearsSession() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveSession("sess_abc", "prsingh", System.currentTimeMillis() + 60_000L, remember = true)
        val repository = AuthRepository(FakeAuthApiClient(), store)

        repository.logout()

        assertNull(store.getToken())
    }

    // marksy-api sessions last 8h and can only be renewed while still valid, so "Remember me"
    // keeps the password (Keystore-encrypted) and signs in again once the session is gone.
    @Test
    fun rememberedLoginSignsInAgainWhenTheSessionIsRejected() = runBlocking {
        val store = AuthSessionStore(context)
        val (fresh, _) = session(8 * 60 * 60 * 1000L)
        val fake = FakeAuthApiClient(loginResult = fresh, refreshError = AuthApiException("HTTP 401"))
        val repository = AuthRepository(fake, store)
        repository.login("prsingh", "pw", remember = true)
        store.saveSession("sess_old", "prsingh", System.currentTimeMillis() - 1_000L, remember = true)

        assertEquals("sess_new", repository.currentToken())
        assertEquals(listOf("prsingh" to "pw", "prsingh" to "pw"), fake.logins)
    }

    @Test
    fun rememberedLoginSignsInAgainWhenNoSessionIsStored() = runBlocking {
        val store = AuthSessionStore(context)
        val (fresh, _) = session(8 * 60 * 60 * 1000L)
        val repository = AuthRepository(FakeAuthApiClient(loginResult = fresh), store)
        repository.login("prsingh", "pw", remember = true)
        store.clearSession()

        assertEquals("sess_new", repository.currentToken())
        assertEquals("prsingh", store.signedInUserId())
    }

    // A changed password must not be retried forever; the username stays to prefill the form.
    @Test
    fun rejectedSavedPasswordIsForgottenButUsernameIsKept() = runBlocking {
        val store = AuthSessionStore(context)
        store.saveCredentials("prsingh", "old-pw")
        val repository = AuthRepository(FakeAuthApiClient(loginError = AuthApiException("HTTP 401: Invalid credentials.")), store)

        assertNull(repository.currentToken())
        assertTrue(!store.hasCredentials())
        assertEquals("prsingh", store.lastUserId())
        assertNull(store.signedInUserId())
    }

    // Sign Out ends the session and silent sign-in, but the saved password stays for the form.
    @Test
    fun logoutKeepsTheSavedPasswordForTheFormButStopsSilentSignIn() = runBlocking {
        val store = AuthSessionStore(context)
        val (fresh, _) = session(8 * 60 * 60 * 1000L)
        val fake = FakeAuthApiClient(loginResult = fresh)
        val repository = AuthRepository(fake, store)

        repository.login("prsingh", "pw", remember = true)
        repository.logout()
        assertTrue(store.hasCredentials())
        assertNull(repository.currentToken())
        assertNull(store.signedInUserId())
        assertEquals(1, fake.logins.size)

        assertTrue(repository.loginWithSavedPassword("prsingh", remember = true).isSuccess)
        assertEquals(listOf("prsingh" to "pw", "prsingh" to "pw"), fake.logins)
        assertEquals("sess_new", repository.currentToken())
    }

    @Test
    fun loginWithoutRememberForgetsSavedCredentials() = runBlocking {
        val store = AuthSessionStore(context)
        val (fresh, _) = session(8 * 60 * 60 * 1000L)
        val repository = AuthRepository(FakeAuthApiClient(loginResult = fresh), store)

        repository.login("prsingh", "pw", remember = true)
        repository.login("prsingh", "pw", remember = false)
        assertTrue(!store.hasCredentials())
        assertNull(store.lastUserId())
        assertTrue(repository.loginWithSavedPassword("prsingh", remember = true).isFailure)
    }
}
