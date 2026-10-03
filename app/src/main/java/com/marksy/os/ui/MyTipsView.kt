package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.MyTipDto

enum class MyTipsStatus(val label: String, val param: String?, val empty: String) {
    OPEN("Open", "ACTIVE", "No open tips"), CLOSED("Closed", "CLOSED", "No closed tips yet"), ALL("All", null, "No tips yet"),
    FOLLOWING("Following", null, "Not following anyone yet");

    fun next(): MyTipsStatus = entries[(ordinal + 1) % entries.size]
}

/** The calls this customer received, as the ledger tracks them (spec §9 `/me/tips`); other holders are only a count. */
@Composable
internal fun MyTipsView(repository: MarketIntelligenceRepository, status: MyTipsStatus, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    if (status == MyTipsStatus.FOLLOWING) return FollowingView(repository, bottomPadding, onOpenStock)
    val paged = remember(status) { Paged { c -> repository.myTips(status.param, c).map { it.items to it.nextCursor } } }
    LaunchedEffect(paged) { paged.more() }
    val quotes = rememberUpstoxQuotes(remember(paged.items) { paged.items.filter { LedgerCalls.isActive(it.tip) }.map { it.tip.symbol }.distinct() })
    var open by remember { mutableStateOf<String?>(null) }
    open?.let { id -> TipDetailDialog(repository, id, onOpenStock = { open = null; onOpenStock(it) }) { open = null } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = MarksySpace.Gutter),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap),
        contentPadding = PaddingValues(top = MarksySpace.ListGap, bottom = bottomPadding)
    ) {
        when (val s = paged.state) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading your tips…") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Your tips are unavailable", s.message) }
            is MarketDataState.Empty -> item { EmptyState(status.empty, "Calls from your broker apps, allow-listed groups and SMS senders appear here once Marksy records them.") }
            else -> {
                items(paged.items, key = { "tip-${it.tip.tipId}" }) { t -> MyTipRow(t, quotes[t.tip.symbol]?.lastPrice) { open = t.tip.tipId } }
                if (paged.hasMore) item(key = "more-${paged.items.size}") {
                    LaunchedEffect(Unit) { paged.more() }
                    MarksyLoader("Loading more…")
                }
            }
        }
    }
}

@Composable
private fun MyTipRow(item: MyTipDto, livePrice: Double?, onClick: () -> Unit) {
    val t = item.tip
    MarksyRowCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead)
            t.direction?.let { d ->
                MarksyBadge(d, if (d == "SELL") MarksyTheme.Negative else MarksyTheme.Positive, MarksyTheme.SurfaceRaised, Modifier.padding(start = MarksySpace.Inner))
            }
            Spacer(Modifier.weight(1f))
            Text(LedgerCalls.state(t), color = toneColor(LedgerCalls.tone(t)), style = MarksyType.Meta, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(LedgerCalls.levelsText(t), color = MarksyTheme.TextSecondary, style = MarksyType.Meta, modifier = Modifier.padding(top = MarksySpace.Hair))
        Row(Modifier.padding(top = MarksySpace.Hair)) {
            LedgerCalls.progressText(t)?.let { Text(it, color = toneColor(LedgerCalls.progressTone(t)), style = MarksyType.Meta, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.weight(1f))
            livePrice?.let { Text("${callRupees(it)} now", color = MarksyTheme.TextPrimary, style = MarksyType.Meta) }
        }
        Text(
            listOfNotNull(t.channel?.name, t.caller?.name, item.channelHeadline?.let(LedgerCalls::recordText)).joinToString(" · "),
            color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(LedgerCalls.receivedVia(item), color = MarksyTheme.TextMuted, style = MarksyType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
