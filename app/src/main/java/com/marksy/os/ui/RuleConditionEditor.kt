package com.marksy.os.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    val body: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalAlignment = Alignment.CenterVertically) {
            Text(if (nested) "GROUP" else "WHEN", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Label)
            Pill("all (and)", selected = !any, modifier = Modifier.testTag(tag("and", path))) { onChange(Tree.setGroupOperator(root, path, any = false)) }
            Pill("any (or)", selected = any, modifier = Modifier.testTag(tag("or", path))) { onChange(Tree.setGroupOperator(root, path, any = true)) }
            if (nested) {
                Pill("not", selected = Tree.isNegated(node), modifier = Modifier.testTag(tag("not", path))) { onChange(Tree.setNegated(root, path, !Tree.isNegated(node))) }
                MarksyButton("Remove group", onClick = { onChange(Tree.remove(root, path)) }, modifier = Modifier.testTag(tag("remove", path)), style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
            }
        }
        val children = Tree.children(node)
        if (children.isEmpty() && !nested) Text("No extra conditions: the filters above decide.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
        children.forEachIndexed { i, child ->
            val childPath = path + i
            if (Tree.isGroup(child)) ConditionGroup(child, childPath, root, issues, onChange)
            else ConditionLeafRow(child, childPath, root, issues[childPath], onChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Tight)) {
            MarksyButton("+ Condition", onClick = { onChange(Tree.addCondition(root, path)) }, modifier = Modifier.testTag(tag("add-condition", path)), style = MarksyButtonStyle.Text, color = MarksyTheme.PrimaryEmerald)
            if (path.size < Tree.MAX_EDITOR_GROUP_DEPTH) {
                // A new sub-group defaults to the opposite operator, which is the usual reason to nest.
                MarksyButton("+ Group", onClick = { onChange(Tree.addGroup(root, path, any = !any)) }, modifier = Modifier.testTag(tag("add-group", path)), style = MarksyButtonStyle.Text, color = MarksyTheme.PrimaryEmerald)
            }
        }
        issues[path]?.let { Text(it, color = MarksyTheme.Negative, style = MarksyType.Meta) }
    }
    if (nested) MarksyCard(content = body) else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner), content = body)
}

@Composable
private fun ConditionLeafRow(node: Condition, path: List<Int>, root: Condition, issue: String?, onChange: (Condition) -> Unit) {
    val leaf = Tree.inner(node) as? Condition.Leaf ?: return
    var fieldMenu by remember { mutableStateOf(false) }
    MarksyCard {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalAlignment = Alignment.CenterVertically) {
            Pill("not", selected = Tree.isNegated(node), modifier = Modifier.testTag(tag("not", path))) { onChange(Tree.setNegated(root, path, !Tree.isNegated(node))) }
            Box {
                Row(Modifier.clickable { fieldMenu = true }.testTag(tag("field", path)).padding(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
                    Text(Tree.fieldLabel(leaf.field), color = MarksyTheme.TextPrimary, style = MarksyType.Body)
                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon))
                }
                DropdownMenu(expanded = fieldMenu, onDismissRequest = { fieldMenu = false }) {
                    Field.entries.forEach { f ->
                        DropdownMenuItem(text = { Text(Tree.fieldLabel(f)) }, onClick = { fieldMenu = false; onChange(Tree.setLeaf(root, path, Tree.withField(leaf, f))) }, modifier = Modifier.testTag(tag("field-${f.name}", path)))
                    }
                }
            }
            Tree.comparators(leaf.field).forEach { c ->
                Pill(Tree.cmpLabel(c), selected = leaf.cmp == c, modifier = Modifier.testTag(tag("cmp-${c.name}", path))) { onChange(Tree.setLeaf(root, path, leaf.copy(cmp = c))) }
            }
            IconButton(onClick = { onChange(Tree.remove(root, path)) }, modifier = Modifier.testTag(tag("remove", path))) { Icon(Icons.Default.Close, contentDescription = "Remove condition", tint = MarksyTheme.Negative, modifier = Modifier.size(MarksySize.Icon)) }
        }
        CompactTextField(
            value = leaf.value,
            onValueChange = { onChange(Tree.setLeaf(root, path, leaf.copy(value = it.take(com.marksy.os.intelligence.RuleEngine.MAX_VALUE)))) },
            placeholder = Tree.valueHint(leaf),
            modifier = Modifier.fillMaxWidth(),
            fieldModifier = Modifier.testTag(tag("value", path))
        )
        issue?.let { Text(it, color = MarksyTheme.Negative, style = MarksyType.Meta, modifier = Modifier.padding(top = MarksySpace.Hair)) }
    }
}
