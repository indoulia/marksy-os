package com.marksy.os.data

/**
 * Local retention policy kept separate from WorkManager scheduling so the
 * lifecycle rules can be reasoned about and tested without Android runtime
 * state.
 */
object RetentionPolicy {
    const val NON_TRADING_DAYS = 7L
    const val TRADING_DAYS = 30L

    const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    fun nonTradingCutoff(nowMillis: Long): Long =
        nowMillis - NON_TRADING_DAYS * MILLIS_PER_DAY

    fun tradingCutoff(nowMillis: Long): Long =
        nowMillis - TRADING_DAYS * MILLIS_PER_DAY

    /**
     * Returns whether a notification is eligible for automatic local cleanup.
     * Events exactly at the cutoff are retained; cleanup removes events older
     * than the computed cutoff.
     */
    fun shouldDelete(postedAtMillis: Long, cutoffMillis: Long): Boolean =
        postedAtMillis < cutoffMillis

    /**
     * Trading events are retained longer because they can carry Marksy insight
     * and are part of the user's trading timeline.
     */
    fun cutoffFor(isTrading: Boolean, nowMillis: Long): Long =
        if (isTrading) tradingCutoff(nowMillis) else nonTradingCutoff(nowMillis)
}
