package com.marksy.os.intelligence

import androidx.room.Room
import com.marksy.os.data.LearningRepository
import com.marksy.os.data.NotificationRepository
import com.marksy.os.data.local.LearningOverrideEntity
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.SignalCount
import com.marksy.os.intelligence.PersonalLearning.Preference
import com.marksy.os.intelligence.PersonalLearning.Signal
import com.marksy.os.intelligence.PersonalLearning.SubjectType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonalLearningTest {
    private lateinit var db: MarksyDatabase
    private var enabled = true
    private val now = 1_790_000_000_000L
    private lateinit var learning: LearningRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        learning = LearningRepository(db.learningDao(), db.notificationEventDao(), { enabled }) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun counts(key: String, vararg pairs: Pair<Signal, Int>) =
        pairs.map { (s, n) -> SignalCount(SubjectType.APP.name, key, s.name, n, now, "App") }

    private fun event(id: Long = 0, pkg: String = "com.chat", title: String = "Rahul", category: String = "MESSAGES", postedAt: Long = now) =
        NotificationEventEntity(id = id, sourcePackage = pkg, sourceName = "Chat", sourceKey = "k$id$postedAt$title", eventFingerprint = "f$id$postedAt$title",
            title = title, body = "b", postedAt = postedAt, category = category, priority = 40, confidence = .8f, isTrading = false)

    @Test
    fun noAdjustmentBelowMinimumSamplesThenBoundedByRateAndConfidence() {
        val few = PersonalLearning.buildProfile(counts("com.a", Signal.OPENED to 4), emptyList()).of(SubjectType.APP, "com.a")!!
        assertEquals(0, few.adjustment)
        assertTrue(few.reason.startsWith("Still learning"))

        val engaged = PersonalLearning.buildProfile(counts("com.a", Signal.OPENED to 20), emptyList()).of(SubjectType.APP, "com.a")!!
        assertEquals(PersonalLearning.MAX_LEARNED_ADJUSTMENT, engaged.adjustment)

        val ignored = PersonalLearning.buildProfile(counts("com.a", Signal.IGNORED to 10), emptyList()).of(SubjectType.APP, "com.a")!!
        assertEquals(-7, ignored.adjustment) // -15 scaled by 10/20 confidence, rounded half up
    }

    @Test
    fun explicitCorrectionOverridesLearningAndMostSpecificWins() {
        val e = event()
        val sender = PersonalLearning.senderKey(e)!!
        val profile = PersonalLearning.buildProfile(
            counts("com.chat", Signal.OPENED to 20),
            listOf(
                LearningOverrideEntity(SubjectType.APP.name, "com.chat", Preference.LESS_IMPORTANT.name, "Chat", now),
                LearningOverrideEntity(SubjectType.SENDER.name, sender, Preference.ALWAYS_IMPORTANT.name, "Rahul", now)
            )
        )
        val adj = PersonalLearning.adjustmentFor(e, profile)
        assertEquals(Preference.ALWAYS_IMPORTANT.adjustment, adj.delta)
        assertEquals(listOf("You marked this important (Rahul)"), adj.reasons)

        // With learning disabled only the corrections remain.
        assertEquals(2, profile.correctionsOnly().subjects.size)
    }

    @Test
    fun recordingIsIdempotentAndDisabledRecordsNothing() = runBlocking {
        val id = db.notificationEventDao().insert(event())
        val e = db.notificationEventDao().getById(id)!!
        assertEquals(3, learning.record(listOf(e), Signal.OPENED)) // app, category, sender
        assertEquals(0, learning.record(listOf(e), Signal.OPENED))
        enabled = false
        assertEquals(0, learning.record(listOf(e), Signal.RESOLVED))
        assertEquals(0, learning.sweepIgnored(now + PersonalLearning.IGNORED_AFTER_MS * 2))
    }

    @Test
    fun repositoryActionsProduceObservableSignalsAndResetKeepsCorrections() = runBlocking {
        val dao = db.notificationEventDao()
        val repo = NotificationRepository(dao, learning)
        val opened = dao.insert(event(title = "Rahul"))
        val dismissed = dao.insert(event(title = "Promo Bot", pkg = "com.shop", category = "PROMOTIONS"))
        val stale = dao.insert(event(title = "Old", pkg = "com.old", category = "OTHER", postedAt = now - PersonalLearning.IGNORED_AFTER_MS - 1))

        repo.markThreadSeen(listOf(opened), now)
        repo.archiveThread(listOf(dismissed), now)
        assertEquals(2, learning.sweepIgnored(now)) // app + category of the stale NEW event
        repo.archiveThread(listOf(opened), now) // already opened: not a negative signal

        val p = learning.profile()
        assertEquals(1, p.of(SubjectType.APP, "com.chat")!!.positive)
        assertEquals(0, p.of(SubjectType.APP, "com.chat")!!.negative)
        assertEquals(1, p.of(SubjectType.APP, "com.shop")!!.negative)
        assertEquals(1, p.of(SubjectType.APP, "com.old")!!.negative)
        assertEquals("Chat", p.of(SubjectType.APP, "com.chat")!!.subject.label)

        learning.setPreference(PersonalLearning.Subject(SubjectType.APP, "com.shop", "Shop"), Preference.LESS_IMPORTANT)
        learning.resetLearning()
        val after = learning.profile()
        assertEquals(1, after.subjects.size)
        assertEquals(Preference.LESS_IMPORTANT, after.of(SubjectType.APP, "com.shop")!!.override)
        assertTrue(stale > 0)
    }
}
