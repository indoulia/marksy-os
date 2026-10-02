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

    private val hour = 60 * 60 * 1000L

    private fun msg(
        id: Long, pkg: String, title: String, body: String, postedAt: Long,
        category: String = "MESSAGES", priority: Int = 50, read: Boolean = false
    ) = NotificationEventEntity(
        id = id, sourcePackage = pkg, sourceName = SOURCE_NAMES[pkg] ?: pkg,
        sourceKey = "$pkg-$id", eventFingerprint = "fp-$id", title = title, body = body,
        postedAt = postedAt, category = category, priority = priority, confidence = 0.95f,
        isTrading = category == "TRADING", isRead = read, lifecycleState = if (read) "ACTIVE" else "NEW"
    )

    private fun bucketsOf(vararg events: NotificationEventEntity, at: Long = now) =
        SmartInboxModel.inbox(events.toList(), nowMillis = at).sections.flatMap { (b, ts) -> ts.map { it.latest.id to b } }.toMap()

    @Test
    fun rejectedOrdersAndFailedDeliveriesNeedAction() {
        val buckets = bucketsOf(
            msg(1, "com.zerodha.kite3", "Order rejected", "BUY 10 INFY rejected: insufficient margin", now - hour, category = "TRADING"),
            msg(2, "com.delhivery", "Delivery attempt failed", "AWB 1490: you were not available", now - hour, category = "DELIVERY"),
            msg(3, "com.delhivery", "Delivered", "Your parcel was delivered", now - hour, category = "DELIVERY")
        )
        assertEquals(SmartInboxModel.Bucket.NEEDS_ACTION, buckets[1L])
        assertEquals(SmartInboxModel.Bucket.NEEDS_ACTION, buckets[2L])
        assertTrue(buckets[3L] != SmartInboxModel.Bucket.NEEDS_ACTION)
    }

    @Test
    fun lanesSeparateNeedsYouNewAndEarlier() {
        val lanes = SmartInboxModel.lanes(
            listOf(
                msg(1, "com.power", "Electricity bill", "Due on 4 Oct", now - hour, category = "BILLS"),
                msg(2, WA, "Mom", "Call me", now - hour),
                msg(3, WA, "College gang", "Plan for Sunday?", now - 30 * hour),
                msg(4, WA, "Papa", "Ok", now - 2 * hour, read = true)
            ),
            nowMillis = now
        )
        assertEquals(listOf(1L), lanes.needsYou.map { it.thread.latest.id })
        assertEquals(SmartInboxModel.NeedReason("Bill due", SmartInboxModel.Urgency.DUE), lanes.needsYou.single().reason)
        assertEquals(listOf(2L), lanes.fresh.flatMap { it.threads }.map { it.latest.id })
        assertEquals(setOf(3L, 4L), lanes.earlier.flatMap { it.threads }.map { it.latest.id }.toSet())
        assertEquals(SmartInboxModel.InboxSummary(needsYou = 1, newUnread = 1), lanes.summary)
    }

    @Test
    fun readThisVisitStaysInNewUntilTheNextVisit() {
        val read = msg(1, WA, "Mom", "Call me", now - hour, read = true)
        val key = EventIntelligence.threadKey(read)
        assertEquals(1, SmartInboxModel.lanes(listOf(read), nowMillis = now, seenThisVisit = setOf(key)).fresh.size)
        assertEquals(1, SmartInboxModel.lanes(listOf(read), nowMillis = now).earlier.size)
    }

    @Test
    fun oneStackPerAppButSmsSplitsBySender() {
        val lanes = SmartInboxModel.lanes(
            listOf(
                msg(1, WA, "Mom", "Call me", now - hour),
                msg(2, WA, "Platform team", "Build is green", now - 2 * hour),
                msg(3, SMS, "HDFC Bank", "Rs 640 debited to SWIGGY", now - hour, category = "PAYMENTS"),
                msg(4, SMS, "Airtel", "Data pack renewed", now - hour, category = "OTHER")
            ),
            nowMillis = now
        )
        assertEquals(mapOf("WhatsApp" to 2, "HDFC Bank" to 1, "Airtel" to 1), lanes.fresh.associate { it.label to it.threads.size })
        assertTrue(lanes.fresh.single { it.label == "HDFC Bank" }.isSms)
    }

    @Test
    fun rowsInAStackRankByAttentionThenRecency() {
        val lanes = SmartInboxModel.lanes(
            listOf(
                msg(1, WA, "Residents", "Water off Saturday", now - 10 * 60_000, priority = 20),
                msg(2, WA, "Mom", "Call me", now - 2 * hour, priority = 70)
            ),
            nowMillis = now
        )
        assertEquals(listOf("Mom", "Residents"), lanes.fresh.single().threads.map { it.latest.title })
    }

    @Test
    fun alwaysImportantSenderIsPinnedToNeedsYou() {
        val e = msg(1, WA, "Mom", "Call me", now - hour, priority = 30)
        val subject = PersonalLearning.subjectsOf(e).first { it.type != PersonalLearning.SubjectType.CATEGORY }
        val profile = PersonalLearning.Profile(
            mapOf(
                (subject.type to subject.key) to PersonalLearning.SubjectProfile(
                    subject, positive = 0, negative = 0, neutral = 0, lastObservedAt = now, adjustment = 25,
                    confidence = 1f, override = PersonalLearning.Preference.ALWAYS_IMPORTANT, reason = "You marked this important"
                )
            )
        )
        val need = SmartInboxModel.lanes(listOf(e), nowMillis = now, profile = profile).needsYou.single()
        assertEquals(SmartInboxModel.NeedReason("Always important", SmartInboxModel.Urgency.FLAGGED), need.reason)
    }

    @Test
    fun failedOrdersAndDeliveriesShowFailureChips() {
        val lanes = SmartInboxModel.lanes(
            listOf(
                msg(1, "com.zerodha.kite3", "Order rejected", "BUY 10 INFY rejected: insufficient margin", now - hour, category = "TRADING"),
                msg(2, "com.delhivery", "Delivery attempt failed", "AWB 1490: you were not available", now - hour, category = "DELIVERY")
            ),
            nowMillis = now
        )
        assertEquals(setOf("Order rejected", "Delivery failed"), lanes.needsYou.map { it.reason.chip }.toSet())
        assertTrue(lanes.needsYou.all { it.reason.urgency == SmartInboxModel.Urgency.FAILURE })
    }

    // Device 2026-10-02: 51 day-old trading items flooded Needs you; a bare high score needs the user only while fresh and unread.
    @Test
    fun highAttentionAloneNeedsYouOnlyWhileFreshAndUnread() {
        val hot = msg(1, "com.zerodha.kite3", "Order executed", "SELL 18 NATSEC executed at 410", now - hour, category = "TRADING", priority = 100)
        assertEquals(SmartInboxModel.NeedReason("High attention", SmartInboxModel.Urgency.FLAGGED), SmartInboxModel.lanes(listOf(hot), nowMillis = now).needsYou.single().reason)
        val read = hot.copy(isRead = true, lifecycleState = "ACTIVE")
        assertTrue(SmartInboxModel.lanes(listOf(read), nowMillis = now).needsYou.isEmpty())
        assertEquals(1, SmartInboxModel.lanes(listOf(read), nowMillis = now, seenThisVisit = setOf(EventIntelligence.threadKey(read))).needsYou.size)
        assertTrue(SmartInboxModel.lanes(listOf(hot), nowMillis = now + 30 * hour).needsYou.isEmpty())
    }

    // Review minor (re-graded): a tip saying "rejected" is not the user's order being rejected.
    @Test
    fun tipSayingRejectedIsNoOrderRejection() {
        val tip = msg(1, SMS, "TIPS", "NIFTY rejected from 24600, SELL below 24500 target 24400 SL 24650", now - hour, category = "TRADING")
        assertTrue(SmartInboxModel.lanes(listOf(tip), nowMillis = now).needsYou.none { it.reason.chip == "Order rejected" })
    }

    // Review I3: opening a thread pins only New and Needs-you threads; one opened from Earlier stays in Earlier.
    @Test
    fun threadOpenedFromEarlierStaysInEarlier() {
        val old = msg(1, WA, "Papa", "Ok", now - 2 * hour, read = true)
        val fresh = msg(2, WA, "Mom", "Call me", now - hour)
        val lanes = SmartInboxModel.lanes(listOf(old, fresh), nowMillis = now)
        val oldKey = EventIntelligence.threadKey(old)
        val freshKey = EventIntelligence.threadKey(fresh)
        assertTrue(!lanes.holdsOnOpen(oldKey))
        assertTrue(lanes.holdsOnOpen(freshKey))
        val seen = setOf(oldKey, freshKey).filter(lanes::holdsOnOpen).toSet()
        val after = SmartInboxModel.lanes(listOf(old, fresh.copy(isRead = true, lifecycleState = "ACTIVE")), nowMillis = now, seenThisVisit = seen)
        assertEquals(listOf(1L), after.earlier.flatMap { it.threads }.map { it.latest.id })
        assertEquals(listOf(2L), after.fresh.flatMap { it.threads }.map { it.latest.id })
    }

    companion object {
        const val WA = "com.whatsapp"
        const val SMS = "com.google.android.apps.messaging"
        val SOURCE_NAMES = mapOf(WA to "WhatsApp", SMS to "Messages")
    }
}
