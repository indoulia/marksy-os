package com.marksy.os.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailySetupsTest {
    // Same shape as marksy-api's tests/test_external_tip_ingest.py fixtures: prose around one fenced marksy-tips block.
    private val report = """
        📊 AiTradingAgent — Daily Setups
        Session: 16 Sep 2026
        1. HCLTECH long ₹1,245–1,265
        ```json
        {
          "schema": "marksy-tips/v1",
          "reportType": "DAILY_SETUPS",
          "reportDate": "2026-09-16",
          "source": "AiTradingAgent",
          "predictions": [
            {"id": "P-1", "direction": "LONG", "entryLow": 100, "entryHigh": 110, "stopLoss": 95, "target1": 120, "horizonDays": 3, "confidence": 60},
            {"id": "P-2", "symbol": "hcltech", "direction": "LONG", "entryLow": 1245, "entryHigh": 1265, "stopLoss": 1205,
             "target1": 1310, "target2": 1350, "horizonDays": 5, "confidence": 78, "rationale": "IT relative strength plus price confirmation."},
            {"id": "P-3", "symbol": "BHEL", "direction": "SHORT", "entryPrice": 413, "stopLoss": 430, "target1": 392, "horizonDays": 5, "confidence": 0.69}
          ]
        }
        ```
    """.trimIndent()

    @Test
    fun parsesTheFencedMarksyTipsBlockAndSkipsSetupsWithoutASymbol() {
        val r = DailySetups.parse(report)!!

        assertEquals("AiTradingAgent", r.source)
        assertEquals("2026-09-16", r.reportDate)
        assertEquals(listOf("HCLTECH", "BHEL"), r.setups.map { it.symbol })
        val hcl = r.setups.first()
        assertEquals(1245.0 to 1265.0, hcl.entryLow to hcl.entryHigh)
        assertEquals(listOf(1310.0, 1350.0), hcl.targets)
        assertEquals(78, hcl.confidencePct)
        assertEquals(true, hcl.long)
        val bhel = r.setups.last()
        assertEquals(413.0 to 413.0, bhel.entryLow to bhel.entryHigh)
        assertEquals(69, bhel.confidencePct)
        assertEquals(false, bhel.long)
    }

    @Test
    fun ignoresTextWithoutADailySetupsBlock() {
        assertNull(DailySetups.parse("BUY INFY target 1600 {\"schema\": \"other/v1\"}"))
        assertNull(DailySetups.parse("""{"schema": "marksy-tips/v1", "reportType": "OTHER", "predictions": []}"""))
    }
}
