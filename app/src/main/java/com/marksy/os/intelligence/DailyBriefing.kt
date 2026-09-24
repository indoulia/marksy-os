package com.marksy.os.intelligence

import com.marksy.os.data.local.EventActionEntity
import com.marksy.os.data.local.NotificationEventEntity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * EPIC-017 deterministic briefing. Same inputs (events, expected items, profile, clock, zone)
 * always give the same briefing; every line points at the events it came from. Wording can be
 * improved later by a model, but facts and ordering are fixed here.
 */
object DailyBriefing {
    enum class Kind(val label: String) { MORNING("Morning"), EVENING("Evening"), OVERNIGHT("Overnight") }

    data class Line(val text: String, val why: String, val eventIds: List<Long>)
    data class Section(val title: String, val lines: List<Line>)

    data class Briefing(
        val kind: Kind,
        val windowStart: Long,
        val windowEnd: Long,
        val headline: String,
        val sections: List<Section>,
        val derivedFromEventIds: List<Long>
    )

    fun defaultKind(nowMillis: Long, zone: ZoneId): Kind {
        val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
        return when {
            hour < 5 -> Kind.OVERNIGHT
            hour < 15 -> Kind.MORNING
            else -> Kind.EVENING
        }
    }

    fun build(
        kind: Kind,
        events: List<NotificationEventEntity>,
        expected: List<EventActionEntity>,
        profile: PersonalLearning.Profile,
        nowMillis: Long,
        zone: ZoneId
    ): Briefing {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val (windowStart, windowEnd) = when (kind) {
            // Overnight: 22:00 yesterday until now (or 07:00 if asked later in the day).
            Kind.OVERNIGHT -> today.minusDays(1).atTime(22, 0).atZone(zone).toInstant().toEpochMilli() to
                minOf(nowMillis, today.atTime(LocalTime.of(7, 0)).atZone(zone).toInstant().toEpochMilli())
            Kind.MORNING -> today.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli() to nowMillis
            Kind.EVENING -> today.atStartOfDay(zone).toInstant().toEpochMilli() to nowMillis
        }
        val upcomingDay = if (kind == Kind.EVENING) today.plusDays(1) else today
        val upStart = upcomingDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val upEnd = upcomingDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val ids = events.mapTo(HashSet()) { it.id }
        val canonical = events.filter { it.duplicateOfId == null || it.duplicateOfId !in ids }.sortedWith(STABLE)
        val inWindow = canonical.filter { it.postedAt in windowStart..windowEnd }
        val ctx = AdaptiveRanker.Context.from(canonical, nowMillis, profile, zone)
        fun facts(e: NotificationEventEntity) = EventNormalizer.factsFromJson(e.intelligenceJson)
        fun isOpen(e: NotificationEventEntity) = !e.archived && e.lifecycleState != EventLifecycle.State.RESOLVED.name

        // Important: adaptive score, protected events first; ties broken by time then id.
        val important = inWindow.filter { isOpen(it) }
            .map { it to AdaptiveRanker.rank(it, ctx) }
            .filter { it.second.score >= IMPORTANT || it.second.protected }
            .sortedWith(compareByDescending<Pair<NotificationEventEntity, AdaptiveRanker.Ranked>> { it.second.score }.thenByDescending { it.first.postedAt }.thenBy { it.first.id })
            .distinctBy { EventIntelligence.threadKey(it.first) }
            .take(MAX_LINES)
            .map { (e, r) -> Line(title(e), r.reasons.take(2).joinToString("; "), listOf(e.id)) }

        // Pending actions: open threads from the last week that the inbox would put in "Needs action".
        val pending = canonical.filter { isOpen(it) && nowMillis - it.postedAt in 0..WEEK }
            .groupBy { EventIntelligence.threadKey(it) }.values
            .mapNotNull { thread ->
                val top = thread.maxBy { it.postedAt }
                val (bucket, reason) = SmartInboxModel.bucketFor(thread, AdaptiveRanker.rank(top, ctx).score, nowMillis)
                if (bucket == SmartInboxModel.Bucket.NEEDS_ACTION) Line(title(top), reason, listOf(top.id) + thread.map { it.id }.filter { it != top.id }) else null
            }
            .sortedWith(compareByDescending<Line> { l -> canonical.first { it.id == l.eventIds.first() }.postedAt }.thenBy { it.eventIds.first() })
            .take(MAX_LINES)

        // Upcoming: dates mentioned in still-open events plus items the user marked as expected.
        val upcomingFromText = canonical.filter { isOpen(it) && !facts(it).terminal }
            .mapNotNull { e -> facts(e).times.firstOrNull { it.epochMillis in upStart until upEnd }?.let { e to it } }
            .sortedWith(compareBy<Pair<NotificationEventEntity, EventExtractor.TimeMention>> { it.second.epochMillis }.thenBy { it.first.id })
            .distinctBy { EventIntelligence.threadKey(it.first) }
            .map { (e, t) -> Line(title(e), "Mentions \"${t.raw}\"", listOf(e.id)) }
        val byId = events.associateBy { it.id }
        val upcomingExpected = expected.filter { (it.scheduledFor ?: -1) in upStart until upEnd && byId[it.eventId]?.let(::isOpen) == true }
            .sortedWith(compareBy<EventActionEntity> { it.scheduledFor }.thenBy { it.id })
            .map { Line(title(byId.getValue(it.eventId)), "You marked this as expected", listOf(it.eventId)) }
        val upcoming = (upcomingExpected + upcomingFromText).distinctBy { it.eventIds.first() }.take(MAX_LINES)

        // Financial summary over the window, canonical rows only so a payment seen twice counts once.
        val money = inWindow.filter { it.category in MONEY }.mapNotNull { e -> facts(e).primaryAmount?.let { e to it } }
            .filter { it.second.direction != EventExtractor.Direction.UNKNOWN }
        val financial = money.groupBy { it.second.direction to it.second.currency }.toSortedMap(compareBy({ it.first }, { it.second }))
            .map { (k, v) ->
                Line(
                    "${if (k.first == EventExtractor.Direction.DEBIT) "Spent" else "Received"} ${AskMarksy.formatMoney(v.sumOf { it.second.amountMinor }, k.second)}",
                    "${v.size} transaction${if (v.size == 1) "" else "s"} with an amount",
                    v.map { it.first.id }
                )
            }

        // Personalised: things from subjects the user explicitly marked important.
        val highlights = inWindow.filter { e ->
            PersonalLearning.subjectsOf(e).any { profile.of(it.type, it.key)?.override == PersonalLearning.Preference.ALWAYS_IMPORTANT }
        }.distinctBy { EventIntelligence.threadKey(it) }.take(MAX_LINES)
            .map { Line(title(it), "You marked ${it.sourceName.ifBlank { it.sourcePackage }} or this sender as important", listOf(it.id)) }

        val sections = listOf(
            Section("Important", important),
            Section("Needs your action", pending),
            Section(if (kind == Kind.EVENING) "Tomorrow" else "Today", upcoming),
            Section("Money", financial),
            Section("For you", highlights)
        ).filter { it.lines.isNotEmpty() }

        val headline = buildString {
            append("${inWindow.size} notification${if (inWindow.size == 1) "" else "s"} ${windowLabel(kind)}")
            if (important.isNotEmpty()) append(", ${important.size} important")
            if (pending.isNotEmpty()) append(", ${pending.size} need${if (pending.size == 1) "s" else ""} action")
            append('.')
        }
        return Briefing(kind, windowStart, windowEnd, headline, sections, sections.flatMap { s -> s.lines.flatMap { it.eventIds } }.distinct())
    }

    private fun windowLabel(kind: Kind) = when (kind) {
        Kind.OVERNIGHT -> "overnight"
        Kind.MORNING -> "since yesterday evening"
        Kind.EVENING -> "today"
    }

    private fun title(e: NotificationEventEntity) =
        "${e.sourceName.ifBlank { e.sourcePackage }}: ${e.title.ifBlank { e.body }.take(80)}"

    private val STABLE = compareByDescending<NotificationEventEntity> { it.postedAt }.thenBy { it.id }
    private val MONEY = setOf("PAYMENTS", "BANKING", "BILLS")
    private const val IMPORTANT = 70
    private const val MAX_LINES = 5
    private const val WEEK = 7 * 24 * 60 * 60 * 1000L
}
