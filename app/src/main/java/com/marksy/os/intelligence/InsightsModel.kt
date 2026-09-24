package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import java.time.ZoneId
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

    fun inPeriod(events: List<NotificationEventEntity>, period: HomePeriod, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): List<NotificationEventEntity> {
        val since = period.startMillis(nowMillis, zone)
        return events.filter { !it.archived && (since == null || it.postedAt >= since) }
    }

    /** Category counts for the period, largest first; the tail and OTHER fold into one "OTHER" slice. */
    fun breakdown(
        events: List<NotificationEventEntity>,
        period: HomePeriod,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        maxSlices: Int = 6
    ): List<Pair<String, Int>> {
        val counts = inPeriod(events, period, nowMillis, zone)
            .groupingBy { it.category.ifBlank { "OTHER" }.uppercase() }.eachCount()
        val ranked = counts.filterKeys { it != "OTHER" }.toList().sortedByDescending { it.second }
        val head = ranked.take(maxSlices - 1)
        val rest = ranked.drop(maxSlices - 1).sumOf { it.second } + (counts["OTHER"] ?: 0)
        return if (rest > 0) head + ("OTHER" to rest) else head
    }

    fun categoryInsight(events: List<NotificationEventEntity>, category: String, singular: String, plural: String, nowMillis: Long): String? {
        val matching = events.filter { it.category.equals(category, ignoreCase = true) }
        if (matching.isEmpty()) return null
        val important = matching.count { EventIntelligence.analyze(it, nowMillis).attentionScore >= 70 }
        val sources = matching.groupingBy { it.sourceName.ifBlank { "Unknown source" } }.eachCount()
            .toList().sortedByDescending { it.second }.take(3)
        return buildString {
            append("${matching.size} ${if (matching.size == 1) singular else plural}")
            if (important > 0) append(", $important important")
            append(". Mostly from ")
            append(sources.joinToString { (name, count) -> "$name ($count)" })
            append(".")
        }
    }

    private fun hourOf(timestamp: Long): Int = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY)

    private fun formatHour(hour: Int): String = String.format(Locale.getDefault(), "%02d:00", hour)

    private fun isQuiet(events: List<NotificationEventEntity>, nowMillis: Long): Boolean =
        events.none { nowMillis - it.postedAt <= 2 * 60 * 60 * 1000L }
}
