package com.marksy.os.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.MarksyTerminalException
import com.marksy.os.gateway.TradingDeliveryPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturedRetryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MarksyDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.notificationEventDao()
    private val repository = NotificationRepository(dao)

    private fun row(key: String, state: DeliveryState, trading: Boolean = true) = NotificationEventEntity(
        sourcePackage = "p", sourceName = "App", sourceKey = key, eventFingerprint = key, title = "t", body = key,
        postedAt = 1_790_000_000_000L, category = "TRADING", priority = 1, confidence = 1f, isTrading = trading,
        deliveryState = state.name, deliveryAttempts = 5
    )

    @After
    fun tearDown() = db.close()

    @Test
    fun retryPutsFailedRowBackInTheQueueOnly() = runBlocking {
        val failed = dao.insert(row("a", DeliveryState.FAILED))
        val delivered = dao.insert(row("b", DeliveryState.DELIVERED))
        dao.setDeliveryNote(failed, "rejected")

        assertEquals(true, repository.retryDelivery(failed))
        assertEquals(false, repository.retryDelivery(delivered))

        val back = dao.getById(failed)!!
        assertEquals(DeliveryState.PENDING.name, back.deliveryState)
        assertEquals(0, back.deliveryAttempts)
        assertNull(back.deliveryNote)
        assertEquals(listOf(failed), dao.findPendingCapture(10).map { it.id })
        assertEquals(DeliveryState.DELIVERED.name, dao.getById(delivered)!!.deliveryState)
    }

    // Retired calls must stay visible on Captured (Earlier); other categories and archived rows must not.
    @Test
    fun capturedIncludesRetiredTradingRows() = runBlocking {
        val open = dao.insert(row("a", DeliveryState.DELIVERED))
        val retired = dao.insert(row("b", DeliveryState.DELIVERED))
        dao.insert(row("c", DeliveryState.NOT_APPLICABLE, trading = false))
        val archived = dao.insert(row("d", DeliveryState.DELIVERED))
        dao.resolve(listOf(retired), "Retired: x", 1L)
        dao.archiveAll(listOf(archived), 1L)

        assertEquals(setOf(open, retired), repository.observeCaptured().first().map { it.id }.toSet())
    }

    @Test
    fun failureCodeNeverCarriesTheMessage() {
        assertEquals("rejected", TradingDeliveryPolicy.failureCode(MarksyTerminalException("secret body")))
        assertEquals("invalid", TradingDeliveryPolicy.failureCode(IllegalArgumentException("secret body")))
    }
}
