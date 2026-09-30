package com.marksy.os.gateway

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import java.util.UUID

/** Device-local tip-capture state (tip-ledger spec §5.1); none of it is ever sent as stored. */
class CaptureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceSalt(): String = synchronized(LOCK) {
        // commit() (not apply()) so a retry after process death still finds this same salt on disk (fix round 1).
        prefs.getString(KEY_SALT, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_SALT, it).commit() }
    }

    /** Null until the first successful fetch of `GET /channels/capture-list`. */
    fun capturePackages(): Set<String>? =
        if (!prefs.contains(KEY_FETCHED_AT)) null else prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()

    fun isCaptureListStale(now: Long): Boolean = now - prefs.getLong(KEY_FETCHED_AT, 0L) >= CAPTURE_LIST_MAX_AGE_MS

    fun saveCapturePackages(packages: Set<String>, now: Long) {
        prefs.edit().putStringSet(KEY_PACKAGES, packages).putLong(KEY_FETCHED_AT, now).apply()
    }

    fun chatSenders(): Set<String> = orderedSenders().toSet()

    /**
     * Finding M2: senders are an ORDERED list (oldest first), not a Set, so eviction over the cap drops the
     * oldest name, not an arbitrary one. A re-seen name moves to the end. A legacy StringSet (pre-fix) is
     * migrated to this format on first read.
     */
    private fun orderedSenders(): List<String> = synchronized(LOCK) {
        when (val raw = prefs.all[KEY_SENDERS]) {
            is String -> decodeSenders(raw)
            is Set<*> -> raw.filterIsInstance<String>().also { migrated ->
                prefs.edit().putString(KEY_SENDERS, encodeSenders(migrated)).commit()
            }
            else -> emptyList()
        }
    }

    /**
     * Finding C2(b): persisted with commit(), not apply(), so a per-batch re-read (finding C2a) that runs
     * on another thread right after this call is guaranteed to see it.
     */
    fun rememberChatSenders(senders: Collection<String>) = synchronized(LOCK) {
        val fresh = senders.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (fresh.isEmpty()) return@synchronized
        val ordered = orderedSenders().toMutableList()
        // Finding N3: skip the JSON rewrite and commit() when fresh is already the tail, in the same
        // order -- e.g. the same sender posting again with nothing new to record.
        if (ordered.size >= fresh.size && ordered.takeLast(fresh.size) == fresh) return@synchronized
        fresh.forEach { name ->
            ordered.remove(name)
            ordered.add(name)
        }
        val trimmed = if (ordered.size > MAX_SENDERS) ordered.takeLast(MAX_SENDERS) else ordered
        prefs.edit().putString(KEY_SENDERS, encodeSenders(trimmed)).commit()
    }

    private fun encodeSenders(list: List<String>): String = JSONArray(list).toString()

    private fun decodeSenders(json: String): List<String> {
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
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
