package com.marksy.os.ai

import com.marksy.os.intelligence.AskMarksy
import com.marksy.os.intelligence.EventExtractor
import org.json.JSONObject
import java.time.ZoneId

/** Versioned templates. Bump [PromptTemplate.version] whenever text or schema changes. */
object PromptTemplates {
    val ASK_INTERPRET = PromptTemplate(
        id = "ask.interpret",
        version = 3,
        task = AiTask.INTERPRET_QUERY,
        text = """
            You map a user's question about their own phone notifications to a query. Do not answer it.
            Reply with JSON only: {"intent": one of ${AskMarksy.Intent.entries.joinToString("|")},
            "range": one of today|yesterday|tomorrow|this_week|last_week|this_month|last_month|default,
            "subject": the person, merchant, app or search words named in the question, or null, "direction": DEBIT|CREDIT|null, "confidence": 0..1}.
            Never add names or words that are not in the question.
            Example: "what did I pay Amazon last month" -> {"intent":"PAYMENTS","range":"last_month","subject":"amazon","direction":"DEBIT","confidence":0.9}
            Previous query intent: {{previous}}
            Question: {{question}}
        """.trimIndent(),
        schema = JsonSchema(
            mapOf(
                "intent" to JsonSchema.FieldSpec(JsonSchema.Type.STRING, enum = AskMarksy.Intent.entries.map { it.name }.toSet()),
                "range" to JsonSchema.FieldSpec(JsonSchema.Type.STRING, enum = setOf("today", "yesterday", "tomorrow", "this_week", "last_week", "this_month", "last_month", "default")),
                "subject" to JsonSchema.FieldSpec(JsonSchema.Type.STRING, required = false),
                "direction" to JsonSchema.FieldSpec(JsonSchema.Type.STRING, required = false, enum = setOf("DEBIT", "CREDIT")),
                "confidence" to JsonSchema.FieldSpec(JsonSchema.Type.NUMBER, min = 0.0, max = 1.0)
            )
        )
    )

    val ALL = listOf(ASK_INTERPRET)
}

/**
 * Ask Marksy interpretation through the AI layer. The model only picks intent/range/subject;
 * the time range itself is resolved deterministically and all facts still come from retrieval.
 */
class ModelQueryInterpreter(private val service: IntelligenceService) : AskMarksy.QueryInterpreter {
    override val name = "on-device-model"

    override suspend fun interpret(text: String, previous: AskMarksy.Query?, nowMillis: Long, zone: ZoneId): AskMarksy.Query? {
        if (service.available(AiTask.INTERPRET_QUERY) == null) {
            service.refresh()
            if (service.available(AiTask.INTERPRET_QUERY) == null) {
                DiagLog.i(TAG, "interpret: no local model; deterministic fallback")
                return null
            }
        }
        val outcome = service.run(
            PromptTemplates.ASK_INTERPRET,
            mapOf("question" to text, "previous" to (previous?.intent?.name ?: "none")),
            parse = { o -> toQuery(o, text, previous, nowMillis, zone) },
            fallback = { null }
        )
        val q = outcome.value
        // Keyword-backed wording is explicit user intent; a model that contradicts it is not trusted.
        val deterministic = AskMarksy.parse(text, previous, nowMillis, zone).intent
        if (q != null && deterministic != AskMarksy.Intent.SEARCH && deterministic != q.intent) {
            DiagLog.i(TAG, "interpret: model intent ${q.intent} contradicts $deterministic; deterministic fallback")
            return null
        }
        DiagLog.i(TAG, "interpret: outcome=${outcome.invocation.outcome} latencyMs=${outcome.invocation.latencyMs} intent=${q?.intent}")
        return q
    }

    internal fun toQuery(o: JSONObject, text: String, previous: AskMarksy.Query?, nowMillis: Long, zone: ZoneId): AskMarksy.Query {
        val intent = AskMarksy.Intent.valueOf(o.getString("intent"))
        val rangeWords = when (o.getString("range")) {
            "today" -> "today"; "yesterday" -> "yesterday"; "tomorrow" -> "tomorrow"
            "this_week" -> "this week"; "last_week" -> "last week"; "this_month" -> "this month"; "last_month" -> "last month"
            else -> ""
        }
        // Reuse the deterministic range resolver so a model can't invent a time window.
        val base = AskMarksy.parse("$rangeWords ${intentHint(intent)}".trim(), previous, nowMillis, zone)
        val direction = o.optString("direction").takeIf { it.isNotBlank() && o.has("direction") && !o.isNull("direction") }
            ?.let { EventExtractor.Direction.valueOf(it) }
        // A subject the user never typed is dropped: it could only narrow results, but it would misreport what was searched.
        val typed = text.lowercase().split(Regex("[^\\p{L}\\p{N}&'.-]+")).filter { it.isNotBlank() }.toSet()
        val subject = if (o.has("subject") && !o.isNull("subject")) o.getString("subject").trim().lowercase().take(60).ifBlank { null }
            ?.takeIf { s -> s.split(Regex("\\s+")).all { w -> w in typed } } else null
        // Same for the window: a range the user typed explicitly always beats the model's choice.
        val range = AskMarksy.explicitRange(text, nowMillis, zone) ?: base.range
        // A model that omits the merchant/person the user typed must not silently widen the answer.
        val typedQuery = AskMarksy.parse(text, previous, nowMillis, zone).takeIf { it.intent == intent }
        // Channel and page/stock target are only ever taken from the typed words.
        val typedAny = AskMarksy.parse(text, previous, nowMillis, zone)
        return base.copy(
            intent = intent, range = range, rawText = text,
            subject = if (intent == AskMarksy.Intent.SOURCE) typedQuery?.subject else subject ?: typedQuery?.subject ?: base.subject,
            direction = typedQuery?.direction ?: direction ?: base.direction,
            channel = typedAny.channel ?: subject.takeIf { intent == AskMarksy.Intent.SOURCE },
            target = typedQuery?.target
        )
    }

    private companion object { const val TAG = "MarksyAsk" }

    private fun intentHint(i: AskMarksy.Intent) = when (i) {
        AskMarksy.Intent.PAYMENTS -> "payments"
        AskMarksy.Intent.DELIVERIES -> "deliveries"
        AskMarksy.Intent.BILLS_DUE -> "bills"
        AskMarksy.Intent.IMPORTANT -> "important"
        AskMarksy.Intent.MISSED -> "missed"
        AskMarksy.Intent.TRADING -> "trading"
        else -> ""
    }
}
