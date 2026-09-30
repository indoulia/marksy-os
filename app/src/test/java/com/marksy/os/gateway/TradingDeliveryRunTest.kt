package com.marksy.os.gateway

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
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

        assertEquals(0, dao.findPendingTrading(50).size)
    }

    // A row put back to PENDING must end the run with a retry, not spin the drain loop.
    @Test
    fun aRowWaitingForTheCaptureListEndsTheRunWithARetry() = runBlocking {
        dao.insert(row("k1", 1L))

        val retry = TradingDeliveryRun(dao, client, capture.copy(capturePackages = null)).drain()

        assertTrue(retry)
        assertEquals(1, dao.findPendingTrading(5).size)
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
        assertEquals(0, dao.findPendingTrading(50).size)
        assertEquals(1, captureCount)  // Only row 2 reached capture
        assertEquals("NOT_APPLICABLE", dao.findById(1L)?.deliveryState)
        assertEquals("DELIVERED", dao.findById(2L)?.deliveryState)
    }

    // Finding C2: chatSenders is re-read after each findPendingTrading call, so a row inserted mid-run still gets its sender masked.
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
