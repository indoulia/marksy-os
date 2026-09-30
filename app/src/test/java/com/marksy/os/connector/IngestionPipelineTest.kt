package com.marksy.os.connector

import androidx.room.Room
import com.marksy.os.data.Metric
import com.marksy.os.data.MetricsRecorder
import com.marksy.os.data.RuleRunner
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.intelligence.EventIntelligencePipeline
import com.marksy.os.intelligence.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IngestionPipelineTest {
    private lateinit var db: MarksyDatabase
    private val t0 = 1_790_000_000_000L
    private var trading = 0
    private var rules = listOf<RuleEngine.Rule>()
    private lateinit var pipeline: IngestionPipeline
    private lateinit var metrics: MetricsRecorder

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        metrics = MetricsRecorder(db.metricsDao(), { t0 + 500 }, { ZoneOffset.UTC })
        pipeline = IngestionPipeline(
            db.notificationEventDao(), db.connectorDao(), metrics, { rules },
            RuleRunner(db.notificationEventDao(), db.ruleExecutionDao(), clock = { t0 }),
            EventIntelligencePipeline(db.notificationEventDao(), ZoneOffset.UTC),
            onTradingCaptured = { trading++ }, clock = { t0 + 500 }
        )
    }

    @After
    fun tearDown() = db.close()

    private fun raw(pkg: String, key: String, title: String, body: String, connector: String = ConnectorRegistry.NOTIFICATIONS) =
        RawCapture(connector, pkg, pkg.substringAfterLast('.'), key, title, body, t0)

    private fun counters() = runBlocking { db.metricsDao().range("2000-01-01", "2100-01-01") }
    private fun count(metric: String, scope: String = "all") = counters().filter { it.metric == metric && it.scope == scope }.sumOf { it.value }

    @Test
    fun storesClassifiesProcessesAndCountsPerConnectorAndSource() = runBlocking {
        val r = pipeline.ingest(raw("com.snapwork.hdfc", "k1", "Debited", "Rs 500 debited. UPI Ref 426712345678"))
        val id = (r as IngestionPipeline.Result.Stored).eventId
        val e = db.notificationEventDao().getById(id)!!
        assertEquals("BANKING", e.category)
        assertEquals(1, e.intelligenceVersion)
        assertEquals(1L, count(Metric.CAPTURED))
        assertEquals(1L, count(Metric.CAPTURED, "connector:${ConnectorRegistry.NOTIFICATIONS}"))
        assertEquals(1L, count(Metric.CAPTURED, "source:com.snapwork.hdfc"))
        assertEquals(1L, count(Metric.CAPTURED, "category:BANKING"))
        assertEquals(500L, count(Metric.DELIVERY_DELAY_MS_SUM))
    }

    @Test
    fun ingestionDedupsBySourceKeyAndFingerprintAndCountsCrossSourceDuplicates() = runBlocking {
        pipeline.ingest(raw("com.snapwork.hdfc", "k1", "Debited", "Rs 500 debited. UPI Ref 426712345678"))
        assertEquals(IngestionPipeline.Result.Duplicate, pipeline.ingest(raw("com.snapwork.hdfc", "k1", "Debited", "Rs 500 debited. UPI Ref 426712345678")))
        assertEquals(IngestionPipeline.Result.Duplicate, pipeline.ingest(raw("com.snapwork.hdfc", "k2", "Debited", "Rs 500 debited. UPI Ref 426712345678")))
        pipeline.ingest(raw("com.phonepe.app", "p1", "Paid", "Paid Rs 500 to Rahul. UPI Ref 426712345678"))
        assertEquals(2L, count(Metric.DUPLICATE))
        assertEquals(1L, count(Metric.CROSS_SOURCE_DUPLICATE))
        assertEquals(2, db.notificationEventDao().findInRange(0, Long.MAX_VALUE, 10).size)
    }

    @Test
    fun rulesApplyToEveryConnectorAndTradingTriggersDelivery() = runBlocking {
        rules = listOf(RuleEngine.Rule("wa", "Archive family group", containsText = "family", action = RuleEngine.Action.ARCHIVE))
        val r = pipeline.ingest(raw("com.whatsapp", "wa:1", "Family", "dinner at 8", ConnectorRegistry.WHATSAPP_ACCESSIBILITY)) as IngestionPipeline.Result.Stored
        assertTrue(db.notificationEventDao().getById(r.eventId)!!.archived)
        assertEquals(1, db.ruleExecutionDao().count("wa"))

        pipeline.ingest(raw("com.zerodha.kite3", "z1", "Order executed", "BUY INFY at 1500"))
        assertEquals(1, trading)
    }

    @Test
    fun whatsappAdapterDropsTheChangingMessageCountSoThreadsStayStable() = runBlocking {
        val a = pipeline.ingest(raw("com.whatsapp", "w1", "Family (3 messages): Rahul", "hi")) as IngestionPipeline.Result.Stored
        val b = pipeline.ingest(raw("com.whatsapp", "w2", "Family (4 messages): Rahul", "again")) as IngestionPipeline.Result.Stored
        val ea = db.notificationEventDao().getById(a.eventId)!!
        val eb = db.notificationEventDao().getById(b.eventId)!!
        assertEquals("Family: Rahul", ea.title)
        assertEquals(ea.threadKey, eb.threadKey)
    }

    @Test
    fun failuresAreIsolatedRecordedAndTraceable() = runBlocking {
        val broken = IngestionPipeline(
            db.notificationEventDao(), db.connectorDao(), metrics, { error("rule store corrupt") }, null, null, clock = { t0 }
        )
        val r = broken.ingest(raw("com.a", "x1", "t", "b"))
        assertTrue(r is IngestionPipeline.Result.Failed)
        assertEquals(1L, count(Metric.CAPTURE_FAILED))
        val failure = db.connectorDao().failuresSince(0, 10).single()
        assertEquals(ConnectorRegistry.NOTIFICATIONS, failure.connectorId)
        assertEquals("IllegalStateException", failure.detail)
        assertEquals(IngestionPipeline.Result.Empty, pipeline.ingest(raw("com.a", "x2", " ", "")))
    }

    // Regression kept from the trade-call rule: a tip posted after "Good morning" in a sent chat notification must go.
    @Test
    fun aNewMessageInADeliveredChatIsItsOwnCaptureAndALocalChatStillFolds() = runBlocking {
        val dao = db.notificationEventDao()
        val group = raw("org.telegram.messenger", "chat", "Tips Group", "Good morning all").copy(groupConversation = true)
        val sent = (pipeline.ingest(group) as IngestionPipeline.Result.Stored).eventId
        assertEquals("PENDING", dao.getById(sent)!!.deliveryState)
        dao.claimPendingTrading(sent, 1, t0)
        dao.updateInFlightDeliveryState(sent, "DELIVERED", 1, t0)

        val next = pipeline.ingest(group.copy(body = "Good morning all\nBUY RENUKA CMP 23.62 SL 22.25 TGT 26")) as IngestionPipeline.Result.Stored
        val added = dao.getById(next.eventId)!!
        assertEquals("BUY RENUKA CMP 23.62 SL 22.25 TGT 26", added.body)
        assertEquals("PENDING", added.deliveryState)

        val oneToOne = raw("org.telegram.messenger", "dm", "Rahul", "hi").copy(groupConversation = false)
        val local = (pipeline.ingest(oneToOne) as IngestionPipeline.Result.Stored).eventId
        assertTrue(pipeline.ingest(oneToOne.copy(body = "hi\nBUY IDEA CMP 9.5 SL 8.9 TGT 11")) is IngestionPipeline.Result.Updated)
        assertEquals("NOT_APPLICABLE", dao.getById(local)!!.deliveryState)
        assertEquals(2, trading)

        // 4b ruling: a group row kept local for want of a market signal is decided again once a call joins it, not on chatter.
        val quiet = raw("org.telegram.messenger", "quiet", "Swing Group", "Morning team").copy(groupConversation = true)
        val kept = (pipeline.ingest(quiet) as IngestionPipeline.Result.Stored).eventId
        dao.claimPendingTrading(kept, 1, t0)
        dao.updateInFlightDeliveryState(kept, "NOT_APPLICABLE", 1, t0)
        assertTrue(pipeline.ingest(quiet.copy(body = "Morning team\nHow was the weekend?")) is IngestionPipeline.Result.Updated)
        assertEquals("NOT_APPLICABLE", dao.getById(kept)!!.deliveryState)
        assertTrue(pipeline.ingest(quiet.copy(body = "Morning team\nHow was the weekend?\nBUY IDEA CMP 9.5 SL 8.9 TGT 11")) is IngestionPipeline.Result.Updated)
        assertEquals("PENDING", dao.getById(kept)!!.deliveryState)
        assertEquals(4, trading)
    }

    // Regression: a second call reusing the first call's notification slot was folded into it and never sent.
    @Test
    fun newCallInAnAlreadyTradingNotificationIsStoredAndDeliveredSeparately() = runBlocking {
        pipeline.ingest(raw("com.fivepaisa.trade", "n1", "Short term Call", "BUY RENUKA CMP 23.62 SL 22.25 TGT 26"))
        val r = pipeline.ingest(raw("com.fivepaisa.trade", "n1", "Short term Call", "BUY IDEA CMP 9.5 SL 8.9 TGT 11"))
        val e = db.notificationEventDao().getById((r as IngestionPipeline.Result.Stored).eventId)!!
        assertEquals("BUY IDEA CMP 9.5 SL 8.9 TGT 11", e.body)
        assertEquals("PENDING", e.deliveryState)
        assertEquals(2, trading)
    }

    // Tip capture sends a chat only when it knows it is a group, so a later call split into its own row keeps the flag.
    @Test
    fun theGroupConversationFlagIsStoredOnTheRowAndOnASplitCall() = runBlocking {
        val first = pipeline.ingest(raw("com.fivepaisa.trade", "n1", "Short term Call", "BUY RENUKA CMP 23.62 SL 22.25 TGT 26").copy(groupConversation = true))
        val split = pipeline.ingest(raw("com.fivepaisa.trade", "n1", "Short term Call", "BUY IDEA CMP 9.5 SL 8.9 TGT 11").copy(groupConversation = true))
        val dao = db.notificationEventDao()

        assertEquals(true, dao.getById((first as IngestionPipeline.Result.Stored).eventId)!!.chatGroup)
        assertEquals(true, dao.getById((split as IngestionPipeline.Result.Stored).eventId)!!.chatGroup)
    }

    // Regression: two quick posts of one notification raced lookup-then-insert and the second was dropped.
    @Test
    fun concurrentPostsOfOneNotificationAreBothKept() = runBlocking {
        val bothInside = java.util.concurrent.CyclicBarrier(2)
        val racing = IngestionPipeline(db.notificationEventDao(), db.connectorDao(), metrics, {
            runCatching { bothInside.await(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
            emptyList()
        }, null, null)

        listOf("Good morning all", "Good morning all\nSee you at noon").map { body ->
            async(Dispatchers.IO) { racing.ingest(raw("org.telegram.messenger", "chat", "Group", body)) }
        }.awaitAll()

        val body = db.notificationEventDao().findBySourceKey("org.telegram.messenger", "chat")!!.body
        assertTrue(body, "Good morning all" in body && "See you at noon" in body)
    }

    @Test
    fun captureArrivingOverAMinuteLateIsCounted() = runBlocking {
        pipeline.ingest(RawCapture(ConnectorRegistry.NOTIFICATIONS, "com.snapwork.hdfc", "hdfc", "k1", "Debited", "Rs 500 debited", t0 - 120_000))
        pipeline.ingest(raw("com.snapwork.hdfc", "k2", "Credited", "Rs 900 credited"))
        assertEquals(1L, count(Metric.LATE_CAPTURE))
    }

    // Regression: Dainik Bhaskar and SMS senders reuse one slot; new headlines were buried under a days-old row.
    @Test
    fun repostWithEntirelyNewContentIsItsOwnNotification() = runBlocking {
        val first = pipeline.ingest(raw("com.ak.ta.dainikbhaskar.activity", "slot", "Dainik Bhaskar", "Headline one")) as IngestionPipeline.Result.Stored
        val second = pipeline.ingest(raw("com.ak.ta.dainikbhaskar.activity", "slot", "Dainik Bhaskar", "Headline two"))
        assertTrue(second is IngestionPipeline.Result.Stored && second.eventId != first.eventId)
        assertEquals("Headline one", db.notificationEventDao().getById(first.eventId)!!.body)
    }
}
