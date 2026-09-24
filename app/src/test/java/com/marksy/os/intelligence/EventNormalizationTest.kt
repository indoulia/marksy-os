package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.EventExtractor.Direction
import com.marksy.os.intelligence.EventExtractor.EntityType
import com.marksy.os.intelligence.EventExtractor.ReferenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class EventNormalizationTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val posted = LocalDateTime.of(2026, 9, 24, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun event(
        title: String,
        body: String,
        category: String = "BANKING",
        pkg: String = "com.snapwork.hdfc",
        source: String = "HDFC Bank",
        trading: Boolean = false,
        confidence: Float = .9f
    ) = NotificationEventEntity(
        id = 1, sourcePackage = pkg, sourceName = source, sourceKey = "k", eventFingerprint = "f",
        title = title, body = body, postedAt = posted, category = category, priority = 80,
        confidence = confidence, isTrading = trading
    )

    private fun facts(e: NotificationEventEntity) =
        EventExtractor.extract(e.sourcePackage, e.sourceName, e.category, e.title, e.body, e.postedAt, zone)

    @Test
    fun extractsDebitAmountTransactionRefBankAndMerchant() {
        val f = facts(event("Account debited", "Rs 1,250.50 debited from a/c XX1234 at AMAZON RETAIL on 24-09-26. UPI Ref No 426712345678."))

        assertEquals(125050L, f.primaryAmount!!.amountMinor)
        assertEquals("INR", f.primaryAmount!!.currency)
        assertEquals(Direction.DEBIT, f.primaryAmount!!.direction)
        assertEquals("426712345678", f.transactionReference!!.value)
        assertTrue(f.entities.any { it.type == EntityType.BANK && it.value == "HDFC Bank" })
        assertTrue(f.entities.any { it.type == EntityType.MERCHANT && it.value == "AMAZON RETAIL" })
        assertTrue(f.times.any { it.raw == "24-09-26" })
    }

    @Test
    fun extractsOrderTrackingCourierAndRelativeDate() {
        val f = facts(event("Out for delivery", "Order ID 403-1234567-7654321 via Delhivery AWB 1234567890 arriving tomorrow by 7 pm", category = "DELIVERY", pkg = "in.amazon.mShop.android.shopping", source = "Amazon"))

        assertEquals(ReferenceType.ORDER, f.threadReference!!.type)
        assertEquals("403-1234567-7654321", f.threadReference!!.value)
        assertTrue(f.references.any { it.type == ReferenceType.TRACKING && it.value == "1234567890" })
        assertTrue(f.entities.any { it.type == EntityType.DELIVERY && it.value == "Delhivery" })
        val expected = LocalDateTime.of(2026, 9, 25, 19, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, f.times.first().epochMillis)
        assertFalse(f.terminal)
    }

    @Test
    fun messageSenderBecomesPersonAndPlainWordsAreNotReferences() {
        val f = facts(event("Rahul Sharma", "Reference: please call me back", category = "MESSAGES", pkg = "com.whatsapp", source = "WhatsApp"))
        assertTrue(f.entities.any { it.type == EntityType.PERSON && it.value == "Rahul Sharma" })
        assertTrue(f.references.isEmpty())
        assertTrue(f.amounts.isEmpty())
    }

    @Test
    fun eachAmountTakesTheDirectionOfItsNearestVerb() {
        val f = facts(event("Alert", "Rs 500 debited from a/c XX1. Salary Rs 12,300 credited yesterday"))
        assertEquals(EventExtractor.Direction.DEBIT, f.amounts.single { it.amountMinor == 50000L }.direction)
        assertEquals(EventExtractor.Direction.CREDIT, f.amounts.single { it.amountMinor == 1230000L }.direction)
    }

    @Test
    fun deliveredIsTerminal() {
        assertTrue(facts(event("Delivered", "Your order ID 403-1234567-7654321 has been delivered", category = "DELIVERY")).terminal)
    }

    @Test
    fun orderIdThreadsAcrossSourcesAndTxnRefIsStrongCorrelation() {
        val shop = EventNormalizer.normalize(event("Shipped", "Order ID 403-1234567-7654321 shipped", "DELIVERY", "in.amazon", "Amazon"), zone)
        val courier = EventNormalizer.normalize(event("Delivered", "order no. 403-1234567-7654321 delivered", "DELIVERY", "com.delhivery", "Delhivery"), zone)
        assertEquals(shop.threadKey, courier.threadKey)
        assertTrue(shop.threadKey.startsWith(EventNormalizer.REF_THREAD_PREFIX))

        val bank = EventNormalizer.normalize(event("Debited", "Rs 500 debited. UPI Ref 426712345678"), zone)
        assertEquals("txn|426712345678", bank.correlationKey)
        assertTrue(bank.correlationIsStrong)
    }

    @Test
    fun amountOnlyCorrelationIsWeakAndTradingNeverCorrelates() {
        val pay = EventNormalizer.normalize(event("Payment", "You paid ₹500 to Rahul", "PAYMENTS", "com.phonepe.app", "PhonePe"), zone)
        assertEquals("amt|DEBIT|50000|INR", pay.correlationKey)
        assertFalse(pay.correlationIsStrong)

        val trade = EventNormalizer.normalize(event("Order executed", "BUY INFY 10 at ₹1500. Txn ID AB12345678", "TRADING", "com.zerodha.kite3", "Zerodha", trading = true), zone)
        assertNull(trade.correlationKey)
    }

    @Test
    fun corroboratedCategoryRaisesConfidenceWithExplanation() {
        val n = EventNormalizer.normalize(event("Debited", "Rs 500 debited", confidence = .9f), zone)
        assertEquals(.95f, n.confidence, .0001f)
        assertTrue(n.reasons.contains("Category corroborated by extracted details"))
        assertTrue(n.reasons.any { it.startsWith("Amount Rs 500") })
    }

    @Test
    fun jsonRoundTripsAndMalformedJsonIsTolerated() {
        val n = EventNormalizer.normalize(event("Debited", "Rs 500 debited at AMAZON RETAIL. UPI Ref 426712345678"), zone)
        val json = EventNormalizer.toJson(n, duplicateOfId = 7)
        val back = EventNormalizer.factsFromJson(json)
        assertEquals(n.facts.amounts, back.amounts)
        assertEquals(n.facts.references, back.references)
        assertEquals(n.facts.entities.map { it.type to it.value }, back.entities.map { it.type to it.value })
        assertEquals(n.reasons, EventNormalizer.reasonsFromJson(json))

        assertEquals(EventExtractor.Facts.EMPTY, EventNormalizer.factsFromJson("{not json"))
        assertTrue(EventNormalizer.reasonsFromJson(null).isEmpty())
    }

    @Test
    fun lifecycleTransitionsAreGuarded() {
        assertTrue(EventLifecycle.canTransition(EventLifecycle.State.NEW, EventLifecycle.State.ACTIVE))
        assertTrue(EventLifecycle.canTransition(EventLifecycle.State.ACTIVE, EventLifecycle.State.RESOLVED))
        assertTrue(EventLifecycle.canTransition(EventLifecycle.State.ARCHIVED, EventLifecycle.State.ACTIVE))
        assertFalse(EventLifecycle.canTransition(EventLifecycle.State.ARCHIVED, EventLifecycle.State.NEW))
        assertFalse(EventLifecycle.canTransition(EventLifecycle.State.RESOLVED, EventLifecycle.State.NEW))
        assertEquals(EventLifecycle.State.ARCHIVED, EventLifecycle.parse("NEW", archived = true))
        assertEquals(EventLifecycle.State.ACTIVE, EventLifecycle.parse("FROM_A_NEWER_BUILD"))
    }

    @Test
    fun persistedImportanceIsTimeIndependent() {
        val e = event("Debited", "Rs 500 debited")
        assertEquals(EventIntelligence.importance(e).attentionScore, EventIntelligence.importance(e).attentionScore)
        assertFalse(EventIntelligence.importance(e).reasons.contains("Recent"))
        assertTrue(EventIntelligence.analyze(e, nowMillis = posted).reasons.contains("Recent"))
    }
}
