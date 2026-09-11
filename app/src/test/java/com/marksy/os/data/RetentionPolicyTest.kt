package com.marksy.os.data

import org.junit.Assert.assertEquals
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
}
