package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * EPIC-021/022/023 connector lifecycle and failure log. Only state changes and failures are
 * logged here (bounded); per-capture volume goes to [MetricCounterEntity]. Never content.
 */
@Entity(tableName = "connector_events", indices = [Index(value = ["connectorId", "at"]), Index(value = ["at"])])
data class ConnectorEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectorId: String,
    val adapterId: String?,
    val type: String,
    val detail: String?,
    val at: Long
) {
    companion object {
        const val CONNECTED = "CONNECTED"
        const val DISCONNECTED = "DISCONNECTED"
        const val FAILED = "FAILED"
        const val PROCESSING_FAILED = "PROCESSING_FAILED"
    }
}

/**
 * Daily counters that survive event retention, so a 30-day validation never has to be
 * reconstructed from (already deleted) events. day = local ISO date, scope = "all",
 * "connector:<id>", "source:<package>" or "category:<name>".
 */
@Entity(tableName = "metric_counters", primaryKeys = ["day", "scope", "metric"], indices = [Index(value = ["metric"])])
data class MetricCounterEntity(val day: String, val scope: String, val metric: String, val value: Long)

data class ConnectorLastEvent(val connectorId: String, val type: String, val at: Long)

@Dao
interface ConnectorDao {
    @Insert
    suspend fun insert(event: ConnectorEventEntity): Long

    @Query("SELECT * FROM connector_events WHERE connectorId = :connectorId AND at >= :since ORDER BY at ASC")
    suspend fun eventsSince(connectorId: String, since: Long): List<ConnectorEventEntity>

    /** Last lifecycle event before [before], needed to know the state at the start of a window. */
    @Query("SELECT * FROM connector_events WHERE connectorId = :connectorId AND type IN ('CONNECTED','DISCONNECTED') AND at < :before ORDER BY at DESC LIMIT 1")
    suspend fun lastLifecycleBefore(connectorId: String, before: Long): ConnectorEventEntity?

    @Query("SELECT * FROM connector_events WHERE type IN ('FAILED','PROCESSING_FAILED') AND at >= :since ORDER BY at DESC LIMIT :limit")
    suspend fun failuresSince(since: Long, limit: Int): List<ConnectorEventEntity>

    @Query("DELETE FROM connector_events WHERE at < :cutoff")
    suspend fun prune(cutoff: Long): Int
}

@Dao
interface MetricsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertZero(row: MetricCounterEntity): Long

    @Query("UPDATE metric_counters SET value = value + :delta WHERE day = :day AND scope = :scope AND metric = :metric")
    suspend fun add(day: String, scope: String, metric: String, delta: Long): Int

    /** minSdk 26 predates SQLite UPSERT, so increment is insert-if-missing then update, atomically. */
    @Transaction
    suspend fun increment(day: String, scope: String, metric: String, delta: Long = 1) {
        insertZero(MetricCounterEntity(day, scope, metric, 0))
        add(day, scope, metric, delta)
    }

    @Query("SELECT * FROM metric_counters WHERE day >= :fromDay AND day <= :toDay ORDER BY day ASC, scope ASC, metric ASC")
    suspend fun range(fromDay: String, toDay: String): List<MetricCounterEntity>

    @Query("DELETE FROM metric_counters WHERE day < :beforeDay")
    suspend fun prune(beforeDay: String): Int
}
