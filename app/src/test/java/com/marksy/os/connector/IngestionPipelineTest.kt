package com.marksy.os.connector

import androidx.room.Room
import com.marksy.os.data.Metric
import com.marksy.os.data.MetricsRecorder
import com.marksy.os.data.RuleRunner
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.CaptureContext
import com.marksy.os.gateway.CaptureDecision
import com.marksy.os.gateway.CaptureGate
import com.marksy.os.gateway.CapturedMessage
import com.marksy.os.gateway.MarksyGatewayClient
import com.marksy.os.gateway.MarksyInsight
import com.marksy.os.gateway.TradingDeliveryRun
import com.marksy.os.intelligence.EventIntelligencePipeline
import com.marksy.os.intelligence.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    // User rule 2026-10-02: a one-time code never reaches the database, on first post or on a re-post.
    @Test
    fun oneTimeCodesAreNeverStoredButAlertsMentioningOtpAre() = runBlocking {
        assertEquals(IngestionPipeline.Result.Empty, pipeline.ingest(raw("com.google.android.apps.messaging", "s1", "SBI", "482913 is your OTP for login. Do not share.")))
        val chat = pipeline.ingest(raw("com.google.android.apps.messaging", "s2", "Mom", "Reached home")) as IngestionPipeline.Result.Stored
        pipeline.ingest(raw("com.google.android.apps.messaging", "s2", "Mom", "Reached home\nMy OTP is 4821, enter it"))
        assertEquals("Reached home", db.notificationEventDao().getById(chat.eventId)!!.body)
        val alert = pipeline.ingest(raw("com.snapwork.hdfc", "k9", "HDFC Bank", "Rs 500 debited from a/c XX1234. Never share your OTP.")) as IngestionPipeline.Result.Stored
        assertEquals("BANKING", db.notificationEventDao().getById(alert.eventId)!!.category)
        assertEquals(2, db.notificationEventDao().countAll())
    }

    // Review C1: a code in a conversation re-post drops only that message, never the alert that follows it.
    @Test
    fun aCodeInAConversationDropsOnlyThatMessage() = runBlocking {
        val sms = "com.google.android.apps.messaging"
        assertEquals(IngestionPipeline.Result.Empty, pipeline.ingest(raw(sms, "c1", "HDFC Bank", "482913 is your OTP for txn of Rs 500. Do not share.")))
        val r = pipeline.ingest(raw(sms, "c1", "HDFC Bank", "482913 is your OTP for txn of Rs 500. Do not share.\nRs 500 debited from a/c XX1234 to AMAZON")) as IngestionPipeline.Result.Stored
        assertEquals("Rs 500 debited from a/c XX1234 to AMAZON", db.notificationEventDao().getById(r.eventId)!!.body)
    }

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

        // 4b review C1/I3: only a candidate wakes delivery, so a broker's research call does and its own execution does not.
        pipeline.ingest(raw("com.zerodha.kite3", "z1", "Research call", "BUY INFY CMP 1500 SL 1450 TGT 1600"))
        pipeline.ingest(raw("com.zerodha.kite3", "z2", "Order executed", "BUY INFY at 1500"))
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

    // Regression kept from the trade-call rule: a tip posted after "Good morning" in a chat notification must go, as its own capture.
    @Test
    fun aNewMessageInADeliveredChatIsItsOwnCaptureAndALocalChatStillFolds() = runBlocking {
        val dao = db.notificationEventDao()
        val group = raw("org.telegram.messenger", "chat", "Tips Group", "BUY RENUKA CMP 23.62 SL 22.25 TGT 26").copy(groupConversation = true)
        val sent = (pipeline.ingest(group) as IngestionPipeline.Result.Stored).eventId
        assertEquals("PENDING", dao.getById(sent)!!.deliveryState)
        dao.claimPendingTrading(sent, 1, t0)
        dao.updateInFlightDeliveryState(sent, "DELIVERED", 1, t0)

        val next = pipeline.ingest(group.copy(body = "BUY RENUKA CMP 23.62 SL 22.25 TGT 26\nBUY IDEA CMP 9.5 SL 8.9 TGT 11")) as IngestionPipeline.Result.Stored
        val added = dao.getById(next.eventId)!!
        assertEquals("BUY IDEA CMP 9.5 SL 8.9 TGT 11", added.body)
        assertEquals("PENDING", added.deliveryState)
        assertEquals(2, trading)

        // 4b review I3: group chatter never queues or wakes delivery; I2: a call it gains later splits off on its own.
        val quiet = raw("org.telegram.messenger", "quiet", "Swing Group", "Good morning all").copy(groupConversation = true)
        val local = (pipeline.ingest(quiet) as IngestionPipeline.Result.Stored).eventId
        assertEquals("NOT_APPLICABLE", dao.getById(local)!!.deliveryState)
        assertTrue(pipeline.ingest(quiet.copy(body = "Good morning all\nHow was the weekend?")) is IngestionPipeline.Result.Updated)
        assertEquals(2, trading)
        val tip = pipeline.ingest(quiet.copy(body = "Good morning all\nHow was the weekend?\nBUY TATASTEEL CMP 152 SL 147 TGT 162")) as IngestionPipeline.Result.Stored
        assertEquals("BUY TATASTEEL CMP 152 SL 147 TGT 162", dao.getById(tip.eventId)!!.body)
        assertEquals("PENDING", dao.getById(tip.eventId)!!.deliveryState)
        assertEquals("NOT_APPLICABLE", dao.getById(local)!!.deliveryState)
        assertEquals(3, trading)

        val oneToOne = raw("org.telegram.messenger", "dm", "Rahul", "hi").copy(groupConversation = false)
        val direct = (pipeline.ingest(oneToOne) as IngestionPipeline.Result.Stored).eventId
        assertTrue(pipeline.ingest(oneToOne.copy(body = "hi\nBUY IDEA CMP 9.5 SL 8.9 TGT 11")) is IngestionPipeline.Result.Updated)
        assertEquals("NOT_APPLICABLE", dao.getById(direct)!!.deliveryState)
        assertEquals(3, trading)
    }

    // Option-tip safety B: appended lines, a classifier re-queue and the accessibility path all reach the delivery gate, and no request.
    @Test
    fun optionCallsFromEveryIngestionPathNeverReachTheClient() = runBlocking {
        val dao = db.notificationEventDao()
        val option = "BUY NIFTY 24500 CE @ 180 TGT 220 SL 150"
        val group = raw("org.telegram.messenger", "g1", "Tips Group", "Good morning").copy(groupConversation = true)
        pipeline.ingest(group)
        pipeline.ingest(group.copy(body = "Good morning\n$option"))
        pipeline.ingest(group.copy(body = "Good morning\n$option\nSELL @ 200 TGT 150 SL 220"))
        val stock = raw("org.telegram.messenger", "g2", "Tips Group", "Good evening").copy(groupConversation = true)
        pipeline.ingest(stock)
        pipeline.ingest(stock.copy(body = "Good evening\nBUY IDEA CMP 9.5 SL 8.9 TGT 11"))
        // The accessibility service sets no group flag, so its rows never queue; a group row would still be stopped at delivery.
        val ungrouped = pipeline.ingest(raw("com.whatsapp", "wa1", "Tips Group", option, ConnectorRegistry.WHATSAPP_ACCESSIBILITY)) as IngestionPipeline.Result.Stored
        assertEquals("NOT_APPLICABLE", dao.getById(ungrouped.eventId)!!.deliveryState)
        val accessibility = listOf(
            raw("com.whatsapp", "wa2", "Tips Group", "SELL NIFTY 24500 PE below 120 TGT 90 SL 140", ConnectorRegistry.WHATSAPP_ACCESSIBILITY),
            raw("com.whatsapp", "wa3", "Tips Group", "BUY TCS (18 PE) @ 3500 TGT 3600 SL 3400", ConnectorRegistry.WHATSAPP_ACCESSIBILITY)
        ).map { (pipeline.ingest(it.copy(groupConversation = true)) as IngestionPipeline.Result.Stored).eventId }
        accessibility.forEach { assertEquals("PENDING", dao.getById(it)!!.deliveryState) }
        val requeued = dao.insert(
            NotificationEventEntity(
                sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceKey = "r1", eventFingerprint = "r1", title = "Idea", body = option,
                postedAt = t0, category = "MARKET", priority = 10, confidence = .5f, isTrading = false, deliveryState = "NOT_APPLICABLE"
            )
        )
        dao.updateClassification(requeued, "TRADING", 100, .96f, isTrading = true, queue = true)
        assertEquals("PENDING", dao.getById(requeued)!!.deliveryState)

        val sent = mutableListOf<String>()
        val client = object : MarksyGatewayClient {
            override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> {
                sent += message.text
                return Result.success(MarksyInsight(eventId, "ok"))
            }
            override suspend fun captureList() = Result.success(emptySet<String>())
        }
        val context = CaptureContext(capturePackages = setOf("com.fivepaisa.trade"), chatAllowList = setOf("tips group"), chatSenders = emptySet(), username = "user-1", deviceSalt = "salt")
        TradingDeliveryRun(dao, client, context).drain()

        assertEquals(listOf("BUY IDEA CMP 9.5 SL 8.9 TGT 11"), sent)
        assertEquals(0, dao.findPendingCapture(50).size)
        (accessibility + requeued).forEach { assertEquals("NOT_APPLICABLE" to CaptureGate.OPTION_UNSUPPORTED, dao.getById(it)!!.let { e -> e.deliveryState to e.deliveryNote }) }
    }

    // Option-tip safety B: a maybe-option line appended to a group row, or re-queued by the classifier, is kept on the phone.
    @Test
    fun maybeOptionLinesFromAppendAndRequeueAreNeverSent() = runBlocking {
        val dao = db.notificationEventDao()
        val maybe = "BUY TCS (18 PE) @ 3500 TGT 3600 SL 3400"
        val group = raw("org.telegram.messenger", "g1", "Tips Group", "Good morning").copy(groupConversation = true)
        pipeline.ingest(group)
        val appended = (pipeline.ingest(group.copy(body = "Good morning\n$maybe")) as IngestionPipeline.Result.Stored).eventId
        assertEquals("PENDING", dao.getById(appended)!!.deliveryState)
        val requeued = dao.insert(
            NotificationEventEntity(
                sourcePackage = "com.fivepaisa.trade", sourceName = "5paisa", sourceKey = "r1", eventFingerprint = "r1", title = "Idea", body = maybe,
                postedAt = t0, category = "MARKET", priority = 10, confidence = .5f, isTrading = false, deliveryState = "NOT_APPLICABLE"
            )
        )
        dao.updateClassification(requeued, "TRADING", 100, .96f, isTrading = true, queue = true)
        assertEquals("PENDING", dao.getById(requeued)!!.deliveryState)

        val sent = mutableListOf<String>()
        val client = object : MarksyGatewayClient {
            override suspend fun capture(eventId: Long, message: CapturedMessage): Result<MarksyInsight> {
                sent += message.text
                return Result.success(MarksyInsight(eventId, "ok"))
            }
            override suspend fun captureList() = Result.success(emptySet<String>())
        }
        val context = CaptureContext(capturePackages = setOf("com.fivepaisa.trade"), chatAllowList = setOf("tips group"), chatSenders = emptySet(), username = "user-1", deviceSalt = "salt")
        TradingDeliveryRun(dao, client, context).drain()

        assertEquals(emptyList<String>(), sent)
        listOf(appended, requeued).forEach { assertEquals(it.toString(), "NOT_APPLICABLE" to CaptureGate.OPTION_UNSUPPORTED, dao.getById(it)!!.let { e -> e.deliveryState to e.deliveryNote }) }
    }

    // 4b review I2: a local group row that gains a call sends only the new line, with no sender name, recorded or not.
    @Test
    fun aLocalGroupRowThatGainsACallSendsOnlyTheCallAndNoNames() = runBlocking {
        val dao = db.notificationEventDao()
        val group = raw("org.telegram.messenger", "g1", "Tips Group", "Priya: hi\nRK: ok").copy(groupConversation = true)
        val local = (pipeline.ingest(group) as IngestionPipeline.Result.Stored).eventId
        val split = pipeline.ingest(group.copy(body = "Priya: hi\nRK: ok\nAmit: BUY IDEA CMP 9.5 SL 8.9 TGT 11")) as IngestionPipeline.Result.Stored
        assertEquals("NOT_APPLICABLE", dao.getById(local)!!.deliveryState)
        val row = dao.getById(split.eventId)!!
        assertEquals("PENDING", row.deliveryState)

        val context = CaptureContext(capturePackages = emptySet(), chatAllowList = setOf("tips group"), chatSenders = setOf("RK"), username = "user-1", deviceSalt = "salt")
        val text = (CaptureGate.decide(row, context) as CaptureDecision.Send).message.text
        assertEquals("BUY IDEA CMP 9.5 SL 8.9 TGT 11", text)
        listOf("Priya", "RK", "Amit").forEach { assertFalse(it, text.contains(it)) }
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
