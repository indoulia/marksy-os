package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * EPIC-020 personal memory. Outlives the 7-day event retention on purpose, so it stores only
 * derived facts (names, amounts, cadence, observation times) and ids of the events it came from.
 */
@Entity(
    tableName = "memory_entries",
    indices = [Index(value = ["kind", "memoryKey"], unique = true), Index(value = ["state"]), Index(value = ["expiresAt"])]
)
data class MemoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val memoryKey: String,
    val label: String,
    val confidence: Float,
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val observations: Int,
    val expiresAt: Long?,
    /** LEARNED or USER (explicit settings / corrections). */
    val origin: String,
    /** ACTIVE, CORRECTED (user edited; learner may not overwrite), FORGOTTEN (tombstone; never re-learned). */
    val state: String,
    /** Bounded JSON: observation times, amounts, cadence, derivedFromEventIds. */
    val detailJson: String,
    val updatedAt: Long
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_entries WHERE kind = :kind AND memoryKey = :key")
    suspend fun find(kind: String, key: String): MemoryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MemoryEntryEntity): Long

    @Query("SELECT * FROM memory_entries WHERE state != 'FORGOTTEN' AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY kind, confidence DESC, lastObservedAt DESC")
    fun observeActive(now: Long): Flow<List<MemoryEntryEntity>>

    @Query("SELECT * FROM memory_entries WHERE state != 'FORGOTTEN' AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY kind, confidence DESC")
    suspend fun active(now: Long): List<MemoryEntryEntity>

    @Query("SELECT * FROM memory_entries WHERE id = :id")
    suspend fun get(id: Long): MemoryEntryEntity?

    /** Forgetting keeps only a tombstone (kind + key) so the learner never recreates it; derived detail is erased. */
    @Query("UPDATE memory_entries SET state = 'FORGOTTEN', label = '', detailJson = '{}', observations = 0, updatedAt = :now WHERE id = :id")
    suspend fun forget(id: Long, now: Long): Int

    @Query("UPDATE memory_entries SET label = :label, state = 'CORRECTED', origin = 'USER', confidence = 1.0, expiresAt = NULL, updatedAt = :now WHERE id = :id AND state != 'FORGOTTEN'")
    suspend fun correct(id: Long, label: String, now: Long): Int

    /** Expired learned entries are deleted outright (user-owned ones never expire). */
    @Query("DELETE FROM memory_entries WHERE origin = 'LEARNED' AND state = 'ACTIVE' AND expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM memory_entries WHERE kind = :kind AND origin = 'LEARNED' AND state = 'ACTIVE'")
    suspend fun deleteLearnedOfKind(kind: String): Int

    @Query("DELETE FROM memory_entries WHERE origin = 'LEARNED' AND state = 'ACTIVE'")
    suspend fun deleteAllLearned(): Int
}
