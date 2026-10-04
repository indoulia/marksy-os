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
import com.marksy.os.market.StockMentions

@Composable
fun TradingIntelligenceScreen(
    captured: List<com.marksy.os.data.local.NotificationEventEntity>,
    padding: PaddingValues,
    market: MarketState = MarketState.Loading,
    selectedFilter: String = TradingFilters.first(),
    onFilterSelected: (String) -> Unit = {},
    onOpenStock: (String) -> Unit = {},
    marketRepository: com.marksy.os.market.MarketIntelligenceRepository? = null,
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN,
    onTipsStatusChange: (MyTipsStatus) -> Unit = {},
    onRetry: (Long) -> Unit = {},
    onSendNow: () -> Unit = {},
    onAllowBackground: () -> Unit = {},
    showHealth: Boolean = false,
    onHealthDismiss: () -> Unit = {},
    captureInbox: CaptureInbox = CaptureInbox()
) {
    var show by rememberSaveable { mutableStateOf(ShowOnly.ALL) }
    var stackBy by rememberSaveable { mutableStateOf(StackBy.SOURCE) }
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
            else -> CapturedList(captured, stackBy, show, onOpenStock, onRetry, onSendNow, onAllowBackground, showHealth, onHealthDismiss, captureInbox)
        }
    }
    OneHandControls(
        filters = TradingFilters.map { it to it },
        selectedFilter = selectedFilter,
        onFilterSelected = onFilterSelected,
        filterIsView = false,
        actions = if (selectedFilter == TAB_CAPTURED) listOf(FloatingAction(Icons.Default.AddPhotoAlternate, "Add screenshot", onClick = captureInbox.onAddScreenshot)) else emptyList(),
        // One filter button: My tips' status and Captured's options sit in its panel under the tabs.
        extrasActive = (selectedFilter == TAB_CAPTURED && show != ShowOnly.ALL) || (selectedFilter == TAB_TIPS && tipsStatus != MyTipsStatus.OPEN),
        filterExtras = when (selectedFilter) {
            TAB_CAPTURED -> ({ CapturedFilterSections(show, { show = it }, stackBy, { stackBy = it }) })
            TAB_TIPS -> ({ TipsFilterSection(tipsStatus, onTipsStatusChange) })
            else -> null
        }
    )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TipsFilterSection(status: MyTipsStatus, onStatus: (MyTipsStatus) -> Unit) {
    Column(Modifier.widthIn(max = 260.dp).padding(start = MarksySpace.CardPadding, end = MarksySpace.CardPadding, top = MarksySpace.Gap, bottom = MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Text("SHOW", color = MarksyTheme.TextMuted, style = MarksyType.Label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            MyTipsStatus.entries.forEach { s -> Pill(s.label, selected = status == s) { onStatus(s) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapturedFilterSections(show: ShowOnly, onShow: (ShowOnly) -> Unit, stackBy: StackBy, onStackBy: (StackBy) -> Unit) {
    Column(Modifier.widthIn(max = 260.dp).padding(start = MarksySpace.CardPadding, end = MarksySpace.CardPadding, top = MarksySpace.Gap, bottom = MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Text("SHOW", color = MarksyTheme.TextMuted, style = MarksyType.Label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            listOf(ShowOnly.ALL to "All", ShowOnly.TIPS to "Tips", ShowOnly.NEEDS_YOU to "Needs you").forEach { (v, l) -> Pill(l, selected = show == v) { onShow(v) } }
        }
        Text("STACK BY", color = MarksyTheme.TextMuted, style = MarksyType.Label, modifier = Modifier.padding(top = MarksySpace.Tight))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            listOf(StackBy.SOURCE to "Source", StackBy.SYMBOL to "Symbol").forEach { (v, l) -> Pill(l, selected = stackBy == v) { onStackBy(v) } }
        }
    }
}

/** Capture log lanes; a ticker named in a call opens its stock page. */
@Composable
private fun CapturedList(
    events: List<com.marksy.os.data.local.NotificationEventEntity>, stackBy: StackBy, show: ShowOnly, onOpenStock: (String) -> Unit,
    onRetry: (Long) -> Unit, onSendNow: () -> Unit, onAllowBackground: () -> Unit, showHealth: Boolean, onHealthDismiss: () -> Unit, inbox: CaptureInbox
) {
    val isSymbol = rememberSymbolCheck()
    val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() } }
    val lanes = remember(events, stackBy, show, isSymbol, now) {
        CapturedModel.lanes(events, now, stackBy, show) { StockMentions.find(it, isSymbol, limit = 1).firstOrNull() }
    }
    CapturedScreen(lanes, now, onOpenStock, onRetry, onSendNow, onAllowBackground, showHealth, onHealthDismiss, inbox)
}

private const val TAB_PICKS = "Setups"
private const val TAB_PREDICTIONS = "Results"
private const val TAB_TIPS = "My tips"
private const val TAB_CAPTURED = "Captured"
val TradingFilters = listOf(TAB_PICKS, TAB_PREDICTIONS, TAB_TIPS, TAB_CAPTURED)
/** The My tips filter, for a tip-alert notification that opens My tips · Following. */
const val TradingTipsFilter = TAB_TIPS

/** Title superscript: the tab, plus Marksy's scan session or the My tips status. */
fun tradingTitleNote(
    filter: String,
    scan: com.marksy.os.market.LatestScanDto?,
    tipsStatus: MyTipsStatus = MyTipsStatus.OPEN,
    capturedNote: String? = null
): String = when (filter) {
    TAB_CAPTURED -> capturedNote ?: filter
    TAB_TIPS -> "$filter · ${tipsStatus.label}"
    else -> filter + (if (filter == TAB_PICKS) com.marksy.os.market.PicksBasis.day(scan?.scanSessionDate)?.let { " · $it" } else null).orEmpty()
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
