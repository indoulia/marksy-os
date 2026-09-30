package com.marksy.os.alerts

import com.marksy.os.market.TipAlertDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TipAlertNoticesTest {
    private fun alert(id: Long, type: String = "TIP_NEW", unread: Boolean = true) =
        TipAlertDto(id, type, "RENUKA BUY call from Upstox: new call", "tip-$id", 1_790_740_800_000L + id, unread)

    @Test
    fun theFirstPollOnlyRemembersWhereItIsSoHistoryNeverFloods() {
        val plan = TipAlertNotices.plan(listOf(alert(7), alert(9)), lastSeenId = null)

        assertEquals(emptyList<TipAlertDto>(), plan.notify)
        assertEquals(9L, plan.lastSeenId)
    }

    @Test
    fun onlyUnreadAlertsNewerThanTheLastSeenAreNotifiedNewestFirst() {
        val plan = TipAlertNotices.plan(listOf(alert(5), alert(12, unread = false), alert(11), alert(10, "TIP_CLOSED")), lastSeenId = 9)

        assertEquals(listOf(11L, 10L), plan.notify.map { it.id })
        assertEquals(12L, plan.lastSeenId)
        assertFalse(plan.summary)
        assertEquals(TipAlertNotices.plan(listOf(alert(12)), lastSeenId = 12).notify, emptyList<TipAlertDto>())
    }

    @Test
    fun moreThanThreeNewAlertsAreGroupedUnderASummary() {
        val three = TipAlertNotices.plan((1L..3L).map { alert(it) }, lastSeenId = 0)
        val four = TipAlertNotices.plan((1L..4L).map { alert(it) }, lastSeenId = 0)

        assertFalse(three.summary)
        assertTrue(four.summary)
        assertEquals("4 tip alerts", TipAlertNotices.summaryTitle(four.notify))
        assertEquals(listOf("New call", "Entry reached", "Call closed"), listOf("TIP_NEW", "TIP_ENTERED", "TIP_CLOSED").map(TipAlertNotices::title))
    }
}
