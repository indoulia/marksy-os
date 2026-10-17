package com.marksy.os.gateway

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.capture.CandidateDeliveryRun
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.TipCandidateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TradingDeliveryRunTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MarksyDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.notificationEventDao()
    private val capture = CaptureContext(
        capturePackages = setOf("com.fivepaisa.trade"), chatAllowList = emptySet(), chatSenders = emptySet(),
        username = "user-1", deviceSalt = "salt"
    )
    private val client = object : MarksyGatewayClient {
        override suspend fun capture(eventId: Long, message: CapturedMessage) = Result.success(MarksyInsight(eventId, "ok"))
        override suspend fun captureList() = Result.success(emptySet<String>())
    }

    @After
    fun tearDown() = db.close()

    // Regression: one run sent only the first 10 calls; the rest waited for the 15-minute periodic run.
    @Test
    fun oneRunDeliversEveryPendingCall() = runBlocking {
        repeat(12) { i -> dao.insert(row("k$i", i.toLong())) }

        TradingDeliveryRun(dao, client, capture).drain()

        assertEquals(0, dao.findPendingCapture(50).size)
    }

    // A row put back to PENDING must end the run with a retry, not spin the drain loop.
    @Test
    fun aRowWaitingForTheCaptureListEndsTheRunWithARetry() = runBlocking {
        dao.insert(row("k1", 1L))

        val retry = TradingDeliveryRun(dao, client, capture.copy(capturePackages = null)).drain()

        assertTrue(retry)
        assertEquals(1, dao.findPendingCapture(5).size)
    }

    // A row whose gate throws must be marked NOT_APPLICABLE, not left IN_FLIGHT blocking all delivery.
    @Test
    fun aRowWhoseGateThrowsIsMarkedLocalInsteadOfStalling() = runBlocking {
        dao.insert(row("k1", 1L))
        dao.insert(row("k2", 2L))

        var captureCount = 0
        val testClient = object : MarksyGatewayClient {
            override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> {
                captureCount++
                return Result.success(MarksyInsight(eventId, "ok"))
            }
            override suspend fun captureList() = Result.success(emptySet<String>())
        }

        // Injected decide throws for row 1 only; row 2 delegates to CaptureGate.decide.
        val decideFn = { event: NotificationEventEntity, ctx: CaptureContext ->
            if (event.id == 1L) throw IllegalStateException("Simulated gate error")
            CaptureGate.decide(event, ctx)
        }

        TradingDeliveryRun(dao, testClient, capture, decide = decideFn).drain()

        // Row 1 should be NOT_APPLICABLE (gate threw); row 2 should be delivered.
        assertEquals(0, dao.findPendingCapture(50).size)
        assertEquals(1, captureCount)  // Only row 2 reached capture
        assertEquals("NOT_APPLICABLE", dao.findById(1L)?.deliveryState)
        assertEquals("DELIVERED", dao.findById(2L)?.deliveryState)
    }

    // Finding C2: chatSenders is re-read after each findPendingCapture call, so a row inserted mid-run still gets its sender masked.
    @Test
    fun aChatRowThatArrivesMidRunUsesTheSenderRecordedAtThatMoment() = runBlocking {
        dao.insert(row("k1", 1L))
        val recordedSenders = mutableSetOf<String>()
        val sentTexts = mutableMapOf<Long, String>()
        var midRunRowId = -1L
        val testClient = object : MarksyGatewayClient {
            override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> {
                sentTexts[eventId] = message.text
                if (recordedSenders.isEmpty()) {
                    // Simulate the listener recording the sender strictly before the row is inserted (finding C2b).
                    recordedSenders += "Amit"
                    midRunRowId = dao.insert(chatRow("k2", 2L))
                }
                return Result.success(MarksyInsight(eventId, "ok"))
            }
            override suspend fun captureList() = Result.success(emptySet<String>())
        }
        val groupContext = capture.copy(chatAllowList = setOf("stocktips"))

        TradingDeliveryRun(dao, testClient, groupContext, chatSenders = { recordedSenders.toSet() }).drain()

        assertTrue(sentTexts.containsKey(midRunRowId))
        val midRunText = sentTexts.getValue(midRunRowId)
        assertFalse(midRunText.contains("Amit"))
    }

    // Finding M3: the gate-error log line names the exception's class, never its message.
    @Test
    fun aGateErrorLogsTheExceptionClassNameNeverItsMessage() = runBlocking {
        ShadowLog.stream = null
        ShadowLog.clear()
        dao.insert(row("k1", 1L))
        val secretMessage = "leaked notification body: BUY RELIANCE"
        val decideFn = { _: NotificationEventEntity, _: CaptureContext -> throw IllegalStateException(secretMessage) }

        TradingDeliveryRun(dao, client, capture, decide = decideFn).drain()

        val messages = ShadowLog.getLogs().map { it.msg }
        assertTrue(messages.any { it.contains("gate-error") && it.contains("IllegalStateException") })
        assertFalse(messages.any { it.contains(secretMessage) })
    }

    // EPIC-036: an accepted candidate waits for the capture list, then leaves as its reviewed canonical line only.
    @Test
    fun aReviewedCandidateIsDeliveredAsItsCanonicalLineOnly() = runBlocking {
        val captureDao = db.captureDao()
        val id = captureDao.insertCandidate(
            TipCandidateEntity(
                evidenceId = 1, method = "USER_SHARED_IMAGE", sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceVerified = true,
                capturedAt = 1L, evidenceRef = "sha256:ab", extractedText = "BUY RENUKA @ 23 Target 26 SL 22 from Rahul", confidence = .9,
                symbol = "RENUKA", side = "BUY", entry = 23.0, target = 26.0, stopLoss = 22.0, horizon = null, visibleTimestamp = null,
                ambiguities = "", state = "ACCEPTED", userChoseSend = true, deliveryState = DeliveryState.PENDING.name, updatedAt = 1L
            )
        )
        val sent = mutableListOf<CapturedMessage>()
        val testClient = object : MarksyGatewayClient {
            override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> {
                sent += message
                return Result.success(MarksyInsight(eventId, "ok"))
            }
            override suspend fun captureList() = Result.success(emptySet<String>())
        }

        assertTrue(CandidateDeliveryRun(captureDao, testClient, capture.copy(capturePackages = null)).drain())
        assertEquals(DeliveryState.PENDING.name, captureDao.candidate(id)!!.deliveryState)
        assertFalse(CandidateDeliveryRun(captureDao, testClient, capture).drain())
        assertEquals(listOf("BUY RENUKA @ 23 Target 26 SL 22"), sent.map { it.text })
        assertEquals(DeliveryState.DELIVERED.name, captureDao.candidate(id)!!.deliveryState)
    }

    // Option-tip safety B: the first run, an offline retry, the user's Retry, Send now and a stale-row reset never hand an option to the client.
    @Test
    fun optionAndMaybeOptionCallsNeverReachTheClientOnAnyAttempt() = runBlocking {
        val sent = mutableListOf<String>()
        var online = false
        val recording = object : MarksyGatewayClient {
            override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> {
                sent += message.text
                return if (online) Result.success(MarksyInsight(eventId, "ok")) else Result.failure(java.io.IOException("offline"))
            }
            override suspend fun captureList() = Result.success(emptySet<String>())
        }
        val ctx = capture.copy(
            capturePackages = setOf("com.fivepaisa.trade", "com.google.android.gm", "com.android.providers.calendar"), chatAllowList = setOf("zerodh", "stocktips")
        )
        val stale = System.currentTimeMillis() - TradingDeliveryPolicy.STALE_IN_FLIGHT_MS - 60_000L
        val option = dao.insert(row("o", 1L).copy(body = "BUY NIFTY 24500 CE @ 180 TGT 220 SL 150"))
        val maybe = dao.insert(row("m", 2L).copy(body = "BUY TCS (18 PE) @ 3500 TGT 3600 SL 3400"))
        val connector = dao.insert(row("g", 3L).copy(sourcePackage = "com.google.android.gm", sourceName = "Gmail", body = "BUY BANKNIFTY 52000 PE @ 310 TGT 380 SL 280"))
        val failedBefore = dao.insert(row("f", 4L).copy(body = "SELL NIFTY24500CE below 120 TGT 90 SL 140", deliveryState = DeliveryState.FAILED.name))
        val stuck = dao.insert(row("i", 5L).copy(body = "BUY NIFTY 24500 CE @ 180 TGT 220 SL 150", deliveryState = DeliveryState.IN_FLIGHT.name, lastDeliveryAttemptAt = stale))
        val routes = listOf(
            row("sm", 7L).copy(sourcePackage = "com.google.android.apps.messaging", sourceName = "Messages", title = "JD-ZERODH-S", body = "BUY NIFTY 24500 CALL @ 180 TGT 220 SL 150"),
            row("sm2", 8L).copy(sourcePackage = "com.google.android.apps.messaging", sourceName = "Messages", title = "JD-ZERODH-S", body = "BUY TCS (18 PE) @ 3500 TGT 3600 SL 3400"),
            row("wa", 9L).copy(sourcePackage = "com.whatsapp", sourceName = "WhatsApp", title = "StockTips: Amit", body = "Amit: BUY TCS (18 PE) @ 3500 TGT 3600 SL 3400", chatGroup = true),
            row("wa2", 10L).copy(sourcePackage = "com.whatsapp", sourceName = "WhatsApp", title = "StockTips: Amit", body = "Amit: BUY NIFTY 24500 CE @ 180 TGT 220 SL 150", chatGroup = true),
            row("tg", 11L).copy(sourcePackage = "org.telegram.messenger", sourceName = "Telegram", title = "StockTips", body = "SELL BANKNIFTY 52000 PUT below 300 TGT 250 SL 330", chatGroup = true),
            row("tg2", 12L).copy(sourcePackage = "org.telegram.messenger", sourceName = "Telegram", title = "StockTips", body = "BUY TCS (18 PE) @ 3500 TGT 3600 SL 3400", chatGroup = true),
            row("cal", 13L).copy(sourcePackage = "com.android.providers.calendar", sourceName = "Calendar", body = "BUY NIFTY 24500 CE @ 180 TGT 220 SL 150"),
            row("gm2", 14L).copy(sourcePackage = "com.google.android.gm", sourceName = "Gmail", body = "BUY TCS (18 PE) @ 3500 TGT 3600 SL 3400")
        ).map { dao.insert(it) }
        val stock = dao.insert(row("s", 6L).copy(body = "BUY TCS @ 3500 TGT 3600 SL 3400, trades at 18 PE"))
        val run = TradingDeliveryRun(dao, recording, ctx)

        assertTrue(run.drain())
        assertEquals(DeliveryState.PENDING.name, dao.findById(stock)!!.deliveryState)
        assertEquals(1, dao.requeueFailed(failedBefore))
        assertEquals(1, dao.recoverStaleInFlight(System.currentTimeMillis() - TradingDeliveryPolicy.STALE_IN_FLIGHT_MS))
        online = true
        assertFalse(run.drain())

        assertEquals(listOf("Call s\nBUY TCS @ 3500 TGT 3600 SL 3400, trades at 18 PE"), sent.distinct())
        (listOf(option, maybe, connector, failedBefore, stuck) + routes).forEach { id ->
            val row = dao.findById(id)!!
            assertEquals("$id", DeliveryState.NOT_APPLICABLE.name to CaptureGate.OPTION_UNSUPPORTED, row.deliveryState to row.deliveryNote)
        }
    }

    // A flagged or contract-shaped screenshot (shared, picked, teaser or screen capture) that reached the queue still never reaches the client.
    @Test
    fun aQueuedOptionScreenshotNeverReachesTheClient() = runBlocking {
        val captureDao = db.captureDao()
        fun candidate(method: String, symbol: String, ambiguities: String) = TipCandidateEntity(
            evidenceId = 1, method = method, sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceVerified = true,
            capturedAt = 1L, evidenceRef = "sha256:ab", extractedText = "BUY NIFTY 24500 CE @ 180 Target 220 SL 150", confidence = .9,
            symbol = symbol, side = "BUY", entry = 180.0, target = 220.0, stopLoss = 150.0, horizon = null, visibleTimestamp = null,
            ambiguities = ambiguities, state = "ACCEPTED", userChoseSend = true, deliveryState = DeliveryState.PENDING.name, updatedAt = 1L
        )
        val ids = listOf(
            candidate("MEDIA_PROJECTION", "NIFTY", "option-contract"),
            candidate("USER_SHARED_IMAGE", "NIFTY", "option-contract,option-ambiguous"),
            candidate("USER_SELECTED_IMAGE", "NIFTY24500CE", "")
        ).map { captureDao.insertCandidate(it) }
        val sent = mutableListOf<String>()
        val recording = object : MarksyGatewayClient {
            override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> {
                sent += message.text
                return Result.success(MarksyInsight(eventId, "ok"))
            }
            override suspend fun captureList() = Result.success(emptySet<String>())
        }

        assertFalse(CandidateDeliveryRun(captureDao, recording, capture).drain())

        assertEquals(emptyList<String>(), sent)
        ids.forEach { assertEquals(DeliveryState.NOT_APPLICABLE.name to CaptureGate.OPTION_UNSUPPORTED, captureDao.candidate(it)!!.let { c -> c.deliveryState to c.deliveryNote }) }
    }

    // The client's own block is kept on the phone with its reason, never failed or retried, for calls and reviewed captures alike.
    @Test
    fun aCallTheClientRefusesAsAnOptionIsKeptNotFailed() = runBlocking {
        val blocking = object : MarksyGatewayClient {
            override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> = Result.failure(OptionTipBlocked())
            override suspend fun captureList() = Result.success(emptySet<String>())
        }
        val id = dao.insert(row("k1", 1L))
        assertFalse(TradingDeliveryRun(dao, blocking, capture).drain())
        assertEquals(DeliveryState.NOT_APPLICABLE.name to CaptureGate.OPTION_UNSUPPORTED, dao.findById(id)!!.let { it.deliveryState to it.deliveryNote })

        val captureDao = db.captureDao()
        val candidate = captureDao.insertCandidate(
            TipCandidateEntity(
                evidenceId = 1, method = "USER_SHARED_IMAGE", sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceVerified = true,
                capturedAt = 1L, evidenceRef = "sha256:ab", extractedText = "BUY RENUKA @ 23 Target 26 SL 22", confidence = .9,
                symbol = "RENUKA", side = "BUY", entry = 23.0, target = 26.0, stopLoss = 22.0, horizon = null, visibleTimestamp = null,
                ambiguities = "", state = "ACCEPTED", userChoseSend = true, deliveryState = DeliveryState.PENDING.name, updatedAt = 1L
            )
        )
        assertFalse(CandidateDeliveryRun(captureDao, blocking, capture).drain())
        assertEquals(DeliveryState.NOT_APPLICABLE.name to CaptureGate.OPTION_UNSUPPORTED, captureDao.candidate(candidate)!!.let { it.deliveryState to it.deliveryNote })
    }

    private fun row(key: String, postedAt: Long) = NotificationEventEntity(
        sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceKey = key, eventFingerprint = "f-$key",
        title = "Call $key", body = "BUY RENUKA CMP 23 SL 22", postedAt = postedAt, category = "TRADING", priority = 10,
        confidence = 1f, isTrading = true, deliveryState = DeliveryState.PENDING.name
    )

    private fun chatRow(key: String, postedAt: Long) = NotificationEventEntity(
        sourcePackage = "com.whatsapp", sourceName = "WhatsApp", sourceKey = key, eventFingerprint = "f-$key",
        title = "StockTips: Amit", body = "Amit: BUY RENUKA CMP 23 SL 22 TGT 26", postedAt = postedAt,
        category = "TRADING", priority = 10, confidence = 1f, isTrading = true,
        deliveryState = DeliveryState.PENDING.name, chatGroup = true
    )
}
