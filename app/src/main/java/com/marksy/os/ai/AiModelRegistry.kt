package com.marksy.os.ai

import com.marksy.os.data.local.AiInvocationDao
import com.marksy.os.data.local.AiInvocationEntity

/**
 * Where on-device model providers are registered. None ships today: an adapter (e.g. an Android
 * system model or a bundled runtime) implements [LocalModel] and is added here; nothing else in
 * the app changes. Until then every AI task uses its deterministic fallback, and the UI says so.
 */
object AiModelRegistry {
    fun installed(): List<LocalModel> = emptyList()
}

class RoomAiInvocationSink(private val dao: AiInvocationDao) : AiInvocationSink {
    override suspend fun record(invocation: AiInvocation) {
        dao.insert(
            AiInvocationEntity(
                task = invocation.task.name, modelId = invocation.modelId, modelVersion = invocation.modelVersion,
                templateId = invocation.templateId, templateVersion = invocation.templateVersion, outcome = invocation.outcome.name,
                latencyMs = invocation.latencyMs, confidence = invocation.confidence, at = invocation.at
            )
        )
    }
}
