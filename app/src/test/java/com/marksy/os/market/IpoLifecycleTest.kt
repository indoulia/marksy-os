package com.marksy.os.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class IpoLifecycleTest {
    private val now = ZonedDateTime.of(2026, 10, 1, 10, 42, 0, 0, IpoLifecycle.IST)
    private fun v(x: Any?) = IpoValueDto(if (x == null) "MISSING" else "AVAILABLE", x, null)
    private fun ipo(
        id: String, stage: String, opens: String? = null, closes: String? = null, lists: String? = null, sme: Boolean = false,
        band: Pair<Int, Int>? = 412 to 434, lot: Int? = 34, odds: Double? = null
    ) = IpoListItemDto(
        id = id, companyName = "Co $id", issueName = null, isSme = sme, sector = "Healthcare", stage = stage,
        opensOn = v(opens), closesOn = v(closes), listsOn = v(lists),
        terms = IpoTermsDto(priceBand = band?.let { v(JSONObject().put("lower", it.first).put("upper", it.second)) }, lotSize = v(lot), issueSizeCrore = null),
        retailAllocation = odds?.let { IpoAllocationDto(it, null) }
    )

    private val book = listOf(
        ipo("today", "CLOSING_SOON", closes = "2026-10-01"), ipo("later", "OPEN", closes = "2026-10-05"),
        ipo("allot", "CLOSED", lists = "2026-10-07", odds = 0.25), ipo("up2", "UPCOMING", opens = "2026-10-09"),
        ipo("up1", "UPCOMING", opens = "2026-10-06", band = null, lot = null, sme = true), ipo("listed", "RECENTLY_LISTED", lists = "2026-09-28"),
        ipo("gone", "HANDED_OVER")
    )

    @Test fun lanesFollowStageAndCloseDate() {
        val lanes = IpoLifecycle.lanes(book, IpoLifecycle.StageFilter.ALL, IpoLifecycle.Board.ALL, "", emptySet(), now)
        assertEquals(
            listOf("TODAY" to listOf("today"), "OPEN" to listOf("later"), "ALLOTMENT" to listOf("allot"), "UPCOMING" to listOf("up1", "up2"), "LISTED" to listOf("listed")),
            lanes.map { (l, xs) -> l.name to xs.map { it.id } }
        )
        // A stage the server has not moved yet still stops taking bids at the 5 pm cutoff.
        assertEquals(IpoLifecycle.Lane.ALLOTMENT, IpoLifecycle.laneOf(book[0], now.withHour(17).withMinute(5)))
        assertEquals(IpoLifecycle.Lane.ALLOTMENT, IpoLifecycle.laneOf(book[1], now.plusDays(5)))
    }

    @Test fun filtersCombineBoardSearchAndWatching() {
        fun ids(stage: IpoLifecycle.StageFilter, board: IpoLifecycle.Board = IpoLifecycle.Board.ALL, q: String = "", w: Set<String> = emptySet()) =
            IpoLifecycle.lanes(book, stage, board, q, w, now).flatMap { it.second }.map { it.id }
        assertEquals(listOf("today", "later"), ids(IpoLifecycle.StageFilter.OPEN))
        assertEquals(listOf("up1"), ids(IpoLifecycle.StageFilter.ALL, IpoLifecycle.Board.SME))
        assertEquals(listOf("allot"), ids(IpoLifecycle.StageFilter.WATCHING, w = setOf("allot", "gone")))
        assertEquals(listOf("up2"), ids(IpoLifecycle.StageFilter.ALL, q = "co up2"))
    }

    @Test fun homeNoteCountsOpenAndClosingTodayOrNamesTheFilter() {
        assertEquals("IPOs · 2 open · 1 closing today", IpoLifecycle.homeNote(book, IpoLifecycle.StageFilter.ALL, IpoLifecycle.Board.ALL, "", now))
        assertEquals("IPOs · SME · Opens soon", IpoLifecycle.homeNote(book, IpoLifecycle.StageFilter.UPCOMING, IpoLifecycle.Board.SME, "", now))
        assertEquals("IPOs · 2 opening soon", IpoLifecycle.homeNote(book.filter { it.stage == "UPCOMING" }, IpoLifecycle.StageFilter.ALL, IpoLifecycle.Board.ALL, "", now))
    }

    @Test fun cardFactsFollowTheStage() {
        val sub = IpoSubscriptionDto("AVAILABLE", null, emptyMap(), mapOf("OVERALL" to IpoSubscriptionReading(2.31, null)))
        val today = IpoLifecycle.cardFacts(book[0].copy(subscription = sub), now)
        assertEquals("2.31x", today.stat)
        assertEquals("Bid and approve the UPI mandate by 5 pm · min ₹14,756", today.line)
        assertEquals("Closes today · 6 h 18 m left", today.chip)
        assertEquals("Price band not out yet · bids 6 Oct", IpoLifecycle.cardFacts(book[4], now).line)
        assertEquals("Retail about 1 in 4 · lists Wed 7 Oct", IpoLifecycle.cardFacts(book[2], now).line)
        assertEquals("closes 5 pm", IpoLifecycle.stageWord(book[0], now))
    }

    @Test fun gmpSummaryShowsEverySourceAsARange() {
        val one = IpoGmpDto("AVAILABLE", listOf(IpoGmpReading("ipoji.com", 48.0, null, "2026-10-01T04:45:00Z")))
        assertEquals("GMP +₹48 (+11%) · unofficial · 10:15", IpoLifecycle.gmpSummary(one, 434.0, now)!!.text)
        val many = IpoGmpDto("AVAILABLE", listOf(
            IpoGmpReading("a", 48.0, 11.1, "2026-10-01T04:45:00Z"), IpoGmpReading("b", 39.0, 9.0, "2026-09-30T12:00:00Z"), IpoGmpReading("c", -5.0, -1.2, null)
        ))
        val s = IpoLifecycle.gmpSummary(many, 434.0, now)!!
        assertEquals("GMP −1% to +11% · 3 sources · unofficial · 10:15", s.text)
        assertEquals(-5.0, s.lowestPremium, 1e-9)
        val agree = IpoGmpDto("AVAILABLE", listOf(IpoGmpReading("a", 48.0, 11.1, null), IpoGmpReading("b", 39.0, 9.0, null)))
        assertEquals("GMP +9–11% · 2 sources · unofficial", IpoLifecycle.gmpSummary(agree, 434.0, now)!!.text)
        assertNull(IpoLifecycle.gmpSummary(IpoGmpDto("EMPTY", emptyList()), 434.0, now))
    }

    @Test fun subscriptionDaysKeepEachCategorysLastReadingPerIstDay() {
        val sub = IpoSubscriptionDto("AVAILABLE", null, mapOf(
            "RETAIL" to listOf(IpoSubscriptionReading(0.5, "2026-09-29T06:00:00Z"), IpoSubscriptionReading(1.18, "2026-09-29T11:30:00Z"), IpoSubscriptionReading(2.41, "2026-09-30T11:30:00Z")),
            "QIB" to listOf(IpoSubscriptionReading(0.93, "2026-09-30T11:30:00Z"))
        ), emptyMap())
        val days = IpoLifecycle.subscriptionDays(sub)
        assertEquals(listOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30)), days.map { it.date })
        assertEquals(mapOf("RETAIL" to 1.18), days[0].values)
        assertEquals(mapOf("RETAIL" to 2.41, "QIB" to 0.93), days[1].values)
    }

    @Test fun lotCategoriesFollowRupeeLimits() {
        val main = IpoLifecycle.lotCategories(14_756.0, isSme = false)
        assertEquals(listOf(1 to 13, 14 to 67), main.take(2).map { it.minLots to it.maxLots })
        assertEquals(68, main[2].minLots)
        assertEquals(listOf(2 to 2, 3 to 50), IpoLifecycle.lotCategories(120_000.0, isSme = true).map { it.minLots to it.maxLots })
    }

    @Test fun calculatorWarnsThatMoreLotsDoNotRaiseOddsPastOneTimes() {
        val cats = IpoLifecycle.lotCategories(14_756.0, isSme = false)
        val q = IpoLifecycle.lotQuote(cats, cats[0], lots = 5, lotSize = 34, upper = 434.0, poolTimes = 3.92, lowestGmp = 40.0, poolName = "Retail")
        assertEquals(73_780.0, q.amount, 1e-6)
        assertEquals("About 1 in 4", q.chance)
        assertEquals(1, q.gainLots)
        assertEquals(1_360.0, q.gain!!, 1e-6)
        assertEquals("Retail is 3.92x subscribed, so allotment is a lottery for 1 lot. Bidding 5 lots blocks ₹73,780 for the same chance as 1 lot (₹14,756).", q.callout)
        assertNull(IpoLifecycle.lotQuote(cats, cats[0], 5, 34, 434.0, 0.8, null, "Retail").callout)
        assertEquals("SHNI", IpoLifecycle.lotQuote(cats, cats[0], 13, 34, 434.0, null, null, "Retail").next?.key)
    }

    @Test fun reminderEventsSkipTimesAlreadyPast() {
        val dates = mapOf("open" to LocalDate.of(2026, 9, 29), "close" to LocalDate.of(2026, 10, 1), "allot" to LocalDate.of(2026, 10, 5))
        assertEquals(listOf("close", "allot"), IpoLifecycle.reminderEvents(dates, now).map { it.key })
        assertEquals(listOf("allot"), IpoLifecycle.reminderEvents(dates, now.withHour(15).withMinute(1)).map { it.key })
        assertEquals("ipo|kaveri|close", IpoLifecycle.reminderKey("kaveri", "close"))
    }
}
