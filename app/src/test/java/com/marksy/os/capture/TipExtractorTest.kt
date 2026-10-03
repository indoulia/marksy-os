package com.marksy.os.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TipExtractorTest {
    private val provenance = CaptureProvenance(CaptureMethod.USER_SHARED_IMAGE, null, null, false, 1_000L, "sha256:ab")

    private fun candidate(text: String, ocr: Float? = null): TipCandidate {
        val result = TipExtractor.extract(text, provenance, ocr)
        assertTrue("expected a candidate, got $result", result is ExtractionResult.Candidate)
        return (result as ExtractionResult.Candidate).candidate
    }

    @Test
    fun validTipIsExtracted() {
        val tip = candidate("BUY RELIANCE @ 2,450 Target 2550 SL 2400 Intraday")
        assertEquals(TipFields("RELIANCE", TipSide.BUY, 2450.0, 2550.0, 2400.0, "Intraday"), tip.fields)
        assertEquals(CaptureState.EXTRACTED, tip.state)
        assertEquals(emptySet<String>(), tip.ambiguities)
        assertTrue(tip.confidence >= 0.8)
        assertSame(provenance, tip.provenance)
    }

    @Test
    fun notificationCompleteTip() {
        val tip = candidate("BUY ABC @ 500 Target 650 SL 470")
        assertEquals(TipFields("ABC", TipSide.BUY, 500.0, 650.0, 470.0), tip.fields)
        assertEquals(CaptureState.EXTRACTED, tip.state)
    }

    @Test
    fun missingTargetNeedsReview() {
        val tip = candidate("SELL XYZ @ 300 SL 320")
        assertEquals(TipFields("XYZ", TipSide.SELL, entry = 300.0, stopLoss = 320.0), tip.fields)
        assertEquals(setOf(Ambiguity.MISSING_TARGET), tip.ambiguities)
        assertEquals(CaptureState.REVIEW_REQUIRED, tip.state)
    }

    @Test
    fun missingStopLossNeedsReview() {
        val tip = candidate("Buy ABC above 500 tgt 560 for 3 days")
        assertEquals(TipFields("ABC", TipSide.BUY, entry = 500.0, target = 560.0, horizon = "3 days"), tip.fields)
        assertEquals(setOf(Ambiguity.MISSING_STOP_LOSS), tip.ambiguities)
        assertEquals(CaptureState.REVIEW_REQUIRED, tip.state)
    }

    // Never invent a field: an OCR-garbled price is left empty and flagged.
    @Test
    fun malformedPriceIsLeftEmpty() {
        val tip = candidate("BUY ABC @ 5O0 Target 4,7O SL 470")
        assertNull(tip.fields.entry)
        assertNull(tip.fields.target)
        assertEquals(470.0, tip.fields.stopLoss)
        assertTrue(Ambiguity.MALFORMED_PRICE in tip.ambiguities)
        assertEquals(CaptureState.REVIEW_REQUIRED, tip.state)
    }

    @Test
    fun ocrNoiseBrokenLinesAndMixedCase() {
        val text = "10:42 AM  ⚡ 4G  87%\n**bUy**  Abc\n@ 500 |\nTARGET : 650\nsl- 470.\n— Research Desk"
        val tip = candidate(text, ocr = 0.92f)
        assertEquals(TipFields("ABC", TipSide.BUY, 500.0, 650.0, 470.0, visibleTimestamp = "10:42 AM"), tip.fields)
        assertEquals(CaptureState.EXTRACTED, tip.state)
        assertEquals(text, tip.extractedText)

        val blurry = candidate(text, ocr = 0.4f)
        assertEquals(setOf(Ambiguity.LOW_OCR_CONFIDENCE), blurry.ambiguities)
        assertEquals(CaptureState.REVIEW_REQUIRED, blurry.state)
    }

    @Test
    fun ambiguousSymbolAndConflictingSide() {
        val tip = candidate("BUY ABC XYZ @ 500 Target 650 SL 470")
        assertNull(tip.fields.symbol)
        assertEquals(setOf(Ambiguity.AMBIGUOUS_SYMBOL), tip.ambiguities)
        assertEquals(CaptureState.REVIEW_REQUIRED, tip.state)

        val both = candidate("BUY ABC @ 500 Target 650 SL 470, SELL on rise")
        assertNull(both.fields.side)
        assertTrue(Ambiguity.CONFLICTING_SIDE in both.ambiguities)
    }

    @Test
    fun nonTradingScreenshotTextIsNotATip() {
        val text = "Order #40213 delivered at 10:45 am\nRate your experience: 5 stars\nShort video: unboxing tips"
        assertEquals(ExtractionResult.NotATip, TipExtractor.extract(text, provenance, 0.95f))
        assertEquals(ExtractionResult.NotATip, TipExtractor.extract("   ", provenance, null))
    }

    @Test
    fun oneTimeCodeDropsTheWholeCapture() {
        assertEquals(ExtractionResult.OneTimeCode, TipExtractor.extract("Your OTP for login is 482913. Do not share it.", provenance, null))
        assertEquals(ExtractionResult.OneTimeCode, TipExtractor.extract("BUY ABC @ 500 Target 650 SL 470\nTPIN 4829", provenance, null))
    }
}
