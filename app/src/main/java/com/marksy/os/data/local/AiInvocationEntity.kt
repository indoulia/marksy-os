package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** EPIC-019/022 AI metrics. Deliberately no prompt, input or output text. */
@Entity(tableName = "ai_invocations", indices = [Index(value = ["at"])])
data class AiInvocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val task: String,
    val modelId: String?,
    val modelVersion: String?,
    val templateId: String,
    val templateVersion: Int,
    val outcome: String,
    val latencyMs: Long,
    val confidence: Double?,
    val at: Long
)

data class AiOutcomeCount(val outcome: String, val count: Int, val avgLatencyMs: Double)

@Dao
interface AiInvocationDao {
    @Insert
    suspend fun insert(row: AiInvocationEntity): Long

    @Query("SELECT outcome, COUNT(*) AS count, AVG(latencyMs) AS avgLatencyMs FROM ai_invocations WHERE at >= :since GROUP BY outcome")
    suspend fun outcomesSince(since: Long): List<AiOutcomeCount>

    @Query("SELECT * FROM ai_invocations WHERE at >= :since ORDER BY at ASC")
    suspend fun since(since: Long): List<AiInvocationEntity>

    @Query("DELETE FROM ai_invocations WHERE at < :cutoff")
    suspend fun prune(cutoff: Long): Int
}
