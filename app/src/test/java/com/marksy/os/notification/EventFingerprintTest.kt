package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EventFingerprintTest {
    @Test
    fun `same source and event in same window is duplicate`() {
        val first = EventFingerprint.create(
            sourcePackage = "com.upstox.pro",
            category = "TRADING",
            title = "Order update",
            body = "BUY HLEGLAS at ₹367.95",
            occurredAt = 1_000_000L
        )
        val second = EventFingerprint.create(
            sourcePackage = "com.upstox.pro",
            category = "TRADING",
            title = "Order update",
            body = "BUY HLEGLAS at ₹367.95",
            occurredAt = 1_000_100L
        )

        assertEquals(first, second)
    }

    @Test
    fun `same event after dedup window is a new occurrence`() {
        val first = EventFingerprint.create(
            sourcePackage = "com.upstox.pro",
            category = "TRADING",
            title = "Order update",
            body = "BUY HLEGLAS at ₹367.95",
            occurredAt = 1_000_000L
        )
        val second = EventFingerprint.create(
            sourcePackage = "com.upstox.pro",
            category = "TRADING",
            title = "Order update",
            body = "BUY HLEGLAS at ₹367.95",
            occurredAt = 1_000_000L + EventFingerprint.DEDUP_WINDOW_MS
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `same event from different sources is not duplicate`() {
        val upstox = EventFingerprint.create(
            sourcePackage = "com.upstox.pro",
            category = "TRADING",
            title = "Order update",
            body = "BUY HLEGLAS at ₹367.95",
            occurredAt = 1_000_000L
        )
        val zerodha = EventFingerprint.create(
            sourcePackage = "com.zerodha.kite3",
            category = "TRADING",
            title = "Order update",
            body = "BUY HLEGLAS at ₹367.95",
            occurredAt = 1_000_000L
        )

        assertNotEquals(upstox, zerodha)
    }

    @Test
    fun `formatting and currency symbol differences remain normalized`() {
        val first = EventFingerprint.create(
            sourcePackage = "com.upstox.pro",
            category = "TRADING",
            title = " Order   update ",
            body = "BUY HLEGLAS at ₹367.95,",
            occurredAt = 1_000_000L
        )
        val second = EventFingerprint.create(
            sourcePackage = "COM.UPSTOX.PRO",
            category = "TRADING",
            title = "Order update",
            body = "BUY HLEGLAS at currency 367.95",
            occurredAt = 1_000_100L
        )

        assertEquals(first, second)
    }
}
