package com.marksy.os.intelligence

import com.marksy.os.data.local.LearningOverrideEntity
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.SignalCount
import java.util.Locale
import kotlin.math.roundToInt

/**
 * EPIC-012 personal learning. Pure and deterministic: the same signal counts and overrides
 * always produce the same profile, and every adjustment carries a human-readable reason.
 */
object PersonalLearning {
    enum class SubjectType { APP, SENDER, CATEGORY }

    enum class Signal(val engagement: Int) {
        OPENED(+1), RESOLVED(+1), SNOOZED(0), ARCHIVED_UNOPENED(-1), IGNORED(-1)
    }

    enum class Preference(val adjustment: Int, val label: String) {
        ALWAYS_IMPORTANT(+25, "You marked this important"),
        NORMAL(0, "You reset this to normal"),
        LESS_IMPORTANT(-25, "You marked this less important")
    }

    data class Subject(val type: SubjectType, val key: String, val label: String)

    data class SubjectProfile(
        val subject: Subject,
        val positive: Int,
        val negative: Int,
        val neutral: Int,
        val lastObservedAt: Long,
        val adjustment: Int,
        val confidence: Float,
        val override: Preference?,
        val reason: String
    )

    data class Profile(val subjects: Map<Pair<SubjectType, String>, SubjectProfile>) {
        fun of(type: SubjectType, key: String): SubjectProfile? = subjects[type to key]

        /** Used while learning is disabled: explicit user corrections still apply, learned values do not. */
        fun correctionsOnly(): Profile = Profile(subjects.filterValues { it.override != null })

        companion object {
            val EMPTY = Profile(emptyMap())
        }
    }

    /** Subjects an event contributes to. Sender only for messaging, where the title is the sender. */
    fun subjectsOf(event: NotificationEventEntity): List<Subject> = buildList {
        add(Subject(SubjectType.APP, event.sourcePackage.trim().lowercase(Locale.ROOT), event.sourceName.ifBlank { event.sourcePackage }))
        add(Subject(SubjectType.CATEGORY, event.category, event.category.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }))
        senderKey(event)?.let { add(Subject(SubjectType.SENDER, it, event.title.trim())) }
    }

    fun senderKey(event: NotificationEventEntity): String? =
        if (event.category == "MESSAGES" && event.title.isNotBlank())
            "${event.sourcePackage.lowercase(Locale.ROOT)}|${event.title.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")}"
        else null

    fun buildProfile(counts: List<SignalCount>, overrides: List<LearningOverrideEntity>): Profile {
        val grouped = counts.groupBy { it.subjectType to it.subjectKey }
        val overrideBy = overrides.associateBy { it.subjectType to it.subjectKey }
        val keys = grouped.keys + overrideBy.keys
        val subjects = keys.mapNotNull { key ->
            val type = SubjectType.entries.firstOrNull { it.name == key.first } ?: return@mapNotNull null
            val rows = grouped[key].orEmpty()
            fun count(s: Signal) = rows.filter { it.signal == s.name }.sumOf { it.count }
            val positive = Signal.entries.filter { it.engagement > 0 }.sumOf { count(it) }
            val negative = Signal.entries.filter { it.engagement < 0 }.sumOf { count(it) }
            val neutral = Signal.entries.filter { it.engagement == 0 }.sumOf { count(it) }
            val override = overrideBy[key]?.let { o -> Preference.entries.firstOrNull { it.name == o.preference } }
            val label = overrideBy[key]?.label ?: rows.maxByOrNull { it.lastAt }?.label?.ifBlank { null } ?: key.second
            val decided = positive + negative

            val (adjustment, confidence, reason) = when {
                override != null -> Triple(override.adjustment, 1f, override.label)
                decided < MIN_SAMPLES -> Triple(0, decided / MIN_SAMPLES.toFloat() * .5f, "Still learning ($decided of $MIN_SAMPLES interactions)")
                else -> {
                    val rate = positive / decided.toFloat()
                    val adj = ((rate - .5f) * 2f * MAX_LEARNED_ADJUSTMENT).roundToInt()
                    val conf = (decided / FULL_CONFIDENCE_SAMPLES.toFloat()).coerceAtMost(1f)
                    val scaled = (adj * conf).roundToInt()
                    Triple(scaled, conf, "You engaged with ${(rate * 100).roundToInt()}% of $decided notifications")
                }
            }
            (type to key.second) to SubjectProfile(
                Subject(type, key.second, label), positive, negative, neutral,
                rows.maxOfOrNull { it.lastAt } ?: 0L, adjustment, confidence, override, reason
            )
        }.toMap()
        return Profile(subjects)
    }

    /** Combined adjustment for an event: an explicit override on any subject wins over all learning. */
    data class Adjustment(val delta: Int, val reasons: List<String>)

    fun adjustmentFor(event: NotificationEventEntity, profile: Profile): Adjustment {
        val profiles = subjectsOf(event).mapNotNull { profile.of(it.type, it.key) }
        // Most specific explicit override wins (sender > app > category).
        val override = profiles.filter { it.override != null }.minByOrNull { SPECIFICITY.indexOf(it.subject.type) }
        if (override != null) return Adjustment(override.adjustment, listOf("${override.reason} (${override.subject.label})"))
        val learned = profiles.filter { it.adjustment != 0 }
        if (learned.isEmpty()) return Adjustment(0, emptyList())
        val delta = learned.sumOf { it.adjustment }.coerceIn(-MAX_LEARNED_ADJUSTMENT, MAX_LEARNED_ADJUSTMENT)
        return Adjustment(delta, learned.map { "Learned: ${it.reason} from ${it.subject.label} (${signed(it.adjustment)})" })
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private val SPECIFICITY = listOf(SubjectType.SENDER, SubjectType.APP, SubjectType.CATEGORY)
    const val MIN_SAMPLES = 5
    const val FULL_CONFIDENCE_SAMPLES = 20
    const val MAX_LEARNED_ADJUSTMENT = 15
    const val WINDOW_MS = 60L * 24 * 60 * 60 * 1000
    const val IGNORED_AFTER_MS = 24L * 60 * 60 * 1000
}
