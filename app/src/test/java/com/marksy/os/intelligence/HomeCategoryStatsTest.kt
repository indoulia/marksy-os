package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class HomeCategoryStatsTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 23, 18, 0).toInstant(zone).toEpochMilli()
    private fun at(day: Int) = LocalDateTime.of(2026, 9, day, 10, 0).toInstant(zone).toEpochMilli()
    private var seq = 0
    private fun event(category: String, day: Int, source: String = "App", trading: Boolean = false) = NotificationEventEntity(
        sourcePackage = "p", sourceName = source, sourceKey = "k${seq++}", eventFingerprint = "f$seq",
        title = "t", body = "b", postedAt = at(day), category = category, priority = 1,
        confidence = 1f, isTrading = trading
    )

    // Regression: subtitles were hard-coded ("4 high priority", "Arriving tomorrow") regardless of data.
    @Test
    fun countsFollowThePeriodAndSubtitlesDescribeRealData() {
        val events = listOf(
            event("DELIVERY", 23, source = "Swiggy"),
            event("DELIVERY", 20, source = "Amazon"),
            event("EMAIL", 10, source = "Gmail")
        )

        val today = HomeCategoryStats.from(events, now, HomePeriod.TODAY, zone)
        assertEquals(1, today.delivery.count)
        assertEquals("Latest: Swiggy", today.delivery.subtitle)
        assertEquals(0, today.emails.count)
        assertEquals("None yet", today.emails.subtitle)

        assertEquals(2, HomeCategoryStats.from(events, now, HomePeriod.WEEK, zone).delivery.count)
        assertEquals(1, HomeCategoryStats.from(events, now, HomePeriod.ALL, zone).emails.count)
    }
}
