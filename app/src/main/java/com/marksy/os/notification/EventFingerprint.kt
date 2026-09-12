package com.marksy.os.notification

import java.security.MessageDigest
import java.util.Locale

/**
 * Builds a source-aware semantic identity for notifications.
 *
 * The source is deliberately part of the fingerprint: the same trading event
 * reported by ICICI Direct, Zerodha and Upstox represents three independent
 * observations rather than one duplicate.
 */
object EventFingerprint {
    fun create(
        sourcePackage: String,
        category: String,
        title: String,
        body: String
    ): String {
        val source = normalize(sourcePackage)
        val semantic = normalizeSemantic("$title $body")
        return sha256(listOf(source, normalize(category), semantic).joinToString("|"))
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
