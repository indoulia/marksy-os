package com.marksy.os.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.marksy.os.intelligence.RuleConditionTree
import com.marksy.os.intelligence.RuleEngine
import com.marksy.os.intelligence.RuleEngine.Cmp
import com.marksy.os.intelligence.RuleEngine.Condition
import com.marksy.os.intelligence.RuleEngine.Field
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleConditionEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun userBuildsNestedAndOrThroughTheEditor() {
        var tree by mutableStateOf(RuleConditionTree.root(null))
        compose.setContent { ConditionTreeEditor(tree, onChange = { tree = it }) }

        compose.onNodeWithTag("rule-add-condition-").performClick()
        compose.onNodeWithTag("rule-field-0").performClick()
        compose.onNodeWithTag("rule-field-APP-0").performClick()
        compose.onNodeWithTag("rule-cmp-EQ-0").performClick()
        compose.onNodeWithTag("rule-value-0").performTextInput("com.google.android.gm")

        compose.onNodeWithTag("rule-add-group-").performClick()
        compose.onNodeWithTag("rule-or-1").assertIsDisplayed()
        compose.onNodeWithText("Empty group: add a condition or remove it").assertIsDisplayed()
        compose.onNodeWithTag("rule-add-condition-1").performClick()
        compose.onNodeWithTag("rule-field-1.0").performClick()
        compose.onNodeWithTag("rule-field-SENDER-1.0").performClick()
        compose.onNodeWithTag("rule-value-1.0").performTextInput("rahul")
        compose.onNodeWithTag("rule-add-condition-1").performClick()
        compose.onNodeWithTag("rule-field-1.1").performClick()
        compose.onNodeWithTag("rule-field-CATEGORY-1.1").performClick()
        compose.onNodeWithTag("rule-cmp-EQ-1.1").performClick()
        compose.onNodeWithTag("rule-value-1.1").performTextInput("PAYMENTS")
        compose.waitForIdle()

        val expected = Condition.All(listOf(
            Condition.Leaf(Field.APP, Cmp.EQ, "com.google.android.gm"),
            Condition.AnyOf(listOf(Condition.Leaf(Field.SENDER, Cmp.CONTAINS, "rahul"), Condition.Leaf(Field.CATEGORY, Cmp.EQ, "PAYMENTS")))
        ))
        assertEquals(expected, RuleConditionTree.toSaved(tree))
        assertEquals(emptyList<RuleConditionTree.Issue>(), RuleConditionTree.validate(tree))
        assertEquals(expected, RuleEngine.conditionFromJson(RuleEngine.conditionToJson(expected)))

        compose.onNodeWithTag("rule-not-1").performClick()
        compose.onNodeWithTag("rule-remove-1.0").performClick()
        compose.waitForIdle()
        assertEquals(
            Condition.All(listOf(Condition.Leaf(Field.APP, Cmp.EQ, "com.google.android.gm"), Condition.Not(Condition.AnyOf(listOf(Condition.Leaf(Field.CATEGORY, Cmp.EQ, "PAYMENTS")))))),
            RuleConditionTree.toSaved(tree)
        )
    }

    @Test
    fun reloadedNestedRuleIsShownAndEditable() {
        val stored = Condition.AnyOf(listOf(Condition.Leaf(Field.TEXT, Cmp.CONTAINS, "otp"), Condition.All(listOf(Condition.Leaf(Field.AMOUNT, Cmp.GTE, "1000"), Condition.Leaf(Field.HOUR, Cmp.BETWEEN, "22..6")))))
        var tree by mutableStateOf(RuleConditionTree.root(stored))
        compose.setContent { ConditionTreeEditor(tree, onChange = { tree = it }) }
        compose.onNodeWithTag("rule-value-1.1").assertIsDisplayed()
        compose.onNodeWithTag("rule-and-").performClick()
        compose.waitForIdle()
        assertEquals(Condition.All(stored.children), RuleConditionTree.toSaved(tree))
    }
}
