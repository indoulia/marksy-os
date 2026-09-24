package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** EPIC-015 context graph node. (type, canonicalKey) is the stable reference across sources. */
@Entity(
    tableName = "context_entities",
    indices = [Index(value = ["type", "canonicalKey"], unique = true), Index(value = ["mergedIntoId"])]
)
data class ContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val canonicalKey: String,
    val displayName: String,
    val confidence: Float,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val mentionCount: Int,
    val sourceCount: Int,
    /** Set when merged (automatically or by the user) into another node; reads follow it. */
    val mergedIntoId: Long? = null
)

@Entity(
    tableName = "context_links",
    primaryKeys = ["entityId", "eventId"],
    indices = [Index(value = ["eventId"])]
)
data class ContextLink(
    val entityId: Long,
    val eventId: Long,
    val sourcePackage: String,
    val confidence: Float,
    val signal: String,
    val createdAt: Long
)

/** Undirected co-occurrence edge, stored with fromId < toId. */
@Entity(
    tableName = "context_relations",
    primaryKeys = ["fromId", "toId"],
    indices = [Index(value = ["toId"])]
)
data class ContextRelation(
    val fromId: Long,
    val toId: Long,
    val weight: Int,
    val confidence: Float,
    val lastSeenAt: Long
)

data class EntityEventRow(val eventId: Long, val postedAt: Long, val title: String, val sourceName: String, val category: String)

@Dao
interface ContextGraphDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntity(entity: ContextEntity): Long

    @Query("SELECT * FROM context_entities WHERE type = :type AND canonicalKey = :key")
    suspend fun findEntity(type: String, key: String): ContextEntity?

    @Query("SELECT * FROM context_entities WHERE id = :id")
    suspend fun entity(id: Long): ContextEntity?

    @Query("UPDATE context_entities SET lastSeenAt = MAX(lastSeenAt, :at), mentionCount = mentionCount + 1, confidence = MAX(confidence, :confidence), sourceCount = :sourceCount WHERE id = :id")
    suspend fun touchEntity(id: Long, at: Long, confidence: Float, sourceCount: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(link: ContextLink): Long

    @Query("SELECT COUNT(DISTINCT sourcePackage) FROM context_links WHERE entityId = :entityId")
    suspend fun sourceCount(entityId: Long): Int

    @Query("INSERT OR IGNORE INTO context_relations (fromId, toId, weight, confidence, lastSeenAt) VALUES (:fromId, :toId, 0, 0, :at)")
    suspend fun ensureRelation(fromId: Long, toId: Long, at: Long)

    @Query("UPDATE context_relations SET weight = weight + 1, confidence = 1.0 - 1.0 / (weight + 2), lastSeenAt = MAX(lastSeenAt, :at) WHERE fromId = :fromId AND toId = :toId")
    suspend fun bumpRelation(fromId: Long, toId: Long, at: Long)

    @Query("SELECT * FROM context_entities WHERE mergedIntoId IS NULL AND (displayName LIKE '%' || :q || '%' OR canonicalKey LIKE '%' || :q || '%') ORDER BY mentionCount DESC LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<ContextEntity>

    @Query("SELECT e.id AS eventId, e.postedAt, e.title, e.sourceName, e.category FROM context_links l JOIN notification_events e ON e.id = l.eventId WHERE l.entityId IN (:entityIds) ORDER BY e.postedAt DESC LIMIT :limit")
    suspend fun timeline(entityIds: List<Long>, limit: Int): List<EntityEventRow>

    @Query("SELECT id FROM context_entities WHERE id = :id OR mergedIntoId = :id")
    suspend fun withMerged(id: Long): List<Long>

    @Query("SELECT c.* FROM context_entities c JOIN context_links l ON l.entityId = c.id WHERE l.eventId = :eventId")
    suspend fun entitiesForEvent(eventId: Long): List<ContextEntity>

    @Query("SELECT * FROM context_relations WHERE fromId = :id OR toId = :id ORDER BY weight DESC LIMIT :limit")
    suspend fun relationsOf(id: Long, limit: Int): List<ContextRelation>

    @Query("DELETE FROM context_links WHERE entityId = :entityId AND eventId = :eventId")
    suspend fun deleteLink(entityId: Long, eventId: Long): Int

    @Query("UPDATE context_entities SET mentionCount = MAX(0, mentionCount - 1) WHERE id = :id")
    suspend fun decrementMentions(id: Long)

    @Query("UPDATE context_entities SET displayName = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    @Query("UPDATE context_entities SET mergedIntoId = :intoId WHERE id = :fromId OR mergedIntoId = :fromId")
    suspend fun markMerged(fromId: Long, intoId: Long)

    @Query("UPDATE context_entities SET mentionCount = mentionCount + :count, lastSeenAt = MAX(lastSeenAt, :lastSeen), firstSeenAt = MIN(firstSeenAt, :firstSeen) WHERE id = :id")
    suspend fun absorb(id: Long, count: Int, firstSeen: Long, lastSeen: Long)

    @Transaction
    suspend fun merge(fromId: Long, intoId: Long) {
        val from = entity(fromId) ?: return
        absorb(intoId, from.mentionCount, from.firstSeenAt, from.lastSeenAt)
        markMerged(fromId, intoId)
    }

    /** Graph rows for events removed by retention; keeps the graph from pointing at deleted events. */
    @Query("DELETE FROM context_links WHERE eventId NOT IN (SELECT id FROM notification_events)")
    suspend fun pruneOrphanLinks(): Int
}
