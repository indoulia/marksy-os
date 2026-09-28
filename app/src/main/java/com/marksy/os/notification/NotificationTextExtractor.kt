package com.marksy.os.notification

import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable

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
        stripMarkup(extras.getCharSequence("android.title")?.toString().orEmpty()).trim().take(MAX_TITLE_LENGTH)

    fun extract(extras: Bundle): String {
        val title = extractTitle(extras)
        val text = extras.getCharSequence("android.text")?.toString().orEmpty().trim()
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty().trim()
        // Expanded-only fields: the collapsed notification often shows just a summary.
        val bigTitle = extras.getCharSequence("android.title.big")?.toString().orEmpty().trim()
        val conversationTitle = extras.getCharSequence("android.conversationTitle")?.toString().orEmpty().trim()
        val summaryText = extras.getCharSequence("android.summaryText")?.toString().orEmpty().trim()
        val infoText = extras.getCharSequence("android.infoText")?.toString().orEmpty().trim()
        val lines = extras.getCharSequenceArray("android.textLines")
            ?.asSequence()
            ?.map { it.toString() }
            .orEmpty()
            .boundedLines()
        val messages = messageLines(extras).boundedLines()

        val headers = listOf(bigTitle, conversationTitle).filter { it != title }
        // MessagingStyle: android.text only repeats the latest message, so the history replaces it.
        val content = if (messages.isNotEmpty()) messages else listOf(text, bigText) + lines

        // Preserve useful secondary text that some banking/broker notifications
        // place in android.subText. Duplicate fragments (e.g. android.text repeated
        // inside android.textLines) are removed at the fragment level before truncation.
        val body = (headers + content + listOf(subText, summaryText, infoText))
            .map { stripMarkup(it).trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        // Chat history is oldest-first; over the limit the newest messages win.
        return if (messages.isNotEmpty()) body.takeLast(MAX_BODY_LENGTH) else body.take(MAX_BODY_LENGTH)
    }

    // Only well-known formatting tags, so text like "price < 500 > 400" survives.
    private val markupTag = Regex("</?(b|i|u|s|em|strong|small|big|font|span|div|p|a|sub|sup|strike|ul|ol|li|h[1-6])(\\s[^<>]*)?>", RegexOption.IGNORE_CASE)
    private val lineBreakTag = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val entities = listOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&amp;" to "&")

    /** Some apps (e.g. ChatGPT) post literal HTML as notification text; reduce it to what the user would read. */
    fun stripMarkup(text: String): String {
        if ('<' !in text && '&' !in text) return text
        var plain = text.replace(lineBreakTag, "\n").replace(markupTag, "")
        entities.forEach { (entity, value) -> plain = plain.replace(entity, value, ignoreCase = true) }
        return plain
    }

    /** Lines of [incoming] not already in [existing]: what a re-post actually adds. */
    fun added(existing: String, incoming: String): String {
        val known = existing.lines().filter { it.isNotBlank() }.toSet()
        return incoming.lines().filter { it.isNotBlank() && it !in known }.joinToString("\n")
    }

    /** True when a re-post repeats at least one stored line (a chat thread growing), not a new item in a reused slot. */
    fun sharesLines(existing: String, incoming: String): Boolean {
        val known = existing.lines().filter { it.isNotBlank() }.toSet()
        return incoming.lines().any { it.isNotBlank() && it in known }
    }

    /**
     * Combines a previously stored body with the content of a re-posted notification.
     * Earlier lines are kept (apps often re-post only the newest message once dismissed)
     * and unseen lines go first, so the card shows the latest message; over the limit the newest content wins.
     */
    fun merge(existing: String, incoming: String): String {
        val known = existing.lines().filter { it.isNotBlank() }
        val added = incoming.lines().filter { it.isNotBlank() && it !in known }
        val merged = (added + known).joinToString("\n")
        return if (merged.length <= MAX_BODY_LENGTH) merged else merged.take(MAX_BODY_LENGTH)
    }

    private fun Sequence<String>.boundedLines(): List<String> =
        take(MAX_LINE_COUNT).map { it.trim().take(MAX_LINE_LENGTH) }.filter { it.isNotBlank() }.toList()

    @Suppress("DEPRECATION")
    private fun messageLines(extras: Bundle): Sequence<String> {
        val raw: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray("android.messages", Parcelable::class.java)
        } else {
            extras.getParcelableArray("android.messages")
        }
        return raw.orEmpty().asSequence().mapNotNull { item ->
            val message = item as? Bundle ?: return@mapNotNull null
            val body = message.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (body.isBlank()) return@mapNotNull null
            val sender = message.getCharSequence("sender")?.toString()?.trim()
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) senderPerson(message)?.name?.toString()?.trim() else null
            if (sender.isNullOrBlank()) body else "$sender: $body"
        }
    }

    @Suppress("DEPRECATION")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun senderPerson(message: Bundle): Person? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            message.getParcelable("sender_person", Person::class.java)
        } else {
            message.getParcelable("sender_person")
        }
}
