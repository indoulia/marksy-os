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
    fun teamsFilterMatchesBySourceNotCategory() {
        val teams = event(1, "WORK", "Standup", 1, sourcePackage = "com.microsoft.teams")
        val other = event(2, "WORK", "Jira", 2, sourcePackage = "com.atlassian.android.jira.core")
        assertEquals(SmartInboxModel.Filter.TEAMS, SmartInboxModel.Filter.forCategoryLabel("Teams"))
        assertEquals(listOf(teams), SmartInboxModel.filter(listOf(teams, other), SmartInboxModel.Filter.TEAMS))
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

    private fun event(id: Long, category: String, title: String, postedAt: Long, sourcePackage: String = "pkg.broker", deliveryState: String = "NOT_APPLICABLE", priority: Int = 50, archived: Boolean = false) =
        NotificationEventEntity(
            id = id, sourcePackage = sourcePackage, sourceName = sourcePackage,
            sourceKey = "$sourcePackage-$id", eventFingerprint = "fp-$id",
            title = title, body = "", postedAt = postedAt, category = category,
            priority = priority, confidence = 0.95f, isTrading = category == "TRADING",
            deliveryState = deliveryState, archived = archived
        )
}
