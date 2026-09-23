package com.marksy.os.ui

import com.marksy.os.data.local.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DailyDigestModelTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 23, 18, 0).toInstant(zone).toEpochMilli()
    private fun at(day: Int, hour: Int = 10) = LocalDateTime.of(2026, 9, day, hour, 0).toInstant(zone).toEpochMilli()
    private var seq = 0
    private fun event(category: String, day: Int, priority: Int = 1, source: String = "App", trading: Boolean = false, hour: Int = 10) =
        NotificationEventEntity(
            sourcePackage = "p", sourceName = source, sourceKey = "k${seq++}", eventFingerprint = "f$seq",
            title = "t$seq", body = "b", postedAt = at(day, hour), category = category, priority = priority,
            confidence = 1f, isTrading = trading
        )

    // Regression: digest used raw priority >= 65, disagreeing with Home's attention-score "Important" count.
    @Test
    fun attentionMatchesHomeAttentionScore() {
        val events = listOf(
            event("TRADING", 23, priority = 60, trading = true),
            event("EMAIL", 23, priority = 1),
            event("EMAIL", 22, priority = 99)
        )

        val digest = DailyDigestModel.build(events, now, zone)

        assertEquals(2, digest.totalNotifications)
        assertEquals(1, digest.attentionEvents.size)
        assertTrue(digest.attentionEvents.single().isTrading)
    }

    // Regression: digest rendered hard-coded counts ("84 notifications", "₹12,540") instead of real data.
    @Test
    fun shareTextSummarisesRealCounts() {
        val events = listOf(
            event("EMAIL", 23, source = "Gmail"),
            event("EMAIL", 23, source = "Gmail"),
            event("DELIVERY", 23, source = "Swiggy")
        )

        val text = DailyDigestModel.build(events, now, zone).shareText()

        assertTrue(text, text.contains("3 notifications"))
        assertTrue(text, text.contains("Gmail (2)"))
        assertTrue(text, !text.contains("12,540"))
    }
}
