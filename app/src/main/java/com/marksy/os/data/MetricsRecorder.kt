package com.marksy.os.data

import com.marksy.os.data.local.MetricCounterEntity
import com.marksy.os.data.local.MetricsDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Names of every metric Marksy collects (EPIC-022/023). Counts and durations only, never content. */
object Metric {
    const val CAPTURED = "captured"
    const val DUPLICATE = "duplicate"
    const val CROSS_SOURCE_DUPLICATE = "cross_source_duplicate"
    const val CAPTURE_FAILED = "capture_failed"
    const val PROCESSING_FAILED = "processing_failed"
    const val CLASSIFIED = "classified"
    const val UNCLASSIFIED = "unclassified"
    const val IMPORTANT = "important"
    const val PROCESSING_MS_SUM = "processing_ms_sum"
    const val PROCESSING_COUNT = "processing_count"
    const val DELIVERY_DELAY_MS_SUM = "delivery_delay_ms_sum"
    const val USER_INTERACTION = "user_interaction"
    const val CORRECTION = "correction"
    const val RULE_EXECUTION = "rule_execution"
    const val LEARNING_SIGNAL = "learning_signal"
    const val AI_CALL = "ai_call"
    const val AI_FAILURE = "ai_failure"
    const val AI_LATENCY_MS_SUM = "ai_latency_ms_sum"
}

class MetricsRecorder(
    private val dao: MetricsDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    fun day(at: Long = clock()): String = Instant.ofEpochMilli(at).atZone(zone()).toLocalDate().toString()

    /** Increments [metric] for "all" and each extra scope; metrics must never break the caller. */
    suspend fun count(metric: String, vararg scopes: String, delta: Long = 1, at: Long = clock()) {
        val d = day(at)
        val rows = (listOf(SCOPE_ALL) + scopes.filter { it.isNotBlank() }).distinct().map { MetricCounterEntity(d, it, metric, delta) }
        val pending = currentCoroutineContext()[Pending]
        if (pending != null) { pending.add(rows); return }
        write(rows)
    }

    /** Holds every count made inside [block] and writes the merged totals in one transaction, even if [block] fails. */
    suspend fun <T> batch(block: suspend () -> T): T {
        if (currentCoroutineContext()[Pending] != null) return block()
        val pending = Pending()
        try {
            return withContext(pending) { block() }
        } finally {
            withContext(NonCancellable) { write(pending.drain()) }
        }
    }

    private suspend fun write(rows: List<MetricCounterEntity>) {
        if (rows.isEmpty()) return
        try {
            dao.incrementAll(rows)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    private class Pending : AbstractCoroutineContextElement(Pending) {
        companion object Key : CoroutineContext.Key<Pending>
        private val totals = LinkedHashMap<Triple<String, String, String>, Long>()
        @Synchronized fun add(rows: List<MetricCounterEntity>) = rows.forEach { r -> totals.merge(Triple(r.day, r.scope, r.metric), r.value, Long::plus) }
        @Synchronized fun drain() = totals.map { (k, v) -> MetricCounterEntity(k.first, k.second, k.third, v) }.also { totals.clear() }
    }

    suspend fun range(from: LocalDate, to: LocalDate) = dao.range(from.toString(), to.toString())

    companion object {
        const val SCOPE_ALL = "all"
        fun connector(id: String) = "connector:$id"
        fun source(pkg: String) = "source:$pkg"
        fun category(name: String) = "category:$name"
    }
}
