package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * EPIC-018 rule audit trail. The (ruleId, ruleVersion, eventId) key makes execution idempotent:
 * a rule version acts on an event at most once, whether at capture or via "apply to history".
 */
@Entity(
    tableName = "rule_executions",
    primaryKeys = ["ruleId", "ruleVersion", "eventId"],
    indices = [Index(value = ["eventId"]), Index(value = ["executedAt"])]
)
data class RuleExecutionEntity(
    val ruleId: String,
    val ruleVersion: Int,
    val eventId: Long,
    val action: String,
    /** CAPTURE or HISTORY. */
    val trigger: String,
    val applied: Boolean,
    val note: String?,
    val executedAt: Long
)

@Dao
interface RuleExecutionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<RuleExecutionEntity>): List<Long>

    @Query("SELECT eventId FROM rule_executions WHERE ruleId = :ruleId AND ruleVersion = :version AND applied = 1")
    suspend fun executedEventIds(ruleId: String, version: Int): List<Long>

    @Query("SELECT * FROM rule_executions WHERE ruleId = :ruleId ORDER BY executedAt DESC LIMIT :limit")
    suspend fun history(ruleId: String, limit: Int): List<RuleExecutionEntity>

    @Query("SELECT COUNT(*) FROM rule_executions WHERE ruleId = :ruleId")
    suspend fun count(ruleId: String): Int

    /** Replaces an earlier "overridden" row once the rule is really applied. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RuleExecutionEntity)

    @Query("DELETE FROM rule_executions WHERE eventId NOT IN (SELECT id FROM notification_events)")
    suspend fun pruneOrphans(): Int
}
