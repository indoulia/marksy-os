package com.marksy.os.ui

import com.marksy.os.gateway.MarketState
import java.util.Locale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.StockMentions

@Composable
fun TradingIntelligenceScreen(
    insights: List<TradingInsight>,
    padding: PaddingValues,
    market: MarketState = MarketState.Loading,
    selectedFilter: String = TradingFilters.first(),
    onFilterSelected: (String) -> Unit = {},
    onOpenStock: (String) -> Unit = {},
    marketRepository: com.marksy.os.market.MarketIntelligenceRepository? = null,
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN,
    onTipsStatusChange: (MyTipsStatus) -> Unit = {}
) {
    val actions = listOfNotNull(
        if (selectedFilter == TAB_TIPS) FloatingAction(Icons.Default.FilterList, if (tipsStatus.next() == MyTipsStatus.FOLLOWING) "Show who you follow" else "Show ${tipsStatus.next().label.lowercase()} tips") { onTipsStatusChange(tipsStatus.next()) } else null
    )
    // Marksy supplies the calls and their record; prices tick live from the user's Upstox feed.
    Box(
        Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
    Column(Modifier.fillMaxSize()) {
        when {
            selectedFilter == TAB_PREDICTIONS && marketRepository != null -> PredictionsView(marketRepository, OneHandListBottomPadding, onOpenStock)
            selectedFilter == TAB_TIPS && marketRepository != null -> MyTipsView(marketRepository, tipsStatus, OneHandListBottomPadding, onOpenStock)
            selectedFilter == TAB_PICKS -> SetupsView(marketRepository, OneHandListBottomPadding, onOpenStock)
            else -> CapturedList(insights, onOpenStock)
        }
    }
    OneHandControls(
        filters = TradingFilters.map { it to it },
        selectedFilter = selectedFilter,
        onFilterSelected = onFilterSelected,
        actions = actions
    )
    }
}

/** Broker executions and confirmations kept on the phone; a ticker named in one opens its stock page. */
@Composable
private fun CapturedList(insights: List<TradingInsight>, onOpenStock: (String) -> Unit) {
    val isSymbol = rememberSymbolCheck()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = OneHandListBottomPadding)
    ) {
        if (insights.isEmpty()) item { EmptyState("No trading events captured yet.", "Order executions and confirmations from your broker apps appear here.") }
        items(insights, key = { it.eventId }) { insight ->
            val symbol = remember(insight.eventId, isSymbol) { StockMentions.find("${insight.title} ${insight.body}", isSymbol, limit = 1).firstOrNull() }
            CapturedInsightCard(insight, symbol?.let { { onOpenStock(it) } })
        }
    }
}

private const val TAB_PICKS = "Setups"
private const val TAB_PREDICTIONS = "Predictions"
private const val TAB_TIPS = "My tips"
private const val TAB_CAPTURED = "Captured"
val TradingFilters = listOf(TAB_PICKS, TAB_PREDICTIONS, TAB_TIPS, TAB_CAPTURED)
/** The My tips filter, for a tip-alert notification that opens My tips · Following. */
const val TradingTipsFilter = TAB_TIPS

/** Title superscript: the tab, plus Marksy's scan session or the My tips status. */
fun tradingTitleNote(
    filter: String,
    scan: com.marksy.os.market.LatestScanDto?,
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN
): String = when (filter) {
    TAB_TIPS -> "$filter · ${tipsStatus.label}"
    else -> filter + (if (filter == TAB_PICKS || filter == TAB_PREDICTIONS) com.marksy.os.market.PicksBasis.day(scan?.scanSessionDate)?.let { " · $it" } else null).orEmpty()
}


private fun marketMessage(market: MarketState, loaded: String): String = when (market) {
    MarketState.Loading -> "Loading market data…"
    MarketState.NotConfigured -> "Connect the Marksy gateway in Settings to load market data."
    is MarketState.Unavailable -> "Marksy market data is unavailable right now."
    is MarketState.Loaded -> loaded
}

private fun changeColor(change: String): Color = if (change.startsWith("-")) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald

@Composable
private fun TradingTickerCard(
    symbol: String,
    price: String,
    change: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MarksyTheme.BadgeFinanceBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📊", fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(price, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(change, color = changeColor(change), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CapturedInsightCard(
    insight: TradingInsight,
    onClick: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface, disabledContainerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)),
        onClick = onClick ?: {},
        enabled = onClick != null
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    insight.source.ifBlank { "Trading Source" },
                    color = MarksyTheme.PrimaryEmerald,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(listOfNotNull(compactTime(insight.postedAt), insight.status).joinToString(" · "), color = MarksyTheme.TextMuted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                insight.headline.ifBlank { "Trading Alert" },
                color = MarksyTheme.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (insight.body.isNotBlank()) {
                Text(
                    insight.body,
                    color = MarksyTheme.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
