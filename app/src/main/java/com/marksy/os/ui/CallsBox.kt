package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.market.InstrumentCallsDto
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MarksyAnalysis
import com.marksy.os.market.ScorecardHeadlineDto
import org.json.JSONObject

internal fun toneColor(tone: LedgerCalls.Tone): Color = when (tone) {
    LedgerCalls.Tone.POSITIVE -> MarksyTheme.PrimaryEmerald
    LedgerCalls.Tone.NEGATIVE -> MarksyTheme.RedUrgent
    LedgerCalls.Tone.NEUTRAL -> MarksyTheme.TextSecondary
    LedgerCalls.Tone.MUTED -> MarksyTheme.TextMuted
}

/** Every call on this stock, grouped per Marksy engine then per channel with its record (spec §10); states come from the ledger tip. */
@Composable
internal fun CallsBox(calls: InstrumentCallsDto, livePrice: Double?, analysis: JSONObject?, onOpenTip: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val leading = remember(calls) { LedgerCalls.leadingMarksyCall(calls) != null }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface)
            .border(1.dp, if (leading) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, shape).padding(12.dp)
    ) {
        Text("MARKSY", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Black)
        calls.engines.forEach { e -> CallGroup(e.name, null, e.scorecard, e.tips, withCaller = false, livePrice, onOpenTip) }
        AnalysisSection(analysis)
        if (calls.channels.isNotEmpty()) {
            Text("EXTERNAL", color = MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 10.dp))
        }
        calls.channels.forEach { c -> CallGroup(c.name, LedgerCalls.channelType(c.type), c.scorecard, c.tips, withCaller = true, livePrice, onOpenTip) }
    }
}

@Composable
private fun CallGroup(
    name: String, kind: String?, record: ScorecardHeadlineDto, tips: List<LedgerTipDto>, withCaller: Boolean,
    livePrice: Double?, onOpenTip: (String) -> Unit
) {
    val (open, past) = remember(tips) { tips.partition(LedgerCalls::isActive) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name + (kind?.let { " · $it" } ?: ""), color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text(LedgerCalls.recordText(record), color = MarksyTheme.TextSecondary, fontSize = 10.sp, maxLines = 1)
        }
        if (tips.isEmpty()) Text("No call on this stock", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        open.forEach { t -> OpenCall(t, withCaller, livePrice) { onOpenTip(t.tipId) } }
        if (past.isNotEmpty()) Collapsible("Past calls (${past.size})", summary = null) {
            past.forEach { t ->
                Text(
                    LedgerCalls.pastLine(t, withCaller), color = toneColor(LedgerCalls.tone(t)), fontSize = 11.sp, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenTip(t.tipId) }.padding(vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun OpenCall(t: LedgerTipDto, withCaller: Boolean, livePrice: Double?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 6.dp)) {
        Text(LedgerCalls.headline(t, withCaller), color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(LedgerCalls.levelsText(t), color = MarksyTheme.TextSecondary, fontSize = 11.sp)
        val entry = LedgerCalls.entryMid(t)
        if (t.target != null && t.stopLoss != null && entry != null) ProgressBar(t.stopLoss, entry, t.target, livePrice)
        LedgerCalls.progressText(t)?.let { Text(it, color = toneColor(LedgerCalls.progressTone(t)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** Stop on the left, target on the right, the entry tick and today's price between them. */
@Composable
internal fun ProgressBar(stop: Double, entry: Double, target: Double, price: Double?) {
    if (target == stop) return
    fun at(v: Double) = ((v - stop) / (target - stop)).coerceIn(0.0, 1.0).toFloat()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val mid = size.height / 2
            drawLine(MarksyTheme.RedUrgent.copy(alpha = .5f), Offset(0f, mid), Offset(size.width * at(entry), mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(MarksyTheme.PrimaryEmerald.copy(alpha = .5f), Offset(size.width * at(entry), mid), Offset(size.width, mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(MarksyTheme.TextSecondary, Offset(size.width * at(entry), 0f), Offset(size.width * at(entry), size.height), 1.dp.toPx())
            price?.let { drawCircle(Color.White, 5.dp.toPx(), Offset(size.width * at(it), mid)) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Stop", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            price?.let { Text("Now ₹${money(it)}", color = MarksyTheme.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
            Text("Target", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
internal fun Collapsible(title: String, summary: String?, preview: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                summary?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 11.sp) }
            }
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp))
        }
        preview?.let { Column(content = it) }
        if (open) Column(Modifier.padding(bottom = 8.dp), content = content)
    }
}

/** Gauges stay visible; facts, signals and reasons open on tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisSection(analysis: JSONObject?, title: String = "Marksy analysis") {
    val a = remember(analysis) { analysis?.let(MarksyAnalysis::from) } ?: return
    if (a.gauges.isEmpty() && a.facts.isEmpty() && a.signals.isEmpty()) return
    Collapsible(title, summary = null, preview = { a.gauges.forEach { Gauge(it) } }) {
        if (a.facts.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            a.facts.forEach { (label, value) -> FactPill(label, value) }
        }
        a.signals.forEach { (label, text) ->
            Column(Modifier.padding(top = 8.dp)) {
                Text(label, color = MarksyTheme.TextMuted, fontSize = 10.sp)
                Text(text, color = MarksyTheme.TextPrimary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (a.reasons.isNotEmpty()) Text(a.reasons.joinToString(" · "), color = MarksyTheme.YellowImportant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        a.basedOn?.let { Text("Based on $it", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** A 0–100% reading drawn like the day range: label, bar, value. */
@Composable
private fun Gauge(g: MarksyAnalysis.Gauge) {
    val tint = when {
        g.fraction >= .66f -> MarksyTheme.PrimaryEmerald
        g.fraction >= .4f -> MarksyTheme.YellowImportant
        else -> MarksyTheme.RedUrgent
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(g.label, color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(78.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val mid = size.height / 2
            drawLine(Color(0x33FFFFFF), Offset(0f, mid), Offset(size.width, mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(0f, mid), Offset(size.width * g.fraction.coerceIn(.02f, 1f), mid), 4.dp.toPx(), StrokeCap.Round)
        }
        Text(g.value, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
    g.note?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(start = 78.dp)) }
}

@Composable
private fun FactPill(label: String, value: String) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MarksyTheme.SurfaceRaised).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        Text(value, color = if (value.startsWith("+")) MarksyTheme.PrimaryEmerald else if (value.startsWith("-")) MarksyTheme.RedUrgent else MarksyTheme.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
