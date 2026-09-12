package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationClassifierEdgeCasesTest {

    @Test
    fun brokerOtpWinsOverTradingKeywords() {
        assertEquals(
            "OTP",
            NotificationClassifier.classify(
                packageName = "com.upstox.pro",
                title = "OTP",
                body = "Your OTP for buy order is 482913"
            ).category
        )
    }

    @Test
    fun tradingTermsFromUnknownAppsDoNotBecomeTrading() {
        assertEquals(
            "OTHER",
            NotificationClassifier.classify(
                packageName = "com.example.notes",
                title = "Market alert",
                body = "Buy order executed at 367.95"
            ).category
        )
    }

    @Test
    fun brokerExecutedOrderIsTrading() {
        assertEquals(
            "TRADING",
            NotificationClassifier.classify(
                packageName = "com.upstox.pro",
                title = "Order executed",
                body = "HLEGLAS BUY 10 @ 367.95"
            ).category
        )
    }

    @Test
    fun brokerRejectedOrderIsTrading() {
        assertEquals(
            "TRADING",
            NotificationClassifier.classify(
                packageName = "com.zerodha.kite3",
                title = "Order rejected",
                body = "Your SELL order was rejected"
            ).category
        )
    }

    @Test
    fun whatsappMessageIsMessage() {
        assertEquals(
            "MESSAGE",
            NotificationClassifier.classify(
                packageName = "com.whatsapp",
                title = "Asha",
                body = "Can you call me when you are free?"
            ).category
        )
    }

    @Test
    fun whatsappBusinessMessageIsMessage() {
        assertEquals(
            "MESSAGE",
            NotificationClassifier.classify(
                packageName = "com.whatsapp.w4b",
                title = "Store",
                body = "Your order is ready for pickup"
            ).category
        )
    }

    @Test
    fun paymentNotificationDoesNotBecomeTrading() {
        assertEquals(
            "PAYMENT",
            NotificationClassifier.classify(
                packageName = "com.google.android.apps.nbu.paisa.user",
                title = "Payment received",
                body = "₹1,000 received successfully"
            ).category
        )
    }

    @Test
    fun multilineTradingNotificationStillClassifies() {
        assertEquals(
            "TRADING",
            NotificationClassifier.classify(
                packageName = "com.icicidirect",
                title = "Trade confirmation",
                body = "HLEGLAS\nBUY\n10 shares\nAverage price ₹367.95\nTarget ₹390"
            ).category
        )
    }

    @Test
    fun blankNotificationIsOther() {
        assertEquals(
            "OTHER",
            NotificationClassifier.classify(
                packageName = "com.example.app",
                title = "",
                body = ""
            ).category
        )
    }
}
