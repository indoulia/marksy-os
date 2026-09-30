package com.marksy.os.gateway

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private fun row(key: String, postedAt: Long) = NotificationEventEntity(
        sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceKey = key, eventFingerprint = "f-$key",
        title = "Call $key", body = "BUY RENUKA CMP 23 SL 22", postedAt = postedAt, category = "TRADING", priority = 10,
        confidence = 1f, isTrading = true, deliveryState = DeliveryState.PENDING.name
    )
}
