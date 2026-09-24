package com.marksy.os.ai

import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * EPIC-019 provider-independent AI layer. The app talks only to [IntelligenceService]; models are
 * swappable [LocalModel]s. Every call has a deterministic fallback, output is schema-validated,
 * and a provider that is not on-device is refused unless external processing is explicitly allowed.
 */
enum class AiTask { INTERPRET_QUERY, CLASSIFY, EXTRACT, SUMMARIZE }

enum class ModelState { NOT_INSTALLED, DOWNLOADING, READY, FAILED, DISABLED }

data class ModelInfo(
    val id: String,
    val version: String,
    val tasks: Set<AiTask>,
    /** False means data would leave the device; such models need explicit user permission. */
    val onDevice: Boolean
)

interface LocalModel {
    val info: ModelInfo
    /** Capability detection: must be cheap and must not load the model. */
    fun state(): ModelState
    /** Returns raw JSON text; the service validates it. */
    suspend fun generate(prompt: String, task: AiTask): String
}

/** Versioned prompt template with {{name}} placeholders and the schema its output must satisfy. */
data class PromptTemplate(val id: String, val version: Int, val task: AiTask, val text: String, val schema: JsonSchema) {
    fun render(values: Map<String, String>): String =
        PLACEHOLDER.replace(text) { m -> values[m.groupValues[1]]?.let(::sanitize) ?: "" }

    private companion object {
        val PLACEHOLDER = Regex("\\{\\{(\\w+)}}")
        // Prompt-injection hygiene: user/notification text cannot close the template's delimiters.
        fun sanitize(v: String) = v.replace("{{", "").replace("}}", "").replace("\"\"\"", "\"").take(2000)
    }
}

/** Minimal JSON schema: required typed fields, optional enums and numeric bounds. */
data class JsonSchema(val fields: Map<String, FieldSpec>) {
    enum class Type { STRING, NUMBER, BOOLEAN, STRING_ARRAY }
    data class FieldSpec(val type: Type, val required: Boolean = true, val enum: Set<String>? = null, val min: Double? = null, val max: Double? = null)

    /** Returns the parsed object or a list of violations. */
    fun validate(raw: String): Result<JSONObject> {
        val o = runCatching { JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }
            .getOrElse { return Result.failure(IllegalArgumentException("not a JSON object")) }
        val errors = mutableListOf<String>()
        fields.forEach { (name, spec) ->
            if (!o.has(name) || o.isNull(name)) {
                if (spec.required) errors += "$name missing"
                return@forEach
            }
            val v = o.get(name)
            when (spec.type) {
                Type.STRING -> if (v !is String) errors += "$name not string" else if (spec.enum != null && v !in spec.enum) errors += "$name not allowed"
                Type.NUMBER -> {
                    val d = (v as? Number)?.toDouble()
                    if (d == null) errors += "$name not number"
                    else if ((spec.min != null && d < spec.min) || (spec.max != null && d > spec.max)) errors += "$name out of range"
                }
                Type.BOOLEAN -> if (v !is Boolean) errors += "$name not boolean"
                Type.STRING_ARRAY -> if (v !is JSONArray || (0 until v.length()).any { v.opt(it) !is String }) errors += "$name not string array"
            }
        }
        return if (errors.isEmpty()) Result.success(o) else Result.failure(IllegalArgumentException(errors.joinToString()))
    }
}

/** Invocation record without any prompt/notification content (privacy). */
data class AiInvocation(
    val task: AiTask,
    val modelId: String?,
    val modelVersion: String?,
    val templateId: String,
    val templateVersion: Int,
    val outcome: Outcome,
    val latencyMs: Long,
    val confidence: Double?,
    val at: Long
) {
    enum class Outcome { OK, NO_MODEL, BLOCKED_EXTERNAL, TIMEOUT, ERROR, INVALID_OUTPUT, LOW_CONFIDENCE }
}

fun interface AiInvocationSink { suspend fun record(invocation: AiInvocation) }

class IntelligenceService(
    private val models: List<LocalModel>,
    private val allowExternal: () -> Boolean = { false },
    private val sink: AiInvocationSink = AiInvocationSink { },
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val minConfidence: Double = DEFAULT_MIN_CONFIDENCE
) {
    data class Outcome<T>(val value: T, val usedModel: ModelInfo?, val invocation: AiInvocation)

    /** Models that are installed and permitted, for status/privacy UI. */
    fun status(): List<Pair<ModelInfo, ModelState>> = models.map { m ->
        val s = runCatching { m.state() }.getOrDefault(ModelState.FAILED)
        m.info to if (!m.info.onDevice && !allowExternal() && s == ModelState.READY) ModelState.DISABLED else s
    }

    fun available(task: AiTask): ModelInfo? = pick(task)?.info

    /**
     * Runs [template] on the best ready model; [parse] maps validated JSON to T. Any failure,
     * timeout, invalid output, or confidence below threshold returns [fallback] instead.
     */
    suspend fun <T> run(template: PromptTemplate, values: Map<String, String>, parse: (JSONObject) -> T?, fallback: () -> T): Outcome<T> {
        val start = clock()
        val model = pick(template.task)
        fun record(outcome: AiInvocation.Outcome, confidence: Double? = null) = AiInvocation(
            template.task, model?.info?.id, model?.info?.version, template.id, template.version, outcome, clock() - start, confidence, start
        )
        if (model == null) {
            val blocked = models.any { template.task in it.info.tasks && !it.info.onDevice && runCatching { it.state() }.getOrNull() == ModelState.READY }
            return done(fallback(), null, record(if (blocked) AiInvocation.Outcome.BLOCKED_EXTERNAL else AiInvocation.Outcome.NO_MODEL))
        }
        val raw = try {
            withTimeoutOrNull(timeoutMs) { model.generate(template.render(values), template.task) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return done(fallback(), null, record(AiInvocation.Outcome.ERROR))
        } ?: return done(fallback(), null, record(AiInvocation.Outcome.TIMEOUT))

        val obj = template.schema.validate(raw).getOrElse { return done(fallback(), null, record(AiInvocation.Outcome.INVALID_OUTPUT)) }
        val confidence = obj.optDouble("confidence", Double.NaN).takeIf { !it.isNaN() }
        if (confidence == null || confidence < minConfidence) return done(fallback(), null, record(AiInvocation.Outcome.LOW_CONFIDENCE, confidence))
        val value = runCatching { parse(obj) }.getOrNull() ?: return done(fallback(), null, record(AiInvocation.Outcome.INVALID_OUTPUT, confidence))
        return done(value, model.info, record(AiInvocation.Outcome.OK, confidence))
    }

    private suspend fun <T> done(value: T, model: ModelInfo?, inv: AiInvocation): Outcome<T> {
        runCatching { sink.record(inv) }
        return Outcome(value, model, inv)
    }

    private fun pick(task: AiTask): LocalModel? = models
        .filter { task in it.info.tasks && (it.info.onDevice || allowExternal()) }
        .firstOrNull { runCatching { it.state() }.getOrNull() == ModelState.READY }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val DEFAULT_MIN_CONFIDENCE = 0.6
    }
}
