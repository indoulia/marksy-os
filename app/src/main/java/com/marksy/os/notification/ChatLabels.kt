package com.marksy.os.notification

import java.util.Locale

/** Channel labels and sender-free text for group-chat and SMS captures (tip-ledger spec §5.1). */
object ChatLabels {
    const val MASK_SENDER = "[SENDER]"
    private const val GROUP_SENDER = " @ "
    private const val SENDER_SUFFIX = ": "
    private const val MIN_MASKED_SENDER_LENGTH = 3
    // TRAI DLT sender ids: the operator/circle prefix and type suffix vary per message, the 6-character header does not.
    private val DLT_HEADER = Regex("^[A-Za-z]{2}-([A-Za-z0-9]{6})(?:-[PSTGpstg])?$")

    /** The allow-listed group a title names: "Rahul @ StockTips" (Telegram), "StockTips: Rahul" (WhatsApp) or "StockTips". */
    fun allowListedChat(title: String, allowList: Collection<String>): String? {
        val value = title.trim()
        val candidates = listOfNotNull(
            value.substringAfterLast(GROUP_SENDER, "").trim().takeIf { GROUP_SENDER in value },
            value.substringBeforeLast(SENDER_SUFFIX, "").trim().takeIf { SENDER_SUFFIX in value },
            value
        )
        return candidates.firstOrNull { it.isNotBlank() && WhatsAppSenderWatchlist.matches(allowList, it) }
    }

    fun smsSender(title: String): String {
        val value = title.trim()
        return DLT_HEADER.matchEntire(value)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: value
    }

    fun allowListedSmsSender(title: String, allowList: Collection<String>): String? {
        val sender = smsSender(title)
        return sender.takeIf { it.isNotBlank() && WhatsAppSenderWatchlist.matches(allowList.map(::smsSender), it) }
    }

    /** This row's senders lose their "Name: " line prefix, and any other mention of them is masked. */
    fun withoutSenders(body: String, title: String, senders: Collection<String>): String {
        val lines = body.lines()
        val present = senders.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .filter { name -> title.contains(name) || lines.any { it.startsWith("$name$SENDER_SUFFIX") } }
            .sortedByDescending { it.length }
        if (present.isEmpty()) return body
        val masks = present.filter { it.length >= MIN_MASKED_SENDER_LENGTH }
            .map { Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE) }
        return lines.joinToString("\n") { line ->
            val unprefixed = present.firstOrNull { line.startsWith("$it$SENDER_SUFFIX") }
                ?.let { line.removePrefix("$it$SENDER_SUFFIX") } ?: line
            masks.fold(unprefixed) { text, mask -> mask.replace(text, MASK_SENDER) }
        }
    }
}
