package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * EPIC-014 action history and audit trail. Rows are never deleted by action code; state moves
 * PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED and every change bumps updatedAt.
 */
@Entity(
    tableName = "event_actions",
    indices = [Index(value = ["eventId"]), Index(value = ["state"]), Index(value = ["type"])]
)
data class EventActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val type: String,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** For REMIND / MARK_EXPECTED: when it is due. */
    val scheduledFor: Long? = null,
    /** Small structured detail, e.g. the corrected category for REPORT. Never notification text. */
    val detail: String? = null,
    val error: String? = null,
    val attempts: Int = 0
)

@Dao
interface EventActionDao {
    @Insert
    suspend fun insert(action: EventActionEntity): Long

    @Query("SELECT * FROM event_actions WHERE id = :id")
    suspend fun get(id: Long): EventActionEntity?

    @Query("UPDATE event_actions SET state = :to, updatedAt = :at, error = :error, attempts = attempts + :attemptDelta WHERE id = :id AND state = :from")
    suspend fun transition(id: Long, from: String, to: String, at: Long, error: String? = null, attemptDelta: Int = 0): Int

    @Query("SELECT * FROM event_actions WHERE eventId = :eventId ORDER BY createdAt DESC")
    fun observeForEvent(eventId: Long): Flow<List<EventActionEntity>>

    @Query("SELECT * FROM event_actions WHERE type = :type AND state = 'PENDING' ORDER BY scheduledFor ASC")
    suspend fun pendingOfType(type: String): List<EventActionEntity>

    /** Active "expected" items for the briefing / EPIC-017. */
    @Query("SELECT * FROM event_actions WHERE type = 'MARK_EXPECTED' AND state = 'SUCCEEDED' ORDER BY scheduledFor ASC")
    suspend fun expected(): List<EventActionEntity>

    @Query("SELECT * FROM event_actions WHERE createdAt >= :since ORDER BY createdAt DESC")
    suspend fun since(since: Long): List<EventActionEntity>

    /** A RUNNING row older than the cutoff was interrupted (process death); make it retryable. */
    @Query("UPDATE event_actions SET state = 'PENDING', updatedAt = :at WHERE state = 'RUNNING' AND updatedAt < :cutoff")
    suspend fun recoverStaleRunning(cutoff: Long, at: Long): Int
}
