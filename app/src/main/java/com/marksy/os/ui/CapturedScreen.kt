package com.marksy.os.ui

import java.time.ZoneId
import java.time.Instant
import com.marksy.os.MarksyFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marksy.os.EmptyState
import com.marksy.os.data.local.DeliveryState
import java.math.BigDecimal

private const val SHOWN_PER_STACK = 2

/** Capture log: what the phone caught and whether Marksy got it, in lanes (Needs you, Today, Earlier, Rejected orders). */
@Composable
fun CapturedScreen(
    lanes: CapturedLanes,
    now: Long,
    onOpenStock: (String) -> Unit,
    onRetry: (Long) -> Unit,
    onSendNow: () -> Unit,
    onAllowBackground: () -> Unit,
    showHealth: Boolean,
    onHealthDismiss: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var earlierOpen by rememberSaveable { mutableStateOf(false) }
    var rejectedOpen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CapturedRow?>(null) }
    val toggle: (String) -> Unit = { key -> expanded = if (key in expanded) expanded - key else expanded + key }

    MarksyList {
        if (lanes.isEmpty) item { EmptyState("Nothing captured yet", "Calls from groups, SMS and research apps, and broker orders, appear here once your phone catches them.") }
        if (lanes.needsYou.isNotEmpty()) {
            item { SectionLabel("Needs you", lanes.needsYou.size, MarksyTheme.Negative) }
            items(lanes.needsYou, key = { "n${it.row.event.id}" }) { NeedCard(it, now, { selected = it.row }, onRetry, onSendNow, onAllowBackground) }
        }
        if (lanes.today.isNotEmpty()) {
            item { SectionLabel("Today", lanes.today.sumOf { it.rows.size }, MarksyTheme.Positive) }
            items(lanes.today, key = { "t${it.key}" }) { StackCard("t${it.key}", it, now, "t${it.key}" in expanded, toggle) { selected = it } }
        }
        if (lanes.earlier.isNotEmpty()) {
            val count = lanes.earlier.sumOf { it.rows.size }
            item {
                FoldRow("Earlier · $count ${if (count == 1) "call" else "calls"} from ${lanes.earlier.size} ${if (lanes.earlier.size == 1) "source" else "sources"}", "Older than this session, or retired", earlierOpen) { earlierOpen = !earlierOpen }
            }
            if (earlierOpen) items(lanes.earlier, key = { "e${it.key}" }) { StackCard("e${it.key}", it, now, "e${it.key}" in expanded, toggle) { selected = it } }
        }
        if (lanes.rejected.isNotEmpty()) {
            item { FoldRow("Rejected orders · ${lanes.rejected.size}", "Your broker turned these down", rejectedOpen) { rejectedOpen = !rejectedOpen } }
            if (rejectedOpen) items(lanes.rejected, key = { "r${it.event.id}" }) { r ->
                MarksyRowCard(onClick = { selected = r }) { TipRow(r, now, firstRow = true) }
            }
        }
    }
    selected?.let { RowDialog(it, onOpenStock) { selected = null } }
    if (showHealth) HealthDialog(lanes.health, onHealthDismiss)
}

@Composable
private fun FoldRow(title: String, sub: String, open: Boolean, onClick: () -> Unit) {
    MarksyRowCard(onClick = onClick) {
        Text("${if (open) "▴" else "▾"} $title", color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
        Text(sub, color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedCard(need: NeedItem, now: Long, onOpen: () -> Unit, onRetry: (Long) -> Unit, onSendNow: () -> Unit, onAllowBackground: () -> Unit) {
    val r = need.row
    val failed = need.kind == NeedKind.RETRY
    MarksyCard(border = if (failed) MarksyTheme.RedUrgent.copy(alpha = 0.45f) else MarksyTheme.YellowImportant.copy(alpha = 0.4f), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (failed) MarksyBadge("Not sent", MarksyTheme.RedUrgent, MarksyTheme.BadgeUrgentBg) else MarksyBadge("Waiting ${need.waitingMinutes} min", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
            Spacer(Modifier.width(MarksySpace.Gap))
            Text("${sourceOf(r)} · ${compactTime(r.event.postedAt, now) ?: ""}", color = MarksyTheme.TextMuted, style = MarksyType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(r.headline, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (failed) noteLabel(r.event.deliveryNote) ?: "Marksy did not receive this call." else "Android is holding the send back; it goes out when the phone allows.",
            color = MarksyTheme.TextSecondary, style = MarksyType.Small
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            if (failed) Pill("Retry", selected = true) { onRetry(r.event.id) }
            else {
                Pill("Send now", selected = true) { onSendNow() }
                Pill("Allow background") { onAllowBackground() }
            }
        }
    }
}

@Composable
private fun StackCard(id: String, stack: CapturedStack, now: Long, expanded: Boolean, onToggle: (String) -> Unit, onOpen: (CapturedRow) -> Unit) {
    val shown = if (expanded) stack.rows else stack.rows.take(SHOWN_PER_STACK)
    val hidden = stack.rows.size - SHOWN_PER_STACK
    MarksyGroupCard {
        Row(Modifier.padding(vertical = MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
            Text(stack.label, color = MarksyTheme.PrimaryEmerald, style = MarksyType.Body, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(MarksySpace.Gap))
            Text("${stack.rows.size}", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        }
        shown.forEachIndexed { i, r ->
            MarksyDivider()
            Column(Modifier.marksyRow(onClick = { onOpen(r) })) { TipRow(r, now, firstRow = i == 0) }
        }
        if (hidden > 0) {
            val names = stack.rows.drop(SHOWN_PER_STACK).mapNotNull { it.levels?.symbol }.distinct().take(3).joinToString(", ")
            MarksyDivider()
            Text(
                if (expanded) "▴ Show less" else "▾ $hidden more" + if (names.isNotEmpty()) " · $names" else "",
                color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.Medium,
                modifier = Modifier.marksyRow(onClick = { onToggle(id) })
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TipRow(r: CapturedRow, now: Long, firstRow: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(r.headline, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(MarksySpace.Inner))
        Text(compactTime(r.event.postedAt, now) ?: "", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
    FlowRow(Modifier.padding(top = MarksySpace.Tight), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight), itemVerticalAlignment = Alignment.CenterVertically) {
        val (text, fg, bg) = deliveryChip(r)
        MarksyBadge(text, fg, bg)
        r.levels?.status?.let { s ->
            val tone = r.levels.tone
            MarksyBadge(s, if (tone == StatusTone.NEGATIVE) MarksyTheme.RedUrgent else if (tone == StatusTone.POSITIVE) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, if (tone == StatusTone.NEGATIVE) MarksyTheme.BadgeUrgentBg else if (tone == StatusTone.POSITIVE) MarksyTheme.BadgeTradingBg else MarksyTheme.SurfaceRaised)
        }
        if (r.folded > 0) Text("+${r.folded} forwarded", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
    val lv = r.levels
    if (lv != null && lv.hasLevels) {
        Text(levelsLine(lv), color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.padding(top = MarksySpace.Tight))
    } else if (firstRow || r.keptLocal) {
        Text(r.event.body, color = MarksyTheme.TextSecondary, style = MarksyType.Small, maxLines = if (firstRow) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MarksySpace.Tight))
    }
}

private fun deliveryChip(r: CapturedRow): Triple<String, Color, Color> = when (r.event.deliveryState) {
    DeliveryState.DELIVERED.name -> Triple("Sent", MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg)
    DeliveryState.PENDING.name -> Triple("Waiting", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
    DeliveryState.IN_FLIGHT.name -> Triple("Sending", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
    DeliveryState.FAILED.name -> Triple("Not sent", MarksyTheme.RedUrgent, MarksyTheme.BadgeUrgentBg)
    else -> Triple("On this phone", MarksyTheme.TextSecondary, MarksyTheme.SurfaceRaised)
}

private fun sourceOf(r: CapturedRow) = CapturedModel.sourceLabel(r.event)

private fun price(v: Double): String = BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

internal fun levelsLine(lv: TipLevels): String = listOfNotNull(
    lv.entry?.let { "Entry ${price(it)}" }, lv.target?.let { "Target ${price(it)}" }, lv.stopLoss?.let { "SL ${price(it)}" }
).joinToString(" · ")

internal fun noteLabel(note: String?): String? = when (note) {
    null -> null
    "rejected" -> "Marksy refused this message."
    "invalid" -> "The message could not be prepared for Marksy."
    "own-order", "own-account" -> "Your own orders and holdings never leave this phone."
    "one-to-one-chat" -> "Only group chats are sent; this was a one-to-one chat."
    "outside-capture-set", "sms-not-sender-id" -> "This source is not on your capture list."
    "not-a-candidate" -> "Not a call, so nothing was sent."
    else -> "Kept on this phone ($note)."
}

private fun fullTime(ms: Long): String = MarksyFormat.dayTime(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
private fun RowDialog(r: CapturedRow, onOpenStock: (String) -> Unit, onDismiss: () -> Unit) {
    val e = r.event
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.headline) },
        confirmButton = { MarksyButton("Close", onClick = onDismiss, style = MarksyButtonStyle.Text) },
        dismissButton = r.levels?.symbol?.let { s -> { MarksyButton("Open $s", onClick = { onDismiss(); onOpenStock(s) }, style = MarksyButtonStyle.Text) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                val lv = r.levels
                if (lv != null && lv.hasLevels) Text(levelsLine(lv), color = MarksyTheme.TextPrimary, style = MarksyType.Subhead)
                lv?.status?.let { Text("Marksy status: $it", style = MarksyTheme.DialogBody) }
                r.insight.marksyHorizonDays?.let { Text("Horizon $it days", style = MarksyTheme.DialogBody) }
                Text(e.body, color = MarksyTheme.TextPrimary, style = MarksyType.Body, maxLines = 12, overflow = TextOverflow.Ellipsis)
                Text(
                    when (e.deliveryState) {
                        DeliveryState.DELIVERED.name -> "Sent to Marksy${e.insightReceivedAt?.let { " at ${fullTime(it)}" } ?: ""}."
                        DeliveryState.PENDING.name -> "Waiting to go to Marksy."
                        DeliveryState.IN_FLIGHT.name -> "Sending to Marksy now."
                        DeliveryState.FAILED.name -> "Not sent. ${noteLabel(e.deliveryNote) ?: ""}"
                        else -> noteLabel(e.deliveryNote) ?: "Stays on this phone."
                    },
                    style = MarksyTheme.DialogBody
                )
                if (r.alsoIn.isNotEmpty()) Text("Also posted in ${r.alsoIn.joinToString(", ")}. Each group's copy is its own tip in Marksy's ledger.", style = MarksyTheme.DialogBody)
                if (e.lifecycleState == "RESOLVED") e.lifecycleReason?.let { Text(it.removePrefix("Retired: ").replaceFirstChar { c -> c.uppercase() } + ".", style = MarksyTheme.DialogBody) }
                Text("Captured ${fullTime(e.postedAt)} from ${sourceOf(r)}.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        }
    )
}

@Composable
private fun HealthDialog(h: CaptureHealth, onDismiss: () -> Unit) {
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture health") },
        confirmButton = { MarksyButton("Close", onClick = onDismiss, style = MarksyButtonStyle.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                listOf("Caught this session" to h.today, "Sent to Marksy" to h.sent, "Waiting" to h.waiting, "Not sent" to h.failed, "Kept on this phone" to h.kept).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, style = MarksyTheme.DialogBody)
                        Text("$v", color = if (k == "Not sent" && v > 0) MarksyTheme.RedUrgent else MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(h.lastDeliveredAt?.let { "Last reply from Marksy ${fullTime(it)}." } ?: "No reply from Marksy yet.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        }
    )
}
