package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartInboxModelTest {
    private val now = 1_760_000_000_000L

    @Test
    fun filtersByCategoryWithoutChangingSourceIdentity() {
        val events = listOf(event(1, "TRADING", "AAPL", now), event(2, "BANKING", "Credit", now))
        val filtered = SmartInboxModel.filter(events, SmartInboxModel.Filter.TRADING)
        assertEquals(listOf(1L), filtered.map { it.id })
        assertEquals("pkg.broker", filtered.single().sourcePackage)
    }

    @Test
    fun categoryLabelsMapToMatchingFilterOrFallBackToAll() {
        // Home dashboard tiles whose labels match a filter category.
        assertEquals(SmartInboxModel.Filter.TRADING, SmartInboxModel.Filter.forCategoryLabel("Trading"))
        assertEquals(SmartInboxModel.Filter.MESSAGES, SmartInboxModel.Filter.forCategoryLabel("Messages"))
        assertEquals(SmartInboxModel.Filter.BANKING, SmartInboxModel.Filter.forCategoryLabel("Banking"))
        assertEquals(SmartInboxModel.Filter.DELIVERY, SmartInboxModel.Filter.forCategoryLabel("Delivery"))
        assertEquals(SmartInboxModel.Filter.EMAIL, SmartInboxModel.Filter.forCategoryLabel("Emails"))
        // Tiles with no matching category filter fall back to ALL.
        assertEquals(SmartInboxModel.Filter.ALL, SmartInboxModel.Filter.forCategoryLabel("Important"))
    }

    @Test
    fun archivedEventsNeverEnterActiveInbox() {
        val events = listOf(event(1, "TRADING", "AAPL", now), event(2, "TRADING", "AAPL", now, archived = true))
        val filtered = SmartInboxModel.filter(events, SmartInboxModel.Filter.ALL)
        assertEquals(listOf(1L), filtered.map { it.id })
        // The one active (non-archived) event surfaces as exactly one thread; the archived one never does.
        val sectioned = SmartInboxModel.section(events, now)
        assertEquals(1, (sectioned.needsAttention + sectioned.recent + sectioned.quiet).size)
    }

    @Test
    fun groupsTradingEventsBySameSourceAndSymbol() {
        val events = listOf(event(1, "TRADING", "BUY AAPL", now), event(2, "TRADING", "AAPL order executed", now - 60_000))
        val sectioned = SmartInboxModel.section(events, now)
        val thread = (sectioned.needsAttention + sectioned.recent + sectioned.quiet).single()
        assertEquals(2, thread.count)
    }

    @Test
    fun keepsSameSymbolFromDifferentSourcesSeparate() {
        val events = listOf(event(1, "TRADING", "BUY AAPL", now, "pkg.one"), event(2, "TRADING", "BUY AAPL", now, "pkg.two"))
        val sectioned = SmartInboxModel.section(events, now)
        val threads = sectioned.needsAttention + sectioned.recent + sectioned.quiet
        assertEquals(2, threads.size)
        assertTrue(threads.all { it.count == 1 })
    }

    @Test
    fun failedTradingDeliveryRisesToNeedsAttention() {
        val events = listOf(event(1, "TRADING", "Order failed", now, deliveryState = "FAILED", priority = 60))
        val sectioned = SmartInboxModel.section(events, now)
        assertEquals(1, sectioned.needsAttention.size)
        assertEquals(1, sectioned.needsAttention.single().count)
        assertEquals(EventIntelligence.AttentionLevel.CRITICAL, sectioned.needsAttention.single().attentionLevel)
    }

    @Test
    fun pendingTradingBecomesMoreAttentionWorthyWhenStale() {
        val events = listOf(event(1, "TRADING", "AAPL", now - 20 * 60_000L, deliveryState = "PENDING", priority = 55))
        val result = SmartInboxModel.section(events, now)
        assertEquals(1, result.needsAttention.size)
        assertTrue(result.needsAttention.single().primaryReason.isNotBlank())
    }

    @Test
    fun inboxFoldsCrossSourceDuplicateIntoCanonicalThread() {
        val bank = event(1, "BANKING", "Rs 500 debited", now, "com.bank").copy(threadKey = "ref|transaction|X1")
        val upi = event(2, "PAYMENTS", "Paid Rs 500", now + 1, "com.upi").copy(threadKey = "ref|transaction|X1", duplicateOfId = 1)
        val threads = SmartInboxModel.inbox(listOf(bank, upi), nowMillis = now).sections.values.flatten()

        assertEquals(1, threads.size)
        assertEquals(listOf(1L), threads.single().events.map { it.id })
        assertEquals(listOf(2L), threads.single().duplicates.map { it.id })
        assertEquals(listOf(1L, 2L), threads.single().allIds)
        assertTrue(threads.single().why.any { it.contains("also reported by com.upi") })
    }

    @Test
    fun duplicateWhoseCanonicalIsGoneStillShows() {
        val orphan = event(2, "PAYMENTS", "Paid Rs 500", now, "com.upi").copy(duplicateOfId = 99)
        assertEquals(1, SmartInboxModel.inbox(listOf(orphan), nowMillis = now).sections.values.flatten().size)
    }

    @Test
    fun bucketsAreDeterministicAndExplained() {
        val inbox = SmartInboxModel.inbox(
            listOf(
                event(1, "BILLS", "Electricity bill due", now, "com.power", priority = 65),
                event(2, "TRADING", "Order executed", now, "com.broker", priority = 100),
                event(3, "PROMOTIONS", "Sale", now, "com.shop", priority = 20),
                event(4, "DELIVERY", "Delivered", now, "com.courier").copy(lifecycleState = "RESOLVED")
            ),
            nowMillis = now
        )
        fun bucketOf(id: Long) = inbox.sections.entries.first { (_, t) -> t.any { it.latest.id == id } }.key
        assertEquals(SmartInboxModel.Bucket.NEEDS_ACTION, bucketOf(1))
        assertEquals(SmartInboxModel.Bucket.PRIORITY, bucketOf(2))
        assertEquals(SmartInboxModel.Bucket.INFORMATIONAL, bucketOf(3))
        assertEquals(SmartInboxModel.Bucket.RESOLVED, bucketOf(4))
        assertEquals("Bill that may need payment", inbox.sections.getValue(SmartInboxModel.Bucket.NEEDS_ACTION).single().why.first())
    }

    @Test
    fun snoozedThreadsHideUntilExpiryAndNewMeansUnread() {
        val e = event(1, "MESSAGES", "hi", now, "com.chat").copy(snoozedUntil = now + 1_000)
        assertTrue(SmartInboxModel.inbox(listOf(e), nowMillis = now).isEmpty)
        assertEquals(1, SmartInboxModel.inbox(listOf(e), nowMillis = now).snoozedCount)
        val back = SmartInboxModel.inbox(listOf(e), nowMillis = now + 1_001).sections.values.flatten().single()
        assertTrue(back.unread)
    }

    @Test
    fun searchMatchesExtractedEntitiesAndReferences() {
        val n = EventNormalizer.normalize(event(1, "DELIVERY", "Shipped", now, "com.shop").copy(body = "Order ID 403-1234567-7654321 via Delhivery"))
        val e = event(1, "DELIVERY", "Shipped", now, "com.shop").copy(intelligenceJson = EventNormalizer.toJson(n, null))
        assertEquals(1, SmartInboxModel.inbox(listOf(e), query = "delhivery", nowMillis = now).sections.values.flatten().size)
        assertEquals(1, SmartInboxModel.inbox(listOf(e), query = "403-1234567", nowMillis = now).sections.values.flatten().size)
        assertTrue(SmartInboxModel.inbox(listOf(e), query = "flipkart", nowMillis = now).isEmpty)
    }

    private fun event(id: Long, category: String, title: String, postedAt: Long, sourcePackage: String = "pkg.broker", deliveryState: String = "NOT_APPLICABLE", priority: Int = 50, archived: Boolean = false) =
        NotificationEventEntity(
            id = id, sourcePackage = sourcePackage, sourceName = sourcePackage,
            sourceKey = "$sourcePackage-$id", eventFingerprint = "fp-$id",
            title = title, body = "", postedAt = postedAt, category = category,
            priority = priority, confidence = 0.95f, isTrading = category == "TRADING",
            deliveryState = deliveryState, archived = archived
        )
}
