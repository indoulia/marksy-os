package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DetailText = Color(0xFFE8F1EC)
private val DetailSecondary = Color(0xFF9AA9A1)
private val DetailPrimary = Color(0xFF72D49A)
private val DetailMuted = Color(0xFF657169)

/**
 * Read-only V1 detail surface. This presents captured event information and
 * delivery state without implying that a recommendation or broker action exists.
 */
@Composable
fun TradingInsightDetailDialog(
    insight: TradingInsight,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(insight.headline, color = DetailText) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(insight.source, color = DetailPrimary, fontWeight = FontWeight.SemiBold)
                    Text(insight.status, color = DetailSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("Captured notification", color = DetailMuted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(insight.body.ifBlank { "No notification body was captured." }, color = DetailSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Classification", color = DetailMuted, fontSize = 11.sp)
                    Text(insight.eventType, color = DetailSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Classifier confidence", color = DetailMuted, fontSize = 11.sp)
                    Text("${(insight.confidence * 100).toInt()}%", color = DetailSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Live execution is disabled in V1. This screen does not place, modify, or cancel brokerage orders.",
                    color = DetailPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
