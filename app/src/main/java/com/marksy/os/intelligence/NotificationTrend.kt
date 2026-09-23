package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Real notification volume for the Home greeting card, computed from retained history. */
data class NotificationTrend(
    val today: Int,
    val yesterdaySoFar: Int,
    /** Oldest → today. */
    val lastSevenDays: List<Int>,
    val total: Int
) {
    /** Null when yesterday had nothing to compare against. */
    val changeVsYesterdayPercent: Int?
        get() = if (yesterdaySoFar == 0) null else ((today - yesterdaySoFar) * 100.0 / yesterdaySoFar).roundToInt()

    companion object {
        fun from(events: List<NotificationEventEntity>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): NotificationTrend {
            val postedAt = events.filterNot { it.archived }.map { it.postedAt }
            val todayDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val byDay = postedAt.groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.eachCount()
            // Compare against yesterday up to the same time of day, so mornings aren't always "down".
            val yesterdayStart = todayDate.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val yesterdayCutoff = nowMillis - 24 * 60 * 60 * 1000L
            return NotificationTrend(
                today = byDay[todayDate] ?: 0,
                yesterdaySoFar = postedAt.count { it in yesterdayStart..yesterdayCutoff },
                lastSevenDays = (6 downTo 0).map { byDay[todayDate.minusDays(it.toLong())] ?: 0 },
                total = postedAt.size
            )
        }
    }
}
