package com.marksy.os.data

import androidx.room.Room
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
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

    // Regression: broker calls captured before the classifier fix stayed in OTHER forever.
    @Test fun storedBrokerCallBecomesDeliverableTradingOnce() = runBlocking {
        val dao = db.notificationEventDao()
        val call = dao.insert(event("com.fivepaisa.trade", "Short term Call", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26", "OTHER", false, "NOT_APPLICABLE"))
        val delivered = dao.insert(event("com.zerodha.kite3", "Order executed", "BUY 10 INFY", "TRADING", true, "DELIVERED"))
        val repo = NotificationRepository(dao)

        repo.reclassifyIfClassifierChanged(prefs)

        val updated = dao.getById(call)!!
        assertEquals("TRADING", updated.category)
        assertTrue(updated.isTrading)
        assertEquals("PENDING", updated.deliveryState)
        assertEquals("DELIVERED", dao.getById(delivered)!!.deliveryState)

        // Second launch: already at this classifier version, so nothing is re-run.
        dao.updateText(call, "Short term Call", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26")
        repo.reclassifyIfClassifierChanged(prefs)
        assertEquals("PENDING", dao.getById(call)!!.deliveryState)
    }
}
