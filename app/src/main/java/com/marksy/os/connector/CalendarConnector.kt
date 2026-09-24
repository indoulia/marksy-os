package com.marksy.os.connector

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * EPIC-021 Calendar connector over the Android Calendar provider (read-only, READ_CALENDAR).
 * Instances are used so recurring events arrive expanded. The provider exposes no change feed to
 * apps, so the cursor is a snapshot of instance-key -> content hash for the sync window: new and
 * changed instances are upserted, and ones that vanished while still inside the window are removed.
 */
class CalendarConnector(
    private val source: CalendarSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) : SyncConnector {
    data class Instance(
        val eventId: Long,
        val begin: Long,
        val end: Long,
        val title: String?,
        val location: String?,
        val organizer: String?,
        val calendarName: String?,
        val allDay: Boolean,
        val canceled: Boolean,
        val declined: Boolean
    )

    interface CalendarSource {
        fun hasPermission(): Boolean
        fun instances(from: Long, to: Long, limit: Int): List<Instance>
    }

    override val descriptor = DESCRIPTOR
    override val sourcePackage = "com.android.providers.calendar"
    override val sourceName = "Calendar"

    override fun state() = if (source.hasPermission()) ConnectorState.ACTIVE else ConnectorState.NEEDS_PERMISSION

    override suspend fun sync(cursor: String?): SyncBatch {
        val now = clock()
        val from = now - PAST_MS
        val to = now + FUTURE_MS
        val previous = parseCursor(cursor)
        val all = try {
            source.instances(from, to, MAX_INSTANCES)
        } catch (e: SecurityException) {
            throw ConnectorException(ConnectorException.Kind.PERMISSION, cause = e)
        } catch (e: IllegalArgumentException) {
            throw ConnectorException(ConnectorException.Kind.UNAVAILABLE, cause = e)
        }
        val current = all.filter { !it.canceled && !it.declined && it.end >= it.begin }.associateBy { key(it) }
        val records = current.mapValues { (_, i) -> record(i, now) }
        val hashes = records.mapValues { (_, r) -> hash(r) }
        // Unchanged instances are re-sent every few days so an upcoming event pruned by retention is restored.
        val stale = { k: String -> previous[k]?.let { now - it.second >= REFRESH_MS } ?: true }
        val upserts = records.filter { (k, _) -> previous[k]?.first != hashes[k] || stale(k) }.values.toList()
        val seen = hashes.mapValues { (k, h) -> if (previous[k]?.first == h && !stale(k)) "$h|${previous.getValue(k).second}" else "$h|$now" }
        // Only instances still inside the window count as deleted; ones that slid out of it simply age out.
        // A truncated result says nothing about instances after the last one returned.
        val horizon = if (all.size >= MAX_INSTANCES) all.maxOf { it.begin } else Long.MAX_VALUE
        val removed = previous.keys.filter { it !in current && (beginOf(it) ?: 0L).let { b -> b >= from && b <= horizon } }
        val kept = previous.filterKeys { it !in current && (beginOf(it) ?: 0L) > horizon }.mapValues { (_, v) -> "${v.first}|${v.second}" }
        return SyncBatch(upserts, removed, JSONObject((seen + kept) as Map<*, *>).toString())
    }

    private fun record(i: Instance, now: Long): SourceRecord {
        val z = zone()
        val start = Instant.ofEpochMilli(i.begin).atZone(z)
        val end = Instant.ofEpochMilli(i.end).atZone(z)
        val whenText = if (i.allDay) "${start.format(DAY)} (all day)"
        else if (start.toLocalDate() == end.toLocalDate()) "${start.format(DAY)}, ${start.format(TIME)}–${end.format(TIME)}"
        else "${start.format(DAY)}, ${start.format(TIME)} – ${end.format(DAY)}, ${end.format(TIME)}"
        val body = buildList {
            add(whenText)
            i.location?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Location: ${it.take(120)}") }
            i.organizer?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Organizer: ${it.take(80)}") }
            i.calendarName?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Calendar: ${it.take(60)}") }
        }.joinToString("\n")
        // First-seen time, never the future start: Marksy orders and retains events by when they reached it.
        return SourceRecord(key(i), i.title?.trim()?.ifBlank { null } ?: "(No title)", body, minOf(now, i.begin).coerceAtLeast(now - PAST_MS))
    }

    companion object {
        const val ID = "calendar-provider"
        val DESCRIPTOR = ConnectorDescriptor(ID, "Calendar", "Android Calendar provider, read-only, next 14 days")
        const val PAST_MS = 24 * 3_600_000L
        const val FUTURE_MS = 14 * 24 * 3_600_000L
        const val MAX_INSTANCES = 300
        const val REFRESH_MS = 3 * 24 * 3_600_000L
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        fun key(i: Instance) = "${i.eventId}@${i.begin}"
        fun beginOf(key: String) = key.substringAfter('@', "").toLongOrNull()
        fun hash(r: SourceRecord) = (r.title + "\u0000" + r.body).hashCode().toString(16)

        /** key -> (content hash, last sent). A corrupt cursor means a full snapshot, never a crash; re-sent records dedup by source key. */
        fun parseCursor(cursor: String?): Map<String, Pair<String, Long>> = runCatching {
            val o = JSONObject(cursor ?: return emptyMap())
            o.keys().asSequence().associateWith { k -> o.getString(k).split('|').let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) } }
        }.getOrDefault(emptyMap())
    }
}

class AndroidCalendarSource(private val context: Context) : CalendarConnector.CalendarSource {
    override fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun instances(from: Long, to: Long, limit: Int): List<CalendarConnector.Instance> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ORGANIZER,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS
        )
        val out = mutableListOf<CalendarConnector.Instance>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += CalendarConnector.Instance(
                    eventId = c.getLong(0), begin = c.getLong(1), end = c.getLong(2),
                    title = c.getString(3), location = c.getString(4), organizer = c.getString(5), calendarName = c.getString(6),
                    allDay = c.getInt(7) == 1,
                    canceled = !c.isNull(8) && c.getInt(8) == CalendarContract.Events.STATUS_CANCELED,
                    declined = !c.isNull(9) && c.getInt(9) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                )
            }
        }
        return out
    }
}
