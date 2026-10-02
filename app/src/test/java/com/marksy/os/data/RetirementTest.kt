package com.marksy.os.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetirementTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MarksyDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.notificationEventDao()
    private val repository = NotificationRepository(dao)
    private val t0 = 1_790_000_000_000L
    private val minute = 60_000L

    private fun event(category: String, body: String) = NotificationEventEntity(
        sourcePackage = "p", sourceName = "App", sourceKey = body, eventFingerprint = body, title = category, body = body,
        postedAt = t0, category = category, priority = 1, confidence = 1f, isTrading = false
    )

    @After
    fun tearDown() = db.close()

    // User rule: an OTP leaves the active views once its validity passes; history keeps it.
    @Test
    fun expiredOtpLeavesActiveViewsButStaysInHistory() = runBlocking {
        dao.insert(event("OTP", "123456 is your OTP"))
        dao.insert(event("BANKING", "Rs 500 debited"))

        repository.retireExpired(t0 + 31 * minute)

        assertEquals(listOf("BANKING"), dao.observeActive().first().map { it.category })
        assertEquals(listOf("BANKING"), dao.observeRecent(10).first().map { it.category })
        assertEquals(2, dao.observeHistory().first().size)
    }

    // Retiring must not hide an unseen item from learning's "ignored" signal.
    @Test
    fun unseenRetiredItemStillCountsAsIgnored() = runBlocking {
        dao.insert(event("OTP", "123456 is your OTP"))

        repository.retireExpired(t0 + 31 * minute)

        assertEquals(1, dao.findStaleNew(t0 + 1, 10).size)
    }

    // Inbox redesign: a seen, low-attention item leaves the Inbox after two days; history keeps it, unread stays.
    @Test
    fun seenLowAttentionItemRetiresAfterTwoDays() = runBlocking {
        dao.insert(event("BANKING", "Rs 500 debited").copy(isRead = true, lifecycleState = "ACTIVE"))
        dao.insert(event("MESSAGES", "Call me"))

        repository.retireExpired(t0 + 2 * 24 * 60 * minute)

        assertEquals(listOf("MESSAGES"), dao.observeActive().first().map { it.category })
        assertEquals(2, dao.observeHistory().first().size)
    }
}
