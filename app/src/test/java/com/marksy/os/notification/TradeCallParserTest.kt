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
        assertEquals("1-2 Months", call.horizon)
    }

    @Test fun textWithoutACallIsNotParsed() {
        assertNull(TradeCallParser.parse("4 IPOs Just Went Live", "Acevector & Orient Cables IPOs are now open for subscription"))
    }
}
