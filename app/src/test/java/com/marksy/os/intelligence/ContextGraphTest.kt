package com.marksy.os.intelligence

import androidx.room.Room
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.ContextGraph.NodeType
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
class ContextGraphTest {
    private lateinit var db: MarksyDatabase
    private lateinit var graph: ContextGraph
    private lateinit var pipeline: EventIntelligencePipeline
    private val t0 = 1_790_000_000_000L
    private var seq = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MarksyDatabase::class.java).allowMainThreadQueries().build()
        graph = ContextGraph(db.contextGraphDao()) { t0 }
        pipeline = EventIntelligencePipeline(db.notificationEventDao(), ZoneOffset.UTC, { t0 }, graph)
    }

    @After
    fun tearDown() = db.close()

    private fun capture(pkg: String, source: String, category: String, title: String, body: String, at: Long = t0): Long = runBlocking {
        seq++
        val id = db.notificationEventDao().insert(
            NotificationEventEntity(sourcePackage = pkg, sourceName = source, sourceKey = "k$seq", eventFingerprint = "f$seq", title = title,
                body = body, postedAt = at, category = category, priority = 60, confidence = .9f, isTrading = false)
        )
        pipeline.process(id)
        id
    }

    @Test
    fun sameOrderFromShopAndCourierIsOneEntityWithCrossSourceTimeline() = runBlocking {
        val shipped = capture("in.amazon", "Amazon", "DELIVERY", "Shipped", "Order ID 403-1234567-7654321 shipped via Delhivery")
        val delivered = capture("com.delhivery", "Delhivery", "DELIVERY", "Delivered", "order no 403-1234567-7654321 delivered", t0 + 1000)

        val order = db.contextGraphDao().findEntity(NodeType.ORDER.name, "40312345677654321")!!
        assertEquals(2, order.mentionCount)
        assertEquals(2, order.sourceCount)
        assertEquals(listOf(delivered, shipped), graph.timeline(order.id).map { it.eventId })

        val courier = db.contextGraphDao().findEntity(NodeType.DELIVERY.name, "delhivery")!!
        val edge = db.contextGraphDao().relationsOf(order.id, 10).single { it.fromId == minOf(order.id, courier.id) && it.toId == maxOf(order.id, courier.id) }
        assertEquals(2, edge.weight)
        assertEquals(2f / 3f, edge.confidence, .001f)
    }

    @Test
    fun reindexingIsIdempotentAndLegalSuffixDuplicatesCollapse() = runBlocking {
        val a = capture("com.bank", "Bank", "BANKING", "Debited", "Rs 500 debited at AMAZON RETAIL PVT LTD.")
        capture("com.upi", "UPI", "PAYMENTS", "Paid", "Paid Rs 20 at Amazon Retail.")
        val merchant = db.contextGraphDao().findEntity(NodeType.MERCHANT.name, "amazon retail")!!
        assertEquals(2, merchant.mentionCount)

        graph.index(db.notificationEventDao().getById(a)!!, EventNormalizer.factsFromJson(db.notificationEventDao().getById(a)!!.intelligenceJson))
        assertEquals(2, db.contextGraphDao().entity(merchant.id)!!.mentionCount)
    }

    @Test
    fun correctionsMergeUnlinkAndRename() = runBlocking {
        val e1 = capture("com.whatsapp", "WhatsApp", "MESSAGES", "Rahul", "see you")
        val e2 = capture("com.whatsapp", "WhatsApp", "MESSAGES", "Rahul Sharma", "ok")
        val short = db.contextGraphDao().findEntity(NodeType.PERSON.name, "rahul")!!
        val full = db.contextGraphDao().findEntity(NodeType.PERSON.name, "rahul sharma")!!

        graph.merge(short.id, full.id)
        assertEquals(listOf(e2, e1).toSet(), graph.timeline(short.id).map { it.eventId }.toSet())
        // New mentions of the merged name land on the surviving node.
        val e3 = capture("com.whatsapp", "WhatsApp", "MESSAGES", "Rahul", "again", t0 + 5)
        assertTrue(graph.timeline(full.id).any { it.eventId == e3 })

        graph.unlink(full.id, e2)
        assertTrue(graph.timeline(full.id).none { it.eventId == e2 })
        graph.rename(full.id, "Rahul S.")
        assertEquals("Rahul S.", db.contextGraphDao().entity(full.id)!!.displayName)
        assertEquals("Rahul S.", graph.search("rahul").first().displayName)
    }

    @Test
    fun canonicalKeysAreStable() {
        assertEquals("amazon retail", ContextGraph.canonicalKey(NodeType.MERCHANT, "Amazon Retail Pvt. Ltd."))
        assertEquals("40312345677654321", ContextGraph.canonicalKey(NodeType.ORDER, "403-1234567-7654321"))
    }
}
