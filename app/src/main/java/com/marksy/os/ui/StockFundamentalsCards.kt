package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marksy.os.MarksyFormat
import com.marksy.os.upstox.FinancialPeriod
import com.marksy.os.upstox.FinancialSeries
import com.marksy.os.upstox.UpstoxFundamentals
import java.time.Instant
import java.time.ZoneId
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
    Column(Modifier.fillMaxWidth().marksyCard().padding(MarksySpace.CardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            note?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Caption) }
        }
        content()
    }
}

@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.padding(top = MarksySpace.Gap).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        options.forEach { o -> Pill(o, selected = o == selected) { onSelect(o) } }
    }
}

/** ₹ crore without paise once the number is big enough to read at a glance. */
private fun crore(v: Double) = if (abs(v) >= 100) (if (v < 0) MarksyFormat.MINUS else "") + count(Math.round(abs(v))) else money(v)

private fun label(category: String) = category.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    .replace("Fii", "FII").replace("Other Dii", "Other DII").replace("Retail And Other", "Retail & others")

@Composable
private fun AboutCard(f: StockFundamentals) {
    val p = f.profile ?: return
    var more by rememberSaveable { mutableStateOf(false) }
    Section("About") {
        Row(Modifier.padding(top = MarksySpace.Inner)) {
            listOfNotNull(p.sector?.let { "Sector" to it }, p.marketCapCr?.let { "Market cap" to "₹${crore(it)} Cr" }).forEach { (l, v) ->
                Column(Modifier.weight(1f)) {
                    Text(l, color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                    Text(v, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        p.about?.let {
            Text(it, color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = if (more) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MarksySpace.Gap).clickable { more = !more })
        }
    }
}

@Composable
private fun RatiosCard(f: StockFundamentals, price: Double?) {
    Section("Key ratios") {
        Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Inner, bottom = MarksySpace.Hair)) {
            Text("Ratio", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1.2f))
            Text("Company", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            Text("Sector", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        f.ratios.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Tight)) {
                Text(r.name, color = MarksyTheme.TextPrimary, style = MarksyType.Small, modifier = Modifier.weight(1.2f))
                Text(r.company, color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text(r.sector ?: "–", color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }
        val pe = f.ratios.firstOrNull { it.name.equals("P/E", true) }?.companyValue
        val pb = f.ratios.firstOrNull { it.name.equals("P/B", true) }?.companyValue
        val graham = if (price != null && pe != null && pb != null) UpstoxFundamentals.grahamNumber(price, pe, pb) else null
        val dupont = UpstoxFundamentals.dupont(f.yearly, f.balance)
        if (graham != null || dupont != null) {
            Text("Value checks · Marksy calculation", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.padding(top = MarksySpace.ListGap))
            if (graham != null && price != null) {
                val gap = (price - graham) / graham * 100
                Text(
                    "Graham Number ₹${money(graham)} · price is ${MarksyFormat.percent(abs(gap), 0, signed = false)} ${if (gap >= 0) "above" else "below"} it",
                    color = if (gap <= 0) MarksyTheme.Positive else MarksyTheme.TextPrimary, style = MarksyType.Small, modifier = Modifier.padding(top = MarksySpace.Tight)
                )
                Text("√(22.5 × EPS × book value), with EPS and book value per share derived from P/E and P/B.", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }
            if (dupont != null) {
                val sectorRoe = f.ratios.firstOrNull { it.name.equals("ROE", true) }?.sectorValue
                Text(
                    "DuPont ROE ${pct(dupont.roe)} = margin ${pct(dupont.margin)} × turnover ${MarksyFormat.number(dupont.turnover) + "×"} × leverage ${MarksyFormat.number(dupont.multiplier) + "×"}",
                    color = MarksyTheme.TextPrimary, style = MarksyType.Small, modifier = Modifier.padding(top = MarksySpace.Inner)
                )
                Text("FY ${dupont.period}" + (sectorRoe?.let { " · sector ROE ${MarksyFormat.percent(it, 2, signed = false)}" } ?: ""), color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }
        }
    }
}

private fun pct(v: Double) = MarksyFormat.percent(v * 100, 2, signed = false)

@Composable
private fun FinancialsCard(f: StockFundamentals) {
    val tabs = listOfNotNull("Income".takeIf { f.quarterly.isNotEmpty() || f.yearly.isNotEmpty() }, "Balance sheet".takeIf { f.balance.isNotEmpty() }, "Cash flow".takeIf { f.cashFlow.isNotEmpty() })
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    var yearly by rememberSaveable { mutableStateOf(f.quarterly.isEmpty()) }
    var category by rememberSaveable(tab) { mutableStateOf<String?>(null) }
    Section("Financials", note = "₹ Cr · consolidated · Upstox") {
        MarksySegmented(tabs, tab, { tab = it }, Modifier.fillMaxWidth().padding(top = MarksySpace.Gap))
        when (tab) {
            "Income" -> {
                val series = if (yearly) f.yearly else f.quarterly
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SeriesChips(series, category) { category = it } }
                    MarksySegmented(listOf("Quarterly", "Yearly"), if (yearly) "Yearly" else "Quarterly", { yearly = it == "Yearly" }, Modifier.padding(top = MarksySpace.Gap).width(170.dp))
                }
                (series.firstOrNull { it.category == category } ?: series.firstOrNull())?.let { Bars(it.history) }
            }
            "Cash flow" -> {
                SeriesChips(f.cashFlow, category) { category = it }
                (f.cashFlow.firstOrNull { it.category == category } ?: f.cashFlow.firstOrNull())?.let { Bars(it.history) }
            }
            else -> {
                Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Gap, bottom = MarksySpace.Hair)) {
                    listOf("Period", "Assets", "Liabilities", "Equity").forEachIndexed { i, h ->
                        Text(h, color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f), textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
                    }
                }
                f.balance.take(5).forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Tight)) {
                        listOf(b.period, crore(b.totalAssets), crore(b.totalLiabilities), crore(b.totalAssets - b.totalLiabilities)).forEachIndexed { i, v ->
                            Text(v, color = if (i == 0) MarksyTheme.TextSecondary else MarksyTheme.TextPrimary, style = MarksyType.Small, modifier = Modifier.weight(1f), textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
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
    Row(Modifier.fillMaxWidth().height(150.dp).padding(top = MarksySpace.ListGap), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.Bottom) {
        periods.forEach { p ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(crore(p.value), color = MarksyTheme.TextPrimary, style = MarksyType.Caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
                p.change?.let { Text(it, color = if (it.startsWith("-")) MarksyTheme.Negative else MarksyTheme.Positive, style = MarksyType.Caption, maxLines = 1) }
                Box(
                    Modifier.padding(top = MarksySpace.Hair).fillMaxWidth(.7f).fillMaxHeight((abs(p.value) / peak * .62).toFloat().coerceAtLeast(.02f))
                        .clip(MarksyShape.BarTop).background(if (p.value >= 0) MarksyTheme.Positive.copy(alpha = .75f) else MarksyTheme.Negative.copy(alpha = .75f))
                )
                Text(p.period, color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1, modifier = Modifier.padding(top = MarksySpace.Hair))
            }
        }
    }
}

private val HOLDER_COLORS = listOf(MarksyTheme.BlueFinance, MarksyTheme.AccentGreen, MarksyTheme.YellowImportant, MarksyTheme.OrangeDelivery, MarksyTheme.PurpleWork, MarksyTheme.TextMuted)

@Composable
private fun ShareholdingCard(series: List<FinancialSeries>) {
    val latest = series.mapNotNull { s -> s.history.firstOrNull()?.let { s.category to it } }
    if (latest.isEmpty()) return
    Section("Shareholding", note = "${latest.first().second.period} · Upstox") {
        Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Gap).height(10.dp).clip(MarksyShape.Badge)) {
            latest.forEachIndexed { i, (_, p) -> if (p.value > 0) Box(Modifier.weight(p.value.toFloat()).fillMaxHeight().background(HOLDER_COLORS[i % HOLDER_COLORS.size])) }
        }
        latest.chunked(2).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Inner)) {
                row.forEachIndexed { c, (category, p) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(MarksySize.Dot).clip(CircleShape).background(HOLDER_COLORS[(r * 2 + c) % HOLDER_COLORS.size]))
                        Text(label(category), color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.padding(start = MarksySpace.Inner).weight(1f))
                        Text(MarksyFormat.percent(p.value, 2, signed = false), color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = MarksySpace.ListGap))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        series.firstOrNull { it.category == "promoters" }?.history?.takeIf { it.size > 1 }?.let { h ->
            Text("Promoters: " + h.take(4).joinToString(" · ") { "${it.period} ${MarksyFormat.percent(it.value, 2, signed = false)}" }, color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.padding(top = MarksySpace.Gap))
        }
    }
}

@Composable
private fun ActionsCard(f: StockFundamentals) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    Section("Corporate actions") {
        f.actions.take(8).forEachIndexed { i, a ->
            Column(Modifier.fillMaxWidth().padding(top = MarksySpace.Inner).clip(MarksyShape.Chip).clickable { open = if (open == i) null else i }.padding(vertical = MarksySpace.Hair)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    (a.amount?.let { "₹${money(it)}" } ?: a.ratio)?.let { Text(it, color = MarksyTheme.Positive, style = MarksyType.Small, fontWeight = FontWeight.SemiBold) }
                    a.date?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Meta, modifier = Modifier.padding(start = MarksySpace.Gap)) }
                }
                if (open == i) a.details.forEach { (k, v) ->
                    Row(Modifier.padding(top = MarksySpace.Hair)) {
                        Text(k, color = MarksyTheme.TextMuted, style = MarksyType.Meta, modifier = Modifier.weight(1f))
                        Text(v, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End)
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
                Modifier.fillMaxWidth().padding(top = MarksySpace.Inner).clip(MarksyShape.Chip).clickable(enabled = symbol != null) { symbol?.let(onOpenSymbol) }.padding(vertical = MarksySpace.Tight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(symbol ?: p.instrumentKey, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                    p.sector?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Caption) }
                }
                p.marketCapCr?.let { Text("₹${crore(it)} Cr", color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
                symbol?.let { WatchlistButton(it, Modifier.padding(start = MarksySpace.Tight)) }
            }
        }
    }
}

@Composable
private fun NewsCard(f: StockFundamentals) {
    val uri = LocalUriHandler.current
        Section("News") {
        f.news.take(8).forEach { n ->
            Column(
                Modifier.fillMaxWidth().padding(top = MarksySpace.Gap).clip(MarksyShape.Chip)
                    .clickable(enabled = n.link?.startsWith("https://") == true) { n.link?.let { runCatching { uri.openUri(it) } } }.padding(vertical = MarksySpace.Hair)
            ) {
                Text(n.heading, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                n.summary?.let { Text(it, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (n.publishedAt > 0) Text(MarksyFormat.dayTime(Instant.ofEpochMilli(n.publishedAt).atZone(ZoneId.systemDefault())), color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }
        }
    }
}
