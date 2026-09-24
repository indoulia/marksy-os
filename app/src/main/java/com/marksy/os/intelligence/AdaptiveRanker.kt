package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * EPIC-013 adaptive ranking. Starts from the deterministic V2 attention score and applies small,
 * individually explained adjustments (personal history, time of day, repetition, fatigue).
 * Protected events can only be raised: no adaptive signal, and no model, may push them below base.
 */
object AdaptiveRanker {
    enum class Prediction { LIKELY_IMPORTANT, UNCERTAIN, LIKELY_NOISE }

    data class Contribution(val name: String, val delta: Int, val reason: String)

    data class Ranked(
        val eventId: Long,
        val baseScore: Int,
        val score: Int,
        val contributions: List<Contribution>,
        val protected: Boolean,
        val prediction: Prediction,
        val reasons: List<String>
    )

    /** Pre-computed per-batch context so ranking a list stays O(n). */
    data class Context(
        val nowMillis: Long,
        val zone: ZoneId,
        val profile: PersonalLearning.Profile,
        val perSourceLast24h: Map<String, Int>,
        val perThreadLastHour: Map<String, Int>
    ) {
        companion object {
            fun from(
                events: List<NotificationEventEntity>,
                nowMillis: Long,
                profile: PersonalLearning.Profile = PersonalLearning.Profile.EMPTY,
                zone: ZoneId = ZoneId.systemDefault()
            ) = Context(
                nowMillis, zone, profile,
                perSourceLast24h = events.filter { nowMillis - it.postedAt in 0..DAY_MS }.groupingBy { it.sourcePackage }.eachCount(),
                perThreadLastHour = events.filter { nowMillis - it.postedAt in 0..HOUR_MS }.groupingBy { EventIntelligence.threadKey(it) }.eachCount()
            )
        }
    }

    fun isProtected(event: NotificationEventEntity): Boolean =
        event.category in PROTECTED_CATEGORIES ||
            event.priority >= PROTECTED_PRIORITY ||
            (event.isTrading && event.deliveryState == DeliveryState.FAILED.name)

    fun rank(event: NotificationEventEntity, ctx: Context): Ranked {
        val base = EventIntelligence.analyze(event, ctx.nowMillis)
        val protected = isProtected(event)
        val contributions = mutableListOf<Contribution>()

        val learned = PersonalLearning.adjustmentFor(event, ctx.profile)
        if (learned.delta != 0) contributions += Contribution("personal", learned.delta, learned.reasons.joinToString("; "))

        val local = Instant.ofEpochMilli(ctx.nowMillis).atZone(ctx.zone)
        val hour = local.hour
        val weekday = local.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        if ((hour >= QUIET_START || hour < QUIET_END) && event.category in LOW_VALUE_CATEGORIES) {
            contributions += Contribution("time_of_day", -5, "Quiet hours: low-value ${event.category.lowercase()} deprioritised")
        }
        if (weekday && hour in WORK_START until WORK_END && event.category in WORK_CATEGORIES) {
            contributions += Contribution("time_of_day", +5, "Working hours: ${event.category.lowercase()} is timely")
        }

        val threadRepeats = ctx.perThreadLastHour[EventIntelligence.threadKey(event)] ?: 0
        if (threadRepeats >= REPEAT_THRESHOLD && event.category !in LOW_VALUE_CATEGORIES) {
            contributions += Contribution("repetition", +5, "$threadRepeats updates on this item in the last hour")
        }

        val sourceVolume = ctx.perSourceLast24h[event.sourcePackage] ?: 0
        if (sourceVolume > FATIGUE_THRESHOLD) {
            val penalty = -((sourceVolume - FATIGUE_THRESHOLD) / FATIGUE_STEP + 1).coerceAtMost(MAX_FATIGUE_PENALTY)
            contributions += Contribution("fatigue", penalty, "${event.sourceName.ifBlank { event.sourcePackage }} sent $sourceVolume notifications in 24h")
        }

        // Weak classifications get half the adaptive effect: we are less sure what the event even is.
        val dampen = event.confidence < LOW_CONFIDENCE
        var delta = contributions.sumOf { it.delta }.let { if (dampen) (it / 2f).roundToInt() else it }
        val reasons = base.reasons.toMutableList()
        contributions.forEach { reasons += "${it.reason} (${if (it.delta > 0) "+" else ""}${it.delta})" }
        if (dampen && contributions.isNotEmpty()) reasons += "Low classification confidence: adaptive effect halved"
        if (protected && delta < 0) {
            reasons += "Protected ${event.category.lowercase()} event: never ranked below its base score"
            delta = 0
        }

        val score = (base.attentionScore + delta).coerceIn(0, 100)
        val prediction = when {
            score >= IMPORTANT_SCORE || protected -> Prediction.LIKELY_IMPORTANT
            score < NOISE_SCORE || (delta < 0 && learned.delta < 0) -> Prediction.LIKELY_NOISE
            else -> Prediction.UNCERTAIN
        }
        return Ranked(event.id, base.attentionScore, score, contributions, protected, prediction, reasons.distinct())
    }

    private val PROTECTED_CATEGORIES = setOf("OTP", "TRADING")
    private val LOW_VALUE_CATEGORIES = setOf("PROMOTIONS", "SYSTEM", "OTHER")
    private val WORK_CATEGORIES = setOf("WORK", "EMAIL")
    private const val PROTECTED_PRIORITY = 90
    private const val QUIET_START = 23
    private const val QUIET_END = 7
    private const val WORK_START = 9
    private const val WORK_END = 18
    private const val REPEAT_THRESHOLD = 3
    private const val FATIGUE_THRESHOLD = 10
    private const val FATIGUE_STEP = 5
    private const val MAX_FATIGUE_PENALTY = 10
    private const val LOW_CONFIDENCE = .7f
    private const val IMPORTANT_SCORE = 70
    private const val NOISE_SCORE = 30
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS
}
