package com.marksy.os.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallHorizonTest {
    private fun sessions(text: String) = CallHorizon.parse(text)?.sessions

    @Test
    fun durationsInAnyWordingBecomeTradingSessions() {
        assertEquals(252, sessions("Buy TENNIND around Rs 500 for 12 Month with target price of Rs 650"))
        assertEquals(504, sessions("Accumulate for 24 months"))
        assertEquals(252, sessions("Target in 1 year"))
        assertEquals(378, sessions("holding 1.5 years"))
        assertEquals(504, sessions("2 yrs view"))
        assertEquals(252, sessions("one year target"))
        assertEquals(126, sessions("six months"))
        assertEquals(15, sessions("for 3 weeks"))
        assertEquals(5, sessions("Horizon: 5 days"))
        assertEquals(252, sessions("12M target 650"))
        assertEquals(252, sessions("6-12 months"))
        assertEquals(126, sessions("3 to 6 months"))
    }

    @Test
    fun phrasesUseTheirConventionalLength() {
        assertEquals(1, sessions("Intraday call"))
        assertEquals(1, sessions("BTST: buy XYZ"))
        assertEquals(10, sessions("Swing trade"))
        assertEquals(21, sessions("Positional call"))
        assertEquals(63, sessions("Short term Call"))
        assertEquals(126, sessions("Mid-term pick"))
        assertEquals(252, sessions("Long term investment"))
    }

    @Test
    fun labelledFieldKeepsItsWordsAndWinsOverProse() {
        val h = CallHorizon.parse("BUY | CROPSTER AGRO | Time: 1-2 Months | long term story")!!
        assertEquals("1-2 months", h.label)
        assertEquals(42, h.sessions)
        assertEquals("12 months", CallHorizon.parse("for 12 Month")!!.label)
        assertEquals("Long term", CallHorizon.parse("long-term")!!.label)
    }

    @Test
    fun statisticsAndHistoryAreNotHorizons() {
        assertNull(sessions("Trading near its 52 week high"))
        assertNull(sessions("above the 200 day moving average"))
        assertNull(sessions("up 40% in the last 3 years"))
        assertNull(sessions("1 year return of 25%"))
        assertNull(sessions("Price ₹12M turnover"))
        assertNull(sessions("Time: 10:30 AM"))
    }
}
