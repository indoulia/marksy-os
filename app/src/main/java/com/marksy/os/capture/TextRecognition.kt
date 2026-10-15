package com.marksy.os.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class OcrResult(val text: String, val meanConfidence: Float?)

/** On-device text recognition; the bitmap stays owned (and released) by the caller. */
interface TextRecognizer {
    suspend fun recognize(bitmap: Bitmap): OcrResult
}

object OcrScale {
    const val MAX_EDGE = 2048

    /** The size with the long edge at most [maxEdge], aspect kept. */
    fun fit(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / long
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    /** Largest power-of-two decode sample that keeps the long edge at or above [maxEdge]. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        val long = maxOf(width, height)
        var sample = 1
        while (long / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    fun meanConfidence(values: List<Float>): Float? =
        values.filter { it in 0f..1f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
}

/** Bundled ML Kit Latin model: recognition runs on the phone, the image never leaves it. */
class MlKitTextRecognizer : TextRecognizer {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val (width, height) = OcrScale.fit(bitmap.width, bitmap.height)
        val input = if (width == bitmap.width && height == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val text = suspendCancellableCoroutine { cont ->
                client.process(InputImage.fromBitmap(input, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            return OcrResult(text.text, OcrScale.meanConfidence(text.textBlocks.flatMap { block -> block.lines.map { it.confidence } }))
        } finally {
            if (input !== bitmap) input.recycle()
        }
    }
}
