package com.marksy.os.capture

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.marksy.os.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Shared and picked screenshots (EPIC-037): decoded in memory, hashed, recognized on the phone, released.
 * Nothing is written to storage; the evidence is the SHA-256 of the image bytes.
 */
class ImageIntake(
    private val gateway: CaptureGateway,
    private val recognizer: TextRecognizer,
    private val decode: (ByteArray) -> Bitmap? = ImageBytes::decode,
    private val log: (String) -> Unit = {}
) {
    suspend fun intake(resolver: ContentResolver, uri: Uri, method: CaptureMethod, sourceHint: String? = null): CaptureOutcome {
        val bytes = withContext(Dispatchers.IO) { runCatching { resolver.openInputStream(uri)?.use { ImageBytes.read(it) } }.getOrNull() }
        return submit(bytes, method, sourceHint)
    }

    /** [sourceHint] is Android package identity (a sender that shared it), never a guess, so a hint counts as verified. */
    suspend fun submit(bytes: ByteArray?, method: CaptureMethod, sourceHint: String?): CaptureOutcome {
        val bitmap = bytes?.let { withContext(Dispatchers.Default) { runCatching { decode(it) }.getOrNull() } }
            ?: return gateway.submitFailure(method, null, CaptureFailure.IMAGE_UNREADABLE)
        val ocr = try {
            recognizer.recognize(bitmap)
        } catch (e: Exception) {
            log("capture intake method=$method ocr-failed (${e.javaClass.simpleName})")
            return gateway.submitFailure(method, null, CaptureFailure.CAPTURE_FAILED)
        } finally {
            bitmap.recycle()
        }
        return gateway.submitRecognized(method, sourceHint, sourceHint != null, ImageBytes.sha256(bytes), ocr.text, ocr.meanConfidence, null)
    }
}

object ImageBytes {
    const val MAX_BYTES = 32 * 1024 * 1024

    /** The whole stream in memory, or null when empty or larger than [maxBytes]. */
    fun read(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A software bitmap with the long edge at most [OcrScale.MAX_EDGE], or null when the bytes are not an image. */
    fun decode(bytes: ByteArray): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = OcrScale.fit(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = OcrScale.sampleSize(bounds.outWidth, bounds.outHeight) })
    }
}

object ShareIntake {
    fun isImageShare(action: String?, type: String?): Boolean = action == Intent.ACTION_SEND && type?.startsWith("image/") == true

    // Screenshot UIs and galleries are not the tip's source, so only an identity the sender chose to share counts.
    fun sourceHint(launchedFromPackage: String?, ownPackage: String): String? =
        launchedFromPackage?.trim()?.takeIf { it.isNotEmpty() && it != ownPackage }
}

/** Where a capture outcome leads in [MainActivity]: a candidate review or a fixed notice code. */
object CaptureRouting {
    fun reviewCandidate(outcome: CaptureOutcome?): Long? = when (outcome) {
        is CaptureOutcome.Candidate -> outcome.id
        is CaptureOutcome.Duplicate -> outcome.existingCandidateId
        else -> null
    }

    fun notice(outcome: CaptureOutcome?): String? = when (outcome) {
        is CaptureOutcome.Failed -> outcome.code
        CaptureOutcome.NotATip -> CaptureFailure.NOT_A_TIP
        else -> null
    }

    fun mainIntent(context: Context, outcome: CaptureOutcome?): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply {
            reviewCandidate(outcome)?.let { putExtra(MainActivity.EXTRA_REVIEW_CANDIDATE, it) }
            notice(outcome)?.let { putExtra(MainActivity.EXTRA_CAPTURE_NOTICE, it) }
        }
}
