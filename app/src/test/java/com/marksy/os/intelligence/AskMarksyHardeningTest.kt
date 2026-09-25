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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** EPIC-016 pre-device hardening: spec query set plus adversarial model output against a fixed store. */
class AskMarksyHardeningTest {
    private val zone = ZoneOffset.UTC
    // Thursday 24 Sep 2026, 18:00 UTC.
    private val now = LocalDateTime.of(2026, 9, 24, 18, 0).toInstant(zone).toEpochMilli()
    private var seq = 0L

    private fun at(m: Int, d: Int) = LocalDateTime.of(2026, m, d, 10, 0).toInstant(zone).toEpochMilli()
    private fun startOf(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun event(category: String, title: String, body: String, postedAt: Long): NotificationEventEntity {
        val base = NotificationEventEntity(id = ++seq, sourcePackage = "com.app", sourceName = "app", sourceKey = "k$seq", eventFingerprint = "f$seq",
            title = title, body = body, postedAt = postedAt, category = category, priority = 60, confidence = .9f, isTrading = false, lifecycleState = "ACTIVE")
        return base.copy(intelligenceJson = EventNormalizer.toJson(EventNormalizer.normalize(base, zone), null))
    }

    private class Store(val rows: List<NotificationEventEntity>) : AskMarksy.Retriever {
        override suspend fun events(from: Long, to: Long, limit: Int) = rows.filter { it.postedAt in from until to }.sortedByDescending { it.postedAt }.take(limit)
        override suspend fun entities(name: String) = emptyList<ContextEntity>()
        override suspend fun eventIdsFor(entityId: Long) = emptyList<Long>()
    }

    private val amazonAug = event("PAYMENTS", "Paid", "You paid ₹1,299 to Amazon", at(8, 25))
    private val swiggyAug = event("PAYMENTS", "Paid", "You paid ₹450 to Swiggy", at(8, 24))
    private val amazonSep5 = event("PAYMENTS", "Paid", "You paid ₹500 to Amazon", at(9, 5))
    private val swiggySep10 = event("PAYMENTS", "Paid", "You paid ₹300 to Swiggy", at(9, 10))
    private val amazonSep20 = event("PAYMENTS", "Paid", "You paid ₹200 to Amazon", at(9, 20))
    private val amazonParcel = event("DELIVERY", "Amazon", "Your Amazon package arrives tomorrow", at(9, 22))
    private val store = Store(listOf(amazonAug, swiggyAug, amazonSep5, swiggySep10, amazonSep20, amazonParcel))
    private val ids = store.rows.map { it.id }.toSet()

    private fun backend(reply: String) = object : PromptBackend {
        override val runtimeVersion = "fake"
        override suspend fun availability() = PromptBackend.Availability.AVAILABLE
        override suspend fun modelName() = "nano"
        override suspend fun download() {}
        override suspend fun warmup() {}
        override suspend fun generate(prompt: String) = reply
    }

    private fun modelChain(reply: String) = listOf(ModelQueryInterpreter(IntelligenceService(listOf(GeminiNanoModel(backend(reply))))))

    private fun ask(text: String, interpreters: List<AskMarksy.QueryInterpreter> = emptyList()) =
        runBlocking { AskMarksy.ask(text, null, interpreters, store, now, zone) }.also { a ->
            assertTrue("answers may only reference stored rows", ids.containsAll(a.derivedFromEventIds))
            assertTrue(a.items.all { it.eventId in a.derivedFromEventIds })
        }

    // ------------------------------------------------------------ spec query set (deterministic)

    @Test
    fun spendAtAmazonLastMonth() {
        val a = ask("What did I spend at Amazon last month?")
        assertEquals(Intent.PAYMENTS, a.query.intent)
        assertEquals("amazon", a.query.subject)
        assertEquals("last month", a.query.range.label)
        assertEquals(listOf(amazonAug.id), a.derivedFromEventIds)
    }

    @Test
    fun showMyAmazonTransactionsFiltersByTheNamedMerchant() {
        val a = ask("Show my Amazon transactions")
        assertEquals(Intent.PAYMENTS, a.query.intent)
        assertEquals("amazon", a.query.subject)
        assertEquals(listOf(amazonSep20.id), a.derivedFromEventIds)
    }

    @Test
    fun howMuchAtAmazonTotalsOnlyAmazonDebits() {
        val a = ask("How much did I spend at Amazon?")
        assertEquals("amazon", a.query.subject)
        assertEquals(EventExtractor.Direction.DEBIT, a.query.direction)
        assertEquals(listOf(amazonSep20.id), a.derivedFromEventIds)
        assertTrue(a.headline, a.headline.contains("₹200") && !a.headline.contains("₹300"))
    }

    @Test
    fun spendLastMonthCoversEveryMerchant() {
        val a = ask("What did I spend last month?")
        assertNull(a.query.subject)
        assertEquals(setOf(amazonAug.id, swiggyAug.id), a.derivedFromEventIds.toSet())
    }

    @Test
    fun explicitDateSpanIsResolvedInclusively() {
        listOf("Show transactions between 1 September and 15 September", "show transactions from sep 1st to sep 15th").forEach { q ->
            val a = ask(q)
            assertEquals(q, Intent.PAYMENTS, a.query.intent)
            assertNull(q, a.query.subject)
            assertEquals(startOf(2026, 9, 1), a.query.range.from)
            assertEquals(startOf(2026, 9, 16), a.query.range.to)
            assertEquals("1 Sep – 15 Sep", a.query.range.label)
            assertEquals(q, setOf(amazonSep5.id, swiggySep10.id), a.derivedFromEventIds.toSet())
        }
    }

    @Test
    fun yearlessSpanNeverPointsIntoTheFutureAndInvalidDatesAreIgnored() {
        val winter = AskMarksy.explicitRange("payments between 1 December and 5 January", now, zone)!!
        assertEquals(startOf(2025, 12, 1), winter.from)
        assertEquals(startOf(2026, 1, 6), winter.to)
        val withYear = AskMarksy.explicitRange("between 1 dec 2024 and 3 dec 2024", now, zone)!!
        assertEquals(startOf(2024, 12, 1), withYear.from)
        assertNull(AskMarksy.explicitRange("between 31 february and 3 march", now, zone))
    }

    @Test
    fun unknownMerchantIsANoResultNotAFabrication() {
        val a = ask("How much did I spend at a merchant that does not exist?")
        assertTrue(a.noResult)
        assertTrue(a.items.isEmpty() && a.derivedFromEventIds.isEmpty())
        assertTrue(a.headline, a.headline.startsWith("I found no payments matching"))
    }

    // ------------------------------------------------------------ adversarial model output

    @Test
    fun inventedTransactionsAmountsAndAnswersInModelOutputAreIgnored() {
        val reply = """{"intent":"PAYMENTS","range":"default","subject":"amazon","direction":"DEBIT","confidence":0.95,
            "transactions":[{"merchant":"Flipkart","amount":99999,"date":"2026-09-23"}],"answer":"You spent ₹99999 at Flipkart","amount":99999}"""
        val a = ask("how much did I spend at amazon", modelChain(reply))
        assertEquals("on-device-model", a.interpretedBy)
        assertEquals(listOf(amazonSep20.id), a.derivedFromEventIds)
        assertFalse(a.headline.contains("99999") || a.headline.contains("Flipkart", ignoreCase = true))
    }

    @Test
    fun fabricatedOrPartialMerchantIsDroppedAndTheTypedMerchantKept() {
        listOf("flipkart", "maz", "₹5000").forEach { fake ->
            val reply = """{"intent":"PAYMENTS","range":"default","subject":"$fake","confidence":0.9}"""
            val a = ask("how much did I spend at amazon", modelChain(reply))
            assertEquals(fake, "amazon", a.query.subject)
            assertEquals(fake, listOf(amazonSep20.id), a.derivedFromEventIds)
        }
    }

    @Test
    fun modelOmittingTheTypedMerchantCannotWidenTheAnswer() {
        val reply = """{"intent":"PAYMENTS","range":"default","subject":null,"direction":"CREDIT","confidence":0.9}"""
        val a = ask("how much did I spend at amazon", modelChain(reply))
        assertEquals("amazon", a.query.subject)
        assertEquals(EventExtractor.Direction.DEBIT, a.query.direction)
        assertEquals(listOf(amazonSep20.id), a.derivedFromEventIds)
    }

    @Test
    fun unsupportedDateMalformedJsonAndMissingFieldsFallBackToDeterministic() {
        listOf(
            """{"intent":"PAYMENTS","range":"2026-01-01..2026-12-31","confidence":0.9}""",
            """{intent: PAYMENTS, range: """,
            """{"intent":"PAYMENTS","confidence":0.9}""",
            """{"range":"last_month","confidence":0.9}""",
            """{"intent":"PAYMENTS","range":"last_month"}""",
            """{"intent":"PAYMENTS","range":"last_month","confidence":"high"}""",
            """{"intent":"PAYMENTS","range":"last_month","direction":"STEAL","confidence":0.9}""",
            ""
        ).forEach { reply ->
            val a = ask("What did I spend at Amazon last month?", modelChain(reply))
            assertEquals(reply, "deterministic", a.interpretedBy)
            assertEquals(reply, listOf(amazonAug.id), a.derivedFromEventIds)
        }
    }

    @Test
    fun modelIntentContradictingExplicitWordingDefersToDeterministic() {
        val reply = """{"intent":"DELIVERIES","range":"default","subject":"amazon","confidence":0.97}"""
        val a = ask("How much did I spend at Amazon?", modelChain(reply))
        assertEquals("deterministic", a.interpretedBy)
        assertEquals(Intent.PAYMENTS, a.query.intent)
        assertEquals(listOf(amazonSep20.id), a.derivedFromEventIds)
    }

    @Test
    fun explicitUserRangeBeatsTheModelsRange() {
        val lastMonth = """{"intent":"PAYMENTS","range":"today","confidence":0.9}"""
        assertEquals(setOf(amazonAug.id, swiggyAug.id), ask("payments last month", modelChain(lastMonth)).derivedFromEventIds.toSet())
        val span = """{"intent":"PAYMENTS","range":"default","confidence":0.9}"""
        val a = ask("show transactions between 1 September and 15 September", modelChain(span))
        assertEquals("1 Sep – 15 Sep", a.query.range.label)
        assertEquals(setOf(amazonSep5.id, swiggySep10.id), a.derivedFromEventIds.toSet())
    }
}
