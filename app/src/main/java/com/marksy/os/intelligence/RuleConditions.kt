package com.marksy.os.intelligence

import com.marksy.os.intelligence.RuleEngine.Cmp
import com.marksy.os.intelligence.RuleEngine.Condition
import com.marksy.os.intelligence.RuleEngine.Field

/**
 * EPIC-018 editor shape: the subset of condition trees the Rules screen can edit directly
 * (any-of words AND hour window AND minimum amount AND minimum confidence). Anything richer is
 * kept untouched by the editor rather than being silently flattened.
 */
data class SimpleCondition(
    val anyWords: List<String> = emptyList(),
    val hours: String? = null,
    val minAmount: Double? = null,
    val minConfidencePercent: Int? = null
) {
    fun toCondition(): Condition? {
        val parts = buildList<Condition> {
            val words = anyWords.map { it.trim() }.filter { it.isNotEmpty() }
            if (words.size == 1) add(Condition.Leaf(Field.TEXT, Cmp.CONTAINS, words.single()))
            if (words.size > 1) add(Condition.AnyOf(words.map { Condition.Leaf(Field.TEXT, Cmp.CONTAINS, it) }))
            hours?.trim()?.takeIf { HOURS.matches(it) }?.let { add(Condition.Leaf(Field.HOUR, Cmp.BETWEEN, it)) }
            minAmount?.let { add(Condition.Leaf(Field.AMOUNT, Cmp.GTE, it.toString())) }
            minConfidencePercent?.let { add(Condition.Leaf(Field.CONFIDENCE, Cmp.GTE, (it / 100.0).toString())) }
        }
        return when (parts.size) {
            0 -> null
            1 -> parts.single()
            else -> Condition.All(parts)
        }
    }

    companion object {
        private val HOURS = Regex("^([01]?\\d|2[0-3])\\.\\.([01]?\\d|2[0-3])$")

        /** Null when the tree has a shape the simple editor cannot represent. */
        fun from(condition: Condition?): SimpleCondition? {
            if (condition == null) return SimpleCondition()
            val parts = if (condition is Condition.All) condition.children else listOf(condition)
            var result = SimpleCondition()
            for (p in parts) {
                result = when {
                    p is Condition.Leaf && p.field == Field.TEXT && p.cmp == Cmp.CONTAINS && result.anyWords.isEmpty() -> result.copy(anyWords = listOf(p.value))
                    p is Condition.AnyOf && result.anyWords.isEmpty() && p.children.all { it is Condition.Leaf && it.field == Field.TEXT && it.cmp == Cmp.CONTAINS } ->
                        result.copy(anyWords = p.children.map { (it as Condition.Leaf).value })
                    p is Condition.Leaf && p.field == Field.HOUR && p.cmp == Cmp.BETWEEN && result.hours == null -> result.copy(hours = p.value)
                    p is Condition.Leaf && p.field == Field.AMOUNT && p.cmp == Cmp.GTE && result.minAmount == null -> result.copy(minAmount = p.value.toDoubleOrNull() ?: return null)
                    p is Condition.Leaf && p.field == Field.CONFIDENCE && p.cmp == Cmp.GTE && result.minConfidencePercent == null ->
                        result.copy(minConfidencePercent = ((p.value.toDoubleOrNull() ?: return null) * 100).toInt())
                    else -> return null
                }
            }
            return result
        }
    }
}
