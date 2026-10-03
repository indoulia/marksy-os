package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.marksy.os.MarksyFormat
import com.marksy.os.capture.CandidateDeliveryPolicy
import com.marksy.os.capture.CaptureMessages
import com.marksy.os.capture.CaptureMethod
import com.marksy.os.capture.CaptureSource
import com.marksy.os.capture.CaptureSourceRegistry
import com.marksy.os.capture.CaptureState
import com.marksy.os.capture.ReviewDecision
import com.marksy.os.capture.TipFields
import com.marksy.os.capture.TipSide
import com.marksy.os.capture.ambiguityCodes
import com.marksy.os.capture.fields
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.local.TipCandidateEntity
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** Loads a candidate and shows its review; the user's choice goes through [com.marksy.os.capture.CaptureGateway.review]. */
@Composable
fun CaptureReviewHost(candidateId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val notice = rememberNotice()
    val scope = rememberCoroutineScope()
    val registry = remember { MarksyContainer.captureSources(context) }
    val candidate by produceState<TipCandidateEntity?>(null, candidateId) { value = MarksyContainer.database(context).captureDao().candidate(candidateId) }
    val loaded = candidate
    val reviewable = loaded != null && loaded.state in setOf(CaptureState.EXTRACTED.name, CaptureState.REVIEW_REQUIRED.name)
    // Already decided: say so once instead of opening an empty review.
    LaunchedEffect(loaded) { if (loaded != null && !reviewable) { notice(CaptureMessages.ALREADY_REVIEWED); onDismiss() } }
    if (loaded == null || !reviewable) return
    CaptureReviewDialog(loaded, remember(registry) { registry.allowListed() }, registry, onDismiss) { fields, source, decision ->
        scope.launch {
            MarksyContainer.captureGateway(context).review(loaded.id, fields, source, decision)
            onDismiss()
        }
    }
}

/** What the user sees before anything leaves the phone: the reading, the source, and exactly what Send would transmit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureReviewDialog(
    candidate: TipCandidateEntity,
    sources: List<CaptureSource>,
    registry: CaptureSourceRegistry,
    onDismiss: () -> Unit,
    onDecision: (TipFields, String?, ReviewDecision) -> Unit
) {
    val key = candidate.id
    val read = candidate.fields
    var symbol by remember(key) { mutableStateOf(read.symbol.orEmpty()) }
    var side by remember(key) { mutableStateOf(read.side) }
    var entry by remember(key) { mutableStateOf(level(read.entry)) }
    var target by remember(key) { mutableStateOf(level(read.target)) }
    var stopLoss by remember(key) { mutableStateOf(level(read.stopLoss)) }
    var horizon by remember(key) { mutableStateOf(read.horizon.orEmpty()) }
    var picked by remember(key) { mutableStateOf(candidate.sourcePackage) }
    var textOpen by remember(key) { mutableStateOf(false) }

    val fields = TipFields(
        symbol.trim().uppercase().ifEmpty { null }, side, entry.toDoubleOrNull(), target.toDoubleOrNull(), stopLoss.toDoubleOrNull(),
        horizon.trim().ifEmpty { null }, read.visibleTimestamp
    )
    val sourcePackage = if (candidate.sourceVerified) candidate.sourcePackage else picked
    val source = registry.resolve(sourcePackage)
    val line = CandidateDeliveryPolicy.canonicalLine(fields)
    val canSend = CandidateDeliveryPolicy.mayQueue(
        candidate.copy(
            state = CaptureState.ACCEPTED.name, userChoseSend = true, symbol = fields.symbol, side = side?.name, entry = fields.entry,
            target = fields.target, stopLoss = fields.stopLoss, sourcePackage = source?.packageName
        ),
        registry
    )
    val sourceName = source?.displayName ?: candidate.sourceName

    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review tip") },
        confirmButton = { MarksyButton("Send to Marksy", { onDecision(fields, sourcePackage, ReviewDecision.SEND) }, enabled = canSend) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Tight), verticalAlignment = Alignment.CenterVertically) {
                MarksyButton("Reject", { onDecision(fields, sourcePackage, ReviewDecision.REJECT) }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
                MarksyButton("Keep on phone", { onDecision(fields, sourcePackage, ReviewDecision.KEEP) }, style = MarksyButtonStyle.Outlined, color = MarksyTheme.TextPrimary)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner), itemVerticalAlignment = Alignment.CenterVertically) {
                    MarksyBadge(CaptureMessages.method(CaptureMethod.valueOf(candidate.method)), MarksyTheme.TextSecondary, MarksyTheme.SurfaceRaised)
                    if (candidate.sourceVerified) MarksyBadge("Source verified", MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg)
                    else MarksyBadge("Source chosen by you", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
                    MarksyBadge("Read ${MarksyFormat.percent(candidate.confidence * 100.0, 0, signed = false)}", MarksyTheme.TextSecondary, MarksyTheme.SurfaceRaised)
                }
                Text(
                    "${sourceName ?: "Unknown app"} · ${MarksyFormat.dayTime(Instant.ofEpochMilli(candidate.capturedAt).atZone(ZoneId.systemDefault()))}",
                    color = MarksyTheme.TextMuted, style = MarksyType.Meta
                )
                candidate.ambiguityCodes.mapNotNull(CaptureMessages::ambiguity).forEach { Text(it, color = MarksyTheme.Warning, style = MarksyType.Small) }

                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.Bottom) {
                    CompactTextField(
                        symbol, { symbol = it }, Modifier.weight(1f), label = "Symbol",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                    )
                    TipSide.entries.forEach { s -> Pill(s.name.lowercase().replaceFirstChar { it.uppercase() }, selected = side == s) { side = s } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                    LevelField(entry, { entry = it }, "Entry", Modifier.weight(1f))
                    LevelField(target, { target = it }, "Target", Modifier.weight(1f))
                    LevelField(stopLoss, { stopLoss = it }, "Stop loss", Modifier.weight(1f))
                }
                CompactTextField(horizon, { horizon = it }, Modifier.fillMaxWidth(), label = "Horizon", placeholder = "Intraday, swing, 3 weeks")

                if (!candidate.sourceVerified) {
                    Text("Which app is this from?", color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                        sources.forEach { s -> Pill(s.displayName, selected = picked == s.packageName) { picked = s.packageName } }
                        Pill("Other", selected = picked == null) { picked = null }
                    }
                }

                MarksyCard {
                    Text(
                        if (canSend) "Send to Marksy sends only: ${line.orEmpty()}, from $sourceName. Nothing else leaves your phone."
                        else sendBlocked(line, source),
                        color = MarksyTheme.TextPrimary, style = MarksyType.Small
                    )
                    Text("Keep on phone sends nothing.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                }

                if (!candidate.extractedText.isNullOrBlank()) {
                    Row(Modifier.marksyRow(onClick = { textOpen = !textOpen }), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recognized text", color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon).rotate(if (textOpen) 180f else 0f))
                    }
                    if (textOpen) Text(candidate.extractedText, color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                }
            }
        }
    )
}

@Composable
private fun LevelField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) =
    CompactTextField(value, onChange, modifier, label = label, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

private fun level(v: Double?): String = v?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

private fun sendBlocked(line: String?, source: CaptureSource?): String = when {
    line == null -> "To send, add a symbol, buy or sell, and at least two of entry, target and stop loss."
    source == null -> "Choose the app this came from to send it. Until then it stays on your phone."
    else -> "${source.displayName} tips can't be sent to Marksy, so this one stays on your phone."
}
