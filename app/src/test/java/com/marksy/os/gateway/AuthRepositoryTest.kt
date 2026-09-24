package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    private val refreshError: Throwable? = null
) : AuthApiClient {
    var refreshCallCount = 0
    override suspend fun login(userId: String, password: String): SessionResponseDto = loginError?.let { throw it } ?: loginResult!!
    override suspend fun refresh(currentToken: String): SessionResponseDto {
        refreshCallCount++
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

    private fun session(expiresInMs: Long) = SessionResponseDto(
        sessionToken = "sess_new", userId = "prsingh",
        issuedAt = "2026-09-25T09:00:00Z", expiresAt = "2026-09-25T17:00:00Z", readOnly = false
    ).let { it to (System.currentTimeMillis() + expiresInMs) }

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
}
