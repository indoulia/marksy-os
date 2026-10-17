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

    private fun clientReplying(accepted: JSONObject, tip: () -> JSONObject) = object : MarksyTipsApiClient(authRepository()) {
        override suspend fun execute(method: String, url: String, payload: JSONObject?): JSONObject = when {
            method == "POST" -> JSONObject().put("data", accepted.put("tipId", "tip-1").put("kind", "TIP")).put("meta", JSONObject())
            url.endsWith("/tips/tip-1") -> tip()
            else -> throw IllegalStateException("Unexpected call: $method $url")
        }
    }

    // Without the tip's details, the accepted reply's own status is shown; tracking is never promised.
    @Test
    fun missingTipDetailsFallBackToTheAcceptedStatus() = runBlocking {
        val option = JSONObject().put("status", "DATA_UNRESOLVED").put("reason", "OPTION_UNSUPPORTED")
        val failedFetch = clientReplying(JSONObject(option.toString())) { throw httpFailure(404) }.capture(1L, message).getOrThrow()
        val noLedger = clientReplying(JSONObject(option.toString())) { JSONObject().put("data", JSONObject()).put("meta", JSONObject()) }.capture(1L, message).getOrThrow()
        assertEquals("Option tracking unsupported · not tracked or scored", failedFetch.summary)
        assertEquals("Option tracking unsupported · not tracked or scored", noLedger.summary)

        val olderServer = clientReplying(JSONObject()) { throw httpFailure(404) }.capture(1L, message).getOrThrow()
        assertEquals("Recorded by Marksy", olderServer.summary)
    }

    // Option-tip safety B: the last stop before the network. No request at all, and the refusal is not a transport failure.
    @Test
    fun anOptionOrMaybeOptionCallIsNeverRequested() = runBlocking {
        val requests = mutableListOf<String>()
        val client = object : MarksyTipsApiClient(authRepository()) {
            override suspend fun execute(method: String, url: String, payload: JSONObject?): JSONObject {
                requests += "$method $url"
                return JSONObject().put("data", JSONObject().put("kind", "TIP")).put("meta", JSONObject())
            }
        }
        listOf("BUY NIFTY 24500 CE @ 180 TGT 220 SL 150", "BUY TCS (18 PE) @ 3500 TGT 3600 SL 3400").forEach { text ->
            assertTrue(text, client.capture(1L, message.copy(text = text)).exceptionOrNull() is OptionTipBlocked)
        }
        assertEquals(emptyList<String>(), requests)

        assertTrue(client.capture(1L, message.copy(text = "BUY TCS @ 3500 TGT 3600 SL 3400, trades at 18 PE")).isSuccess)
        assertEquals(1, requests.size)
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
