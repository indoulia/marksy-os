package com.marksy.os.ai

import com.marksy.os.intelligence.AskMarksy
import com.marksy.os.intelligence.EventExtractor
import org.json.JSONObject
import java.time.ZoneId

/** Versioned templates. Bump [PromptTemplate.version] whenever text or schema changes. */
object PromptTemplates {
    val ASK_INTERPRET = PromptTemplate(
        id = "ask.interpret",
        version = 2,
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
            if (service.available(AiTask.INTERPRET_QUERY) == null) return null
        }
        val outcome = service.run(
            PromptTemplates.ASK_INTERPRET,
            mapOf("question" to text, "previous" to (previous?.intent?.name ?: "none")),
            parse = { o -> toQuery(o, text, previous, nowMillis, zone) },
            fallback = { null }
        )
        return outcome.value
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
        val subject = if (o.has("subject") && !o.isNull("subject")) o.getString("subject").trim().lowercase().take(60).ifBlank { null }
            ?.takeIf { s -> s.split(' ').all { w -> text.contains(w, ignoreCase = true) } } else null
        return base.copy(intent = intent, subject = subject ?: base.subject, direction = direction ?: base.direction, rawText = text)
    }

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
