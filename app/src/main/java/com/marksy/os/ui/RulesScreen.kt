package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.marksy.os.intelligence.RuleEngine
import com.marksy.os.intelligence.RuleStore
import kotlinx.coroutines.launch

private val RuleSurface = Color(0xFF101613)
private val RuleRaised = Color(0xFF151C18)
private val RulePrimary = Color(0xFF72D49A)
private val RuleText = Color(0xFFE8F1EC)
private val RuleSecondary = Color(0xFF9AA9A1)

private const val MAX_RULES = 25
private const val MAX_NAME = 60
private const val MAX_FILTER = 120

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
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Rules & Automation", color = RuleText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("Deterministic local rules shape attention before events reach the rest of Marksy OS.", color = RuleSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { editingRule = null; showEditor = true },
                enabled = rules.size < MAX_RULES,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (rules.size < MAX_RULES) "Add Rule" else "Rule limit reached") }
        }

        items(rules, key = { it.id }) { rule ->
            RuleCard(
                rule = rule,
                onEnabledChanged = { enabled ->
                    val index = rules.indexOfFirst { it.id == rule.id }
                    if (index >= 0) { rules[index] = rule.copy(enabled = enabled); persist() }
                },
                onEdit = { editingRule = rule; showEditor = true },
                onDelete = { deleteRule = rule }
            )
        }

        item {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = {
                rules.clear(); rules.addAll(RuleStore.defaultRules()); persist()
            }, modifier = Modifier.fillMaxWidth()) { Text("Reset to defaults") }
            Spacer(Modifier.height(4.dp))
            Card(colors = CardDefaults.cardColors(containerColor = RuleRaised), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp)) {
                    Text("Safe by design", color = RulePrimary, fontWeight = FontWeight.SemiBold)
                    Text("Rules run locally during event capture. They can highlight or prioritize events, but cannot place brokerage orders or send notification content to third parties.", color = RuleSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
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
            title = { Text("Delete rule?") },
            text = { Text("\"${rule.name}\" will stop applying to newly captured events. Existing events are unchanged.") },
            confirmButton = {
                TextButton(onClick = {
                    rules.removeAll { it.id == rule.id }
                    persist()
                    deleteRule = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteRule = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RuleCard(
    rule: RuleEngine.Rule,
    onEnabledChanged: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = RuleSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, color = RuleText, fontWeight = FontWeight.SemiBold)
                    Text(ruleDescription(rule), color = RuleSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(actionLabel(rule.action), color = RulePrimary, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                }
                Switch(checked = rule.enabled, onCheckedChange = onEnabledChanged)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
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
    var actionMenu by remember { mutableStateOf(false) }

    val cleanName = name.trim().take(MAX_NAME)
    val cleanCategory = category.trim().take(MAX_FILTER).ifBlank { null }
    val cleanSource = source.trim().take(MAX_FILTER).ifBlank { null }
    val cleanText = containsText.trim().take(MAX_FILTER).ifBlank { null }
    val valid = cleanName.isNotBlank() && (cleanCategory != null || cleanSource != null || cleanText != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Rule" else "Edit Rule") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(name, { name = it.take(MAX_NAME) }, label = { Text("Rule name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it.take(MAX_FILTER) }, label = { Text("Category (optional)") }, placeholder = { Text("TRADING, PAYMENTS, BILLS…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(source, { source = it.take(MAX_FILTER) }, label = { Text("Source package (optional)") }, placeholder = { Text("com.example.app") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(containsText, { containsText = it.take(MAX_FILTER) }, label = { Text("Text contains (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column {
                    OutlinedButton(onClick = { actionMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Action: ${actionLabel(action)}") }
                    DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                        RuleEngine.Action.values().forEach { option ->
                            DropdownMenuItem(text = { Text(actionLabel(option)) }, onClick = { action = option; actionMenu = false })
                        }
                    }
                }
                if (!valid) Text("Enter a name and at least one condition.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
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
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun actionLabel(action: RuleEngine.Action): String = when (action) {
    RuleEngine.Action.HIGHLIGHT -> "Highlight"
    RuleEngine.Action.ARCHIVE -> "Archive"
    RuleEngine.Action.MARK_TRADING_PRIORITY -> "Mark trading priority"
}

private fun ruleDescription(rule: RuleEngine.Rule): String = buildList {
    rule.category?.let { add("Category: $it") }
    rule.sourcePackage?.let { add("Source: $it") }
    rule.containsText?.let { add("Text: $it") }
}.joinToString(" • ")
