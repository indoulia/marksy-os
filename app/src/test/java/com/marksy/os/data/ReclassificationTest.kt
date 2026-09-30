package com.marksy.os.data

import androidx.room.Room
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReclassificationTest {
    private lateinit var db: MarksyDatabase
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("reclassify_test", 0)

    @Before fun setUp() {
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun event(pkg: String, title: String, body: String, category: String, trading: Boolean, delivery: String) = NotificationEventEntity(
        sourcePackage = pkg, sourceName = pkg, sourceKey = "$pkg:$title", eventFingerprint = "$pkg:$title",
        title = title, body = body, postedAt = 1_790_000_000_000L, category = category, priority = 10, confidence = .5f,
        isTrading = trading, deliveryState = delivery
    )

    // Regression: broker calls captured before the classifier fix stayed in OTHER forever; since 4b they are MARKET, and never newly sent.
    @Test fun storedBrokerCallMovesToMarketOnceAndIsNotNewlySent() = runBlocking {
        val dao = db.notificationEventDao()
        val call = dao.insert(event("com.fivepaisa.trade", "Short term Call", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26", "OTHER", false, "NOT_APPLICABLE"))
        val delivered = dao.insert(event("com.zerodha.kite3", "Order executed", "BUY 10 INFY", "TRADING", true, "DELIVERED"))
        val repo = NotificationRepository(dao)

        repo.reclassifyIfClassifierChanged(prefs)

        val updated = dao.getById(call)!!
        assertEquals("MARKET", updated.category)
        assertFalse(updated.isTrading)
        assertEquals("NOT_APPLICABLE", updated.deliveryState)
        assertEquals("DELIVERED", dao.getById(delivered)!!.deliveryState)

        // Second launch: already at this classifier version, so nothing is re-run.
        dao.updateClassification(call, "OTHER", 10, .5f, isTrading = false, queue = false)
        repo.reclassifyIfClassifierChanged(prefs)
        assertEquals("OTHER", dao.getById(call)!!.category)
    }

    // 4b review M2: a reclassified row that already left the phone is never queued again, and only a candidate is queued at all.
    @Test fun reclassifyingNeverRequeuesASentRowOrAnExecution() = runBlocking {
        val dao = db.notificationEventDao()
        val sent = dao.insert(event("com.upstox.pro", "Research call", "BUY RENUKA CMP 23 SL 22 TGT 26", "MARKET", false, "DELIVERED"))
        val inFlight = dao.insert(event("com.upstox.pro", "Research", "BUY IDEA CMP 9.5 SL 8.9 TGT 11", "MARKET", false, "IN_FLIGHT"))
        val local = dao.insert(event("com.upstox.pro", "Idea", "BUY INFY CMP 1500 SL 1450 TGT 1600", "MARKET", false, "NOT_APPLICABLE"))

        listOf(sent, inFlight, local).forEach { dao.updateClassification(it, "TRADING", 100, .96f, isTrading = true, queue = true) }

        assertEquals("DELIVERED", dao.getById(sent)!!.deliveryState)
        assertEquals("IN_FLIGHT", dao.getById(inFlight)!!.deliveryState)
        assertEquals("PENDING", dao.getById(local)!!.deliveryState)

        val execution = dao.insert(event("com.zerodha.kite3", "Order executed", "BUY 10 INFY at 1500", "OTHER", false, "NOT_APPLICABLE"))
        NotificationRepository(dao).reclassifyIfClassifierChanged(prefs)
        assertEquals("TRADING", dao.getById(execution)!!.category)
        assertEquals("NOT_APPLICABLE", dao.getById(execution)!!.deliveryState)
    }
}
