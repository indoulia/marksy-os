package com.marksy.os.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.alerts.TipAlertNotifier
import com.marksy.os.alerts.TipAlertWorker
import com.marksy.os.market.FollowDto
import com.marksy.os.market.FollowKey
import com.marksy.os.market.FollowListDto
import com.marksy.os.market.Follows
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.TipAlertDto
import kotlinx.coroutines.launch

/** Follow / Following as a Marksy pill; hidden while the server's follow state is unknown. */
@Composable
internal fun FollowPill(following: Boolean?, onToggle: (Boolean) -> Unit, compact: Boolean = false) {
    following ?: return
    Pill(if (following) "Following" else "Follow", selected = following, compact = compact) { onToggle(!following) }
}

/** Saves a follow on the server: the shared set changes at once, settles on the reply, and a refusal toasts. */
@Composable
internal fun rememberFollowToggle(repository: MarketIntelligenceRepository): (FollowKey, String, Boolean) -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(repository, scope, context, permission) {
        { key, name, follow ->
            scope.launch {
                val result = repository.setFollowing(key, follow)
                if (result !is MarketDataState.Loaded) {
                    Toast.makeText(context, Follows.failureText(result, follow, name), Toast.LENGTH_SHORT).show()
                } else if (follow) {
                    TipAlertWorker.schedule(context)
                    // I5: asked once, on the first follow, so a followed source's calls can reach the shade.
                    if (TipAlertNotifier.shouldAskPermission(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}

/** My tips · Following: tip alerts (new calls from followed channels, callers and engines; entries; closes), then who is followed. */
@Composable
internal fun FollowingView(repository: MarketIntelligenceRepository, bottomPadding: Dp, onOpenStock: (String) -> Unit) {
    val follows by produceState<MarketDataState<FollowListDto>>(MarketDataState.Loading) { value = repository.follows() }
    val alerts by produceState<MarketDataState<List<TipAlertDto>>>(MarketDataState.Loading) { value = repository.tipAlerts() }
    val followed by MarketIntelligenceRepository.followed.collectAsState()
    val toggle = rememberFollowToggle(repository)
    val scope = rememberCoroutineScope()
    var read by remember { mutableStateOf(emptySet<Long>()) }
    var open by remember { mutableStateOf<String?>(null) }
    open?.let { id -> TipDetailDialog(repository, id, onOpenStock = { open = null; onOpenStock(it) }) { open = null } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding)
    ) {
        (alerts as? MarketDataState.Loaded)?.value?.let { list ->
            items(list, key = { "alert-${it.id}" }) { a ->
                TipAlertRow(a, unread = a.unread && a.id !in read) {
                    a.tipId?.let { open = it }
                    if (a.unread && a.id !in read) {
                        read = read + a.id
                        scope.launch { repository.markAlertRead(a.id) }
                    }
                }
            }
        }
        when (val f = follows) {
            is MarketDataState.Loading -> item { MarksyLoader("Loading who you follow...") }
            is MarketDataState.Unavailable -> item { EmptyState("Marksy is not connected", "Sign in to your Marksy account in More.") }
            is MarketDataState.Error -> item { EmptyState("Your follows are unavailable", f.message) }
            is MarketDataState.Empty -> item { EmptyState(MyTipsStatus.FOLLOWING.empty, "Follow a channel, caller or engine from Scorecards or a stock's calls to hear about its calls.") }
            is MarketDataState.Loaded -> items(f.value.items, key = { "follow-${it.key.type}-${it.key.id}" }) { item ->
                FollowRow(item, Follows.isFollowing(followed, item.key, fallback = true)) { toggle(item.key, item.name, it) }
            }
            else -> Unit
        }
    }
}

@Composable
private fun TipAlertRow(alert: TipAlertDto, unread: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface)
            .border(1.dp, if (unread) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(alert.message, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
        compactTime(alert.triggeredAt)?.let { Text(it, color = MarksyTheme.TextMuted, fontSize = 10.sp) }
    }
}

@Composable
private fun FollowRow(item: FollowDto, following: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.name + (item.channelName?.takeIf { it != item.name }?.let { " · $it" } ?: ""), color = MarksyTheme.TextPrimary,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(if (item.engine) "Marksy engine" else item.channelType?.let(LedgerCalls::channelType), LedgerCalls.recordText(item.headline)).joinToString(" · "),
                color = MarksyTheme.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        FollowPill(following, onToggle)
    }
}
