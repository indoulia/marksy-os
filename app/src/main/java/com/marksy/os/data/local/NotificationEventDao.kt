package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.marksy.os.data.RetentionPolicy
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: NotificationEventEntity): Long

    @Query("SELECT * FROM notification_events WHERE archived = 0 ORDER BY postedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events WHERE category = :category AND archived = 0 ORDER BY postedAt DESC LIMIT :limit")
    fun observeByCategory(category: String, limit: Int): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events WHERE category != 'OTHER' AND archived = 0 ORDER BY postedAt DESC LIMIT :limit")
    fun observeTimeline(limit: Int): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events WHERE category != 'OTHER' AND archived = 0 ORDER BY postedAt DESC")
    fun observeHistory(): Flow<List<NotificationEventEntity>>

    @Query("SELECT postedAt FROM notification_events WHERE archived = 0")
    fun observeActivePostedAt(): Flow<List<Long>>

    /** High-value active events for the Home/Smart Inbox attention surfaces. */
    @Query("SELECT * FROM notification_events WHERE priority >= :minimumPriority AND archived = 0 ORDER BY priority DESC, postedAt DESC LIMIT :limit")
    fun observeByMinimumPriority(minimumPriority: Int, limit: Int): Flow<List<NotificationEventEntity>>

    /** Trading events remain source-driven and independently delivered to Marksy. */
    @Query("SELECT * FROM notification_events WHERE isTrading = 1 AND archived = 0 ORDER BY postedAt DESC LIMIT :limit")
    fun observeTrading(limit: Int): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events WHERE isTrading = 1 AND archived = 0 AND deliveryState = 'PENDING' ORDER BY postedAt ASC LIMIT :limit")
    suspend fun findPendingTrading(limit: Int): List<NotificationEventEntity>

    @Query("SELECT id FROM notification_events WHERE sourcePackage = :sourcePackage AND sourceKey = :sourceKey LIMIT 1")
    suspend fun findIdBySourceKey(sourcePackage: String, sourceKey: String): Long?

    @Query("SELECT id FROM notification_events WHERE sourcePackage = :sourcePackage AND eventFingerprint = :eventFingerprint LIMIT 1")
    suspend fun findIdByFingerprint(sourcePackage: String, eventFingerprint: String): Long?

    @Query("UPDATE notification_events SET deliveryState = 'IN_FLIGHT', deliveryAttempts = :attempts, lastDeliveryAttemptAt = :attemptedAt WHERE id = :eventId AND archived = 0 AND deliveryState = 'PENDING'")
    suspend fun claimPendingTrading(eventId: Long, attempts: Int, attemptedAt: Long): Int

    @Query("UPDATE notification_events SET deliveryState = :state, deliveryAttempts = :attempts, lastDeliveryAttemptAt = :attemptedAt WHERE id = :eventId AND deliveryState = 'IN_FLIGHT'")
    suspend fun updateInFlightDeliveryState(eventId: Long, state: String, attempts: Int, attemptedAt: Long?): Int

    @Query("UPDATE notification_events SET deliveryState = :state, deliveryAttempts = :attempts, lastDeliveryAttemptAt = :attemptedAt, insightSummary = :summary, insightAction = :action, insightConfidence = :confidence, insightReceivedAt = :receivedAt, marksyTipId = :tipId, marksyResponseJson = :responseJson WHERE id = :eventId AND deliveryState = 'IN_FLIGHT'")
    suspend fun markDeliveredWithInsight(
        eventId: Long,
        state: String,
        attempts: Int,
        attemptedAt: Long,
        summary: String?,
        action: String?,
        confidence: Float?,
        receivedAt: Long,
        tipId: String?,
        responseJson: String?
    ): Int

    @Query("UPDATE notification_events SET deliveryState = 'PENDING' WHERE deliveryState = 'IN_FLIGHT' AND lastDeliveryAttemptAt < :cutoff")
    suspend fun recoverStaleInFlight(cutoff: Long): Int

    @Query("UPDATE notification_events SET archived = :archived WHERE id = :eventId")
    suspend fun setArchived(eventId: Long, archived: Boolean): Int

    @Query("UPDATE notification_events SET archived = :archived WHERE isTrading = :isTrading AND postedAt < :beforeMillis AND deliveryState != 'IN_FLIGHT'")
    suspend fun setArchivedForType(isTrading: Boolean, beforeMillis: Long, archived: Boolean): Int

    @Query("DELETE FROM notification_events WHERE postedAt < :cutoff AND isTrading = 0")
    suspend fun deleteOldNonTrading(cutoff: Long): Int

    @Query("DELETE FROM notification_events WHERE postedAt < :cutoff AND isTrading = 1")
    suspend fun deleteOldTrading(cutoff: Long): Int

    @Transaction
    suspend fun pruneExpired(nowMillis: Long) {
        deleteOldNonTrading(RetentionPolicy.nonTradingCutoff(nowMillis))
        deleteOldTrading(RetentionPolicy.tradingCutoff(nowMillis))
    }

    @Query("DELETE FROM notification_events")
    suspend fun deleteAll()
}
