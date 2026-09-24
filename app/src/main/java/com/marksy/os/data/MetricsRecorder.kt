package com.marksy.os.data

import com.marksy.os.data.local.MetricsDao
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
        try {
            val d = day(at)
            dao.increment(d, SCOPE_ALL, metric, delta)
            scopes.filter { it.isNotBlank() }.distinct().forEach { dao.increment(d, it, metric, delta) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    suspend fun range(from: LocalDate, to: LocalDate) = dao.range(from.toString(), to.toString())

    companion object {
        const val SCOPE_ALL = "all"
        fun connector(id: String) = "connector:$id"
        fun source(pkg: String) = "source:$pkg"
        fun category(name: String) = "category:$name"
    }
}
