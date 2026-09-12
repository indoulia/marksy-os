package com.marksy.os.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {
    @Test
    fun nonTradingCutoffIsExactlySevenDaysBeforeNow() {
        val now = 10_000_000_000L
        assertEquals(now - 7L * RetentionPolicy.MILLIS_PER_DAY, RetentionPolicy.nonTradingCutoff(now))
    }

    @Test
    fun tradingCutoffIsExactlyThirtyDaysBeforeNow() {
        val now = 10_000_000_000L
        assertEquals(now - 30L * RetentionPolicy.MILLIS_PER_DAY, RetentionPolicy.tradingCutoff(now))
    }

    @Test
    fun tradingWindowIsLongerThanNonTradingWindow() {
        assert(RetentionPolicy.TRADING_DAYS > RetentionPolicy.NON_TRADING_DAYS)
    }

    @Test
    fun cutoffBoundaryIsRetained() {
        val cutoff = 5_000_000L
        assertFalse(RetentionPolicy.shouldDelete(cutoff, cutoff))
    }

    @Test
    fun eventOlderThanCutoffIsDeleted() {
        val cutoff = 5_000_000L
        assertTrue(RetentionPolicy.shouldDelete(cutoff - 1L, cutoff))
    }

    @Test
    fun eventNewerThanCutoffIsRetained() {
        val cutoff = 5_000_000L
        assertFalse(RetentionPolicy.shouldDelete(cutoff + 1L, cutoff))
    }

    @Test
    fun tradingUsesLongerRetentionWindow() {
        val now = 10_000_000_000L
        assertEquals(
            RetentionPolicy.tradingCutoff(now),
            RetentionPolicy.cutoffFor(isTrading = true, nowMillis = now)
        )
        assertEquals(
            RetentionPolicy.nonTradingCutoff(now),
            RetentionPolicy.cutoffFor(isTrading = false, nowMillis = now)
        )
    }
}
