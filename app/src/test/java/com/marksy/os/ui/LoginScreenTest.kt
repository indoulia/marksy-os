package com.marksy.os.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.AuthApiClient
import com.marksy.os.gateway.AuthRepository
import com.marksy.os.gateway.AuthSessionStore
import com.marksy.os.gateway.SessionResponseDto
import com.marksy.os.gateway.TestAndroidKeyStoreProvider
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

private class FixtureAuthApiClient(
    private val loginResult: SessionResponseDto? = null,
    private val loginError: Throwable? = null
) : AuthApiClient {
    override suspend fun login(userId: String, password: String): SessionResponseDto = loginError?.let { throw it } ?: loginResult!!
    override suspend fun refresh(currentToken: String) = throw NotImplementedError()
    override suspend fun logout(currentToken: String) = throw NotImplementedError()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    // See AuthSessionStoreTest's identical @Before for why this is needed:
    // AuthSessionStore's in-memory holder is a process-wide singleton, and
    // `successfulLoginCallsOnSignedIn` below writes a real session through it.
    @Before
    fun resetSession() {
        com.marksy.os.gateway.AuthSessionStore(ApplicationProvider.getApplicationContext<Context>()).clearSession()
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

    private fun repository(client: AuthApiClient) =
        AuthRepository(client, AuthSessionStore(ApplicationProvider.getApplicationContext<Context>()))

    @Test
    fun notSignedInShowsLoginForm() {
        compose.setContent {
            LoginScreen(authRepository = repository(FixtureAuthApiClient()), padding = PaddingValues(), currentUserId = null, onSignedIn = {})
        }

        compose.onNodeWithText("Sign In").assertExists()
    }

    @Test
    fun successfulLoginCallsOnSignedIn() {
        var signedIn = false
        val session = SessionResponseDto("sess_abc", "prsingh", "2026-09-25T09:00:00Z", "2026-09-25T17:00:00Z", false)
        compose.setContent {
            LoginScreen(authRepository = repository(FixtureAuthApiClient(loginResult = session)), padding = PaddingValues(), currentUserId = null, onSignedIn = { signedIn = true })
        }

        compose.onNodeWithText("Marksy username").performTextInput("prsingh")
        compose.onNodeWithText("Password").performTextInput("correct-password")
        compose.onNodeWithText("Sign In").performClick()
        // A remembered session's save hops onto Dispatchers.IO for real Keystore crypto,
        // which waitForIdle() alone does not block on -- poll instead of a single check.
        compose.waitUntil(timeoutMillis = 5_000) { signedIn }

        assert(signedIn)
    }

    @Test
    fun failedLoginShowsErrorAndDoesNotCallOnSignedIn() {
        var signedIn = false
        compose.setContent {
            LoginScreen(
                authRepository = repository(FixtureAuthApiClient(loginError = com.marksy.os.gateway.AuthApiException("HTTP 401: Invalid credentials."))),
                padding = PaddingValues(), currentUserId = null, onSignedIn = { signedIn = true }
            )
        }

        compose.onNodeWithText("Marksy username").performTextInput("prsingh")
        compose.onNodeWithText("Password").performTextInput("wrong-password")
        compose.onNodeWithText("Sign In").performClick()
        compose.waitForIdle()

        assert(!signedIn)
        compose.onNodeWithText("Invalid credentials", substring = true).assertExists()
    }

    @Test
    fun alreadySignedInShowsUserIdAndSignOut() {
        compose.setContent {
            LoginScreen(authRepository = repository(FixtureAuthApiClient()), padding = PaddingValues(), currentUserId = "prsingh", onSignedIn = {})
        }

        compose.onNodeWithText("prsingh", substring = true).assertExists()
        compose.onNodeWithText("Sign Out").assertExists()
    }
}
