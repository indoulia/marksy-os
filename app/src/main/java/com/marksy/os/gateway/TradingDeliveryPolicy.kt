package com.marksy.os.gateway

import com.marksy.os.data.local.DeliveryState

/** Pure delivery rules kept separate from WorkManager for deterministic testing. */
object TradingDeliveryPolicy {
    const val BATCH_SIZE = 10
    const val STALE_IN_FLIGHT_MS = 15 * 60 * 1000L

    fun shouldRetry(error: Throwable): Boolean =
        error !is MarksyTerminalException && error !is IllegalArgumentException

    fun retryState(error: Throwable): DeliveryState =
        if (shouldRetry(error)) DeliveryState.PENDING else DeliveryState.FAILED

    fun staleCutoff(nowMillis: Long): Long = nowMillis - STALE_IN_FLIGHT_MS
}
