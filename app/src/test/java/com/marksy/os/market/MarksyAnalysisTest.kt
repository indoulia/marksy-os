package com.marksy.os.market

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Shape and values as the JSL recommendation arrived on-device 2026-09-28 (decimals as strings). */
class MarksyAnalysisTest {
    private val jsl = JSONObject(mapOf(
        "predictionVersion" to JSONObject(mapOf("modelVersion" to "BASELINE-001", "featureVersion" to "FV-001")),
        "levelState" to "ACTIVE", "upsidePct" to "5.37888406", "publishedUpsidePct" to "0.050000",
        "probability" to "0.62865800", "confidence" to "0.82614900", "trustScore" to "0.42215022", "trustQuality" to "LOW",
        "trustReasons" to JSONArray(listOf("recent_performance: RECENT_PERFORMANCE_VERDICT_NOT_CONCLUSIVE: latest regression check for model BASELINE-001 returned INSUFFICIENT_SAMPLE, which is neither HEALTHY nor REGRESSED")),
        "trustComponentsAvailable" to 5, "trustComponentsTotal" to 6, "uncertainty" to "LOW", "evidenceStrength" to "SUFFICIENT",
        "technical" to "SMA20 distance 0.029937, volume ratio 2.435564, ATR% 0.034770",
        "benchmarkRelative" to "MARKET_DRIVEN vs BROAD_MARKET (alpha 0.005821)", "liquidity" to "HIGH",
        "providerEvidence" to JSONArray(listOf("FUNDAMENTAL", "MARKET_SECTOR", "TECHNICAL_VOLUME"))
    ))

    // User: "unreadable" — raw fractions, 8-decimal strings and model versions instead of a few clear numbers.
    @Test fun analysisReadsAsGaugesFactsAndPlainLines() {
        val a = MarksyAnalysis.from(jsl)
        assertEquals("63% probability · 83% confidence · Low trust", a.summary)
        assertEquals(listOf("63%", "83%", "42%"), a.gauges.map { it.value })
        assertEquals("Upside" to "+5.38%", a.facts.first())
        assertEquals("Technical" to "+2.99% vs 20-day avg · volume 2.44× avg · ATR 3.48%", a.signals.first())
        assertEquals(listOf("Recent performance verdict not conclusive"), a.reasons)
    }
}
