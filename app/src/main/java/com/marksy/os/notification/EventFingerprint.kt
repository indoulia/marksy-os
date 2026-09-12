package com.marksy.os.notification

import java.security.MessageDigest
import java.util.Locale

/**
 * Builds a source-aware semantic identity for notifications.
 *
 * The source is deliberately part of the fingerprint: the same trading event
 * reported by ICICI Direct, Zerodha and Upstox represents independent
 * observations rather than one duplicate.
 *
 * The fingerprint also contains a short time bucket. This prevents an
 * identical notification from the same source from suppressing a genuinely
 * new occurrence hours later while still collapsing rapid notification
 * updates/reposts of the same event.
 */
object EventFingerprint {
    const val DEDUP_WINDOW_MS = 5 * 60 * 1000L

    fun create(
        sourcePackage: String,
        category: String,
        title: String,
        body: String,
        occurredAt: Long = 0L
    ): String {
        val source = normalize(sourcePackage)
        val semantic = normalizeSemantic("$title $body")
        val timeBucket = if (occurredAt > 0L) occurredAt / DEDUP_WINDOW_MS else 0L
        return sha256(listOf(source, normalize(category), semantic, timeBucket).joinToString("|"))
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun normalizeSemantic(value: String): String =
        normalize(value)
            .replace(Regex("[₹$€£]"), " currency ")
            .replace(Regex("[,;|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
