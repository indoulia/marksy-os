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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.intelligence.RuleEngine
import com.marksy.os.intelligence.RuleStore
import kotlinx.coroutines.launch

private val RuleSurface = Color(0xFF101613)
private val RuleRaised = Color(0xFF151C18)
private val RulePrimary = Color(0xFF72D49A)
private val RuleText = Color(0xFFE8F1EC)
private val RuleSecondary = Color(0xFF9AA9A1)

@Composable
fun RulesScreen(padding: PaddingValues) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { RuleStore(context.applicationContext) }
    val rules = remember(store) { mutableStateListOf<RuleEngine.Rule>().apply { addAll(store.load()) } }
    val scope = rememberCoroutineScope()

    fun persist() {
        scope.launch { store.save(rules.toList()) }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Rules & Automation", color = RuleText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("Local rules prepare Marksy OS to act on events without touching brokerage execution.", color = RuleSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
        }
        items(rules, key = { it.id }) { rule ->
            RuleCard(rule) { enabled ->
                val index = rules.indexOfFirst { it.id == rule.id }
                if (index >= 0) {
                    rules[index] = rule.copy(enabled = enabled)
                    persist()
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                rules.clear()
                rules.addAll(RuleStore.defaultRules())
                persist()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset to defaults")
            }
            Spacer(Modifier.height(4.dp))
            Card(colors = CardDefaults.cardColors(containerColor = RuleRaised), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp)) {
                    Text("Safe by design", color = RulePrimary, fontWeight = FontWeight.SemiBold)
                    Text("Rules currently describe local presentation/prioritization only. Notifications are not forwarded and no order can be placed from this screen.", color = RuleSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: RuleEngine.Rule, onEnabledChanged: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = RuleSurface), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.name, color = RuleText, fontWeight = FontWeight.SemiBold)
                Text(ruleDescription(rule), color = RuleSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text(rule.action.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, color = RulePrimary, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Switch(checked = rule.enabled, onCheckedChange = onEnabledChanged)
        }
    }
}

private fun ruleDescription(rule: RuleEngine.Rule): String = buildList {
    rule.category?.let { add("Category: $it") }
    rule.sourcePackage?.let { add("Source: $it") }
    rule.containsText?.let { add("Text: $it") }
}.ifEmpty { listOf("All matching events") }.joinToString(" • ")
