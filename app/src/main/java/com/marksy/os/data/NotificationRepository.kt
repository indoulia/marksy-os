package com.marksy.os.data

import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.flow.Flow

class NotificationRepository(private val dao: NotificationEventDao) {
    fun observeRecent(limit: Int = 50): Flow<List<NotificationEventEntity>> = dao.observeRecent(limit)
    fun observeTrading(limit: Int = 50): Flow<List<NotificationEventEntity>> = dao.observeByCategory("TRADING", limit)
    fun observeCategory(category: String, limit: Int = 50): Flow<List<NotificationEventEntity>> =
        dao.observeByCategory(category, limit)

    /** Events useful for the chronological Timeline; excludes low-value OTHER noise. */
    fun observeTimeline(limit: Int = 100): Flow<List<NotificationEventEntity>> =
        dao.observeTimeline(limit)

    /** All retained meaningful events, used by the interactive local history calendar. */
    fun observeHistory(): Flow<List<NotificationEventEntity>> = dao.observeHistory()

    suspend fun clearAll() = dao.deleteAll()
}
