package com.marksy.os.ui

import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.AlsoTippedDto
import com.marksy.os.market.CallerRefDto
import com.marksy.os.market.ChannelRefDto
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MarksyViewDto
import com.marksy.os.market.MyTipDto
import com.marksy.os.market.ScorecardHeadlineDto
import com.marksy.os.market.ScorecardSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TipTrackTest {
    private val today = LocalDate.of(2026, 10, 16)
    private val channel = ChannelRefDto(4, "Swing Picks", "TELEGRAM_CHANNEL")

    private fun tip(
        status: String = "ACTIVE", entryStatus: String? = "ENTERED", direction: String = "BUY", low: Double? = 4460.0, high: Double? = 4460.0,
        stop: Double? = 4310.0, target: Double? = 4780.0, exit: Double? = null, closedAt: String? = null, buyBy: String? = null,
        horizonEndsOn: String? = null, sessionsElapsed: Int? = null, entered: Double? = null, recommended: Double? = null,
        marksy: Double? = null, repeats: Int? = null, alsoBy: List<AlsoTippedDto> = emptyList(), actual: Double? = null
    ) = LedgerTipDto(
        tipId = "t1", symbol = "HAL", direction = direction, entryLow = low, entryHigh = high, entryBasis = "STATED", target = target, stopLoss = stop,
        horizonSessions = 15, horizonBasis = "STATED", firstSeenAt = "2026-10-13T05:12:00Z", status = status, entryStatus = entryStatus, outcome = null,
        reason = null, enteredSession = null, closedSession = null, closedAt = closedAt, exitPrice = exit, promisedReturn = null, actualReturn = actual,
        predictionId = null, channel = channel, caller = CallerRefDto(9, "Rahul K."), latestProgress = null,
        recommendedPrice = recommended, enteredPrice = entered, buyBy = buyBy, horizonEndsOn = horizonEndsOn, sessionsElapsed = sessionsElapsed,
        marksyView = marksy?.let { MarksyViewDto(it, "AGREES", null) }, repeats = repeats, alsoBy = alsoBy
    )

    private fun also(channelId: Int, callerId: Int? = null) = AlsoTippedDto(
        "o$channelId", ChannelRefDto(channelId, "Options Desk", "WHATSAPP_GROUP"), callerId?.let { CallerRefDto(it, "Rahul K.") },
        "2026-10-11T04:00:00Z", 4398.0, "ACTIVE", "ENTERED", 0.0301, null
    )

    // The 2×2 block's fourth slot follows the tip's state; a date the backend does not send leaves the slot out.
    @Test
    fun priceBlockFourthSlotFollowsTheTipState() {
        assertNull(TipTrack.slot(tip(entryStatus = "WAITING")))
        assertEquals(ExitSlot("BUY BY", "17 Oct", exited = false), TipTrack.slot(tip(entryStatus = "WAITING", buyBy = "2026-10-17")))
        assertNull(TipTrack.slot(tip()))
        assertEquals("EXITS BY" to "30 Oct · day 3/15", TipTrack.slot(tip(horizonEndsOn = "2026-10-30", sessionsElapsed = 3))!!.let { it.caption to it.text })
        assertEquals("EXIT" to "₹4,780.00", TipTrack.slot(tip(status = "TARGET_HIT", exit = 4780.0))!!.let { it.caption to it.text })
        assertEquals("HORIZON END", TipTrack.slot(tip(status = "HORIZON_EXPIRED", exit = 4471.0))!!.caption)
        assertEquals(ExitSlot("VOIDED", "30 Sep", exited = false), TipTrack.slot(tip(status = "INVALIDATED", entryStatus = "WAITING", closedAt = "2026-09-30T09:00:00Z")))
    }

    @Test
    fun priceTrackShowsOnlyWhatTheTipHas() {
        val waiting = TipTrack.of(tip(entryStatus = "WAITING", low = 410.0, high = 418.0, stop = 400.0, target = 450.0), live = 421.1)
        assertEquals("₹410–418", waiting.entryText)
        assertFalse(waiting.bought)
        assertNull(waiting.entryRef)
        assertNull(waiting.returnFraction)
        assertNull(waiting.recommendedText)
        assertEquals("₹421.10", waiting.currentText)
        assertTrue(waiting.live)

        val bought = TipTrack.of(tip(entered = 4460.0, recommended = 4452.0), live = 4530.4)
        assertEquals("₹4,460.00", bought.entryText)
        assertEquals(4460.0, bought.entryRef!!, 0.0)
        assertEquals((4530.4 - 4460) / 4460, bought.returnFraction!!, 1e-9)
        assertEquals(TrackTone.POSITIVE, bought.tone)

        val voided = TipTrack.of(tip(status = "INVALIDATED", entryStatus = "WAITING"), live = 4400.0)
        assertTrue(voided.muted)
        assertFalse(voided.live)
        assertEquals(TrackTone.MUTED, voided.tone)

        val closed = TipTrack.of(tip(status = "STOP_LOSS_HIT", exit = 4310.0, actual = -0.0336), live = null)
        assertEquals(-0.0336, closed.returnFraction!!, 1e-9)
        assertNull(closed.current)
        assertFalse(closed.live)
    }

    // A six-digit price keeps paise and Indian grouping; a SELL's target sits on the right like a BUY's.
    @Test
    fun pricesFormatAndSellTracksMirror() {
        assertEquals("₹1,30,450.00", TipTrack.of(tip(recommended = 130450.0)).recommendedText)
        assertEquals("₹23.40–23.60", TipTrack.zone(23.4, 23.6))
        assertEquals("₹410.00", TipTrack.zone(410.0, 410.0))
        val sell = TipTrack.of(tip(direction = "SELL", low = 411.5, high = 411.5, stop = 425.0, target = 390.0, entered = 411.5), live = 404.2)
        assertEquals(0f, sell.at(425.0)!!, 0f)
        assertEquals(1f, sell.at(390.0)!!, 0f)
        assertEquals(0.6f, sell.at(404.0)!!, 1e-4f)
        assertEquals((411.5 - 404.2) / 411.5, sell.returnFraction!!, 1e-9)
        assertTrue(sell.fillPositive)
    }

    @Test
    fun statusWordsUseTipVocabulary() {
        assertEquals("Waiting to buy", TipTrack.status(tip(entryStatus = "WAITING")))
        assertEquals("Active", TipTrack.status(tip()))
        assertEquals("Voided before buy", TipTrack.status(tip(status = "INVALIDATED")))
        assertEquals("Stop-loss hit", TipTrack.status(tip(status = "STOP_LOSS_HIT")))
        assertEquals(TrackTone.WARNING, TipTrack.statusTone(tip(entryStatus = "WAITING")))
    }

    // Score bands: 65+ positive, 45+ warning, else negative; Marksy's score is its probability × 100 and absent without a view.
    @Test
    fun scoreChipsMapToBands() {
        assertEquals(TrackTone.POSITIVE, TipTrack.band(65))
        assertEquals(TrackTone.WARNING, TipTrack.band(64))
        assertEquals(TrackTone.WARNING, TipTrack.band(45))
        assertEquals(TrackTone.NEGATIVE, TipTrack.band(44))
        assertEquals(72, TipTrack.marksyScore(tip(marksy = 0.724)))
        assertNull(TipTrack.marksyScore(tip()))
        assertNull(TipTrack.marksyChip(tip(), null))

        val headline = { trust: Int? -> ScorecardHeadlineDto(10, 0, 10, 4, 6, 0, 0, null, 40.0, null, trust) }
        val item = { channelTrust: Int?, callerTrust: Int? -> MyTipDto(tip(), headline(channelTrust), headline(callerTrust), emptyList(), 0) }
        assertEquals(ScoreChip("Trust", 58, TrackTone.WARNING, ScorecardSource("channel", 4, "Swing Picks")), TipTrack.trustChip(item(58, 64)))
        assertEquals(ScorecardSource("caller", 9, "Rahul K."), TipTrack.trustChip(item(null, 64))!!.source)
        assertNull(TipTrack.trustChip(item(null, null)))
    }

    @Test
    fun sourcesChipCountsOtherSourcesOrRepeats() {
        assertNull(TipTrack.sourcesChip(tip()))
        assertEquals("+2 sources", TipTrack.sourcesChip(tip(alsoBy = listOf(also(5), also(6), also(6))))!!.label)
        assertEquals("+1 source", TipTrack.sourcesChip(tip(alsoBy = listOf(also(5), also(4, 9))))!!.label)
        assertEquals("given 2×", TipTrack.sourcesChip(tip(alsoBy = listOf(also(4, 9))))!!.label)
        assertEquals("given 3×", TipTrack.sourcesChip(tip(repeats = 3))!!.label)
        assertEquals("Options Desk · given again", TipTrack.alsoSource(tip(), also(4, 9)))
        assertEquals("11 Oct · 2 days earlier", TipTrack.alsoWhen(tip(), also(5)))
    }

    // Buy shows only while the entry can still be taken: waiting, and not past its buy-by day.
    @Test
    fun buyIsActionableOnlyWhileWaiting() {
        assertTrue(TipTrack.buyable(tip(entryStatus = "WAITING"), today))
        assertTrue(TipTrack.buyable(tip(entryStatus = "WAITING", buyBy = "2026-10-16"), today))
        assertFalse(TipTrack.buyable(tip(entryStatus = "WAITING", buyBy = "2026-10-15"), today))
        assertFalse(TipTrack.buyable(tip(), today))
        assertFalse(TipTrack.buyable(tip(status = "INVALIDATED", entryStatus = "WAITING"), today))

        val call = { state: String, actionable: Boolean, ledger: LedgerTipDto? -> ActivePredictionDto(
            predictionId = 1, symbol = "PGHL", companyName = null, exchange = "NSE", price = 5098.0, targetPrice = 5376.0, stopLoss = 4966.4,
            horizon = 3, remainingTradingDays = 3, distanceToTargetPercent = null, distanceToStopLossPercent = null, confidence = 0.72, trustScore = null,
            trustQuality = null, status = "OPEN", lifecycleState = state, isActionableNow = actionable, lifecycleDetail = null, entryPrice = 5110.0,
            compositeOpportunityScore = null, ledger = ledger
        ) }
        assertTrue(TipTrack.buyable(call("ACTIONABLE_NOW", true, null), today))
        assertFalse(TipTrack.buyable(call("WATCHING", false, null), today))
        assertTrue(TipTrack.buyable(call("WATCHING", false, tip(entryStatus = "WAITING")), today))
        assertFalse(TipTrack.buyable(call("INVALIDATED", true, null), today))
        assertFalse(TipTrack.buyable(call("ACTIONABLE_NOW", true, tip()), today))
    }
}
