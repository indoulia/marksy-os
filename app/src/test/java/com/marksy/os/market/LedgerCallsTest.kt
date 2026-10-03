package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerCallsTest {
    // Spec §7: Marksy withdrawing a losing call is a source exit that failed, never a neutral invalidation.
    @Test
    fun aWithdrawnLosingMarksyCallReadsAsAFailedExit() {
        val withdrawn = tip(status = "SOURCE_EXIT", outcome = "FAILURE", actual = -0.03)
        val neverEntered = tip(status = "INVALIDATED", reason = "NEVER_ENTERED")

        assertEquals("Exited · Failed", LedgerCalls.state(withdrawn))
        assertEquals(LedgerCalls.Tone.NEGATIVE, LedgerCalls.tone(withdrawn))
        assertEquals("−3.00%", LedgerCalls.progressText(withdrawn))
        assertEquals("Invalidated · never entered", LedgerCalls.state(neverEntered))
        assertEquals(LedgerCalls.Tone.MUTED, LedgerCalls.tone(neverEntered))
    }

    @Test
    fun aProvisionalReturnIsLabelledAndAFinalOneIsNot() {
        assertEquals("+2.00% so far · provisional", LedgerCalls.progressText(tip(progress = point(0.02, "PROVISIONAL"))))
        assertEquals("+2.00% so far", LedgerCalls.progressText(tip(progress = point(0.02, "FINAL"))))
        assertEquals("22 Sep · S2 · +2.00% · best +2.00% · worst 0.00% · provisional", LedgerCalls.progressLine(point(0.02, "PROVISIONAL")))
        assertNull(LedgerCalls.progressText(tip(entryStatus = "WAITING", progress = point(null, "FINAL"))))
    }

    @Test
    fun theStockPageLeadsWithTheOpenMarksyCallAndItsAnalysis() {
        val closed = tip(status = "TARGET_HIT", outcome = "SUCCESS", predictionId = 7, seen = "2026-09-01T04:00:00Z")
        val open = tip(predictionId = 8, seen = "2026-09-20T04:00:00Z")
        val rating = tip(target = null, stop = null, seen = "2026-09-25T04:00:00Z")
        val calls = InstrumentCallsDto("2026-09-30T10:00:00Z", 90, listOf(engine("Prediction engine", closed, open), engine("Rating engine", rating)), emptyList())
        val predictions = listOf(7 to 70, 8 to 80).map { (p, r) -> InstrumentPredictionEntryDto.parse(JSONObject("""{"predictionId": $p, "recommendationId": $r}""")) }

        assertEquals(open, LedgerCalls.leadingMarksyCall(calls))
        assertEquals(80, LedgerCalls.analysisRecommendationId(calls, predictions))
        assertEquals(70, LedgerCalls.analysisRecommendationId(calls.copy(engines = listOf(engine("Prediction engine", closed))), predictions))
        assertEquals(listOf("Target" to 110.0, "Entry" to 100.0, "Stop" to 95.0), LedgerCalls.chartLevels(open))
    }

    @Test
    fun recordsAndReceiptsReadAsTheServerSentThem() {
        val received = MyTipDto(tip(), null, null, listOf(receipt("SMS"), receipt("APP_NOTIFICATION"), receipt("SMS")), alsoReceivedBy = 2)

        assertEquals("Not enough history · 3 calls · 50% hit · 1 invalidated", LedgerCalls.recordText(headline(null, 3, 50.0, 1)))
        assertEquals("Trust 72 · 11 calls · 91% hit", LedgerCalls.recordText(headline(72, 11, 90.91, 0)))
        assertEquals("Received via SMS, app · 2 others got it", LedgerCalls.receivedVia(received))
        assertEquals("Received via WhatsApp", LedgerCalls.receivedVia(received.copy(receivedVia = listOf(receipt("WHATSAPP")), alsoReceivedBy = 0)))
    }

    // Spec §7 and Phase 6: the Predictions tab and Setups read the tip, so a withdrawn loss never reads "Invalidated".
    @Test
    fun aWithdrawnLosingPredictionReadsAsAFailedExitNotAnInvalidation() {
        val withdrawn = prediction("INVALIDATED", ledgerJson("SOURCE_EXIT", "FAILURE", "-0.030000"))
        val pending = prediction("INVALIDATED", ledgerJson("ACTIVE"))
        val closed = ClosedPredictionDto.parse(JSONObject(
            """{"id": 7, "symbol": "RENUKA", "outcome": "HORIZON_EXPIRED", "realizedReturn": "0.01",
               "ledger": ${ledgerJson("SOURCE_EXIT", "FAILURE", "-0.030000")}}"""
        ))

        assertFalse(LedgerCalls.isLive(withdrawn))
        assertEquals("Exited · Failed · −3.00%", LedgerCalls.endedLine(withdrawn))
        assertNull(LedgerCalls.lifecycleWord(withdrawn))
        assertEquals("Withdrawn · result pending", LedgerCalls.endedLine(pending))
        assertTrue(LedgerCalls.isLive(prediction("ACTIONABLE_NOW", ledgerJson("ACTIVE"))))
        assertFalse(LedgerCalls.isLive(prediction("ACTIONABLE_NOW", ledgerJson("TARGET_HIT", "SUCCESS", "0.050000"))))
        assertEquals("Exited · Failed", LedgerCalls.closedLabel(closed))
        assertEquals(-0.03, LedgerCalls.closedReturn(closed)!!, 1e-9)
        assertFalse(LedgerCalls.isLive(prediction("INVALIDATED", null)))
        assertEquals("INVALIDATED", LedgerCalls.lifecycleWord(prediction("INVALIDATED", null)))
    }

    private fun prediction(lifecycleState: String, ledger: String?) = ActivePredictionDto.parse(JSONObject(
        """{"predictionId": 7, "symbol": "RENUKA", "lifecycleState": "$lifecycleState", "ledger": ${ledger ?: "null"}}"""
    ))

    private fun ledgerJson(status: String, outcome: String? = null, actual: String? = null) =
        """{"tipId": "t-7", "symbol": "RENUKA", "direction": "BUY", "firstSeenAt": "2026-09-21T11:00:00Z",
           "status": "$status", "outcome": ${outcome?.let { "\"$it\"" } ?: "null"},
           "actualReturn": ${actual?.let { "\"$it\"" } ?: "null"}}"""

    private fun point(ret: Double?, basis: String) = ProgressPointDto("2026-09-22", 2, "ENTERED", "ACTIVE", ret, ret, ret?.let { 0.0 }, 5.0, 4.0, "DAILY", basis)

    private fun headline(trust: Int?, total: Int, hit: Double?, invalidated: Int) =
        ScorecardHeadlineDto(total, 0, total, 0, 0, 0, invalidated, null, hit, null, trust)

    private fun engine(name: String, vararg tips: LedgerTipDto) = EngineCallsDto(1, name, 7, headline(null, tips.size, null, 0), tips.toList())

    private fun receipt(medium: String) = ReceiptRefDto("r-$medium", "TIP", medium, "UPSTOX", null, null, "2026-09-21T04:00:00Z")

    private fun tip(
        status: String = "ACTIVE",
        outcome: String? = null,
        reason: String? = null,
        actual: Double? = null,
        entryStatus: String = "ENTERED",
        progress: ProgressPointDto? = null,
        predictionId: Int? = null,
        seen: String = "2026-09-21T04:00:00Z",
        target: Double? = 110.0,
        stop: Double? = 95.0
    ) = LedgerTipDto(
        tipId = "t-$seen-$predictionId", symbol = "RENUKA", direction = "BUY", entryLow = 100.0, entryHigh = 100.0, entryBasis = "STATED",
        target = target, stopLoss = stop, horizonSessions = 5, horizonBasis = "STATED", firstSeenAt = seen, status = status,
        entryStatus = entryStatus, outcome = outcome, reason = reason, enteredSession = 1, closedSession = null, closedAt = null,
        exitPrice = null, promisedReturn = 0.1, actualReturn = actual, predictionId = predictionId, channel = null, caller = null,
        latestProgress = progress
    )
}
