package com.marksy.os.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PlanRulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int = 9) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L

    @Test fun criticalFromOneDayBeforeUntilDone() {
        val due = at(2026, 10, 6)
        assertFalse(PlanRules.isCritical(PlanStatus.TODO, due, due - 2 * day))
        assertTrue(PlanRules.isCritical(PlanStatus.TODO, due, due - day))
        assertTrue(PlanRules.isCritical(PlanStatus.DOING, due, due + 5 * day))
        assertFalse(PlanRules.isCritical(PlanStatus.DONE, due, due))
        assertFalse(PlanRules.isCritical(PlanStatus.TODO, null, due))
    }

    @Test fun duesAlertThreeDaysOneDayAndOnTheDayButFollowUpsOnlyAtTheirTime() {
        val due = at(2026, 10, 6)
        assertEquals(listOf(due - 3 * day, due - day, due), PlanRules.alertTimes(PlanKind.CARD_DUE, due, now = at(2026, 9, 25)))
        assertEquals(listOf(due), PlanRules.alertTimes(PlanKind.BILL, due, now = due - 12 * 60 * 60 * 1000L))
        val followUp = at(2026, 9, 25, 18)
        assertEquals(listOf(followUp), PlanRules.alertTimes(PlanKind.FOLLOW_UP, followUp, now = at(2026, 9, 25, 16)))
    }

    @Test fun repeatsRollForwardAndClampShortMonths() {
        assertEquals(at(2026, 11, 6), PlanRules.next(at(2026, 10, 6), Recurrence.MONTHLY, zone))
        assertEquals(at(2027, 2, 28), PlanRules.next(at(2027, 1, 31), Recurrence.MONTHLY, zone))
        assertEquals(at(2027, 10, 6), PlanRules.next(at(2026, 10, 6), Recurrence.YEARLY, zone))
        assertEquals(null, PlanRules.next(at(2026, 10, 6), Recurrence.NONE, zone))
    }

    @Test fun nextBirthdayIsTodayOrLater() {
        val now = at(2026, 9, 25, 16)
        assertEquals(at(2027, 3, 14), PlanRules.nextBirthday(3, 14, now, zone))
        assertEquals(at(2026, 12, 1), PlanRules.nextBirthday(12, 1, now, zone))
        assertEquals(at(2026, 9, 25), PlanRules.nextBirthday(9, 25, now, zone))
    }
}
