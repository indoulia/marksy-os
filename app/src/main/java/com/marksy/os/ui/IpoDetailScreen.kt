package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.IpoDetailDto
import com.marksy.os.market.IpoDetailFormatter
import com.marksy.os.market.IpoHistoryEntryDto
import com.marksy.os.market.IpoListItemDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.display

/** Everything Marksy knows about one IPO, fetched on open and kept only while the page is shown. */
@Composable
fun IpoDetailScreen(repository: MarketIntelligenceRepository, ipo: IpoListItemDto, padding: PaddingValues) {
    val detail by produceState(MarketDataState.Loading as MarketDataState<IpoDetailDto>, ipo.id) { value = repository.ipoDetail(ipo.id) }
    val history by produceState(MarketDataState.Loading as MarketDataState<List<IpoHistoryEntryDto>>, ipo.id) { value = repository.ipoHistory(ipo.id) }
    val loaded = (detail as? MarketDataState.Loaded)?.value
    val summary = loaded?.summary?.takeIf { it.companyName.isNotBlank() } ?: ipo
    LazyColumn(
        Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { IpoHeader(summary) }
        item { KeyFacts(summary) }
        (history as? MarketDataState.Loaded)?.value?.takeIf { it.isNotEmpty() }?.let { entries ->
            item { SectionTitle("Marksy predictions") }
            val newestFirst = entries.sortedByDescending { it.predictedAt }
            item { PredictionCard(newestFirst.first(), full = true) }
            items(newestFirst.drop(1).take(4)) { entry -> PredictionCard(entry, full = false) }
        }
        when (val d = detail) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading IPO details...") }
            is MarketDataState.Error -> item { EmptyState("Details unavailable", d.message) }
            is MarketDataState.Unavailable -> item { EmptyState("Market Intelligence is not configured", "Sign in to your Marksy account in More.") }
            else -> loaded?.let { value ->
                value.riskRun?.let { run ->
                    item { Text("Risk engine: ${run.status.lowercase().replaceFirstChar { it.titlecase() }} · ${run.findingsCount} finding${if (run.findingsCount == 1) "" else "s"}${run.ranAt?.let { " · ${it.take(10)}" }.orEmpty()}", color = MarksyTheme.TextSecondary, fontSize = 12.sp) }
                }
                // The summary's own extras (grey market, subscription…) then every other section the backend returns.
                val curated = setOf("companyName", "issueName", "isSme", "sector", "stage", "opensOn", "closesOn", "listsOn", "terms", "name")
                val extras = summary.raw?.let { IpoDetailFormatter.rows(it, skip = curated) }.orEmpty()
                val sections = value.raw?.let { IpoDetailFormatter.rows(it, skip = setOf("summary", "riskEngineRan", "riskRun")) }.orEmpty()
                items(extras + sections) { row -> DetailRow(row) }
            }
        }
    }
}

@Composable
private fun IpoHeader(ipo: IpoListItemDto) {
    Column {
        Text(ipo.companyName, color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(listOfNotNull(ipo.issueName?.takeIf { it != ipo.companyName }, ipo.sector, if (ipo.isSme) "SME" else "Mainboard", ipo.stage?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.titlecase() })
            .joinToString(" · "), color = MarksyTheme.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun KeyFacts(ipo: IpoListItemDto) {
    val t = ipo.terms
    val facts = listOf(
        "Price band" to t?.priceBand.display("₹"), "Lot size" to t?.lotSize.display(),
        "Min. investment" to t?.minInvestment.display("₹"), "Issue size" to t?.issueSizeCrore.display("₹")?.let { "$it Cr" },
        "Fresh issue" to t?.freshIssueCrore.display("₹")?.let { "$it Cr" }, "Offer for sale" to t?.offerForSaleCrore.display("₹")?.let { "$it Cr" },
        "Opens" to ipo.opensOn.display(), "Closes" to ipo.closesOn.display(),
        "Lists" to ipo.listsOn.display(), "Exchanges" to t?.exchanges.display()
    ).filter { it.second != null }
    if (facts.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
        facts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = MarksyTheme.TextMuted, fontSize = 11.sp)
                        Text(value!!, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PredictionCard(entry: IpoHistoryEntryDto, full: Boolean) {
    Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp)).padding(10.dp)) {
        val call = listOfNotNull(entry.decision?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.titlecase() }, entry.expectedReturnPercent.display()?.let { "expected listing return $it%" })
        Text(call.joinToString(" · ").ifBlank { "Prediction" }, color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(entry.predictedAt.take(16).replace('T', ' '), color = MarksyTheme.TextMuted, fontSize = 10.sp)
        // Only the newest snapshot is shown in full; earlier ones are the call and when it was made.
        if (full) entry.raw?.let { raw ->
            IpoDetailFormatter.rows(raw, skip = setOf("predictedAt", "decision", "expectedReturnPercent")).forEach { DetailRow(it) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun DetailRow(row: IpoDetailFormatter.Row) {
    val indent = (row.depth * 12).dp
    if (row.paragraph) {
        Text("• ${row.label}", color = MarksyTheme.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = indent, top = 2.dp))
    } else if (row.value == null) {
        Text(row.label, color = if (row.depth == 0) MarksyTheme.TextPrimary else MarksyTheme.TextSecondary,
            fontSize = if (row.depth == 0) 14.sp else 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = indent, top = if (row.depth == 0) 6.dp else 2.dp))
    } else if (row.value.length > 60) {
        // Long text (overview, thesis) needs the full width, not the right-hand column.
        Column(Modifier.fillMaxWidth().padding(start = indent)) {
            Text(row.label, color = MarksyTheme.TextMuted, fontSize = 12.sp)
            Text(row.value, color = MarksyTheme.TextPrimary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(start = indent)) {
            Text(row.label, color = MarksyTheme.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(.45f))
            Text(row.value, color = MarksyTheme.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(.55f))
        }
    }
}
