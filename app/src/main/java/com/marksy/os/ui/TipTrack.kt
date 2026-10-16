package com.marksy.os.ui

import com.marksy.os.MarksyFormat
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.AlsoTippedDto
import com.marksy.os.market.ClosedPredictionDto
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MyTipDto
import com.marksy.os.market.ProgressPointDto
import com.marksy.os.market.ScorecardEntity
import com.marksy.os.market.ScorecardHeadlineDto
import com.marksy.os.market.ScorecardSource
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

enum class TrackTone { POSITIVE, NEGATIVE, WARNING, NEUTRAL, MUTED }

/** The price block's fourth slot: how the tip ended, or the date it is waiting on. */
internal data class ExitSlot(val caption: String, val text: String, val exited: Boolean, val note: String? = null, val compact: String? = null)

/** One tip's prices for the shared track and its 2×2 block; a missing price is left out, never guessed. */
internal data class PriceTrack(
    val sell: Boolean,
    val stop: Double?,
    val target: Double?,
    val recommended: Double?,
    val zoneLow: Double?,
    val zoneHigh: Double?,
    /** The bought price; null while waiting or when the backend does not send it. */
    val entered: Double?,
    val bought: Boolean,
    val current: Double?,
    val exit: Double?,
    val slot: ExitSlot?,
    /** Open tip: the current marker pulses. */
    val live: Boolean,
    /** Voided before buy. */
    val muted: Boolean,
    /** From entry, as a fraction. */
    val returnFraction: Double?
) {
    val hasBar: Boolean get() = stop != null && target != null && stop != target

    /** 0 at the stop, 1 at the target; the same formula mirrors a SELL, whose stop sits above its target. */
    fun at(price: Double): Float? {
        val s = stop ?: return null
        val t = target ?: return null
        if (s == t) return null
        return ((price - s) / (t - s)).coerceIn(0.0, 1.0).toFloat()
    }

    /** Where the fill starts once bought: the bought price, else a single stated entry level. */
    val entryRef: Double? get() = if (!bought) null else entered ?: zoneLow?.takeIf { it == zoneHigh }

    val tone: TrackTone get() = when {
        muted -> TrackTone.MUTED
        returnFraction == null || returnFraction == 0.0 -> TrackTone.NEUTRAL
        returnFraction > 0 -> TrackTone.POSITIVE
        else -> TrackTone.NEGATIVE
    }

    /** The fill's colour: the return's sign, else which way the end sits from entry. */
    val fillPositive: Boolean get() = returnFraction?.let { it >= 0 } ?: run {
        val from = entryRef?.let(::at) ?: return@run true
        val to = (exit ?: current)?.let(::at) ?: return@run true
        to >= from
    }

    val recommendedText: String? get() = recommended?.let(TipTrack::price)
    val entryText: String? get() = (if (bought) entered else null)?.let(TipTrack::price) ?: TipTrack.zone(zoneLow, zoneHigh)
    val currentText: String? get() = current?.let(TipTrack::price)
}

/** A Marksy or Trust score chip; [source] is the scorecard it opens. */
internal data class ScoreChip(val label: String, val score: Int, val tone: TrackTone, val source: ScorecardSource?)

/** "+N sources" or "given N×", and the rows it expands to. */
internal data class SourcesChip(val label: String, val rows: List<AlsoTippedDto>)

/** One row of the day-by-day popup. */
internal data class DayRow(val day: String, val close: String?, val fromEntry: Double?)

/** Words and mapping for tips on the shared price track (Trading, Inbox captured tips, Market picks). */
internal object TipTrack {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    const val WAITING = "WAITING"
    const val ENTERED = "ENTERED"
    private val HORIZON = setOf("HORIZON_EXPIRED", "DIRECTION_HORIZON")
    private val WORDS = mapOf("TARGET_HIT" to "Target hit", "STOP_LOSS_HIT" to "Stop-loss hit", "HORIZON_EXPIRED" to "Expired", LedgerCalls.STATUS_INVALIDATED to "Voided before buy")

    fun price(v: Double): String = MarksyFormat.rupees(v)

    /** "₹410–418" (no paise when both ends are whole), or one price when the zone is a single level. */
    fun zone(low: Double?, high: Double?): String? {
        val l = low ?: high ?: return null
        val h = high ?: low ?: return null
        if (l == h) return price(l)
        val decimals = if (l % 1.0 == 0.0 && h % 1.0 == 0.0) 0 else 2
        return MarksyFormat.rupees(minOf(l, h), decimals) + "–" + MarksyFormat.number(maxOf(l, h), decimals)
    }

    fun percent(fraction: Double?): String? = fraction?.let { MarksyFormat.percent(it * 100, 2) }

    fun waiting(t: LedgerTipDto): Boolean = LedgerCalls.isActive(t) && t.entryStatus == WAITING

    fun voided(t: LedgerTipDto): Boolean = t.status == LedgerCalls.STATUS_INVALIDATED

    fun bought(t: LedgerTipDto): Boolean = !waiting(t) && !voided(t) && (t.entryStatus == ENTERED || t.enteredPrice != null || t.exitPrice != null)

    /** "Waiting to buy", "Active", "Target hit", "Voided before buy"; other closes keep the ledger's words. */
    fun status(status: String, entryStatus: String?): String = when (status) {
        LedgerCalls.STATUS_ACTIVE -> if (entryStatus == WAITING) "Waiting to buy" else "Active"
        else -> WORDS[status] ?: status.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
    }

    fun status(t: LedgerTipDto): String = WORDS[t.status] ?: if (LedgerCalls.isActive(t)) status(t.status, t.entryStatus) else LedgerCalls.state(t)

    fun statusTone(t: LedgerTipDto): TrackTone = when {
        voided(t) -> TrackTone.MUTED
        waiting(t) -> TrackTone.WARNING
        LedgerCalls.isActive(t) -> TrackTone.POSITIVE
        else -> when (LedgerCalls.tone(t)) {
            LedgerCalls.Tone.POSITIVE -> TrackTone.POSITIVE
            LedgerCalls.Tone.NEGATIVE -> TrackTone.NEGATIVE
            LedgerCalls.Tone.NEUTRAL -> TrackTone.NEUTRAL
            LedgerCalls.Tone.MUTED -> TrackTone.MUTED
        }
    }

    /** From entry: a live price against the bought price, else the ledger's latest; none while waiting. */
    fun returnOf(t: LedgerTipDto, live: Double?): Double? = when {
        waiting(t) || voided(t) -> null
        !LedgerCalls.isActive(t) -> t.actualReturn
        t.enteredPrice != null && live != null -> fromEntry(t.enteredPrice, live, t.direction == "SELL")
        else -> t.targetProgress?.currentReturn ?: t.latestProgress?.returnToDate
    }

    fun fromEntry(entry: Double, now: Double, sell: Boolean): Double? =
        if (entry == 0.0) null else (if (sell) entry - now else now - entry) / entry

    fun slot(t: LedgerTipDto, today: LocalDate = LocalDate.now(IST)): ExitSlot? {
        // "16 Sep" read in October looks like last month; within six months the year adds nothing.
        fun day(d: LocalDate) = if (abs(ChronoUnit.DAYS.between(today, d)) <= 182) MarksyFormat.day(d) else MarksyFormat.fullDay(d)
        t.exitPrice?.let { return ExitSlot(if (t.status in HORIZON) "HORIZON END" else "EXIT", price(it), exited = true, note = t.closedAt?.let(::date)?.let(::day)) }
        if (voided(t)) return t.closedAt?.let(::date)?.let { ExitSlot("VOIDED", day(it), exited = false) }
        if (!LedgerCalls.isActive(t)) return null
        if (t.entryStatus == WAITING) return t.buyBy?.let(::date)?.let { ExitSlot("BUY BY", day(it), exited = false) }
        val by = t.horizonEndsOn?.let(::date) ?: return null
        val count = t.sessionsElapsed?.let { e -> t.horizonSessions?.let { n -> "day $e/$n" } }
        return ExitSlot("EXITS BY", listOfNotNull(day(by), count).joinToString(" · "), exited = false,
            note = t.sessionsElapsed?.let { e -> t.horizonSessions?.let { n -> "day $e of $n" } }, compact = day(by))
    }

    fun of(t: LedgerTipDto, live: Double? = null, fallbackCurrent: Double? = null): PriceTrack {
        val active = LedgerCalls.isActive(t)
        return PriceTrack(
            sell = t.direction == "SELL", stop = t.stopLoss?.takeIf { it > 0 }, target = t.target?.takeIf { it > 0 }, recommended = t.recommendedPrice,
            zoneLow = t.entryLow ?: t.entryHigh, zoneHigh = t.entryHigh ?: t.entryLow, entered = t.enteredPrice, bought = bought(t),
            current = live ?: if (active) t.targetProgress?.price ?: fallbackCurrent else null,
            exit = t.exitPrice, slot = slot(t), live = active, muted = voided(t), returnFraction = returnOf(t, live)
        )
    }

    /** A Marksy call: its ledger tip, with Marksy's own levels filling any the ledger lacks. */
    fun of(p: ActivePredictionDto, live: Double?): PriceTrack {
        val t = p.ledger
        if (t == null) return PriceTrack(
            sell = p.targetPrice < p.entryPrice, stop = p.stopLoss.takeIf { it > 0 }, target = p.targetPrice.takeIf { it > 0 }, recommended = null,
            zoneLow = p.entryPrice.takeIf { it > 0 }, zoneHigh = p.entryPrice.takeIf { it > 0 }, entered = null, bought = false,
            current = live ?: p.price, exit = null, slot = null, live = LedgerCalls.isLive(p), muted = false, returnFraction = null
        )
        val filled = t.copy(
            direction = t.direction ?: if (p.targetPrice < p.entryPrice) "SELL" else "BUY",
            target = t.target ?: p.targetPrice.takeIf { it > 0 }, stopLoss = t.stopLoss ?: p.stopLoss.takeIf { it > 0 },
            entryLow = t.entryLow ?: p.entryPrice.takeIf { it > 0 }, entryHigh = t.entryHigh ?: p.entryPrice.takeIf { it > 0 }
        )
        return of(filled, live, fallbackCurrent = p.price).let { if (LedgerCalls.isLive(p)) it else it.copy(live = false) }
    }

    fun of(c: ClosedPredictionDto, live: Double?): PriceTrack? = c.ledger?.let { of(it, live) }

    /** A captured tip: the levels Marksy read from the message and the live price; nothing about its ledger state. */
    fun of(lv: TipLevels, live: Double?, open: Boolean): PriceTrack = PriceTrack(
        sell = lv.side == "SELL" || lv.side == "SHORT", stop = lv.stopLoss, target = lv.target, recommended = null,
        zoneLow = lv.entry, zoneHigh = lv.entry, entered = null, bought = false, current = live, exit = null, slot = null,
        live = open, muted = false, returnFraction = null
    )

    fun buyable(t: LedgerTipDto, today: LocalDate): Boolean = waiting(t) && t.buyBy?.let(::date)?.let { !it.isBefore(today) } != false

    /** A live Marksy call still waiting for its entry; without a ledger (older backend) the monitor's in-zone flag decides. */
    fun buyable(p: ActivePredictionDto, today: LocalDate): Boolean = LedgerCalls.isLive(p) && (p.ledger?.let { buyable(it, today) } ?: p.isActionableNow)

    fun trade(symbol: String, track: PriceTrack): TradeIntent =
        TradeIntent(symbol, if (track.sell) TradeSide.SELL else TradeSide.BUY, track.current, track.target, track.stop)

    fun band(score: Int): TrackTone = when {
        score >= 65 -> TrackTone.POSITIVE
        score >= 45 -> TrackTone.WARNING
        else -> TrackTone.NEGATIVE
    }

    /** round(probability × 100); null when Marksy has no view on the tip. */
    fun marksyScore(t: LedgerTipDto?): Int? = t?.marksyView?.probability?.let { (it * 100).roundToInt().coerceIn(0, 100) }

    fun marksyChip(t: LedgerTipDto?, engine: ScorecardSource?): ScoreChip? = marksyScore(t)?.let { ScoreChip("Marksy", it, band(it), engine) }

    fun trustChip(score: Int?, source: ScorecardSource?): ScoreChip? = score?.let { ScoreChip("Trust", it, band(it), source) }

    /** The channel's Trust, else the caller's, as the tip's own scorecards report them. */
    fun trustChip(item: MyTipDto): ScoreChip? {
        val t = item.tip
        t.channel?.let { c -> item.channelHeadline?.trustScore?.let { return trustChip(it, ScorecardSource(ScorecardEntity.CHANNEL.param, c.channelId, c.name)) } }
        t.caller?.let { c -> item.callerHeadline?.trustScore?.let { return trustChip(it, ScorecardSource(ScorecardEntity.CALLER.param, c.callerId, c.name)) } }
        return null
    }

    fun sameSource(t: LedgerTipDto, o: AlsoTippedDto): Boolean = o.channel?.channelId == t.channel?.channelId && o.caller?.callerId == t.caller?.callerId

    fun sourcesChip(t: LedgerTipDto): SourcesChip? {
        val others = t.alsoBy.filterNot { sameSource(t, it) }.distinctBy { it.channel?.channelId to it.caller?.callerId }.size
        val label = when {
            others > 0 -> "+$others source" + if (others == 1) "" else "s"
            (t.repeats ?: 0) > 1 || t.alsoBy.isNotEmpty() -> "given ${maxOf(t.repeats ?: 0, t.alsoBy.size + 1)}×"
            else -> return null
        }
        return SourcesChip(label, t.alsoBy)
    }

    fun alsoSource(t: LedgerTipDto, o: AlsoTippedDto): String {
        val name = o.channel?.name?.ifBlank { null } ?: o.caller?.name?.ifBlank { null }
        return if (sameSource(t, o)) listOfNotNull(name, "given again").joinToString(" · ")
        else listOfNotNull(name, o.channel?.type?.let(LedgerCalls::channelType)?.replaceFirstChar { it.titlecase() }).joinToString(" · ")
    }

    /** "11 Oct · 2 days earlier" against this tip's first sighting. */
    fun alsoWhen(t: LedgerTipDto, o: AlsoTippedDto): String? {
        val day = date(o.firstSeenAt) ?: return null
        val gap = date(t.firstSeenAt)?.let { ChronoUnit.DAYS.between(it, day) }
        val rel = when {
            gap == null -> null
            gap == 0L -> "same day"
            else -> "${abs(gap)} day${if (abs(gap) == 1L) "" else "s"} ${if (gap < 0) "earlier" else "later"}"
        }
        return listOfNotNull(MarksyFormat.day(day), rel).joinToString(" · ")
    }

    fun alsoReturn(o: AlsoTippedDto): Double? = when {
        o.status == LedgerCalls.STATUS_ACTIVE && o.entryStatus == WAITING -> null
        o.status == LedgerCalls.STATUS_ACTIVE -> o.returnToDate
        else -> o.actualReturn
    }

    fun days(points: List<ProgressPointDto>): List<DayRow> = points.map { p ->
        DayRow(date(p.sessionDate)?.let(MarksyFormat::day) ?: p.sessionDate, p.close?.let(::price), p.returnToDate)
    }

    /** Best and worst return from entry so far, each with the first session it was reached. */
    fun extremes(points: List<ProgressPointDto>): Pair<Pair<Double, String?>?, Pair<Double, String?>?> {
        val last = points.lastOrNull() ?: return null to null
        fun reached(v: Double?, of: (ProgressPointDto) -> Double?) = v?.let { x -> x to points.firstOrNull { of(it) == x }?.sessionDate?.let(::date)?.let(MarksyFormat::day) }
        return reached(last.bestReturn) { it.bestReturn } to reached(last.worstReturn) { it.worstReturn }
    }

    /** A date-only field ("2026-10-17") or a timestamp, as the IST trading day. */
    fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(s).atZoneSameInstant(IST).toLocalDate() }.getOrNull()

    fun dayTime(iso: String): String? = runCatching { MarksyFormat.dayTime(OffsetDateTime.parse(iso).atZoneSameInstant(IST)) }.getOrNull()
}

internal fun ScorecardHeadlineDto.hitText(): String? = hitRatePct?.let { MarksyFormat.percent(it, 0, signed = false) + " hit" }

/** A watchlist row's one-line read on the stock's tips; [trade] while an entry is still buyable. */
internal data class WatchSignal(val text: String, val tone: TrackTone, val trade: TradeIntent?)

internal object WatchSignals {
    /** The customer's own open tip first, then Marksy's live call, else "No open tip". */
    fun of(symbol: String, mine: List<MyTipDto>, marksy: List<ActivePredictionDto>, live: Double?, today: LocalDate): WatchSignal {
        mine.map { it.tip }.firstOrNull { it.symbol == symbol && LedgerCalls.isActive(it) }?.let { t ->
            val track = TipTrack.of(t, live)
            if (TipTrack.waiting(t)) return WatchSignal(listOfNotNull("Waiting to buy", track.entryText?.let { "zone $it" }).joinToString(" · "), TrackTone.WARNING, TipTrack.trade(symbol, track).takeIf { TipTrack.buyable(t, today) })
            return WatchSignal(listOfNotNull("Open tip", TipTrack.percent(track.returnFraction)?.let { "$it from entry" }).joinToString(" · "), track.tone, null)
        }
        marksy.firstOrNull { it.symbol == symbol && LedgerCalls.isLive(it) }?.let { p ->
            val track = TipTrack.of(p, live)
            val waiting = p.ledger?.let(TipTrack::waiting) ?: true
            if (waiting) return WatchSignal(
                listOfNotNull("Marksy setup", track.recommendedText?.let { "recommended at $it" } ?: track.entryText?.let { "zone $it" }).joinToString(" · "),
                TrackTone.WARNING, TipTrack.trade(symbol, track).takeIf { TipTrack.buyable(p, today) }
            )
            return WatchSignal(listOfNotNull("Marksy tip", TipTrack.percent(track.returnFraction)?.let { "$it from entry" }).joinToString(" · "), track.tone, null)
        }
        return WatchSignal("No open tip", TrackTone.MUTED, null)
    }
}
