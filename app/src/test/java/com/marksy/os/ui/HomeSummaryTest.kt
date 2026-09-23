package com.marksy.os.ui

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.MarketSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class HomeSummaryTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 23, 18, 0).toInstant(zone).toEpochMilli()
    private var seq = 0
    private fun event(source: String) = NotificationEventEntity(
        sourcePackage = "p", sourceName = source, sourceKey = "k${seq++}", eventFingerprint = "f$seq",
        title = "t", body = "b", postedAt = LocalDateTime.of(2026, 9, 23, 10, 0).toInstant(zone).toEpochMilli(),
        category = "OTHER", priority = 1, confidence = 1f, isTrading = false
    )

    // Regression: Home showed a fixed "Reliance and ICICI Bank showed strong activity…" sentence.
    @Test
    fun summaryDescribesRealCountsSourcesAndMarket() {
        val digest = DailyDigestModel.build(listOf(event("Teams"), event("Teams"), event("Gmail")), now, zone)
        val market = MarketSnapshot(
            marketStatus = "OPEN",
            indices = listOf(MarketSnapshot.MarketIndex("NIFTY 50", 25143.0, -0.42, null)),
            opportunities = emptyList(), gainers = emptyList(), losers = emptyList(), asOf = null
        )

        val text = HomeSummary.text(digest, market)

        assertTrue(text, text.startsWith("3 notifications today, none need your attention."))
        assertTrue(text, text.contains("Busiest: Teams (2), Gmail (1)."))
        assertTrue(text, text.contains("NIFTY 50 is down 0.42%."))
    }

    @Test
    fun emptyDayWithoutMarketSaysSo() {
        assertEquals("No notifications captured yet today.", HomeSummary.text(DailyDigestModel.build(emptyList(), now, zone), null))
    }
}
