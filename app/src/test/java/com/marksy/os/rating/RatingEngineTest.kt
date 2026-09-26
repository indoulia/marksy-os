package com.marksy.os.rating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingEngineTest {
    private fun short(r: RatingResult) = r.ratings.first { it.horizon == Horizon.SHORT }
    private fun long(r: RatingResult) = r.ratings.first { it.horizon == Horizon.LONG }

    private val bullish = RatingInputs(
        price = 120.0, technicalBull = 12, technicalBear = 1, technicalTotal = 14, return3m = 15.0, nifty3m = 2.0, yearLow = 80.0, yearHigh = 125.0,
        callUpsidePct = 8.0, callProbability = .75,
        pe = 12.0, sectorPe = 20.0, pb = 1.5, sectorPb = 2.5, roe = 22.0, sectorRoe = 14.0, roce = 24.0, sectorRoce = 15.0,
        revenueGrowthYoy = 18.0, profitGrowthYoy = 25.0, promoterChange = .5, fiiChange = 1.0, volatility = 22.0, monthAverage = 2.0, monthNegativeShare = .3
    )

    private val bearish = RatingInputs(
        price = 80.0, technicalBull = 0, technicalBear = 13, technicalTotal = 14, return3m = -20.0, nifty3m = 1.0, yearLow = 78.0, yearHigh = 150.0,
        callUpsidePct = -6.0, callProbability = .7,
        pe = 45.0, sectorPe = 18.0, pb = 6.0, sectorPb = 2.0, roe = 4.0, sectorRoe = 15.0, roce = 5.0, sectorRoce = 16.0,
        revenueGrowthYoy = -12.0, profitGrowthYoy = -40.0, promoterChange = -2.0, fiiChange = -1.5, volatility = 24.0, monthAverage = -3.0, monthNegativeShare = .8
    )

    @Test fun strongInputsRateBuyAndWeakOnesSell() {
        val up = RatingEngine.rate(bullish)
        assertEquals(Verdict.BUY, short(up).verdict)
        assertEquals(Verdict.BUY, long(up).verdict)
        val down = RatingEngine.rate(bearish)
        assertEquals(Verdict.SELL, short(down).verdict)
        assertEquals(Verdict.SELL, long(down).verdict)
    }

    @Test fun conflictingFactorsRateHold() {
        val mixed = bullish.copy(technicalBull = 2, technicalBear = 10, return3m = -8.0, callUpsidePct = null, callProbability = null, pe = 30.0, sectorPe = 20.0, revenueGrowthYoy = 2.0, profitGrowthYoy = -3.0)
        assertEquals(Verdict.HOLD, long(RatingEngine.rate(mixed)).verdict)
    }

    @Test fun tooLittleDataSaysSo() {
        val r = RatingEngine.rate(RatingInputs(monthAverage = 3.0, monthNegativeShare = .2))
        assertEquals(Verdict.NOT_ENOUGH_DATA, short(r).verdict)
        assertEquals(Verdict.NOT_ENOUGH_DATA, long(r).verdict)
    }

    /** Parity fixture: a backend port must produce exactly this. Short weights: trend .35, call .30. */
    @Test fun goldenShortTermScore() {
        val r = short(RatingEngine.rate(RatingInputs(technicalBull = 10, technicalBear = 2, technicalTotal = 14, callUpsidePct = 5.0, callProbability = .8)))
        // Trend = (10 - 2) / 14 = 0.5714; call = +0.8; score = (.35 × .5714 + .30 × .8) / .65 = .6769.
        assertEquals(.6769, r.score, 1e-4)
        assertEquals(Verdict.BUY, r.verdict)
        assertEquals(.65, r.coverage, 1e-9)
        assertEquals(.65, r.confidence, 1e-9)
    }

    @Test fun highVolatilityPullsTowardsHoldAndLowersConfidence() {
        val calm = short(RatingEngine.rate(bullish))
        val wild = short(RatingEngine.rate(bullish.copy(volatility = 70.0)))
        assertTrue(wild.score < calm.score)
        assertTrue(wild.confidence < calm.confidence)
    }

    @Test fun driversRankTheBiggestContributionsWithReasons() {
        val r = short(RatingEngine.rate(bearish))
        assertEquals(Factor.TREND, r.against.first().factor)
        assertTrue(r.against.first().reason.isNotBlank())
        assertTrue(r.support.isEmpty())
    }
}
