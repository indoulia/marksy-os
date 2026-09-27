package com.marksy.os.rating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RatingCalibrationTest {
    // Trend is carried by the technical counts; seasonality by the month average. Only one of them predicts the return.
    private fun sample(trend: Double, season: Double, ret: Double) = RatingCalibration.Sample(
        RatingInputs(price = 100.0, technicalBull = (5 + 5 * trend).toInt(), technicalBear = (5 - 5 * trend).toInt(), technicalTotal = 10,
            monthAverage = season * 4, monthNegativeShare = .5 - season / 2, callUpsidePct = 5.0),
        realizedReturn = ret
    )

    @Test fun weightMovesToTheFactorThatPredictsAndIsAdoptedOnlyWithAHeldOutGain() {
        val r = Random(7)
        // V1 leans on trend; here only seasonality predicts, so tuning must shift weight to it by a clear held-out margin.
        val samples = (0 until 300).map { val t = r.nextDouble(-1.0, 1.0); val s = r.nextDouble(-1.0, 1.0); sample(t, s, ret = s * .03 + r.nextDouble(-.01, .01)) }

        val result = RatingCalibration.calibrate(samples)

        assertTrue(result.adopted)
        val w = result.config.weights.getValue(Horizon.SHORT)
        assertTrue(w.getValue(Factor.SEASONALITY) > RatingConfig.V1.weights.getValue(Horizon.SHORT).getValue(Factor.SEASONALITY))
        assertTrue(result.test > result.baseTest)
        assertEquals(RatingConfig.V1.weights.getValue(Horizon.LONG), result.config.weights.getValue(Horizon.LONG))
    }

    @Test fun noiseKeepsTheCurrentWeights() {
        val r = Random(11)
        val samples = (0 until 300).map { sample(r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0), ret = r.nextDouble(-.03, .03)) }

        val result = RatingCalibration.calibrate(samples)

        assertEquals(false, result.adopted)
        assertEquals(RatingConfig.V1, result.config)
    }

    @Test fun spearmanOfPerfectOrderIsOne() {
        assertEquals(1.0, RatingCalibration.spearman(listOf(1.0, 2.0, 3.0), listOf(10.0, 20.0, 30.0)), 1e-9)
        assertEquals(-1.0, RatingCalibration.spearman(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0)), 1e-9)
    }
}
