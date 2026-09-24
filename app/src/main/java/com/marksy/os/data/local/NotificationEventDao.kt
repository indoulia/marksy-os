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

    // Lifecycle mirrors the legacy archived flag so both stay consistent (EPIC-010).
    @Query("UPDATE notification_events SET archived = :archived, lifecycleState = CASE WHEN :archived THEN 'ARCHIVED' ELSE 'ACTIVE' END, lifecycleUpdatedAt = :atMillis, lifecycleReason = NULL WHERE id = :eventId")
    suspend fun setArchived(eventId: Long, archived: Boolean, atMillis: Long = System.currentTimeMillis()): Int

    @Query("UPDATE notification_events SET archived = :archived, lifecycleState = CASE WHEN :archived THEN 'ARCHIVED' ELSE 'ACTIVE' END WHERE isTrading = :isTrading AND postedAt < :beforeMillis AND deliveryState != 'IN_FLIGHT'")
    suspend fun setArchivedForType(isTrading: Boolean, beforeMillis: Long, archived: Boolean): Int

    // ---- EPIC-010 event intelligence ----

    @Query("SELECT * FROM notification_events WHERE id = :eventId")
    suspend fun getById(eventId: Long): NotificationEventEntity?

    /** Rows whose derived intelligence is missing or from an older pipeline version, oldest first. */
    @Query("SELECT * FROM notification_events WHERE intelligenceVersion < :version ORDER BY postedAt ASC, id ASC LIMIT :limit")
    suspend fun findNeedingIntelligence(version: Int, limit: Int): List<NotificationEventEntity>

    /** Earliest canonical (non-duplicate) event sharing a correlation key inside the window. */
    @Query("SELECT id FROM notification_events WHERE correlationKey = :correlationKey AND id != :excludeId AND duplicateOfId IS NULL AND isTrading = 0 AND postedAt BETWEEN :fromMillis AND :toMillis AND (:otherSourceOnly = 0 OR sourcePackage != :sourcePackage) ORDER BY postedAt ASC, id ASC LIMIT 1")
    suspend fun findCorrelatedCanonical(
        correlationKey: String,
        excludeId: Long,
        fromMillis: Long,
        toMillis: Long,
        sourcePackage: String,
        otherSourceOnly: Boolean
    ): Long?

    @Query("UPDATE notification_events SET importanceScore = :importance, intelligenceConfidence = :confidence, threadKey = :threadKey, correlationKey = :correlationKey, duplicateOfId = :duplicateOfId, intelligenceJson = :json, intelligenceVersion = :version WHERE id = :eventId")
    suspend fun updateIntelligence(
        eventId: Long,
        importance: Int,
        confidence: Float,
        threadKey: String,
        correlationKey: String?,
        duplicateOfId: Long?,
        json: String,
        version: Int
    ): Int

    /** Auto-resolves still-open earlier events of a thread once a later event says the item is done. */
    @Query("UPDATE notification_events SET lifecycleState = 'RESOLVED', lifecycleUpdatedAt = :atMillis, lifecycleReason = :reason WHERE threadKey = :threadKey AND id != :excludeId AND postedAt <= :upToMillis AND lifecycleState IN ('NEW', 'ACTIVE') AND archived = 0")
    suspend fun resolveOpenInThread(threadKey: String, excludeId: Long, upToMillis: Long, reason: String, atMillis: Long): Int

    /** Conditional so a stale caller can never move an event backwards (e.g. ARCHIVED -> ACTIVE). */
    @Query("UPDATE notification_events SET lifecycleState = :to, lifecycleUpdatedAt = :atMillis, lifecycleReason = :reason WHERE id = :eventId AND lifecycleState = :from AND archived = 0")
    suspend fun transitionLifecycle(eventId: Long, from: String, to: String, reason: String?, atMillis: Long): Int

    @Query("DELETE FROM notification_events WHERE postedAt < :cutoff AND isTrading = 0")
    suspend fun deleteOldNonTrading(cutoff: Long): Int

    @Query("DELETE FROM notification_events WHERE postedAt < :cutoff AND isTrading = 1")
    suspend fun deleteOldTrading(cutoff: Long): Int

    @Transaction
    suspend fun pruneExpired(nowMillis: Long) {
        deleteOldNonTrading(RetentionPolicy.nonTradingCutoff(nowMillis))
        deleteOldTrading(RetentionPolicy.tradingCutoff(nowMillis))
    }

    // ---- EPIC-011 Smart Inbox (thread-level actions take the thread's event ids) ----

    /** Non-archived events incl. resolved ones; bounded by retention and the limit. */
    @Query("SELECT * FROM notification_events WHERE archived = 0 ORDER BY postedAt DESC LIMIT :limit")
    fun observeInbox(limit: Int): Flow<List<NotificationEventEntity>>

    @Query("UPDATE notification_events SET lifecycleState = 'ACTIVE', lifecycleUpdatedAt = :atMillis WHERE id IN (:ids) AND lifecycleState = 'NEW' AND archived = 0")
    suspend fun markSeen(ids: List<Long>, atMillis: Long): Int

    @Query("UPDATE notification_events SET lifecycleState = 'RESOLVED', lifecycleUpdatedAt = :atMillis, lifecycleReason = :reason, snoozedUntil = NULL WHERE id IN (:ids) AND lifecycleState IN ('NEW', 'ACTIVE') AND archived = 0")
    suspend fun resolve(ids: List<Long>, reason: String, atMillis: Long): Int

    @Query("UPDATE notification_events SET lifecycleState = 'ACTIVE', lifecycleUpdatedAt = :atMillis, lifecycleReason = NULL WHERE id IN (:ids) AND lifecycleState = 'RESOLVED' AND archived = 0")
    suspend fun reopen(ids: List<Long>, atMillis: Long): Int

    @Query("UPDATE notification_events SET snoozedUntil = :untilMillis WHERE id IN (:ids) AND archived = 0")
    suspend fun setSnoozedUntil(ids: List<Long>, untilMillis: Long?): Int

    @Query("UPDATE notification_events SET archived = 1, lifecycleState = 'ARCHIVED', lifecycleUpdatedAt = :atMillis, lifecycleReason = NULL WHERE id IN (:ids)")
    suspend fun archiveAll(ids: List<Long>, atMillis: Long): Int

    @Query("SELECT * FROM notification_events WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<NotificationEventEntity>

    /** EPIC-012: still-unseen events older than the cutoff count as ignored. */
    @Query("SELECT * FROM notification_events WHERE lifecycleState = 'NEW' AND archived = 0 AND postedAt < :beforeMillis ORDER BY postedAt ASC LIMIT :limit")
    suspend fun findStaleNew(beforeMillis: Long, limit: Int): List<NotificationEventEntity>

    /** EPIC-014 REPORT: the user's corrected category replaces the classifier's (the original is kept in the audit row). */
    @Query("UPDATE notification_events SET category = :category WHERE id = :eventId")
    suspend fun updateCategory(eventId: Long, category: String): Int

    /** EPIC-016 retrieval: archived rows included (archived does not mean it did not happen). */
    @Query("SELECT * FROM notification_events WHERE postedAt >= :fromMillis AND postedAt < :toMillis ORDER BY postedAt DESC LIMIT :limit")
    suspend fun findInRange(fromMillis: Long, toMillis: Long, limit: Int): List<NotificationEventEntity>

    @Query("UPDATE notification_events SET priority = :priority WHERE id = :eventId")
    suspend fun setPriority(eventId: Long, priority: Int): Int

    /** EPIC-020 memory ingestion: rows the intelligence pipeline has already processed, in id order. */
    @Query("SELECT * FROM notification_events WHERE id > :afterId AND intelligenceVersion > 0 ORDER BY id ASC LIMIT :limit")
    suspend fun findProcessedAfterId(afterId: Long, limit: Int): List<NotificationEventEntity>

    @Query("DELETE FROM notification_events")
    suspend fun deleteAll()
}
