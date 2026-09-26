package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.marksy.os.upstox.Seasonality
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private val CELL = 50.dp
private const val RECENT_YEARS = 12

private fun heat(v: Double?): Color = when {
    v == null -> Color.Transparent
    v >= 0 -> MarksyTheme.PrimaryEmerald.copy(alpha = .15f + (abs(v) / 20).coerceAtMost(1.0).toFloat() * .6f)
    else -> MarksyTheme.RedUrgent.copy(alpha = .15f + (abs(v) / 20).coerceAtMost(1.0).toFloat() * .6f)
}

private fun signed(v: Double) = String.format(Locale.US, "%+.2f%%", v)

/** Moneycontrol-style seasonality: how each calendar month has gone every year since listing (or 2000). */
@Composable
internal fun SeasonalityCard(monthly: List<Candle>, name: String) {
    val zone = ZoneId.of("Asia/Kolkata")
    val table = remember(monthly) { Seasonality.table(monthly, zone) }
    var month by rememberSaveable { mutableStateOf(LocalDate.now(zone).monthValue) }
    var all by rememberSaveable { mutableStateOf(false) }
    val summary = remember(table, month) { Seasonality.summary(table, month) }
    val averages = remember(table) { Seasonality.monthlyAverages(table) }
    val monthName = Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault())
    val years = if (all) table.keys.toList() else table.keys.take(RECENT_YEARS)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Seasonality", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Monthly change · Upstox", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        }
        summary?.let { s ->
            val fell = s.negative * 2 > s.years
            Text(
                "${if (fell) s.negative else s.years - s.negative} of ${s.years} years $name has ${if (fell) "fallen" else "risen"} in $monthName.",
                color = MarksyTheme.TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
            )
            listOf(
                listOf("Best" to "${signed(s.best.first)} (${s.best.second})", "Worst" to "${signed(s.worst.first)} (${s.worst.second})"),
                listOf("Avg. gain" to (s.averageGain?.let(::signed) ?: "–"), "Avg. loss" to (s.averageLoss?.let(::signed) ?: "–"), "Average" to signed(s.average))
            ).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    row.forEach { (label, v) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, color = MarksyTheme.TextMuted, fontSize = 11.sp)
                            Text(v, color = if (v.startsWith("-")) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        val scroll = rememberScrollState()
        Row(Modifier.padding(top = 10.dp)) {
            Column(Modifier.width(44.dp)) {
                Cell("Year", header = true)
                Cell("Avg", header = true)
                years.forEach { Cell(it.toString(), header = true) }
            }
            Column(Modifier.horizontalScroll(scroll)) {
                Row {
                    (1..12).forEach { m ->
                        val on = m == month
                        Box(Modifier.width(CELL).height(24.dp).clip(RoundedCornerShape(6.dp)).background(if (on) MarksyTheme.SurfaceRaised else Color.Transparent).clickable { month = m }, contentAlignment = Alignment.Center) {
                            Text(Month.of(m).getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = if (on) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Row { averages.forEach { HeatCell(it, bold = true) } }
                years.forEach { y -> Row { table.getValue(y).forEach { HeatCell(it) } } }
            }
        }
        if (table.size > RECENT_YEARS) Text(
            if (all) "Show recent years" else "Show all ${table.size} years", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp).clickable { all = !all }
        )
    }
}

@Composable
private fun Cell(text: String, header: Boolean = false) {
    Box(Modifier.height(24.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Text(text, color = if (header) MarksyTheme.TextSecondary else MarksyTheme.TextPrimary, fontSize = 11.sp, fontWeight = if (header) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun HeatCell(v: Double?, bold: Boolean = false) {
    Box(Modifier.width(CELL).height(24.dp).padding(1.dp).background(heat(v)), contentAlignment = Alignment.Center) {
        if (v != null) Text(String.format(Locale.US, "%.1f", v), color = Color.White, fontSize = 10.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
    }
}
