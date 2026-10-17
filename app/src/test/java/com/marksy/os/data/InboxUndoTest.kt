package com.marksy.os.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.PersonalLearning
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxUndoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MarksyDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.notificationEventDao()
    private val t0 = 1_790_000_000_000L
    private var now = t0
    private val learning = LearningRepository(db.learningDao(), dao, { true }) { now }
    private val repository = NotificationRepository(dao, learning)

    private fun insert(body: String): Long = runBlocking {
        dao.insert(NotificationEventEntity(
            sourcePackage = "com.bank", sourceName = "Bank", sourceKey = body, eventFingerprint = body, title = "Bill due", body = body,
            postedAt = t0, category = "BANKING", priority = 70, confidence = 1f, isTrading = false
        ))
    }

    private fun signals(signal: PersonalLearning.Signal) = runBlocking { db.learningDao().counts(0).filter { it.signal == signal.name }.sumOf { it.count } }

    @After
    fun tearDown() = db.close()

    @Test
    fun undoneSnoozeWakesTheThreadAndLeavesNoSnoozeSignal() = runBlocking {
        val ids = listOf(insert("Card bill due"))
        now = t0 + 1_000
        repository.snoozeThread(ids, t0 + 3_600_000)
        assertEquals(t0 + 3_600_000, dao.getById(ids[0])?.snoozedUntil)

        repository.unsnoozeThread(ids, retractSince = t0 + 1_000)

        assertNull(dao.getById(ids[0])?.snoozedUntil)
        assertEquals(0, signals(PersonalLearning.Signal.SNOOZED))
    }

    @Test
    fun undoKeepsAnEarlierSnoozeSignal() = runBlocking {
        val ids = listOf(insert("Card bill due"))
        repository.snoozeThread(ids, t0 + 60_000)
        val earlier = signals(PersonalLearning.Signal.SNOOZED)
        assertTrue(earlier > 0)
        repository.unsnoozeThread(ids)

        now = t0 + 120_000
        repository.snoozeThread(ids, t0 + 3_600_000)
        repository.unsnoozeThread(ids, retractSince = t0 + 120_000)

        assertEquals(earlier, signals(PersonalLearning.Signal.SNOOZED))
    }

    @Test
    fun undoneDoneReopensTheThreadAndRetractsItsResolvedSignal() = runBlocking {
        val ids = listOf(insert("Card bill due"), insert("Card bill due tomorrow"))
        now = t0 + 1_000
        repository.resolveThread(ids, nowMillis = now)
        assertEquals(listOf("RESOLVED", "RESOLVED"), ids.map { dao.getById(it)?.lifecycleState })

        repository.reopenThread(ids, retractSince = t0 + 1_000)

        assertEquals(listOf("ACTIVE", "ACTIVE"), ids.map { dao.getById(it)?.lifecycleState })
        assertEquals(0, signals(PersonalLearning.Signal.RESOLVED))
    }
}
