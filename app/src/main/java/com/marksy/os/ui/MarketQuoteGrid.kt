package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marksy.os.MarksyFormat

/** One boxed section, two entries per row: name on top, price and day change beneath. */
@Composable
internal fun QuoteGridCard(title: String, items: List<Triple<String, String, Double?>>, watchable: Boolean = false, onOpen: ((String) -> Unit)? = null, footer: (@Composable () -> Unit)? = null) {
    MarksyCard {
        run {
            SectionLabel(title, rule = false)
            items.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                    row.forEach { (name, price, change) ->
                        Column(Modifier.weight(1f).clip(MarksyShape.Chip).then(if (onOpen != null) Modifier.clickable { onOpen(name) } else Modifier)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (watchable) WatchlistButton(name, Modifier.size(24.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(price, color = MarksyTheme.TextPrimary, style = MarksyType.Lead, fontWeight = FontWeight.Bold, maxLines = 1)
                                change?.let { pct ->
                                    Spacer(Modifier.width(MarksySpace.Inner))
                                    Text(MarksyFormat.percent(pct), color = if (pct < 0) MarksyTheme.Negative else MarksyTheme.Positive, style = MarksyType.Meta, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            footer?.invoke()
        }
    }
}

/** Advances vs declines as one split bar (green, grey unchanged, red); the split animates as prices tick. */
@Composable
internal fun BreadthBar(b: com.marksy.os.market.MarketBreadth, label: String, modifier: Modifier = Modifier) {
    if (b.total == 0) return
    val up by androidx.compose.animation.core.animateFloatAsState(b.advances.toFloat(), label = "adv")
    val flat by androidx.compose.animation.core.animateFloatAsState(b.unchanged.toFloat(), label = "unch")
    val down by androidx.compose.animation.core.animateFloatAsState(b.declines.toFloat(), label = "dec")
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label: ${b.advances} advancing, ${b.declines} declining, ${b.unchanged} unchanged" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▲ ${b.advances}", color = MarksyTheme.Positive, style = MarksyType.Meta, fontWeight = FontWeight.Bold)
            Text("$label${if (b.unchanged > 0) " · ${b.unchanged} unch." else ""}", color = MarksyTheme.TextMuted, style = MarksyType.Caption, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            Text("${b.declines} ▼", color = MarksyTheme.Negative, style = MarksyType.Meta, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(top = MarksySpace.Tight).height(5.dp).clip(MarksyShape.Badge), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Hair)) {
            if (up > 0.01f) Box(Modifier.weight(up).fillMaxHeight().background(MarksyTheme.Positive))
            if (flat > 0.01f) Box(Modifier.weight(flat).fillMaxHeight().background(MarksyTheme.TextMuted))
            if (down > 0.01f) Box(Modifier.weight(down).fillMaxHeight().background(MarksyTheme.Negative))
        }
    }
}

/** NIFTY 50 by traded value, refreshed each minute while NSE is open; null until the first answer, empty without Upstox. */
@Composable
internal fun rememberMostActive(symbols: List<String>): List<Pair<String, com.marksy.os.upstox.UpstoxQuote>>? {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val client = remember { com.marksy.os.upstox.UpstoxTokenStore(context).let { store -> com.marksy.os.upstox.UpstoxApiClient { store.getToken() } } }
    var result by remember { mutableStateOf<List<Pair<String, com.marksy.os.upstox.UpstoxQuote>>?>(null) }
    LaunchedEffect(symbols) {
        runCatching { com.marksy.os.upstox.UpstoxInstruments.load(context) }
        val bySymbol = symbols.mapNotNull { s -> com.marksy.os.upstox.UpstoxInstruments.keyFor(s)?.let { it to s } }.toMap()
        if (bySymbol.isEmpty()) { result = emptyList(); return@LaunchedEffect }
        while (true) {
            try {
                val quotes = client.quotes(bySymbol.keys.toList()).mapNotNull { (k, q) -> bySymbol[k]?.let { it to q } }.toMap()
                val ranked = com.marksy.os.market.MarketBreadth.mostActive(quotes.mapValues { (_, q) -> com.marksy.os.market.MarketBreadth.Traded(q.volume, q.averagePrice ?: q.lastPrice) })
                result = ranked.mapNotNull { (s, _) -> quotes[s]?.let { s to it } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                com.marksy.os.ai.DiagLog.i("MarksyUpstox", "most active failed: ${e.javaClass.simpleName} ${e.message}")
                if (result == null) result = emptyList()
            }
            if (!com.marksy.os.upstox.UpstoxFeed.isMarketOpen()) break
            kotlinx.coroutines.delay(60_000)
        }
    }
    return result
}

/** NIFTY 50 constituents used to rank live movers; symbols Upstox doesn't know are simply skipped. */
internal object Nifty50 {
    val SYMBOLS = listOf(
        "ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK", "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV",
        "BEL", "BHARTIARTL", "CIPLA", "COALINDIA", "DRREDDY", "EICHERMOT", "ETERNAL", "GRASIM", "HCLTECH", "HDFCBANK",
        "HDFCLIFE", "HEROMOTOCO", "HINDALCO", "HINDUNILVR", "ICICIBANK", "INDIGO", "INFY", "ITC", "JIOFIN", "JSWSTEEL",
        "KOTAKBANK", "LT", "M&M", "MARUTI", "MAXHEALTH", "NESTLEIND", "NTPC", "ONGC", "POWERGRID", "RELIANCE",
        "SBILIFE", "SBIN", "SHRIRAMFIN", "SUNPHARMA", "TATACONSUM", "TATAMOTORS", "TATASTEEL", "TCS", "TECHM",
        "TITAN", "TRENT", "ULTRACEMCO", "WIPRO"
    )
}
