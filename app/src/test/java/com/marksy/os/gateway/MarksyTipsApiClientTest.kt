package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarksyTipsApiClientTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetSession() {
        AuthSessionStore(context).clearSession()
    }

    // Never-logged-in and lapsed-session both surface as authRepository.currentToken() == null.
    // Losing queued trading tips permanently (TERMINAL/FAILED) whenever this happens would be
    // silent data loss the moment a remembered session expires mid-batch; it must instead be
    // retryable so delivery resumes automatically once the user signs back in.
    @Test
    fun notSignedInFailsRetryableNotTerminal() = runBlocking {
        val authRepository = AuthRepository(
            client = object : AuthApiClient {
                override suspend fun login(userId: String, password: String) = throw NotImplementedError()
                override suspend fun refresh(currentToken: String) = throw NotImplementedError()
                override suspend fun logout(currentToken: String) = throw NotImplementedError()
            },
            store = AuthSessionStore(context)
        )
        val client = MarksyTipsApiClient(authRepository, baseUrl = "https://marksy.indoulia.com/api/v1")
        val request = MarksyTradingEventRequest(
            eventId = 1L,
            source = "Upstox",
            sourcePackage = "com.upstox.pro",
            title = "Order update",
            body = "Symbol: RELIANCE BUY order executed at 1450",
            category = "TRADING",
            priority = 3,
            confidence = 0.99f,
            occurredAt = 1_700_000_000_000L,
            idempotencyKey = "key-1"
        )

        val result = client.analyze(request)

        assertTrue(result.isFailure)
        val error = requireNotNull(result.exceptionOrNull())
        assertFalse(error is MarksyTerminalException)
        assertTrue(TradingDeliveryPolicy.shouldRetry(error))
    }
}
