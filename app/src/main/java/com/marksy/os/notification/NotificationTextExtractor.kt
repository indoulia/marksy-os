package com.marksy.os.notification

import android.os.Bundle

/**
 * Normalizes Android notification text before it is classified or persisted.
 * Keeping this logic independent from the listener makes the capture boundary
 * deterministic and easy to test.
 */
object NotificationTextExtractor {
    const val MAX_BODY_LENGTH = 4000

    fun extract(extras: Bundle): String {
        val text = extras.getCharSequence("android.text")?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty().trim()
        val lines = extras.getCharSequenceArray("android.textLines")
            ?.map { it.toString().trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return listOf(text, bigText, lines.joinToString("\n"))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .take(MAX_BODY_LENGTH)
    }
}
