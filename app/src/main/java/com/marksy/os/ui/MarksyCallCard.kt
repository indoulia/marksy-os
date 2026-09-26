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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.market.InstrumentPredictionEntryDto
import com.marksy.os.market.IpoDetailFormatter
import com.marksy.os.market.MarksyCallView
import com.marksy.os.market.MarksyCalls
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Fields the card already shows; the rest of the recommendation is Marksy's analysis.
private val SHOWN = setOf("id", "symbol", "companyName", "exchange", "market", "entryPrice", "targetPrice", "stopLoss", "horizonDays", "asOf", "createdAt", "updatedAt", "price", "currentPrice")

private fun pct(v: Double) = "${Math.round(if (v <= 1.0) v * 100 else v)}%"
private fun signed(v: Double) = String.format(Locale.US, "%+.1f%%", v)
private fun humanize(s: String) = s.lowercase().split('_').joinToString(" ").replaceFirstChar { it.uppercase() }
private fun day(asOf: String): String = runCatching { LocalDate.parse(asOf.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }.getOrDefault(asOf.take(10))

/** Marksy's call on this stock, shown under the price: the open call in full, otherwise one line over the history. */
@Composable
internal fun MarksyCallCard(view: MarksyCallView, livePrice: Double?, analysis: JSONObject?) {
    val shape = RoundedCornerShape(14.dp)
    when (view) {
        MarksyCallView.None -> return
        is MarksyCallView.Active -> Column(Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.PrimaryEmerald, shape).padding(12.dp)) {
            val p = view.primary
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MARKSY CALL", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text(
                    listOfNotNull(" · ${p.horizonDays}-day", MarksyCalls.daysLeft(p)?.let { " · $it days left" }).joinToString(""),
                    color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f)
                )
                Text(
                    humanize(p.lifecycleState), color = MarksyTheme.PrimaryEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MarksyTheme.BadgeTradingBg).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Levels(p)
            p.targetPrice?.let { t -> p.stopLoss?.let { s -> ProgressBar(s, p.entryPrice, t, livePrice ?: p.currentPrice) } }
            Text(
                "Probability ${pct(p.probabilityAtPublication)} · Confidence ${pct(p.confidenceAtPublication)} · published ${day(p.asOf)}",
                color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)
            )
            if (p.lifecycleDetail.isNotBlank()) Text(p.lifecycleDetail, color = MarksyTheme.TextSecondary, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            view.others.forEach { o ->
                Text(
                    "Also open: ${o.horizonDays}-day call from ${day(o.asOf)} · target ${o.targetPrice?.let(::money) ?: "–"} · stop ${o.stopLoss?.let(::money) ?: "–"}",
                    color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
            AnalysisSection(analysis)
            if (view.history.isNotEmpty()) HistorySection(view.history, title = null)
        }
        is MarksyCallView.HistoryOnly -> Column(Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape).padding(horizontal = 12.dp, vertical = 4.dp)) {
            HistorySection(view.history, title = "No active Marksy call")
            AnalysisSection(analysis, title = "Marksy analysis (latest call)")
        }
    }
}

@Composable
private fun Levels(p: InstrumentPredictionEntryDto) {
    fun from(v: Double) = if (p.entryPrice > 0) " (${signed((v - p.entryPrice) / p.entryPrice * 100)})" else ""
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        listOf("Entry" to "₹${money(p.entryPrice)}", "Target" to (p.targetPrice?.let { "₹${money(it)}${from(it)}" } ?: "–"), "Stop" to (p.stopLoss?.let { "₹${money(it)}${from(it)}" } ?: "–"))
            .forEachIndexed { i, (label, v) ->
                Column(Modifier.weight(1f)) {
                    Text(label, color = MarksyTheme.TextMuted, fontSize = 11.sp)
                    Text(v, color = when (i) { 1 -> MarksyTheme.PrimaryEmerald; 2 -> MarksyTheme.RedUrgent; else -> MarksyTheme.TextPrimary }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
            }
    }
}

/** Stop on the left, target on the right, the entry tick and today's price between them. */
@Composable
private fun ProgressBar(stop: Double, entry: Double, target: Double, price: Double?) {
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
            Text("Target", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun Collapsible(title: String, summary: String?, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                summary?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 11.sp) }
            }
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp))
        }
        if (open) Column(Modifier.padding(bottom = 8.dp), content = content)
    }
}

@Composable
private fun AnalysisSection(analysis: JSONObject?, title: String = "Marksy analysis") {
    val rows = remember(analysis) { analysis?.let { IpoDetailFormatter.rows(it, skip = SHOWN) }.orEmpty() }
    if (rows.isEmpty()) return
    Collapsible(title, summary = null) { rows.forEach { DetailRow(it) } }
}

@Composable
private fun HistorySection(history: List<InstrumentPredictionEntryDto>, title: String?) {
    val r = MarksyCalls.record(history)
    val summary = listOfNotNull(
        "${r.total} past call${if (r.total == 1) "" else "s"}",
        r.hit.takeIf { it > 0 }?.let { "$it succeeded" },
        r.stopped.takeIf { it > 0 }?.let { "$it failed" },
        r.expired.takeIf { it > 0 }?.let { "$it expired" },
        r.invalidated.takeIf { it > 0 }?.let { "$it invalidated" },
        r.averageReturn?.let { "avg ${signed(it)}" }
    ).joinToString(" · ")
    Collapsible(title ?: "Past calls", summary = if (title == null) null else summary) {
        if (title == null) Text(summary, color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        history.forEach { h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${day(h.asOf)} · ${h.horizonDays}-day", color = MarksyTheme.TextPrimary, fontSize = 12.sp)
                    Text("₹${money(h.entryPrice)} → ${h.targetPrice?.let { "₹" + money(it) } ?: "–"} · stop ${h.stopLoss?.let { "₹" + money(it) } ?: "–"}", color = MarksyTheme.TextMuted, fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(humanize(MarksyCalls.outcome(h)), color = MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    h.realizedReturnPct?.let { Text(signed(it), color = if (it >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 11.sp) }
                }
            }
        }
    }
}
