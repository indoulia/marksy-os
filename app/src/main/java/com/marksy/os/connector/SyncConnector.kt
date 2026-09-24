package com.marksy.os.connector

import android.content.Context
import org.json.JSONObject

/**
 * EPIC-021 pull connectors (provider APIs rather than notifications). A connector only fetches and
 * maps provider records; [ConnectorSyncer] feeds them through the same [IngestionPipeline] as
 * notification capture, so classification, dedup, rules and event intelligence are shared.
 */
data class SourceRecord(
    /** Stable provider id; the syncer namespaces it per connector. */
    val sourceKey: String,
    val title: String,
    val body: String,
    val postedAt: Long
)

data class SyncBatch(
    val upserts: List<SourceRecord>,
    /** Provider records deleted/cancelled since the last cursor. */
    val removedKeys: List<String> = emptyList(),
    /** Opaque provider checkpoint; persisted only after the whole batch was ingested. */
    val cursor: String?
)

class ConnectorException(val kind: Kind, message: String? = null, cause: Throwable? = null) : Exception(message ?: kind.name, cause) {
    enum class Kind(val retryable: Boolean) { PERMISSION(false), NOT_CONFIGURED(false), AUTH(false), UNAVAILABLE(true), TRANSIENT(true), MALFORMED(true) }
}

interface SyncConnector {
    val descriptor: ConnectorDescriptor
    val sourcePackage: String
    val sourceName: String
    /** Cheap permission/configuration check; must not touch the network or provider data. */
    fun state(): ConnectorState
    /** Fetches changes since [cursor] (null = first sync). Throws [ConnectorException] for known failures. */
    suspend fun sync(cursor: String?): SyncBatch
}

data class SyncStatus(
    val connectorId: String,
    val enabled: Boolean = false,
    val cursor: String? = null,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val lastAdded: Int = 0,
    val lastUpdated: Int = 0,
    val lastRemoved: Int = 0
)

interface SyncStore {
    fun load(connectorId: String): SyncStatus
    fun save(status: SyncStatus)
}

class PrefsSyncStore(context: Context) : SyncStore {
    private val prefs = context.getSharedPreferences("marksy_connector_sync", Context.MODE_PRIVATE)

    override fun load(connectorId: String): SyncStatus {
        val raw = prefs.getString(connectorId, null) ?: return SyncStatus(connectorId)
        return runCatching {
            val o = JSONObject(raw)
            fun long(k: String) = if (o.has(k) && !o.isNull(k)) o.getLong(k) else null
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
            SyncStatus(
                connectorId, o.optBoolean("enabled"), str("cursor"), long("lastAttemptAt"), long("lastSuccessAt"), str("lastError"),
                o.optInt("consecutiveFailures"), o.optInt("lastAdded"), o.optInt("lastUpdated"), o.optInt("lastRemoved")
            )
        }.getOrElse { SyncStatus(connectorId) }
    }

    override fun save(status: SyncStatus) {
        val o = JSONObject()
            .put("enabled", status.enabled).put("cursor", status.cursor ?: JSONObject.NULL)
            .put("lastAttemptAt", status.lastAttemptAt ?: JSONObject.NULL).put("lastSuccessAt", status.lastSuccessAt ?: JSONObject.NULL)
            .put("lastError", status.lastError ?: JSONObject.NULL).put("consecutiveFailures", status.consecutiveFailures)
            .put("lastAdded", status.lastAdded).put("lastUpdated", status.lastUpdated).put("lastRemoved", status.lastRemoved)
        // commit(): the cursor must be durable before the worker reports success.
        prefs.edit().putString(status.connectorId, o.toString()).commit()
    }
}

class ConnectorSyncer(
    private val pipeline: IngestionPipeline,
    private val store: SyncStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    sealed class Outcome {
        data class Synced(val added: Int, val updated: Int, val removed: Int, val unchanged: Int, val rejected: Int) : Outcome()
        data class Skipped(val state: ConnectorState) : Outcome()
        data class Failed(val kind: ConnectorException.Kind) : Outcome() { val retryable get() = kind.retryable }
    }

    suspend fun sync(connector: SyncConnector): Outcome {
        val id = connector.descriptor.id
        val status = store.load(id)
        if (!status.enabled) return Outcome.Skipped(ConnectorState.DISCONNECTED)
        val now = clock()
        val state = runCatching { connector.state() }.getOrDefault(ConnectorState.NOT_AVAILABLE)
        if (state != ConnectorState.ACTIVE) {
            store.save(status.copy(lastAttemptAt = now, lastError = state.name))
            return Outcome.Skipped(state)
        }
        val batch = try {
            connector.sync(status.cursor)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val kind = (e as? ConnectorException)?.kind ?: if (e is SecurityException) ConnectorException.Kind.PERMISSION else ConnectorException.Kind.TRANSIENT
            store.save(status.copy(lastAttemptAt = now, lastError = kind.name, consecutiveFailures = status.consecutiveFailures + 1))
            runCatching { pipeline.syncFailed(id, kind.name) }
            return Outcome.Failed(kind)
        }
        var added = 0; var updated = 0; var unchanged = 0; var rejected = 0; var failed = 0
        for (r in batch.upserts) {
            if (r.sourceKey.isBlank()) { rejected++; continue }
            val capture = RawCapture(id, connector.sourcePackage, connector.sourceName, key(id, r.sourceKey), r.title.take(MAX_TITLE), r.body.take(MAX_BODY), r.postedAt, replaceOnUpdate = true)
            when (pipeline.ingest(capture)) {
                is IngestionPipeline.Result.Stored -> added++
                is IngestionPipeline.Result.Updated -> updated++
                IngestionPipeline.Result.Duplicate -> unchanged++
                IngestionPipeline.Result.Empty -> rejected++
                is IngestionPipeline.Result.Failed -> failed++
            }
        }
        if (failed > 0) {
            // Keep the old cursor so the failed records are fetched again; re-ingesting the rest is a no-op (source-key dedup).
            store.save(status.copy(lastAttemptAt = now, lastError = "$failed record(s) failed to store", consecutiveFailures = status.consecutiveFailures + 1))
            return Outcome.Failed(ConnectorException.Kind.TRANSIENT)
        }
        var removed = 0
        for (k in batch.removedKeys) if (pipeline.retract(connector.sourcePackage, key(id, k), "Removed at source (${connector.sourceName})")) removed++
        store.save(
            status.copy(
                cursor = batch.cursor, lastAttemptAt = now, lastSuccessAt = clock(), lastError = if (rejected > 0) "$rejected record(s) rejected" else null,
                consecutiveFailures = 0, lastAdded = added, lastUpdated = updated, lastRemoved = removed
            )
        )
        return Outcome.Synced(added, updated, removed, unchanged, rejected)
    }

    fun setEnabled(connectorId: String, enabled: Boolean) {
        val s = store.load(connectorId)
        // Disconnecting drops the cursor so a later reconnect starts from a fresh snapshot.
        store.save(if (enabled) s.copy(enabled = true) else SyncStatus(connectorId, enabled = false))
    }

    fun status(connectorId: String) = store.load(connectorId)

    companion object {
        fun key(connectorId: String, sourceKey: String) = "$connectorId:$sourceKey"
        private const val MAX_TITLE = 200
        private const val MAX_BODY = 2_000
    }
}
