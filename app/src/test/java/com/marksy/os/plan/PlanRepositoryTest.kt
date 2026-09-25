package com.marksy.os.plan

import androidx.room.Room
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.PlanItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanRepositoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 9, 25, 16, 0, 0, 0, zone).toInstant().toEpochMilli()
    private lateinit var db: MarksyDatabase
    private lateinit var repo: PlanRepository
    private val scheduled = mutableListOf<Long>()
    private val cancelled = mutableListOf<Long>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        val alarms = object : PlanAlarms {
            override fun schedule(item: PlanItemEntity) { scheduled += item.id }
            override fun cancel(itemId: Long) { cancelled += itemId }
        }
        repo = PlanRepository(db.planItemDao(), alarms, clock = { now }, zone = zone)
    }

    @After fun tearDown() = db.close()

    private fun event(id: Long, title: String, body: String, category: String = "REMINDERS") = NotificationEventEntity(
        id = id, sourcePackage = "com.truecaller", sourceName = "Truecaller", sourceKey = "k$id", eventFingerprint = "f$id",
        title = title, body = body, postedAt = now, category = category, priority = 88, confidence = .9f, isTrading = false,
        deliveryState = "NOT_APPLICABLE"
    )

    @Test fun dueSmsCreatesOneScheduledItemEvenWhenRepeated() = runBlocking {
        val first = repo.captureFromEvent(event(1, "₹14,917", "•  ICICI Bank  •  Bill due on 6th Oct SMS from ICICI Bank"))!!
        repo.captureFromEvent(event(2, "₹14,917", "•  ICICI Bank  •  Bill due on 6th Oct SMS from ICICI Bank"))
        val items = db.planItemDao().all()
        assertEquals(1, items.size)
        assertEquals(PlanKind.CARD_DUE.name, first.kind)
        assertEquals("ICICI Bank card bill", first.title)
        assertEquals(1_491_700L, first.amountMinor)
        assertEquals(PlanOrigin.SMS.name, first.origin)
        assertTrue(first.id in scheduled)
    }

    @Test fun nonReminderOrUndatedEventsCreateNothing() = runBlocking {
        assertNull(repo.captureFromEvent(event(1, "₹14,917", "Bill due on 6th Oct", category = "PROMOTIONS")))
        assertNull(repo.captureFromEvent(event(2, "Birthdays", "It's Aisha's birthday today")))
        assertEquals(0, db.planItemDao().all().size)
    }

    @Test fun doneOnARepeatingManualItemRollsForwardButSmsItemsDoNot() = runBlocking {
        val due = PlanRules.atAlertHour(java.time.LocalDate.of(2026, 10, 6), zone)
        val rent = repo.add(PlanKind.BILL, "Rent", null, 2_500_000L, due, Recurrence.MONTHLY)
        repo.setStatus(rent, PlanStatus.DONE)
        val all = db.planItemDao().all()
        assertEquals(PlanStatus.DONE.name, all.single { it.id == rent }.status)
        assertEquals(PlanRules.next(due, Recurrence.MONTHLY, zone), all.single { it.id != rent }.dueAt)
        assertTrue(rent in cancelled)

        val sms = repo.captureFromEvent(event(9, "₹14,917", "•  ICICI Bank  •  Bill due on 6th Oct SMS from ICICI Bank"))!!
        repo.setStatus(sms.id, PlanStatus.DONE)
        assertEquals(3, db.planItemDao().all().size)
    }

    @Test fun remindMeMirrorsOneFollowUpPerEventAndClearingRemovesIt() = runBlocking {
        repo.mirrorFollowUp(42, "KISHAN ENTERPRISE", now + 3_600_000)
        repo.mirrorFollowUp(42, "KISHAN ENTERPRISE", now + 7_200_000)
        val item = db.planItemDao().all().single()
        assertEquals(PlanKind.FOLLOW_UP.name, item.kind)
        assertEquals(now + 7_200_000, item.dueAt)
        assertEquals(42L, item.sourceEventId)
        repo.mirrorFollowUp(42, "KISHAN ENTERPRISE", null)
        assertEquals(0, db.planItemDao().all().size)
    }

    // Items captured by an older parser are re-derived: invalid ones go, stale ones are rebuilt.
    @Test fun revalidateDropsItemsThatNoLongerParseAndRebuildsStaleOnes() = runBlocking {
        val email = event(3, "Cultural Club", "The challenge is due on 24th September 2026.").copy(sourcePackage = "com.microsoft.office.outlook", sourceName = "Outlook")
        val cred = event(5, "your bill is due on Oct 05, 2026", "pay your bill of ₹14,364.00 now").copy(sourcePackage = "com.dreamplug.androidapp", sourceName = "CRED")
        fun stale(e: NotificationEventEntity, title: String) = PlanItemEntity(
            kind = "BILL", title = title, counterparty = title, dueAt = now + 86_400_000, recurrence = "NONE", status = "TODO",
            origin = "SMS", sourceEventId = e.id, dedupeKey = "sms|BILL|${title.lowercase()}|x", createdAt = 0, updatedAt = 0
        )
        val dao = db.planItemDao()
        val old = listOf(dao.insert(stale(email, "Cultural Club bill")), dao.insert(stale(cred, "your bill is due on Oct 05, 2026 bill")))
        repo.revalidate(mapOf(email.id to email, cred.id to cred)::get)
        val items = dao.all()
        assertEquals(listOf("CRED bill"), items.map { it.title })
        assertTrue(old.all { it in cancelled })
    }

    @Test fun contactBirthdayIsYearlyAndUpdatedInPlace() = runBlocking {
        repo.upsertBirthday("lookup-1", "Aisha", 3, 14)
        repo.upsertBirthday("lookup-1", "Aisha K", 3, 14)
        val item = db.planItemDao().all().single()
        assertEquals("Aisha K's birthday", item.title)
        assertEquals(Recurrence.YEARLY.name, item.recurrence)
        assertEquals(PlanRules.nextBirthday(3, 14, now, zone), item.dueAt)
    }
}
