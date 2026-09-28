package com.marksy.os.gateway

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    @After
    fun tearDown() = db.close()

    // Regression: one run sent only the first 10 calls; the rest waited for the 15-minute periodic run.
    @Test
    fun oneRunDeliversEveryPendingCall() = runBlocking {
        repeat(12) { i ->
            dao.insert(NotificationEventEntity(
                sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceKey = "k$i", eventFingerprint = "f$i",
                title = "Call $i", body = "BUY RENUKA CMP 23 SL 22", postedAt = i.toLong(), category = "TRADING", priority = 10,
                confidence = 1f, isTrading = true, deliveryState = DeliveryState.PENDING.name
            ))
        }
        val client = object : MarksyGatewayClient {
            override suspend fun analyze(request: MarksyTradingEventRequest) = Result.success(MarksyInsight(request.eventId, "ok"))
        }

        TradingDeliveryRun(dao, client).drain()

        assertEquals(0, dao.findPendingTrading(50).size)
    }
}
