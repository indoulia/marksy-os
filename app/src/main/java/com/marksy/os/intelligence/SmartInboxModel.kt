package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity

/** Presentation-ready grouping for Smart Inbox. All decisions remain deterministic and local. */
object SmartInboxModel {
    enum class Filter(val label: String, val category: String? = null) {
        ALL("All"), TRADING("Trading", "TRADING"), MESSAGES("Messages", "MESSAGES"),
        EMAIL("Emails", "EMAIL"), PAYMENTS("Payments", "PAYMENTS"), BANKING("Banking", "BANKING"),
        BILLS("Bills", "BILLS"), WORK("Work", "WORK"), DELIVERY("Delivery", "DELIVERY");

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
        val unread: Boolean get() = events.any { it.lifecycleState == EventLifecycle.State.NEW.name }
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

    /** Deterministic bucket; the returned string is shown verbatim in "Why am I seeing this?". */
    internal fun bucketFor(events: List<NotificationEventEntity>, attention: Int, nowMillis: Long): Pair<Bucket, String> {
        val open = events.filter { it.lifecycleState != EventLifecycle.State.RESOLVED.name }
        if (open.isEmpty()) return Bucket.RESOLVED to "Resolved"
        val latest = open.maxBy { it.postedAt }
        val text = "${latest.title} ${latest.body}".lowercase()
        val actionReason = when {
            open.any { it.isTrading && it.deliveryState == "FAILED" } -> "Marksy delivery failed and needs a retry"
            latest.category == "BILLS" -> "Bill that may need payment"
            latest.category == "REMINDERS" -> "Reminder you set"
            latest.category == "OTP" && nowMillis - latest.postedAt <= OTP_ACTION_WINDOW_MS -> "Fresh one-time code"
            latest.category in setOf("PAYMENTS", "BANKING") && FAILURE_TERMS.any { text.contains(it) } -> "A payment or transaction failed"
            else -> null
        }
        return when {
            attention >= PRIORITY_THRESHOLD && actionReason == null -> Bucket.PRIORITY to "Very high attention score ($attention)"
            actionReason != null -> Bucket.NEEDS_ACTION to actionReason
            attention >= IMPORTANT_THRESHOLD -> Bucket.IMPORTANT to "Attention score $attention"
            else -> Bucket.INFORMATIONAL to "Informational (attention $attention)"
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
    private const val OTP_ACTION_WINDOW_MS = 10 * 60 * 1000L
    private val FAILURE_TERMS = listOf("failed", "declined", "unsuccessful", "reversed")

    private const val ATTENTION_THRESHOLD = 70
    private const val RECENT_WINDOW_MS = 2 * 60 * 60 * 1000L
}
