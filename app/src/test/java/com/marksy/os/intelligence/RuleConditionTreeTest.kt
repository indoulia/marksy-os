package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.RuleConditionTree as T
import com.marksy.os.intelligence.RuleEngine.Cmp
import com.marksy.os.intelligence.RuleEngine.Condition
import com.marksy.os.intelligence.RuleEngine.Field
import com.marksy.os.intelligence.RuleEngine.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleConditionTreeTest {
    private val gmail = Condition.Leaf(Field.APP, Cmp.EQ, "com.google.android.gm")
    private val sender = Condition.Leaf(Field.SENDER, Cmp.CONTAINS, "rahul")
    private val finance = Condition.Leaf(Field.CATEGORY, Cmp.EQ, "PAYMENTS")

    private fun event(pkg: String, title: String, category: String) = NotificationEventEntity(
        sourcePackage = pkg, sourceName = "Gmail", sourceKey = "k$title", eventFingerprint = "f$title", title = title, body = "body",
        postedAt = 0L, category = category, priority = 50, confidence = .9f, isTrading = false
    )

    /** Builds App = Gmail AND (Sender = rahul OR Category = PAYMENTS) purely through editor operations. */
    private fun buildNested(): Condition {
        var root = T.root(null)
        root = T.addCondition(root, emptyList(), gmail)
        root = T.addGroup(root, emptyList(), any = true)
        root = T.addCondition(root, listOf(1), sender)
        root = T.addCondition(root, listOf(1), finance)
        return root
    }

    @Test
    fun simpleConditionSavesAsBareLeaf() {
        val root = T.addCondition(T.root(null), emptyList(), sender)
        assertEquals(sender, T.toSaved(root))
        assertEquals(T.root(sender), root)
        assertNull(T.toSaved(T.root(null)))
    }

    @Test
    fun editorBuildsNestedAndOrThatTheEngineEvaluates() {
        val saved = T.toSaved(buildNested())
        assertEquals(Condition.All(listOf(gmail, Condition.AnyOf(listOf(sender, finance)))), saved)
        assertTrue(T.validate(buildNested()).isEmpty())
        val rule = Rule("r", "nested", condition = saved)
        assertTrue(RuleEngine.matches(rule, event("com.google.android.gm", "Rahul", "EMAIL")))
        assertTrue(RuleEngine.matches(rule, event("com.google.android.gm", "Bank", "PAYMENTS")))
        assertFalse(RuleEngine.matches(rule, event("com.google.android.gm", "Newsletter", "EMAIL")))
        assertFalse(RuleEngine.matches(rule, event("com.whatsapp", "Rahul", "MESSAGES")))
        assertEquals("app package is com.google.android.gm AND (sender contains rahul OR category is PAYMENTS)", T.describe(saved!!))
    }

    @Test
    fun orRootAndNegationRoundTripThroughJson() {
        var root = T.setGroupOperator(T.root(null), emptyList(), any = true)
        root = T.addCondition(root, emptyList(), sender)
        root = T.addGroup(root, emptyList(), any = false)
        root = T.addCondition(root, listOf(1), finance)
        root = T.addCondition(root, listOf(1), gmail)
        root = T.setNegated(root, listOf(1), true)
        val saved = T.toSaved(root)!!
        assertEquals(Condition.AnyOf(listOf(sender, Condition.Not(Condition.All(listOf(finance, gmail))))), saved)
        assertEquals(saved, RuleEngine.conditionFromJson(RuleEngine.conditionToJson(saved)))
        // Paths pass through the NOT wrapper and it survives edits of its children.
        root = T.setLeaf(root, listOf(1, 0), finance.copy(value = "BILLS"))
        assertEquals(Condition.Not(Condition.All(listOf(finance.copy(value = "BILLS"), gmail))), T.nodeAt(root, listOf(1)))
        assertEquals(root, T.setNegated(T.setNegated(root, listOf(1), false), listOf(1), true))
    }

    @Test
    fun editingAReloadedNestedRuleKeepsItsStructure() {
        val stored = T.toSaved(buildNested())
        var root = T.root(stored)
        assertEquals(buildNested(), root)
        root = T.setGroupOperator(root, listOf(1), any = false)
        assertEquals(Condition.All(listOf(sender, finance)), T.nodeAt(root, listOf(1)))
        root = T.setLeaf(root, listOf(1, 0), T.withField(sender, Field.AMOUNT))
        assertEquals(Condition.Leaf(Field.AMOUNT, Cmp.GTE, "rahul"), T.nodeAt(root, listOf(1, 0)))
        assertEquals("Enter a number", T.validate(root).single().message)
    }

    @Test
    fun deletingNestedConditionsAndGroups() {
        var root = T.remove(buildNested(), listOf(1, 0))
        assertEquals(Condition.All(listOf(gmail, Condition.AnyOf(listOf(finance)))), root)
        root = T.remove(root, listOf(1, 0))
        assertEquals(listOf(listOf(1)), T.validate(root).map { it.path })
        root = T.remove(root, listOf(1))
        assertEquals(gmail, T.toSaved(root))
        assertNull(T.toSaved(T.remove(root, emptyList())))
        // Out-of-range paths are ignored rather than corrupting the tree.
        assertEquals(root, T.remove(root, listOf(5, 2)))
    }

    @Test
    fun invalidLeavesAndGroupsAreReportedBeforeSave() {
        fun issue(l: Condition.Leaf) = T.leafIssue(l)
        assertEquals("Enter a value", issue(T.newLeaf()))
        assertNull(issue(Condition.Leaf(Field.HOUR, Cmp.BETWEEN, "22..6")))
        assertEquals("Hours are whole numbers 0–23", issue(Condition.Leaf(Field.HOUR, Cmp.GTE, "25")))
        assertEquals("Range low must not exceed high", issue(Condition.Leaf(Field.AMOUNT, Cmp.BETWEEN, "500..100")))
        assertEquals("Use a range like 9..17", issue(Condition.Leaf(Field.AMOUNT, Cmp.BETWEEN, "100")))
        assertEquals("Confidence is between 0 and 1", issue(Condition.Leaf(Field.CONFIDENCE, Cmp.GTE, "80")))
        assertNull(issue(Condition.Leaf(Field.DAY_OF_WEEK, Cmp.EQ, "sat, SUNDAY")))
        assertTrue(issue(Condition.Leaf(Field.DAY_OF_WEEK, Cmp.EQ, "weekend"))!!.startsWith("Unknown day"))
        assertTrue(issue(Condition.Leaf(Field.TEXT, Cmp.GTE, "x"))!!.contains("not supported"))
        assertEquals("Value is too long", issue(Condition.Leaf(Field.TEXT, Cmp.CONTAINS, "x".repeat(RuleEngine.MAX_VALUE + 1))))

        val emptyGroup = T.addGroup(T.root(null), emptyList(), any = true)
        assertEquals("Empty group: add a condition or remove it", T.validate(emptyGroup).single().message)
        assertTrue(T.validate(T.root(null)).isEmpty())
    }

    @Test
    fun treesTheStoreCouldNotReloadAreRejected() {
        var root = T.root(null)
        var path = emptyList<Int>()
        repeat(RuleEngine.MAX_DEPTH) { root = T.addGroup(root, path, any = it % 2 == 0); path = path + 0 }
        root = T.addCondition(root, path, sender)
        assertEquals("Too deeply nested", T.validate(root).single().message)

        var wide = T.root(null)
        repeat(RuleEngine.MAX_CHILDREN + 1) { wide = T.addCondition(wide, emptyList(), sender) }
        assertTrue(T.validate(wide).any { it.message.startsWith("At most") })
    }

    @Test
    fun storeReloadsNestedEditorRulesAndKeepsFlatLegacyRules() {
        val store = RuleStore(RuntimeEnvironment.getApplication())
        val nested = Rule("n", "Nested", condition = T.toSaved(buildNested()), priority = 5)
        val legacy = Rule("l", "Legacy", category = "PAYMENTS", containsText = "debited")
        store.save(listOf(nested, legacy))
        val loaded = store.load()
        assertEquals(nested.condition, loaded.first { it.id == "n" }.condition)
        assertEquals(buildNested(), T.root(loaded.first { it.id == "n" }.condition))
        assertEquals(legacy, loaded.first { it.id == "l" })
        // Re-saving an untouched rule through the editor round trip does not bump its version.
        store.save(loaded.map { it.copy(condition = it.condition?.let { c -> T.toSaved(T.root(c)) }) })
        assertEquals(1, store.load().first { it.id == "n" }.version)
    }

    @Test
    fun storeDropsARuleWhoseConditionIsCorruptInsteadOfMatchingEverything() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("marksy_rules", 0)
        prefs.edit().putString("rules_v1", """[
            {"id":"bad","name":"Bad","action":"ARCHIVE","condition":{"op":"AND","children":[]}},
            {"id":"ok","name":"Ok","category":"PAYMENTS"}
        ]""").commit()
        val loaded = RuleStore(RuntimeEnvironment.getApplication()).load()
        assertEquals(listOf("ok"), loaded.map { it.id })
        assertFalse(RuleEngine.matches(Rule("x", "x", condition = Condition.AnyOf(emptyList())), event("a", "b", "OTHER")))
    }
}
