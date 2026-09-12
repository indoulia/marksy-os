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
import com.marksy.os.data.local.NotificationEventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DetailSecondary = Color(0xFF9AA9A1)
private val DetailMuted = Color(0xFF657169)

@Composable
fun EventDetailDialog(event: NotificationEventEntity, onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title.ifBlank { "Notification event" }) },
        text = {
            Column(Modifier.verticalScroll(scrollState)) {
                Text(event.sourceName, fontWeight = FontWeight.SemiBold)
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
                Spacer(Modifier.height(8.dp))
                Text("Stored locally on this device", color = DetailMuted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Text(label, color = DetailMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
    Text(value, color = Color(0xFFE8F1EC), fontSize = 13.sp)
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
