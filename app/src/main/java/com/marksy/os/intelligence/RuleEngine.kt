package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic local rule evaluation. Rules only describe safe app-local reactions;
 * they never place brokerage orders or send notification content to third parties.
 *
 * Rules 2.0 (EPIC-018): the legacy flat filters (source/category/text) are ANDed with an optional
 * nested [Condition] tree; rules carry a version and a priority used for deterministic conflict resolution.
 */
object RuleEngine {
    enum class Action { HIGHLIGHT, ARCHIVE, MARK_TRADING_PRIORITY, MARK_RESOLVED }

    enum class Field { APP, SOURCE_NAME, SENDER, CATEGORY, TEXT, ENTITY, CONFIDENCE, PRIORITY, HOUR, DAY_OF_WEEK, AMOUNT }
    enum class Cmp { EQ, CONTAINS, GTE, LTE, BETWEEN }

    sealed class Condition {
        data class All(val children: List<Condition>) : Condition()
        data class AnyOf(val children: List<Condition>) : Condition()
        data class Not(val child: Condition) : Condition()
        /** For BETWEEN, [value] is "low..high" (inclusive; HOUR ranges may wrap midnight, e.g. "22..6"). */
        data class Leaf(val field: Field, val cmp: Cmp, val value: String) : Condition()
    }

    data class Rule(
        val id: String,
        val name: String,
        val enabled: Boolean = true,
        val sourcePackage: String? = null,
        val category: String? = null,
        val containsText: String? = null,
        val action: Action = Action.HIGHLIGHT,
        val condition: Condition? = null,
        /** Higher runs first; ties broken by id so resolution is stable. */
        val priority: Int = 0,
        val version: Int = 1
    )

    data class Evaluation(
        val matchedRules: List<Rule>,
        val priority: Int,
        /** Winning exclusive state change (ARCHIVE or MARK_RESOLVED), if any. */
        val stateAction: Action? = null,
        val explanations: List<String> = emptyList()
    )

    /** Everything a condition can look at, computed once per event. */
    data class Subject(val event: NotificationEventEntity, val facts: EventExtractor.Facts, val zone: ZoneId) {
        companion object {
            fun of(event: NotificationEventEntity, zone: ZoneId = ZoneId.systemDefault()): Subject {
                val stored = EventNormalizer.factsFromJson(event.intelligenceJson)
                val facts = if (stored != EventExtractor.Facts.EMPTY) stored else
                    EventExtractor.extract(event.sourcePackage, event.sourceName, event.category, event.title, event.body, event.postedAt, zone)
                return Subject(event, facts, zone)
            }
        }
    }

    fun matches(rule: Rule, event: NotificationEventEntity): Boolean = matches(rule, Subject.of(event))

    fun matches(rule: Rule, subject: Subject): Boolean {
        val event = subject.event
        if (!rule.enabled) return false
        if (rule.sourcePackage != null && rule.sourcePackage != event.sourcePackage) return false
        if (rule.category != null && rule.category != event.category) return false
        if (rule.containsText != null) {
            val needle = rule.containsText.trim()
            if (needle.isEmpty()) return false
            val haystack = "${event.title} ${event.body}".lowercase()
            if (!haystack.contains(needle.lowercase())) return false
        }
        return rule.condition?.let { evaluate(it, subject) } ?: true
    }

    fun evaluate(condition: Condition, s: Subject): Boolean = when (condition) {
        is Condition.All -> condition.children.all { evaluate(it, s) }
        is Condition.AnyOf -> condition.children.any { evaluate(it, s) }
        is Condition.Not -> !evaluate(condition.child, s)
        is Condition.Leaf -> leaf(condition, s)
    }

    private fun leaf(c: Condition.Leaf, s: Subject): Boolean {
        val e = s.event
        val at = Instant.ofEpochMilli(e.postedAt).atZone(s.zone)
        return when (c.field) {
            Field.APP -> text(e.sourcePackage, c.cmp, c.value)
            Field.SOURCE_NAME -> text(e.sourceName, c.cmp, c.value)
            Field.SENDER -> text(e.title, c.cmp, c.value)
            Field.CATEGORY -> text(e.category, c.cmp, c.value)
            Field.TEXT -> text("${e.title} ${e.body}", c.cmp, c.value)
            Field.ENTITY -> entity(c, s.facts)
            Field.CONFIDENCE -> number(e.confidence.toDouble(), c)
            Field.PRIORITY -> number(e.priority.toDouble(), c)
            Field.AMOUNT -> s.facts.primaryAmount?.let { number(it.amountMinor / 100.0, c) } ?: false
            Field.HOUR -> hour(at.hour, c)
            Field.DAY_OF_WEEK -> c.value.split(',').map { it.trim().uppercase() }.any { it == at.dayOfWeek.name || it == at.dayOfWeek.name.take(3) }
        }
    }

    /** "MERCHANT:amazon" = entity of that type matching the text; "BANK" = any entity of that type; else any entity value. */
    private fun entity(c: Condition.Leaf, facts: EventExtractor.Facts): Boolean {
        val types = EventExtractor.EntityType.entries.map { it.name }
        val raw = c.value.trim()
        val prefix = raw.substringBefore(':').uppercase()
        return when {
            raw.contains(':') && prefix in types -> facts.entities.any { it.type.name == prefix && text(it.value, c.cmp, raw.substringAfter(':')) }
            raw.uppercase() in types -> facts.entities.any { it.type.name == raw.uppercase() }
            else -> facts.entities.any { text(it.value, c.cmp, raw) }
        }
    }

    private fun text(actual: String, cmp: Cmp, value: String): Boolean = when (cmp) {
        Cmp.EQ -> actual.equals(value.trim(), ignoreCase = true)
        Cmp.CONTAINS -> value.isNotBlank() && actual.contains(value.trim(), ignoreCase = true)
        else -> false
    }

    private fun number(actual: Double, c: Condition.Leaf): Boolean {
        if (c.cmp == Cmp.BETWEEN) {
            val (lo, hi) = range(c.value) ?: return false
            return actual in lo..hi
        }
        val v = c.value.trim().toDoubleOrNull() ?: return false
        return when (c.cmp) {
            Cmp.EQ -> actual == v
            Cmp.GTE -> actual >= v
            Cmp.LTE -> actual <= v
            else -> false
        }
    }

    private fun hour(h: Int, c: Condition.Leaf): Boolean {
        if (c.cmp != Cmp.BETWEEN) return number(h.toDouble(), c)
        val (lo, hi) = range(c.value) ?: return false
        return if (lo <= hi) h.toDouble() in lo..hi else h >= lo || h <= hi
    }

    private fun range(v: String): Pair<Double, Double>? {
        val parts = v.split("..")
        if (parts.size != 2) return null
        return (parts[0].trim().toDoubleOrNull() ?: return null) to (parts[1].trim().toDoubleOrNull() ?: return null)
    }

    /** Stable order: priority desc, then id. */
    fun ordered(rules: List<Rule>): List<Rule> = rules.sortedWith(compareByDescending<Rule> { it.priority }.thenBy { it.id })

    fun matchingRules(rules: List<Rule>, event: NotificationEventEntity): List<Rule> {
        val subject = Subject.of(event)
        return rules.filter { matches(it, subject) }
    }

    fun evaluate(rules: List<Rule>, event: NotificationEventEntity): Evaluation = evaluate(rules, Subject.of(event))

    fun evaluate(rules: List<Rule>, subject: Subject): Evaluation {
        val event = subject.event
        val matched = ordered(rules).filter { matches(it, subject) }
        val boost = matched.sumOf { rule ->
            when (rule.action) {
                Action.HIGHLIGHT -> HIGHLIGHT_BOOST
                Action.MARK_TRADING_PRIORITY -> TRADING_PRIORITY_BOOST
                Action.ARCHIVE, Action.MARK_RESOLVED -> 0
            }
        }
        // Conflict resolution: the highest-ordered exclusive rule decides the state; boosts always add up.
        val state = matched.firstOrNull { it.action == Action.ARCHIVE || it.action == Action.MARK_RESOLVED }
        val explanations = matched.map { r ->
            val overridden = state != null && r !== state && (r.action == Action.ARCHIVE || r.action == Action.MARK_RESOLVED)
            "Rule \"${r.name}\" v${r.version}: ${r.action.name.lowercase()}" + if (overridden) " (overridden by \"${state!!.name}\")" else ""
        }
        val matchedSet = matched.toSet()
        // matchedRules keeps the caller's input order (legacy contract).
        return Evaluation(rules.filter { it in matchedSet }, (event.priority + boost).coerceIn(0, 100), state?.action, explanations)
    }

    // ---- historical simulation (no writes) ----

    data class SimulationHit(val eventId: Long, val title: String, val postedAt: Long, val priorityBefore: Int, val priorityAfter: Int, val stateAction: Action?)

    /** Evaluates as if the rule were enabled, so a draft or disabled rule can be tested first. */
    fun simulate(rule: Rule, events: List<NotificationEventEntity>, zone: ZoneId = ZoneId.systemDefault()): List<SimulationHit> {
        val enabled = rule.copy(enabled = true)
        return events.mapNotNull { e ->
            val s = Subject.of(e, zone)
            if (!matches(enabled, s)) return@mapNotNull null
            val ev = evaluate(listOf(enabled), s)
            SimulationHit(e.id, e.title, e.postedAt, e.priority, ev.priority, ev.stateAction)
        }
    }

    // ---- JSON for the condition tree ----

    fun conditionToJson(c: Condition): JSONObject = when (c) {
        is Condition.All -> JSONObject().put("op", "AND").put("children", JSONArray().apply { c.children.forEach { put(conditionToJson(it)) } })
        is Condition.AnyOf -> JSONObject().put("op", "OR").put("children", JSONArray().apply { c.children.forEach { put(conditionToJson(it)) } })
        is Condition.Not -> JSONObject().put("op", "NOT").put("child", conditionToJson(c.child))
        is Condition.Leaf -> JSONObject().put("field", c.field.name).put("cmp", c.cmp.name).put("value", c.value)
    }

    /** Returns null for anything malformed so a corrupt rule is dropped rather than matching everything. */
    fun conditionFromJson(o: JSONObject?, depth: Int = 0): Condition? {
        if (o == null || depth > MAX_DEPTH) return null
        fun children(): List<Condition>? {
            val arr = o.optJSONArray("children") ?: return null
            val list = (0 until minOf(arr.length(), MAX_CHILDREN)).map { conditionFromJson(arr.optJSONObject(it), depth + 1) ?: return null }
            return list.takeIf { it.isNotEmpty() }
        }
        return when (o.optString("op")) {
            "AND" -> children()?.let { Condition.All(it) }
            "OR" -> children()?.let { Condition.AnyOf(it) }
            "NOT" -> conditionFromJson(o.optJSONObject("child"), depth + 1)?.let { Condition.Not(it) }
            "" -> {
                val field = Field.entries.firstOrNull { it.name == o.optString("field") } ?: return null
                val cmp = Cmp.entries.firstOrNull { it.name == o.optString("cmp") } ?: return null
                Condition.Leaf(field, cmp, o.optString("value").take(MAX_VALUE))
            }
            else -> null
        }
    }

    private const val HIGHLIGHT_BOOST = 15
    private const val TRADING_PRIORITY_BOOST = 20
    const val MAX_DEPTH = 6
    const val MAX_CHILDREN = 20
    const val MAX_VALUE = 120
}
