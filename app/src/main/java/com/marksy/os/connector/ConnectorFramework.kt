package com.marksy.os.connector

import com.marksy.os.data.Metric
import com.marksy.os.data.MetricsRecorder
import com.marksy.os.data.RuleRunner
import com.marksy.os.data.local.ConnectorDao
import com.marksy.os.data.local.ConnectorEventEntity
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.EventIntelligencePipeline
import com.marksy.os.intelligence.RuleApplication
import com.marksy.os.intelligence.RuleEngine
import com.marksy.os.notification.EventFingerprint
import com.marksy.os.notification.NotificationClassifier
import com.marksy.os.notification.NotificationTextExtractor
import java.util.Locale

/**
 * EPIC-021 connector architecture:
 * Connector (Android surface: listener, accessibility, ...) -> [SourceAdapter] -> [IngestionPipeline]
 * (normalize, classify, dedup, rules, persist) -> EventIntelligencePipeline.
 * Adding a source means adding a connector/adapter; nothing in the pipeline changes.
 */

/** Common event contract every connector produces. */
data class RawCapture(
    val connectorId: String,
    val sourcePackage: String,
    val sourceName: String,
    /** Stable per-source identity (e.g. the StatusBarNotification key). */
    val sourceKey: String,
    val title: String,
    val body: String,
    val postedAt: Long,
    /** Pull connectors send the provider's current full record, so an update replaces content instead of appending lines. */
    val replaceOnUpdate: Boolean = false
)

/** Source-specific clean-up before classification. Must be pure and must not drop content silently. */
interface SourceAdapter {
    val id: String
    val label: String
    fun handles(sourcePackage: String): Boolean
    fun adapt(raw: RawCapture): RawCapture = raw
}

enum class ConnectorState { ACTIVE, DISCONNECTED, NEEDS_PERMISSION, NOT_CONFIGURED, NOT_AVAILABLE }

data class ConnectorDescriptor(
    val id: String,
    val label: String,
    /** How data actually arrives; stated in the UI so nothing is implied that doesn't exist. */
    val mechanism: String,
    val adapters: List<SourceAdapter> = emptyList()
)

object Adapters {
    private fun pkgIn(pkg: String, vararg tokens: String) = pkg.lowercase(Locale.ROOT).let { p -> tokens.any { p.contains(it) } }

    /** WhatsApp notification titles look like "Group (3 messages): Rahul"; keep the sender as the title. */
    val WHATSAPP = object : SourceAdapter {
        override val id = "whatsapp"
        override val label = "WhatsApp"
        override fun handles(sourcePackage: String) = sourcePackage.lowercase(Locale.ROOT) in setOf("com.whatsapp", "com.whatsapp.w4b")
        // Only the running count is removed: it changes on every message and would split threads/fingerprints.
        override fun adapt(raw: RawCapture): RawCapture =
            raw.copy(title = raw.title.replace(Regex("\\s*\\(\\d+ (?:new )?messages?\\)"), "").trim().ifBlank { raw.title })
    }
    val GMAIL = object : SourceAdapter {
        override val id = "gmail"
        override val label = "Gmail"
        override fun handles(sourcePackage: String) = sourcePackage.lowercase(Locale.ROOT) == "com.google.android.gm"
    }
    val SMS = object : SourceAdapter {
        override val id = "sms"
        override val label = "SMS"
        override fun handles(sourcePackage: String) = pkgIn(sourcePackage, "messaging", "mms", "sms", "securesms")
    }
    val CALENDAR = object : SourceAdapter {
        override val id = "calendar"
        override val label = "Calendar reminders"
        override fun handles(sourcePackage: String) = pkgIn(sourcePackage, "calendar")
    }
    val BANKING = object : SourceAdapter {
        override val id = "banking"
        override val label = "Banking & payments"
        override fun handles(sourcePackage: String) = pkgIn(sourcePackage, "hdfc", "icici", "sbi", "axis", "kotak", "phonepe", "paytm", "nbu.paisa", "bhim", "upi")
    }
    val SHOPPING = object : SourceAdapter {
        override val id = "shopping"
        override val label = "Delivery & shopping"
        override fun handles(sourcePackage: String) = pkgIn(sourcePackage, "amazon", "flipkart", "myntra", "swiggy", "zomato", "delhivery", "bluedart", "meesho", "ajio")
    }
    val ALL = listOf(WHATSAPP, GMAIL, SMS, CALENDAR, BANKING, SHOPPING)
}

object ConnectorRegistry {
    const val NOTIFICATIONS = "android-notifications"
    const val WHATSAPP_ACCESSIBILITY = "whatsapp-accessibility"

    val NOTIFICATION_LISTENER = ConnectorDescriptor(NOTIFICATIONS, "Android notifications", "Notification access (all apps)", Adapters.ALL)
    val WHATSAPP_WATCHLIST = ConnectorDescriptor(WHATSAPP_ACCESSIBILITY, "WhatsApp watch-list", "Accessibility service, allow-listed senders only")

    val ALL = listOf(NOTIFICATION_LISTENER, WHATSAPP_WATCHLIST)

    fun adapterFor(connectorId: String, sourcePackage: String): SourceAdapter? =
        ALL.firstOrNull { it.id == connectorId }?.adapters?.firstOrNull { it.handles(sourcePackage) }
}

/** Shared ingestion for every connector. Failures are isolated per capture and recorded per connector. */
class IngestionPipeline(
    private val dao: NotificationEventDao,
    private val connectors: ConnectorDao,
    private val metrics: MetricsRecorder,
    private val rules: () -> List<RuleEngine.Rule>,
    private val ruleRunner: RuleRunner?,
    private val intelligence: EventIntelligencePipeline?,
    private val onTradingCaptured: () -> Unit = {},
    /** Newly stored event (e.g. a bill due becomes a plan reminder); failures never affect capture. */
    private val onStored: suspend (NotificationEventEntity) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    sealed class Result {
        data class Stored(val eventId: Long) : Result()
        data object Duplicate : Result()
        /** Same notification re-posted with new content; the stored row was updated. */
        data class Updated(val eventId: Long) : Result()
        data object Empty : Result()
        data class Failed(val reason: String) : Result()
    }

    // A capture makes dozens of counter updates; batching turns them into one transaction.
    suspend fun ingest(input: RawCapture): Result = metrics.batch { ingestNow(input) }

    private suspend fun ingestNow(input: RawCapture): Result {
        if (input.title.isBlank() && input.body.isBlank()) return Result.Empty
        // An adapter bug must not stop capture: fall back to the unadapted capture.
        val adapter = ConnectorRegistry.adapterFor(input.connectorId, input.sourcePackage)
        val raw = adapter?.let { a -> runCatching { a.adapt(input) }.getOrNull() } ?: input
        return try {
            val result = NotificationClassifier.classify(raw.sourcePackage, raw.title, raw.body)
            val isTrading = result.category == NotificationClassifier.Category.TRADING
            val fingerprint = EventFingerprint.create(raw.sourcePackage, result.category.name, raw.title, raw.body, raw.postedAt)
            // Ingestion-level dedup: same source key, or same content within the fingerprint window.
            val scopes = arrayOf(MetricsRecorder.connector(input.connectorId), MetricsRecorder.source(raw.sourcePackage))
            // Apps re-post the same key with new messages: fold new lines into the stored event (and
            // re-derive it) instead of dropping them as a duplicate.
            dao.findBySourceKey(raw.sourcePackage, raw.sourceKey)?.let { existing ->
                val body = if (raw.replaceOnUpdate) raw.body else NotificationTextExtractor.merge(existing.body, raw.body)
                val title = raw.title.ifBlank { existing.title }
                if (body == existing.body && title == existing.title) {
                    // A pull connector re-sending an unchanged record is a refresh, not a duplicate capture.
                    if (!raw.replaceOnUpdate) metrics.count(Metric.DUPLICATE, *scopes)
                    return Result.Duplicate
                }
                dao.updateContent(existing.id, title, body, maxOf(existing.postedAt, raw.postedAt))
                runCatching { intelligence?.process(existing.id) }
                return Result.Updated(existing.id)
            }
            if (dao.findIdByFingerprint(raw.sourcePackage, fingerprint) != null) {
                metrics.count(Metric.DUPLICATE, *scopes)
                return Result.Duplicate
            }
            val base = NotificationEventEntity(
                sourcePackage = raw.sourcePackage, sourceName = raw.sourceName, sourceKey = raw.sourceKey, eventFingerprint = fingerprint,
                title = raw.title, body = raw.body, postedAt = raw.postedAt, category = result.category.name, priority = result.priority,
                confidence = result.confidence, isTrading = isTrading,
                deliveryState = if (isTrading) DeliveryState.PENDING.name else DeliveryState.NOT_APPLICABLE.name
            )
            val applied = RuleApplication.apply(rules(), base)
            val id = dao.insert(applied.event)
            if (id == -1L) {
                metrics.count(Metric.DUPLICATE, *scopes)
                return Result.Duplicate
            }
            metrics.count(Metric.CAPTURED, *scopes, MetricsRecorder.category(result.category.name))
            metrics.count(if (result.category == NotificationClassifier.Category.OTHER) Metric.UNCLASSIFIED else Metric.CLASSIFIED, *scopes)
            metrics.count(Metric.DELIVERY_DELAY_MS_SUM, *scopes, delta = (clock() - raw.postedAt).coerceIn(0, MAX_DELAY_MS))
            if (isTrading && !applied.archived) runCatching(onTradingCaptured)
            runCatching { onStored(applied.event.copy(id = id)) }
            runCatching { ruleRunner?.recordCapture(id, applied.evaluation) }
            if (applied.evaluation.matchedRules.isNotEmpty()) metrics.count(Metric.RULE_EXECUTION, *scopes, delta = applied.evaluation.matchedRules.size.toLong())
            val started = clock()
            try {
                val outcome = intelligence?.process(id)
                metrics.count(Metric.PROCESSING_MS_SUM, *scopes, delta = clock() - started)
                metrics.count(Metric.PROCESSING_COUNT, *scopes)
                if (outcome?.duplicateOfId != null) metrics.count(Metric.CROSS_SOURCE_DUPLICATE, *scopes)
                if ((outcome?.normalized?.importance ?: 0) >= IMPORTANT_SCORE) metrics.count(Metric.IMPORTANT, *scopes)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                metrics.count(Metric.PROCESSING_FAILED, *scopes)
                record(input.connectorId, adapter?.id, ConnectorEventEntity.PROCESSING_FAILED, e.javaClass.simpleName)
            }
            Result.Stored(id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            runCatching { record(input.connectorId, adapter?.id, ConnectorEventEntity.FAILED, e.javaClass.simpleName) }
            metrics.count(Metric.CAPTURE_FAILED, MetricsRecorder.connector(input.connectorId), MetricsRecorder.source(input.sourcePackage))
            Result.Failed(e.javaClass.simpleName)
        }
    }

    /** A record deleted/cancelled at its source resolves the stored event (kept for history, not deleted). */
    suspend fun retract(sourcePackage: String, sourceKey: String, reason: String): Boolean {
        val existing = dao.findBySourceKey(sourcePackage, sourceKey) ?: return false
        return dao.resolve(listOf(existing.id), reason.take(120), clock()) > 0
    }

    suspend fun syncFailed(connectorId: String, detail: String) = record(connectorId, null, ConnectorEventEntity.FAILED, detail)

    suspend fun connected(connectorId: String) = record(connectorId, null, ConnectorEventEntity.CONNECTED)
    suspend fun disconnected(connectorId: String) = record(connectorId, null, ConnectorEventEntity.DISCONNECTED)

    private suspend fun record(connectorId: String, adapterId: String?, type: String, detail: String? = null) {
        connectors.insert(ConnectorEventEntity(connectorId = connectorId, adapterId = adapterId, type = type, detail = detail, at = clock()))
    }

    private companion object {
        const val IMPORTANT_SCORE = 70
        const val MAX_DELAY_MS = 10 * 60 * 1000L
    }
}
