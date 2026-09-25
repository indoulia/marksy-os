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

    // Regression: 5paisa "Short term Call" tips (BUY … CMP … SL … TGT) landed in OTHER.
    @Test fun brokerTipCallWithCmpSlTargetIsTrading() {
        val result = NotificationClassifier.classify("com.fivepaisa.trade", "Short term Call", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26")
        assertEquals(NotificationClassifier.Category.TRADING, result.category)
    }

    // Regression: Upstox's Play Store package is in.upstox.app, so its calls were never seen as broker calls.
    @Test fun upstoxPlayStorePackageCallIsTrading() {
        val result = NotificationClassifier.classify("in.upstox.app", "📈BUY LCCPROJECT with 20.0% upside potential", "🛠️ Entry : Rs 144.24 🎯 Target : Rs 173.08 🛑 Stoploss : Rs 129.81")
        assertEquals(NotificationClassifier.Category.TRADING, result.category)
    }

    @Test fun tipCallTextFromANonBrokerAppIsStillNotTrading() {
        val result = NotificationClassifier.classify("com.android.shell", "Short term Call", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26")
        assertTrue(result.category != NotificationClassifier.Category.TRADING)
    }

    // Regression: broker/market updates (holdings alerts, research, IPO notices, market moves) landed in OTHER.
    @Test fun brokerAndMarketUpdatesWithoutACallAreMarket() {
        fun cat(pkg: String, t: String, b: String) = NotificationClassifier.classify(pkg, t, b).category
        assertEquals(NotificationClassifier.Category.MARKET, cat("com.icicidirect.idirectsuper", "ICICI Direct", "Your stock NATSEC has touched 52 week low of 780.0"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("com.icicidirect.idirectsuper", "MCX Silver December", "Expected to slip towards ₹232,000-₹233,000 levels"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("com.assetgro.stockgro.prod", "🔴 Markets open in RED", "🔻 Nifty50: 23,035.00 (-0.12%)"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("com.fivepaisa.trade", "4 IPOs Just Went Live 🚀", "Acevector & Orient Cables IPOs are now open for subscription"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("com.divum.MoneyControl", "Closing Bell Live:", "Nifty off day's low, reclaims 23,100"))
    }

    @Test fun brokerCallToActionIsPromotions() {
        assertEquals(NotificationClassifier.Category.PROMOTIONS, NotificationClassifier.classify("com.fivepaisa.trade", "New on 5paisa: Most Bought MTF Stocks", "Discover Most Bought MTF stocks right inside the 5paisa app.").category)
        assertEquals(NotificationClassifier.Category.PROMOTIONS, NotificationClassifier.classify("com.icicidirect.idirectsuper", "IPOs of Moneyview Ltd. & A-One Steels", "Click to apply now!").category)
    }

    // Regression: Moneycontrol course and portfolio-checker ads landed in MARKET.
    @Test fun marketAppCourseAndUpsellAdsArePromotions() {
        fun cat(t: String, b: String) = NotificationClassifier.classify("com.divum.MoneyControl", t, b).category
        assertEquals(NotificationClassifier.Category.PROMOTIONS, cat("Popular Demand Brings Vishal Malkan Back", "Join FREE. Master swing trading in two hours."))
        assertEquals(NotificationClassifier.Category.PROMOTIONS, cat("Is your portfolio beating NIFTY 50?", "Check your portfolio's performance in under a minute"))
        assertEquals(NotificationClassifier.Category.PROMOTIONS, cat("Are you actually beating the NIFTY50?", "See where yours stands in seconds."))
        assertEquals(NotificationClassifier.Category.PROMOTIONS, cat("Ask The Expert is live!", "SEBI Reg. expert is here to answer your stock questions!"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("Live Trades", "New Options Recommendation with Profit Potential Rs.6522.75 by Dhaval Vyas has been posted. Know Details!"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("Chart Patterns", "New Horizontal Resistance formed! Check out the stock and pattern details and get real-time updates."))
    }

    // Calls also arrive by SMS and chat; a parsed call (side + symbol + levels) from those apps is TRADING.
    @Test fun smsAndChatCallsAreTrading() {
        val sms = "KISHAN ENTERPRISE: Dear Client \nBUY | CROPSTER AGRO | \nEntry ₹2.82 | Target ₹10 | SL ₹2 | \nTime: 1-2 Months"
        assertEquals(NotificationClassifier.Category.TRADING, NotificationClassifier.classify("com.google.android.apps.messaging", "KISHAN ENTERPRISE", sms).category)
        assertEquals(NotificationClassifier.Category.TRADING, NotificationClassifier.classify("com.whatsapp", "Tips Group", "BUY TATASTEEL CMP 152 SL 147 TGT 162").category)
        assertEquals(NotificationClassifier.Category.TRADING, NotificationClassifier.classify("org.telegram.messenger", "Stock Calls", "SELL INFY @ 1500 target 1450 stoploss 1525").category)
    }

    @Test fun ordinaryChatMentioningBuyIsNotTrading() {
        assertTrue(NotificationClassifier.classify("com.whatsapp", "Mom", "Buy milk and bread on the way home").category != NotificationClassifier.Category.TRADING)
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
