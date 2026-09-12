package com.marksy.os.gateway

import com.marksy.os.data.local.DeliveryState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingDeliveryPolicyTest {
    @Test
    fun transientFailureReturnsPendingAndRequestsRetry() {
        val error = IOException("network unavailable")

        assertTrue(TradingDeliveryPolicy.shouldRetry(error))
        assertEquals(DeliveryState.PENDING, TradingDeliveryPolicy.retryState(error))
    }

    @Test
    fun terminalMarksyFailureDoesNotRetry() {
        val error = MarksyTerminalException("bad request")

        assertFalse(TradingDeliveryPolicy.shouldRetry(error))
        assertEquals(DeliveryState.FAILED, TradingDeliveryPolicy.retryState(error))
    }

    @Test
    fun invalidLocalMappingDoesNotRetry() {
        val error = IllegalArgumentException("missing safe symbol")

        assertFalse(TradingDeliveryPolicy.shouldRetry(error))
        assertEquals(DeliveryState.FAILED, TradingDeliveryPolicy.retryState(error))
    }

    @Test
    fun staleCutoffUsesFifteenMinuteRecoveryWindow() {
        val now = 1_000_000L

        assertEquals(
            now - 15 * 60 * 1000L,
            TradingDeliveryPolicy.staleCutoff(now)
        )
    }

    @Test
    fun batchSizeRemainsBounded() {
        assertEquals(10, TradingDeliveryPolicy.BATCH_SIZE)
    }
}
