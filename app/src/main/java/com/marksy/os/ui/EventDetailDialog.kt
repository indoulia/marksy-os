package com.marksy.os.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.marksy.os.data.ActionRepository
import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.ActionEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DetailSecondary = Color(0xFF9AA9A1)
private val DetailMuted = Color(0xFF657169)

@Composable
fun EventDetailDialog(
    event: NotificationEventEntity,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDismiss: () -> Unit,
    actions: List<ActionEngine.Available> = emptyList(),
    actionMessage: String? = null,
    onAction: (ActionEngine.Type, Long?, String?) -> Unit = { _, _, _ -> },
    onRequestNotificationPermission: () -> Unit = {},
    related: List<ContextEntity> = emptyList(),
    onUnlinkEntity: (Long) -> Unit = {},
) {
    val scrollState = rememberScrollState()
    var reporting by remember(event.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MarksyTheme.Surface,
        titleContentColor = MarksyTheme.TextPrimary,
        textContentColor = MarksyTheme.TextSecondary,
        title = { Text(event.title.ifBlank { "Notification event" }, color = MarksyTheme.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(scrollState)) {
                Text(event.sourceName, color = MarksyTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(event.body.ifBlank { "No notification body was captured." }, color = DetailSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                DetailRow("Category", event.category.lowercase().replaceFirstChar { it.uppercase() })
                DetailRow("Captured", formatTimestamp(event.postedAt))
                DetailRow("Priority", event.priority.toString())
                DetailRow("Classifier", "${(event.confidence * 100).toInt()}%")
                if (event.isTrading) {
                    DetailRow("Marksy delivery", event.deliveryState.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                    event.insightSummary?.takeIf { it.isNotBlank() }?.let { DetailRow("Marksy", it) }
                    event.insightAction?.takeIf { it.isNotBlank() }?.let { DetailRow("Action", it) }
                    event.insightConfidence?.let { DetailRow("Marksy confidence", "${(it * 100).toInt()}%") }
                }
                if (related.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Related", color = DetailMuted, fontSize = 11.sp)
                    related.forEach { r ->
                        Text(
                            "${r.displayName} · ${r.type.lowercase()} · ${r.mentionCount} events" + if (r.sourceCount > 1) " across ${r.sourceCount} apps" else "",
                            color = DetailSecondary, fontSize = 12.sp
                        )
                        TextButton(onClick = { onUnlinkEntity(r.id) }) { Text("Not related", fontSize = 11.sp) }
                    }
                }
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Actions", color = DetailMuted, fontSize = 11.sp)
                    actionMessage?.let { Text(it, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp) }
                    actions.forEach { a ->
                        when {
                            a.type == ActionEngine.Type.REMIND && !a.enabled ->
                                TextButton(onClick = onRequestNotificationPermission) { Text(a.reason, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp) }
                            a.type == ActionEngine.Type.REMIND -> {
                                TextButton(onClick = { onAction(a.type, System.currentTimeMillis() + 60 * 60 * 1000L, null) }) { Text("Remind me in 1 hour", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp) }
                                TextButton(onClick = { onAction(a.type, tomorrowNine(), null) }) { Text("Remind me tomorrow 9:00", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp) }
                            }
                            a.type == ActionEngine.Type.REPORT -> {
                                TextButton(onClick = { reporting = !reporting }) { Text(a.type.label, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp) }
                                if (reporting) ActionRepository.CATEGORIES.filter { it != event.category && it != "TRADING" }.forEach { c ->
                                    TextButton(onClick = { reporting = false; onAction(a.type, null, c) }) { Text("It is ${c.lowercase()}", fontSize = 12.sp) }
                                }
                            }
                            else -> TextButton(onClick = { onAction(a.type, null, null) }) {
                                Text(a.type.label, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (event.archived) "Archived locally on this device" else "Stored locally on this device", color = DetailMuted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = if (event.archived) onUnarchive else onArchive) {
                    Text(if (event.archived) "Restore to Inbox" else "Archive", color = MarksyTheme.PrimaryEmerald)
                }
                TextButton(onClick = onDismiss) { Text("Close", color = MarksyTheme.PrimaryEmerald) }
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Text(label, color = DetailMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
    Text(value, color = Color(0xFFE8F1EC), fontSize = 13.sp)
}

private fun tomorrowNine(): Long {
    val zone = java.time.ZoneId.systemDefault()
    return java.time.LocalDate.now(zone).plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
