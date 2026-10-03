package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.intelligence.DailyBriefing

/** EPIC-017 briefing: morning / evening / overnight, each line explained and tappable to its source. */
@Composable
fun BriefingScreen(
    padding: PaddingValues,
    load: suspend (DailyBriefing.Kind?) -> DailyBriefing.Briefing,
    onOpenEvent: (Long) -> Unit,
    onOpenStock: (String) -> Unit = {}
) {
    val isSymbol = rememberSymbolCheck()
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = kindName?.let { DailyBriefing.Kind.valueOf(it) }
    val briefing by produceState<DailyBriefing.Briefing?>(null, kindName) { value = runCatching { load(kind) }.getOrNull() }

    Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = MarksySpace.Gutter),
        // Clears the three floating Morning / Evening / Overnight buttons.
        contentPadding = PaddingValues(top = 8.dp, bottom = oneHandStackBottomPadding(3)),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        val b = briefing
        if (b == null) {
            item { Text("Preparing your briefing…", color = MarksyTheme.TextMuted, style = MarksyType.Body) }
            return@LazyColumn
        }
        item { Text(b.headline, color = MarksyTheme.TextPrimary, style = MarksyType.Lead) }
        if (b.sections.isEmpty()) {
            item { Text("Nothing needs your attention in this window.", color = MarksyTheme.TextSecondary, style = MarksyType.Body) }
        }
        b.sections.forEach { section ->
            item(key = "h-${section.title}") {
                SectionLabel(section.title, section.lines.size)
            }
            items(section.lines, key = { "${section.title}-${it.eventIds.first()}-${it.text}" }) { line ->
                MarksyCard(onClick = { onOpenEvent(line.eventIds.first()) }) {
                    Text(line.text, color = MarksyTheme.TextPrimary, style = MarksyType.Body, maxLines = 2)
                    Text("Why: ${line.why}", color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 2)
                    if (section.title == com.marksy.os.intelligence.DailyBriefing.MARKETS_SECTION) {
                        val stocks = remember(line.text, isSymbol) { com.marksy.os.market.StockMentions.find(line.text, isSymbol) }
                        StockLinkPills(stocks, onOpenStock, Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
    OneHandToggleButtons(
        options = listOf(
            Triple(DailyBriefing.Kind.MORNING.name, Icons.Default.WbSunny, DailyBriefing.Kind.MORNING.label),
            Triple(DailyBriefing.Kind.EVENING.name, Icons.Default.WbTwilight, DailyBriefing.Kind.EVENING.label),
            Triple(DailyBriefing.Kind.OVERNIGHT.name, Icons.Default.Bedtime, DailyBriefing.Kind.OVERNIGHT.label)
        ),
        selected = (briefing?.kind ?: kind)?.name.orEmpty(),
        onSelected = { kindName = it }
    )
    }
}
