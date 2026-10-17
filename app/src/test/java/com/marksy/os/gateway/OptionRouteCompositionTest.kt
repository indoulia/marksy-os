package com.marksy.os.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.capture.CandidateDeliveryPolicy
import com.marksy.os.capture.CandidateDeliveryRun
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.TipCandidateEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

/** Real delivery runs wired to the real client: the HTTP layer is the only recorded part. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OptionRouteCompositionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, MarksyDatabase::class.java).allowMainThreadQueries().build()
    private val requests = mutableListOf<String>()
    private val capture = CaptureContext(
        capturePackages = setOf("com.fivepaisa.trade", "com.google.android.gm", "com.android.providers.calendar"),
        chatAllowList = setOf("stocktips"), chatSenders = emptySet(), username = "user-1", deviceSalt = "salt"
    )

    @Before
    fun signIn() {
        AuthSessionStore(context).saveSession("sess-token", "user-1", System.currentTimeMillis() + 3_600_000L, remember = true)
    }

    @After
    fun tearDown() = db.close()

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(TestAndroidKeyStoreProvider())
        }
    }

    private fun realClient() = object : MarksyTipsApiClient(
        AuthRepository(
            client = object : AuthApiClient {
                override suspend fun login(userId: String, password: String) = throw NotImplementedError()
                override suspend fun refresh(currentToken: String) = throw NotImplementedError()
                override suspend fun logout(currentToken: String) = throw NotImplementedError()
            },
            store = AuthSessionStore(context)
        )
    ) {
        override suspend fun execute(method: String, url: String, payload: JSONObject?): JSONObject {
            requests += "$method ${payload?.optString("text")}"
            return JSONObject().put("data", JSONObject()).put("meta", JSONObject())
        }
    }

    private fun event(key: String, pkg: String, name: String, body: String, group: Boolean = false) = NotificationEventEntity(
        sourcePackage = pkg, sourceName = name, sourceKey = key, eventFingerprint = "f-$key", title = if (group) "StockTips: Amit" else "Call $key",
        body = body, postedAt = 1L, category = "TRADING", priority = 10, confidence = 1f, isTrading = true,
        deliveryState = DeliveryState.PENDING.name, chatGroup = group
    )

    @Test
    fun theRealClientGetsNoRequestForOptionOrMaybeOptionNotifications() = runBlocking {
        val option = "BUY NIFTY 24500 CE @ 180 TGT 220 SL 150"
        val maybe = "BUY TCS (18 PE) @ 3500 TGT 3600 SL 3400"
        val stock = "BUY INFY @ 1450 TGT 1500 SL 1420"
        val dao = db.notificationEventDao()
        listOf(
            event("o1", "com.fivepaisa.trade", "5paisa", option), event("m1", "com.fivepaisa.trade", "5paisa", maybe),
            event("o2", "com.google.android.gm", "Gmail", option), event("m2", "com.google.android.gm", "Gmail", maybe),
            event("o3", "com.android.providers.calendar", "Calendar", option), event("m3", "com.android.providers.calendar", "Calendar", maybe),
            event("o4", "org.telegram.messenger", "Telegram", option, group = true), event("m4", "org.telegram.messenger", "Telegram", maybe, group = true),
            event("o5", "com.whatsapp", "WhatsApp", "Amit: $option", group = true), event("m5", "com.whatsapp", "WhatsApp", "Amit: $maybe", group = true)
        ).forEach { dao.insert(it) }
        assertFalse(TradingDeliveryRun(dao, realClient(), capture).drain())
        assertEquals(emptyList<String>(), requests)

        listOf(
            event("s1", "com.fivepaisa.trade", "5paisa", stock), event("s2", "com.google.android.gm", "Gmail", stock),
            event("s3", "org.telegram.messenger", "Telegram", stock, group = true)
        ).forEach { dao.insert(it) }
        assertFalse(TradingDeliveryRun(dao, realClient(), capture).drain())

        assertEquals(3, requests.size)
        assertEquals(emptyList<String>(), requests.filter { !it.startsWith("POST ") || "INFY" !in it })
        assertEquals(0, dao.findPendingCapture(50).size)
    }

    @Test
    fun theRealClientGetsNoRequestForOptionScreenshotCandidates() = runBlocking {
        val captureDao = db.captureDao()
        fun candidate(text: String, symbol: String, ambiguities: String) = TipCandidateEntity(
            evidenceId = 1, method = "USER_SHARED_IMAGE", sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceVerified = true,
            capturedAt = 1L, evidenceRef = "sha256:ab", extractedText = text, confidence = .9,
            symbol = symbol, side = "BUY", entry = 180.0, target = 220.0, stopLoss = 150.0, horizon = null, visibleTimestamp = null,
            ambiguities = ambiguities, state = "ACCEPTED", userChoseSend = true, deliveryState = DeliveryState.PENDING.name, updatedAt = 1L
        )
        listOf(
            candidate("BUY NIFTY 24500 CE @ 180 Target 220 SL 150", "NIFTY", "option-contract"),
            candidate("BUY TCS (18 PE) @ 180 Target 220 SL 150", "TCS", "option-contract,option-ambiguous"),
            candidate("BUY NIFTY 24500 CE @ 180 Target 220 SL 150", "NIFTY24500CE", "")
        ).forEach { captureDao.insertCandidate(it) }
        assertFalse(CandidateDeliveryRun(captureDao, realClient(), capture).drain())
        assertEquals(emptyList<String>(), requests)

        captureDao.insertCandidate(candidate("BUY RENUKA @ 180 Target 220 SL 150", "RENUKA", ""))
        assertFalse(CandidateDeliveryRun(captureDao, realClient(), capture).drain())

        assertEquals(1, requests.size)
        assertTrue(requests.single(), "RENUKA" in requests.single())
    }

    private val line = "BUY RENUKA @ 180 Target 220 SL 150"
    private val corrected = "option-contract,stock-confirmed"

    private fun screenshot(text: String?, ambiguities: String = "", state: DeliveryState = DeliveryState.PENDING, attempts: Int = 0) = TipCandidateEntity(
        evidenceId = 1, method = "USER_SHARED_IMAGE", sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceVerified = true,
        capturedAt = 1L, evidenceRef = "sha256:ab", extractedText = text, confidence = .9,
        symbol = "RENUKA", side = "BUY", entry = 180.0, target = 220.0, stopLoss = 150.0, horizon = null, visibleTimestamp = null,
        ambiguities = ambiguities, state = "ACCEPTED", userChoseSend = true, deliveryState = state.name, deliveryAttempts = attempts,
        lastDeliveryAttemptAt = 1L, updatedAt = 1L
    )

    // An older app's row, or one saved without the flag: the recognized text decides, and no text means no send.
    @Test
    fun theRealClientGetsNoRequestForAScreenshotWhoseTextReadsAsAnOptionWhateverItsFlags() = runBlocking {
        val captureDao = db.captureDao()
        val kept = listOf(
            screenshot("BUY NIFTY 24500 CE @ 180 Target 220 SL 150"),
            screenshot("BUY BANKNIFTY 52000 PUT @ 180 Target 220 SL 150"),
            screenshot("BUY NIFTY 24500 CALL @ 180 Target 220 SL 150"),
            screenshot("$line\nHedge: NIFTY 24500 PE"),
            screenshot("BUY RENUKA (18 PE) @ 180 Target 220 SL 150"),
            screenshot("BUY NIFTY 24500 CE @ 180 Target 220 SL 150", ambiguities = "low-ocr-confidence"),
            screenshot("BUY NIFTY 24500 CE @ 180 Target 220 SL 150", attempts = 3),
            screenshot("BUY NIFTY 24500 CE @ 180 Target 220 SL 150", state = DeliveryState.IN_FLIGHT, attempts = 1),
            screenshot(null),
            screenshot("  "),
            screenshot(null, ambiguities = corrected),
            // A stock correction never overrides written option wording, a share's or an index's, on any attempt.
            screenshot("BUY TCS @ 45 TGT 60 SL 35 (3500 CE)", ambiguities = corrected),
            screenshot("BUY TCS @ 45 TGT 60 SL 35 (3500 PE)", ambiguities = corrected),
            screenshot("RELIANCE 1400 PE", ambiguities = corrected),
            screenshot("BUY INFY @ 45 TGT 60 SL 35 (1800 CALL)", ambiguities = corrected, attempts = 3),
            screenshot("BUY INFY @ 45 TGT 60 SL 35 (1800 PUT)", ambiguities = corrected, state = DeliveryState.IN_FLIGHT, attempts = 1),
            screenshot("BUY NIFTY @ 180 TGT 220 SL 150 (BANKNIFTY 52000 PUT)", ambiguities = corrected)
        ).map { captureDao.insertCandidate(it) }
        captureDao.recoverStaleInFlight(Long.MAX_VALUE)
        assertFalse(CandidateDeliveryRun(captureDao, realClient(), capture).drain())
        assertEquals(emptyList<String>(), requests)
        kept.forEach { assertEquals(DeliveryState.NOT_APPLICABLE.name, captureDao.candidate(it)!!.deliveryState) }

        captureDao.insertCandidate(screenshot(line))
        captureDao.insertCandidate(screenshot("$line\nTrades at 18 PE"))
        captureDao.insertCandidate(screenshot("BUY RENUKA (18 PE) @ 180 Target 220 SL 150", ambiguities = "option-contract,option-ambiguous,stock-confirmed"))
        assertFalse(CandidateDeliveryRun(captureDao, realClient(), capture).drain())
        assertEquals(listOf("POST $line", "POST $line", "POST $line"), requests)
    }

    @Test
    fun aDecisionThatSkipsThePolicyStillSendsNoOptionScreenshot() = runBlocking {
        val captureDao = db.captureDao()
        val ids = listOf(screenshot("BUY NIFTY 24500 CE @ 180 Target 220 SL 150"), screenshot(null)).map { captureDao.insertCandidate(it) }
        val blind = { c: TipCandidateEntity, ctx: CaptureContext -> CandidateDeliveryPolicy.decide(c.copy(extractedText = line), ctx) }

        assertFalse(CandidateDeliveryRun(captureDao, realClient(), capture, decide = blind).drain())

        assertEquals(emptyList<String>(), requests)
        ids.forEach { assertEquals(DeliveryState.NOT_APPLICABLE.name, captureDao.candidate(it)!!.deliveryState) }
    }
}
