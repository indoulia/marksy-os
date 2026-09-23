package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity

/** Presentation-ready grouping for Smart Inbox. All decisions remain deterministic and local. */
object SmartInboxModel {
    enum class Filter(val label: String, val category: String? = null, val sourceKeyword: String? = null) {
        ALL("All"), TRADING("Trading", "TRADING"), MESSAGES("Messages", "MESSAGES"),
        EMAIL("Emails", "EMAIL"), PAYMENTS("Payments", "PAYMENTS"), BANKING("Banking", "BANKING"),
        BILLS("Bills", "BILLS"), WORK("Work", "WORK"), DELIVERY("Delivery", "DELIVERY"),
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

    private const val ATTENTION_THRESHOLD = 70
    private const val RECENT_WINDOW_MS = 2 * 60 * 60 * 1000L
}
