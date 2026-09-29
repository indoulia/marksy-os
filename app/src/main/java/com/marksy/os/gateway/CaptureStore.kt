package com.marksy.os.gateway

import android.content.Context
import java.util.UUID

/** Device-local tip-capture state (tip-ledger spec §5.1); none of it is ever sent as stored. */
class CaptureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceSalt(): String = synchronized(LOCK) {
        prefs.getString(KEY_SALT, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_SALT, it).apply() }
    }

    /** Null until the first successful fetch of `GET /channels/capture-list`. */
    fun capturePackages(): Set<String>? =
        if (!prefs.contains(KEY_FETCHED_AT)) null else prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()

    fun isCaptureListStale(now: Long): Boolean = now - prefs.getLong(KEY_FETCHED_AT, 0L) >= CAPTURE_LIST_MAX_AGE_MS

    fun saveCapturePackages(packages: Set<String>, now: Long) {
        prefs.edit().putStringSet(KEY_PACKAGES, packages).putLong(KEY_FETCHED_AT, now).apply()
    }

    fun chatSenders(): Set<String> = prefs.getStringSet(KEY_SENDERS, emptySet()).orEmpty().toSet()

    fun rememberChatSenders(senders: Collection<String>) = synchronized(LOCK) {
        val fresh = senders.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val known = chatSenders()
        if (known.containsAll(fresh)) return@synchronized
        // Over the cap the newest names win; an evicted name only matters to an old undelivered row.
        val merged = if (known.size + fresh.size <= MAX_SENDERS) known + fresh
        else fresh + known.take((MAX_SENDERS - fresh.size).coerceAtLeast(0))
        prefs.edit().putStringSet(KEY_SENDERS, merged).apply()
    }

    private companion object {
        val LOCK = Any()
        const val PREFS = "tip_capture"
        const val KEY_SALT = "device_salt"
        const val KEY_PACKAGES = "capture_packages"
        const val KEY_FETCHED_AT = "capture_list_fetched_at"
        const val KEY_SENDERS = "chat_senders"
        const val MAX_SENDERS = 5_000
        const val CAPTURE_LIST_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
