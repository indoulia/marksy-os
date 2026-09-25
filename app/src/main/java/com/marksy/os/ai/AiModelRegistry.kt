package com.marksy.os.ai

import com.marksy.os.data.local.AiInvocationDao
import com.marksy.os.data.local.AiInvocationEntity

/**
 * Where on-device model providers are registered; adding one here is the only change a new runtime
 * needs. Models are process singletons so probed state and diagnostics survive across screens.
 */
object AiModelRegistry {
    @Volatile private var appContext: android.content.Context? = null
    // Nano first: when AICore has it, it's the faster model.
    private val models: List<LocalModel> by lazy {
        listOf(GeminiNanoModel(MlKitPromptBackend()), GeminiNanoModel(MediaPipeGemmaBackend { appContext }, id = MediaPipeGemmaBackend.ID))
    }

    /** Gemma reads its imported file from app storage, so it needs the application context. */
    fun bind(context: android.content.Context) { appContext = context.applicationContext }

    fun installed(): List<LocalModel> = models
}

class RoomAiInvocationSink(private val dao: AiInvocationDao, private val metrics: com.marksy.os.data.MetricsRecorder? = null) : AiInvocationSink {
    override suspend fun record(invocation: AiInvocation) {
        metrics?.let { m ->
            val scope = "ai:${invocation.task.name}"
            m.count(com.marksy.os.data.Metric.AI_CALL, scope)
            m.count(com.marksy.os.data.Metric.AI_LATENCY_MS_SUM, scope, delta = invocation.latencyMs)
            if (invocation.outcome != AiInvocation.Outcome.OK) m.count(com.marksy.os.data.Metric.AI_FAILURE, scope)
        }
        dao.insert(
            AiInvocationEntity(
                task = invocation.task.name, modelId = invocation.modelId, modelVersion = invocation.modelVersion,
                templateId = invocation.templateId, templateVersion = invocation.templateVersion, outcome = invocation.outcome.name,
                latencyMs = invocation.latencyMs, confidence = invocation.confidence, at = invocation.at
            )
        )
    }
}
