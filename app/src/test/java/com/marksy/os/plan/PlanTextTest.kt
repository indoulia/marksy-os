package com.marksy.os.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PlanTextTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(2026, 9, 25, 16)

    @Test fun dueLabelsReadNaturally() {
        assertEquals("Today", PlanText.dueLabel(at(2026, 9, 25), now, zone))
        assertEquals("Tomorrow", PlanText.dueLabel(at(2026, 9, 26), now, zone))
        assertEquals("In 3 days · 28 Sep", PlanText.dueLabel(at(2026, 9, 28), now, zone))
        assertEquals("6 Oct", PlanText.dueLabel(at(2026, 10, 6), now, zone))
        assertEquals("3 Jan 2027", PlanText.dueLabel(at(2027, 1, 3), now, zone))
        assertEquals("Overdue · yesterday", PlanText.dueLabel(at(2026, 9, 24), now, zone))
        assertEquals("Overdue · 31 days", PlanText.dueLabel(at(2026, 8, 25), now, zone))
        assertEquals("Today · 6:30 PM", PlanText.dueLabel(at(2026, 9, 25, 18, 30), now, zone))
        assertNull(PlanText.dueLabel(null, now, zone))
    }

    @Test fun amountsUseIndianGroupingAndDropZeroPaise() {
        assertEquals("₹14,917", PlanText.amount(1_491_700L))
        assertEquals("₹1,25,000.50", PlanText.amount(12_500_050L))
        assertNull(PlanText.amount(null))
    }
}
