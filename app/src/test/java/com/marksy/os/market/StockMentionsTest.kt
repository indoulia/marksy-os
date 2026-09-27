package com.marksy.os.market

import org.junit.Assert.assertEquals
import org.junit.Test

class StockMentionsTest {
    private val known = setOf("INFY", "TATAMOTORS", "ITC", "M&M", "BAJAJ-AUTO", "IT", "OTP")

    @Test
    fun findsKnownUpperCaseTickersInOrderSkippingWordsAndIndices() {
        val text = "BUY INFY above 1500, target 1600. NSE: ITC and M&M up; NIFTY IT flat. Your OTP is 1234. infy again INFY."

        assertEquals(listOf("INFY", "ITC", "M&M"), StockMentions.find(text, known::contains))
    }

    @Test
    fun keepsHyphenatedTickersAndHonoursTheLimit() {
        assertEquals(listOf("BAJAJ-AUTO", "TATAMOTORS"), StockMentions.find("BAJAJ-AUTO, TATAMOTORS, INFY", known::contains, limit = 2))
    }
}
