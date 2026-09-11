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
}
