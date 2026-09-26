package com.marksy.os.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.upstox.Candle
import com.marksy.os.upstox.PivotMethod
import com.marksy.os.upstox.Reading
import com.marksy.os.upstox.Signal
import com.marksy.os.upstox.Technicals
import com.marksy.os.upstox.Trend
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val IST = ZoneId.of("Asia/Kolkata")

private fun Signal.color() = when (this) { Signal.BULLISH -> MarksyTheme.PrimaryEmerald; Signal.NEUTRAL -> MarksyTheme.TextSecondary; Signal.BEARISH -> MarksyTheme.RedUrgent }
private fun Trend.color() = when (this) { Trend.VERY_BULLISH, Trend.BULLISH -> MarksyTheme.PrimaryEmerald; Trend.NEUTRAL -> MarksyTheme.YellowImportant; else -> MarksyTheme.RedUrgent }
private fun Signal.label() = name.lowercase().replaceFirstChar { it.uppercase() }

/** MC-Technicals-style rating from a year of daily candles; hidden until there is enough history. */
@Composable
internal fun TechnicalCard(daily: List<Candle>, lastPrice: Double?) {
    val rating = remember(daily) { Technicals.rate(daily) } ?: return
    val history = remember(daily) { Technicals.history(daily, 6, IST) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Technical rating", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("as of ${ChartAxis.readout(com.marksy.os.upstox.ChartRange.Y1, rating.asOf)} close", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        }
        Box(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(rating.trend.color().copy(alpha = .18f)).padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) { Text(rating.trend.label.uppercase(), color = rating.trend.color(), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
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
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MarksyTheme.SurfaceRaised)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Column(Modifier.width(130.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("$bull", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (neutral > 0) Text("$neutral", color = MarksyTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("$bear", color = MarksyTheme.RedUrgent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp).height(4.dp).clip(RoundedCornerShape(2.dp))) {
                    listOf(bull to MarksyTheme.PrimaryEmerald, neutral to MarksyTheme.TextMuted, bear to MarksyTheme.RedUrgent).filter { it.first > 0 }.forEach { (n, c) ->
                        Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(c))
                    }
                }
            }
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", tint = MarksyTheme.TextSecondary, modifier = Modifier.padding(start = 6.dp).size(20.dp))
        }
        if (open) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text(if (movingAverages) "Period" else "Name", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1.4f))
                Text(if (movingAverages) "Simple" else "Value", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                if (movingAverages) Text("Exponential", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text("Signal", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            readings.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.4f)) {
                        Text(r.name, color = MarksyTheme.TextPrimary, fontSize = 12.sp)
                        r.note?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp) }
                    }
                    Text(if (movingAverages || title == "MA crossovers") money(r.value) else String.format(Locale.US, "%.2f", r.value), color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    if (movingAverages) Text(r.ema?.let(::money) ?: "–", color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(r.signal.label(), color = r.signal.color(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun HistoryGroup(history: List<Pair<Candle, Trend>>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MarksyTheme.SurfaceRaised)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Historical rating", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp))
        }
        if (open) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
            history.forEach { (c, t) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(ChartAxis.readout(com.marksy.os.upstox.ChartRange.Y1, c.time), color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(money(c.close), color = MarksyTheme.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(t.label, color = t.color(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun PivotBlock(daily: List<Candle>, lastPrice: Double?) {
    var method by rememberSaveable { mutableStateOf(PivotMethod.CLASSIC) }
    val p = remember(daily, method) { Technicals.pivots(daily, LocalDate.now(IST), IST, method) } ?: return
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pivot levels", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            PivotMethod.entries.forEach { m ->
                val on = m == method
                Text(
                    m.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (on) Color.Black else MarksyTheme.TextSecondary, fontSize = 10.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(10.dp)).background(if (on) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised).clickable { method = m }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Text("From the ${ChartAxis.readout(com.marksy.os.upstox.ChartRange.Y1, p.from)} session", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        listOf(
            listOf("R1" to p.r1, "R2" to p.r2, "R3" to p.r3),
            listOfNotNull("Pivot" to p.pivot, lastPrice?.let { "LTP" to it }),
            listOf("S1" to p.s1, "S2" to p.s2, "S3" to p.s3)
        ).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                row.forEach { (label, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = MarksyTheme.TextMuted, fontSize = 11.sp)
                        Text(money(v), color = when (label.first()) { 'R' -> MarksyTheme.RedUrgent; 'S' -> MarksyTheme.PrimaryEmerald; else -> MarksyTheme.TextPrimary }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
