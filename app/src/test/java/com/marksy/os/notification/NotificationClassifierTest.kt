package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClassifierTest {
    @Test fun tradingNotificationIsTrading() {
        val result = NotificationClassifier.classify("com.upstox.pro", "Order Executed", "BUY 10 RELIANCE")
        assertEquals(NotificationClassifier.Category.TRADING, result.category)
        assertTrue(result.confidence >= .9f)
    }

    @Test fun whatsappMessageIsMessages() {
        val result = NotificationClassifier.classify("com.whatsapp", "New message", "Hello")
        assertEquals(NotificationClassifier.Category.MESSAGES, result.category)
    }

    @Test fun otpTakesPriorityOverGenericPayment() {
        val result = NotificationClassifier.classify("com.bank", "OTP", "Your verification code is 123456")
        assertEquals(NotificationClassifier.Category.OTP, result.category)
    }

    @Test fun unknownNotificationFallsBackToOther() {
        val result = NotificationClassifier.classify("com.example", "Hello", "Something happened")
        assertEquals(NotificationClassifier.Category.OTHER, result.category)
    }
}
