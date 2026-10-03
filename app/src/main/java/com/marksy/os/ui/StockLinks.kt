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
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.market.StockMentions
import com.marksy.os.upstox.UpstoxInstruments

/** Whether a ticker is in the instrument master; false for everything until the (cached) master loads. */
@Composable
fun rememberSymbolCheck(): (String) -> Boolean {
    val context = LocalContext.current
    val symbols by produceState<Map<String, String>?>(null) { value = runCatching { UpstoxInstruments.load(context) }.getOrNull() }
    return remember(symbols) { val known = symbols; { s: String -> known?.containsKey(s) == true } }
}

private val STOCK_CATEGORIES = setOf("MARKET", "TRADING")

/** Stocks a market or trading event, or one Marksy recorded as a tip, is about: the tickers named in its text. */
fun stocksIn(event: NotificationEventEntity, isSymbol: (String) -> Boolean): List<String> {
    if (event.category !in STOCK_CATEGORIES && !event.isTrading && event.marksyTipId == null) return emptyList()
    return StockMentions.find("${event.title} ${event.body}", isSymbol)
}

/** Small emerald ticker pills that open the stock page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StockLinkPills(symbols: List<String>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (symbols.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        symbols.forEach { symbol ->
            Pill(symbol) { onOpen(symbol) }
        }
    }
}
