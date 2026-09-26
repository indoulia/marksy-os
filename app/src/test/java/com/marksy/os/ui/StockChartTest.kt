package com.marksy.os.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StockChartTest {
    @Test fun priceTicksAreRoundStepsInsideTheRange() {
        assertEquals(listOf(1200.0, 1400.0, 1600.0), ChartAxis.priceTicks(1039.0, 1611.8))
        assertEquals(listOf(244.0, 245.0, 246.0), ChartAxis.priceTicks(243.2, 246.9))
        assertEquals(listOf(12.5), ChartAxis.priceTicks(12.5, 12.5))
    }

    @Test fun axisPriceDropsDecimalsForWholeSteps() {
        assertEquals("1,200", ChartAxis.price(1200.0, step = 100.0))
        assertEquals("12.25", ChartAxis.price(12.25, step = 0.25))
    }

    @Test fun timeTicksSpanFirstToLastCandle() {
        assertEquals(listOf(0, 24, 49, 74), ChartAxis.timeTicks(75))
        assertEquals(listOf(0, 1), ChartAxis.timeTicks(2))
    }

    @Test fun touchMapsToTheNearestCandle() {
        assertEquals(0, ChartAxis.index(-5f, width = 300f, size = 4))
        assertEquals(1, ChartAxis.index(140f, width = 300f, size = 4))
        assertEquals(3, ChartAxis.index(400f, width = 300f, size = 4))
    }
}
