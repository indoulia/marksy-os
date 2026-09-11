package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: NotificationEventEntity): Long

    @Query("SELECT * FROM notification_events ORDER BY postedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events WHERE category = :category ORDER BY postedAt DESC LIMIT :limit")
    fun observeByCategory(category: String, limit: Int): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events WHERE isTrading = 1 AND deliveryState = 'PENDING' ORDER BY postedAt ASC LIMIT :limit")
    suspend fun findPendingTrading(limit: Int): List<NotificationEventEntity>

    @Query("UPDATE notification_events SET deliveryState = :state, deliveryAttempts = :attempts, lastDeliveryAttemptAt = :attemptedAt WHERE id = :eventId")
    suspend fun updateDeliveryState(
        eventId: Long,
        state: String,
        attempts: Int,
        attemptedAt: Long?
    )

    @Query("DELETE FROM notification_events WHERE postedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM notification_events")
    suspend fun deleteAll()
}
