package com.marksy.os.data

import android.content.Context
import com.marksy.os.data.local.LearningDao
import com.marksy.os.data.local.MemoryDao
import com.marksy.os.data.local.MemoryEntryEntity
import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.intelligence.EventNormalizer
import com.marksy.os.intelligence.PersonalMemory
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

/** Per-kind learning controls and ingestion watermark for EPIC-020. */
interface MemorySettings {
    var enabled: Boolean
    var watermark: Long
    fun isKindEnabled(kind: PersonalMemory.Kind): Boolean
    fun setKindEnabled(kind: PersonalMemory.Kind, enabled: Boolean)
}

class PrefsMemorySettings(context: Context) : MemorySettings {
    private val prefs = context.getSharedPreferences("marksy_memory", Context.MODE_PRIVATE)
    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }
    override var watermark: Long
        get() = prefs.getLong("watermark", 0L)
        set(v) { prefs.edit().putLong("watermark", v).apply() }
    override fun isKindEnabled(kind: PersonalMemory.Kind) = prefs.getBoolean("kind_${kind.name}", true)
    override fun setKindEnabled(kind: PersonalMemory.Kind, enabled: Boolean) { prefs.edit().putBoolean("kind_${kind.name}", enabled).apply() }
}

class MemoryRepository(
    private val dao: MemoryDao,
    private val eventDao: NotificationEventDao,
    private val learningDao: LearningDao,
    private val settings: MemorySettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val metrics: MetricsRecorder? = null
) {
    fun observe(): Flow<List<MemoryEntryEntity>> = dao.observeActive(clock())

    /**
     * Folds events processed by the intelligence pipeline since the last run into memory.
     * Must run before retention prunes events (RetentionWorker calls it first).
     */
    suspend fun ingest(batch: Int = 500): Int {
        val now = clock()
        dao.deleteExpired(now)
        syncPreferences(now)
        if (!settings.enabled) return 0
        val enabled = PersonalMemory.Kind.entries.filter { settings.isKindEnabled(it) }.toSet()
        var processed = 0
        while (true) {
            val events = eventDao.findProcessedAfterId(settings.watermark, batch)
            if (events.isEmpty()) break
            // A cross-source duplicate is the same real-world event; learning from it would double-count evidence.
            events.filter { it.duplicateOfId == null }.flatMap { e -> PersonalMemory.observe(e, EventNormalizer.factsFromJson(e.intelligenceJson)) }
                .groupBy { it.kind to it.key }
                .forEach { (k, obs) ->
                    PersonalMemory.fold(dao.find(k.first.name, k.second), obs, enabled, now, zone())?.let { dao.upsert(it) }
                }
            settings.watermark = events.maxOf { it.id }
            processed += events.size
            if (events.size < batch) break
        }
        return processed
    }

    private suspend fun syncPreferences(now: Long) {
        learningDao.overrides().forEach { o ->
            val key = "${o.subjectType}|${o.subjectKey}"
            PersonalMemory.preferenceEntry(key, o.label, o.preference, now, dao.find(PersonalMemory.Kind.PREFERENCE.name, key))?.let { dao.upsert(it) }
        }
    }

    suspend fun forget(id: Long): Boolean {
        metrics?.count(Metric.CORRECTION, "correction:memory")
        return dao.forget(id, clock()) > 0
    }

    suspend fun correct(id: Long, label: String): Boolean {
        metrics?.count(Metric.CORRECTION, "correction:memory")
        return dao.correct(id, label.trim().take(60), clock()) > 0
    }

    /** Stops (or resumes) learning this kind; existing entries stay until erased or expired. */
    fun setKindEnabled(kind: PersonalMemory.Kind, enabled: Boolean) = settings.setKindEnabled(kind, enabled)

    fun setEnabled(enabled: Boolean) { settings.enabled = enabled }

    /** Irreversible (source events may already be pruned); the UI confirms first. User-owned entries stay. */
    suspend fun eraseLearned(kind: PersonalMemory.Kind? = null): Int =
        if (kind == null) dao.deleteAllLearned() else dao.deleteLearnedOfKind(kind.name)

    fun isEnabled() = settings.enabled
    fun isKindEnabled(kind: PersonalMemory.Kind) = settings.isKindEnabled(kind)
}
