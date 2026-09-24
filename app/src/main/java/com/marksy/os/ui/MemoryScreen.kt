package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.MemoryRepository
import com.marksy.os.data.local.MemoryEntryEntity
import com.marksy.os.intelligence.PersonalMemory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** EPIC-020: what Marksy remembers, where it came from, and controls to correct, forget or stop learning it. */
@Composable
fun MemoryScreen(repo: MemoryRepository, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val entries by repo.observe().collectAsState(initial = emptyList())
    var enabled by remember { mutableStateOf(repo.isEnabled()) }
    var kindState by remember { mutableStateOf(PersonalMemory.Kind.entries.associateWith { repo.isKindEnabled(it) }) }
    var renaming by remember { mutableStateOf<MemoryEntryEntity?>(null) }
    LaunchedEffect(Unit) { runCatching { repo.ingest() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MarksyTheme.Background).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Personal memory", color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Built only from notifications on this device. Turning it off erases everything learned; your own corrections stay.",
                        color = MarksyTheme.TextMuted, fontSize = 11.sp
                    )
                }
                Switch(checked = enabled, onCheckedChange = { v -> enabled = v; scope.launch { repo.setEnabled(v) } })
            }
        }
        PersonalMemory.Kind.entries.forEach { kind ->
            val ofKind = entries.filter { it.kind == kind.name }
            item(key = "k-${kind.name}") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${kind.label} · ${ofKind.size}", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (kind != PersonalMemory.Kind.PREFERENCE) Switch(
                        checked = kindState.getValue(kind),
                        enabled = enabled,
                        onCheckedChange = { v -> kindState = kindState + (kind to v); scope.launch { repo.setKindEnabled(kind, v) } }
                    )
                }
                if (kind == PersonalMemory.Kind.LOCATION) {
                    Text("Marksy captures no location data, so nothing is learned here.", color = MarksyTheme.TextMuted, fontSize = 10.sp)
                }
            }
            items(ofKind, key = { "m-${it.id}" }) { e ->
                MemoryRow(e, onForget = { scope.launch { repo.forget(e.id) } }, onRename = { renaming = e })
            }
        }
    }

    renaming?.let { e ->
        var text by remember(e.id) { mutableStateOf(e.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Correct memory") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { scope.launch { repo.correct(e.id, text) }; renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MemoryRow(e: MemoryEntryEntity, onForget: () -> Unit, onRename: () -> Unit) {
    val date = SimpleDateFormat("dd MMM", Locale.getDefault())
    val cadence = runCatching { org.json.JSONObject(e.detailJson).optInt("cadenceDays", 0) }.getOrDefault(0)
    Column(
        Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp))
            .background(MarksyTheme.Surface, RoundedCornerShape(12.dp)).padding(10.dp)
    ) {
        Text(e.label, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            buildString {
                append("${(e.confidence * 100).toInt()}% · seen ${e.observations}x · last ${date.format(Date(e.lastObservedAt))}")
                if (cadence > 0) append(" · every ~$cadence days")
                if (e.origin == PersonalMemory.ORIGIN_USER) append(" · set by you")
                e.expiresAt?.let { append(" · forgets after ${date.format(Date(it))}") }
                append(" · from ${PersonalMemory.eventIds(e).size} notifications")
            },
            color = MarksyTheme.TextMuted, fontSize = 10.sp
        )
        Row {
            TextButton(onClick = onRename) { Text("Correct", fontSize = 11.sp) }
            TextButton(onClick = onForget) { Text("Forget", color = MarksyTheme.RedUrgent, fontSize = 11.sp) }
        }
    }
}
