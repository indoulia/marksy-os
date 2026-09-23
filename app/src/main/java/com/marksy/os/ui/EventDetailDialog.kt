package com.marksy.os.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.marksy.os.notification.OriginalAppLauncher
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
import com.marksy.os.data.local.NotificationEventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DetailSecondary = Color(0xFF9AA9A1)
private val DetailMuted = Color(0xFF657169)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventDetailDialog(
    event: NotificationEventEntity,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val actions = OriginalAppLauncher.actionsFor(event.sourcePackage, event.sourceKey)
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
                Text(linkified(event.body.ifBlank { "No notification body was captured." }), color = DetailSecondary, fontSize = 13.sp)
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEach { action ->
                            OutlinedButton(onClick = {
                                if (!OriginalAppLauncher.send(context, action.intent)) {
                                    Toast.makeText(context, "\"${action.title}\" is no longer available", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(action.title, color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp) }
                        }
                    }
                }
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
                Spacer(Modifier.height(8.dp))
                Text(if (event.archived) "Archived locally on this device" else "Stored locally on this device", color = DetailMuted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = {
                    if (OriginalAppLauncher.open(context, event.sourcePackage, event.sourceKey)) onDismiss()
                    else Toast.makeText(context, "${event.sourceName} can't be opened", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Open in ${event.sourceName}", color = MarksyTheme.PrimaryEmerald)
                }
                TextButton(onClick = if (event.archived) onUnarchive else onArchive) {
                    Text(if (event.archived) "Restore to Inbox" else "Archive", color = MarksyTheme.PrimaryEmerald)
                }
                TextButton(onClick = onDismiss) { Text("Close", color = MarksyTheme.PrimaryEmerald) }
            }
        }
    )
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
private fun DetailRow(label: String, value: String) {
    Text(label, color = DetailMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
    Text(value, color = Color(0xFFE8F1EC), fontSize = 13.sp)
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
