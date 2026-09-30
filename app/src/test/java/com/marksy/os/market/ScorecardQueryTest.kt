package com.marksy.os.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ScorecardQueryTest {
    private val sep1 = LocalDate.of(2026, 9, 1)
    private val sep30 = LocalDate.of(2026, 9, 30)

    // marksy-api 422s a custom range missing a date, running backwards, or mixed with a preset (§8.3).
    @Test
    fun aCustomRangeNeedsBothDatesAndReplacesThePreset() {
        val custom = ScorecardQuery(period = ScorecardPeriod.CUSTOM, startDate = sep1)

        assertNull(custom.filterParams())
        assertNull(custom.copy(endDate = sep1.minusDays(1)).filterParams())
        assertEquals("period=CUSTOM&startDate=2026-09-01&endDate=2026-09-30", custom.copy(endDate = sep30).filterParams())
        assertEquals("1 Sep – 30 Sep", custom.copy(endDate = sep30).label())

        val preset = custom.copy(period = ScorecardPeriod.LAST_7_TRADING_DAYS, endDate = sep30, horizon = HorizonBucket.UP_TO_1_WEEK, callerId = 9)
        assertEquals("period=LAST_7_TRADING_DAYS&horizon=UP_TO_1_WEEK&callerId=9", preset.filterParams())
        assertEquals("Last 7 trading days · Up to 1 week", preset.label())
        assertEquals("", ScorecardQuery().filterParams())
        assertEquals(preset, ScorecardQuery.decode(preset.encode()))
        assertEquals(ScorecardQuery(), ScorecardQuery.decode("garbage"))
    }
}
