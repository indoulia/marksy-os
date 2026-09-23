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
 * EPIC-012 learning history: one row per (event, subject, signal). The unique index makes
 * recording idempotent, so re-opening an event or re-running the ignored sweep never double counts.
 * Stores only subject keys (package / normalized sender / category), never notification text.
 */
@Entity(
    tableName = "learning_signals",
    indices = [
        Index(value = ["eventId", "subjectType", "signal"], unique = true),
        Index(value = ["subjectType", "subjectKey"]),
        Index(value = ["createdAt"])
    ]
)
data class LearningSignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val subjectType: String,
    val subjectKey: String,
    /** Display label (app name, sender name or category) so learning is inspectable. */
    val label: String,
    val signal: String,
    val createdAt: Long
)

/** Explicit user correction for a subject. Always outranks anything learned. */
@Entity(tableName = "learning_overrides", primaryKeys = ["subjectType", "subjectKey"])
data class LearningOverrideEntity(
    val subjectType: String,
    val subjectKey: String,
    val preference: String,
    val label: String,
    val createdAt: Long
)

data class SignalCount(val subjectType: String, val subjectKey: String, val signal: String, val count: Int, val lastAt: Long, val label: String = "")

@Dao
interface LearningDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSignals(signals: List<LearningSignalEntity>): List<Long>

    @Query("SELECT subjectType, subjectKey, signal, COUNT(*) AS count, MAX(createdAt) AS lastAt, MAX(label) AS label FROM learning_signals WHERE createdAt >= :sinceMillis GROUP BY subjectType, subjectKey, signal")
    fun observeCounts(sinceMillis: Long): Flow<List<SignalCount>>

    @Query("SELECT subjectType, subjectKey, signal, COUNT(*) AS count, MAX(createdAt) AS lastAt, MAX(label) AS label FROM learning_signals WHERE createdAt >= :sinceMillis GROUP BY subjectType, subjectKey, signal")
    suspend fun counts(sinceMillis: Long): List<SignalCount>

    @Query("SELECT * FROM learning_signals ORDER BY createdAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<LearningSignalEntity>>

    @Query("DELETE FROM learning_signals")
    suspend fun clearSignals()

    @Query("DELETE FROM learning_signals WHERE createdAt < :cutoff")
    suspend fun pruneSignals(cutoff: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(override: LearningOverrideEntity)

    @Query("DELETE FROM learning_overrides WHERE subjectType = :subjectType AND subjectKey = :subjectKey")
    suspend fun deleteOverride(subjectType: String, subjectKey: String)

    @Query("SELECT * FROM learning_overrides")
    fun observeOverrides(): Flow<List<LearningOverrideEntity>>

    @Query("SELECT * FROM learning_overrides")
    suspend fun overrides(): List<LearningOverrideEntity>

    @Query("DELETE FROM learning_overrides")
    suspend fun clearOverrides()
}
