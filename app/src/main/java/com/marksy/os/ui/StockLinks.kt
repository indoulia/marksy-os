package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.market.StockMentions
import com.marksy.os.notification.TradeCallParser
import com.marksy.os.upstox.UpstoxInstruments

/** Whether a ticker is in the instrument master; false for everything until the (cached) master loads. */
@Composable
fun rememberSymbolCheck(): (String) -> Boolean {
    val context = LocalContext.current
    val symbols by produceState<Map<String, String>?>(null) { value = runCatching { UpstoxInstruments.load(context) }.getOrNull() }
    return remember(symbols) { val known = symbols; { s: String -> known?.containsKey(s) == true } }
}

private val STOCK_CATEGORIES = setOf("MARKET", "TRADING")

/** Stocks a market or trading event is about: the parsed call's symbol first, then tickers in its text. */
fun stocksIn(event: NotificationEventEntity, isSymbol: (String) -> Boolean): List<String> {
    if (event.category !in STOCK_CATEGORIES && !event.isTrading) return emptyList()
    val call = TradeCallParser.parse(event.title, event.body)?.symbol?.takeIf(isSymbol)
    return (listOfNotNull(call) + StockMentions.find("${event.title} ${event.body}", isSymbol)).distinct().take(3)
}

/** Small emerald ticker pills that open the stock page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StockLinkPills(symbols: List<String>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (symbols.isEmpty()) return
    val shape = RoundedCornerShape(10.dp)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        symbols.forEach { symbol ->
            Row(
                Modifier.clip(shape).background(MarksyTheme.BadgeTradingBg).border(1.dp, MarksyTheme.BorderGlow, shape)
                    .clickable { onOpen(symbol) }.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ShowChart, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(symbol, color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
