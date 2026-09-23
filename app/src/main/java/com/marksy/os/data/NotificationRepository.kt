package com.marksy.os.data

import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.notification.NotificationTextExtractor
import kotlinx.coroutines.flow.Flow

class NotificationRepository(private val dao: NotificationEventDao) {
    fun observeRecent(limit: Int = 50): Flow<List<NotificationEventEntity>> = dao.observeRecent(limit)

    fun observeTrading(limit: Int = 50): Flow<List<NotificationEventEntity>> =
        dao.observeTrading(limit)

    fun observeCategory(category: String, limit: Int = 50): Flow<List<NotificationEventEntity>> =
        dao.observeByCategory(category, limit)

    fun observeImportant(limit: Int = 50): Flow<List<NotificationEventEntity>> =
        dao.observeByMinimumPriority(minimumPriority = 65, limit = limit)

    fun observeAttention(limit: Int = 100): Flow<List<NotificationEventEntity>> =
        dao.observeByMinimumPriority(minimumPriority = 40, limit = limit)

    fun observeTimeline(limit: Int = 100): Flow<List<NotificationEventEntity>> =
        dao.observeTimeline(limit)

    fun observeHistory(): Flow<List<NotificationEventEntity>> = dao.observeHistory()

    fun observeActive(): Flow<List<NotificationEventEntity>> = dao.observeActive()

    /** One-off repair for rows captured before HTML stripping existed; leaves read state untouched. */
    suspend fun stripStoredMarkup() {
        dao.findWithPossibleMarkup().forEach { event ->
            val title = NotificationTextExtractor.stripMarkup(event.title).trim()
            val body = NotificationTextExtractor.stripMarkup(event.body).trim()
            if (title != event.title || body != event.body) dao.updateText(event.id, title, body)
        }
    }

    suspend fun archive(eventId: Long): Boolean = dao.setArchived(eventId, true) > 0

    suspend fun unarchive(eventId: Long): Boolean = dao.setArchived(eventId, false) > 0

    suspend fun clearAll() = dao.deleteAll()

    suspend fun delete(eventId: Long): Boolean = dao.deleteById(eventId) > 0

    /** Undo for delete: re-inserts the exact row, id included. */
    suspend fun restore(event: NotificationEventEntity) { dao.insert(event) }

    suspend fun setRead(eventId: Long, read: Boolean) = dao.setRead(eventId, read)

    suspend fun setKept(eventId: Long, kept: Boolean) = dao.setKept(eventId, kept)

    suspend fun setReminder(eventId: Long, remindAt: Long?) = dao.setReminder(eventId, remindAt)
}
