package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class InsightsBreakdownTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 23, 18, 0).toInstant(zone).toEpochMilli()
    private var seq = 0
    private fun event(category: String, day: Int, source: String = "App", priority: Int = 1) = NotificationEventEntity(
        sourcePackage = "p", sourceName = source, sourceKey = "k${seq++}", eventFingerprint = "f$seq",
        title = "t", body = "b", postedAt = LocalDateTime.of(2026, 9, day, 10, 0).toInstant(zone).toEpochMilli(),
        category = category, priority = priority, confidence = 1f, isTrading = category == "TRADING"
    )

    // Regression: Insights showed fixed counts (Trading 23, Messages 32, …) and a total floored at 83.
    @Test
    fun breakdownFollowsPeriodAndFoldsTailIntoOther() {
        val events = listOf("EMAIL", "EMAIL", "EMAIL", "WORK", "WORK", "DELIVERY", "BANKING", "BILLS", "PAYMENTS", "OTHER")
            .map { event(it, 23) } + event("EMAIL", 20)

        val today = InsightsModel.breakdown(events, HomePeriod.TODAY, now, zone, maxSlices = 4)

        assertEquals(listOf("EMAIL" to 3, "WORK" to 2, "DELIVERY" to 1, "OTHER" to 4), today)
        assertEquals(4, InsightsModel.breakdown(events, HomePeriod.ALL, now, zone).first().second)
    }

    @Test
    fun categoryInsightNamesTopSourcesAndImportance() {
        val events = listOf(event("EMAIL", 23, "Outlook", priority = 95), event("EMAIL", 23, "Outlook"), event("EMAIL", 23, "Gmail"))

        assertEquals(
            "3 emails, 1 important. Mostly from Outlook (2), Gmail (1).",
            InsightsModel.categoryInsight(events, "EMAIL", "email", "emails", now)
        )
    }
}
