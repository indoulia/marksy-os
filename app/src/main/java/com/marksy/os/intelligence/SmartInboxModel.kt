package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity

/** Presentation-ready grouping for Smart Inbox. All decisions remain deterministic and local. */
object SmartInboxModel {
    enum class Filter(val label: String, val category: String? = null) {
        ALL("All"), TRADING("Trading", "TRADING"), MESSAGES("Messages", "MESSAGES"),
        PAYMENTS("Payments", "PAYMENTS"), BANKING("Banking", "BANKING"), BILLS("Bills", "BILLS"),
        WORK("Work", "WORK"), DELIVERY("Delivery", "DELIVERY")
    }

    data class Thread(val key: String, val events: List<NotificationEventEntity>, val intelligence: List<EventIntelligence.Result>) {
        val latest: NotificationEventEntity get() = events.first()
        val count: Int get() = events.size
        val highestAttention: Int get() = intelligence.maxOfOrNull { it.attentionScore } ?: 0
        val primaryReason: String get() = intelligence.maxByOrNull { it.attentionScore }?.reasons?.firstOrNull() ?: "Standard notification"
    }

    data class Sectioned(val needsAttention: List<Thread>, val recent: List<Thread>, val quiet: List<Thread>)

    fun filter(events: List<NotificationEventEntity>, filter: Filter): List<NotificationEventEntity> =
        filter.category?.let { category -> events.filter { it.category == category } } ?: events

    fun section(events: List<NotificationEventEntity>, nowMillis: Long = System.currentTimeMillis()): Sectioned {
        val threads = EventIntelligence.groupByThread(events, nowMillis).map { (key, intelligence) ->
            val ids = intelligence.map { it.eventId }.toSet()
            Thread(key, events.filter { it.id in ids }.sortedByDescending { it.postedAt }, intelligence.sortedByDescending { it.attentionScore })
        }.sortedWith(compareByDescending<Thread> { it.highestAttention }.thenByDescending { it.latest.postedAt })
        val needs = threads.filter { it.highestAttention >= ATTENTION_THRESHOLD }
        val recent = threads.filter { it.highestAttention < ATTENTION_THRESHOLD && nowMillis - it.latest.postedAt <= RECENT_WINDOW_MS }
        val quiet = threads.filter { it !in needs && it !in recent }
        return Sectioned(needs, recent, quiet)
    }

    private const val ATTENTION_THRESHOLD = 70
    private const val RECENT_WINDOW_MS = 2 * 60 * 60 * 1000L
}
