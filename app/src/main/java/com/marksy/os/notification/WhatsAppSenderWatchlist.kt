package com.marksy.os.notification

import android.content.Context
import java.util.Locale

/**
 * Local-only allow-list for WhatsApp senders that Marksy OS is permitted to inspect
 * through the optional accessibility connector.
 *
 * Matching is deliberately exact after normalization. We never treat the absence of
 * a sender match as permission to inspect/capture the conversation.
 */
object WhatsAppSenderWatchlist {
    private const val PREFS = "whatsapp_connector"
    private const val KEY_SENDERS = "watched_senders"
    private const val MAX_SENDER_LENGTH = 120
    private const val MAX_SENDERS = 25

    fun get(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SENDERS, emptySet())
            .orEmpty()
            .mapNotNull(::normalize)
            .take(MAX_SENDERS)
            .toSet()

    fun replace(context: Context, senders: Collection<String>) {
        val normalized = senders.mapNotNull(::normalize)
            .distinct()
            .take(MAX_SENDERS)
            .toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SENDERS, normalized)
            .apply()
    }

    fun add(context: Context, sender: String): Boolean {
        val normalized = normalize(sender) ?: return false
        val updated = (get(context) + normalized).take(MAX_SENDERS)
        replace(context, updated)
        return true
    }

    fun remove(context: Context, sender: String) {
        val normalized = normalize(sender) ?: return
        replace(context, get(context) - normalized)
    }

    fun matches(watchlist: Collection<String>, candidate: String): Boolean {
        val normalized = normalize(candidate) ?: return false
        return watchlist.any { normalize(it) == normalized }
    }

    private fun normalize(value: String): String? =
        value.trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_SENDER_LENGTH)
            .takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
}
