package com.marksy.os.intelligence

import com.marksy.os.intelligence.RuleEngine.Cmp
import com.marksy.os.intelligence.RuleEngine.Condition
import com.marksy.os.intelligence.RuleEngine.Field

/**
 * EPIC-018 editor operations over the engine's own [Condition] tree, so what the Rules editor builds
 * is exactly what [RuleEngine] evaluates and [RuleStore] persists. A path is a list of child indexes
 * from the root group; a [Condition.Not] wrapper is transparent to paths (it is shown as a flag).
 */
object RuleConditionTree {
    enum class Kind { TEXT, NUMBER, DAYS }

    data class Issue(val path: List<Int>, val message: String)

    /** Deepest group path that may still get a sub-group whose leaves stay within [RuleEngine.MAX_DEPTH]. */
    const val MAX_EDITOR_GROUP_DEPTH = RuleEngine.MAX_DEPTH - 1

    fun kind(field: Field): Kind = when (field) {
        Field.CONFIDENCE, Field.PRIORITY, Field.AMOUNT, Field.HOUR -> Kind.NUMBER
        Field.DAY_OF_WEEK -> Kind.DAYS
        else -> Kind.TEXT
    }

    /** Comparators the evaluator actually honours for a field; anything else never matches. */
    fun comparators(field: Field): List<Cmp> = when (kind(field)) {
        Kind.TEXT -> listOf(Cmp.CONTAINS, Cmp.EQ)
        Kind.NUMBER -> listOf(Cmp.GTE, Cmp.LTE, Cmp.EQ, Cmp.BETWEEN)
        Kind.DAYS -> listOf(Cmp.EQ)
    }

    fun newLeaf(field: Field = Field.TEXT) = Condition.Leaf(field, comparators(field).first(), "")

    /** Changing the field keeps the comparator only when the evaluator supports it for the new field. */
    fun withField(leaf: Condition.Leaf, field: Field): Condition.Leaf =
        leaf.copy(field = field, cmp = if (leaf.cmp in comparators(field)) leaf.cmp else comparators(field).first())

    /** The editor always edits a group root so conditions can be appended to it. */
    fun root(condition: Condition?): Condition = when (condition) {
        null -> Condition.All(emptyList())
        is Condition.All, is Condition.AnyOf -> condition
        else -> Condition.All(listOf(condition))
    }

    /** Inverse of [root]: an empty tree saves as no condition, and a one-child AND root saves as that child so re-saving an untouched rule does not bump its version. */
    fun toSaved(root: Condition): Condition? = when {
        root is Condition.All && root.children.isEmpty() -> null
        root is Condition.All && root.children.size == 1 -> root.children.single()
        else -> root
    }

    fun isGroup(c: Condition) = inner(c).let { it is Condition.All || it is Condition.AnyOf }
    fun isAny(c: Condition) = inner(c) is Condition.AnyOf
    fun isNegated(c: Condition) = c is Condition.Not
    fun inner(c: Condition): Condition = if (c is Condition.Not) c.child else c
    fun children(c: Condition): List<Condition> = when (val g = inner(c)) {
        is Condition.All -> g.children
        is Condition.AnyOf -> g.children
        else -> emptyList()
    }

    fun nodeAt(root: Condition, path: List<Int>): Condition? {
        var node = root
        for (i in path) node = children(node).getOrNull(i) ?: return null
        return node
    }

    fun addCondition(root: Condition, groupPath: List<Int>, leaf: Condition.Leaf = newLeaf()) =
        update(root, groupPath) { g -> mapInner(g) { withChildren(it, children(it) + leaf) } }

    fun addGroup(root: Condition, groupPath: List<Int>, any: Boolean) =
        update(root, groupPath) { g -> mapInner(g) { withChildren(it, children(it) + if (any) Condition.AnyOf(emptyList()) else Condition.All(emptyList())) } }

    fun remove(root: Condition, path: List<Int>): Condition = if (path.isEmpty()) Condition.All(emptyList()) else update(root, path) { null }

    fun setGroupOperator(root: Condition, path: List<Int>, any: Boolean) = update(root, path) { g ->
        mapInner(g) { if (any) Condition.AnyOf(children(it)) else Condition.All(children(it)) }
    }

    fun setLeaf(root: Condition, path: List<Int>, leaf: Condition.Leaf) = update(root, path) { n -> mapInner(n) { leaf } }

    /** The root group cannot be negated: the editor's root is the rule itself. */
    fun setNegated(root: Condition, path: List<Int>, negated: Boolean): Condition {
        if (path.isEmpty()) return root
        return update(root, path) { n -> if (negated) Condition.Not(inner(n)) else inner(n) }
    }

    fun validate(root: Condition): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun walk(node: Condition, path: List<Int>, depth: Int) {
            // Mirrors RuleEngine.conditionFromJson limits, which would otherwise drop the whole tree on reload.
            val innerDepth = if (node is Condition.Not) depth + 1 else depth
            if (innerDepth > RuleEngine.MAX_DEPTH) { issues += Issue(path, "Too deeply nested"); return }
            when (val n = inner(node)) {
                is Condition.All, is Condition.AnyOf -> {
                    val kids = children(n)
                    if (kids.isEmpty() && path.isNotEmpty()) issues += Issue(path, "Empty group: add a condition or remove it")
                    if (kids.size > RuleEngine.MAX_CHILDREN) issues += Issue(path, "At most ${RuleEngine.MAX_CHILDREN} conditions per group")
                    kids.forEachIndexed { i, c -> walk(c, path + i, innerDepth + 1) }
                }
                is Condition.Leaf -> leafIssue(n)?.let { issues += Issue(path, it) }
                is Condition.Not -> issues += Issue(path, "Double negation is not supported")
            }
        }
        walk(root, emptyList(), 0)
        if (issues.isEmpty()) {
            val saved = toSaved(root)
            if (saved != null && RuleEngine.conditionFromJson(RuleEngine.conditionToJson(saved)) != saved) issues += Issue(emptyList(), "Condition cannot be saved")
        }
        return issues
    }

    fun leafIssue(leaf: Condition.Leaf): String? {
        val v = leaf.value.trim()
        if (v.isEmpty()) return "Enter a value"
        if (leaf.value.length > RuleEngine.MAX_VALUE) return "Value is too long"
        if (leaf.cmp !in comparators(leaf.field)) return "${cmpLabel(leaf.cmp)} is not supported for ${fieldLabel(leaf.field)}"
        return when (kind(leaf.field)) {
            Kind.TEXT -> null
            Kind.DAYS -> v.split(',').map { it.trim().uppercase() }.firstOrNull { it !in DAYS }?.let { "Unknown day \"$it\" (use MON,TUE,…)" }
            Kind.NUMBER -> {
                val nums = if (leaf.cmp == Cmp.BETWEEN) {
                    val parts = v.split("..")
                    if (parts.size != 2) return "Use a range like 9..17"
                    parts.map { it.trim().toDoubleOrNull() ?: return "Use a range like 9..17" }
                } else listOf(v.toDoubleOrNull() ?: return "Enter a number")
                when {
                    leaf.field == Field.HOUR && nums.any { it < 0 || it > 23 || it % 1.0 != 0.0 } -> "Hours are whole numbers 0–23"
                    leaf.field == Field.CONFIDENCE && nums.any { it < 0 || it > 1 } -> "Confidence is between 0 and 1"
                    // Only HOUR ranges may wrap midnight; any other reversed range never matches.
                    leaf.cmp == Cmp.BETWEEN && leaf.field != Field.HOUR && nums[0] > nums[1] -> "Range low must not exceed high"
                    else -> null
                }
            }
        }
    }

    fun describe(c: Condition, nested: Boolean = false): String = when (c) {
        is Condition.Leaf -> "${fieldLabel(c.field)} ${cmpLabel(c.cmp)} ${c.value}"
        is Condition.Not -> "NOT (${describe(c.child)})"
        is Condition.All, is Condition.AnyOf -> {
            val kids = children(c)
            val joined = kids.joinToString(if (c is Condition.AnyOf) " OR " else " AND ") { describe(it, nested = true) }
            if (nested && kids.size > 1) "($joined)" else joined
        }
    }

    fun fieldLabel(f: Field) = when (f) {
        Field.APP -> "app package"
        Field.SOURCE_NAME -> "app name"
        Field.SENDER -> "sender"
        Field.CATEGORY -> "category"
        Field.TEXT -> "text"
        Field.ENTITY -> "entity"
        Field.CONFIDENCE -> "confidence"
        Field.PRIORITY -> "priority"
        Field.HOUR -> "hour"
        Field.DAY_OF_WEEK -> "day"
        Field.AMOUNT -> "amount"
    }

    fun cmpLabel(c: Cmp) = when (c) {
        Cmp.EQ -> "is"
        Cmp.CONTAINS -> "contains"
        Cmp.GTE -> "≥"
        Cmp.LTE -> "≤"
        Cmp.BETWEEN -> "between"
    }

    fun valueHint(leaf: Condition.Leaf): String = when {
        leaf.field == Field.ENTITY -> "e.g. MERCHANT:amazon or BANK"
        leaf.field == Field.DAY_OF_WEEK -> "e.g. SAT,SUN"
        leaf.field == Field.CONFIDENCE -> if (leaf.cmp == Cmp.BETWEEN) "e.g. 0.5..0.9" else "0 to 1"
        leaf.field == Field.HOUR -> if (leaf.cmp == Cmp.BETWEEN) "e.g. 22..6" else "0 to 23"
        leaf.cmp == Cmp.BETWEEN -> "e.g. 100..500"
        leaf.field == Field.CATEGORY -> "e.g. PAYMENTS"
        else -> "value"
    }

    private fun mapInner(c: Condition, f: (Condition) -> Condition): Condition = if (c is Condition.Not) Condition.Not(f(c.child)) else f(c)

    private fun withChildren(group: Condition, kids: List<Condition>): Condition =
        if (group is Condition.AnyOf) Condition.AnyOf(kids) else Condition.All(kids)

    /** Applies [f] to the node at [path]; a null result removes it from its parent group. */
    private fun update(root: Condition, path: List<Int>, f: (Condition) -> Condition?): Condition {
        if (path.isEmpty()) return f(root) ?: Condition.All(emptyList())
        return mapInner(root) { g ->
            val kids = children(g)
            val i = path.first()
            if (i !in kids.indices) return@mapInner g
            val replaced = if (path.size == 1) f(kids[i]) else update(kids[i], path.drop(1), f)
            withChildren(g, if (replaced == null) kids.filterIndexed { j, _ -> j != i } else kids.mapIndexed { j, c -> if (j == i) replaced else c })
        }
    }

    private val DAYS = java.time.DayOfWeek.entries.flatMap { listOf(it.name, it.name.take(3)) }.toSet()
}
