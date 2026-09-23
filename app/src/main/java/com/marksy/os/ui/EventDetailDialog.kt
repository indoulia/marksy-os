package com.marksy.os.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.marksy.os.notification.ReminderTimes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.notification.OriginalAppLauncher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DetailSecondary = Color(0xFF9AA9A1)
private val DetailMuted = Color(0xFF657169)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EventDetailDialog(
    event: NotificationEventEntity,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDismiss: () -> Unit,
    onToggleKeep: () -> Unit = {},
    onSetReminder: (Long?) -> Unit = {},
    onMarkUnread: () -> Unit = {},
) {
    var showReminderOptions by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val actions = OriginalAppLauncher.actionsFor(event.sourcePackage, event.sourceKey)
    val details = buildList {
        add("Category" to event.category.lowercase().replaceFirstChar { it.uppercase() })
        add("Priority" to event.priority.toString())
        add("Classifier" to "${(event.confidence * 100).toInt()}%")
        if (event.isTrading) {
            add("Delivery" to event.deliveryState.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
            event.insightConfidence?.let { add("Marksy conf." to "${(it * 100).toInt()}%") }
        }
    }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MarksyTheme.Surface) {
            Column(Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        Text(
                            event.title.ifBlank { "Notification event" },
                            color = MarksyTheme.TextPrimary,
                            fontSize = 16.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${event.sourceName.ifBlank { "System" }} · ${formatTimestamp(event.postedAt)}",
                            color = DetailMuted,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MarksyTheme.TextPrimary, modifier = Modifier.size(20.dp))
                    }
                }
                SectionDivider()
                Column(Modifier.weight(1f, fill = false).padding(end = 6.dp).verticalScroll(scrollState)) {
                    Text(linkified(event.body.ifBlank { "No notification body was captured." }), color = DetailSecondary, fontSize = 13.sp)
                    if (actions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            actions.forEach { action ->
                                OutlinedButton(
                                    onClick = {
                                        if (!OriginalAppLauncher.send(context, action.intent)) {
                                            Toast.makeText(context, "\"${action.title}\" is no longer available", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) { Text(action.title, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp) }
                            }
                        }
                    }
                }
                SectionDivider()
                Column(Modifier.padding(end = 6.dp)) {
                    details.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (label, value) -> DetailCell(label, value, Modifier.weight(1f)) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (event.isTrading) {
                        event.insightSummary?.takeIf { it.isNotBlank() }?.let { DetailCell("Marksy", it, Modifier.fillMaxWidth().padding(bottom = 6.dp), singleLine = false) }
                        event.insightAction?.takeIf { it.isNotBlank() }?.let { DetailCell("Action", it, Modifier.fillMaxWidth().padding(bottom = 6.dp), singleLine = false) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        ActionChip(
                            if (event.kept) Icons.Default.Star else Icons.Default.StarBorder,
                            if (event.kept) "Kept" else "Keep",
                            selected = event.kept,
                            onClick = onToggleKeep
                        )
                        ActionChip(
                            Icons.Default.Alarm,
                            event.remindAt?.let { "Remind " + formatTimestamp(it) } ?: "Remind me",
                            selected = event.remindAt != null,
                            onClick = { showReminderOptions = !showReminderOptions }
                        )
                        ActionChip(Icons.Default.MarkEmailUnread, "Mark unread", selected = false) { onMarkUnread(); onDismiss() }
                    }
                    if (showReminderOptions) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            ReminderTimes.options().forEach { option ->
                                ActionChip(Icons.Default.Schedule, option.label, selected = false) {
                                    onSetReminder(option.atMillis)
                                    showReminderOptions = false
                                }
                            }
                            if (event.remindAt != null) {
                                ActionChip(Icons.Default.AlarmOff, "Cancel reminder", selected = false) {
                                    onSetReminder(null)
                                    showReminderOptions = false
                                }
                            }
                        }
                    }
                    Text(
                        when {
                            event.kept -> "Kept forever on this device"
                            event.archived -> "Archived locally on this device"
                            else -> "Stored locally on this device"
                        },
                        color = DetailMuted,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = if (event.archived) onUnarchive else onArchive,
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(if (event.archived) Icons.Default.Unarchive else Icons.Default.Archive, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (event.archived) "Restore" else "Archive", color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            if (OriginalAppLauncher.open(context, event.sourcePackage, event.sourceKey)) onDismiss()
                            else Toast.makeText(context, "${event.sourceName} can't be opened", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1.4f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Open ${event.sourceName.ifBlank { "app" }}",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private val UrlPattern = Regex("""(https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)

/** Makes web links in the captured body tappable. */
private fun linkified(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    UrlPattern.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        val url = match.value.trimEnd('.', ',', ')', ';', '!', '?')
        val target = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
        withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = MarksyTheme.PrimaryEmerald, textDecoration = TextDecoration.Underline)))) { append(url) }
        last = match.range.first + url.length
    }
    append(text.substring(last))
}

@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Color.Black else MarksyTheme.PrimaryEmerald
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised)
            .border(1.dp, if (selected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(end = 6.dp, top = 8.dp, bottom = 8.dp), thickness = 1.dp, color = MarksyTheme.BorderGlow)
}

@Composable
private fun DetailCell(label: String, value: String, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MarksyTheme.SurfaceRaised)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = DetailMuted, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
        Text(
            value,
            color = Color(0xFFE8F1EC),
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
