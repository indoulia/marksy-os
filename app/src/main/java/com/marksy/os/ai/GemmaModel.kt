package com.marksy.os.ai

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Where the imported Gemma `.task` lives, and how it gets there from the user's download. */
object GemmaModelFile {
    private const val NAME = "gemma3-1b-it-int4.task"
    // A real Gemma 3 1B bundle is ~550 MB; anything tiny is the wrong file.
    private const val MIN_BYTES = 50L * 1024 * 1024

    fun file(context: Context) = File(File(context.filesDir, "models"), NAME)

    fun isInstalled(context: Context) = file(context).let { it.isFile && it.length() >= MIN_BYTES }

    /** Copies a picked `.task`, or the `.task` inside Kaggle's `.tar.gz`, into app storage. */
    suspend fun import(context: Context, uri: Uri, onProgress: (Long) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = file(context).apply { parentFile?.mkdirs() }
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the selected file")
            input.use { extractTask(it, dest, onProgress) }
            if (dest.length() < MIN_BYTES) { dest.delete(); throw IOException("That file isn't a Gemma model") }
            dest
        }
    }

    /** Writes the model to [dest] atomically; throws (leaving no file) when a tar holds no `.task`. */
    internal fun extractTask(input: InputStream, dest: File, onProgress: (Long) -> Unit) {
        val tmp = File(dest.path + ".part")
        try {
            val buffered = BufferedInputStream(input)
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            tmp.outputStream().use { out ->
                if (!gzip) copy(buffered, out, Long.MAX_VALUE, onProgress)
                else if (!copyTaskFromTar(GZIPInputStream(buffered, 64 * 1024), out, onProgress)) throw IOException("No .task model in that archive")
            }
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save the model")
        } finally {
            tmp.delete()
        }
    }

    private fun copyTaskFromTar(tar: InputStream, out: java.io.OutputStream, onProgress: (Long) -> Unit): Boolean {
        val header = ByteArray(512)
        while (readFully(tar, header)) {
            if (header.all { it.toInt() == 0 }) return false
            val name = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000').trim()
            val size = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }.toLongOrNull(8) ?: 0L
            val padded = (size + 511) / 512 * 512
            if (name.endsWith(".task")) {
                copy(tar, out, size, onProgress)
                return true
            }
            skipFully(tar, padded)
        }
        return false
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, limit: Long, onProgress: (Long) -> Unit) {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n < 0) { if (limit == Long.MAX_VALUE) break else throw IOException("Model file is truncated") }
            out.write(buf, 0, n)
            total += n
            onProgress(total)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Archive is truncated")
            left -= n
        }
    }
}

/**
 * Gemma 3 1B through MediaPipe LLM Inference, for devices without AICore (e.g. the OnePlus 12R).
 * "Download" means the file was imported; the model loads on warm-up and stays resident.
 */
class MediaPipeGemmaBackend(private val context: () -> Context?) : PromptBackend {
    @Volatile private var engine: LlmInference? = null
    private val lock = Any()

    override val runtimeVersion = "mediapipe-tasks-genai/0.10.35"

    override suspend fun availability(): PromptBackend.Availability {
        val ctx = context() ?: return PromptBackend.Availability.UNAVAILABLE
        return if (GemmaModelFile.isInstalled(ctx)) PromptBackend.Availability.AVAILABLE else PromptBackend.Availability.DOWNLOADABLE
    }

    override suspend fun modelName(): String = "gemma3-1b-it-int4"

    override suspend fun download() {
        val ctx = context() ?: throw IllegalStateException("no context")
        if (!GemmaModelFile.isInstalled(ctx)) throw IllegalStateException("import the model file first")
    }

    override suspend fun warmup() { load() }

    override suspend fun generate(prompt: String): String? {
        val llm = load()
        // One native call at a time: a timed-out caller's inference may still be running.
        return synchronized(lock) {
            val options = LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).build()
            LlmInferenceSession.createFromOptions(llm, options).use { session ->
                session.addQueryChunk("<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n")
                session.generateResponse()
            }
        }
    }

    private fun load(): LlmInference = engine ?: synchronized(lock) {
        engine ?: run {
            val ctx = context() ?: throw IllegalStateException("no context")
            val path = GemmaModelFile.file(ctx).path
            fun build(backend: LlmInference.Backend) = LlmInference.createFromOptions(
                ctx, LlmInference.LlmInferenceOptions.builder().setModelPath(path).setMaxTokens(MAX_TOKENS).setPreferredBackend(backend).build()
            )
            // GPU is several times faster; phones without usable OpenCL fall back to CPU.
            runCatching { build(LlmInference.Backend.GPU) }.getOrElse { build(LlmInference.Backend.CPU) }
        }.also { engine = it }
    }

    companion object {
        const val ID = "gemma3-1b-mediapipe"
        private const val MAX_TOKENS = 1024
    }
}
