package com.marksy.os.ui

import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.ProgressPointDto
import com.marksy.os.market.SeriesPointDto
import com.marksy.os.market.TrustBandDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorecardGraphicsTest {
    @Test
    fun ringsFillByScoreWithASliverMinimumAndBandRedAmberGreen() {
        assertEquals(0f, ScorecardGraphics.ringFraction(null))
        assertEquals(.03f, ScorecardGraphics.ringFraction(0.0), 1e-6f)
        assertEquals(.59f, ScorecardGraphics.ringFraction(59.0), 1e-6f)
        assertEquals(1f, ScorecardGraphics.ringFraction(140.0))

        assertEquals(MarksyTheme.TextMuted, ScorecardGraphics.tone(null))
        assertEquals(MarksyTheme.RedUrgent, ScorecardGraphics.tone(29.9))
        assertEquals(MarksyTheme.YellowImportant, ScorecardGraphics.tone(30.0))
        assertEquals(MarksyTheme.YellowImportant, ScorecardGraphics.tone(59.0))
        assertEquals(MarksyTheme.PrimaryEmerald, ScorecardGraphics.tone(60.0))
    }

    @Test
    fun theHeroBarIsTheHitRateIntervalWithTheObservedRateInsideIt() {
        val band = TrustBandDto(0.017302, 0.198211)
        val bar = ScorecardGraphics.hitRateBand(band, hitRatePct = 6.25)!!

        assertEquals(.017302f, bar.low, 1e-6f)
        assertEquals(.198211f, bar.high, 1e-6f)
        assertEquals(.0625f, bar.marker!!, 1e-6f)
        assertTrue(bar.marker in bar.low..bar.high)
        assertEquals("Hit-rate range 2–20%", ScorecardGraphics.hitRateRangeText(band))
        assertNull(ScorecardGraphics.hitRateBand(null, 6.25))
        assertNull(ScorecardGraphics.hitRateBand(band, null)!!.marker)
        ScorecardGraphics.hitRateBand(TrustBandDto(-0.1, 1.2), 140.0)!!.let { assertEquals(Triple(0f, 1f, 1f), Triple(it.low, it.high, it.marker)) }
    }

    @Test
    fun donutSegmentsStartWhereThePreviousEndedAndCloseTheTurn() {
        val segments = ScorecardGraphics.donut(listOf(44, 202, 52, 348, 773))
        val total = 1419f

        assertEquals(0f, segments.first().first)
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.first + a.second, b.first, 1e-6f) }
        assertEquals(1f, segments.last().first + segments.last().second, 1e-5f)
        assertEquals(202 / total, segments[1].second, 1e-6f)
        assertEquals(listOf(0f to 0f, 0f to 0f), ScorecardGraphics.donut(listOf(0, 0)))
    }

    @Test
    fun seriesMapAcrossByIndexAndDownFromTheHighestValue() {
        val (lo, hi) = ScorecardGraphics.bounds(listOf(-2.0, -5.0), listOf(4.0, 7.0))
        assertEquals(-5.0 to 7.0, lo to hi)

        val points = ScorecardGraphics.chartPoints(listOf(0.0, -5.0, 7.0), lo, hi)
        assertEquals(listOf(0f, .5f, 1f), points.map { it.first })
        assertEquals(7f / 12, points[0].second, 1e-6f)
        assertEquals(1f, points[1].second, 1e-6f)
        assertEquals(0f, points[2].second, 1e-6f)
        // A flat series still gets a span, so zero sits mid-chart.
        assertEquals(.5f, ScorecardGraphics.chartPoints(listOf(0.0), -1.0, 1.0).single().second, 1e-6f)
        assertEquals(-1.0 to 1.0, ScorecardGraphics.bounds(listOf(0.0, 0.0)))
    }

    @Test
    fun aFarPromisedLineDoesNotStretchTheReturnsScale() {
        // Realised −2.6..0 against +5 promised: the scale keeps to realised plus a fifth of headroom for the pinned line.
        val (lo, hi) = ScorecardGraphics.returnBounds(listOf(-2.6, -2.1), listOf(5.0, 5.0))
        assertEquals(-2.6, lo, 1e-9)
        assertEquals(.52, hi, 1e-9)
        // A promised line within half a span still widens the scale so it draws at its true level.
        assertEquals(-2.0 to 2.0, ScorecardGraphics.returnBounds(listOf(-2.0, 1.0), listOf(2.0)))
    }

    @Test
    fun theChartPlotsRunningAveragesAndARangeAveragesOnlyItsOwnCloses() {
        val series = listOf(
            SeriesPointDto("2026-06-01T10:00:00Z", -0.01, 0.02, 1, realisedAvg = -0.01, promisedAvg = 0.02),
            SeriesPointDto("2026-09-01T10:00:00Z", -0.03, 0.05, 1, realisedAvg = -0.015, promisedAvg = 0.025),
            SeriesPointDto("2026-09-28T10:00:00Z", -0.04, 0.09, 2, realisedAvg = -0.01, promisedAvg = 0.0225)
        )
        fun assertPoints(expected: List<Pair<Double, Double>>, actual: List<Pair<Double, Double>>) {
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEach { (e, a) -> assertEquals(e.first, a.first, 1e-9); assertEquals(e.second, a.second, 1e-9) }
        }

        assertPoints(listOf(-1.0 to 2.0, -1.5 to 2.5, -1.0 to 2.25), ScorecardGraphics.window(series, ReturnRange.ALL))
        // 1M starts at 1 Sep: its means leave out the June close.
        assertPoints(listOf(-2.0 to 3.0, -1.0 to 7.0 / 3), ScorecardGraphics.window(series, ReturnRange.M1))
        // An older backend without the means: the whole series divides its sums by the closes so far.
        assertPoints(listOf(-1.0 to 2.0, -1.5 to 2.5, -1.0 to 2.25), ScorecardGraphics.window(series.map { it.copy(realisedAvg = null, promisedAvg = null) }, ReturnRange.ALL))
        assertEquals(emptyList<Pair<Double, Double>>(), ScorecardGraphics.window(emptyList(), ReturnRange.W1))
    }

    @Test
    fun theGaugeDrawsAShortfallBelowZeroRedBySizeAndKeepsBandsAboveIt() {
        assertEquals(.43f to MarksyTheme.RedUrgent, ScorecardGraphics.gauge(-43.0))
        assertEquals(1f to MarksyTheme.RedUrgent, ScorecardGraphics.gauge(-150.0))
        assertEquals(.38f to MarksyTheme.YellowImportant, ScorecardGraphics.gauge(38.0))
        assertEquals(1f to MarksyTheme.PrimaryEmerald, ScorecardGraphics.gauge(250.0))
        assertEquals(0f to MarksyTheme.TextMuted, ScorecardGraphics.gauge(null))
    }

    @Test
    fun aSparklineFromCountsEndsAtHitsLessStopsAndIsFlatWithoutCompletions() {
        val line = ScorecardGraphics.sparkline(hits = 2, stops = 6, expired = 2)
        assertEquals(11, line.size)
        assertEquals(0.0, line.first(), 1e-9)
        assertEquals(-4.0, line.last(), 1e-9)
        assertEquals(listOf(0.0, 0.0), ScorecardGraphics.sparkline(0, 0, 0))

        val long = ScorecardGraphics.sparkline(44, 202, 52)
        assertEquals(16, long.size)
        assertEquals(-158.0, long.last(), 1e-9)
    }

    @Test
    fun aCallSitsBetweenStopAndTargetByExitOrLastReturn() {
        fun tip(direction: String, stop: Double, target: Double, exit: Double? = null, returnToDate: Double? = null) = LedgerTipDto(
            "t", "X", direction, 100.0, 100.0, null, target, stop, 5, null, "2026-09-01T00:00:00Z", if (exit == null) "ACTIVE" else "TARGET_HIT",
            null, null, null, null, null, null, exit, null, null, null, null, null,
            returnToDate?.let { ProgressPointDto("2026-09-02", 1, "ENTERED", "ACTIVE", it, null, null, null, null, "", "") }
        )

        assertEquals(.5f, ScorecardGraphics.entryPosition(tip("BUY", 90.0, 110.0))!!, 1e-6f)
        assertEquals(.75f, ScorecardGraphics.levelPosition(tip("BUY", 90.0, 110.0, returnToDate = .05))!!, 1e-6f)
        assertEquals(1f, ScorecardGraphics.levelPosition(tip("BUY", 90.0, 110.0, exit = 120.0))!!, 1e-6f)
        assertEquals(.25f, ScorecardGraphics.levelPosition(tip("BUY", 90.0, 110.0).copy(status = "STOP_LOSS_HIT", actualReturn = -.05))!!, 1e-6f)
        // A short's target is below its stop: a 5% gain moves it towards the target.
        assertEquals(.75f, ScorecardGraphics.levelPosition(tip("SELL", 110.0, 90.0, returnToDate = .05))!!, 1e-6f)
        assertEquals(.5f, ScorecardGraphics.levelPosition(tip("BUY", 90.0, 110.0))!!, 1e-6f)
        assertNull(ScorecardGraphics.levelPosition(tip("BUY", 100.0, 100.0)))
        assertTrue(ScorecardGraphics.shares(listOf(0, 0)).all { it == 0f })
    }
}
