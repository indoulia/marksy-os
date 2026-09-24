package com.marksy.os.intelligence

import androidx.room.Room
import com.marksy.os.data.MemoryRepository
import com.marksy.os.data.MemorySettings
import com.marksy.os.data.local.LearningOverrideEntity
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.PersonalMemory.Kind
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
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonalMemoryTest {
    private class MemSettings : MemorySettings {
        override var enabled = true
        override var watermark = 0L
        val kinds = Kind.entries.associateWith { true }.toMutableMap()
        override fun isKindEnabled(kind: Kind) = kinds.getValue(kind)
        override fun setKindEnabled(kind: Kind, enabled: Boolean) { kinds[kind] = enabled }
    }

    private val day = 24 * 3_600_000L
    private val t0 = 1_790_000_000_000L
    private var now = t0
    private lateinit var db: MarksyDatabase
    private lateinit var settings: MemSettings
    private lateinit var repo: MemoryRepository
    private lateinit var pipeline: EventIntelligencePipeline

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        settings = MemSettings()
        repo = MemoryRepository(db.memoryDao(), db.notificationEventDao(), db.learningDao(), settings, { now }, { ZoneOffset.UTC })
        pipeline = EventIntelligencePipeline(db.notificationEventDao(), ZoneOffset.UTC)
    }

    @After
    fun tearDown() = db.close()

    private var seq = 0
    private fun capture(category: String, title: String, body: String, at: Long, pkg: String = "com.bank") = runBlocking {
        seq++
        val id = db.notificationEventDao().insert(NotificationEventEntity(sourcePackage = pkg, sourceName = pkg.substringAfterLast('.'), sourceKey = "k$seq",
            eventFingerprint = "f$seq", title = title, body = body, postedAt = at, category = category, priority = 60, confidence = .9f, isTrading = false))
        pipeline.process(id)
        id
    }

    private fun entry(kind: Kind, key: String) = runBlocking { db.memoryDao().find(kind.name, key) }

    @Test
    fun monthlyPaymentBecomesRecurringWithProvenanceAcrossRetention() = runBlocking {
        val ids = (0..2).map { m -> capture("BANKING", "Debited", "Rs 499 debited at NETFLIX.", t0 + m * 30 * day) }
        now = t0 + 61 * day
        repo.ingest()
        val e = entry(Kind.RECURRING_PAYMENT, "netflix|INR")!!
        assertEquals(3, e.observations)
        assertTrue(e.confidence >= .7f)
        assertTrue(e.detailJson.contains("\"cadenceDays\":30"))
        assertEquals(ids, PersonalMemory.eventIds(e))

        // Events pruned by retention: memory survives and re-ingesting is idempotent.
        db.notificationEventDao().deleteAll()
        repo.ingest()
        assertEquals(3, entry(Kind.RECURRING_PAYMENT, "netflix|INR")!!.observations)
    }

    @Test
    fun forgetIsPermanentCorrectionIsProtectedAndKindsCanBeDisabled() = runBlocking {
        capture("MESSAGES", "Rahul", "hi", t0, "com.whatsapp")
        capture("MESSAGES", "Rahul", "again", t0 + day, "com.whatsapp")
        capture("BANKING", "Debited", "Rs 20 debited at CHAI POINT.", t0)
        repo.ingest()
        val rahul = entry(Kind.CONTACT, "rahul")!!
        val chai = entry(Kind.MERCHANT, "chai point")!!

        repo.forget(rahul.id)
        repo.correct(chai.id, "Chai Point (office)")
        capture("MESSAGES", "Rahul", "third", t0 + 2 * day, "com.whatsapp")
        capture("BANKING", "Debited", "Rs 25 debited at CHAI POINT.", t0 + 2 * day)
        repo.ingest()

        val forgotten = entry(Kind.CONTACT, "rahul")!!
        assertEquals(PersonalMemory.STATE_FORGOTTEN, forgotten.state)
        assertEquals("{}", forgotten.detailJson)
        val corrected = entry(Kind.MERCHANT, "chai point")!!
        assertEquals("Chai Point (office)", corrected.label)
        assertNull(corrected.expiresAt)
        assertEquals(2, corrected.observations)

        repo.setKindEnabled(Kind.ORGANIZATION, false)
        capture("BANKING", "Credited", "Rs 5 credited by HDFC Bank", t0 + 3 * day)
        repo.ingest()
        assertNull(entry(Kind.ORGANIZATION, "hdfc bank"))
    }

    @Test
    fun explicitSettingsBecomePreferencesAndMasterSwitchErasesLearned() = runBlocking {
        db.learningDao().upsertOverride(LearningOverrideEntity("APP", "com.shop", PersonalLearning.Preference.LESS_IMPORTANT.name, "Shop", t0))
        capture("MESSAGES", "Amit", "hey", t0, "com.whatsapp")
        repo.ingest()
        assertEquals("Shop: less important", entry(Kind.PREFERENCE, "APP|com.shop")!!.label)
        assertTrue(entry(Kind.CONTACT, "amit") != null)

        // Switching off stops learning but deletes nothing (review finding: silent irreversible loss).
        repo.setEnabled(false)
        capture("MESSAGES", "Amit", "again", t0 + day, "com.whatsapp")
        repo.ingest()
        assertEquals(1, entry(Kind.CONTACT, "amit")!!.observations)

        repo.eraseLearned()
        assertNull(entry(Kind.CONTACT, "amit"))
        assertTrue(entry(Kind.PREFERENCE, "APP|com.shop") != null)
    }

    @Test
    fun learnedEntriesExpireAndCadenceNeedsRegularGaps() = runBlocking {
        capture("MESSAGES", "Neha", "hi", t0, "com.whatsapp")
        repo.ingest()
        assertTrue(entry(Kind.CONTACT, "neha") != null)
        now = t0 + 61 * day
        repo.ingest()
        assertNull(entry(Kind.CONTACT, "neha"))

        assertEquals(7, PersonalMemory.cadence(listOf(0L, 7 * day, 14 * day)))
        assertNull(PersonalMemory.cadence(listOf(0L, 2 * day, 30 * day)))
        assertNull(PersonalMemory.cadence(listOf(0L)))
    }
}
