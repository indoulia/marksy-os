package com.marksy.os.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarksyTipsApiClientRecordedReceiptTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun signIn() {
        AuthSessionStore(context).saveSession("sess-token", "user-1", System.currentTimeMillis() + 3_600_000L, remember = true)
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

    private fun authRepository() = AuthRepository(
        client = object : AuthApiClient {
            override suspend fun login(userId: String, password: String) = throw NotImplementedError()
            override suspend fun refresh(currentToken: String) = throw NotImplementedError()
            override suspend fun logout(currentToken: String) = throw NotImplementedError()
        },
        store = AuthSessionStore(context)
    )

    private val message = CapturedMessage(
        deviceEventKey = "n1-key", medium = "APP_NOTIFICATION", appPackage = "com.upstox.pro",
        channelLabel = "Upstox", text = "Symbol: RELIANCE BUY order executed at 1450",
        devicePostedAt = "2026-09-30T00:00:00Z"
    )

    // Finding M5: the POST recorded the tip; a failed follow-up GET must not fail the row.
    @Test
    fun aFailedComparisonFetchStillReturnsARecordedInsight() = runBlocking {
        val client = object : MarksyTipsApiClient(authRepository()) {
            override suspend fun execute(method: String, url: String, payload: JSONObject?): JSONObject = when {
                method == "POST" -> JSONObject()
                    .put("data", JSONObject().put("tipId", "tip-1").put("kind", "TIP"))
                    .put("meta", JSONObject())
                url.endsWith("/tips/tip-1") -> throw httpFailure(404)
                else -> throw IllegalStateException("Unexpected call: $method $url")
            }
        }

        val result = client.capture(1L, message)

        assertTrue(result.isSuccess)
        val insight = result.getOrThrow()
        assertEquals(1L, insight.eventId)
        assertEquals("TIP", insight.action)
    }

    // The POST itself failing is unchanged: the row is not recorded server-side, so it must still fail/retry.
    @Test
    fun aFailedPostStillFails() = runBlocking {
        val client = object : MarksyTipsApiClient(authRepository()) {
            override suspend fun execute(method: String, url: String, payload: JSONObject?): JSONObject =
                throw httpFailure(500)
        }

        val result = client.capture(1L, message)

        assertTrue(result.isFailure)
    }
}
