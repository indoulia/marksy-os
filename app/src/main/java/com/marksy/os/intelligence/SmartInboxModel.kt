package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.notification.CaptureMedium
import com.marksy.os.notification.NotificationClassifier
import java.util.Locale

/** Presentation-ready grouping for Smart Inbox. All decisions remain deterministic and local. */
object SmartInboxModel {
    enum class Filter(val label: String, val category: String? = null, val sourceKeyword: String? = null) {
        ALL("All"), TRADING("Trading", "TRADING"), MARKET("Market", "MARKET"), MESSAGES("Messages", "MESSAGES"),
        EMAIL("Emails", "EMAIL"), PAYMENTS("Payments", "PAYMENTS"), BANKING("Banking", "BANKING"),
        BILLS("Bills", "BILLS"), REMINDERS("Reminders", "REMINDERS"), WORK("Work", "WORK"), DELIVERY("Delivery", "DELIVERY"),
        TEAMS("Teams", sourceKeyword = "teams");

        companion object {
            /** Maps a Home dashboard tile label to its inbox filter; unmatched labels fall back to ALL. */
            fun forCategoryLabel(label: String): Filter =
                entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: ALL
        }
    }

    data class Thread(
        val key: String,
        val events: List<NotificationEventEntity>,
        val intelligence: List<EventIntelligence.Result>
    ) {
        val latest: NotificationEventEntity get() = events.first()
        val count: Int get() = events.size
        val highestAttention: Int get() = intelligence.maxOfOrNull { it.attentionScore } ?: 0
        val attentionLevel: EventIntelligence.AttentionLevel
            get() = intelligence.maxByOrNull { it.attentionScore }?.attentionLevel
                ?: EventIntelligence.AttentionLevel.LOW
        val primaryReason: String
            get() = intelligence
                .maxByOrNull { it.attentionScore }
                ?.reasons
                ?.firstOrNull()
                ?: "Standard notification"
        val isTrading: Boolean get() = events.any { it.isTrading }
        val hasDeliveryFailure: Boolean get() = events.any { it.isTrading && it.deliveryState == "FAILED" }
    }

    data class Sectioned(
        val needsAttention: List<Thread>,
        val recent: List<Thread>,
        val quiet: List<Thread>
    )

    /** Archived events never re-enter the active inbox. */
    fun filter(events: List<NotificationEventEntity>, filter: Filter): List<NotificationEventEntity> {
        val active = events.filterNot { it.archived }
        filter.sourceKeyword?.let { keyword -> return active.filter { isFromSource(it, keyword) } }
        return filter.category?.let { category -> active.filter { it.category == category } } ?: active
    }

    fun section(events: List<NotificationEventEntity>, nowMillis: Long = System.currentTimeMillis()): Sectioned {
        val active = events.filterNot { it.archived }
        if (active.isEmpty()) return Sectioned(emptyList(), emptyList(), emptyList())

        val byId = active.associateBy { it.id }
        val threads = EventIntelligence.groupByThread(active, nowMillis)
            .map { (key, intelligence) ->
                val orderedIds = intelligence
                    .sortedWith(compareByDescending<EventIntelligence.Result> { it.attentionScore }
                        .thenByDescending { byId[it.eventId]?.postedAt ?: 0L })
                    .map { it.eventId }
                val threadEvents = orderedIds
                    .mapNotNull { byId[it] }
                    .sortedByDescending { it.postedAt }
                Thread(key, threadEvents, intelligence.sortedByDescending { it.attentionScore })
            }
            .sortedWith(compareByDescending<Thread> { it.highestAttention }.thenByDescending { it.latest.postedAt })

        val needs = threads.filter { it.highestAttention >= ATTENTION_THRESHOLD }
        val recent = threads.filter {
            it.highestAttention < ATTENTION_THRESHOLD &&
                nowMillis - it.latest.postedAt in 0..RECENT_WINDOW_MS
        }
        val quiet = threads.filter { it !in needs && it !in recent }
        return Sectioned(needs, recent, quiet)
    }

    fun isFromSource(event: NotificationEventEntity, keyword: String): Boolean =
        event.sourcePackage.contains(keyword, ignoreCase = true) || event.sourceName.contains(keyword, ignoreCase = true)

    // ---- EPIC-011 intelligence inbox -------------------------------------------------

    enum class Bucket(val label: String) {
        PRIORITY("Priority"), NEEDS_ACTION("Needs action"), IMPORTANT("Important"),
        INFORMATIONAL("Informational"), RESOLVED("Resolved")
    }

    /**
     * One conversation/item across sources. [events] are canonical events newest first;
     * cross-source duplicates are folded into [duplicates] instead of appearing twice.
     */
    data class InboxThread(
        val key: String,
        val events: List<NotificationEventEntity>,
        val duplicates: List<NotificationEventEntity>,
        val bucket: Bucket,
        val attentionScore: Int,
        val why: List<String>,
        val prediction: AdaptiveRanker.Prediction = AdaptiveRanker.Prediction.UNCERTAIN
    ) {
        val latest: NotificationEventEntity get() = events.first()
        val count: Int get() = events.size
        // isRead (inbox UI) and lifecycle NEW (EPIC-010) are kept in step; either marks the thread unread.
        val unread: Boolean get() = events.any { !it.isRead || it.lifecycleState == EventLifecycle.State.NEW.name }
        // Same cut-off as EventIntelligence's CRITICAL level.
        val critical: Boolean get() = attentionScore >= 90
        val sources: List<String> get() = (events + duplicates).map { it.sourceName }.distinct()
        /** Every row the thread represents, so an action on the thread also covers folded duplicates. */
        val allIds: List<Long> get() = (events + duplicates).map { it.id }
    }

    data class Inbox(val sections: Map<Bucket, List<InboxThread>>, val snoozedCount: Int) {
        val isEmpty: Boolean get() = sections.values.all { it.isEmpty() }
    }

    fun inbox(
        events: List<NotificationEventEntity>,
        filter: Filter = Filter.ALL,
        query: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        profile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Inbox {
        val active = filter(events, filter)
        val (snoozed, visible) = active.partition { (it.snoozedUntil ?: 0L) > nowMillis }
        val matched = if (query.isBlank()) visible else visible.filter { matches(it, query.trim()) }

        val ids = matched.mapTo(HashSet()) { it.id }
        // A duplicate is only folded when its canonical is on screen; otherwise it stands in for it.
        val (dupes, canonical) = matched.partition { d -> d.duplicateOfId != null && d.duplicateOfId in ids }
        val dupesByCanonical = dupes.groupBy { it.duplicateOfId!! }
        // Context from the whole unfiltered feed: fatigue is about how much a source sends, not what matches a filter.
        val ctx = AdaptiveRanker.Context.from(events.filterNot { it.archived }, nowMillis, profile, zone)

        val threads = canonical
            .groupBy { EventIntelligence.threadKey(it) }
            .map { (key, group) -> buildThread(key, group.sortedByDescending { it.postedAt }, dupesByCanonical, ctx) }
            .sortedWith(compareByDescending<InboxThread> { it.attentionScore }.thenByDescending { it.latest.postedAt })

        val sections = Bucket.entries.associateWith { b -> threads.filter { it.bucket == b } }
        val snoozedThreads = snoozed.map { EventIntelligence.threadKey(it) }.distinct().size
        return Inbox(sections, snoozedThreads)
    }

    data class NeedThread(val thread: InboxThread, val reason: NeedReason)

    data class SourceStack(val key: String, val label: String, val isSms: Boolean, val threads: List<InboxThread>) {
        val unread: Int get() = threads.count { it.unread }
        val latestAt: Long get() = threads.maxOf { it.latest.postedAt }
        val topAttention: Int get() = threads.maxOf { it.attentionScore }
        /** Every row in the group, folded duplicates included, for whole-group actions. */
        val allRows: List<NotificationEventEntity> get() = threads.flatMap { it.events + it.duplicates }
    }

    /** [events] as Inbox's source groups, for Home; [byRecency] orders groups newest first instead of by attention. */
    fun sourceStacks(events: List<NotificationEventEntity>, nowMillis: Long = System.currentTimeMillis(), byRecency: Boolean = false): List<SourceStack> {
        val grouped = stacks(inbox(events, nowMillis = nowMillis).sections.values.flatten())
        return if (byRecency) grouped.sortedByDescending { it.latestAt } else grouped
    }

    data class InboxSummary(val needsYou: Int, val newUnread: Int)

    data class Lanes(
        val needsYou: List<NeedThread>,
        val fresh: List<SourceStack>,
        val earlier: List<SourceStack>,
        val snoozedCount: Int
    ) {
        val isEmpty: Boolean get() = needsYou.isEmpty() && fresh.isEmpty() && earlier.isEmpty()
        val summary: InboxSummary get() = InboxSummary(needsYou.size, fresh.sumOf { it.unread })
        /** Only New and Needs-you threads keep their place once opened; an Earlier thread must not jump up into New. */
        fun holdsOnOpen(threadKey: String): Boolean =
            needsYou.any { it.thread.key == threadKey } || fresh.any { s -> s.threads.any { it.key == threadKey } }
    }

    /** [seenThisVisit] holds thread keys opened since the user entered the tab; they keep their lane until the next visit. */
    fun lanes(
        events: List<NotificationEventEntity>,
        filter: Filter = Filter.ALL,
        query: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        profile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY,
        seenThisVisit: Set<String> = emptySet(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Lanes {
        val inbox = inbox(events, filter, query, nowMillis, profile, zone)
        val needs = mutableListOf<NeedThread>()
        val rest = mutableListOf<InboxThread>()
        inbox.sections.filterKeys { it != Bucket.RESOLVED }.values.flatten().forEach { thread ->
            val reason = needReason(thread, profile, nowMillis, seenThisVisit)
            if (reason == null) rest += thread
            else needs += NeedThread(thread, reason)
        }
        val (fresh, earlier) = rest.partition { t ->
            nowMillis - t.latest.postedAt < NEW_WINDOW_MS && (t.unread || t.key in seenThisVisit)
        }
        return Lanes(
            needs.sortedWith(compareByDescending<NeedThread> { it.thread.attentionScore }.thenByDescending { it.thread.latest.postedAt }),
            stacks(fresh), stacks(earlier), inbox.snoozedCount
        )
    }

    // Action beats an explicit "Always important", which beats a bare high score: the chip names the most useful reason.
    private fun needReason(thread: InboxThread, profile: PersonalLearning.Profile, nowMillis: Long, seenThisVisit: Set<String>): NeedReason? {
        val open = thread.events.filter { it.lifecycleState != EventLifecycle.State.RESOLVED.name }
        actionFor(open, nowMillis)?.let { return it.second }
        val pinned = PersonalLearning.subjectsOf(thread.latest)
            .any { profile.of(it.type, it.key)?.override == PersonalLearning.Preference.ALWAYS_IMPORTANT }
        if (pinned) return NeedReason("Always important", Urgency.FLAGGED)
        // A bare high score is no task (device: 51 day-old trading items flooded the lane), so it needs the user only while fresh and unread.
        val fresh = nowMillis - thread.latest.postedAt < NEW_WINDOW_MS && (thread.unread || thread.key in seenThisVisit)
        return if (thread.bucket == Bucket.PRIORITY && fresh) NeedReason("High attention", Urgency.FLAGGED) else null
    }

    fun isSms(event: NotificationEventEntity): Boolean = CaptureMedium.of(event.sourcePackage) == CaptureMedium.SMS

    // One SMS app carries the bank, the telco and the courier, so SMS stacks by sender; every other app is one stack.
    private fun stackKey(event: NotificationEventEntity): String {
        val pkg = event.sourcePackage.trim().lowercase(Locale.ROOT)
        return if (isSms(event)) "$pkg|${event.title.trim().lowercase(Locale.ROOT)}" else pkg
    }

    private fun stacks(threads: List<InboxThread>): List<SourceStack> =
        threads.groupBy { stackKey(it.latest) }
            .map { (key, group) ->
                val latest = group.maxBy { it.latest.postedAt }.latest
                val sms = isSms(latest)
                val label = if (sms) latest.title.ifBlank { latest.sourceName } else latest.sourceName.ifBlank { "System" }
                SourceStack(key, label, sms, group.sortedWith(compareByDescending<InboxThread> { it.unread }.thenByDescending { it.latest.postedAt }))
            }
            .sortedWith(compareByDescending<SourceStack> { it.topAttention }.thenByDescending { it.latestAt })

    private fun buildThread(
        key: String,
        events: List<NotificationEventEntity>,
        dupesByCanonical: Map<Long, List<NotificationEventEntity>>,
        ctx: AdaptiveRanker.Context
    ): InboxThread {
        val ranked = events.map { AdaptiveRanker.rank(it, ctx) }
        val top = ranked.maxBy { it.score }
        val topEvent = events.first { it.id == top.eventId }
        val duplicates = events.flatMap { dupesByCanonical[it.id].orEmpty() }
        val (bucket, bucketReason) = bucketFor(events, top.score, ctx.nowMillis)

        val why = buildList {
            add(bucketReason)
            addAll(top.reasons)
            addAll(EventNormalizer.reasonsFromJson(topEvent.intelligenceJson))
            if (events.size > 1) add("${events.size} related events grouped")
            if (duplicates.isNotEmpty()) add("Same item also reported by ${duplicates.map { it.sourceName }.distinct().joinToString()}")
            topEvent.lifecycleReason?.let(::add)
        }.distinct()
        return InboxThread(key, events, duplicates, bucket, top.score, why, top.prediction)
    }

    enum class Urgency { FAILURE, DUE, FLAGGED }
    data class NeedReason(val chip: String, val urgency: Urgency)

    /** Deterministic bucket; the returned string is shown verbatim in "Why am I seeing this?". */
    internal fun bucketFor(events: List<NotificationEventEntity>, attention: Int, nowMillis: Long): Pair<Bucket, String> {
        val open = events.filter { it.lifecycleState != EventLifecycle.State.RESOLVED.name }
        if (open.isEmpty()) return Bucket.RESOLVED to "Resolved"
        val action = actionFor(open, nowMillis)
        return when {
            attention >= PRIORITY_THRESHOLD && action == null -> Bucket.PRIORITY to "Very high attention score ($attention)"
            action != null -> Bucket.NEEDS_ACTION to action.first
            attention >= IMPORTANT_THRESHOLD -> Bucket.IMPORTANT to "Attention score $attention"
            else -> Bucket.INFORMATIONAL to "Informational (attention $attention)"
        }
    }

    /** Why-string plus the short chip for anything the user should act on now; null when nothing is due. */
    internal fun actionFor(open: List<NotificationEventEntity>, nowMillis: Long): Pair<String, NeedReason>? {
        if (open.isEmpty()) return null
        val latest = open.maxBy { it.postedAt }
        val text = "${latest.title} ${latest.body}".lowercase()
        return when {
            open.any { it.isTrading && it.deliveryState == "FAILED" } -> "Marksy delivery failed and needs a retry" to NeedReason("Retry needed", Urgency.FAILURE)
            latest.category == "BILLS" -> "Bill that may need payment" to NeedReason("Bill due", Urgency.DUE)
            latest.category == "REMINDERS" -> "Due or reminder to act on" to NeedReason("Reminder", Urgency.DUE)
            latest.category in setOf("PAYMENTS", "BANKING") && FAILURE_TERMS.any { text.contains(it) } -> "A payment or transaction failed" to NeedReason("Payment failed", Urgency.FAILURE)
            latest.category == "TRADING" && text.contains("rejected") && NotificationClassifier.isOwnOrderEvent(latest.title, latest.body) -> "Your order was rejected" to NeedReason("Order rejected", Urgency.FAILURE)
            latest.category == "DELIVERY" && DELIVERY_FAILURE_TERMS.any { text.contains(it) } -> "A delivery attempt failed" to NeedReason("Delivery failed", Urgency.FAILURE)
            else -> null
        }
    }

    /** Searches raw text plus extracted entities/references, so "Delhivery" or an order id finds the thread. */
    private fun matches(event: NotificationEventEntity, query: String): Boolean {
        if (event.title.contains(query, true) || event.body.contains(query, true) || event.sourceName.contains(query, true)) return true
        val facts = EventNormalizer.factsFromJson(event.intelligenceJson)
        return facts.entities.any { it.value.contains(query, true) } || facts.references.any { it.value.contains(query, true) }
    }

    private const val PRIORITY_THRESHOLD = 90
    private const val IMPORTANT_THRESHOLD = 60
    private val FAILURE_TERMS = listOf("failed", "declined", "unsuccessful", "reversed")
    private val DELIVERY_FAILURE_TERMS = listOf("failed", "unsuccessful", "undelivered", "could not be delivered")

    private const val ATTENTION_THRESHOLD = 70
    private const val RECENT_WINDOW_MS = 2 * 60 * 60 * 1000L
    private const val NEW_WINDOW_MS = 24 * 60 * 60 * 1000L
}
