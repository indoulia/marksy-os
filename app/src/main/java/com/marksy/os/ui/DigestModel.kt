package com.marksy.os.ui

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.EventIntelligence
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Deterministic local aggregation for the Daily Digest. No network or fabricated AI content. */
data class DailyDigest(
    val date: ZonedDateTime,
    val totalNotifications: Int,
    val attentionEvents: List<NotificationEventEntity>,
    val tradingEvents: List<NotificationEventEntity>,
    val deliveredTrading: Int,
    val failedTrading: Int,
    val pendingTrading: Int,
    val categoryCounts: Map<String, Int>,
    val topSources: List<Pair<String, Int>>
) {
    val title: String get() = "Today in 60 Seconds"

    fun shareText(): String = buildString {
        appendLine("Marksy Daily Digest · ${date.toLocalDate()}")
        appendLine("$totalNotifications notifications received")
        appendLine("${attentionEvents.size} required attention")
        if (tradingEvents.isNotEmpty()) appendLine("${tradingEvents.size} trading events")
        categoryCounts.forEach { (category, count) -> appendLine("${category.lowercase().replaceFirstChar { it.uppercase() }}: $count") }
        if (topSources.isNotEmpty()) append("Top sources: " + topSources.joinToString { (name, count) -> "$name ($count)" })
    }.trim()
}

object DailyDigestModel {
    fun build(
        events: List<NotificationEventEntity>,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): DailyDigest {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val start = now.toLocalDate().atStartOfDay(zone)
        val tomorrow = start.plusDays(1)

        val today = events.filter { event ->
            val posted = Instant.ofEpochMilli(event.postedAt).atZone(zone)
            !posted.isBefore(start) && posted.isBefore(tomorrow)
        }
        // Same threshold as Home's Important card so both screens agree.
        val attention = today
            .map { it to EventIntelligence.analyze(it, nowMillis).attentionScore }
            .filter { it.second >= ATTENTION_SCORE }
            .sortedWith(compareByDescending<Pair<NotificationEventEntity, Int>> { it.second }.thenByDescending { it.first.postedAt })
            .map { it.first }
        val trading = today.filter { it.isTrading }.sortedByDescending { it.postedAt }
        val categories = today.groupingBy { it.category.ifBlank { "OTHER" }.uppercase() }.eachCount().toList()
            .sortedByDescending { it.second }.take(5).toMap()
        val sources = today.groupingBy { it.sourceName.ifBlank { "Unknown source" } }.eachCount()
            .toList().sortedByDescending { it.second }.take(5)

        return DailyDigest(
            date = now,
            totalNotifications = today.size,
            attentionEvents = attention,
            tradingEvents = trading,
            deliveredTrading = trading.count { it.deliveryState == DeliveryState.DELIVERED.name },
            failedTrading = trading.count { it.deliveryState == DeliveryState.FAILED.name },
            pendingTrading = trading.count { it.deliveryState == DeliveryState.PENDING.name || it.deliveryState == DeliveryState.IN_FLIGHT.name },
            categoryCounts = categories,
            topSources = sources
        )
    }

    private const val ATTENTION_SCORE = 70
}
