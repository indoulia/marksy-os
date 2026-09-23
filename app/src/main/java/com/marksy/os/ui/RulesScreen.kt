package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.intelligence.RuleEngine
import com.marksy.os.intelligence.RuleStore
import kotlinx.coroutines.launch

@Composable
fun RulesScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val store = remember(context) { RuleStore(context.applicationContext) }
    val rules = remember(store) { mutableStateListOf<RuleEngine.Rule>().apply { addAll(store.load()) } }
    val scope = rememberCoroutineScope()
    var showEditor by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<RuleEngine.Rule?>(null) }
    var deleteRule by remember { mutableStateOf<RuleEngine.Rule?>(null) }

    fun persist() = scope.launch { store.save(rules.toList()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("Create custom rules to filter, group and route notifications. Let Marksy work for you.", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
            }
        }

        // Custom Rule Builder Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(16.dp))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Custom Rule Builder", color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("IF", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MarksyTheme.SurfaceRaised)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Notification contains BUY", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("THEN", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MarksyTheme.SurfaceRaised)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Send to Trading Dashboard", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Button(
                        onClick = { editingRule = null; showEditor = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text("+ Add Custom Rule", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text("Active Automations", color = MarksyTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        items(rules, key = { it.id }) { rule ->
            RuleToggleCard(
                rule = rule,
                onEnabledChanged = { enabled ->
                    val index = rules.indexOfFirst { it.id == rule.id }
                    if (index >= 0) { rules[index] = rule.copy(enabled = enabled); persist() }
                },
                onEdit = { editingRule = rule; showEditor = true },
                onDelete = { deleteRule = rule }
            )
        }
    }

    if (showEditor) {
        RuleEditorDialog(
            initial = editingRule,
            onDismiss = { showEditor = false },
            onSave = { saved ->
                val index = rules.indexOfFirst { it.id == saved.id }
                if (index >= 0) rules[index] = saved else rules.add(saved)
                persist()
                showEditor = false
            }
        )
    }

    deleteRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteRule = null },
            title = { Text("Delete rule?", color = MarksyTheme.TextPrimary) },
            text = { Text("\"${rule.name}\" will stop applying to newly captured events.", color = MarksyTheme.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    rules.removeAll { it.id == rule.id }
                    persist()
                    deleteRule = null
                }) { Text("Delete", color = MarksyTheme.RedUrgent) }
            },
            dismissButton = { TextButton(onClick = { deleteRule = null }) { Text("Cancel", color = MarksyTheme.TextSecondary) } },
            containerColor = MarksyTheme.SurfaceRaised
        )
    }
}

@Composable
private fun RuleToggleCard(
    rule: RuleEngine.Rule,
    onEnabledChanged: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(ruleDescription(rule), color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onEnabledChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = MarksyTheme.PrimaryEmerald,
                        uncheckedThumbColor = MarksyTheme.TextMuted,
                        uncheckedTrackColor = MarksyTheme.SurfaceRaised
                    )
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp) }
                TextButton(onClick = onDelete) { Text("Delete", color = MarksyTheme.RedUrgent, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun RuleEditorDialog(
    initial: RuleEngine.Rule?,
    onDismiss: () -> Unit,
    onSave: (RuleEngine.Rule) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var category by remember(initial) { mutableStateOf(initial?.category.orEmpty()) }
    var source by remember(initial) { mutableStateOf(initial?.sourcePackage.orEmpty()) }
    var containsText by remember(initial) { mutableStateOf(initial?.containsText.orEmpty()) }
    var action by remember(initial) { mutableStateOf(initial?.action ?: RuleEngine.Action.HIGHLIGHT) }

    val cleanName = name.trim().take(60)
    val cleanCategory = category.trim().take(120).ifBlank { null }
    val cleanSource = source.trim().take(120).ifBlank { null }
    val cleanText = containsText.trim().take(120).ifBlank { null }
    val valid = cleanName.isNotBlank() && (cleanCategory != null || cleanSource != null || cleanText != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Custom Rule" else "Edit Rule", color = MarksyTheme.TextPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Rule name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = containsText,
                    onValueChange = { containsText = it.take(120) },
                    label = { Text("Contains text (e.g. BUY, OTP)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        RuleEngine.Rule(
                            id = initial?.id ?: "rule-${System.currentTimeMillis()}",
                            name = cleanName,
                            enabled = initial?.enabled ?: true,
                            sourcePackage = cleanSource,
                            category = cleanCategory?.uppercase(),
                            containsText = cleanText,
                            action = action
                        )
                    )
                }
            ) { Text("Save", color = MarksyTheme.PrimaryEmerald) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MarksyTheme.TextSecondary) } },
        containerColor = MarksyTheme.SurfaceRaised
    )
}

private fun ruleDescription(rule: RuleEngine.Rule): String = buildList {
    rule.category?.let { add("Category: $it") }
    rule.sourcePackage?.let { add("Source: $it") }
    rule.containsText?.let { add("Contains: $it") }
}.ifEmpty { listOf("Applies to all matching events") }.joinToString(" • ")
