package com.marksy.os.data

import android.content.Context
import com.marksy.os.data.local.LearningDao
import com.marksy.os.data.local.LearningOverrideEntity
import com.marksy.os.data.local.LearningSignalEntity
import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.PersonalLearning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** User switch for EPIC-012. When off nothing is recorded and no learned adjustment is applied. */
class LearningSettings(context: Context) {
    private val prefs = context.getSharedPreferences("marksy_learning", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    private companion object { const val KEY_ENABLED = "enabled" }
}

class LearningRepository(
    private val dao: LearningDao,
    private val eventDao: NotificationEventDao,
    private val isEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun record(events: List<NotificationEventEntity>, signal: PersonalLearning.Signal): Int {
        if (!isEnabled() || events.isEmpty()) return 0
        val now = clock()
        val rows = events.flatMap { e ->
            PersonalLearning.subjectsOf(e).map { s ->
                LearningSignalEntity(eventId = e.id, subjectType = s.type.name, subjectKey = s.key, label = s.label.take(MAX_LABEL), signal = signal.name, createdAt = now)
            }
        }
        return dao.insertSignals(rows).count { it != -1L }
    }

    /** Records interaction signals for a thread action; must be called before the action mutates state. */
    suspend fun recordAction(ids: List<Long>, action: PersonalLearning.Signal) {
        if (!isEnabled() || ids.isEmpty()) return
        val events = eventDao.getByIds(ids)
        val relevant = if (action == PersonalLearning.Signal.ARCHIVED_UNOPENED) events.filter { it.lifecycleState == "NEW" } else events
        record(relevant, action)
    }

    /** Idempotent sweep: unseen events older than a day become IGNORED signals. */
    suspend fun sweepIgnored(nowMillis: Long = clock()): Int {
        if (!isEnabled()) return 0
        val stale = eventDao.findStaleNew(nowMillis - PersonalLearning.IGNORED_AFTER_MS, SWEEP_LIMIT)
        return record(stale, PersonalLearning.Signal.IGNORED)
    }

    suspend fun pruneExpired(nowMillis: Long = clock()) = dao.pruneSignals(nowMillis - PersonalLearning.WINDOW_MS)

    suspend fun profile(nowMillis: Long = clock()): PersonalLearning.Profile =
        PersonalLearning.buildProfile(dao.counts(nowMillis - PersonalLearning.WINDOW_MS), dao.overrides())

    fun observeProfile(nowMillis: Long = clock()): Flow<PersonalLearning.Profile> =
        combine(dao.observeCounts(nowMillis - PersonalLearning.WINDOW_MS), dao.observeOverrides()) { counts, overrides ->
            PersonalLearning.buildProfile(counts, overrides)
        }

    fun observeHistory(limit: Int = 100): Flow<List<LearningSignalEntity>> = dao.observeHistory(limit)

    /** Explicit corrections are honoured even when learning is disabled. */
    suspend fun setPreference(subject: PersonalLearning.Subject, preference: PersonalLearning.Preference?) {
        if (preference == null) dao.deleteOverride(subject.type.name, subject.key)
        else dao.upsertOverride(LearningOverrideEntity(subject.type.name, subject.key, preference.name, subject.label.take(MAX_LABEL), clock()))
    }

    /** Clears learned history only; explicit corrections are the user's settings and stay. */
    suspend fun resetLearning() = dao.clearSignals()

    suspend fun clearCorrections() = dao.clearOverrides()

    private companion object {
        const val MAX_LABEL = 80
        const val SWEEP_LIMIT = 500
    }
}
