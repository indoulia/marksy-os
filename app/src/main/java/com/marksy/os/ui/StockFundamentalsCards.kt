package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.upstox.FinancialPeriod
import com.marksy.os.upstox.FinancialSeries
import com.marksy.os.upstox.UpstoxFundamentals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** The Upstox fundamentals sections of the stock page, each shown only when Upstox returned it. */
internal fun LazyListScope.fundamentalsContent(f: StockFundamentals, price: Double?, onOpenSymbol: (String) -> Unit) {
    if (f.profile != null) item { AboutCard(f) }
    if (f.ratios.isNotEmpty()) item { RatiosCard(f, price) }
    if (f.quarterly.isNotEmpty() || f.yearly.isNotEmpty() || f.balance.isNotEmpty() || f.cashFlow.isNotEmpty()) item { FinancialsCard(f) }
    if (f.shareholding.isNotEmpty()) item { ShareholdingCard(f.shareholding) }
    if (f.actions.isNotEmpty()) item { ActionsCard(f) }
    if (f.peers.isNotEmpty()) item { PeersCard(f, onOpenSymbol) }
    if (f.news.isNotEmpty()) item { NewsCard(f) }
}

@Composable
private fun Section(title: String, note: String? = "Upstox", content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            note?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp) }
        }
        content()
    }
}

@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(
                o, color = if (on) Color.Black else MarksyTheme.TextSecondary, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised).clickable { onSelect(o) }.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

/** ₹ crore without paise once the number is big enough to read at a glance. */
private fun crore(v: Double) = if (abs(v) >= 100) (if (v < 0) "-" else "") + count(Math.round(abs(v))) else money(v)

private fun label(category: String) = category.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    .replace("Fii", "FII").replace("Other Dii", "Other DII").replace("Retail And Other", "Retail & others")

@Composable
private fun AboutCard(f: StockFundamentals) {
    val p = f.profile ?: return
    var more by rememberSaveable { mutableStateOf(false) }
    Section("About") {
        Row(Modifier.padding(top = 6.dp)) {
            listOfNotNull(p.sector?.let { "Sector" to it }, p.marketCapCr?.let { "Market cap" to "₹${crore(it)} Cr" }).forEach { (l, v) ->
                Column(Modifier.weight(1f)) {
                    Text(l, color = MarksyTheme.TextMuted, fontSize = 11.sp)
                    Text(v, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        p.about?.let {
            Text(it, color = MarksyTheme.TextSecondary, fontSize = 12.sp, maxLines = if (more) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).clickable { more = !more })
        }
    }
}

@Composable
private fun RatiosCard(f: StockFundamentals, price: Double?) {
    Section("Key ratios") {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
            Text("Ratio", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1.2f))
            Text("Company", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            Text("Sector", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        f.ratios.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(r.name, color = MarksyTheme.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1.2f))
                Text(r.company, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text(r.sector ?: "–", color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }
        val pe = f.ratios.firstOrNull { it.name.equals("P/E", true) }?.companyValue
        val pb = f.ratios.firstOrNull { it.name.equals("P/B", true) }?.companyValue
        val graham = if (price != null && pe != null && pb != null) UpstoxFundamentals.grahamNumber(price, pe, pb) else null
        val dupont = UpstoxFundamentals.dupont(f.yearly, f.balance)
        if (graham != null || dupont != null) {
            Text("Value checks · Marksy calculation", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
            if (graham != null && price != null) {
                val gap = (price - graham) / graham * 100
                Text(
                    "Graham Number ₹${money(graham)} · price is ${String.format(Locale.US, "%.0f%%", abs(gap))} ${if (gap >= 0) "above" else "below"} it",
                    color = if (gap <= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                )
                Text("√(22.5 × EPS × book value), with EPS and book value per share derived from P/E and P/B.", color = MarksyTheme.TextMuted, fontSize = 10.sp)
            }
            if (dupont != null) {
                val sectorRoe = f.ratios.firstOrNull { it.name.equals("ROE", true) }?.sectorValue
                Text(
                    "DuPont ROE ${pct(dupont.roe)} = margin ${pct(dupont.margin)} × turnover ${String.format(Locale.US, "%.2f×", dupont.turnover)} × leverage ${String.format(Locale.US, "%.2f×", dupont.multiplier)}",
                    color = MarksyTheme.TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                )
                Text("FY ${dupont.period}" + (sectorRoe?.let { " · sector ROE ${String.format(Locale.US, "%.2f%%", it)}" } ?: ""), color = MarksyTheme.TextMuted, fontSize = 10.sp)
            }
        }
    }
}

private fun pct(v: Double) = String.format(Locale.US, "%.2f%%", v * 100)

@Composable
private fun FinancialsCard(f: StockFundamentals) {
    val tabs = listOfNotNull("Income".takeIf { f.quarterly.isNotEmpty() || f.yearly.isNotEmpty() }, "Balance sheet".takeIf { f.balance.isNotEmpty() }, "Cash flow".takeIf { f.cashFlow.isNotEmpty() })
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    var yearly by rememberSaveable { mutableStateOf(f.quarterly.isEmpty()) }
    var category by rememberSaveable(tab) { mutableStateOf<String?>(null) }
    Section("Financials", note = "₹ Cr · consolidated · Upstox") {
        Chips(tabs, tab) { tab = it }
        when (tab) {
            "Income" -> {
                val series = if (yearly) f.yearly else f.quarterly
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SeriesChips(series, category) { category = it } }
                    Chips(listOf("Quarterly", "Yearly"), if (yearly) "Yearly" else "Quarterly") { yearly = it == "Yearly" }
                }
                (series.firstOrNull { it.category == category } ?: series.firstOrNull())?.let { Bars(it.history) }
            }
            "Cash flow" -> {
                SeriesChips(f.cashFlow, category) { category = it }
                (f.cashFlow.firstOrNull { it.category == category } ?: f.cashFlow.firstOrNull())?.let { Bars(it.history) }
            }
            else -> {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
                    listOf("Period", "Assets", "Liabilities", "Equity").forEachIndexed { i, h ->
                        Text(h, color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
                    }
                }
                f.balance.take(5).forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        listOf(b.period, crore(b.totalAssets), crore(b.totalLiabilities), crore(b.totalAssets - b.totalLiabilities)).forEachIndexed { i, v ->
                            Text(v, color = if (i == 0) MarksyTheme.TextSecondary else MarksyTheme.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesChips(series: List<FinancialSeries>, selected: String?, onSelect: (String) -> Unit) {
    if (series.size < 2) return
    val names = series.map { label(it.category) }
    Chips(names, label(selected ?: series.first().category)) { n -> series.firstOrNull { label(it.category) == n }?.let { onSelect(it.category) } }
}

/** Newest-first periods drawn oldest to newest, negative values in red. */
@Composable
private fun Bars(history: List<FinancialPeriod>) {
    val periods = history.take(5).reversed()
    val peak = periods.maxOfOrNull { abs(it.value) }?.takeIf { it > 0 } ?: return
    Row(Modifier.fillMaxWidth().height(150.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        periods.forEach { p ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(crore(p.value), color = MarksyTheme.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                p.change?.let { Text(it, color = if (it.startsWith("-")) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald, fontSize = 9.sp, maxLines = 1) }
                Box(
                    Modifier.padding(top = 2.dp).fillMaxWidth(.7f).fillMaxHeight((abs(p.value) / peak * .62).toFloat().coerceAtLeast(.02f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(if (p.value >= 0) MarksyTheme.PrimaryEmerald.copy(alpha = .75f) else MarksyTheme.RedUrgent.copy(alpha = .75f))
                )
                Text(p.period, color = MarksyTheme.TextMuted, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private val HOLDER_COLORS = listOf(Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFF97316), Color(0xFFA855F7), Color(0xFF64748B))

@Composable
private fun ShareholdingCard(series: List<FinancialSeries>) {
    val latest = series.mapNotNull { s -> s.history.firstOrNull()?.let { s.category to it } }
    if (latest.isEmpty()) return
    Section("Shareholding", note = "${latest.first().second.period} · Upstox") {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(10.dp).clip(RoundedCornerShape(5.dp))) {
            latest.forEachIndexed { i, (_, p) -> if (p.value > 0) Box(Modifier.weight(p.value.toFloat()).fillMaxHeight().background(HOLDER_COLORS[i % HOLDER_COLORS.size])) }
        }
        latest.chunked(2).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                row.forEachIndexed { c, (category, p) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(HOLDER_COLORS[(r * 2 + c) % HOLDER_COLORS.size]))
                        Text(label(category), color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        Text(String.format(Locale.US, "%.2f%%", p.value), color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        series.firstOrNull { it.category == "promoters" }?.history?.takeIf { it.size > 1 }?.let { h ->
            Text("Promoters: " + h.take(4).joinToString(" · ") { "${it.period} ${String.format(Locale.US, "%.2f%%", it.value)}" }, color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ActionsCard(f: StockFundamentals) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    Section("Corporate actions") {
        f.actions.take(8).forEachIndexed { i, a ->
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { open = if (open == i) null else i }.padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    (a.amount?.let { "₹${money(it)}" } ?: a.ratio)?.let { Text(it, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    a.date?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp)) }
                }
                if (open == i) a.details.forEach { (k, v) ->
                    Row(Modifier.padding(top = 2.dp)) {
                        Text(k, color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(v, color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

@Composable
private fun PeersCard(f: StockFundamentals, onOpenSymbol: (String) -> Unit) {
    Section("Peers") {
        f.peers.take(8).forEach { (symbol, p) ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = symbol != null) { symbol?.let(onOpenSymbol) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(symbol ?: p.instrumentKey, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    p.sector?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp) }
                }
                p.marketCapCr?.let { Text("₹${crore(it)} Cr", color = MarksyTheme.TextSecondary, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun NewsCard(f: StockFundamentals) {
    val uri = LocalUriHandler.current
    val time = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    Section("News") {
        f.news.take(8).forEach { n ->
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = n.link?.startsWith("https://") == true) { n.link?.let { runCatching { uri.openUri(it) } } }.padding(vertical = 2.dp)
            ) {
                Text(n.heading, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                n.summary?.let { Text(it, color = MarksyTheme.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (n.publishedAt > 0) Text(time.format(Date(n.publishedAt)), color = MarksyTheme.TextMuted, fontSize = 10.sp)
            }
        }
    }
}
