package com.marksy.os.intelligence

import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.AskMarksy.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class AskMarksyTest {
    private val zone = ZoneOffset.UTC
    // Thursday 24 Sep 2026, 18:00 UTC.
    private val now = LocalDateTime.of(2026, 9, 24, 18, 0).toInstant(zone).toEpochMilli()
    private val hour = 3_600_000L
    private var seq = 0L

    private fun event(category: String, title: String, body: String, at: Long, pkg: String = "com.app", state: String = "ACTIVE", duplicateOf: Long? = null): NotificationEventEntity {
        val base = NotificationEventEntity(id = ++seq, sourcePackage = pkg, sourceName = pkg.substringAfterLast('.'), sourceKey = "k$seq", eventFingerprint = "f$seq",
            title = title, body = body, postedAt = at, category = category, priority = 60, confidence = .9f, isTrading = category == "TRADING",
            lifecycleState = state, duplicateOfId = duplicateOf)
        return base.copy(intelligenceJson = EventNormalizer.toJson(EventNormalizer.normalize(base, zone), duplicateOf))
    }

    private class Store(val rows: List<NotificationEventEntity>, val graph: Map<String, List<Long>> = emptyMap()) : AskMarksy.Retriever {
        override suspend fun events(from: Long, to: Long, limit: Int) = rows.filter { it.postedAt in from until to }.sortedByDescending { it.postedAt }.take(limit)
        override suspend fun entities(name: String) = graph.keys.filter { it.contains(name, true) }.mapIndexed { i, n ->
            ContextEntity(id = i + 1L, type = "PERSON", canonicalKey = n.lowercase(), displayName = n, confidence = 1f, firstSeenAt = 0, lastSeenAt = 0, mentionCount = 1, sourceCount = 1)
        }
        override suspend fun eventIdsFor(entityId: Long) = graph.values.toList()[(entityId - 1).toInt()]
    }

    private fun ask(store: Store, text: String, previous: AskMarksy.Query? = null) = runBlocking {
        AskMarksy.answer(AskMarksy.parse(text, previous, now, zone), store, now)
    }

    @Test
    fun paymentsThisWeekSumsDebitsFromRetrievedRowsOnlyAndCollapsesDuplicates() {
        val bank = event("BANKING", "Debited", "Rs 500 debited. UPI Ref 426712345678", now - 2 * hour, "com.bank")
        val upiDup = event("PAYMENTS", "Paid", "Paid Rs 500 to Rahul. UPI Ref 426712345678", now - 2 * hour + 1000, "com.upi", duplicateOf = bank.id)
        val other = event("PAYMENTS", "Paid", "You paid ₹1,250.50 to Swiggy", now - 26 * hour, "com.upi")
        val credit = event("BANKING", "Credited", "Rs 900 credited", now - hour, "com.bank")
        val lastMonth = event("PAYMENTS", "Paid", "You paid ₹99 to X", now - 40 * 24 * hour, "com.upi")
        val a = ask(Store(listOf(bank, upiDup, other, credit, lastMonth)), "What payments did I make this week?")

        assertEquals(Intent.PAYMENTS, a.query.intent)
        assertEquals("this week", a.query.range.label)
        assertEquals(EventExtractor.Direction.DEBIT, a.query.direction)
        assertEquals("2 payments this week: debit ₹1750.50.", a.headline)
        assertEquals(setOf(bank.id, other.id), a.derivedFromEventIds.toSet())
    }

    @Test
    fun deliveriesTomorrowUseTheMentionedDateNotThePostedDate() {
        val tomorrow = event("DELIVERY", "Out soon", "Order ID 403-1234567-7654321 arriving tomorrow", now - 3 * hour)
        val nextWeek = event("DELIVERY", "Shipped", "Order ID 111-2222222-3333333 arriving 30 Sep", now - 3 * hour)
        val done = event("DELIVERY", "Delivered", "Order ID 999-2222222-3333333 delivered, was due tomorrow", now - hour)
        val a = ask(Store(listOf(tomorrow, nextWeek, done)), "What deliveries are coming tomorrow?")
        assertEquals(listOf(tomorrow.id), a.derivedFromEventIds)
        assertEquals("1 open delivery expected tomorrow.", a.headline)
    }

    @Test
    fun personQueryUsesGraphAndTitleAndFollowUpKeepsIntentWithNewRange() {
        val msg = event("MESSAGES", "Rahul", "Lunch at 1?", now - hour, "com.whatsapp")
        val viaGraph = event("PAYMENTS", "Money received", "Rs 200 from R. Sharma", now - 2 * hour, "com.upi")
        val old = event("MESSAGES", "Rahul", "yesterday text", now - 20 * hour, "com.whatsapp")
        val store = Store(listOf(msg, viaGraph, old), graph = mapOf("Rahul Sharma" to listOf(viaGraph.id)))

        val today = ask(store, "What did Rahul send me today?")
        assertEquals(Intent.FROM_PERSON, today.query.intent)
        assertEquals("rahul", today.query.subject)
        assertEquals(setOf(msg.id, viaGraph.id), today.derivedFromEventIds.toSet())

        val follow = ask(store, "And yesterday?", today.query)
        assertEquals(Intent.FROM_PERSON, follow.query.intent)
        assertEquals("rahul", follow.query.subject)
        assertEquals("yesterday", follow.query.range.label)
        assertEquals(listOf(old.id), follow.derivedFromEventIds)
    }

    @Test
    fun billsDueImportantMissedAndNoResultHandling() {
        val bill = event("BILLS", "Electricity bill due", "Rs 1,200 due on 30 Sep", now - 5 * 24 * hour)
        val paid = event("BILLS", "Water bill", "Bill paid", now - 5 * 24 * hour, state = "RESOLVED")
        val unread = event("MESSAGES", "Amit", "call me", now - hour, state = "NEW")
        val store = Store(listOf(bill, paid, unread))

        val bills = ask(store, "Which bills are due?")
        assertEquals(listOf(bill.id), bills.derivedFromEventIds)
        assertTrue(bills.items.single().detail!!.contains("₹1200"))

        assertEquals(listOf(unread.id), ask(store, "What did I miss?").derivedFromEventIds)

        val none = ask(Store(emptyList()), "What payments did I make this week?")
        assertTrue(none.noResult)
        assertEquals("I found no payments with an amount this week.", none.headline)
        assertTrue(none.items.isEmpty())
    }

    @Test
    fun interpreterFailureFallsBackToDeterministicAndAnswerStaysGrounded() = runBlocking {
        val broken = object : AskMarksy.QueryInterpreter {
            override val name = "broken-model"
            override suspend fun interpret(text: String, previous: AskMarksy.Query?, nowMillis: Long, zone: java.time.ZoneId): AskMarksy.Query? = error("model crashed")
        }
        val bill = event("BILLS", "Gas bill due", "due soon", now - hour)
        val store = Store(listOf(bill))
        val a = AskMarksy.ask("bills?", null, listOf(broken), store, now, zone)
        assertEquals("deterministic", a.interpretedBy)
        assertEquals(listOf(bill.id), a.derivedFromEventIds)
    }
}
