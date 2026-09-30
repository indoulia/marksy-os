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
    OPEN("Open", "ACTIVE", "No open tips"), CLOSED("Closed", "CLOSED", "No closed tips yet"), ALL("All", null, "No tips yet");

    fun next(): MyTipsStatus = entries[(ordinal + 1) % entries.size]
}

/** The calls this customer received, as the ledger tracks them (spec §9 `/me/tips`); other holders are only a count. */
@Composable
internal fun MyTipsView(repository: MarketIntelligenceRepository, status: MyTipsStatus, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    val paged = remember(status) { Paged { c -> repository.myTips(status.param, c).map { it.items to it.nextCursor } } }
    LaunchedEffect(paged) { paged.more() }
    val quotes = rememberUpstoxQuotes(remember(paged.items) { paged.items.filter { LedgerCalls.isActive(it.tip) }.map { it.tip.symbol }.distinct() })
    var open by remember { mutableStateOf<String?>(null) }
    open?.let { id -> TipDetailDialog(repository, id, onOpenStock = { open = null; onOpenStock(it) }) { open = null } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding)
    ) {
        when (val s = paged.state) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading your tips...") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Your tips are unavailable", s.message) }
            is MarketDataState.Empty -> item { EmptyState(status.empty, "Calls from your broker apps, allow-listed groups and SMS senders appear here once Marksy records them.") }
            else -> {
                items(paged.items, key = { "tip-${it.tip.tipId}" }) { t -> MyTipRow(t, quotes[t.tip.symbol]?.lastPrice) { open = t.tip.tipId } }
                if (paged.hasMore) item(key = "more-${paged.items.size}") {
                    LaunchedEffect(Unit) { paged.more() }
                    MarksyLoader("Loading more...")
                }
            }
        }
    }
}

@Composable
private fun MyTipRow(item: MyTipDto, livePrice: Double?, onClick: () -> Unit) {
    val t = item.tip
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.symbol, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            t.direction?.let { d ->
                Text(
                    d, color = if (d == "SELL") MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(MarksyTheme.SurfaceRaised).padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(LedgerCalls.state(t), color = toneColor(LedgerCalls.tone(t)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(LedgerCalls.levelsText(t), color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 2.dp)) {
            LedgerCalls.progressText(t)?.let { Text(it, color = toneColor(LedgerCalls.progressTone(t)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.weight(1f))
            livePrice?.let { Text("${callRupees(it)} now", color = MarksyTheme.TextPrimary, fontSize = 11.sp) }
        }
        Text(
            listOfNotNull(t.channel?.name, t.caller?.name, item.channelHeadline?.let(LedgerCalls::recordText)).joinToString(" · "),
            color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(LedgerCalls.receivedVia(item), color = MarksyTheme.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
