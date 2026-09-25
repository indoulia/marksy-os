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
    onOpenEvent: (Long) -> Unit
) {
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = kindName?.let { DailyBriefing.Kind.valueOf(it) }
    val briefing by produceState<DailyBriefing.Briefing?>(null, kindName) { value = runCatching { load(kind) }.getOrNull() }

    Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        // Clears the three floating Morning / Evening / Overnight buttons.
        contentPadding = PaddingValues(top = 8.dp, bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val b = briefing
        if (b == null) {
            item { Text("Preparing your briefing...", color = MarksyTheme.TextMuted, fontSize = 13.sp) }
            return@LazyColumn
        }
        item { Text(b.headline, color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        if (b.sections.isEmpty()) {
            item { Text("Nothing needs your attention in this window.", color = MarksyTheme.TextSecondary, fontSize = 13.sp) }
        }
        b.sections.forEach { section ->
            item(key = "h-${section.title}") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(section.title, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${section.lines.size}",
                        color = MarksyTheme.PrimaryEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MarksyTheme.BadgeTradingBg)
                            .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                }
            }
            items(section.lines, key = { "${section.title}-${it.eventIds.first()}-${it.text}" }) { line ->
                Card(
                    onClick = { onOpenEvent(line.eventIds.first()) },
                    colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp))
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(line.text, color = MarksyTheme.TextPrimary, fontSize = 13.sp, maxLines = 2)
                        Text("Why: ${line.why}", color = MarksyTheme.TextMuted, fontSize = 11.sp, maxLines = 2)
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
