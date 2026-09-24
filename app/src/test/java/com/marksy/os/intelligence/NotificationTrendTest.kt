package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class NotificationTrendTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 23, 18, 0).toInstant(zone).toEpochMilli()
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 9, day, hour, 0).toInstant(zone).toEpochMilli()
    private fun event(postedAt: Long, archived: Boolean = false) = NotificationEventEntity(
        sourcePackage = "p", sourceName = "s", sourceKey = "k$postedAt", eventFingerprint = "f$postedAt",
        title = "t", body = "b", postedAt = postedAt, category = "OTHER", priority = 1,
        confidence = 1f, isTrading = false, archived = archived
    )

    @Test
    fun comparesTodayWithYesterdayUpToSameTimeAndBuildsSevenDayBars() {
        val events = listOf(
            event(at(23, 9)), event(at(23, 10)), event(at(23, 11)),   // today: 3
            event(at(23, 12), archived = true),                        // archived: ignored
            event(at(22, 8)), event(at(22, 15)), event(at(22, 20)),   // yesterday: 2 before 18:00, 1 after
            event(at(17, 12))                                          // 6 days ago
        )
        val trend = NotificationTrend.from(events, now, zone)

        assertEquals(3, trend.today)
        assertEquals(2, trend.yesterdaySoFar)
        assertEquals(50, trend.changeVsYesterdayPercent)             // 3 vs 2 → +50%
        assertEquals(listOf(1, 0, 0, 0, 0, 3, 3), trend.lastSevenDays) // oldest → today
        assertEquals(7, trend.total)
    }

    @Test
    fun justAfterMidnightStillKnowsYesterdaysFullTotal() {
        val earlyMorning = LocalDateTime.of(2026, 9, 23, 0, 25).toInstant(zone).toEpochMilli()
        val trend = NotificationTrend.from(listOf(event(at(22, 9)), event(at(22, 20)), event(at(23, 0))), earlyMorning, zone)
        assertNull(trend.changeVsYesterdayPercent)  // nothing yet in yesterday's first 25 minutes
        assertEquals(2, trend.yesterdayTotal)
    }

    @Test
    fun noComparisonWhenYesterdayWasEmpty() {
        val trend = NotificationTrend.from(listOf(event(at(23, 9))), now, zone)
        assertNull(trend.changeVsYesterdayPercent)
    }
}
