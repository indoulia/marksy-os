package com.marksy.os.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationEventDaoTest {
    private lateinit var db: MarksyDatabase
    private lateinit var dao: NotificationEventDao
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day
    private var seq = 0

    private fun event(postedAt: Long, kept: Boolean = false, remindAt: Long? = null) = NotificationEventEntity(
        sourcePackage = "p", sourceName = "App", sourceKey = "k${seq++}", eventFingerprint = "f$seq",
        title = "t$seq", body = "b", postedAt = postedAt, category = "OTHER", priority = 1, confidence = 1f,
        isTrading = false, kept = kept, remindAt = remindAt
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MarksyDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.notificationEventDao()
    }

    @After
    fun tearDown() = db.close()

    // User asked for "permanent storage": kept and reminder-pending events must survive the 7-day expiry.
    @Test
    fun pruneSkipsKeptAndReminderEvents() = runBlocking {
        dao.insert(event(now - 30 * day))
        dao.insert(event(now - 30 * day, kept = true))
        dao.insert(event(now - 30 * day, remindAt = now + day))

        dao.pruneExpired(now)

        val left = dao.observeActive().first()
        assertEquals(2, left.size)
        assertTrue(left.any { it.kept })
        assertTrue(left.any { it.remindAt != null })
    }

    @Test
    fun readStateToggleAndNewContentMarksUnread() = runBlocking {
        val id = dao.insert(event(now))
        dao.setRead(id, true)
        assertTrue(dao.findById(id)!!.isRead)

        dao.updateContent(id, "new title", "new body", now + 1)

        assertFalse(dao.findById(id)!!.isRead)
    }

    @Test
    fun deleteAndRestoreKeepsTheSameRow() = runBlocking {
        val original = dao.findById(dao.insert(event(now)))!!

        dao.deleteById(original.id)
        assertNull(dao.findById(original.id))
        dao.insert(original)

        assertEquals(original, dao.findById(original.id))
    }

    // Launch crash 2026-10-03 ("Couldn't read row 513 from CursorWindow"): rows leaving a list between window fills of one read.
    @Test
    fun longListReadsStayWholeWhileRowsChange() = runBlocking {
        val body = "x".repeat(3000)
        repeat(1500) { dao.insert(event(now - it).copy(title = "<t", body = body)) }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = Thread {
            val sql = db.openHelper.writableDatabase
            var flip = 0
            while (!done.get()) {
                val f = flip++ % 2
                sql.execSQL("UPDATE notification_events SET archived = $f, isTrading = $f, category = CASE $f WHEN 1 THEN 'GONE' ELSE 'OTHER' END, title = CASE $f WHEN 1 THEN 't' ELSE '<t' END WHERE id % 300 = 0")
            }
        }.apply { start() }
        try {
            repeat(10) {
                assertTrue(dao.observeActive().first().size in 1495..1500)
                assertTrue(dao.findNonTrading().size in 1495..1500)
                assertTrue(dao.findByCategory("OTHER").size in 1495..1500)
                assertTrue(dao.findWithPossibleMarkup().size in 1495..1500)
            }
        } finally {
            done.set(true)
            writer.join()
        }
    }
}
