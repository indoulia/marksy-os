package com.marksy.os.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.intelligence.RuleConditionTree as Tree
import com.marksy.os.intelligence.RuleEngine.Condition
import com.marksy.os.intelligence.RuleEngine.Field

/** EPIC-018 nested AND/OR/NOT editor; every edit goes through [Tree] so the result is the engine's own tree. */
@Composable
internal fun ConditionTreeEditor(root: Condition, onChange: (Condition) -> Unit) {
    val issues = remember(root) { Tree.validate(root).associate { it.path to it.message } }
    ConditionGroup(root, emptyList(), root, issues, onChange)
}

private fun tag(kind: String, path: List<Int>) = "rule-$kind-${path.joinToString(".")}"

@Composable
private fun ConditionGroup(node: Condition, path: List<Int>, root: Condition, issues: Map<List<Int>, String>, onChange: (Condition) -> Unit) {
    val any = Tree.isAny(node)
    val nested = path.isNotEmpty()
    val frame = if (nested) Modifier.border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(10.dp)).padding(8.dp) else Modifier
    Column(Modifier.fillMaxWidth().then(frame), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (nested) "GROUP" else "WHEN", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            FilterChip(selected = !any, onClick = { onChange(Tree.setGroupOperator(root, path, any = false)) }, label = { Text("all (and)", fontSize = 11.sp) }, modifier = Modifier.testTag(tag("and", path)))
            FilterChip(selected = any, onClick = { onChange(Tree.setGroupOperator(root, path, any = true)) }, label = { Text("any (or)", fontSize = 11.sp) }, modifier = Modifier.testTag(tag("or", path)))
            if (nested) {
                FilterChip(selected = Tree.isNegated(node), onClick = { onChange(Tree.setNegated(root, path, !Tree.isNegated(node))) }, label = { Text("not", fontSize = 11.sp) }, modifier = Modifier.testTag(tag("not", path)))
                TextButton(onClick = { onChange(Tree.remove(root, path)) }, modifier = Modifier.testTag(tag("remove", path))) { Text("Remove group", color = MarksyTheme.RedUrgent, fontSize = 11.sp) }
            }
        }
        val children = Tree.children(node)
        if (children.isEmpty() && !nested) Text("No extra conditions: the filters above decide.", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        children.forEachIndexed { i, child ->
            val childPath = path + i
            if (Tree.isGroup(child)) ConditionGroup(child, childPath, root, issues, onChange)
            else ConditionLeafRow(child, childPath, root, issues[childPath], onChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onChange(Tree.addCondition(root, path)) }, modifier = Modifier.testTag(tag("add-condition", path))) { Text("+ Condition", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp) }
            if (path.size < Tree.MAX_EDITOR_GROUP_DEPTH) {
                // A new sub-group defaults to the opposite operator, which is the usual reason to nest.
                TextButton(onClick = { onChange(Tree.addGroup(root, path, any = !any)) }, modifier = Modifier.testTag(tag("add-group", path))) { Text("+ Group", color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp) }
            }
        }
        issues[path]?.let { Text(it, color = MarksyTheme.RedUrgent, fontSize = 11.sp) }
    }
}

@Composable
private fun ConditionLeafRow(node: Condition, path: List<Int>, root: Condition, issue: String?, onChange: (Condition) -> Unit) {
    val leaf = Tree.inner(node) as? Condition.Leaf ?: return
    var fieldMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, MarksyTheme.SurfaceRaised, RoundedCornerShape(8.dp)).padding(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = Tree.isNegated(node), onClick = { onChange(Tree.setNegated(root, path, !Tree.isNegated(node))) }, label = { Text("not", fontSize = 11.sp) }, modifier = Modifier.testTag(tag("not", path)))
            Box {
                TextButton(onClick = { fieldMenu = true }, modifier = Modifier.testTag(tag("field", path))) { Text("${Tree.fieldLabel(leaf.field)} ▾", color = MarksyTheme.TextPrimary, fontSize = 12.sp) }
                DropdownMenu(expanded = fieldMenu, onDismissRequest = { fieldMenu = false }) {
                    Field.entries.forEach { f ->
                        DropdownMenuItem(text = { Text(Tree.fieldLabel(f)) }, onClick = { fieldMenu = false; onChange(Tree.setLeaf(root, path, Tree.withField(leaf, f))) }, modifier = Modifier.testTag(tag("field-${f.name}", path)))
                    }
                }
            }
            Tree.comparators(leaf.field).forEach { c ->
                FilterChip(selected = leaf.cmp == c, onClick = { onChange(Tree.setLeaf(root, path, leaf.copy(cmp = c))) }, label = { Text(Tree.cmpLabel(c), fontSize = 11.sp) }, modifier = Modifier.testTag(tag("cmp-${c.name}", path)))
            }
            TextButton(onClick = { onChange(Tree.remove(root, path)) }, modifier = Modifier.testTag(tag("remove", path))) { Text("✕", color = MarksyTheme.RedUrgent, fontSize = 12.sp) }
        }
        OutlinedTextField(
            value = leaf.value,
            onValueChange = { onChange(Tree.setLeaf(root, path, leaf.copy(value = it.take(com.marksy.os.intelligence.RuleEngine.MAX_VALUE)))) },
            placeholder = { Text(Tree.valueHint(leaf), fontSize = 11.sp) },
            singleLine = true,
            isError = issue != null,
            modifier = Modifier.fillMaxWidth().testTag(tag("value", path))
        )
        issue?.let { Text(it, color = MarksyTheme.RedUrgent, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)) }
    }
}
