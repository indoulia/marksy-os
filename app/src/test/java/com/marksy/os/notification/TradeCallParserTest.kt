package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixtures are real calls captured on-device 2026-09-25 (5paisa, Upstox, a broker SMS). */
class TradeCallParserTest {
    @Test fun parsesFivePaisaShortTermCall() {
        val call = TradeCallParser.parse("Short term Call", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26")!!
        assertEquals(TradeCallParser.Side.BUY, call.side)
        assertEquals("RENUKA", call.symbol)
        assertEquals(23.62, call.entry!!, 0.001)
        assertEquals(22.25, call.stopLoss!!, 0.001)
        assertEquals(26.0, call.target!!, 0.001)
        assertEquals("Short term", call.horizon)
    }

    @Test fun parsesUpstoxEmojiLabelledCall() {
        val call = TradeCallParser.parse("📈BUY LCCPROJECT with 20.0% upside potential", "🛠️ Entry : Rs 144.24\n🎯 Target : Rs 173.08\n🛑 Stoploss : Rs 129.81")!!
        assertEquals("LCCPROJECT", call.symbol)
        assertEquals(144.24, call.entry!!, 0.001)
        assertEquals(173.08, call.target!!, 0.001)
        assertEquals(129.81, call.stopLoss!!, 0.001)
    }

    @Test fun parsesPipeSeparatedSmsCallWithMultiWordName() {
        val call = TradeCallParser.parse(
            "KISHAN ENTERPRISE",
            "KISHAN ENTERPRISE: Dear Client \nBUY | CROPSTER AGRO | \nEntry ₹2.82 | Target ₹10 | SL ₹2 | \nTime: 1-2 Months | \nConviction: High\nThank you \nKISHAN Enterprises"
        )!!
        assertEquals("CROPSTER AGRO", call.symbol)
        assertEquals(2.82, call.entry!!, 0.001)
        assertEquals(10.0, call.target!!, 0.001)
        assertEquals(2.0, call.stopLoss!!, 0.001)
        assertEquals("1-2 months", call.horizon)
    }

    // Regression: ICICI Direct research calls ("around Rs N … target price of Rs M") were never parsed.
    @Test fun parsesIciciResearchCallWithAroundAndTargetPrice() {
        val call = TradeCallParser.parse("ICICI Direct", "Buy JSL around Rs 750 for 12 Month with target price of Rs 915, potential upside of 22%.")!!
        assertEquals(TradeCallParser.Side.BUY, call.side)
        assertEquals("JSL", call.symbol)
        assertEquals(750.0, call.entry!!, 0.001)
        assertEquals(915.0, call.target!!, 0.001)
        // Regression: "for 12 Month" (TENNIND, 2026-09-29) showed no horizon.
        assertEquals("12 months", call.horizon)
        assertEquals(252, call.horizonSessions)
    }

    @Test fun textWithoutACallIsNotParsed() {
        assertNull(TradeCallParser.parse("4 IPOs Just Went Live", "Acevector & Orient Cables IPOs are now open for subscription"))
    }

    // Regression: options calls were read as the index itself with no entry.
    @Test fun parsesOptionsCallWithStrikeAndAtPrice() {
        val call = TradeCallParser.parse("F&O Call", "SELL NIFTY 24500 PE @ 120 SL 140 TGT 90")!!
        assertEquals(TradeCallParser.Side.SELL, call.side)
        assertEquals("NIFTY 24500 PE", call.symbol)
        assertEquals(120.0, call.entry!!, 0.001)
    }

    // Regression: symbols that start with a digit were dropped.
    @Test fun parsesDigitLeadingSymbol() {
        assertEquals("360ONE", TradeCallParser.parse("Call", "BUY 360ONE CMP 1150 SL 1090 TGT 1260")!!.symbol)
    }

    // Regression: "Target 1: 150" was read as a target of 1.
    @Test fun numberedTargetIsNotTakenAsThePrice() {
        assertEquals(150.0, TradeCallParser.parse("Call", "BUY RENUKA CMP 140 SL 132 Target 1: 150")!!.target!!, 0.001)
    }

    // Regression: "in the range of" became part of the symbol.
    @Test fun rangeEndsTheSymbolAndGivesTheEntry() {
        val call = TradeCallParser.parse("Call", "Buy TATASTEEL in the range of 150-152, target 170, SL 144")!!
        assertEquals("TATASTEEL", call.symbol)
        assertEquals(150.0, call.entry!!, 0.001)
    }
}
