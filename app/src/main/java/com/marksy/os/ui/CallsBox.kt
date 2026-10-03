package com.marksy.os.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.MarksyFormat
import com.marksy.os.market.FollowKey
import com.marksy.os.market.InstrumentCallsDto
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MarksyAnalysis
import com.marksy.os.market.ScorecardHeadlineDto
import org.json.JSONObject

internal fun toneColor(tone: LedgerCalls.Tone): Color = when (tone) {
    LedgerCalls.Tone.POSITIVE -> MarksyTheme.PrimaryEmerald
    LedgerCalls.Tone.NEGATIVE -> MarksyTheme.Negative
    LedgerCalls.Tone.NEUTRAL -> MarksyTheme.TextSecondary
    LedgerCalls.Tone.MUTED -> MarksyTheme.TextMuted
}

/** Every call on this stock, grouped per Marksy engine then per channel with its record (spec §10); states come from the ledger tip. */
@Composable
internal fun CallsBox(
    calls: InstrumentCallsDto, livePrice: Double?, analysis: JSONObject?, onOpenTip: (String) -> Unit,
    followed: Set<FollowKey>? = null, onToggleFollow: ((FollowKey, String, Boolean) -> Unit)? = null
) {
    fun follow(key: FollowKey, name: String) =
        FollowState(followed?.takeIf { onToggleFollow != null }?.contains(key)) { onToggleFollow?.invoke(key, name, it) }
    val leading = remember(calls) { LedgerCalls.leadingMarksyCall(calls) != null }
    MarksyRowCard(border = if (leading) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow) {
        MarksyCardHeader("Marksy")
        calls.engines.forEach { e ->
            CallGroup(e.name, null, e.scorecard, e.tips, withCaller = false, livePrice, onOpenTip, follow(FollowKey.caller(e.callerId), e.name))
        }
        AnalysisSection(analysis)
        if (calls.channels.isNotEmpty()) {
            Text("External", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, modifier = Modifier.padding(top = MarksySpace.Gap))
        }
        calls.channels.forEach { c ->
            CallGroup(c.name, LedgerCalls.channelType(c.type), c.scorecard, c.tips, withCaller = true, livePrice, onOpenTip, follow(FollowKey.channel(c.channelId), c.name))
        }
    }
}

private class FollowState(val following: Boolean?, val onToggle: (Boolean) -> Unit)

@Composable
private fun CallGroup(
    name: String, kind: String?, record: ScorecardHeadlineDto, tips: List<LedgerTipDto>, withCaller: Boolean,
    livePrice: Double?, onOpenTip: (String) -> Unit, follow: FollowState
) {
    val (open, past) = remember(tips) { tips.partition(LedgerCalls::isActive) }
    Column(Modifier.fillMaxWidth().padding(top = MarksySpace.Gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    name + (kind?.let { " · $it" } ?: ""), color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(LedgerCalls.recordText(record), color = MarksyTheme.TextSecondary, style = MarksyType.Caption, maxLines = 1)
            }
            FollowPill(follow.following, follow.onToggle)
        }
        if (tips.isEmpty()) Text("No call on this stock", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        open.forEach { t -> OpenCall(t, withCaller, livePrice) { onOpenTip(t.tipId) } }
        if (past.isNotEmpty()) Collapsible("Past calls (${past.size})", summary = null) {
            past.forEach { t ->
                Text(
                    LedgerCalls.pastLine(t, withCaller), color = toneColor(LedgerCalls.tone(t)), style = MarksyType.Meta, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenTip(t.tipId) }.padding(vertical = MarksySpace.Tight)
                )
            }
        }
    }
}

@Composable
private fun OpenCall(t: LedgerTipDto, withCaller: Boolean, livePrice: Double?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = MarksySpace.Inner)) {
        Text(LedgerCalls.headline(t, withCaller), color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.Medium)
        Text(LedgerCalls.levelsText(t), color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
        val entry = LedgerCalls.entryMid(t)
        if (t.target != null && t.stopLoss != null && entry != null) ProgressBar(t.stopLoss, entry, t.target, livePrice)
        LedgerCalls.progressText(t)?.let { Text(it, color = toneColor(LedgerCalls.progressTone(t)), style = MarksyType.Meta, fontWeight = FontWeight.SemiBold) }
    }
}

/** Stop on the left, target on the right, the entry tick and today's price between them. */
@Composable
internal fun ProgressBar(stop: Double, entry: Double, target: Double, price: Double?) {
    if (target == stop) return
    fun at(v: Double) = ((v - stop) / (target - stop)).coerceIn(0.0, 1.0).toFloat()
    Column(Modifier.fillMaxWidth().padding(top = MarksySpace.Gap)) {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val mid = size.height / 2
            drawLine(MarksyTheme.Negative.copy(alpha = .5f), Offset(0f, mid), Offset(size.width * at(entry), mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(MarksyTheme.PrimaryEmerald.copy(alpha = .5f), Offset(size.width * at(entry), mid), Offset(size.width, mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(MarksyTheme.TextSecondary, Offset(size.width * at(entry), 0f), Offset(size.width * at(entry), size.height), 1.dp.toPx())
            price?.let { drawCircle(MarksyTheme.TextPrimary, 5.dp.toPx(), Offset(size.width * at(it), mid)) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Stop", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f))
            price?.let { Text("Now ${MarksyFormat.rupees(it)}", color = MarksyTheme.TextPrimary, style = MarksyType.Caption, fontWeight = FontWeight.SemiBold) }
            Text("Target", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
internal fun Collapsible(title: String, summary: String?, preview: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold)
                summary?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Meta) }
            }
            Icon(Icons.Default.ExpandMore, contentDescription = if (open) "Collapse" else "Expand", tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon).rotate(if (open) 180f else 0f))
        }
        preview?.let { Column(content = it) }
        if (open) Column(Modifier.padding(bottom = MarksySpace.Gap), content = content)
    }
}

/** Gauges stay visible; facts, signals and reasons open on tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisSection(analysis: JSONObject?, title: String = "Marksy analysis") {
    val a = remember(analysis) { analysis?.let(MarksyAnalysis::from) } ?: return
    if (a.gauges.isEmpty() && a.facts.isEmpty() && a.signals.isEmpty()) return
    Collapsible(title, summary = null, preview = { a.gauges.forEach { Gauge(it) } }) {
        if (a.facts.isNotEmpty()) FlowRow(Modifier.padding(top = MarksySpace.Tight), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            a.facts.forEach { (label, value) -> FactPill(label, value) }
        }
        a.signals.forEach { (label, text) ->
            Column(Modifier.padding(top = MarksySpace.Gap)) {
                Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                Text(text, color = MarksyTheme.TextPrimary, style = MarksyType.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (a.reasons.isNotEmpty()) Text(a.reasons.joinToString(" · "), color = MarksyTheme.Warning, style = MarksyType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MarksySpace.Gap))
        a.basedOn?.let { Text("Based on $it", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.padding(top = MarksySpace.Tight)) }
    }
}

/** A 0–100% reading drawn like the day range: label, bar, value. */
@Composable
private fun Gauge(g: MarksyAnalysis.Gauge) {
    val tint = when {
        g.fraction >= .66f -> MarksyTheme.PrimaryEmerald
        g.fraction >= .4f -> MarksyTheme.Warning
        else -> MarksyTheme.Negative
    }
    Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Tight), verticalAlignment = Alignment.CenterVertically) {
        Text(g.label, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, modifier = Modifier.width(78.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val mid = size.height / 2
            drawLine(MarksyTheme.Track, Offset(0f, mid), Offset(size.width, mid), 4.dp.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(0f, mid), Offset(size.width * g.fraction.coerceIn(.02f, 1f), mid), 4.dp.toPx(), StrokeCap.Round)
        }
        Text(g.value, color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
    g.note?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.padding(start = MarksySpace.Gutter * 4 + MarksySpace.Inner)) }
}

@Composable
private fun FactPill(label: String, value: String) {
    Row(Modifier.clip(MarksyShape.Chip).background(MarksyTheme.SurfaceRaised).padding(horizontal = MarksySpace.Gap, vertical = MarksySpace.Tight), verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
        Text(value, color = if (value.startsWith("+")) MarksyTheme.PrimaryEmerald else if (value.startsWith("-") || value.startsWith(MarksyFormat.MINUS)) MarksyTheme.Negative else MarksyTheme.TextPrimary, style = MarksyType.Meta, fontWeight = FontWeight.SemiBold)
    }
}
