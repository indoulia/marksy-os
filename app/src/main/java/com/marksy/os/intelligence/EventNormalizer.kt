package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * Stable normalized event contract (EPIC-010). Every downstream consumer (inbox, briefing,
 * Ask Marksy, rules) should read this rather than re-parsing raw notification text.
 */
data class NormalizedEvent(
    val eventId: Long,
    val sourcePackage: String,
    val sourceName: String,
    val category: String,
    val occurredAt: Long,
    val facts: EventExtractor.Facts,
    val importance: Int,
    val confidence: Float,
    val threadKey: String,
    /** Null when the event carries nothing that could identify the same real-world item elsewhere. */
    val correlationKey: String?,
    /** Weak keys (amount-only) may only match a *different* source; strong keys (txn ref) match any. */
    val correlationIsStrong: Boolean,
    val reasons: List<String>
)

object EventNormalizer {
    const val REF_THREAD_PREFIX = "ref|"
    private val MONEY_CATEGORIES = setOf("BANKING", "PAYMENTS", "BILLS", "REMINDERS")

    fun normalize(event: NotificationEventEntity, zone: ZoneId = ZoneId.systemDefault()): NormalizedEvent {
        val facts = EventExtractor.extract(
            event.sourcePackage, event.sourceName, event.category, event.title, event.body, event.postedAt, zone
        )
        val importance = EventIntelligence.importance(event)
        val reasons = importance.reasons.toMutableList()

        val threadRef = facts.threadReference
        val txnRef = facts.transactionReference
        val threadKey = when {
            threadRef != null -> {
                reasons += "Threaded by ${threadRef.type.name.lowercase()} id ${threadRef.value}"
                "$REF_THREAD_PREFIX${threadRef.type.name.lowercase()}|${threadRef.value}"
            }
            txnRef != null && !event.isTrading -> {
                reasons += "Threaded by transaction reference ${txnRef.value}"
                "${REF_THREAD_PREFIX}transaction|${txnRef.value}"
            }
            else -> EventIntelligence.legacyThreadKey(event)
        }

        val amount = facts.primaryAmount
        // Trading observations are deliberately source-independent (see EventFingerprint), so never correlate them.
        val (correlationKey, strong) = when {
            event.isTrading -> null to false
            txnRef != null -> "txn|${txnRef.value}" to true
            amount != null && event.category in MONEY_CATEGORIES && amount.direction != EventExtractor.Direction.UNKNOWN ->
                "amt|${amount.direction.name}|${amount.amountMinor}|${amount.currency}" to false
            else -> null to false
        }

        amount?.let { reasons += "Amount ${it.raw} (${it.direction.name.lowercase()})" }
        facts.times.firstOrNull()?.let { reasons += "Mentions time \"${it.raw}\"" }

        // Structured facts that agree with the category corroborate the classifier; nothing ever lowers it.
        val corroborated = when (event.category) {
            in MONEY_CATEGORIES -> amount != null || txnRef != null
            "DELIVERY" -> threadRef != null || facts.entities.any { it.type == EventExtractor.EntityType.DELIVERY }
            "TRADING" -> facts.entities.any { it.type == EventExtractor.EntityType.STOCK }
            else -> false
        }
        val confidence = (event.confidence + if (corroborated) CORROBORATION_BONUS else 0f).coerceIn(0f, 1f)
        if (corroborated) reasons += "Category corroborated by extracted details"

        return NormalizedEvent(
            eventId = event.id,
            sourcePackage = event.sourcePackage,
            sourceName = event.sourceName,
            category = event.category,
            occurredAt = event.postedAt,
            facts = facts,
            importance = importance.attentionScore,
            confidence = confidence,
            threadKey = threadKey,
            correlationKey = correlationKey,
            correlationIsStrong = strong,
            reasons = reasons.distinct()
        )
    }

    /** Bounded JSON for [NotificationEventEntity.intelligenceJson]; lists are capped to keep rows small. */
    fun toJson(n: NormalizedEvent, duplicateOfId: Long?): String {
        val f = n.facts
        return JSONObject()
            .put("v", EventIntelligencePipeline.VERSION)
            .put("entities", JSONArray().apply {
                f.entities.take(MAX_ITEMS).forEach {
                    put(JSONObject().put("type", it.type.name).put("value", it.value).put("confidence", it.confidence.toDouble()).put("signal", it.signal))
                }
            })
            .put("amounts", JSONArray().apply {
                f.amounts.take(MAX_ITEMS).forEach {
                    put(JSONObject().put("minor", it.amountMinor).put("currency", it.currency).put("direction", it.direction.name).put("raw", it.raw))
                }
            })
            .put("references", JSONArray().apply {
                f.references.take(MAX_ITEMS).forEach { put(JSONObject().put("type", it.type.name).put("value", it.value)) }
            })
            .put("times", JSONArray().apply {
                f.times.take(MAX_ITEMS).forEach { put(JSONObject().put("at", it.epochMillis).put("raw", it.raw).put("hasTime", it.hasTime)) }
            })
            .put("terminal", f.terminal)
            .put("reasons", JSONArray().apply { n.reasons.take(MAX_ITEMS).forEach { put(it) } })
            .apply { if (duplicateOfId != null) put("duplicateOf", duplicateOfId) }
            .toString()
    }

    /** Tolerant reader: malformed or missing JSON yields EMPTY facts, never an exception. */
    fun factsFromJson(json: String?): EventExtractor.Facts {
        if (json.isNullOrBlank()) return EventExtractor.Facts.EMPTY
        return runCatching {
            val o = JSONObject(json)
            EventExtractor.Facts(
                entities = o.optJSONArray("entities").objects().mapNotNull { e ->
                    val type = enumOrNull<EventExtractor.EntityType>(e.optString("type")) ?: return@mapNotNull null
                    EventExtractor.Entity(type, e.optString("value"), e.optDouble("confidence", 0.0).toFloat(), e.optString("signal"))
                },
                amounts = o.optJSONArray("amounts").objects().map { a ->
                    EventExtractor.Money(
                        a.optLong("minor"), a.optString("currency"),
                        enumOrNull<EventExtractor.Direction>(a.optString("direction")) ?: EventExtractor.Direction.UNKNOWN,
                        a.optString("raw")
                    )
                },
                references = o.optJSONArray("references").objects().mapNotNull { r ->
                    val type = enumOrNull<EventExtractor.ReferenceType>(r.optString("type")) ?: return@mapNotNull null
                    EventExtractor.Reference(type, r.optString("value"))
                },
                times = o.optJSONArray("times").objects().map { t ->
                    EventExtractor.TimeMention(t.optLong("at"), t.optString("raw"), t.optBoolean("hasTime"))
                },
                terminal = o.optBoolean("terminal")
            )
        }.getOrDefault(EventExtractor.Facts.EMPTY)
    }

    fun reasonsFromJson(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONObject(json).optJSONArray("reasons") ?: return emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private const val CORROBORATION_BONUS = 0.05f
    private const val MAX_ITEMS = 12
}
