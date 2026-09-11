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

    @Query("SELECT * FROM notification_events WHERE category != 'OTHER' ORDER BY postedAt DESC LIMIT :limit")
    fun observeTimeline(limit: Int): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events WHERE isTrading = 1 AND deliveryState = 'PENDING' ORDER BY postedAt ASC LIMIT :limit")
    suspend fun findPendingTrading(limit: Int): List<NotificationEventEntity>

    @Query("SELECT id FROM notification_events WHERE sourcePackage = :sourcePackage AND sourceKey = :sourceKey LIMIT 1")
    suspend fun findIdBySourceKey(sourcePackage: String, sourceKey: String): Long?

    @Query("UPDATE notification_events SET deliveryState = :state, deliveryAttempts = :attempts, lastDeliveryAttemptAt = :attemptedAt WHERE id = :eventId")
    suspend fun updateDeliveryState(eventId: Long, state: String, attempts: Int, attemptedAt: Long?)

    @Query("UPDATE notification_events SET deliveryState = :state, deliveryAttempts = :attempts, lastDeliveryAttemptAt = :attemptedAt, insightSummary = :summary, insightAction = :action, insightConfidence = :confidence, insightReceivedAt = :receivedAt WHERE id = :eventId")
    suspend fun markDeliveredWithInsight(
        eventId: Long,
        state: String,
        attempts: Int,
        attemptedAt: Long,
        summary: String?,
        action: String?,
        confidence: Float?,
        receivedAt: Long
    )

    @Query("UPDATE notification_events SET deliveryState = 'PENDING' WHERE deliveryState = 'IN_FLIGHT' AND lastDeliveryAttemptAt < :cutoff")
    suspend fun recoverStaleInFlight(cutoff: Long): Int

    @Query("DELETE FROM notification_events WHERE postedAt < :cutoff AND isTrading = 0")
    suspend fun deleteOldNonTrading(cutoff: Long): Int

    @Query("DELETE FROM notification_events WHERE postedAt < :cutoff AND isTrading = 1")
    suspend fun deleteOldTrading(cutoff: Long): Int

    @Query("DELETE FROM notification_events")
    suspend fun deleteAll()
}
