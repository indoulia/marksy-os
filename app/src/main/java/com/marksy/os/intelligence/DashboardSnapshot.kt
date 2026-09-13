package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import java.util.concurrent.TimeUnit

/** Single deterministic snapshot for the Marksy OS home experience. */
data class DashboardSnapshot(
    val totalEvents: Int,
    val tradingEvents: Int,
    val importantEvents: Int,
    val criticalEvents: Int,
    val pendingTrading: Int,
    val failedTrading: Int,
    val deliveredTrading: Int,
    val topAttention: List<EventIntelligence.Result>,
    val categoryCounts: Map<String, Int>,
    val sourceCounts: Map<String, Int>,
    val latestTradingEventId: Long?,
    val latestEventId: Long?,
    val generatedAt: Long
) {
    val tradingHealth: TradingHealth
        get() = when {
            failedTrading > 0 -> TradingHealth.ACTION_REQUIRED
            pendingTrading > 0 -> TradingHealth.ANALYZING
            tradingEvents > 0 && deliveredTrading == tradingEvents -> TradingHealth.CLEAR
            tradingEvents == 0 -> TradingHealth.QUIET
            else -> TradingHealth.LOCAL
        }

    enum class TradingHealth { ACTION_REQUIRED, ANALYZING, CLEAR, QUIET, LOCAL }

    companion object {
        const val TOP_ATTENTION_LIMIT = 5

        fun from(events: List<NotificationEventEntity>, nowMillis: Long = System.currentTimeMillis()): DashboardSnapshot {
            val active = events.filterNot { it.archived }
            val byId = active.associateBy { it.id }
            val intelligence = active
                .map { EventIntelligence.analyze(it, nowMillis) }
                .sortedWith(
                    compareByDescending<EventIntelligence.Result> { it.attentionScore }
                        .thenByDescending { byId[it.eventId]?.postedAt ?: 0L }
                )

            val trading = active.filter { it.isTrading }
            val categoryCounts = active
                .groupingBy { it.category.trim().uppercase() }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .toMap()
            val sourceCounts = active
                .groupingBy { it.sourceName.ifBlank { "Unknown source" } }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .toMap()

            return DashboardSnapshot(
                totalEvents = active.size,
                tradingEvents = trading.size,
                importantEvents = intelligence.count { it.attentionScore >= IMPORTANT_THRESHOLD },
                criticalEvents = intelligence.count { it.attentionLevel == EventIntelligence.AttentionLevel.CRITICAL },
                pendingTrading = trading.count { it.deliveryState == DeliveryState.PENDING.name || it.deliveryState == DeliveryState.IN_FLIGHT.name },
                failedTrading = trading.count { it.deliveryState == DeliveryState.FAILED.name },
                deliveredTrading = trading.count { it.deliveryState == DeliveryState.DELIVERED.name },
                topAttention = intelligence.take(TOP_ATTENTION_LIMIT),
                categoryCounts = categoryCounts,
                sourceCounts = sourceCounts,
                latestTradingEventId = trading.maxByOrNull { it.postedAt }?.id,
                latestEventId = active.maxByOrNull { it.postedAt }?.id,
                generatedAt = nowMillis
            )
        }

        private const val IMPORTANT_THRESHOLD = 70
    }
}

fun dashboardAgeLabel(eventPostedAt: Long, nowMillis: Long): String {
    val age = (nowMillis - eventPostedAt).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(age)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}
