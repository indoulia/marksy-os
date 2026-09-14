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

    @Test fun tradingLanguageFromUnknownPackageIsNotRoutedAsTrading() {
        val result = NotificationClassifier.classify("com.example.broker", "Trade Executed", "SELL 5 TCS")
        assertEquals(NotificationClassifier.Category.OTHER, result.category)
    }

    @Test fun nonTradingBrokerPromotionIsNotTrading() {
        val result = NotificationClassifier.classify("com.upstox.pro", "Special offer", "Get 50% discount on brokerage")
        assertEquals(NotificationClassifier.Category.PROMOTIONS, result.category)
    }

    @Test fun whatsappMessageIsMessages() {
        val result = NotificationClassifier.classify("com.whatsapp", "New message", "Hello")
        assertEquals(NotificationClassifier.Category.MESSAGES, result.category)
    }

    @Test fun whatsappBusinessMessageIsMessages() {
        val result = NotificationClassifier.classify("com.whatsapp.w4b", "New message", "Hello from a business")
        assertEquals(NotificationClassifier.Category.MESSAGES, result.category)
    }

    @Test fun otpTakesPriorityOverGenericPayment() {
        val result = NotificationClassifier.classify("com.bank", "OTP", "Your verification code is 123456")
        assertEquals(NotificationClassifier.Category.OTP, result.category)
    }

    @Test fun otpTakesPriorityOverBrokerTradingLanguage() {
        val result = NotificationClassifier.classify(
            "com.upstox.pro",
            "Order verification OTP",
            "Your OTP is 123456 to authorize the order"
        )
        assertEquals(NotificationClassifier.Category.OTP, result.category)
    }

    @Test fun shortOtpTermDoesNotMatchInsideAnotherWord() {
        val result = NotificationClassifier.classify("com.example", "Status", "The operation stopped successfully")
        assertEquals(NotificationClassifier.Category.OTHER, result.category)
    }

    @Test fun shortUpiTermDoesNotMatchInsideAnotherWord() {
        val result = NotificationClassifier.classify("com.example", "Status", "The pupil account is ready")
        assertEquals(NotificationClassifier.Category.OTHER, result.category)
    }

    @Test fun shortPromotionTermDoesNotMatchInsideAnotherWord() {
        val result = NotificationClassifier.classify("com.example", "Status", "Wholesale pricing updated")
        assertEquals(NotificationClassifier.Category.OTHER, result.category)
    }

    @Test fun tradingTakesPriorityOverBankingLanguage() {
        val result = NotificationClassifier.classify("com.upstox.pro", "Order Executed", "Amount credited to trading account")
        assertEquals(NotificationClassifier.Category.TRADING, result.category)
    }

    @Test fun deliveryLanguageDoesNotBecomeTradingForBrokerPromotion() {
        val result = NotificationClassifier.classify("com.upstox.pro", "Delivery", "Your welcome kit shipment is out for delivery")
        assertEquals(NotificationClassifier.Category.DELIVERY, result.category)
    }

    @Test fun packageWhitespaceDoesNotPreventTradingRecognition() {
        val result = NotificationClassifier.classify("  COM.UPSTOX.PRO  ", "Order Executed", "BUY 10 RELIANCE")
        assertEquals(NotificationClassifier.Category.TRADING, result.category)
    }

    @Test fun sourceRegistryNamesWhatsappBusinessExplicitly() {
        assertEquals("WhatsApp Business", SourceRegistry.displayName("com.whatsapp.w4b"))
    }

    @Test fun unknownNotificationFallsBackToOther() {
        val result = NotificationClassifier.classify("com.example", "Hello", "Something happened")
        assertEquals(NotificationClassifier.Category.OTHER, result.category)
    }

    @Test fun gmailWithoutKeywordsIsEmail() {
        val result = NotificationClassifier.classify("com.google.android.gm", "Aisha", "Lunch tomorrow?")
        assertEquals(NotificationClassifier.Category.EMAIL, result.category)
    }

    @Test fun outlookWithoutKeywordsIsEmail() {
        val result = NotificationClassifier.classify("com.microsoft.office.outlook", "Q3 Report", "Please review the deck")
        assertEquals(NotificationClassifier.Category.EMAIL, result.category)
    }

    @Test fun shoppingAppMarketingWithoutKeywordsIsPromotions() {
        val result = NotificationClassifier.classify("com.myntra.android", "The mall's closed", "We're not")
        assertEquals(NotificationClassifier.Category.PROMOTIONS, result.category)
    }

    @Test fun paymentAppFallsBackToPayments() {
        val result = NotificationClassifier.classify("com.phonepe.app", "PhonePe", "Your friend just joined")
        assertEquals(NotificationClassifier.Category.PAYMENTS, result.category)
    }

    @Test fun packageHintNeverOverridesAnExplicitTermSignal() {
        // A promo term must still win over the shopping-app package hint.
        val result = NotificationClassifier.classify("com.myntra.android", "OTP", "Your verification code is 4321")
        assertEquals(NotificationClassifier.Category.OTP, result.category)
    }
}
