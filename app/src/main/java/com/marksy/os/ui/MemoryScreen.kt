package com.marksy.os.ui

import java.time.ZoneId
import java.time.Instant
import com.marksy.os.MarksyFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marksy.os.data.MemoryRepository
import com.marksy.os.data.local.MemoryEntryEntity
import com.marksy.os.intelligence.PersonalMemory
import kotlinx.coroutines.launch

/** EPIC-020: what Marksy remembers, where it came from, and controls to correct, forget or stop learning it. */
@Composable
fun MemoryScreen(repo: MemoryRepository, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val entries by repo.observe().collectAsState(initial = emptyList())
    var enabled by remember { mutableStateOf(repo.isEnabled()) }
    var kindState by remember { mutableStateOf(PersonalMemory.Kind.entries.associateWith { repo.isKindEnabled(it) }) }
    var renaming by remember { mutableStateOf<MemoryEntryEntity?>(null) }
    var confirmErase by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { repo.ingest() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = MarksySpace.Gutter),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Personal memory", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
                    Text(
                        "Built only from notifications on this device. Turning it off stops learning; use Erase to delete what was learned.",
                        color = MarksyTheme.TextMuted, style = MarksyType.Meta
                    )
                }
                Switch(checked = enabled, onCheckedChange = { v -> enabled = v; repo.setEnabled(v) })
            }
            MarksyButton("Erase learned memory", onClick = { confirmErase = true }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
        }
        PersonalMemory.Kind.entries.forEach { kind ->
            val ofKind = entries.filter { it.kind == kind.name }
            item(key = "k-${kind.name}") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(kind.label, ofKind.size, modifier = Modifier.weight(1f))
                    if (kind != PersonalMemory.Kind.PREFERENCE) Switch(
                        checked = kindState.getValue(kind),
                        enabled = enabled,
                        onCheckedChange = { v -> kindState = kindState + (kind to v); repo.setKindEnabled(kind, v) }
                    )
                }
                if (kind == PersonalMemory.Kind.LOCATION) {
                    Text("Learned only from places named in your notifications (deliveries, rides, calendar). Marksy never reads your device location, and street numbers are dropped.", color = MarksyTheme.TextMuted, style = MarksyType.Caption)
                }
            }
            items(ofKind, key = { "m-${it.id}" }) { e ->
                MemoryRow(e, onForget = { scope.launch { repo.forget(e.id) } }, onRename = { renaming = e })
            }
        }
    }

    if (confirmErase) {
        MarksyDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase learned memory?") },
            text = { Text("Everything Marksy learned is deleted and cannot be rebuilt from notifications that have already expired. Entries you corrected or set stay.") },
            confirmButton = { MarksyButton("Erase", onClick = { scope.launch { repo.eraseLearned() }; confirmErase = false }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative) },
            dismissButton = { MarksyButton("Cancel", onClick = { confirmErase = false }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) }
        )
    }

    renaming?.let { e ->
        var text by remember(e.id) { mutableStateOf(e.label) }
        MarksyDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Correct memory") },
            text = { CompactTextField(value = text, onValueChange = { text = it.take(60) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                MarksyButton("Save", enabled = text.isNotBlank(), onClick = { scope.launch { repo.correct(e.id, text) }; renaming = null }, style = MarksyButtonStyle.Text)
            },
            dismissButton = { MarksyButton("Cancel", onClick = { renaming = null }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) }
        )
    }
}

@Composable
private fun MemoryRow(e: MemoryEntryEntity, onForget: () -> Unit, onRename: () -> Unit) {
    val cadence = runCatching { org.json.JSONObject(e.detailJson).optInt("cadenceDays", 0) }.getOrDefault(0)
    Column(Modifier.fillMaxWidth().marksyCard().padding(MarksySpace.CardPadding)) {
        Text(e.label, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.Medium)
        Text(
            buildString {
                append("${(e.confidence * 100).toInt()}% · seen ${e.observations}x · last ${day(e.lastObservedAt)}")
                if (cadence > 0) append(" · every ~$cadence days")
                if (e.origin == PersonalMemory.ORIGIN_USER) append(" · set by you")
                e.expiresAt?.let { append(" · forgets after ${day(it)}") }
                append(" · from ${PersonalMemory.eventIds(e).size} notifications")
                PersonalMemory.sources(e).takeIf { it.isNotEmpty() }?.let { append(" via ${it.joinToString()}") }
            },
            color = MarksyTheme.TextMuted, style = MarksyType.Caption
        )
        Row {
            MarksyButton("Correct", onClick = onRename, style = MarksyButtonStyle.Text)
            MarksyButton("Forget", onClick = onForget, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
        }
    }
}

private fun day(ms: Long): String = MarksyFormat.day(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate())
