package com.marksy.os.ui

import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.RuleRunner
import com.marksy.os.intelligence.RuleConditionTree
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var testing by remember { mutableStateOf<RuleEngine.Rule?>(null) }
    val runner = remember(context) { MarksyContainer.rules(context.applicationContext) }

    // Reload after saving so versions bumped by the store are reflected (and used by simulation).
    fun persist() = scope.launch {
        store.save(rules.toList())
        val saved = store.load()
        rules.clear()
        rules.addAll(saved)
    }

    MarksyList(Modifier.background(MarksyTheme.Background).padding(padding)) {
        item {
            Column {
                Text("Create custom rules to filter, group and route notifications. Let Marksy work for you.", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
            }
        }

        // Custom Rule Builder Card
        item {
            MarksyCard(border = MarksyTheme.PrimaryEmerald) {
                Text("Custom Rule Builder", color = MarksyTheme.TextPrimary, style = MarksyType.Lead, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("IF", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.Bold, style = MarksyType.Small, modifier = Modifier.width(40.dp))
                    Text("Notification contains BUY", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("THEN", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.Bold, style = MarksyType.Small, modifier = Modifier.width(40.dp))
                    Text("Send to Trading Dashboard", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
                }
                MarksyButton("+ Add Custom Rule", onClick = { editingRule = null; showEditor = true }, modifier = Modifier.fillMaxWidth())
            }
        }

        item { SectionLabel("Active Automations") }

        items(rules, key = { it.id }) { rule ->
            RuleToggleCard(
                rule = rule,
                onEnabledChanged = { enabled ->
                    val index = rules.indexOfFirst { it.id == rule.id }
                    if (index >= 0) { rules[index] = rule.copy(enabled = enabled); persist() }
                },
                onEdit = { editingRule = rule; showEditor = true },
                onDelete = { deleteRule = rule },
                onTest = { testing = rule }
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

    testing?.let { rule -> RuleTestDialog(rule, runner, onDismiss = { testing = null }) }

    deleteRule?.let { rule ->
        MarksyDialog(
            onDismissRequest = { deleteRule = null },
            title = { Text("Delete rule?", color = MarksyTheme.TextPrimary) },
            text = { Text("\"${rule.name}\" will stop applying to newly captured events.", color = MarksyTheme.TextSecondary) },
            confirmButton = {
                MarksyButton("Delete", onClick = {
                    rules.removeAll { it.id == rule.id }
                    persist()
                    deleteRule = null
                }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
            },
            dismissButton = { MarksyButton("Cancel", onClick = { deleteRule = null }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) }
        )
    }
}

@Composable
private fun RuleToggleCard(
    rule: RuleEngine.Rule,
    onEnabledChanged: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    MarksyCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(rule.name, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold)
                Text(ruleDescription(rule), color = MarksyTheme.TextSecondary, style = MarksyType.Meta, modifier = Modifier.padding(top = MarksySpace.Hair))
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onEnabledChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MarksyTheme.OnAccent,
                    checkedTrackColor = MarksyTheme.PrimaryEmerald,
                    uncheckedThumbColor = MarksyTheme.TextMuted,
                    uncheckedTrackColor = MarksyTheme.SurfaceRaised
                )
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MarksyButton("Test on history", onClick = onTest, style = MarksyButtonStyle.Text)
            MarksyButton("Edit", onClick = onEdit, style = MarksyButtonStyle.Text)
            MarksyButton("Delete", onClick = onDelete, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
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
    var tree by remember(initial) { mutableStateOf(RuleConditionTree.root(initial?.condition)) }
    var priority by remember(initial) { mutableStateOf((initial?.priority ?: 0).toString()) }
    val treeValid = remember(tree) { RuleConditionTree.validate(tree).isEmpty() }
    val editedCondition = RuleConditionTree.toSaved(tree)

    val cleanName = name.trim().take(60)
    val cleanCategory = category.trim().take(120).ifBlank { null }
    val cleanSource = source.trim().take(120).ifBlank { null }
    val cleanText = containsText.trim().take(120).ifBlank { null }
    val valid = cleanName.isNotBlank() && treeValid && (cleanCategory != null || cleanSource != null || cleanText != null || editedCondition != null)

    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Custom Rule" else "Edit Rule", color = MarksyTheme.TextPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)
            ) {
                CompactTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = "Rule name",
                    modifier = Modifier.fillMaxWidth()
                )
                CompactTextField(
                    value = containsText,
                    onValueChange = { containsText = it.take(120) },
                    label = "Contains text",
                    placeholder = "e.g. BUY, OTP",
                    modifier = Modifier.fillMaxWidth()
                )
                CompactTextField(value = category, onValueChange = { category = it.take(20) }, label = "Category (e.g. PAYMENTS)", modifier = Modifier.fillMaxWidth())
                CompactTextField(value = source, onValueChange = { source = it.take(120) }, label = "App package (optional)", modifier = Modifier.fillMaxWidth())
                ConditionTreeEditor(tree, onChange = { tree = it })
                CompactTextField(value = priority, onValueChange = { priority = it.take(4) }, label = "Rule priority (higher wins conflicts)", modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    RuleEngine.Action.entries.forEach { a ->
                        Pill(a.name.lowercase().replace('_', ' '), selected = action == a) { action = a }
                    }
                }
            }
        },
        confirmButton = {
            MarksyButton(
                "Save",
                style = MarksyButtonStyle.Text,
                enabled = valid,
                onClick = {
                    onSave(
                        (initial ?: RuleEngine.Rule(id = "rule-${System.currentTimeMillis()}", name = cleanName)).copy(
                            name = cleanName,
                            sourcePackage = cleanSource,
                            category = cleanCategory?.uppercase(),
                            containsText = cleanText,
                            action = action,
                            condition = editedCondition,
                            priority = priority.trim().toIntOrNull()?.coerceIn(-100, 100) ?: 0
                        )
                    )
                }
            )
        },
        dismissButton = { MarksyButton("Cancel", onClick = onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) }
    )
}

@Composable
private fun RuleTestDialog(rule: RuleEngine.Rule, runner: RuleRunner, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var applied by remember { mutableStateOf<Int?>(null) }
    val result by produceState<Pair<Int, List<RuleEngine.SimulationHit>>?>(null, refresh) { value = runCatching { runner.simulate(rule) }.getOrNull() }
    val executions by produceState(0, refresh) { value = runCatching { runner.executionCount(rule.id) }.getOrDefault(0) }
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Test \"${rule.name}\" (v${rule.version})", color = MarksyTheme.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                val r = result
                if (r == null) Text("Checking the last 7 days…", color = MarksyTheme.TextMuted)
                else {
                    Text("Would match ${r.second.size} of ${r.first} notifications from the last 7 days.", color = MarksyTheme.TextSecondary)
                    r.second.take(6).forEach { hit ->
                        val effect = hit.stateAction?.name?.lowercase()?.replace('_', ' ') ?: "priority ${hit.priorityBefore} → ${hit.priorityAfter}"
                        Text("• ${hit.title.take(60)} — $effect", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
                    }
                }
                Text("Audit: $executions recorded execution${if (executions == 1) "" else "s"}", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
                applied?.let { Text("Applied to $it past notification${if (it == 1) "" else "s"}.", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Small) }
            }
        },
        confirmButton = {
            MarksyButton(
                "Apply to these",
                style = MarksyButtonStyle.Text,
                enabled = (result?.second?.isNotEmpty() == true) && rule.enabled,
                onClick = { scope.launch { applied = runCatching { runner.applyToHistory(rule) }.getOrNull(); refresh++ } }
            )
        },
        dismissButton = { MarksyButton("Close", onClick = onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) }
    )
}

private fun ruleDescription(rule: RuleEngine.Rule): String = buildList {
    rule.category?.let { add("Category: $it") }
    rule.sourcePackage?.let { add("Source: $it") }
    rule.containsText?.let { add("Contains: $it") }
    rule.condition?.let { add(if (RuleConditionTree.isUnreadable(it)) "Condition unreadable: edit to fix" else "When ${RuleConditionTree.describe(it).take(160)}") }
    add(rule.action.name.lowercase().replace('_', ' '))
    if (rule.priority != 0) add("priority ${rule.priority}")
    add("v${rule.version}")
}.ifEmpty { listOf("Applies to all matching events") }.joinToString(" • ")
