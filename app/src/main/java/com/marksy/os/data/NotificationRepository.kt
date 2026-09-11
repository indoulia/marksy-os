package com.marksy.os.data

import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.flow.Flow

class NotificationRepository(private val dao: NotificationEventDao) {
    fun observeRecent(limit: Int = 50): Flow<List<NotificationEventEntity>> = dao.observeRecent(limit)
    fun observeTrading(limit: Int = 50): Flow<List<NotificationEventEntity>> = dao.observeByCategory("TRADING", limit)
    suspend fun purgeOlderThan(cutoff: Long): Int = dao.deleteOlderThan(cutoff)
    suspend fun clearAll() = dao.deleteAll()
}
