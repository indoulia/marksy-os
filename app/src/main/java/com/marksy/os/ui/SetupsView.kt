package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.ActivePredictionDto
import com.marksy.os.market.DailySetups
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A DAILY_SETUPS report found in a captured message, with when it arrived. */
data class SetupReport(val eventId: Long, val postedAt: Long, val report: DailySetups.Report)

/** Today's setups: Marksy's live calls (best first), then setups from DAILY_SETUPS reports the user received. */
@Composable
internal fun SetupsView(repository: MarketIntelligenceRepository?, reports: List<SetupReport>, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    val marksy by produceState<MarketDataState<List<ActivePredictionDto>>>(MarketDataState.Loading, repository) {
        value = repository?.activePredictions()?.map { it.items } ?: MarketDataState.Unavailable
    }
    val all = (marksy as? MarketDataState.Loaded)?.value.orEmpty()
    val live = remember(all) {
        all.filter { it.lifecycleState !in INVALIDATED }
            .sortedWith(compareByDescending<ActivePredictionDto> { it.isActionableNow }.thenByDescending { it.compositeOpportunityScore ?: -1.0 })
    }
    val quotes = rememberUpstoxQuotes(remember(live, reports) { (live.map { it.symbol } + reports.flatMap { r -> r.report.setups.map { it.symbol } }).distinct() })
    val today = remember { LocalDate.now() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding)
    ) {
        when (val m = marksy) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading Marksy setups...") }
            is MarketDataState.Error -> item { EmptyState("Marksy setups unavailable", m.message) }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            else -> if (live.isEmpty()) item {
                EmptyState(
                    "No live Marksy setups right now",
                    if (all.isNotEmpty()) "All ${all.size} open calls are invalidated. See Predictions for their history." else "Marksy's new calls appear here each morning."
                )
            }
        }
        items(live, key = { "m-${it.predictionId}" }) { p ->
            OpenCallRow(p, quotes[p.symbol]?.lastPrice, note = "Marksy${p.lastPriceAt?.let { " · as of ${asOf(it)}" }.orEmpty()}") { onOpenStock(p.symbol) }
        }
        reports.forEach { r ->
            val stale = r.report.reportDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.isBefore(today) ?: false
            items(r.report.setups, key = { s -> "r-${r.eventId}-${s.id ?: s.symbol}" }) { s ->
                SetupRow(s, quotes[s.symbol]?.lastPrice, "${r.report.source} · ${r.report.reportDate?.let(::day) ?: "undated"}${if (stale) " · old report" else ""}", stale) { onOpenStock(s.symbol) }
            }
        }
    }
}

@Composable
private fun SetupRow(s: DailySetups.Setup, livePrice: Double?, source: String, stale: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.symbol, color = if (stale) MarksyTheme.TextSecondary else MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (s.long) "LONG" else "SHORT", color = if (s.long) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(MarksyTheme.SurfaceRaised).padding(horizontal = 5.dp, vertical = 1.dp)
            )
            Spacer(Modifier.weight(1f))
            livePrice?.let { Text(callRupees(it), color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        val entry = when {
            s.entryLow == null -> null
            s.entryHigh == null || s.entryHigh == s.entryLow -> callRupees(s.entryLow)
            else -> "${callRupees(s.entryLow)}–${callRupees(s.entryHigh).removePrefix("₹")}"
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            entry?.let { Text("Entry $it", color = MarksyTheme.TextSecondary, fontSize = 11.sp) }
            s.stopLoss?.let { Text("SL ${callRupees(it)}", color = MarksyTheme.RedUrgent, fontSize = 11.sp) }
            if (s.targets.isNotEmpty()) Text("T " + s.targets.joinToString(" / ") { callRupees(it).removePrefix("₹") }, color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp)
        }
        Text(
            listOfNotNull(s.horizonDays?.let { "$it-day" }, s.confidencePct?.let { "conf $it%" }, source).joinToString(" · "),
            color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
        )
        s.rationale?.let { Text(it, color = MarksyTheme.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
private fun day(iso: String) = runCatching { LocalDate.parse(iso).format(DAY) }.getOrDefault(iso)
private fun asOf(iso: String) = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(TIME) }.getOrDefault(iso.take(16).replace('T', ' '))
