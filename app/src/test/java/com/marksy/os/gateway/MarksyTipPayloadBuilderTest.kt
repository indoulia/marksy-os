package com.marksy.os.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Finding M4: symbolOf is still live (TradingIntelligenceScreen.kt), but its regression coverage was
// deleted with the retired MarksyTipPayloadFixtureTest. These 3 assertions are ported from it (see
// `git show 2c8e6ff:app/src/test/java/com/marksy/os/gateway/MarksyTipPayloadFixtureTest.kt`), adapted
// from the old MarksyTradingEventRequest-based API to the current symbolOf(title, body) signature.
class MarksyTipPayloadBuilderTest {

    // "Stock Alert" must not yield ALERT: the labelled-symbol capture is case-sensitive, and the
    // all-caps noise-word fallback never matches a mixed-case token like "Alert" either.
    @Test
    fun stockAlertDoesNotBecomeTheWordAlert() {
        val symbol = MarksyTipPayloadBuilder.symbolOf("Stock Alert", "BUY RELIANCE order executed")

        assertEquals("RELIANCE", symbol)
    }

    @Test
    fun knownNoiseWordsAreRejectedEvenWithTradeContext() {
        val symbol = MarksyTipPayloadBuilder.symbolOf("BUY ORDER EXECUTED", "UNUSUAL VOLUME ALERT DETECTED")

        assertNull(symbol)
    }

    // Regression: the SMS sender name ("KISHAN") was sent as the symbol instead of the called stock.
    @Test
    fun smsCallSendsTheCalledInstrumentNotTheSender() {
        val symbol = MarksyTipPayloadBuilder.symbolOf(
            "KISHAN ENTERPRISE",
            "KISHAN ENTERPRISE: Dear Client \nBUY | CROPSTER AGRO | \nEntry ₹2.82 | Target ₹10 | SL ₹2 | \nTime: 1-2 Months"
        )

        assertEquals("CROPSTER AGRO", symbol)
    }
}
