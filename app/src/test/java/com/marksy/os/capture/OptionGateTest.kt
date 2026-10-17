package com.marksy.os.capture

import com.marksy.os.capture.OptionGate.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionGateTest {
    private fun read(text: String) = OptionGate.classify(text).let { it.signal to it.evidence }

    @Test
    fun optionCallsAreReadWithTheWordsThatMadeThemOptions() {
        assertEquals(Signal.OPTION to "NIFTY 24500 CE", read("SELL NIFTY 24500 CE below 182 target 150 SL 200"))
        assertEquals(Signal.OPTION to "BANKNIFTY 24 OCT 52000 PE", read("BUY BANKNIFTY 24 OCT 52000 PE @ 310 tgt 380 sl 280"))
        assertEquals(Signal.OPTION to "NIFTY24500CE", read("NIFTY24500CE buy above 120 tgt 150 sl 100"))
        assertEquals(Signal.OPTION to "24500 CE", read("Buy 24500 CE @ 180 target 220 sl 150"))
        assertEquals(Signal.OPTION to "24,500 PE", read("BUY @ 180 target 220 sl 150 on 24,500 PE"))
    }

    // CALL/PUT wording after a strike, or "call option", is an option call too; a stock "call", a "Call <phone>" or a "put SL" is not.
    @Test
    fun callAndPutWordingIsReadAsAnOption() {
        assertEquals(Signal.OPTION to "NIFTY 24500 CALL", read("BUY NIFTY 24500 CALL @ 180 TGT 220 SL 150"))
        assertEquals(Signal.OPTION to "BANKNIFTY 52000 PUT", read("BUY BANKNIFTY 52000 PUT @ 250 TGT 300 SL 220"))
        assertEquals(Signal.OPTION to "BANKNIFTY 52,000 PUT", read("SELL BANKNIFTY 52,000 PUT below 300 target 250 sl 330"))
        assertEquals(Signal.OPTION to "call option", read("Buy NIFTY call option @ 120 tgt 150 sl 100"))
        assertEquals(Signal.OPTION to "24500 calls", read("Buy 24500 calls above 120 tgt 150 sl 100"))
        listOf(
            "Stock call: BUY TCS @ 3500 TGT 3600 SL 3400",
            "BUY TCS @ 3500, put SL at 3400 TGT 3600",
            "BUY RENUKA CMP 23.62 SL 22.25 TGT 26. Call 9876543210",
            "Intraday calls: BUY INFY @ 1450 TGT 1500 SL 1420",
            "Call me for more stock tips: BUY TCS @ 3500 TGT 3600 SL 3400",
            "Put a stop at 3400: BUY TCS @ 3500 TGT 3600",
            "BUY TCS @ 3500 TGT 3600 SL 3400 call 9876543210 for details",
            "SELL INFY below 1450, put SL 1480 TGT 1400"
        ).forEach { assertEquals(it, Signal.NONE to null, read(it)) }
    }

    // Spacing, case, fused and expiry forms of CE/PE and CALL/PUT, and the contract kept as the symbol.
    @Test
    fun contractFormsAcrossSpacingCaseAndExpiry() {
        listOf(
            "BUY NIFTY24500CALL @ 180 TGT 220 SL 150",
            "buy nifty24500ce @ 180 tgt 220 sl 150",
            "BUY BANKNIFTY 52000PUT @ 250 TGT 300 SL 220",
            "BUY  NIFTY   24500   CE  @ 180 TGT 220 SL 150",
            "buy nifty 24500 call @ 180 tgt 220 sl 150",
            "SELL NIFTY 28-OCT-2026 24500 PUT below 120 TGT 90 SL 140",
            "BUY BANKNIFTY 24TH OCT 52000 Call above 300 tgt 360 sl 260"
        ).forEach { assertEquals(it, Signal.OPTION, OptionGate.classify(it).signal) }
        assertEquals("NIFTY 24 OCT 24500 CALL", TipExtractor.firstCallOption("BUY NIFTY 24 OCT 24500 CALL @ 180 TGT 220 SL 150").contract)
        assertTrue(OptionGate.looksLikeContract("NIFTY24500CALL"))
        assertTrue(OptionGate.looksLikeContract("BANKNIFTY 52000 PUT"))
        // Mixed: the whole message blocks, but a screenshot's flag still comes from its first call only.
        val mixed = "BUY TCS @ 3500 TGT 3600 SL 3400 | BUY NIFTY 24500 CALL @ 180"
        assertEquals(Signal.OPTION, OptionGate.classify(mixed).signal)
        assertEquals(Signal.NONE, TipExtractor.firstCallOption(mixed).signal)
    }

    // Every route sends the whole message, so a contract anywhere in it counts, even after a stock call.
    @Test
    fun aContractAnywhereInTheMessageCounts() {
        assertEquals(Signal.OPTION, OptionGate.classify("BUY TCS @ 3500 TARGET 3600 SL 3400 | BUY NIFTY 24500 CE @ 180").signal)
        assertTrue(OptionGate.blocks("BUY TCS @ 3500 TARGET 3600 SL 3400.\nHedge with 24500 PE"))
    }

    @Test
    fun aBarePeIsAmbiguousAndAPeRatioIsNot() {
        assertEquals(Signal.AMBIGUOUS to "18 PE", read("BUY TCS (18 PE) @ 3500 target 3600 stop 3400"))
        assertTrue(OptionGate.blocks("BUY TCS @ 3500, target 3600, stop 3400 (18 PE)"))
        listOf(
            "BUY TCS @ 3500 target 3600 SL 3400, trades at 18 PE",
            "BUY TCS @ 3500 target 3600 SL 3400 (18x PE)",
            "BUY TCS @ 3500 target 3600 SL 3400, 18 PE ratio",
            "BUY RENUKA CMP 23.62 SL 22.25 TGT 26"
        ).forEach { assertEquals(it, Signal.NONE to null, read(it)) }
    }

    @Test
    fun contractShapedSymbols() {
        listOf("NIFTY24500CE", "NIFTY 24500 CE", "TCS18PE", "NIFTY24OCT24500CE", "TCS 18 PE").forEach { assertTrue(it, OptionGate.looksLikeContract(it)) }
        listOf("TCS", "M&M", "BAJAJ-AUTO", "SPICE", null, "").forEach { assertFalse("$it", OptionGate.looksLikeContract(it)) }
    }

    // A capture's flag comes from its first call only, so the review shows that call's words, not a later hedge.
    @Test
    fun theFirstCallsOptionWordsAreWhatReviewShows() {
        assertEquals(Signal.OPTION to "NIFTY 24500 CE", TipExtractor.firstCallOption("BUY NIFTY 24500 CE @ 180 TARGET 220 SL 150").let { it.signal to it.evidence })
        assertEquals(Signal.NONE, TipExtractor.firstCallOption("BUY TCS @ 3500 TARGET 3600 SL 3400 | BUY NIFTY 24500 CE @ 180").signal)
        assertEquals(Signal.AMBIGUOUS to "18 PE", TipExtractor.firstCallOption("BUY TCS (18 PE) @ 3500, target 3600, stop 3400").let { it.signal to it.evidence })
    }
}
