package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Learn from my interactions", color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Opens, resolves, snoozes and ignored notifications adjust ranking by at most ±${PersonalLearning.MAX_LEARNED_ADJUSTMENT}. " +
                            "Your corrections always win. Nothing leaves this device.",
                        color = MarksyTheme.TextMuted, fontSize = 11.sp
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChanged)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onResetLearning) { Text("Reset learning") }
                TextButton(onClick = onClearCorrections) { Text("Clear my corrections") }
            }
        }
        item {
            // EPIC-019 privacy visibility: exactly which models exist and whether data can leave the device.
            Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("On-device AI", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (aiStatus.isEmpty()) {
                    Text("No AI model is installed. Marksy uses deterministic, explainable intelligence.", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
                } else aiStatus.forEach { (info, state) ->
                    Text("${info.id} v${info.version} · ${if (info.onDevice) "on-device" else "external"} · ${state.name.lowercase()}", color = MarksyTheme.TextSecondary, fontSize = 11.sp)
                }
                Text("External AI: off. Notification content never leaves this device.", color = MarksyTheme.TextMuted, fontSize = 10.sp)
            }
        }
        if (subjects.isEmpty()) {
            item { Text("Nothing learned yet.", color = MarksyTheme.TextMuted, fontSize = 12.sp) }
        }
        items(subjects, key = { "${it.subject.type}|${it.subject.key}" }) { p ->
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
                    .background(MarksyTheme.Surface, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${p.subject.label} · ${p.subject.type.name.lowercase()}", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    val sign = if (p.adjustment > 0) "+" else ""
                    Text("$sign${p.adjustment}", color = if (p.adjustment >= 0) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(p.reason, color = MarksyTheme.TextSecondary, fontSize = 11.sp)
                Text(
                    "${p.positive} engaged · ${p.negative} ignored/dismissed · ${p.neutral} snoozed · confidence ${(p.confidence * 100).toInt()}%",
                    color = MarksyTheme.TextMuted, fontSize = 10.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onPreference(p.subject, PersonalLearning.Preference.ALWAYS_IMPORTANT) }) { Text("Important", fontSize = 11.sp) }
                    TextButton(onClick = { onPreference(p.subject, PersonalLearning.Preference.LESS_IMPORTANT) }) { Text("Less", fontSize = 11.sp) }
                    if (p.override != null) TextButton(onClick = { onPreference(p.subject, null) }) { Text("Undo correction", fontSize = 11.sp) }
                }
            }
        }
    }
}
