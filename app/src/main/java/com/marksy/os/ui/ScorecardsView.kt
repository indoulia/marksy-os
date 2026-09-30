package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.marksy.os.market.ScorecardDto
import com.marksy.os.market.ScorecardEntity
import com.marksy.os.market.ScorecardPeriod
import com.marksy.os.market.ScorecardQuery
import com.marksy.os.market.ScorecardSummaryDto
import com.marksy.os.market.ScorecardText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal val ScorecardQuerySaver = Saver<ScorecardQuery, String>(save = { it.encode() }, restore = { ScorecardQuery.decode(it) })

/** Scorecards (spec §8): own record, Marksy vs external, then channels or callers by trust under one §8.3 filter; all server numbers. */
@Composable
internal fun ScorecardsView(repository: MarketIntelligenceRepository, query: ScorecardQuery, bottomPadding: Dp) {
    val mine by produceState<MarketDataState<ScorecardDto>>(MarketDataState.Loading, query) { value = repository.myScorecard(query) }
    val split by produceState<MarketDataState<ScorecardSummaryDto>>(MarketDataState.Loading, query) { value = repository.scorecardSummary(query) }
    val ranked by produceState<MarketDataState<EntityScorecardListDto>>(MarketDataState.Loading, query) { value = repository.scorecards(query.entity, query) }
    var detail by remember { mutableStateOf<EntityScorecardDto?>(null) }
    detail?.let { e -> ScorecardDetailDialog(repository, query, e) { detail = null } }
    val followed by MarketIntelligenceRepository.followed.collectAsState()
    LaunchedEffect(Unit) { repository.follows() }
    val toggleFollow = rememberFollowToggle(repository)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding)
    ) {
        (mine as? MarketDataState.Loaded)?.value?.let { card -> item(key = "mine") { ScoreTile("Your record", ScorecardText.range(card.filter), card.body) } }
        (split as? MarketDataState.Loaded)?.value?.let { s ->
            item(key = "split") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScoreTile("Marksy", null, s.marksy, Modifier.weight(1f))
                    ScoreTile("External", null, s.external, Modifier.weight(1f))
                }
            }
        }
        when (val r = ranked) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading scorecards...") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Scorecards unavailable", r.message) }
            is MarketDataState.Empty -> item { EmptyState("No ${query.entity.label.lowercase()} with calls in this period", "Widen the period or clear the horizon.") }
            is MarketDataState.Loaded -> items(r.value.items, key = { "${it.entity}-${it.id}" }) { e ->
                val key = Follows.key(e)
                EntityRow(e, Follows.isFollowing(followed, key, e.following), onToggleFollow = { toggleFollow(key, e.name, it) }) { detail = e }
            }
            else -> Unit
        }
    }
}

@Composable
private fun ScoreTile(title: String, subtitle: String?, body: ScorecardBodyDto, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(ScorecardText.trust(body.trust), color = if (body.trust.trustScore != null) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        subtitle?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp) }
        Text(ScorecardText.summary(body), color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun EntityRow(e: EntityScorecardDto, following: Boolean, onToggleFollow: (Boolean) -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.name + (e.channelName?.takeIf { it != e.name }?.let { " · $it" } ?: ""), color = MarksyTheme.TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text(ScorecardText.trust(e.body.trust), color = if (e.body.trust.trustScore != null) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ScorecardText.summary(e.body), color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            FollowPill(following, onToggleFollow)
        }
    }
}

/** One entity's full §8.1/§8.2 card; a channel also lists its callers, narrowed to it (§8.3). */
@Composable
private fun ScorecardDetailDialog(repository: MarketIntelligenceRepository, query: ScorecardQuery, entity: EntityScorecardDto, onDismiss: () -> Unit) {
    val card by produceState<MarketDataState<ScorecardDto>>(MarketDataState.Loading, entity, query) {
        value = repository.scorecard(ScorecardEntity.fromParam(entity.entity), entity.id, query)
    }
    val callers by produceState<MarketDataState<EntityScorecardListDto>>(MarketDataState.Loading, entity, query) {
        value = if (entity.entity == ScorecardEntity.CHANNEL.param) repository.scorecards(ScorecardEntity.CALLER, query.copy(channelId = entity.id)) else MarketDataState.Empty
    }
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(entity.name, color = MarksyTheme.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                when (val c = card) {
                    is MarketDataState.Loaded -> {
                        Text(ScorecardText.range(c.value.filter), color = MarksyTheme.TextMuted, fontSize = 11.sp)
                        ScorecardText.details(c.value.body).forEach { (label, value) ->
                            Row {
                                Text(label, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(value, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    is MarketDataState.Error -> Text(c.message, color = MarksyTheme.TextSecondary)
                    else -> MarksyLoader("Loading...")
                }
                (callers as? MarketDataState.Loaded)?.value?.items?.takeIf { it.isNotEmpty() }?.let { list ->
                    Text("Callers in ${entity.name}", color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    list.forEach { c -> Text("${c.name} · ${ScorecardText.trust(c.body.trust)} · ${ScorecardText.summary(c.body)}", color = MarksyTheme.TextSecondary, fontSize = 11.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.SemiBold) } }
    )
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Show", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScorecardEntity.entries.forEach { e -> Pill(e.label, selected = q.entity == e) { q = q.copy(entity = e) } }
                }
                Text("Period (IST)", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScorecardPeriod.entries.forEach { p -> Pill(p.label, selected = q.period == p) { q = q.copy(period = p) } }
                }
                if (q.period == ScorecardPeriod.CUSTOM) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("From ${q.startDate?.let(ScorecardText::date) ?: "…"}", selected = q.startDate != null) { pickingStart = true }
                    Pill("To ${q.endDate?.let(ScorecardText::date) ?: "…"}", selected = q.endDate != null) { pickingStart = false }
                }
                Text("Horizon", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("All", selected = q.horizon == null) { q = q.copy(horizon = null) }
                    HorizonBucket.entries.forEach { h -> Pill(h.label, selected = q.horizon == h) { q = q.copy(horizon = h) } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(q) }, enabled = applicable) {
                Text("Apply", color = if (applicable) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MarksyTheme.TextSecondary) } }
    )
    pickingStart?.let { start ->
        val state = rememberDatePickerState(
            initialSelectedDateMillis = ((if (start) q.startDate else q.endDate) ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickingStart = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        q = if (start) q.copy(startDate = day) else q.copy(endDate = day)
                    }
                    pickingStart = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingStart = null }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }
}
