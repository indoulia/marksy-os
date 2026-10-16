package com.marksy.os.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marksy.os.EmptyState
import com.marksy.os.MarksyFormat
import com.marksy.os.market.EntityScorecardDto
import com.marksy.os.market.EntityScorecardListDto
import com.marksy.os.market.Follows
import com.marksy.os.market.HorizonBucket
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.ScorecardBodyDto
import com.marksy.os.market.ScorecardEntity
import com.marksy.os.market.ScorecardNav
import com.marksy.os.market.ScorecardPeriod
import com.marksy.os.market.ScorecardQuery
import com.marksy.os.market.ScorecardSource
import com.marksy.os.market.ScorecardSources
import com.marksy.os.market.ScorecardSummaryDto
import com.marksy.os.market.ScorecardText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal val ScorecardQuerySaver = Saver<ScorecardQuery, String>(save = { it.encode() }, restore = { ScorecardQuery.decode(it) })
internal val ScorecardTrailSaver = Saver<List<ScorecardSource>, String>(save = { ScorecardNav.encode(it) }, restore = { ScorecardNav.decode(it) })

private val OutcomeBarWidth = 110.dp

/** Trust: Marksy vs external, then channels, finfluencers or engines by trust under one §8.3 filter; a row opens in place, its record a tap further. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScorecardsView(
    repository: MarketIntelligenceRepository, query: ScorecardQuery, bottomPadding: Dp,
    onQueryChange: (ScorecardQuery) -> Unit = {}, onEditFilter: (ScorecardQuery) -> Unit = {},
    trail: List<ScorecardSource> = emptyList(), onTrailChange: (List<ScorecardSource>) -> Unit = {}, onOpenStock: (String) -> Unit = {}
) {
    // The tabs share one filter, so switching tabs reads nothing new.
    val filter = query.copy(entity = ScorecardEntity.CHANNEL)
    val split by produceState<MarketDataState<ScorecardSummaryDto>>(MarketDataState.Loading, filter) { value = repository.scorecardSummary(filter) }
    val channels by produceState<MarketDataState<EntityScorecardListDto>>(MarketDataState.Loading, filter) { value = repository.scorecards(ScorecardEntity.CHANNEL, filter) }
    val callers by produceState<MarketDataState<EntityScorecardListDto>>(MarketDataState.Loading, filter) { value = repository.scorecards(ScorecardEntity.CALLER, filter) }
    val followed by MarketIntelligenceRepository.followed.collectAsState()
    LaunchedEffect(Unit) { repository.follows() }
    val toggleFollow = rememberFollowToggle(repository)

    val source = trail.lastOrNull()
    if (source != null) {
        val back = { onTrailChange(ScorecardNav.back(trail)) }
        BackHandler(onBack = back)
        ScorecardDetailScreen(
            repository, source, query, bottomPadding, followed, toggleFollow, onBack = back,
            onOpenSource = { onTrailChange(ScorecardNav.open(trail, it)) }, onOpenStock = onOpenStock
        )
        return
    }

    val open: (EntityScorecardDto) -> Unit = { onTrailChange(ScorecardNav.open(trail, ScorecardSource.of(it))) }
    var sort by rememberSaveable { mutableStateOf(TrustSort.TRUST) }
    var expanded by rememberSaveable(query.entity) { mutableStateOf<String?>(null) }
    var unscoredOpen by rememberSaveable { mutableStateOf(false) }
    val best = remember(filter) { mutableStateMapOf<String, String?>() }

    Column(Modifier.fillMaxSize()) {
        // Sticky: the lists and their period stay put while the rows scroll under them.
        Column(
            Modifier.fillMaxWidth().background(MarksyTheme.Background).padding(start = MarksySpace.Gutter, end = MarksySpace.Gutter, top = MarksySpace.Tight, bottom = MarksySpace.Gap),
            verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)
        ) {
            MarksySegmented(
                ScorecardEntity.entries.map { it.name }, query.entity.name, { onQueryChange(query.copy(entity = ScorecardEntity.valueOf(it))) }, Modifier.fillMaxWidth(),
                label = { TrustModel.tabLabel(ScorecardEntity.valueOf(it)) }, count = { TrustModel.count(ScorecardEntity.valueOf(it), channels, callers) }
            )
            val chips = TrustModel.periodPills(query)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                chips.forEach { (p, label) ->
                    Pill(label, selected = query.period == p) {
                        if (p == ScorecardPeriod.CUSTOM) onEditFilter(query.copy(period = p)) else onQueryChange(query.copy(period = p, startDate = null, endDate = null))
                    }
                }
            }
        }
        MarksyDivider()
        Box(Modifier.weight(1f)) {
            MarksyList(bottom = bottomPadding) {
                (split as? MarketDataState.Loaded)?.value?.let { s ->
                    item(key = "compare") {
                        val marksy = (channels as? MarketDataState.Loaded)?.value?.items?.let(TrustModel::marksyChannel)
                        Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                            CompareCard("Marksy", s.marksy, Modifier.weight(1f), marksy?.let { m -> { open(m) } })
                            CompareCard("External", s.external, Modifier.weight(1f), null)
                        }
                    }
                }
                item(key = "sort") {
                    FlowRow(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner),
                        verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner), itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SORT", color = MarksyTheme.TextMuted, style = MarksyType.PriceLabel, maxLines = 1)
                        TrustSort.entries.forEach { s -> Pill(s.label, selected = s == sort, compact = true) { sort = s } }
                        Spacer(Modifier.weight(1f))
                        OutcomeLegend()
                    }
                }
                val state = TrustModel.state(query.entity, channels, callers)
                val members = (state as? MarketDataState.Loaded)?.value?.items?.let { TrustModel.members(query.entity, it) }.orEmpty()
                when (state) {
                    is MarketDataState.Loading -> item { MarksyLoader("Loading scorecards…") }
                    is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
                    is MarketDataState.Error -> item { EmptyState("Scorecards unavailable", state.message) }
                    else -> if (members.isEmpty()) item {
                        EmptyState("No ${TrustModel.tabLabel(query.entity).lowercase()} with calls in this period", "Widen the period or clear the horizon.")
                    } else {
                        val (scored, unscored) = TrustModel.split(members)
                        items(TrustModel.sorted(scored, sort), key = { "${it.entity}-${it.id}" }) { e ->
                            val key = "${e.entity}-${e.id}"
                            val follow = Follows.key(e)
                            val isOpen = expanded == key
                            if (isOpen) LaunchedEffect(key) {
                                if (key !in best) best[key] = (repository.scorecardDetail(ScorecardSource.of(e), filter) as? MarketDataState.Loaded)
                                    ?.value?.let { TrustModel.bestHorizon(it.byHorizon, e.body.trust.minimumCompleted) }
                            }
                            TrustRow(
                                e, Follows.isFollowing(followed, follow, e.following), isOpen, best[key],
                                onToggle = { expanded = if (isOpen) null else key }, onFollow = { toggleFollow(follow, e.name, it) }, onOpen = { open(e) }
                            )
                        }
                        if (unscored.isNotEmpty()) {
                            // With nothing scored the fold is the whole list, so it starts open.
                            val foldOpen = unscoredOpen || scored.isEmpty()
                            val need = unscored.first().body.trust.minimumCompleted
                            item(key = "unscored") {
                                FoldRow(
                                    "Not scored yet · ${unscored.size}",
                                    if (need > 0) "A score needs $need closed tips; until then each shows its progress" else "Each shows its progress toward a score",
                                    foldOpen
                                ) { unscoredOpen = !foldOpen }
                            }
                            if (foldOpen) items(unscored, key = { "u-${it.entity}-${it.id}" }) { e -> UnscoredRow(e) { open(e) } }
                        }
                    }
                }
            }
        }
    }
}

/** A trust score on its band's tint; "—" while a source has too few closed tips. */
@Composable
internal fun ScoreBadge(score: Int?, modifier: Modifier = Modifier) {
    val band = score?.let(TipTrack::band)
    Box(
        modifier.widthIn(min = 34.dp).height(30.dp).clip(MarksyShape.Chip).background(band?.let(::bandBackground) ?: MarksyTheme.SurfaceRaised).padding(horizontal = MarksySpace.Tight),
        contentAlignment = Alignment.Center
    ) {
        Text(score?.toString() ?: "—", color = band?.let(::trackColor) ?: MarksyTheme.TextMuted, style = MarksyType.Subhead, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Follow as a star: a 44dp button around a [size] circle, filled while following. */
@Composable
internal fun FollowStar(following: Boolean, name: String, onToggle: (Boolean) -> Unit, size: Dp = 30.dp) {
    val label = if (following) "Unfollow $name" else "Follow $name"
    Box(
        Modifier.size(MarksySize.Touch).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label) { onToggle(!following) }
            .semantics { contentDescription = label; stateDescription = if (following) "Following" else "Not following" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(size).clip(CircleShape).background(if (following) MarksyTheme.BadgeTradingBg else Color.Transparent)
                .border(MarksySpace.Border, if (following) MarksyTheme.Positive else MarksyTheme.BorderGlow, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (following) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null,
                tint = if (following) MarksyTheme.Positive else MarksyTheme.TextSecondary, modifier = Modifier.size(MarksySize.IconSmall)
            )
        }
    }
}

@Composable
private fun CompareCard(name: String, body: ScorecardBodyDto, modifier: Modifier, onClick: (() -> Unit)?) {
    val ret = body.performance.avgActualReturn
    MarksyRowCard(modifier, onClick = onClick, onClickLabel = onClick?.let { "Open $name record" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
            ScoreBadge(body.trust.trustScore)
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)) {
                Text(name + if (onClick != null) " ›" else "", color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Row {
                    Text("${body.performance.hitRatePct?.let { MarksyFormat.percent(it, 0, signed = false) } ?: "—"} hit · ", color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1)
                    Text(LedgerCalls.returnText(ret) ?: "—", color = ScorecardGraphics.returnTone(ret), style = MarksyType.Meta, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TrustRow(
    e: EntityScorecardDto, following: Boolean, open: Boolean, best: String?,
    onToggle: () -> Unit, onFollow: (Boolean) -> Unit, onOpen: () -> Unit
) {
    val b = e.body
    val c = b.counts
    val ret = b.performance.avgActualReturn
    MarksyRowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = if (open) "Hide ${e.name} details" else "Show ${e.name} details", onClick = onToggle)
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
                horizontalArrangement = Arrangement.spacedBy(MarksySpace.ListGap), verticalAlignment = Alignment.CenterVertically
            ) {
                ScoreBadge(b.trust.trustScore)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NameAndTag(e, Modifier.weight(1f))
                        Text(
                            LedgerCalls.returnText(ret) ?: "—", color = ScorecardGraphics.returnTone(ret), style = MarksyType.Body, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.padding(start = MarksySpace.Gap)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                        OutcomeBar(c.successful, c.failed, c.expired, 0, Modifier.width(OutcomeBarWidth))
                        Text(TrustModel.closedLine(b), color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            FollowStar(following, e.name, onFollow)
        }
        if (open) Column(Modifier.padding(top = MarksySpace.Gap), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            MarksyDivider()
            TrustModel.hitLine(b)?.let { Kv("Hit rate", it) }
            TrustModel.factors(b.trust)?.let { Kv("Score", it) }
            TrustModel.delivered(b)?.let { Kv("Returns delivered", it) }
            best?.let { Kv("Best horizon", it) }
            Text(
                "Full record ›", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.End).clip(MarksyShape.Chip).clickable(role = Role.Button, onClick = onOpen).padding(vertical = MarksySpace.Tight)
            )
        }
    }
}

@Composable
private fun NameAndTag(e: EntityScorecardDto, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Text(e.name, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        TagChip(ScorecardSources.chip(e))
    }
}

@Composable
private fun UnscoredRow(e: EntityScorecardDto, onOpen: () -> Unit) {
    val (done, words) = TrustModel.progress(e.body)
    MarksyRowCard(onClick = onOpen, onClickLabel = "Open ${e.name} record") {
        Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.ListGap), verticalAlignment = Alignment.CenterVertically) {
            ScoreBadge(null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                NameAndTag(e)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                    Box(Modifier.width(OutcomeBarWidth).height(MarksySize.Dot).clip(CircleShape).background(MarksyTheme.Track)) {
                        if (done > 0f) Box(Modifier.fillMaxWidth(done).fillMaxHeight().clip(CircleShape).background(MarksyTheme.TextMuted))
                    }
                    Text(words, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A label-left, value-right line; neither wraps. */
@Composable
internal fun Kv(label: String, value: String, valueColor: Color = MarksyTheme.TextPrimary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 1)
        Text(
            value, color = valueColor, style = MarksyType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = MarksySpace.Gap)
        )
    }
}

/** The period (with a custom range) and the horizon, each only where the page has no pills for it; Apply stays off until a custom range has both ends in order. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ScorecardFilterDialog(
    initial: ScorecardQuery, onApply: (ScorecardQuery) -> Unit, periods: Boolean = true, horizon: Boolean = true, onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf(initial) }
    var pickingStart by remember { mutableStateOf<Boolean?>(null) }
    val applicable = q.filterParams() != null
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (periods) "Period and horizon" else if (horizon) "Horizon" else "Custom range", color = MarksyTheme.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                if (periods || q.period == ScorecardPeriod.CUSTOM) Text("PERIOD (IST)", color = MarksyTheme.TextMuted, style = MarksyType.Label)
                if (periods) FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    ScorecardPeriod.entries.forEach { p -> Pill(p.label, selected = q.period == p) { q = q.copy(period = p) } }
                }
                if (q.period == ScorecardPeriod.CUSTOM) FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    Pill("From ${q.startDate?.let(ScorecardText::date) ?: "…"}", selected = q.startDate != null) { pickingStart = true }
                    Pill("To ${q.endDate?.let(ScorecardText::date) ?: "…"}", selected = q.endDate != null) { pickingStart = false }
                }
                if (horizon) {
                    Text("HORIZON", color = MarksyTheme.TextMuted, style = MarksyType.Label)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                        Pill("All", selected = q.horizon == null) { q = q.copy(horizon = null) }
                        HorizonBucket.entries.forEach { h -> Pill(h.label, selected = q.horizon == h) { q = q.copy(horizon = h) } }
                    }
                }
            }
        },
        confirmButton = {
            MarksyButton("Apply", { onApply(q) }, style = MarksyButtonStyle.Text, enabled = applicable)
        },
        dismissButton = { MarksyButton("Cancel", onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) }
    )
    pickingStart?.let { start ->
        val state = rememberDatePickerState(
            initialSelectedDateMillis = ((if (start) q.startDate else q.endDate) ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickingStart = null },
            confirmButton = {
                MarksyButton("OK", {
                    state.selectedDateMillis?.let { ms ->
                        val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        q = if (start) q.copy(startDate = day) else q.copy(endDate = day)
                    }
                    pickingStart = null
                }, style = MarksyButtonStyle.Text)
            },
            dismissButton = { MarksyButton("Cancel", { pickingStart = null }, style = MarksyButtonStyle.Text) }
        ) { DatePicker(state) }
    }
}
