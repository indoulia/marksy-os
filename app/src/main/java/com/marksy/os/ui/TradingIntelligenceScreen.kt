package com.marksy.os.ui

import com.marksy.os.gateway.MarketState
import java.util.Locale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.marksy.os.MarksyFormat

@Composable
fun TradingIntelligenceScreen(
    padding: PaddingValues,
    market: MarketState = MarketState.Loading,
    selectedFilter: String = TradingFilters.first(),
    onFilterSelected: (String) -> Unit = {},
    onOpenStock: (String) -> Unit = {},
    marketRepository: com.marksy.os.market.MarketIntelligenceRepository? = null,
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN,
    onTipsStatusChange: (MyTipsStatus) -> Unit = {},
    historyScope: HistoryScope = HistoryScope.MINE,
    onHistoryScopeChange: (HistoryScope) -> Unit = {}
) {
    val following = tipsStatus == MyTipsStatus.FOLLOWING
    // Open: the user's live tips. Setups: Marksy's live calls. History: how calls ended. Captured calls are reviewed in Inbox.
    Box(
        Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
    Column(Modifier.fillMaxSize()) {
        when {
            selectedFilter == TAB_PICKS -> SetupsView(marketRepository, OneHandListBottomPadding, onOpenStock)
            marketRepository == null -> EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.")
            selectedFilter == TAB_HISTORY -> when (historyScope) {
                HistoryScope.MINE -> MyTipsView(marketRepository, MyTipsStatus.CLOSED, OneHandListBottomPadding, onOpenStock)
                else -> PredictionsView(marketRepository, historyScope == HistoryScope.ENDED, OneHandListBottomPadding, onOpenStock)
            }
            else -> MyTipsView(marketRepository, if (following) MyTipsStatus.FOLLOWING else MyTipsStatus.OPEN, OneHandListBottomPadding, onOpenStock)
        }
    }
    OneHandControls(
        filters = TradingFilters.map { it to it },
        selectedFilter = selectedFilter,
        onFilterSelected = onFilterSelected,
        filterIsView = false,
        // One filter button: each view's Show options sit in its panel under the views.
        extrasActive = (selectedFilter == TAB_HISTORY && historyScope != HistoryScope.MINE) || (selectedFilter !in setOf(TAB_PICKS, TAB_HISTORY) && following),
        filterExtras = when (selectedFilter) {
            TAB_PICKS -> null
            TAB_HISTORY -> ({ ShowSection(HistoryScope.entries, historyScope, HistoryScope::label, onHistoryScopeChange) })
            else -> ({ ShowSection(listOf(MyTipsStatus.OPEN, MyTipsStatus.FOLLOWING), if (following) MyTipsStatus.FOLLOWING else MyTipsStatus.OPEN, { if (it == MyTipsStatus.OPEN) "Mine" else it.label }, onTipsStatusChange) })
        }
    )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ShowSection(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.widthIn(max = 260.dp).padding(start = MarksySpace.CardPadding, end = MarksySpace.CardPadding, top = MarksySpace.Gap, bottom = MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Text("SHOW", color = MarksyTheme.TextMuted, style = MarksyType.Label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            options.forEach { o -> Pill(label(o), selected = o == selected) { onPick(o) } }
        }
    }
}

/** History's lists: the user's closed tips, or Marksy's own closed or ended calls. */
enum class HistoryScope(val label: String) { MINE("My tips"), MARKSY("Marksy closed"), ENDED("Marksy ended") }

private const val TAB_OPEN = "Open"
private const val TAB_PICKS = "Setups"
private const val TAB_HISTORY = "History"
val TradingFilters = listOf(TAB_OPEN, TAB_PICKS, TAB_HISTORY)
/** The Open view, whose Following list a tip-alert notification opens. */
const val TradingTipsFilter = TAB_OPEN
const val TradingSetupsFilter = TAB_PICKS
/** Ask Marksy's argument for Marksy's results: History, on Marksy's closed calls. */
const val TradingMarksyResults = "Marksy results"

/** An Ask Marksy argument → the view to show and, for Marksy's results, the History list; anything else opens Open. */
fun tradingRoute(arg: String?): Pair<String, HistoryScope?> =
    if (arg == TradingMarksyResults) TAB_HISTORY to HistoryScope.MARKSY else (TradingFilters.firstOrNull { it == arg } ?: TAB_OPEN) to null

/** Title superscript: the view, plus Marksy's scan session, Following, or History's list. */
fun tradingTitleNote(
    filter: String,
    scan: com.marksy.os.market.LatestScanDto?,
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN,
    historyScope: HistoryScope = HistoryScope.MINE
): String = when (filter) {
    TAB_PICKS -> filter + com.marksy.os.market.PicksBasis.day(scan?.scanSessionDate)?.let { " · $it" }.orEmpty()
    TAB_HISTORY -> "$filter · ${historyScope.label}"
    else -> TAB_OPEN + (if (tipsStatus == MyTipsStatus.FOLLOWING) " · ${tipsStatus.label}" else "")
}


private fun marketMessage(market: MarketState, loaded: String): String = when (market) {
    MarketState.Loading -> "Loading market data…"
    MarketState.NotConfigured -> "Marksy is not connected"
    is MarketState.Unavailable -> "Marksy market data is unavailable right now."
    is MarketState.Loaded -> loaded
}

private fun changeColor(change: String): Color = if (change.startsWith("-") || change.startsWith(MarksyFormat.MINUS)) MarksyTheme.Negative else MarksyTheme.PrimaryEmerald

@Composable
private fun TradingTickerCard(
    symbol: String,
    price: String,
    change: String
) {
    MarksyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(MarksySize.IconLarge)
                        .clip(CircleShape)
                        .background(MarksyTheme.BadgeFinanceBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📊", style = MarksyType.Small)
                }
                Spacer(Modifier.width(MarksySpace.Gap))
                Text(symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(price, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(MarksySpace.Inner))
                Text(change, color = changeColor(change), style = MarksyType.Small, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CapturedInsightCard(
    insight: TradingInsight,
    onClick: (() -> Unit)?
) {
    MarksyCard(onClick = onClick) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    insight.source.ifBlank { "Trading Source" },
                    color = MarksyTheme.PrimaryEmerald,
                    fontWeight = FontWeight.Bold,
                    style = MarksyType.Body
                )
                Text(listOfNotNull(compactTime(insight.postedAt), insight.status).joinToString(" · "), color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }
            Spacer(Modifier.height(MarksySpace.Tight))
            Text(
                insight.headline.ifBlank { "Trading Alert" },
                color = MarksyTheme.TextPrimary,
                style = MarksyType.Subhead,
                fontWeight = FontWeight.SemiBold
            )
            if (insight.body.isNotBlank()) {
                Text(
                    insight.body,
                    color = MarksyTheme.TextSecondary,
                    style = MarksyType.Small,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MarksySpace.Tight)
                )
            }
        }
    }
}
