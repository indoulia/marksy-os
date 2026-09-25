package com.marksy.os.plan

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.data.local.PlanItemDao
import com.marksy.os.data.local.PlanItemEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

/** Phone alerts for plan items; the Android implementation is WorkManager-backed. */
interface PlanAlarms {
    fun schedule(item: PlanItemEntity)
    fun cancel(itemId: Long)
}

class PlanRepository(
    private val dao: PlanItemDao,
    private val alarms: PlanAlarms,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    fun observeAll(): Flow<List<PlanItemEntity>> = dao.observeAll()

    /** A due found in a REMINDERS notification becomes (or refreshes) one item; a paid item stays paid. */
    suspend fun captureFromEvent(event: NotificationEventEntity): PlanItemEntity? {
        if (event.category != "REMINDERS") return null
        val notice = DueDateParser.parse(event.title, event.body, event.postedAt, zone) ?: return null
        val day = Instant.ofEpochMilli(notice.dueAt).atZone(zone).toLocalDate()
        val who = notice.counterparty?.lowercase()?.replace(Regex("\\s+"), " ") ?: notice.amountMinor?.toString() ?: "?"
        val key = "sms|${notice.kind.name}|$who|$day"
        dao.byKey(key)?.let { existing ->
            if (existing.amountMinor == notice.amountMinor || notice.amountMinor == null) return existing
            return existing.copy(amountMinor = notice.amountMinor, updatedAt = clock()).also { dao.update(it) }
        }
        val now = clock()
        val item = PlanItemEntity(
            kind = notice.kind.name, title = titleFor(notice.kind, notice.counterparty), counterparty = notice.counterparty,
            amountMinor = notice.amountMinor, dueAt = notice.dueAt, recurrence = Recurrence.NONE.name, status = PlanStatus.TODO.name,
            origin = PlanOrigin.SMS.name, sourceEventId = event.id, dedupeKey = key, createdAt = now, updatedAt = now
        )
        return item.copy(id = dao.insert(item)).also(alarms::schedule)
    }

    suspend fun backfill(events: List<NotificationEventEntity>): Int = events.count { captureFromEvent(it) != null }

    suspend fun add(kind: PlanKind, title: String, counterparty: String?, amountMinor: Long?, dueAt: Long?, recurrence: Recurrence, status: PlanStatus = PlanStatus.TODO): Long {
        val now = clock()
        val item = PlanItemEntity(
            kind = kind.name, title = title.trim(), counterparty = counterparty?.trim()?.ifEmpty { null }, amountMinor = amountMinor,
            dueAt = dueAt, recurrence = recurrence.name, status = status.name, origin = PlanOrigin.MANUAL.name, createdAt = now, updatedAt = now
        )
        val id = dao.insert(item)
        if (dueAt != null && status != PlanStatus.DONE) alarms.schedule(item.copy(id = id))
        return id
    }

    suspend fun update(item: PlanItemEntity) {
        val updated = item.copy(updatedAt = clock())
        dao.update(updated)
        reschedule(updated)
    }

    /** Done on a repeating item you added starts the next one; a contact birthday rolls to next year in place. */
    suspend fun setStatus(id: Long, status: PlanStatus) {
        val item = dao.get(id) ?: return
        val now = clock()
        val recurrence = Recurrence.valueOf(item.recurrence)
        val next = item.dueAt?.let { PlanRules.next(it, recurrence, zone) }
        if (status == PlanStatus.DONE && next != null && item.origin == PlanOrigin.CONTACTS.name) {
            update(item.copy(dueAt = next, status = PlanStatus.TODO.name))
            return
        }
        val updated = item.copy(status = status.name, completedAt = if (status == PlanStatus.DONE) now else null, updatedAt = now)
        dao.update(updated)
        reschedule(updated)
        if (status == PlanStatus.DONE && next != null && item.origin == PlanOrigin.MANUAL.name) {
            add(PlanKind.valueOf(item.kind), item.title, item.counterparty, item.amountMinor, next, recurrence)
        }
    }

    suspend fun delete(id: Long) {
        alarms.cancel(id)
        dao.delete(id)
    }

    /** Mirrors a notification's "Remind me" (its own alarm already fires) so it shows with every other reminder. */
    suspend fun mirrorFollowUp(eventId: Long, title: String, at: Long?) {
        val key = "remind|$eventId"
        val existing = dao.byKey(key)
        if (at == null) { existing?.let { dao.delete(it.id) }; return }
        val now = clock()
        if (existing != null) { dao.update(existing.copy(title = title, dueAt = at, status = PlanStatus.TODO.name, completedAt = null, updatedAt = now)); return }
        dao.insert(
            PlanItemEntity(
                kind = PlanKind.FOLLOW_UP.name, title = title, dueAt = at, recurrence = Recurrence.NONE.name, status = PlanStatus.TODO.name,
                origin = PlanOrigin.REMIND_ME.name, sourceEventId = eventId, dedupeKey = key, createdAt = now, updatedAt = now
            )
        )
    }

    suspend fun upsertBirthday(lookupKey: String, name: String, month: Int, day: Int) {
        val key = "birthday|$lookupKey"
        val dueAt = PlanRules.nextBirthday(month, day, clock(), zone)
        val title = "$name's birthday"
        val existing = dao.byKey(key)
        if (existing != null) {
            if (existing.title == title && existing.dueAt == dueAt) return
            // A new year's date reopens a birthday marked done last year.
            val status = if (existing.dueAt != dueAt) PlanStatus.TODO.name else existing.status
            update(existing.copy(title = title, dueAt = dueAt, status = status))
            return
        }
        val now = clock()
        val item = PlanItemEntity(
            kind = PlanKind.BIRTHDAY.name, title = title, dueAt = dueAt, recurrence = Recurrence.YEARLY.name, status = PlanStatus.TODO.name,
            origin = PlanOrigin.CONTACTS.name, dedupeKey = key, createdAt = now, updatedAt = now
        )
        alarms.schedule(item.copy(id = dao.insert(item)))
    }

    /** Re-arms alerts for every open dated item (idempotent: one unique job per item and offset). */
    suspend fun rescheduleAll() = dao.activeDated().filter { it.origin != PlanOrigin.REMIND_ME.name }.forEach(alarms::schedule)

    private fun reschedule(item: PlanItemEntity) {
        alarms.cancel(item.id)
        if (item.status != PlanStatus.DONE.name && item.dueAt != null && item.origin != PlanOrigin.REMIND_ME.name) alarms.schedule(item)
    }

    private fun titleFor(kind: PlanKind, counterparty: String?): String = when (kind) {
        PlanKind.CARD_DUE -> counterparty?.let { "$it card bill" } ?: "Card bill"
        PlanKind.EMI -> counterparty?.let { "$it EMI" } ?: "EMI"
        else -> counterparty?.let { "$it bill" } ?: "Bill"
    }
}
