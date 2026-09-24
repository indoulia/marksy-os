package com.marksy.os.intelligence

import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.ContextGraphDao
import com.marksy.os.data.local.ContextLink
import com.marksy.os.data.local.EntityEventRow
import com.marksy.os.data.local.NotificationEventEntity
import java.util.Locale

/**
 * EPIC-015 cross-source context graph. Nodes are people, organisations, merchants, banks,
 * couriers, stocks, apps and reference ids (orders, shipments, transactions); edges are
 * co-occurrence within one event. Incremental and idempotent per event.
 */
class ContextGraph(private val dao: ContextGraphDao, private val clock: () -> Long = System::currentTimeMillis) {
    enum class NodeType { PERSON, COMPANY, MERCHANT, BANK, DELIVERY, STOCK, APP, ORDER, SHIPMENT, TRANSACTION }

    data class Node(val type: NodeType, val key: String, val displayName: String, val confidence: Float, val signal: String)

    /** Links an event's extracted facts into the graph; safe to call repeatedly for the same event. */
    suspend fun index(event: NotificationEventEntity, facts: EventExtractor.Facts): List<Long> {
        data class Linked(val id: Long, val type: NodeType, val isNew: Boolean)
        val linked = nodesFor(facts).mapNotNull { node ->
            val existing = dao.findEntity(node.type.name, node.key)
            val id = existing?.let { it.mergedIntoId ?: it.id } ?: dao.insertEntity(
                ContextEntity(type = node.type.name, canonicalKey = node.key, displayName = node.displayName, confidence = node.confidence,
                    firstSeenAt = event.postedAt, lastSeenAt = event.postedAt, mentionCount = 0, sourceCount = 0)
            ).takeIf { it != -1L } ?: dao.findEntity(node.type.name, node.key)?.id ?: return@mapNotNull null
            val isNew = dao.insertLink(ContextLink(id, event.id, event.sourcePackage, node.confidence, node.signal, clock())) != -1L
            if (isNew) dao.touchEntity(id, event.postedAt, node.confidence, dao.sourceCount(id))
            Linked(id, node.type, isNew)
        }.distinctBy { it.id }
        // Co-occurrence edges, skipping the ubiquitous APP node which would connect everything.
        // An edge is only counted when one endpoint was newly linked, so re-indexing never inflates weights.
        val related = linked.filter { it.type != NodeType.APP }.sortedBy { it.id }
        for (i in related.indices) for (j in i + 1 until related.size) {
            if (!related[i].isNew && !related[j].isNew) continue
            dao.ensureRelation(related[i].id, related[j].id, event.postedAt)
            dao.bumpRelation(related[i].id, related[j].id, event.postedAt)
        }
        return linked.map { it.id }
    }

    suspend fun search(query: String, limit: Int = 10): List<ContextEntity> =
        if (query.isBlank()) emptyList() else dao.search(query.trim(), limit)

    /** Context timeline across every source, following merges. */
    suspend fun timeline(entityId: Long, limit: Int = 50): List<EntityEventRow> {
        val root = dao.entity(entityId)?.let { it.mergedIntoId ?: it.id } ?: return emptyList()
        return dao.timeline(dao.withMerged(root), limit)
    }

    suspend fun entitiesFor(eventId: Long): List<ContextEntity> = dao.entitiesForEvent(eventId)

    // ---- corrections ----
    suspend fun merge(fromId: Long, intoId: Long) { if (fromId != intoId) dao.merge(fromId, intoId) }
    suspend fun unlink(entityId: Long, eventId: Long) { if (dao.deleteLink(entityId, eventId) > 0) dao.decrementMentions(entityId) }
    suspend fun rename(entityId: Long, name: String) = dao.rename(entityId, name.trim().take(60))

    companion object {
        /** Duplicate names collapse by construction: "Amazon Retail Pvt Ltd" and "AMAZON RETAIL" share a key. */
        fun canonicalKey(type: NodeType, value: String): String {
            val base = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}@ ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (type in REFERENCE_TYPES) return base.replace(" ", "")
            return base.split(' ').filterNot { it in LEGAL_SUFFIXES }.joinToString(" ").ifBlank { base }
        }

        fun nodesFor(facts: EventExtractor.Facts): List<Node> {
            val entityNodes = facts.entities.map { e ->
                val type = NodeType.valueOf(e.type.name)
                Node(type, canonicalKey(type, e.value), e.value, e.confidence, e.signal)
            }
            val refNodes = facts.references.mapNotNull { r ->
                val type = when (r.type) {
                    EventExtractor.ReferenceType.ORDER -> NodeType.ORDER
                    EventExtractor.ReferenceType.TRACKING -> NodeType.SHIPMENT
                    EventExtractor.ReferenceType.TRANSACTION -> NodeType.TRANSACTION
                    EventExtractor.ReferenceType.REFERENCE -> return@mapNotNull null
                }
                Node(type, canonicalKey(type, r.value), r.value, .95f, "reference id")
            }
            return (entityNodes + refNodes).filter { it.key.isNotBlank() }.distinctBy { it.type to it.key }
        }

        private val REFERENCE_TYPES = setOf(NodeType.ORDER, NodeType.SHIPMENT, NodeType.TRANSACTION)
        private val LEGAL_SUFFIXES = setOf("ltd", "limited", "pvt", "private", "inc", "llp", "llc", "corp", "co")
    }
}
