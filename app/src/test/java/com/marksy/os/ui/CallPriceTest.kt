package com.marksy.os.ui

import com.marksy.os.upstox.FeedFreshness
import com.marksy.os.upstox.UpstoxLtp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CallPriceTest {
    private val today = LocalDate.of(2026, 10, 15)
    private fun quote(tradedAt: String?) = UpstoxLtp("NSE_EQ|INE000X01010", 112.4, 110.0, tradedAt?.let { Instant.parse(it).toEpochMilli() })
    private fun of(quote: UpstoxLtp?, feed: FeedFreshness, stored: Double? = 104.0, storedAt: String? = "2026-10-13T18:30:00Z") =
        CallPrice.of(quote, feed, stored, storedAt, today)

    @Test
    fun theBarKeepsTheUpstoxQuoteAndCallsItLiveOnlyWhileItStreams() {
        assertEquals(CallPrice(112.4, "Live · 11:42", live = true), of(quote("2026-10-15T06:12:00Z"), FeedFreshness.LIVE))
        assertEquals(CallPrice(112.4, "Live", live = true), of(quote(null), FeedFreshness.LIVE))
    }

    @Test
    fun aQuoteThatIsNotStreamingNeverReadsAsCurrent() {
        assertEquals(CallPrice(112.4, "Last trade · 15:29", live = false), of(quote("2026-10-15T09:59:00Z"), FeedFreshness.CLOSED))
        assertEquals(CallPrice(112.4, "Last trade · 14 Oct, 15:29", live = false), of(quote("2026-10-14T09:59:00Z"), FeedFreshness.LIVE))
        assertEquals(CallPrice(112.4, "Not live · last trade 11:02", live = false), of(quote("2026-10-15T05:32:00Z"), FeedFreshness.STALE))
        assertEquals(CallPrice(112.4, "Not live", live = false), of(quote(null), FeedFreshness.RECONNECTING))
    }

    @Test
    fun withoutAQuoteTheBarUsesMarksysStoredCloseAndNamesItsSession() {
        assertEquals(CallPrice(104.0, "Close · 14 Oct", live = false), of(null, FeedFreshness.OFF))
        assertEquals(CallPrice(104.0, "Close · date unknown", live = false), of(null, FeedFreshness.LIVE, storedAt = null))
        assertEquals(CallPrice(null, null, live = false), of(null, FeedFreshness.LIVE, stored = null, storedAt = null))
    }
}
