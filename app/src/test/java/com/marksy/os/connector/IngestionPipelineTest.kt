package com.marksy.os.connector

import androidx.room.Room
import com.marksy.os.data.Metric
import com.marksy.os.data.MetricsRecorder
import com.marksy.os.data.RuleRunner
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.intelligence.EventIntelligencePipeline
import com.marksy.os.intelligence.RuleEngine
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
            RuleRunner(db.notificationEventDao(), db.ruleExecutionDao(), { t0 }),
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
}
