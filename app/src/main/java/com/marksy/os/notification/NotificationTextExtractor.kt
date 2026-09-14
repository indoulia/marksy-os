package com.marksy.os.notification

import android.os.Bundle

/**
 * Normalizes Android notification text before it is classified or persisted.
 * Keeping this logic independent from the listener makes the capture boundary
 * deterministic and easy to test.
 */
object NotificationTextExtractor {
    const val MAX_TITLE_LENGTH = 500
    const val MAX_BODY_LENGTH = 4000
    const val MAX_LINE_LENGTH = 1000
    const val MAX_LINE_COUNT = 50

    fun extractTitle(extras: Bundle): String =
        extras.getCharSequence("android.title")?.toString().orEmpty().trim().take(MAX_TITLE_LENGTH)

    fun extract(extras: Bundle): String {
        val text = extras.getCharSequence("android.text")?.toString().orEmpty().trim()
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty().trim()
        val lines = extras.getCharSequenceArray("android.textLines")
            ?.asSequence()
            ?.take(MAX_LINE_COUNT)
            ?.map { it.toString().trim().take(MAX_LINE_LENGTH) }
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()

        // Preserve useful secondary text that some banking/broker notifications
        // place in android.subText. Duplicate fragments (e.g. android.text repeated
        // inside android.textLines) are removed at the fragment level before truncation.
        return (listOf(text, subText, bigText) + lines)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .take(MAX_BODY_LENGTH)
    }
}
