package com.marksy.os.ui

import androidx.compose.foundation.lazy.items
import com.marksy.os.MarksyFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.marksy.os.data.MarksyContainer
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marksy.os.intelligence.PersonalLearning
import com.marksy.os.ai.ModelInfo
import com.marksy.os.ai.ModelState

/** EPIC-012: everything Marksy learned, why, and the controls to correct, disable or reset it. */
@Composable
fun LearningScreen(
    profile: PersonalLearning.Profile,
    enabled: Boolean,
    padding: PaddingValues,
    onEnabledChanged: (Boolean) -> Unit,
    onPreference: (PersonalLearning.Subject, PersonalLearning.Preference?) -> Unit,
    onResetLearning: () -> Unit,
    onClearCorrections: () -> Unit,
    aiStatus: List<Pair<ModelInfo, ModelState>> = emptyList()
) {
    val subjects = profile.subjects.values
        .sortedWith(compareByDescending<PersonalLearning.SubjectProfile> { it.override != null }
            .thenByDescending { kotlin.math.abs(it.adjustment) }
            .thenByDescending { it.lastObservedAt })

    MarksyList(Modifier.background(MarksyTheme.Background).padding(bottom = padding.calculateBottomPadding())) {
        item {
            MarksyCard {
            MarksyCardHeader("Learn from my interactions", trailing = { Switch(checked = enabled, onCheckedChange = onEnabledChanged) })
            Text(
                "Opens, resolves, snoozes and ignored notifications adjust ranking by at most ±${PersonalLearning.MAX_LEARNED_ADJUSTMENT}. " +
                    "Your corrections always win. Nothing leaves this device.",
                color = MarksyTheme.TextMuted, style = MarksyType.Meta
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                MarksyButton("Reset learning", onClick = onResetLearning, style = MarksyButtonStyle.Text)
                MarksyButton("Clear my corrections", onClick = onClearCorrections, style = MarksyButtonStyle.Text)
            }
            }
        }
        item {
            // EPIC-019 privacy visibility: exactly which models exist and whether data can leave the device.
            AiStatusCard(aiStatus)
        }
        if (subjects.isEmpty()) {
            item { InlineEmpty("Nothing learned yet") }
        }
        items(subjects, key = { "${it.subject.type}|${it.subject.key}" }) { p ->
            MarksyCard {
                val sign = if (p.adjustment > 0) "+" else ""
                MarksyCardHeader("${p.subject.label} · ${p.subject.type.name.lowercase()}", trailing = {
                    Text("$sign${p.adjustment}", color = if (p.adjustment >= 0) MarksyTheme.Positive else MarksyTheme.Negative, style = MarksyType.Body, fontWeight = FontWeight.Bold)
                })
                Text(p.reason, color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
                Text(
                    "${p.positive} engaged · ${p.negative} ignored/dismissed · ${p.neutral} snoozed · confidence ${MarksyFormat.percent(p.confidence * 100.0, 0, signed = false)}",
                    color = MarksyTheme.TextMuted, style = MarksyType.Caption
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
                    MarksyButton("Important", onClick = { onPreference(p.subject, PersonalLearning.Preference.ALWAYS_IMPORTANT) }, style = MarksyButtonStyle.Text)
                    MarksyButton("Less", onClick = { onPreference(p.subject, PersonalLearning.Preference.LESS_IMPORTANT) }, style = MarksyButtonStyle.Text)
                    if (p.override != null) MarksyButton("Undo correction", onClick = { onPreference(p.subject, null) }, style = MarksyButtonStyle.Text)
                }
            }
        }
    }
}

/** EPIC-019 privacy visibility: which models exist, whether they run locally, and live runtime health. */
@Composable
private fun AiStatusCard(initial: List<Pair<ModelInfo, ModelState>>) {
    val context = LocalContext.current
    val service = remember(context) { MarksyContainer.intelligence(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(initial) }
    var diagnostics by remember { mutableStateOf(service.diagnostics().toMap()) }
    var busy by remember { mutableStateOf(false) }
    var importNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(service) {
        status = service.refresh()
        diagnostics = service.diagnostics().toMap()
    }
    // Gemma isn't managed by the OS: the user picks the Kaggle download (.tar.gz or .task) and Marksy copies the model in.
    val importGemma = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = com.marksy.os.ai.GemmaModelFile.import(context, uri) { bytes -> importNote = "Importing… ${bytes / (1024 * 1024)} MB" }
            importNote = result.fold({ "Model imported. Loading…" }, { it.message ?: "Import failed" })
            if (result.isSuccess) {
                service.prepare(com.marksy.os.ai.MediaPipeGemmaBackend.ID)
                importNote = null
            }
            status = service.refresh()
            diagnostics = service.diagnostics().toMap()
            busy = false
        }
    }
    MarksyCard {
        MarksyCardHeader("On-device AI")
        if (status.isEmpty()) {
            Text("No AI model is installed. Marksy uses deterministic, explainable intelligence.", color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
        }
        status.forEach { (info, state) ->
            Text("${info.id} · ${if (info.onDevice) "on-device" else "external"} · ${aiStateLabel(state)}", color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
            diagnostics[info]?.let { d ->
                val parts = listOfNotNull(
                    d.modelVersion?.let { "model $it" }, d.runtimeVersion,
                    d.initMs?.let { "warm-up $it ms" }, d.lastLatencyMs?.let { "last call $it ms" },
                    "${d.calls} call${if (d.calls == 1) "" else "s"}, ${d.failures} failed", d.lastError?.let { "last error $it" }
                )
                Text(parts.joinToString(" · "), color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }
            val isGemma = info.id == com.marksy.os.ai.MediaPipeGemmaBackend.ID
            if (isGemma && (state == ModelState.NOT_INSTALLED || state == ModelState.FAILED)) {
                MarksyButton("Import Gemma model file (Kaggle .tar.gz or .task)", enabled = !busy, onClick = { importGemma.launch(arrayOf("*/*")) }, style = MarksyButtonStyle.Text)
                importNote?.let { Text(it, color = MarksyTheme.TextMuted, style = MarksyType.Caption) }
            } else if (state == ModelState.NOT_INSTALLED || state == ModelState.READY || state == ModelState.FAILED) {
                MarksyButton(if (state == ModelState.NOT_INSTALLED) "Download model (via Android AICore)" else if (state == ModelState.FAILED) "Retry" else "Warm up", enabled = !busy, style = MarksyButtonStyle.Text, onClick = {
                    busy = true
                    scope.launch {
                        service.prepare(info.id)
                        status = service.refresh()
                        diagnostics = service.diagnostics().toMap()
                        busy = false
                    }
                })
            }
        }
        Text("AI only interprets your question; answers always come from your Marksy data. External AI: off. Notification content never leaves this device.", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
    }
}

internal fun aiStateLabel(state: ModelState) = when (state) {
    ModelState.UNKNOWN -> "checking"
    ModelState.NOT_AVAILABLE -> "not supported on this device"
    ModelState.NOT_INSTALLED -> "available to download"
    ModelState.DOWNLOADING -> "downloading"
    ModelState.READY -> "ready"
    ModelState.FAILED -> "error"
    ModelState.DISABLED -> "disabled"
}
