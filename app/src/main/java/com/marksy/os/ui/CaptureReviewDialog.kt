package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.marksy.os.MarksyFormat
import com.marksy.os.capture.Ambiguity
import com.marksy.os.capture.CandidateDeliveryPolicy
import com.marksy.os.capture.CaptureMessages
import com.marksy.os.capture.CaptureMethod
import com.marksy.os.capture.CaptureSource
import com.marksy.os.capture.CaptureSourceRegistry
import com.marksy.os.capture.CaptureState
import com.marksy.os.capture.OptionGate
import com.marksy.os.capture.ReviewDecision
import com.marksy.os.capture.TipFields
import com.marksy.os.capture.TipExtractor
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
    CaptureReviewDialog(loaded, remember(registry) { registry.allowListed() }, registry, onDismiss) { fields, source, decision, stockConfirmed ->
        scope.launch {
            MarksyContainer.captureGateway(context).review(loaded.id, fields, source, decision, stockConfirmed)
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
    onDecision: (TipFields, String?, ReviewDecision, Boolean) -> Unit
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
    // Off until the reviewer says so; editing fields never clears an option flag.
    var stock by remember(key) { mutableStateOf(false) }
    val flagged = Ambiguity.OPTION_CONTRACT in candidate.ambiguityCodes
    val reading = remember(key) { TipExtractor.captureOption(candidate.extractedText.orEmpty()) }
    val index = flagged && OptionGate.isOptionIndex(symbol)
    // Written option wording ("3500 CE") is never corrected to a stock tip; only a maybe-option ("18 PE") is (mockup 1d).
    val written = remember(key) { CandidateDeliveryPolicy.writtenOption(candidate) }
    val confirming = stock && !index && written == null

    val fields = TipFields(
        symbol.trim().uppercase().ifEmpty { null }, side, entry.toDoubleOrNull(), target.toDoubleOrNull(), stopLoss.toDoubleOrNull(),
        horizon.trim().ifEmpty { null }, read.visibleTimestamp
    )
    val sourcePackage = if (candidate.sourceVerified) candidate.sourcePackage else picked
    val source = registry.resolve(sourcePackage)
    val line = CandidateDeliveryPolicy.canonicalLine(fields)
    val reviewed = candidate.copy(
        state = CaptureState.ACCEPTED.name, userChoseSend = true, symbol = fields.symbol, side = side?.name, entry = fields.entry,
        target = fields.target, stopLoss = fields.stopLoss, sourcePackage = source?.packageName
    )
    val canSend = CandidateDeliveryPolicy.mayQueue(if (confirming) CandidateDeliveryPolicy.confirmedAsStock(reviewed) else reviewed, registry)
    val sourceName = source?.displayName ?: candidate.sourceName

    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review tip") },
        confirmButton = { MarksyButton("Send to Marksy", { onDecision(fields, sourcePackage, ReviewDecision.SEND, confirming) }, enabled = canSend) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Tight), verticalAlignment = Alignment.CenterVertically) {
                MarksyButton("Reject", { onDecision(fields, sourcePackage, ReviewDecision.REJECT, confirming) }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
                MarksyButton("Keep on phone", { onDecision(fields, sourcePackage, ReviewDecision.KEEP, confirming) }, style = MarksyButtonStyle.Outlined, color = MarksyTheme.TextPrimary)
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
                candidate.ambiguityCodes.filter { it != Ambiguity.OPTION_CONTRACT }.mapNotNull(CaptureMessages::ambiguity)
                    .forEach { Text(it, color = MarksyTheme.Warning, style = MarksyType.Small) }
                if (flagged) {
                    Text(optionReason(reading), color = MarksyTheme.Warning, style = MarksyType.Small)
                    if (!index && written == null) Pill("It's a stock tip, not an option call", selected = stock) { stock = !stock }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.Bottom) {
                    CompactTextField(
                        symbol, { symbol = it }, Modifier.weight(1f), label = "Symbol",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        fieldModifier = Modifier.testTag(ReviewSymbolTag)
                    )
                    // Owner decision 2026-10-18: Buy/Sell take the field's control height, the one exception to small pills.
                    Row(Modifier.heightIn(min = MarksySize.Control), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalAlignment = Alignment.CenterVertically) {
                        TipSide.entries.forEach { s ->
                            Pill(s.name.lowercase().replaceFirstChar { it.uppercase() }, selected = side == s, tall = true, modifier = Modifier.testTag(reviewSideTag(s))) { side = s }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                    LevelField(entry, { entry = it }, "Entry", Modifier.weight(1f))
                    LevelField(target, { target = it }, "Target", Modifier.weight(1f))
                    LevelField(stopLoss, { stopLoss = it }, "Stop loss", Modifier.weight(1f))
                }
                CompactTextField(horizon, { horizon = it }, Modifier.fillMaxWidth(), label = "Horizon", placeholder = "Intraday, swing, 3 weeks")

                if (!candidate.sourceVerified) {
                    Text("Which app is this from? Kept on this phone only.", color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                        sources.forEach { s -> Pill(s.displayName, selected = picked == s.packageName) { picked = s.packageName } }
                        Pill("Other", selected = picked == null) { picked = null }
                    }
                }

                MarksyCard {
                    Text(
                        if (canSend) "Send to Marksy sends only: ${line.orEmpty()}, from $sourceName. Nothing else leaves your phone."
                        else sendBlocked(
                            candidate.sourceVerified, line, source, flagged && !confirming, OptionGate.looksLikeContract(fields.symbol), fields.symbol.takeIf { index },
                            written?.let { it.evidence ?: "" }, candidate.extractedText.isNullOrBlank()
                        ),
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

private fun optionReason(reading: OptionGate.Reading): String = when {
    reading.evidence == null -> CaptureMessages.ambiguity(Ambiguity.OPTION_CONTRACT).orEmpty()
    reading.signal == OptionGate.Signal.AMBIGUOUS -> "“${reading.evidence}” may make this an option call, so it is treated as one."
    else -> "Read as an option call because of “${reading.evidence}”: its levels would be the option's price."
}

private fun sendBlocked(
    verified: Boolean, line: String?, source: CaptureSource?, option: Boolean, contract: Boolean, index: String?, written: String?, textMissing: Boolean
): String = when {
    textMissing -> "The screenshot's text is gone, so it stays on this phone. Capture it again to send it."
    index != null -> "$index is an index, so a tip read as an option stays on this phone."
    written != null -> "The screenshot names an option contract${if (written.isBlank()) "" else " ($written)"}, so it stays on this phone."
    option -> "Marksy can't track options yet, so this tip stays on this phone."
    contract -> "It still looks like an option contract, so it stays on this phone."
    !verified -> "Tips with a source you picked yourself stay on this phone."
    line == null -> "To send, add a symbol, buy or sell, and at least two of entry, target and stop loss."
    else -> "${source?.displayName ?: "This source"} tips can't be sent to Marksy, so this one stays on your phone."
}

/** Test tags for the review dialog's symbol field and side pills. */
internal const val ReviewSymbolTag = "review-symbol"
internal fun reviewSideTag(side: TipSide) = "review-side-${side.name}"
