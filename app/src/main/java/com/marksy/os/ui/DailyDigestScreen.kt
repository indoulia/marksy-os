package com.marksy.os.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.EmptyState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.SmartInboxModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DailyDigestScreen(
    events: List<NotificationEventEntity> = emptyList(),
    padding: PaddingValues = PaddingValues(),
    onOpenInbox: (filterName: String) -> Unit = {},
    onOpenTrading: () -> Unit = {},
    onEventSelected: (NotificationEventEntity) -> Unit = {}
) {
    val digest = remember(events) { DailyDigestModel.build(events) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "Today • " + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())),
                color = MarksyTheme.TextSecondary,
                fontSize = 13.sp
            )
        }

        if (digest.totalNotifications == 0) {
            item { EmptyState("Nothing captured today.", "Your digest builds up as notifications arrive.") }
            return@LazyColumn
        }

        item {
            DigestCard(borderColor = MarksyTheme.PrimaryEmerald) {
                Text(digest.title, color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))

                DigestItemRow(
                    icon = Icons.Default.Notifications,
                    iconColor = MarksyTheme.PrimaryEmerald,
                    bgColor = MarksyTheme.BadgeTradingBg,
                    text = "${digest.totalNotifications} notifications received"
                ) { onOpenInbox(SmartInboxModel.Filter.ALL.name) }
                DigestItemRow(
                    icon = Icons.Default.Bolt,
                    iconColor = MarksyTheme.YellowImportant,
                    bgColor = MarksyTheme.BadgeImportantBg,
                    text = "${digest.attentionEvents.size} required attention"
                ) {
                    if (digest.attentionEvents.isEmpty()) onOpenInbox(SmartInboxModel.Filter.ALL.name)
                    else scope.launch { listState.animateScrollToItem(ATTENTION_ITEM_INDEX) }
                }
                if (digest.tradingEvents.isNotEmpty()) {
                    val detail = listOfNotNull(
                        "${digest.deliveredTrading} analysed".takeIf { digest.deliveredTrading > 0 },
                        "${digest.pendingTrading} pending".takeIf { digest.pendingTrading > 0 },
                        "${digest.failedTrading} failed".takeIf { digest.failedTrading > 0 }
                    ).joinToString(" · ")
                    DigestItemRow(
                        icon = Icons.Default.ShowChart,
                        iconColor = MarksyTheme.PrimaryEmerald,
                        bgColor = MarksyTheme.BadgeTradingBg,
                        text = "${digest.tradingEvents.size} trading events" + if (detail.isNotEmpty()) " · $detail" else ""
                    ) { onOpenTrading() }
                }
                digest.categoryCounts.filterKeys { it != "TRADING" }.forEach { (category, count) ->
                    val filter = SmartInboxModel.Filter.entries.firstOrNull { it.category == category }
                    val (icon, tint, bg) = categoryStyle(category)
                    DigestItemRow(
                        icon = icon,
                        iconColor = tint,
                        bgColor = bg,
                        text = "${filter?.label ?: category.lowercase().replaceFirstChar { it.uppercase() }} · $count"
                    ) { onOpenInbox((filter ?: SmartInboxModel.Filter.ALL).name) }
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, digest.shareText())
                        context.startActivity(Intent.createChooser(send, "Share digest"))
                    },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald),
                    shape = RoundedCornerShape(25.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share Digest", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (digest.attentionEvents.isNotEmpty()) {
            item {
                DigestCard {
                    Text("Needs attention", color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    digest.attentionEvents.take(MAX_ATTENTION_ROWS).forEach { event ->
                        AttentionRow(event) { onEventSelected(event) }
                    }
                }
            }
        }

        if (digest.topSources.isNotEmpty()) {
            item {
                DigestCard {
                    Text("Top sources", color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    digest.topSources.forEach { (name, count) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, color = MarksyTheme.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$count", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private const val ATTENTION_ITEM_INDEX = 2
private const val MAX_ATTENTION_ROWS = 5

@Composable
private fun DigestCard(borderColor: Color = MarksyTheme.BorderGlow, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), content = content)
    }
}

@Composable
private fun DigestItemRow(
    icon: ImageVector,
    iconColor: Color,
    bgColor: Color,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AttentionRow(event: NotificationEventEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(event.title.ifBlank { "Notification event" }, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                event.sourceName.ifBlank { "System" } + " · " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.postedAt)),
                color = MarksyTheme.TextMuted,
                fontSize = 11.sp
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(18.dp))
    }
}

private fun categoryStyle(category: String): Triple<ImageVector, Color, Color> = when (category) {
    "EMAIL" -> Triple(Icons.Default.Email, Color(0xFF82B1FF), Color(0xFF0F1B2E))
    "DELIVERY" -> Triple(Icons.Default.LocalShipping, MarksyTheme.OrangeDelivery, MarksyTheme.BadgeDeliveryBg)
    "BANKING", "PAYMENTS", "BILLS" -> Triple(Icons.Default.AccountBalance, MarksyTheme.BlueFinance, MarksyTheme.BadgeFinanceBg)
    "MESSAGES" -> Triple(Icons.Default.Chat, MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg)
    else -> Triple(Icons.Default.Notifications, MarksyTheme.TextSecondary, MarksyTheme.SurfaceRaised)
}
