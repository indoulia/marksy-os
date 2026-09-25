package com.marksy.os.intelligence

import com.marksy.os.ai.GeminiNanoModel
import com.marksy.os.ai.IntelligenceService
import com.marksy.os.ai.ModelQueryInterpreter
import com.marksy.os.ai.PromptBackend
import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.AskMarksy.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** EPIC-016: interpretation (model or deterministic) only shapes the query; every fact comes from retrieved rows. */
class AskMarksyGroundingTest {
    private val zone = ZoneOffset.UTC
    // Thursday 24 Sep 2026, 18:00 UTC.
    private val now = LocalDateTime.of(2026, 9, 24, 18, 0).toInstant(zone).toEpochMilli()
    private val day = 86_400_000L
    private var seq = 0L

    private fun event(category: String, title: String, body: String, at: Long): NotificationEventEntity {
        val base = NotificationEventEntity(id = ++seq, sourcePackage = "com.app", sourceName = "app", sourceKey = "k$seq", eventFingerprint = "f$seq",
            title = title, body = body, postedAt = at, category = category, priority = 60, confidence = .9f, isTrading = false, lifecycleState = "ACTIVE")
        return base.copy(intelligenceJson = EventNormalizer.toJson(EventNormalizer.normalize(base, zone), null))
    }

    private class Store(val rows: List<NotificationEventEntity>, val people: Map<String, List<Long>> = emptyMap()) : AskMarksy.Retriever {
        override suspend fun events(from: Long, to: Long, limit: Int) = rows.filter { it.postedAt in from until to }.sortedByDescending { it.postedAt }.take(limit)
        override suspend fun entities(name: String) = people.keys.toList().mapIndexedNotNull { i, n ->
            if (!n.contains(name, true)) null
            else ContextEntity(id = i + 1L, type = "PERSON", canonicalKey = n.lowercase(), displayName = n, confidence = 1f, firstSeenAt = 0, lastSeenAt = 0, mentionCount = 1, sourceCount = 1)
        }
        override suspend fun eventIdsFor(entityId: Long) = people.values.toList()[(entityId - 1).toInt()]
    }

    private fun backend(reply: String) = object : PromptBackend {
        override val runtimeVersion = "fake"
        override suspend fun availability() = PromptBackend.Availability.AVAILABLE
        override suspend fun modelName() = "nano"
        override suspend fun download() {}
        override suspend fun warmup() {}
        override suspend fun generate(prompt: String) = reply
    }

    /** The production chain: Gemini Nano adapter -> IntelligenceService -> ModelQueryInterpreter, with a scripted runtime. */
    private fun modelChain(reply: String) = listOf(ModelQueryInterpreter(IntelligenceService(listOf(GeminiNanoModel(backend(reply))))))

    private fun ask(store: Store, text: String, interpreters: List<AskMarksy.QueryInterpreter> = emptyList(), previous: AskMarksy.Query? = null) =
        runBlocking { AskMarksy.ask(text, previous, interpreters, store, now, zone) }

    private val amazonAug = event("PAYMENTS", "Paid", "You paid ₹1,299 to Amazon", now - 30 * day)
    private val swiggyAug = event("PAYMENTS", "Paid", "You paid ₹450 to Swiggy", now - 31 * day)
    private val amazonSep = event("PAYMENTS", "Paid", "You paid ₹200 to Amazon", now - day)
    private val store = Store(listOf(amazonAug, swiggyAug, amazonSep))

    @Test
    fun deterministicMerchantAndLastMonthInterpretation() {
        val a = ask(store, "What payments did I make to Amazon last month?")
        assertEquals(Intent.PAYMENTS, a.query.intent)
        assertEquals("amazon", a.query.subject)
        assertEquals("last month", a.query.range.label)
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0).toInstant(zone).toEpochMilli(), a.query.range.from)
        assertEquals(EventExtractor.Direction.DEBIT, a.query.direction)
        assertEquals(listOf(amazonAug.id), a.derivedFromEventIds)
        assertEquals("1 payment matching \"amazon\" last month: debit ₹1299.", a.headline)
        assertEquals(amazonAug.id, a.items.single().eventId)
        assertEquals("deterministic", a.interpretedBy)
    }

    @Test
    fun noMatchingRecordsSaysSoInsteadOfInventing() {
        val a = ask(store, "payments to Flipkart last month")
        assertTrue(a.noResult)
        assertTrue(a.items.isEmpty() && a.derivedFromEventIds.isEmpty())
        assertEquals("I found no payments matching \"flipkart\" with an amount last month.", a.headline)
    }

    @Test
    fun localModelInterpretsNaturalLanguageButRetrievalStaysAuthoritative() {
        val reply = """{"intent":"PAYMENTS","range":"last_month","subject":"amazon","direction":"DEBIT","confidence":0.92}"""
        val a = ask(store, "how much did amazon take from me in the previous month", modelChain(reply))
        assertEquals("on-device-model", a.interpretedBy)
        assertEquals(Intent.PAYMENTS, a.query.intent)
        assertEquals(listOf(amazonAug.id), a.derivedFromEventIds)
        assertTrue(a.headline.contains("₹1299"))
    }

    @Test
    fun modelCannotInjectASubjectTheUserNeverTyped() {
        val reply = """{"intent":"PAYMENTS","range":"this_week","subject":"rahul","direction":null,"confidence":0.9}"""
        val a = ask(store, "payments this week", modelChain(reply))
        assertEquals("on-device-model", a.interpretedBy)
        assertNull(a.query.subject)
        assertEquals(listOf(amazonSep.id), a.derivedFromEventIds)
    }

    @Test
    fun modelNamingAPersonWithNoDataGetsNoResultNotAFabricatedAnswer() {
        val reply = """{"intent":"FROM_PERSON","range":"this_week","subject":"priya","confidence":0.95}"""
        val a = ask(store, "anything from priya", modelChain(reply))
        assertTrue(a.noResult)
        assertTrue(a.items.isEmpty())
        assertTrue(a.headline.startsWith("Nothing from \"priya\""))
        // Whatever the model says, answers can only reference ids that exist in the store.
        assertTrue(store.rows.map { it.id }.containsAll(a.derivedFromEventIds))
    }

    @Test
    fun malformedOrUnsupportedModelOutputFallsBackToDeterministic() {
        listOf("not json", """{"intent":"SEND_MONEY","range":"today","confidence":0.9}""", """{"intent":"PAYMENTS","range":"today","confidence":0.1}""").forEach { reply ->
            val a = ask(store, "What payments did I make to Amazon last month?", modelChain(reply))
            assertEquals("deterministic", a.interpretedBy)
            assertEquals(listOf(amazonAug.id), a.derivedFromEventIds)
        }
    }

    @Test
    fun ambiguousPersonAsksWhichOneThenAnswersTheChosenOne() {
        val a1 = event("MESSAGES", "Rahul Sharma", "Draft is ready", now - day)
        val a2 = event("MESSAGES", "Rahul Verma", "Lunch?", now - 2 * day)
        val people = Store(listOf(a1, a2), mapOf("Rahul Sharma" to listOf(a1.id), "Rahul Verma" to listOf(a2.id)))

        val q = ask(people, "What did Rahul send me?")
        assertTrue(q.needsClarification)
        assertFalse(q.noResult)
        assertEquals("Which Rahul do you mean: Rahul Sharma or Rahul Verma?", q.headline)
        assertTrue(q.items.isEmpty() && q.derivedFromEventIds.isEmpty())
        assertEquals(listOf("What did Rahul Sharma send me?", "What did Rahul Verma send me?"), q.followUps)

        val chosen = ask(people, q.followUps.first(), previous = q.query)
        assertFalse(chosen.needsClarification)
        assertEquals(listOf(a1.id), chosen.derivedFromEventIds)
    }

    @Test
    fun onlyOneCandidateActiveInRangeIsNotAmbiguous() {
        val recent = event("MESSAGES", "Rahul Sharma", "Draft is ready", now - day)
        val old = event("MESSAGES", "Rahul Verma", "Lunch?", now - 40 * day)
        val people = Store(listOf(recent, old), mapOf("Rahul Sharma" to listOf(recent.id), "Rahul Verma" to listOf(old.id)))
        val a = ask(people, "What did Rahul send me?")
        assertFalse(a.needsClarification)
        assertEquals(listOf(recent.id), a.derivedFromEventIds)
    }

    @Test
    fun interpretationIsSeparableSoTheScreenCanRouteOnTheModelsIntent() = runBlocking {
        val reply = """{"intent":"BILLS_DUE","range":"default","subject":null,"confidence":0.8}"""
        val i = AskMarksy.interpret("anything I haven't cleared yet?", null, modelChain(reply), now, zone)
        assertEquals(Intent.BILLS_DUE, i.query.intent)
        assertEquals("on-device-model", i.interpretedBy)
        val d = AskMarksy.interpret("anything I haven't cleared yet?", null, emptyList(), now, zone)
        assertEquals(Intent.SEARCH, d.query.intent)
        assertEquals("deterministic", d.interpretedBy)
    }
}
