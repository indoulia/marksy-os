package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Reminders and tasks in one place: bills, EMIs, card dues, birthdays, your tasks and "Remind me" follow-ups.
 * Auto-created rows (SMS, contacts, remind-me) carry a dedupeKey so a repeat updates the same row.
 */
@Entity(
    tableName = "plan_items",
    indices = [Index(value = ["dedupeKey"], unique = true), Index(value = ["status"]), Index(value = ["dueAt"])]
)
data class PlanItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    val counterparty: String? = null,
    val amountMinor: Long? = null,
    val dueAt: Long? = null,
    val recurrence: String,
    val status: String,
    val origin: String,
    val sourceEventId: Long? = null,
    val dedupeKey: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null
)

@Dao
interface PlanItemDao {
    @Insert
    suspend fun insert(item: PlanItemEntity): Long

    @Update
    suspend fun update(item: PlanItemEntity): Int

    @Query("SELECT * FROM plan_items WHERE id = :id")
    suspend fun get(id: Long): PlanItemEntity?

    @Query("SELECT * FROM plan_items WHERE dedupeKey = :key")
    suspend fun byKey(key: String): PlanItemEntity?

    @Query("DELETE FROM plan_items WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM plan_items ORDER BY dueAt IS NULL, dueAt ASC, id ASC")
    suspend fun all(): List<PlanItemEntity>

    @Query("SELECT * FROM plan_items WHERE status != 'DONE' AND dueAt IS NOT NULL")
    suspend fun activeDated(): List<PlanItemEntity>

    /** Undated items last; done items keep their place so the board's Done column stays stable. */
    @Query("SELECT * FROM plan_items ORDER BY dueAt IS NULL, dueAt ASC, id ASC")
    fun observeAll(): Flow<List<PlanItemEntity>>
}
