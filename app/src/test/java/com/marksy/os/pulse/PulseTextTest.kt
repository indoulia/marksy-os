package com.marksy.os.pulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PulseTextTest {
    @Test fun lockScreenVersionCarriesCountsAndMarketButNoNamesOrAmounts() {
        val p = PulseText.build(total = 166, attention = 2, busiest = listOf("Meesho" to 22), next = "CRED bill ₹14,364 · 5 Oct", index = "NIFTY 50" to 0.34)

        assertEquals("2 need your attention · NIFTY 50 +0.34%", p.title)
        assertEquals("166 notifications today. Busiest: Meesho (22). Next: CRED bill ₹14,364 · 5 Oct", p.text)
        assertEquals("166 notifications today, 2 need attention · NIFTY 50 +0.34%", p.publicText)
        assertFalse("CRED" in p.publicText || "Meesho" in p.publicText)
    }

    @Test fun quietDayReadsAsAllClear() {
        val p = PulseText.build(total = 0, attention = 0, busiest = emptyList(), next = null, index = null)
        assertEquals("All clear", p.title)
        assertEquals("No notifications captured yet today.", p.text)
    }
}
