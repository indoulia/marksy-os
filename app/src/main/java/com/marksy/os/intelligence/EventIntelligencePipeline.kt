package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Persists EPIC-010 intelligence for captured events: normalization, cross-source dedup,
 * reference threading and automatic thread resolution. Idempotent per event: rerunning on an
 * already-processed row only refreshes derived fields and never re-applies dedup/resolution.
 */
class EventIntelligencePipeline(
    private val dao: NotificationEventDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val graph: ContextGraph? = null
) {
    data class Outcome(val eventId: Long, val normalized: NormalizedEvent, val duplicateOfId: Long?, val resolvedCount: Int)

    suspend fun process(eventId: Long): Outcome? = LOCK.withLock {
        val event = dao.getById(eventId) ?: return@withLock null
        processLocked(event)
    }

    /** Bounded backfill/re-derive batch; returns how many rows were processed. */
    suspend fun processPending(limit: Int = BATCH_SIZE): Int = LOCK.withLock {
        val batch = dao.findNeedingIntelligence(VERSION, limit)
        batch.forEach { event ->
            try {
                processLocked(event)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                markFailed(event)
            }
        }
        batch.size
    }

    // Serialized: two sources posting the same payment concurrently must not both become canonical.
    private suspend fun processLocked(event: NotificationEventEntity): Outcome {
        val n = EventNormalizer.normalize(event, zone)
        val firstPass = event.intelligenceVersion == 0

        val duplicateOfId = if (!firstPass) {
            event.duplicateOfId
        } else n.correlationKey?.let { key ->
            val candidates = dao.findCorrelatedCanonical(
                correlationKey = key,
                excludeId = event.id,
                fromMillis = event.postedAt - DEDUP_WINDOW_MS,
                toMillis = event.postedAt + DEDUP_WINDOW_MS,
                sourcePackage = event.sourcePackage,
                otherSourceOnly = !n.correlationIsStrong
            )
            if (n.correlationIsStrong) candidates.firstOrNull()
            else candidates.firstOrNull { id ->
                dao.getById(id)?.let { counterpartiesCompatible(n.facts, EventNormalizer.factsFromJson(it.intelligenceJson)) } ?: false
            }
        }
        val reasons = if (duplicateOfId != null) n.reasons + "Duplicate of event #$duplicateOfId from another observation" else n.reasons
        val normalized = n.copy(reasons = reasons)

        dao.updateIntelligence(
            eventId = event.id,
            importance = normalized.importance,
            confidence = normalized.confidence,
            threadKey = normalized.threadKey,
            correlationKey = normalized.correlationKey,
            duplicateOfId = duplicateOfId,
            json = EventNormalizer.toJson(normalized, duplicateOfId),
            version = VERSION
        )

        // Only reference threads are specific enough to auto-resolve; title-based threads could merge unrelated items.
        val resolved = if (firstPass && normalized.facts.terminal && normalized.threadKey.startsWith(EventNormalizer.REF_THREAD_PREFIX)) {
            dao.resolveOpenInThread(
                threadKey = normalized.threadKey,
                excludeId = event.id,
                upToMillis = event.postedAt,
                reason = "Resolved by later event #${event.id}",
                atMillis = clock()
            )
        } else 0

        // Graph indexing is idempotent and secondary: a failure must not lose the event's own intelligence.
        try {
            graph?.index(event, normalized.facts)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }

        return Outcome(event.id, normalized, duplicateOfId, resolved)
    }

    /**
     * Amount-only matches: two payments of the same amount to *different* named counterparties are
     * different transactions. Unknown on either side (typical bank SMS) stays compatible.
     */
    private fun counterpartiesCompatible(a: EventExtractor.Facts, b: EventExtractor.Facts): Boolean {
        fun names(f: EventExtractor.Facts) = f.entities.filter { it.type in COUNTERPARTY_TYPES }.map { ContextGraph.canonicalKey(ContextGraph.NodeType.MERCHANT, it.value) }.toSet()
        val x = names(a)
        val y = names(b)
        return x.isEmpty() || y.isEmpty() || x.intersect(y).isNotEmpty()
    }

    /** A row that cannot be derived is stamped (with a traceable marker) so it can never stall the backfill. */
    private suspend fun markFailed(event: NotificationEventEntity) {
        dao.updateIntelligence(
            eventId = event.id,
            importance = EventIntelligence.importance(event).attentionScore,
            confidence = event.confidence,
            threadKey = EventIntelligence.legacyThreadKey(event),
            correlationKey = null,
            duplicateOfId = event.duplicateOfId,
            json = FAILED_JSON,
            version = VERSION
        )
    }

    companion object {
        const val FAILED_JSON = "{\"error\":\"derivation_failed\"}"
        /** Bump when extraction/normalization changes so stored rows are re-derived in the background. */
        const val VERSION = 1
        const val BATCH_SIZE = 200
        const val DEDUP_WINDOW_MS = 10 * 60 * 1000L
        private val LOCK = Mutex()
        private val COUNTERPARTY_TYPES = setOf(EventExtractor.EntityType.MERCHANT, EventExtractor.EntityType.PERSON, EventExtractor.EntityType.COMPANY)
    }
}
