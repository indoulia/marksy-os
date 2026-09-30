package com.marksy.os.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MarksyGatewayClientTest {
    @Test
    fun unconfiguredClientDoesNotPretendDeliverySucceeded() = runBlocking {
        val message = CapturedMessage("n1-key", "APP_NOTIFICATION", "com.upstox.pro", "Upstox", "BUY RENUKA CMP 23 SL 22", "2026-09-28T04:45:00Z")

        assertEquals(false, UnconfiguredMarksyGatewayClient().capture(1L, message).isSuccess)
        assertEquals(false, UnconfiguredMarksyGatewayClient().captureList().isSuccess)
    }
}
