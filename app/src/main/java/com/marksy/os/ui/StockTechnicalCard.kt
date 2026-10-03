package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.PivotMethod
import com.marksy.os.upstox.Reading
import com.marksy.os.upstox.Signal
import com.marksy.os.upstox.Technicals
import com.marksy.os.upstox.Trend
import java.time.LocalDate
import java.time.ZoneId

private val IST = ZoneId.of("Asia/Kolkata")

private fun Signal.color() = when (this) { Signal.BULLISH -> MarksyTheme.Positive; Signal.NEUTRAL -> MarksyTheme.TextSecondary; Signal.BEARISH -> MarksyTheme.Negative }
private fun Trend.color() = when (this) { Trend.VERY_BULLISH, Trend.BULLISH -> MarksyTheme.Positive; Trend.NEUTRAL -> MarksyTheme.Warning; else -> MarksyTheme.Negative }
private fun Signal.label() = name.lowercase().replaceFirstChar { it.uppercase() }

/** MC-Technicals-style rating from a year of daily candles; hidden until there is enough history. */
@Composable
internal fun TechnicalCard(daily: List<Candle>, lastPrice: Double?) {
    val rating = remember(daily) { Technicals.rate(daily) } ?: return
    val history = remember(daily) { Technicals.history(daily, 6, IST) }
    MarksyCard {
        MarksyCardHeader("Technical rating", trailing = {
            Text("as of ${ChartAxis.readout(com.marksy.os.upstox.ChartRange.Y1, rating.asOf)} close", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
        })
        Box(
            Modifier.fillMaxWidth().clip(MarksyShape.Panel).background(rating.trend.color().copy(alpha = .18f)).padding(vertical = MarksySpace.Gap),
            contentAlignment = Alignment.Center
        ) { Text(rating.trend.label.uppercase(), color = rating.trend.color(), style = MarksyType.Subhead, fontWeight = FontWeight.Bold) }
        SignalGroup("Moving averages", rating.movingAverages, movingAverages = true)
        SignalGroup("Indicators", rating.indicators)
        SignalGroup("MA crossovers", rating.crossovers)
        if (history.isNotEmpty()) HistoryGroup(history)
        PivotBlock(daily, lastPrice)
    }
}

@Composable
private fun SignalGroup(title: String, readings: List<Reading>, movingAverages: Boolean = false) {
    if (readings.isEmpty()) return
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val bull = readings.count { it.signal == Signal.BULLISH }
    val neutral = readings.count { it.signal == Signal.NEUTRAL }
    val bear = readings.count { it.signal == Signal.BEARISH }
    Column(Modifier.fillMaxWidth()) {
        MarksyDivider()
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, modifier = Modifier.weight(1f))
            Column(Modifier.width(130.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("$bull", color = MarksyTheme.Positive, style = MarksyType.Small, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (neutral > 0) Text("$neutral", color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("$bear", color = MarksyTheme.Negative, style = MarksyType.Small, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Hair).height(4.dp).clip(MarksyShape.Badge)) {
                    listOf(bull to MarksyTheme.Positive, neutral to MarksyTheme.TextMuted, bear to MarksyTheme.Negative).filter { it.first > 0 }.forEach { (n, c) ->
                        Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(c))
                    }
                }
            }
            Icon(Icons.Default.ExpandMore, contentDescription = if (open) "Collapse" else "Expand", tint = MarksyTheme.TextMuted, modifier = Modifier.padding(start = MarksySpace.Inner).size(18.dp).rotate(if (open) 180f else 0f))
        }
        if (open) Column(Modifier.padding(bottom = MarksySpace.Gap)) {
            Row(Modifier.fillMaxWidth().padding(bottom = MarksySpace.Hair)) {
                Text(if (movingAverages) "Period" else "Name", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1.4f))
                Text(if (movingAverages) "Simple" else "Value", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                if (movingAverages) Text("Exponential", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text("Signal", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            readings.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Tight), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.4f)) {
                        Text(r.name, color = MarksyTheme.TextPrimary, style = MarksyType.Small)
                        r.note?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Caption) }
                    }
                    Text(money(r.value), color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    if (movingAverages) Text(r.ema?.let(::money) ?: "–", color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(r.signal.label(), color = r.signal.color(), style = MarksyType.Small, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun HistoryGroup(history: List<Pair<Candle, Trend>>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        MarksyDivider()
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
            Text("Historical rating", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, contentDescription = if (open) "Collapse" else "Expand", tint = MarksyTheme.TextMuted, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
        }
        if (open) Column(Modifier.padding(bottom = MarksySpace.Gap)) {
            history.forEach { (c, t) ->
                Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Tight)) {
                    Text(ChartAxis.readout(com.marksy.os.upstox.ChartRange.Y1, c.time), color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.weight(1f))
                    Text(money(c.close), color = MarksyTheme.TextPrimary, style = MarksyType.Small, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(t.label, color = t.color(), style = MarksyType.Small, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun PivotBlock(daily: List<Candle>, lastPrice: Double?) {
    var method by rememberSaveable { mutableStateOf(PivotMethod.CLASSIC) }
    val p = remember(daily, method) { Technicals.pivots(daily, LocalDate.now(IST), IST, method) } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pivot levels", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, modifier = Modifier.weight(1f))
            MarksySegmented(PivotMethod.entries.map { it.name }, method.name, { method = PivotMethod.valueOf(it) }, Modifier.width(190.dp), label = { it.lowercase().replaceFirstChar { c -> c.uppercase() } })
        }
        Text("From the ${ChartAxis.readout(com.marksy.os.upstox.ChartRange.Y1, p.from)} session", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
        listOf(
            listOf("R1" to p.r1, "R2" to p.r2, "R3" to p.r3),
            listOfNotNull("Pivot" to p.pivot, lastPrice?.let { "LTP" to it }),
            listOf("S1" to p.s1, "S2" to p.s2, "S3" to p.s3)
        ).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                        Text(money(v), color = when (label.first()) { 'R' -> MarksyTheme.Negative; 'S' -> MarksyTheme.Positive; else -> MarksyTheme.TextPrimary }, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
