package com.marksy.os.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DetailText = Color(0xFFE8F1EC)
private val DetailSecondary = Color(0xFF9AA9A1)
private val DetailPrimary = Color(0xFF72D49A)
private val DetailMuted = Color(0xFF657169)

/** Read-only V1 detail surface; no broker action is possible here. */
@Composable
fun TradingInsightDetailDialog(insight: TradingInsight, onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MarksyTheme.Surface,
        title = { Text(insight.headline, color = DetailText) },
        text = {
            Column(Modifier.verticalScroll(scrollState)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(insight.source, color = DetailPrimary, fontWeight = FontWeight.SemiBold)
                    Text(insight.status, color = DetailSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("Captured notification", color = DetailMuted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(insight.body.ifBlank { "No notification body was captured." }, color = DetailSecondary, fontSize = 13.sp)
                if (insight.marksySummary != null) {
                    Spacer(Modifier.height(14.dp))
                    Text("Marksy analysis", color = DetailPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(insight.marksySummary, color = DetailText, fontSize = 14.sp)
                }
                insight.marksyVerdict?.let { DetailRow("Verdict", it) }
                if (insight.marksyVerdictReasons.isNotEmpty()) {
                    DetailList("Verdict reasons", insight.marksyVerdictReasons)
                }
                insight.marksyRecommendation?.let { DetailRow("Recommendation", it) }
                insight.marksyProbability?.let { DetailRow("Probability", "${percent(it)}%") }
                insight.marksyOpportunityScore?.let { DetailRow("Opportunity score", score(it)) }
                insight.marksyTrustScore?.let { DetailRow("Trust score", score(it)) }
                insight.marksyTrustQuality?.let { DetailRow("Trust quality", it) }
                insight.marksyUncertaintyLevel?.let { DetailRow("Uncertainty", it) }
                if (insight.marksyEntryPrice != null || insight.marksyTargetPrice != null || insight.marksyStopLoss != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Levels", color = DetailPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    insight.marksyEntryPrice?.let { DetailRow("Entry", price(it)) }
                    insight.marksyTargetPrice?.let { DetailRow("Target", price(it)) }
                    insight.marksyStopLoss?.let { DetailRow("Stop loss", price(it)) }
                    insight.marksyUpsidePct?.let { DetailRow("Upside", "${trimmed(it)}%") }
                    insight.marksyHorizonDays?.let { DetailRow("Horizon", "$it days") }
                }
                insight.marksyLevelState?.let { DetailRow("Level state", it) }
                insight.marksyDecisionOutcome?.let { DetailRow("Decision outcome", it) }
                insight.marksySource?.let { DetailRow("Marksy source", it) }
                insight.marksyModelVersion?.let { DetailRow("Model", it) }
                insight.marksyAsOf?.let { DetailRow("As of", it) }
                if (insight.marksyEvidence.isNotEmpty()) DetailList("Evidence", insight.marksyEvidence)
                if (insight.marksyFailedCriteria.isNotEmpty()) DetailList("Failed criteria", insight.marksyFailedCriteria)
                insight.marksyTipId?.let { DetailRow("Tip ID", it) }
                insight.marksyAction?.let { action ->
                    Spacer(Modifier.height(10.dp))
                    DetailRow("Marksy action", action)
                }
                insight.marksyConfidence?.let { confidence ->
                    Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Marksy confidence", color = DetailMuted, fontSize = 11.sp)
                        Text("${confidencePercent(confidence)}%", color = DetailSecondary, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Classification", color = DetailMuted, fontSize = 11.sp)
                    Text(insight.eventType, color = DetailSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Classifier confidence", color = DetailMuted, fontSize = 11.sp)
                    Text("${confidencePercent(insight.confidence)}%", color = DetailSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("Live execution is disabled in V1. This screen cannot place, modify, or cancel brokerage orders.", color = DetailPrimary, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = MarksyTheme.PrimaryEmerald) } }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Text(label, color = DetailMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
    Text(value, color = DetailText, fontSize = 13.sp)
}

@Composable
private fun DetailList(label: String, values: List<String>) {
    Spacer(Modifier.height(8.dp))
    Text(label, color = DetailMuted, fontSize = 11.sp)
    values.take(12).forEach { value ->
        Text("• $value", color = DetailSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

private fun percent(value: Double): String = "${trimmed(value * 100.0)}"

private fun score(value: Double): String = trimmed(value)

private fun price(value: Double): String = "₹${trimmed(value)}"

private fun trimmed(value: Double): String =
    if (value.isFinite()) "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.') else "—"
