package com.marksy.os.intelligence

import com.marksy.os.data.local.MemoryEntryEntity
import com.marksy.os.data.local.NotificationEventEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * EPIC-020 controlled personal memory. Pure: folds newly seen events into existing entries.
 * Rules: FORGOTTEN tombstones are never recreated, CORRECTED/USER entries keep their label and
 * never expire, disabled kinds are not learned, every entry records the events it came from.
 */
object PersonalMemory {
    enum class Kind(val label: String, val ttlDays: Long?) {
        CONTACT("Frequent contacts", 60), MERCHANT("Merchants", 90), ORGANIZATION("Organisations", 120),
        RECURRING_PAYMENT("Recurring payments", 120), RECURRING_DELIVERY("Recurring deliveries", 120),
        RECURRING_EVENT("Recurring events", 60), LOCATION("Places", 90), PREFERENCE("Preferences", null)
    }

    data class Observation(val kind: Kind, val key: String, val label: String, val at: Long, val eventId: Long, val amountMinor: Long? = null, val currency: String? = null)

    /** Candidate observations from one event (deterministic). Locations are never guessed: no location data is captured. */
    fun observe(event: NotificationEventEntity, facts: EventExtractor.Facts): List<Observation> = buildList {
        val amount = facts.primaryAmount
        facts.entities.forEach { e ->
            when (e.type) {
                EventExtractor.EntityType.PERSON -> if (event.category == "MESSAGES" || event.category in MONEY)
                    add(Observation(Kind.CONTACT, key(e.value), e.value, event.postedAt, event.id))
                EventExtractor.EntityType.MERCHANT -> {
                    add(Observation(Kind.MERCHANT, key(e.value), e.value, event.postedAt, event.id))
                    if (amount != null && amount.direction == EventExtractor.Direction.DEBIT) add(
                        Observation(Kind.RECURRING_PAYMENT, "${key(e.value)}|${amount.currency}", e.value, event.postedAt, event.id, amount.amountMinor, amount.currency)
                    )
                }
                EventExtractor.EntityType.COMPANY, EventExtractor.EntityType.BANK -> add(Observation(Kind.ORGANIZATION, key(e.value), e.value, event.postedAt, event.id))
                EventExtractor.EntityType.DELIVERY -> add(Observation(Kind.RECURRING_DELIVERY, key(e.value), e.value, event.postedAt, event.id))
                else -> Unit
            }
        }
        if (event.category == "BILLS") add(Observation(Kind.RECURRING_PAYMENT, "bill|${key(event.sourceName.ifBlank { event.sourcePackage })}", event.sourceName.ifBlank { event.sourcePackage }, event.postedAt, event.id, amount?.amountMinor, amount?.currency))
        if (event.category in RECURRING_EVENT_CATEGORIES) {
            val title = event.title.lowercase(Locale.ROOT).replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").trim().take(60)
            if (title.isNotBlank()) add(Observation(Kind.RECURRING_EVENT, "${event.sourcePackage}|$title", "${event.sourceName}: ${event.title.take(40)}", event.postedAt, event.id))
        }
    }.distinctBy { it.kind to it.key }

    fun fold(
        existing: MemoryEntryEntity?,
        obs: List<Observation>,
        enabledKinds: Set<Kind>,
        nowMillis: Long,
        zone: ZoneId
    ): MemoryEntryEntity? {
        if (obs.isEmpty()) return null
        val kind = obs.first().kind
        if (existing?.state == STATE_FORGOTTEN) return null
        if (kind !in enabledKinds && existing?.origin != ORIGIN_USER) return null

        val detail = existing?.detailJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val times = (detail.optJSONArray("times").longs() + obs.map { it.at }).distinct().sorted().takeLast(MAX_TIMES)
        val eventIds = (detail.optJSONArray("events").longs() + obs.map { it.eventId }).distinct().takeLast(MAX_EVENTS)
        val amounts = (detail.optJSONArray("amounts").longs() + obs.mapNotNull { it.amountMinor }).takeLast(MAX_TIMES)
        val days = times.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.distinct()

        // A memory needs repetition on distinct days before it is believed.
        val confidenceBase = when (kind) {
            Kind.RECURRING_PAYMENT, Kind.RECURRING_DELIVERY, Kind.RECURRING_EVENT -> cadence(times)?.let { .5f + .1f * (days.size - 1).coerceAtMost(4) } ?: .2f
            else -> (.3f + .15f * (days.size - 1)).coerceAtMost(.95f)
        }
        val amountStable = amounts.size >= 2 && amounts.all { abs(it - amounts.last()) <= amounts.last() * 0.05 }
        val confidence = (if (kind == Kind.RECURRING_PAYMENT && amountStable) confidenceBase + .1f else confidenceBase).coerceIn(0f, .95f)

        val newDetail = JSONObject()
            .put("times", JSONArray(times))
            .put("events", JSONArray(eventIds))
            .apply { if (amounts.isNotEmpty()) put("amounts", JSONArray(amounts)) }
            .apply { obs.firstNotNullOfOrNull { it.currency }?.let { put("currency", it) } ?: detail.optString("currency").takeIf { it.isNotBlank() }?.let { put("currency", it) } }
            .apply { cadence(times)?.let { put("cadenceDays", it) } }

        val userOwned = existing?.origin == ORIGIN_USER || existing?.state == STATE_CORRECTED
        val last = times.last()
        return MemoryEntryEntity(
            id = existing?.id ?: 0,
            kind = kind.name,
            memoryKey = obs.first().key,
            label = if (userOwned) existing!!.label else obs.maxBy { it.at }.label.take(60),
            confidence = if (userOwned) 1f else confidence,
            firstObservedAt = minOf(existing?.firstObservedAt ?: Long.MAX_VALUE, times.first()),
            lastObservedAt = maxOf(existing?.lastObservedAt ?: 0L, last),
            observations = times.size,
            expiresAt = if (userOwned) null else kind.ttlDays?.let { last + it * DAY },
            origin = existing?.origin ?: ORIGIN_LEARNED,
            state = existing?.state ?: STATE_ACTIVE,
            detailJson = newDetail.toString(),
            updatedAt = nowMillis
        )
    }

    /** Median gap in days when the gaps are regular (weekly/monthly-ish), else null. */
    fun cadence(times: List<Long>): Int? {
        if (times.size < 2) return null
        val gaps = times.zipWithNext { a, b -> ((b - a) / DAY).toInt() }.filter { it >= 1 }
        if (gaps.isEmpty()) return null
        val median = gaps.sorted()[gaps.size / 2]
        if (median !in 6..35) return null
        return if (gaps.all { abs(it - median) <= maxOf(2, median / 5) }) median else null
    }

    /** Explicit preferences mirrored from EPIC-012 corrections, so memory reflects user settings. */
    fun preferenceEntry(subjectKey: String, label: String, preference: String, nowMillis: Long, existing: MemoryEntryEntity?): MemoryEntryEntity? {
        if (existing?.state == STATE_FORGOTTEN) return null
        return MemoryEntryEntity(
            id = existing?.id ?: 0, kind = Kind.PREFERENCE.name, memoryKey = subjectKey, label = "$label: ${preference.lowercase().replace('_', ' ')}",
            confidence = 1f, firstObservedAt = existing?.firstObservedAt ?: nowMillis, lastObservedAt = nowMillis, observations = 1, expiresAt = null,
            origin = ORIGIN_USER, state = existing?.state ?: STATE_ACTIVE, detailJson = JSONObject().put("preference", preference).toString(), updatedAt = nowMillis
        )
    }

    fun eventIds(entry: MemoryEntryEntity): List<Long> =
        runCatching { JSONObject(entry.detailJson).optJSONArray("events").longs() }.getOrDefault(emptyList())

    fun key(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun JSONArray?.longs(): List<Long> = if (this == null) emptyList() else (0 until length()).map { optLong(it) }

    const val ORIGIN_LEARNED = "LEARNED"
    const val ORIGIN_USER = "USER"
    const val STATE_ACTIVE = "ACTIVE"
    const val STATE_CORRECTED = "CORRECTED"
    const val STATE_FORGOTTEN = "FORGOTTEN"
    private val MONEY = setOf("PAYMENTS", "BANKING", "BILLS")
    private val RECURRING_EVENT_CATEGORIES = setOf("WORK", "REMINDERS", "BILLS")
    private const val MAX_TIMES = 24
    private const val MAX_EVENTS = 20
    private const val DAY = 24 * 60 * 60 * 1000L
}
