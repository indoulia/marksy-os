package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClassifierTest {
    // Seen on device: 18 Outlook emails were REMINDERS/WORK because they said "reminder" or "meeting".
    @Test fun mailAppWordsLikeReminderOrMeetingStayEmailButDuesAndPromosDoNot() {
        fun cat(t: String, b: String) = NotificationClassifier.classify("com.microsoft.office.outlook", t, b).category
        assertEquals(NotificationClassifier.Category.EMAIL, cat("MySpace", "Timesheet update reminder for September 23,2026"))
        assertEquals(NotificationClassifier.Category.EMAIL, cat("Josh Mau", "Meeting moved to 3pm, join on Teams"))
        assertEquals(NotificationClassifier.Category.REMINDERS, cat("HDFC Bank", "Your card bill of Rs 4,210 is due on 5 Oct"))
        assertEquals(NotificationClassifier.Category.PROMOTIONS, cat("Myntra", "Flat 50% off, sale ends tonight"))
    }

    @Test fun tradingNotificationIsTrading() {
        val result = NotificationClassifier.classify("com.upstox.pro", "Order Executed", "BUY 10 RELIANCE")
        assertEquals(NotificationClassifier.Category.TRADING, result.category)
        assertTrue(result.confidence >= .9f)
    }

    @Test fun tradingLanguageFromUnknownPackageIsNotRoutedAsTrading() {
        val result = NotificationClassifier.classify("com.example.broker", "Trade Executed", "SELL 5 TCS")
        assertEquals(NotificationClassifier.Category.OTHER, result.category)
    }

    // Phase 4b: the phone no longer spots calls; they land in categories the capture gate sends (CaptureGateTest).
    @Test fun callsLandInCategoriesTheCaptureGateSends() {
        fun cat(pkg: String, t: String, b: String) = NotificationClassifier.classify(pkg, t, b).category
        assertEquals(NotificationClassifier.Category.MARKET, cat("com.fivepaisa.trade", "Short term Call", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("in.upstox.app", "📈BUY LCCPROJECT with 20.0% upside potential", "🛠️ Entry : Rs 144.24 🎯 Target : Rs 173.08 🛑 Stoploss : Rs 129.81"))
        assertEquals(NotificationClassifier.Category.MARKET, cat("com.icicidirect.idirectsuper", "ICICI Direct", "Buy INDGN around Rs 609 for 12 Month with target price of Rs 750, potential upside of 23.15%."))
        assertEquals(NotificationClassifier.Category.OTHER, cat("com.google.android.apps.messaging", "KISHAN ENTERPRISE", "KISHAN ENTERPRISE: Dear Client \nBUY | CROPSTER AGRO | \nEntry ₹2.82 | Target ₹10 | SL ₹2 | \nTime: 1-2 Months"))
        assertEquals(NotificationClassifier.Category.MESSAGES, cat("com.whatsapp", "Tips Group", "BUY TATASTEEL CMP 152 SL 147 TGT 162"))
        assertEquals(NotificationClassifier.Category.MESSAGES, cat("org.telegram.messenger", "Stock Calls", "SELL INFY @ 1500 target 1450 stoploss 1525"))
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

    // Bills, EMIs, card dues and birthdays are reminders, ahead of generic bills/banking.
    @Test fun duesAndBirthdaysAreReminders() {
        fun cat(pkg: String, t: String, b: String) = NotificationClassifier.classify(pkg, t, b).category
        assertEquals(NotificationClassifier.Category.REMINDERS, cat("com.truecaller", "₹14,917", "•  ICICI Bank  •  Bill due on 6th Oct SMS from ICICI Bank"))
        assertEquals(NotificationClassifier.Category.REMINDERS, cat("com.google.android.apps.messaging", "HDFC Bank", "Your EMI of Rs.5,432 is due on 05/10/2026. Ensure funds are credited."))
        assertEquals(NotificationClassifier.Category.REMINDERS, cat("com.google.android.apps.messaging", "ICICI Bank", "Total amount due Rs 14,917, minimum amount due Rs 750"))
        assertEquals(NotificationClassifier.Category.REMINDERS, cat("com.facebook.katana", "Birthdays", "It's Aisha's birthday today"))
        assertTrue(NotificationClassifier.classify("com.truecaller", "₹14,917", "Bill due on 6th Oct").priority >= 85)
        assertEquals(NotificationClassifier.Category.BANKING, cat("com.google.android.apps.messaging", "HDFC Bank", "Your EMI of Rs 5,432 has been debited"))
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

    // Regression: PhonePe marketing (SIP, insurance, loans) ranked as PAYMENTS on Home; only transfers are payments.
    @Test fun paymentAppMarketingIsPromotionsButTransfersStayPayments() {
        fun cat(t: String, b: String) = NotificationClassifier.classify("com.phonepe.app", t, b).category
        assertEquals(NotificationClassifier.Category.PROMOTIONS, cat("Daily Mutual Fund SIP from ₹10", "Start now"))
        assertEquals(NotificationClassifier.Category.PROMOTIONS, cat("Pay faster every time ⚡", "Save VISA card details on PhonePe & enjoy seamless checkouts"))
        assertEquals(NotificationClassifier.Category.PROMOTIONS, cat("PhonePe", "Your friend just joined"))
        assertEquals(NotificationClassifier.Category.PAYMENTS, cat("Received ₹500", "Received ₹500 from Rahul"))
        assertEquals(NotificationClassifier.Category.PAYMENTS, cat("Asha requested ₹250", "Tap to pay or decline"))
        assertEquals(NotificationClassifier.Category.PAYMENTS, cat("Payment successful", "₹120 paid to Swiggy"))
    }

    @Test fun packageHintNeverOverridesAnExplicitTermSignal() {
        // A promo term must still win over the shopping-app package hint.
        val result = NotificationClassifier.classify("com.myntra.android", "OTP", "Your verification code is 4321")
        assertEquals(NotificationClassifier.Category.OTP, result.category)
    }

    // Regression: a broker's P&L statement was routed to Marksy as a trade.
    @Test fun brokerPnlStatementIsNotTrading() {
        val result = NotificationClassifier.classify("com.zerodha.kite3", "Kite", "Your P&L statement for September is ready")
        assertTrue(result.category != NotificationClassifier.Category.TRADING)
    }

    // Regression: a tip SMS with a "never share your OTP" footer was filed as an OTP; a real OTP with it still is one.
    @Test fun tradeSmsWithOtpWarningIsNotAnOtp() {
        val tip = NotificationClassifier.classify("com.google.android.apps.messaging", "KISHAN", "BUY RENUKA CMP 23.62 SL 22.25 TGT 26. Never share your OTP with anyone.")
        val otp = NotificationClassifier.classify("com.google.android.apps.messaging", "JD-ZERODH-S", "Use 482913 to log in. Never share your OTP with anyone.")
        assertTrue(tip.category != NotificationClassifier.Category.OTP)
        assertEquals(NotificationClassifier.Category.OTP, otp.category)
        // 4b review M3: a standalone code keeps the footer, so a TPIN with call levels is an OTP; a price after a level is no code.
        val tpin = NotificationClassifier.classify("com.google.android.apps.messaging", "JD-ZERODH-S", "482913 is your TPIN code to authorise SELL of INFY at LTP 1450 SL 1400. Never share your OTP.")
        val pricedTip = NotificationClassifier.classify("com.google.android.apps.messaging", "KISHAN", "BUY RELIANCE CMP 1450 SL 1400 TGT 1500. Never share your OTP with anyone.")
        assertEquals(NotificationClassifier.Category.OTP, tpin.category)
        assertTrue(pricedTip.category != NotificationClassifier.Category.OTP)
    }
}
