package com.marksy.os.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.EntityScorecardDto
import com.marksy.os.market.EntityScorecardListDto
import com.marksy.os.market.Follows
import com.marksy.os.market.HorizonBucket
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

private val PeriodChips = listOf(
    ScorecardPeriod.LIFETIME to "Lifetime", ScorecardPeriod.LAST_30_DAYS to "30 days", ScorecardPeriod.LAST_7_DAYS to "7 days",
    ScorecardPeriod.THIS_MONTH to "This month", ScorecardPeriod.CUSTOM to "Custom"
)

/** Scorecards (spec §8): Marksy vs external, then channels, callers or engines by trust under one §8.3 filter; a row opens its source. */
@Composable
internal fun ScorecardsView(
    repository: MarketIntelligenceRepository, query: ScorecardQuery, bottomPadding: Dp,
    onQueryChange: (ScorecardQuery) -> Unit = {}, onEditFilter: (ScorecardQuery) -> Unit = {},
    trail: List<ScorecardSource> = emptyList(), onTrailChange: (List<ScorecardSource>) -> Unit = {}, onOpenStock: (String) -> Unit = {}
) {
    val split by produceState<MarketDataState<ScorecardSummaryDto>>(MarketDataState.Loading, query) { value = repository.scorecardSummary(query) }
    val ranked by produceState<MarketDataState<EntityScorecardListDto>>(MarketDataState.Loading, query) { value = repository.scorecards(query.entity, query) }
    val followed by MarketIntelligenceRepository.followed.collectAsState()
    LaunchedEffect(Unit) { repository.follows() }
    val toggleFollow = rememberFollowToggle(repository)
    val listState = rememberLazyListState()

    val source = trail.lastOrNull()
    if (source != null) {
        BackHandler { onTrailChange(ScorecardNav.back(trail)) }
        ScorecardDetailScreen(
            repository, source, query, bottomPadding, followed, toggleFollow,
            onOpenSource = { onTrailChange(ScorecardNav.open(trail, it)) }, onOpenStock = onOpenStock
        )
        return
    }

    MarksyList(state = listState, bottom = bottomPadding) {
        item(key = "segments") {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                    ScorecardEntity.entries.forEach { e -> Pill(e.label, selected = query.entity == e) { onQueryChange(query.copy(entity = e)) } }
                }
                val chips = PeriodChips.toMutableList().apply { if (none { it.first == query.period }) add(1, query.period to query.period.label) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                    chips.forEach { (p, label) ->
                        Pill(label, selected = query.period == p, compact = true) {
                            if (p == ScorecardPeriod.CUSTOM) onEditFilter(query.copy(period = p)) else onQueryChange(query.copy(period = p, startDate = null, endDate = null))
                        }
                    }
                }
            }
        }
        (split as? MarketDataState.Loaded)?.value?.let { s ->
            item(key = "split") {
                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)) {
                    SummaryCard("Marksy", s.marksy, Modifier.weight(1f))
                    SummaryCard("External", s.external, Modifier.weight(1f))
                }
            }
            item(key = "legend") { OutcomeLegend() }
        }
        when (val r = ranked) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading scorecards…") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Scorecards unavailable", r.message) }
            is MarketDataState.Empty -> item { EmptyState("No ${query.entity.label.lowercase()} with calls in this period", "Widen the period or clear the horizon.") }
            is MarketDataState.Loaded -> items(r.value.items, key = { "${it.entity}-${it.id}" }) { e ->
                val key = Follows.key(e)
                SourceRow(e, Follows.isFollowing(followed, key, e.following), onToggleFollow = { toggleFollow(key, e.name, it) }) {
                    onTrailChange(ScorecardNav.open(trail, ScorecardSource.of(e)))
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun SummaryCard(name: String, body: ScorecardBodyDto, modifier: Modifier = Modifier) {
    val c = body.counts
    MarksyCard(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
            TrustRing(body.trust.trustScore, 56.dp, 6.dp, MarksyType.Lead, caption = false)
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                Text(name, color = MarksyTheme.TextPrimary, style = MarksyType.Lead, maxLines = 1)
                ReturnBadge(body.performance.avgActualReturn, style = MarksyType.Small)
            }
        }
        OutcomeBar(c.successful, c.failed, c.expired, c.open)
        Text("${c.completed}/${c.total} completed", color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = 1)
    }
}

@Composable
private fun SourceRow(e: EntityScorecardDto, following: Boolean, onToggleFollow: (Boolean) -> Unit, onClick: () -> Unit) {
    val c = e.body.counts
    val ret = e.body.performance.avgActualReturn
    MarksyRowCard(onClick = onClick) {
        Row(
            Modifier.padding(end = OneHandRowEndClearance),
            horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically
        ) {
            TrustRing(e.body.trust.trustScore, 40.dp, 5.dp, MarksyType.Small, caption = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    Text(e.name, color = MarksyTheme.TextPrimary, style = MarksyType.Lead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    TagChip(ScorecardSources.chip(e))
                }
                OutcomeBar(c.successful, c.failed, c.expired, c.open)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.completed}/${c.total}", color = MarksyTheme.TextMuted, style = MarksyType.Meta, modifier = Modifier.weight(1f))
                    FollowPill(following, onToggleFollow)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                val spark = remember(c) { ScorecardGraphics.sparkline(c.successful, c.failed, c.expired) }
                Sparkline(spark, ScorecardGraphics.returnTone(ret), Modifier.size(64.dp, 22.dp))
                ReturnBadge(ret)
            }
        }
    }
}

/** Which list, and the §8.3 period and horizon; Apply stays off until a custom range has both ends in order. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ScorecardFilterDialog(initial: ScorecardQuery, onApply: (ScorecardQuery) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf(initial) }
    var pickingStart by remember { mutableStateOf<Boolean?>(null) }
    val applicable = q.filterParams() != null
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scorecards", color = MarksyTheme.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                Text("SHOW", color = MarksyTheme.TextMuted, style = MarksyType.Label)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    ScorecardEntity.entries.forEach { e -> Pill(e.label, selected = q.entity == e) { q = q.copy(entity = e) } }
                }
                Text("PERIOD (IST)", color = MarksyTheme.TextMuted, style = MarksyType.Label)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    ScorecardPeriod.entries.forEach { p -> Pill(p.label, selected = q.period == p) { q = q.copy(period = p) } }
                }
                if (q.period == ScorecardPeriod.CUSTOM) FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    Pill("From ${q.startDate?.let(ScorecardText::date) ?: "…"}", selected = q.startDate != null) { pickingStart = true }
                    Pill("To ${q.endDate?.let(ScorecardText::date) ?: "…"}", selected = q.endDate != null) { pickingStart = false }
                }
                Text("HORIZON", color = MarksyTheme.TextMuted, style = MarksyType.Label)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    Pill("All", selected = q.horizon == null) { q = q.copy(horizon = null) }
                    HorizonBucket.entries.forEach { h -> Pill(h.label, selected = q.horizon == h) { q = q.copy(horizon = h) } }
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
