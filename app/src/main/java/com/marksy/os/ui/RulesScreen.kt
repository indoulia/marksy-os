package com.marksy.os.ui

import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.RuleRunner
import com.marksy.os.intelligence.SimpleCondition
import androidx.compose.foundation.horizontalScroll
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
    var testing by remember { mutableStateOf<RuleEngine.Rule?>(null) }
    val runner = remember(context) { MarksyContainer.rules(context.applicationContext) }

    // Reload after saving so versions bumped by the store are reflected (and used by simulation).
    fun persist() = scope.launch {
        store.save(rules.toList())
        val saved = store.load()
        rules.clear()
        rules.addAll(saved)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("Rules & Automation", color = MarksyTheme.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
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
    onDelete: () -> Unit,
    onTest: () -> Unit
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
                TextButton(onClick = onTest) { Text("Test on history", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp) }
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
    val simple = remember(initial) { SimpleCondition.from(initial?.condition) }
    var anyWords by remember(initial) { mutableStateOf(simple?.anyWords?.joinToString(", ").orEmpty()) }
    var hours by remember(initial) { mutableStateOf(simple?.hours.orEmpty()) }
    var minAmount by remember(initial) { mutableStateOf(simple?.minAmount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    var minConfidence by remember(initial) { mutableStateOf(simple?.minConfidencePercent?.toString().orEmpty()) }
    var priority by remember(initial) { mutableStateOf((initial?.priority ?: 0).toString()) }
    // A tree the simple editor cannot represent is preserved unchanged.
    val editedCondition = if (simple == null) initial?.condition else SimpleCondition(
        anyWords = anyWords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        hours = hours.trim().ifBlank { null },
        minAmount = minAmount.trim().toDoubleOrNull(),
        minConfidencePercent = minConfidence.trim().toIntOrNull()?.coerceIn(0, 100)
    ).toCondition()

    val cleanName = name.trim().take(60)
    val cleanCategory = category.trim().take(120).ifBlank { null }
    val cleanSource = source.trim().take(120).ifBlank { null }
    val cleanText = containsText.trim().take(120).ifBlank { null }
    val valid = cleanName.isNotBlank() && (cleanCategory != null || cleanSource != null || cleanText != null || editedCondition != null)

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
                OutlinedTextField(value = category, onValueChange = { category = it.take(20) }, label = { Text("Category (e.g. PAYMENTS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = source, onValueChange = { source = it.take(120) }, label = { Text("App package (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (simple != null) {
                    OutlinedTextField(value = anyWords, onValueChange = { anyWords = it.take(200) }, label = { Text("Any of these words (comma separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = hours, onValueChange = { hours = it.take(5) }, label = { Text("Only between hours, e.g. 22..6") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = minAmount, onValueChange = { minAmount = it.take(12) }, label = { Text("Minimum amount") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = minConfidence, onValueChange = { minConfidence = it.take(3) }, label = { Text("Minimum classifier confidence %") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("This rule has an advanced condition; it is kept as is.", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                }
                OutlinedTextField(value = priority, onValueChange = { priority = it.take(4) }, label = { Text("Rule priority (higher wins conflicts)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleEngine.Action.entries.forEach { a ->
                        FilterChip(selected = action == a, onClick = { action = a }, label = { Text(a.name.lowercase().replace('_', ' '), fontSize = 11.sp) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
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
            ) { Text("Save", color = MarksyTheme.PrimaryEmerald) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MarksyTheme.TextSecondary) } },
        containerColor = MarksyTheme.SurfaceRaised
    )
}

@Composable
private fun RuleTestDialog(rule: RuleEngine.Rule, runner: RuleRunner, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var applied by remember { mutableStateOf<Int?>(null) }
    val result by produceState<Pair<Int, List<RuleEngine.SimulationHit>>?>(null, refresh) { value = runCatching { runner.simulate(rule) }.getOrNull() }
    val executions by produceState(0, refresh) { value = runCatching { runner.executionCount(rule.id) }.getOrDefault(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Test \"${rule.name}\" (v${rule.version})", color = MarksyTheme.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val r = result
                if (r == null) Text("Checking the last 7 days...", color = MarksyTheme.TextMuted)
                else {
                    Text("Would match ${r.second.size} of ${r.first} notifications from the last 7 days.", color = MarksyTheme.TextSecondary)
                    r.second.take(6).forEach { hit ->
                        val effect = hit.stateAction?.name?.lowercase()?.replace('_', ' ') ?: "priority ${hit.priorityBefore} → ${hit.priorityAfter}"
                        Text("• ${hit.title.take(60)} — $effect", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
                    }
                }
                Text("Audit: $executions recorded execution${if (executions == 1) "" else "s"}", color = MarksyTheme.TextMuted, fontSize = 11.sp)
                applied?.let { Text("Applied to $it past notification${if (it == 1) "" else "s"}.", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (result?.second?.isNotEmpty() == true) && rule.enabled,
                onClick = { scope.launch { applied = runCatching { runner.applyToHistory(rule) }.getOrNull(); refresh++ } }
            ) { Text("Apply to these", color = MarksyTheme.PrimaryEmerald) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = MarksyTheme.TextSecondary) } },
        containerColor = MarksyTheme.SurfaceRaised
    )
}

private fun ruleDescription(rule: RuleEngine.Rule): String = buildList {
    rule.category?.let { add("Category: $it") }
    rule.sourcePackage?.let { add("Source: $it") }
    rule.containsText?.let { add("Contains: $it") }
    SimpleCondition.from(rule.condition)?.let { s ->
        if (s.anyWords.isNotEmpty()) add("Any of: ${s.anyWords.joinToString()}")
        s.hours?.let { add("Hours $it") }
        s.minAmount?.let { add("Amount ≥ $it") }
        s.minConfidencePercent?.let { add("Confidence ≥ $it%") }
    } ?: if (rule.condition != null) add("Advanced condition") else Unit
    add(rule.action.name.lowercase().replace('_', ' '))
    if (rule.priority != 0) add("priority ${rule.priority}")
    add("v${rule.version}")
}.ifEmpty { listOf("Applies to all matching events") }.joinToString(" • ")
