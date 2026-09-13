package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import java.util.Calendar
import java.util.Locale

/** Deterministic local patterns derived from retained active events; no network or LLM required. */
object InsightsModel {
    data class Snapshot(
        val eventCount: Int,
        val meaningfulCount: Int,
        val tradingCount: Int,
        val attentionCount: Int,
        val deliveredTrading: Int,
        val failedTrading: Int,
        val topCategory: String?,
        val topSource: String?,
        val busiestHour: Int?,
        val attentionRate: Int,
        val tradingDeliveryRate: Int?,
        val observations: List<String>
    )

    fun from(events: List<NotificationEventEntity>, nowMillis: Long = System.currentTimeMillis()): Snapshot {
        val active = events.filterNot { it.archived }
        val meaningful = active.filter { it.category != "OTHER" }
        val trading = meaningful.filter { it.isTrading }
        val attention = meaningful.count { EventIntelligence.analyze(it, nowMillis).attentionScore >= 70 }
        val delivered = trading.count { it.deliveryState == DeliveryState.DELIVERED.name }
        val failed = trading.count { it.deliveryState == DeliveryState.FAILED.name }
        val topCategory = meaningful.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key
        val topSource = meaningful.groupingBy { it.sourceName.ifBlank { "Unknown source" } }.eachCount().maxByOrNull { it.value }?.key
        val hourCounts = meaningful.groupingBy { hourOf(it.postedAt) }.eachCount()
        val busiestHour = hourCounts.maxByOrNull { it.value }?.key
        val attentionRate = if (meaningful.isEmpty()) 0 else (attention * 100 / meaningful.size).coerceIn(0, 100)
        val deliveryRate = if (trading.isEmpty()) null else (delivered * 100 / trading.size).coerceIn(0, 100)

        val observations = buildList {
            topCategory?.let { add("$it is your most frequent meaningful notification category.") }
            topSource?.let { add("$it is currently your busiest meaningful source.") }
            busiestHour?.let { add("Activity peaks around ${formatHour(it)}.") }
            if (attentionRate > 0) add("$attentionRate% of meaningful events currently deserve elevated attention.")
            if (trading.isNotEmpty()) {
                when {
                    failed > 0 -> add("$failed trading event${if (failed == 1) "" else "s"} still need delivery attention.")
                    delivered == trading.size -> add("All retained trading events have a Marksy response recorded.")
                    else -> add("Trading intelligence is still being processed for ${trading.size - delivered} event${if (trading.size - delivered == 1) "" else "s"}.")
                }
            }
            if (isQuiet(meaningful, nowMillis)) add("There has been little meaningful activity recently.")
        }

        return Snapshot(active.size, meaningful.size, trading.size, attention, delivered, failed, topCategory, topSource, busiestHour, attentionRate, deliveryRate, observations)
    }

    private fun hourOf(timestamp: Long): Int = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY)

    private fun formatHour(hour: Int): String = String.format(Locale.getDefault(), "%02d:00", hour)

    private fun isQuiet(events: List<NotificationEventEntity>, nowMillis: Long): Boolean =
        events.none { nowMillis - it.postedAt <= 2 * 60 * 60 * 1000L }
}
