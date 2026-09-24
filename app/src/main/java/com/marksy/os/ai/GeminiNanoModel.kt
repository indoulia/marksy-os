package com.marksy.os.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Seam over the platform runtime so the adapter's lifecycle logic is testable without AICore. */
interface PromptBackend {
    enum class Availability { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

    val runtimeVersion: String
    suspend fun availability(): Availability
    suspend fun modelName(): String?
    /** Suspends until the runtime reports the download finished; throws if it failed. */
    suspend fun download()
    suspend fun warmup()
    suspend fun generate(prompt: String): String?
}

/**
 * EPIC-019 on-device model: Gemini Nano via ML Kit GenAI Prompt API, executed by Android AICore.
 * The model is managed by the OS (nothing ships in the APK) and only exists on AICore-capable
 * devices; everywhere else the state is NOT_AVAILABLE and callers use their deterministic fallback.
 */
class GeminiNanoModel(
    private val backend: PromptBackend,
    private val clock: () -> Long = System::currentTimeMillis,
    private val statusTtlMs: Long = STATUS_TTL_MS
) : LocalModel {
    @Volatile private var state = ModelState.UNKNOWN
    @Volatile private var checkedAt: Long? = null
    @Volatile private var modelName: String? = null
    @Volatile private var initMs: Long? = null
    @Volatile private var lastLatencyMs: Long? = null
    @Volatile private var lastError: String? = null
    private val calls = AtomicInteger()
    private val failures = AtomicInteger()

    override val info: ModelInfo
        get() = ModelInfo(ID, modelName ?: "unknown", setOf(AiTask.INTERPRET_QUERY), onDevice = true, runtime = backend.runtimeVersion)

    override fun state(): ModelState = state

    override suspend fun refresh(): ModelState {
        val last = checkedAt
        if (state == ModelState.DOWNLOADING || (state != ModelState.UNKNOWN && last != null && clock() - last < statusTtlMs)) return state
        state = try {
            val probed = withContext(Dispatchers.Default) { backend.availability() }
            if (probed == PromptBackend.Availability.AVAILABLE && modelName == null) {
                modelName = runCatching { withContext(Dispatchers.Default) { backend.modelName() } }.getOrNull()?.take(60)
            }
            when (probed) {
                PromptBackend.Availability.UNAVAILABLE -> ModelState.NOT_AVAILABLE
                PromptBackend.Availability.DOWNLOADABLE -> ModelState.NOT_INSTALLED
                PromptBackend.Availability.DOWNLOADING -> ModelState.DOWNLOADING
                PromptBackend.Availability.AVAILABLE -> ModelState.READY
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed(e)
            ModelState.FAILED
        }
        checkedAt = clock()
        return state
    }

    override suspend fun prepare(): ModelState {
        if (state == ModelState.UNKNOWN || state == ModelState.FAILED) { checkedAt = null; refresh() }
        try {
            if (state == ModelState.NOT_INSTALLED) {
                state = ModelState.DOWNLOADING
                withContext(Dispatchers.Default) { backend.download() }
                state = ModelState.UNKNOWN
                refresh()
            }
            if (state == ModelState.READY && initMs == null) {
                val start = clock()
                withContext(Dispatchers.Default) { backend.warmup() }
                initMs = clock() - start
            }
        } catch (e: Exception) {
            if (e is CancellationException) { if (state == ModelState.DOWNLOADING) state = ModelState.UNKNOWN; throw e }
            failed(e)
            state = ModelState.FAILED
            checkedAt = clock()
        }
        return state
    }

    override suspend fun generate(prompt: String, task: AiTask): String {
        check(state == ModelState.READY) { "model not ready" }
        val start = clock()
        calls.incrementAndGet()
        try {
            val text = withContext(Dispatchers.Default) { backend.generate(prompt) }
            lastLatencyMs = clock() - start
            return firstJsonObject(text) ?: throw IllegalStateException("no JSON object in output")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            lastLatencyMs = clock() - start
            failed(e)
            checkedAt = null // the runtime may have dropped the model; re-probe on the next refresh
            throw e
        }
    }

    override fun diagnostics() = ModelDiagnostics(
        supportsLocalInference = state == ModelState.READY,
        modelVersion = modelName,
        runtimeVersion = backend.runtimeVersion,
        initMs = initMs,
        lastLatencyMs = lastLatencyMs,
        calls = calls.get(),
        failures = failures.get(),
        lastError = lastError
    )

    // Only the exception type is kept: runtime messages could echo prompt text.
    private fun failed(e: Exception) {
        failures.incrementAndGet()
        lastError = e.javaClass.simpleName
    }

    companion object {
        const val ID = "gemini-nano-aicore"
        const val STATUS_TTL_MS = 60_000L

        /** Small models often wrap JSON in prose or fences; the schema validator then decides. */
        fun firstJsonObject(text: String?): String? {
            if (text == null) return null
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start in 0 until end) text.substring(start, end + 1) else null
        }
    }
}

/** Thin ML Kit binding; the client is created lazily so merely registering the model costs nothing. */
class MlKitPromptBackend : PromptBackend {
    private val model: GenerativeModel by lazy { Generation.getClient() }

    override val runtimeVersion = "mlkit-genai-prompt/1.0.0-beta4"

    override suspend fun availability() = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> PromptBackend.Availability.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> PromptBackend.Availability.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> PromptBackend.Availability.DOWNLOADING
        else -> PromptBackend.Availability.UNAVAILABLE
    }

    override suspend fun modelName(): String? = model.getBaseModelName()

    override suspend fun download() {
        val end = model.download().first { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed }
        if (end is DownloadStatus.DownloadFailed) throw end.e
    }

    override suspend fun warmup() = model.warmup()

    override suspend fun generate(prompt: String): String? {
        val request = generateContentRequest(TextPart(prompt)) {
            // Deterministic decoding: interpretation should not vary between identical questions.
            temperature = 0f
            topK = 1
            maxOutputTokens = 200
        }
        return model.generateContent(request).candidates.firstOrNull()?.text
    }
}
