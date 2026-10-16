package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.marksy.os.MarksyFormat
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.AlsoTippedDto
import com.marksy.os.market.EntityScorecardDto
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.MyTipDto
import com.marksy.os.market.PredictionScoreDto
import com.marksy.os.market.PicksBasis
import com.marksy.os.market.ProgressPointDto
import com.marksy.os.market.ScorecardSource
import java.time.LocalDate

/** A score in the tip sheet's SCORES block. */
/** One value on the sheet's TRUST or PREDICTION line; [score] is null until there is one, and [note] then says why. */
internal data class SheetScore(val caption: String, val score: Int?, val name: String?, val note: String?, val source: ScorecardSource?)

/** Everything one tip card (and its sheet) shows; built once from whichever feed the tip came from. */
internal data class TipCardModel(
    val key: String,
    val symbol: String,
    val sell: Boolean,
    val status: String,
    val statusTone: TrackTone,
    val meta: String,
    val returnFraction: Double?,
    val track: PriceTrack?,
    val marksy: ScoreChip? = null,
    val trust: ScoreChip? = null,
    val sources: SourcesChip? = null,
    /** Non-null only while the entry is still actionable. */
    val trade: TradeIntent? = null,
    val ledger: LedgerTipDto? = null,
    val scores: List<SheetScore> = emptyList(),
    val voided: Boolean = false,
    /** Closing (else first-seen) time, newest first when feeds merge. */
    val at: String = ""
)

/** Builds tip cards from the ledger and Marksy feeds. */
internal object TipCards {
    private fun confidence(c: Double) = MarksyFormat.percent(if (c <= 1) c * 100 else c, 0, signed = false)

    fun meta(t: LedgerTipDto): String {
        val open = LedgerCalls.isActive(t)
        return listOfNotNull(
            t.channel?.name?.ifBlank { null }, t.caller?.name?.ifBlank { null }?.takeIf { it != t.channel?.name },
            t.firstSeenAt.ifBlank { null }?.let { at -> "recommended " + ((if (open) TipTrack.dayTime(at) else null) ?: LedgerCalls.day(at)) },
            t.closedAt?.takeIf { !open && !TipTrack.voided(t) }?.let { "closed ${LedgerCalls.day(it)}" }
        ).joinToString(" · ")
    }

    /** Marksy's Prediction line, only for a score that passed validation (MSV-001 activation; none has yet). */
    fun prediction(validatedScore: Int?): SheetScore? = validatedScore?.let { SheetScore("PREDICTION", it, null, "/ 100", null) }

    /** The backend's Prediction Score value, only while it reports VALIDATED. */
    fun validatedPrediction(t: LedgerTipDto?): Int? =
        t?.predictionScore?.takeIf { it.status == PredictionScoreDto.VALIDATED }?.value?.coerceIn(0, 100)

    /** A source's Trust, or why it has none yet; its hit rate lives on the record the value opens. */
    private fun trust(name: String, score: Int?, completed: Int?, source: ScorecardSource?) =
        SheetScore("TRUST", score, name.ifBlank { null }, if (score == null) completed?.let { "$it closed, too few" } else null, source)

    /** Trust for each source the tip names (channel, then finfluencer), then Marksy's Prediction line once one is validated. */
    fun scores(item: MyTipDto, validatedPrediction: Int? = null): List<SheetScore> {
        val t = item.tip
        return listOfNotNull(
            t.channel?.let { c -> trust(c.name, item.channelHeadline?.trustScore, item.channelHeadline?.completed, ScorecardSource("channel", c.channelId, c.name)) },
            t.caller?.let { c -> trust(c.name, item.callerHeadline?.trustScore, item.callerHeadline?.completed, ScorecardSource("caller", c.callerId, c.name)) },
            prediction(validatedPrediction)
        )
    }

    private fun engineScores(engine: EntityScorecardDto?, source: ScorecardSource?, validatedPrediction: Int? = null): List<SheetScore> = listOfNotNull(
        source?.let { trust(it.name, engine?.body?.trust?.trustScore, engine?.body?.counts?.completed, it) },
        prediction(validatedPrediction)
    )

    fun mine(item: MyTipDto, live: Double?, today: LocalDate): TipCardModel {
        val t = item.tip
        val track = TipTrack.of(t, live)
        return TipCardModel(
            key = "tip-${t.tipId}", symbol = t.symbol, sell = t.direction == "SELL", status = TipTrack.status(t), statusTone = TipTrack.statusTone(t),
            meta = meta(t), returnFraction = track.returnFraction, track = track,
            trust = TipTrack.trustChip(item), sources = TipTrack.sourcesChip(t),
            trade = TipTrack.trade(t.symbol, track).takeIf { TipTrack.buyable(t, today) }, ledger = t,
            scores = scores(item, validatedPrediction(t)),
            voided = TipTrack.voided(t), at = t.closedAt ?: t.firstSeenAt
        )
    }

    /** A tip read by id (a Following alert): no scorecards come with it. */
    fun ledger(t: LedgerTipDto, live: Double?, today: LocalDate): TipCardModel {
        val track = TipTrack.of(t, live)
        return TipCardModel(
            key = "tip-${t.tipId}", symbol = t.symbol, sell = t.direction == "SELL", status = TipTrack.status(t), statusTone = TipTrack.statusTone(t),
            meta = meta(t), returnFraction = track.returnFraction, track = track, sources = TipTrack.sourcesChip(t),
            trade = TipTrack.trade(t.symbol, track).takeIf { TipTrack.buyable(t, today) }, ledger = t,
            scores = scores(MyTipDto(t, null, null, emptyList(), 0), validatedPrediction(t)),
            voided = TipTrack.voided(t), at = t.closedAt ?: t.firstSeenAt
        )
    }

    /** A Marksy call from the active feed: a setup while waiting, an open tip once bought, or one that ended early. */
    fun marksy(p: ActivePredictionDto, live: Double?, today: LocalDate, engine: EntityScorecardDto?): TipCardModel {
        val t = p.ledger
        val track = TipTrack.of(p, live)
        val engineSource = engine?.let(ScorecardSource::of)
        val ended = !LedgerCalls.isLive(p)
        val horizon = "${p.horizon}-day"
        val status = when {
            ended && t != null && LedgerCalls.isActive(t) -> "Withdrawn · result pending"
            t != null -> TipTrack.status(t)
            ended -> p.lifecycleState.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
            // An older backend sends no ledger: a live call then sits in Setups as one still to buy.
            else -> "Waiting to buy"
        }
        val meta = when {
            ended -> listOfNotNull("Marksy", horizon, PicksBasis.day(p.scanSessionDate)?.let { "recommended $it" },
                t?.closedAt?.takeIf { !TipTrack.voided(t) }?.let { "closed ${LedgerCalls.day(it)}" })
            t == null || TipTrack.waiting(t) -> listOf("Marksy", horizon, "confidence ${confidence(p.confidence)}")
            else -> listOfNotNull("Marksy", horizon, p.publishedAt?.let(TipTrack::dayTime)?.let { "recommended $it" })
        }.joinToString(" · ")
        return TipCardModel(
            key = "m-${p.predictionId}", symbol = p.symbol, sell = track.sell, status = status,
            statusTone = when { t != null && !(ended && LedgerCalls.isActive(t)) -> TipTrack.statusTone(t); ended -> TrackTone.MUTED; else -> TrackTone.WARNING },
            meta = meta, returnFraction = track.returnFraction, track = track,
            trust = TipTrack.trustChip(engine?.body?.trust?.trustScore, engineSource),
            sources = t?.let(TipTrack::sourcesChip), trade = TipTrack.trade(p.symbol, track).takeIf { TipTrack.buyable(p, today) }, ledger = t,
            scores = engineScores(engine, engineSource, validatedPrediction(t)),
            voided = (t?.let(TipTrack::voided) ?: (p.lifecycleState == LedgerCalls.STATUS_INVALIDATED)) && ended,
            at = t?.closedAt ?: t?.firstSeenAt ?: p.publishedAt.orEmpty()
        )
    }

    /** A closed Marksy call; [predictions] counts the predictions the ledger folded into this one tip. */
    fun closed(c: ClosedCall, live: Double?, engine: EntityScorecardDto?): TipCardModel {
        val row = c.row
        val t = row.ledger
        val track = TipTrack.of(row, live)
        val engineSource = engine?.let(ScorecardSource::of)
        val o = row.outcome.orEmpty().uppercase()
        return TipCardModel(
            key = "c-${row.id}", symbol = row.symbol, sell = t?.direction == "SELL",
            status = t?.let(TipTrack::status) ?: row.outcome?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.titlecase() } ?: "Closed",
            statusTone = t?.let(TipTrack::statusTone) ?: when { "TARGET" in o || "SUCCESS" in o -> TrackTone.POSITIVE; "STOP" in o || "FAIL" in o -> TrackTone.NEGATIVE; else -> TrackTone.NEUTRAL },
            meta = listOfNotNull(
                "Marksy", "${row.horizonDays}-day", row.asOf?.let { TipTrack.date(it) }?.let { "recommended ${MarksyFormat.day(it)}" },
                t?.closedAt?.takeIf { !TipTrack.voided(t) }?.let { "closed ${LedgerCalls.day(it)}" },
                row.excludedReason?.let { "not scored" }, "${c.predictions} predictions".takeIf { c.predictions > 1 }
            ).joinToString(" · "),
            returnFraction = LedgerCalls.closedReturn(row), track = track,
            trust = TipTrack.trustChip(engine?.body?.trust?.trustScore, engineSource),
            sources = t?.let(TipTrack::sourcesChip), ledger = t,
            scores = engineScores(engine, engineSource, validatedPrediction(t)),
            voided = t?.let(TipTrack::voided) == true, at = t?.closedAt ?: row.asOf.orEmpty()
        )
    }
}

internal fun bandBackground(tone: TrackTone) = when (tone) {
    TrackTone.POSITIVE -> MarksyTheme.BadgeTradingBg
    TrackTone.WARNING -> MarksyTheme.BadgeImportantBg
    TrackTone.NEGATIVE -> MarksyTheme.BadgeUrgentBg
    else -> MarksyTheme.SurfaceRaised
}

/** BUY or SELL on its tinted badge. */
@Composable
internal fun SideBadge(sell: Boolean) =
    MarksyBadge(if (sell) "SELL" else "BUY", if (sell) MarksyTheme.Negative else MarksyTheme.Positive, if (sell) MarksyTheme.BadgeUrgentBg else MarksyTheme.BadgeTradingBg)

@Composable
private fun ChipPill(text: String, color: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)?) {
    Text(
        text, color = color, style = MarksyType.Caption, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(MarksyShape.Pill).background(background)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = MarksySpace.Gap, vertical = MarksySpace.Hair)
    )
}

/** "Marksy 72 ›" / "Trust 58 ›" in its score band; opens the scorecard behind it. */
@Composable
internal fun ScorePill(chip: ScoreChip, onOpen: (ScorecardSource) -> Unit) =
    ChipPill("${chip.label} ${chip.score}" + if (chip.source != null) " ›" else "", trackColor(chip.tone), bandBackground(chip.tone), chip.source?.let { s -> { onOpen(s) } })

/** One Trading tip: header, chips, the other sources when expanded, then the shared price track. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TipCard(
    m: TipCardModel, expanded: Boolean, onToggleSources: () -> Unit, onOpen: () -> Unit, onTrade: (TradeIntent) -> Unit,
    onScore: (ScorecardSource) -> Unit, onSourceRow: (AlsoTippedDto) -> Unit, impliedStatus: String? = null
) {
    MarksyRowCard(onClick = onOpen, onClickLabel = "Open ${m.symbol} tip") {
        Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            TipHeader(m, onTrade, impliedStatus)
            val chips = m.marksy != null || m.trust != null || m.sources != null
            if (chips) FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Tight), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
                m.marksy?.let { ScorePill(it, onScore) }
                m.trust?.let { ScorePill(it, onScore) }
                m.sources?.let { s ->
                    val rows = s.rows.isNotEmpty()
                    ChipPill(s.label + if (rows) (if (expanded) " ▴" else " ▾") else "", MarksyTheme.TextPrimary, MarksyTheme.SurfaceRaised, onToggleSources.takeIf { rows })
                }
            }
            if (expanded) m.ledger?.let { t -> m.sources?.rows?.takeIf { it.isNotEmpty() }?.let { AlsoRows(t, it, onSourceRow) } }
            m.track?.let { PriceTrackView(it) }
        }
    }
}

@Composable
internal fun TipHeader(m: TipCardModel, onTrade: (TradeIntent) -> Unit, impliedStatus: String? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                Text(m.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold, maxLines = 1)
                SideBadge(m.sell)
                // A status the list already states (Open's "Active", a fold's own name) is left off the card.
                if (m.status != impliedStatus) Text(m.status, color = trackColor(m.statusTone), style = MarksyType.Meta, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (m.meta.isNotEmpty()) Text(m.meta, color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            val r = m.returnFraction
            Text(TipTrack.percent(r) ?: "—", color = if (r == null) MarksyTheme.TextSecondary else trackColor(m.track?.tone ?: if (r > 0) TrackTone.POSITIVE else if (r < 0) TrackTone.NEGATIVE else TrackTone.NEUTRAL), style = MarksyType.Lead, fontWeight = FontWeight.Bold, maxLines = 1)
            m.trade?.let { t -> MarksyButton(if (t.side == TradeSide.SELL) "Sell" else "Buy", { onTrade(t) }) }
        }
    }
}

/** The same tip from other sources (or this source again): each row opens its day-by-day. */
@Composable
internal fun AlsoRows(t: LedgerTipDto, rows: List<AlsoTippedDto>, onRow: (AlsoTippedDto) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MarksyShape.Chip).background(MarksyTheme.Background).border(MarksySpace.Border, MarksyTheme.Divider, MarksyShape.Chip)
            .padding(horizontal = MarksySpace.ListGap)
    ) {
        rows.forEachIndexed { i, o ->
            if (i > 0) MarksyDivider()
            val ret = TipTrack.alsoReturn(o)
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Day by day") { onRow(o) }.padding(vertical = MarksySpace.Inner)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(TipTrack.alsoSource(t, o), color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TipTrack.percent(ret)?.let { Text(it, color = if (ret!! >= 0) MarksyTheme.Positive else MarksyTheme.Negative, style = MarksyType.Small, fontWeight = FontWeight.Bold) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(TipTrack.alsoWhen(t, o), o.recommendedPrice?.let { "recommended ${TipTrack.price(it)}" }).joinToString(" · "),
                        color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Text("${TipTrack.status(o.status, o.entryStatus)} ›", color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1)
                }
            }
        }
    }
}

/** Day by day for another source's tip: each session's close and its return from entry. */
@Composable
internal fun DayByDayDialog(repository: MarketIntelligenceRepository, t: LedgerTipDto, o: AlsoTippedDto, onDismiss: () -> Unit) {
    val progress by produceState<MarketDataState<List<ProgressPointDto>>>(MarketDataState.Loading, o.tipId) { value = repository.tipProgress(o.tipId) }
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(TipTrack.alsoSource(t, o)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
                Text(listOfNotNull(TipTrack.alsoWhen(t, o), o.recommendedPrice?.let { "recommended ${TipTrack.price(it)}" }).joinToString(" · "), color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                when (val p = progress) {
                    is MarketDataState.Loaded -> DayRows(TipTrack.days(p.value))
                    MarketDataState.Loading -> MarksyLoader("Loading days…")
                    is MarketDataState.Error -> Text(p.message, style = MarksyTheme.DialogBody)
                    else -> Text("No sessions recorded yet.", style = MarksyTheme.DialogBody)
                }
            }
        },
        confirmButton = { MarksyButton("Close", onDismiss, style = MarksyButtonStyle.Text) }
    )
}

@Composable
private fun DayRows(rows: List<DayRow>) {
    if (rows.isEmpty()) { Text("No sessions recorded yet.", style = MarksyTheme.DialogBody); return }
    val closes = rows.any { it.close != null }
    Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Inner)) {
        Text("DAY", color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel, modifier = Modifier.weight(1f))
        if (closes) Text("CLOSE", color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text("FROM ENTRY", color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    MarksyDivider()
    rows.forEach { d ->
        Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Hair)) {
            Text(d.day, color = MarksyTheme.TextSecondary, style = MarksyType.Body, modifier = Modifier.weight(1f))
            if (closes) Text(d.close.orEmpty(), color = MarksyTheme.TextPrimary, style = MarksyType.Body, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(
                TipTrack.percent(d.fromEntry) ?: "—", color = d.fromEntry?.let { if (it >= 0) MarksyTheme.Positive else MarksyTheme.Negative } ?: MarksyTheme.TextMuted,
                style = MarksyType.Body, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f)
            )
        }
    }
}

/** The strip above a Trading list: what the list adds up to. */
internal data class TradingSummary(val caption: String, val line: String, val big: String?, val tone: TrackTone)

@Composable
internal fun SummaryStrip(s: TradingSummary) {
    MarksyRowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)) {
                Text(s.caption, color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.line, color = MarksyTheme.TextSecondary, style = MarksyType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            s.big?.let { Text(it, color = trackColor(s.tone), style = MarksyType.Heading, maxLines = 1, modifier = Modifier.padding(start = MarksySpace.Gap)) }
        }
    }
}
