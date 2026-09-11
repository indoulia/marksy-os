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
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty().trim()
        val lines = extras.getCharSequenceArray("android.textLines")
            ?.map { it.toString().trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        // Preserve useful secondary text that some banking/broker notifications
        // place in android.subText. Duplicate fragments are removed before truncation.
        return listOf(text, subText, bigText, lines.joinToString("\n"))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .take(MAX_BODY_LENGTH)
    }
}
