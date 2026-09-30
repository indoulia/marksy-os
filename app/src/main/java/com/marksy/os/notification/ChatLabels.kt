package com.marksy.os.notification

import java.util.Locale

/** Channel labels and sender-free text for group-chat and SMS captures (tip-ledger spec §5.1). */
object ChatLabels {
    const val MASK_SENDER = "[SENDER]"
    private const val GROUP_SENDER = " @ "
    private const val SENDER_SUFFIX = ": "
    // Length >= 2 so a short handle such as "RK" is still masked.
    private const val MIN_MASKED_SENDER_LENGTH = 2
    // \p{M} so a combining mark (e.g. a Devanagari matra) right after a name still counts as part of the same word.
    private const val WORD = "\\p{L}\\p{M}\\p{N}_"
    // Uppercase-only and case-sensitive: a mixed-case or bare-6-letter contact name (e.g. "Suresh") must never read as a sender id.
    private val DLT_HEADER = Regex("^[A-Z]{2}-([A-Z0-9]{6})(?:-[PSTG])?$")

    /** An SMS title must look like a sender id before capture. */
    fun isSenderIdShaped(title: String): Boolean = DLT_HEADER.matches(title.trim())

    /** The allow-listed group a title names: "Rahul @ StockTips" (Telegram), "StockTips: Rahul" (WhatsApp) or "StockTips". */
    fun allowListedChat(title: String, allowList: Collection<String>, chatSenders: Collection<String> = emptySet()): String? {
        val value = title.trim()
        val senderNames = chatSenders.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        val candidates = listOfNotNull(
            value.substringAfterLast(GROUP_SENDER, "").trim().takeIf { GROUP_SENDER in value },
            value.substringBeforeLast(SENDER_SUFFIX, "").trim().takeIf { SENDER_SUFFIX in value },
            value
        )
        // Defence in depth: a candidate carrying a sender line or matching a known sender's name is never a group label.
        return candidates.firstOrNull { candidate ->
            candidate.isNotBlank() &&
                SENDER_SUFFIX !in candidate &&
                candidate.trim().lowercase(Locale.ROOT) !in senderNames &&
                WhatsAppSenderWatchlist.matches(allowList, candidate)
        }
    }

    fun smsSender(title: String): String {
        val value = title.trim()
        return DLT_HEADER.matchEntire(value)?.groupValues?.get(1) ?: value
    }

    fun allowListedSmsSender(title: String, allowList: Collection<String>): String? {
        val sender = smsSender(title)
        // Allow-list entries are stored lowercased; uppercase before extraction or the uppercase-only DLT_HEADER never matches.
        val normalizedAllowList = allowList.map { smsSender(it.trim().uppercase(Locale.ROOT)) }
        return sender.takeIf { it.isNotBlank() && WhatsAppSenderWatchlist.matches(normalizedAllowList, it) }
    }

    /** One alternation over every sender (not one Regex each); stateless so the caller can cache it per batch. */
    fun buildSenderMask(names: Collection<String>): Regex? {
        val maskable = names.map { it.trim() }.filter { it.length >= MIN_MASKED_SENDER_LENGTH }.distinct().sortedByDescending { it.length }
        if (maskable.isEmpty()) return null
        val alternation = maskable.joinToString("|") { Regex.escape(it) }
        return Regex("(?<![$WORD])(?:$alternation)(?![$WORD])", RegexOption.IGNORE_CASE)
    }

    /** Strips a sender's "Name: " line prefix and masks every known sender via [mask], wherever it appears in the row. */
    fun withoutSenders(body: String, title: String, senders: Collection<String>, mask: Regex?): String {
        val lines = body.lines()
        val names = senders.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        val linePrefixNames = names.filter { name -> title.contains(name) || lines.any { it.startsWith("$name$SENDER_SUFFIX") } }
        if (mask == null && linePrefixNames.isEmpty()) return body
        return lines.joinToString("\n") { line ->
            val unprefixed = linePrefixNames.firstOrNull { line.startsWith("$it$SENDER_SUFFIX") }
                ?.let { line.removePrefix("$it$SENDER_SUFFIX") } ?: line
            if (mask != null) mask.replace(unprefixed, MASK_SENDER) else unprefixed
        }
    }
}
