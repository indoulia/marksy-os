package com.marksy.os.intelligence

import androidx.room.Room
import com.marksy.os.data.ActionPlatform
import com.marksy.os.data.ActionRepository
import com.marksy.os.data.ActionState
import com.marksy.os.data.LearningRepository
import com.marksy.os.data.NotificationRepository
import com.marksy.os.data.local.EventActionEntity
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.ActionEngine.Type
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionEngineTest {
    private class FakePlatform : ActionPlatform {
        val installed = mutableSetOf("com.shop", "com.power")
        var notificationsAllowed = true
        var launchResult = true
        val scheduled = mutableListOf<Pair<Long, Long>>()
        override fun canLaunch(sourcePackage: String) = sourcePackage in installed
        override fun canPostNotifications() = notificationsAllowed
        override fun launch(sourcePackage: String) = launchResult && sourcePackage in installed
        override fun scheduleReminder(actionId: Long, atMillis: Long) { scheduled += actionId to atMillis }
    }

    private val now = 1_790_000_000_000L
    private lateinit var db: MarksyDatabase
    private lateinit var platform: FakePlatform
    private lateinit var actions: ActionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        platform = FakePlatform()
        val learning = LearningRepository(db.learningDao(), db.notificationEventDao(), { true }) { now }
        actions = ActionRepository(db.eventActionDao(), db.notificationEventDao(), NotificationRepository(db.notificationEventDao(), learning), learning, platform) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun insert(pkg: String, category: String, body: String, trading: Boolean = false): Long = runBlocking {
        val e = NotificationEventEntity(sourcePackage = pkg, sourceName = pkg, sourceKey = "k$pkg$body", eventFingerprint = "f$pkg$body",
            title = "t", body = body, postedAt = now, category = category, priority = 50, confidence = .9f, isTrading = trading)
        val id = db.notificationEventDao().insert(e)
        EventIntelligencePipeline(db.notificationEventDao()).process(id)
        id
    }

    private fun event(id: Long) = runBlocking { db.notificationEventDao().getById(id)!! }
    private fun audit(id: Long): EventActionEntity = runBlocking { db.eventActionDao().get(id)!! }

    @Test
    fun discoveryOffersOnlyGenuinelySupportedActions() {
        val delivery = event(insert("com.shop", "DELIVERY", "Order ID 403-1234567-7654321 shipped, arriving tomorrow"))
        val types = actions.discover(delivery).map { it.type }
        assertTrue(Type.TRACK in types && Type.OPEN_SOURCE in types && Type.MARK_EXPECTED in types)
        assertFalse(Type.PAYMENT in types)

        val notInstalled = event(insert("com.missing", "BILLS", "Electricity bill due"))
        val t2 = actions.discover(notInstalled).map { it.type }
        assertFalse(Type.OPEN_SOURCE in t2 || Type.PAYMENT in t2)

        platform.notificationsAllowed = false
        val remind = actions.discover(notInstalled).single { it.type == Type.REMIND }
        assertFalse(remind.enabled)
    }

    @Test
    fun launchFailureIsAuditedAndUninstallBetweenDiscoveryAndExecutionFails() = runBlocking {
        val id = insert("com.power", "BILLS", "Electricity bill due")
        platform.launchResult = false
        val failed = actions.execute(id, Type.PAYMENT)
        assertEquals(ActionState.FAILED, failed.state)
        assertEquals("FAILED", audit(failed.actionId).state)
        assertEquals(1, audit(failed.actionId).attempts)

        platform.installed.remove("com.power")
        val gone = actions.execute(id, Type.OPEN_SOURCE)
        assertEquals(ActionState.FAILED, gone.state)
        assertEquals("Not available for this event", audit(gone.actionId).error)
    }

    @Test
    fun reminderLifecycleAndRecovery() = runBlocking {
        val id = insert("com.power", "BILLS", "Electricity bill due")
        val r = actions.execute(id, Type.REMIND, scheduledFor = now + 60_000)
        assertEquals(ActionState.PENDING, r.state)
        assertEquals(listOf(r.actionId to now + 60_000), platform.scheduled)

        platform.scheduled.clear()
        assertEquals(1, actions.recover())
        assertEquals(r.actionId, platform.scheduled.single().first)

        assertEquals(ActionState.SUCCEEDED, actions.completeReminder(r.actionId) { _, e -> e != null })
        // Firing twice (duplicate work) is a no-op.
        assertEquals(ActionState.SUCCEEDED, actions.completeReminder(r.actionId) { _, _ -> error("must not post twice") })

        assertEquals(ActionState.FAILED, actions.execute(id, Type.REMIND, scheduledFor = now - 1).state)
    }

    @Test
    fun resolveReportAndIgnoreChangeRealState() = runBlocking {
        val bill = insert("com.power", "BILLS", "Electricity bill due")
        assertEquals(ActionState.SUCCEEDED, actions.execute(bill, Type.MARK_RESOLVED).state)
        assertEquals("RESOLVED", event(bill).lifecycleState)
        assertEquals(ActionState.FAILED, actions.execute(bill, Type.MARK_RESOLVED).state)

        val promo = insert("com.shop", "PROMOTIONS", "Big sale")
        val report = actions.execute(promo, Type.REPORT, detail = "DELIVERY")
        assertEquals("DELIVERY", event(promo).category)
        assertEquals("PROMOTIONS->DELIVERY", audit(report.actionId).detail)

        assertEquals(0, event(promo).intelligenceVersion) // re-derived for the corrected category

        val trade = insert("com.broker", "TRADING", "Order executed", trading = true)
        actions.execute(trade, Type.REPORT, detail = "OTHER")
        assertEquals("TRADING", event(trade).category)

        assertEquals(ActionState.SUCCEEDED, actions.execute(promo, Type.IGNORE).state)
        assertTrue(event(promo).archived)
        assertTrue(db.learningDao().counts(0).any { it.signal == "IGNORED" && it.subjectKey == "com.shop" })
    }
}
