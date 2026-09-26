package com.marksy.os.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksyCallsTest {
    private fun p(
        id: Int, asOf: String, terminal: Boolean, outcome: String = "PENDING", realized: Double? = null,
        superseded: Boolean = false, recommendation: Int? = id * 10, horizon: Int = 10, observed: Int? = null,
        life: String = if (terminal) "CLOSED" else "ACTIVE", resolved: Boolean = terminal
    ) = InstrumentPredictionEntryDto(
        predictionId = id, asOf = asOf, horizonDays = horizon, entryPrice = 100.0, targetPrice = 110.0, stopLoss = 95.0,
        probabilityAtPublication = .6, confidenceAtPublication = .7, lifecycleState = life,
        lifecycleDetail = "", isTerminal = terminal, currentPrice = null, currentReturn = null, targetProgress = null, stopProgress = null,
        outcomeStatus = outcome, realizedReturnPct = realized, hasResolvedOutcome = resolved, evidenceItemCount = 0,
        recommendationId = recommendation, observedDays = observed, isSupersededByRevision = superseded
    )

    @Test fun newestOpenCallLeadsAndClosedOnesAreHistory() {
        val v = MarksyCalls.view(listOf(
            p(1, "2026-08-01", terminal = true, "TARGET_HIT", 9.0),
            p(2, "2026-09-20", terminal = false),
            p(3, "2026-09-10", terminal = false),
            p(4, "2026-09-01", terminal = false, superseded = true)
        )) as MarksyCallView.Active
        assertEquals(2, v.primary.predictionId)
        assertEquals(listOf(3), v.others.map { it.predictionId })
        assertEquals(listOf(1), v.history.map { it.predictionId })
    }

    @Test fun onlyClosedCallsIsHistoryAndNothingIsNone() {
        assertTrue(MarksyCalls.view(listOf(p(1, "2026-08-01", terminal = true))) is MarksyCallView.HistoryOnly)
        assertEquals(MarksyCallView.None, MarksyCalls.view(emptyList()))
    }

    @Test fun trackRecordCountsOutcomesAndAveragesRealisedReturns() {
        val r = MarksyCalls.record(listOf(
            p(1, "a", true, "TARGET_HIT", 8.0), p(2, "b", true, "SUCCESS", 4.0),
            p(3, "c", true, "STOP_HIT", -5.0), p(4, "d", true, "HORIZON_EXPIRED", null)
        ))
        assertEquals(4, r.total)
        assertEquals(2, r.hit)
        assertEquals(1, r.stopped)
        assertEquals(1, r.expired)
        assertEquals(7.0 / 3, r.averageReturn!!, 1e-9)
    }

    /** Live shape (INVPRECQ, 2026-09-26): the outcome monitor invalidates calls whose outcome is still OPEN. */
    @Test fun invalidatedCallsCountOnceAndSayInvalidated() {
        val open = p(1, "a", true, "OPEN", life = "INVALIDATED", resolved = false)
        val won = p(2, "b", true, "SUCCESS", .1, life = "INVALIDATED")
        val stopped = p(3, "c", true, "STOP_LOSS_HIT", -4.0, life = "STOP_LOSS_HIT")
        val r = MarksyCalls.record(listOf(open, won, stopped))
        assertEquals(1, r.hit)
        assertEquals(1, r.stopped)
        assertEquals(1, r.invalidated)
        assertEquals("INVALIDATED", MarksyCalls.outcome(open))
        assertEquals("SUCCESS", MarksyCalls.outcome(won))
        assertEquals("PENDING", MarksyCalls.outcome(p(4, "d", false)))
    }

    @Test fun analysisComesFromTheLeadingCallOrTheNewestPastOne() {
        assertEquals(20, MarksyCalls.analysisId(listOf(p(1, "2026-08-01", true), p(2, "2026-09-20", false))))
        assertEquals(10, MarksyCalls.analysisId(listOf(p(1, "2026-08-01", true), p(5, "2026-07-01", true))))
        assertNull(MarksyCalls.analysisId(listOf(p(1, "2026-08-01", true, recommendation = null))))
    }

    @Test fun daysLeftIsHorizonMinusObservedSessions() {
        assertEquals(6, MarksyCalls.daysLeft(p(1, "x", false, horizon = 10, observed = 4)))
        assertNull(MarksyCalls.daysLeft(p(1, "x", false, observed = null)))
    }
}
