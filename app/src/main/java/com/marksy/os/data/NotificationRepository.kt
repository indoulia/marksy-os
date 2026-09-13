package com.marksy.os.data

import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
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

    suspend fun archive(eventId: Long): Boolean = dao.setArchived(eventId, true) > 0

    suspend fun unarchive(eventId: Long): Boolean = dao.setArchived(eventId, false) > 0

    suspend fun clearAll() = dao.deleteAll()
}
