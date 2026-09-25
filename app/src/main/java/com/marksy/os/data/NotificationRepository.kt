package com.marksy.os.data

import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.EventLifecycle
import com.marksy.os.intelligence.PersonalLearning
import com.marksy.os.notification.NotificationTextExtractor
import kotlinx.coroutines.flow.Flow

class NotificationRepository(
    private val dao: NotificationEventDao,
    private val learning: LearningRepository? = null,
    private val metrics: MetricsRecorder? = null
) {
    private suspend fun interaction(n: Int = 1) { metrics?.count(Metric.USER_INTERACTION, delta = n.toLong()) }

    companion object {
        /** Enough for ~a week of real traffic; threads/grouping need more than the 50-row recent feed. */
        const val INBOX_LIMIT = 500
        private const val KEY_CLASSIFIER_VERSION = "classifier_version"
    }

    fun observeRecent(limit: Int = 50): Flow<List<NotificationEventEntity>> = dao.observeRecent(limit)

    fun observeTrading(limit: Int = 50): Flow<List<NotificationEventEntity>> =
        dao.observeTrading(limit)

    fun observeCategory(category: String, limit: Int = 50): Flow<List<NotificationEventEntity>> =
        dao.observeByCategory(category, limit)

    suspend fun inCategory(category: String): List<NotificationEventEntity> = dao.findByCategory(category)

    fun observeImportant(limit: Int = 50): Flow<List<NotificationEventEntity>> =
        dao.observeByMinimumPriority(minimumPriority = 65, limit = limit)

    fun observeAttention(limit: Int = 100): Flow<List<NotificationEventEntity>> =
        dao.observeByMinimumPriority(minimumPriority = 40, limit = limit)

    fun observeTimeline(limit: Int = 100): Flow<List<NotificationEventEntity>> =
        dao.observeTimeline(limit)

    fun observeHistory(): Flow<List<NotificationEventEntity>> = dao.observeHistory()

    fun observeActive(): Flow<List<NotificationEventEntity>> = dao.observeActive()

    /** One-off repair for rows captured before HTML stripping existed; leaves read state untouched. */
    suspend fun stripStoredMarkup() {
        dao.findWithPossibleMarkup().forEach { event ->
            val title = NotificationTextExtractor.stripMarkup(event.title).trim()
            val body = NotificationTextExtractor.stripMarkup(event.body).trim()
            if (title != event.title || body != event.body) dao.updateText(event.id, title, body)
        }
    }

    /**
     * Re-runs the classifier over stored non-trading rows when its rules change, so events captured
     * before a fix (e.g. broker calls that landed in OTHER) move to the right section. Rows already
     * TRADING are never touched, so delivery history is preserved.
     */
    suspend fun reclassifyIfClassifierChanged(prefs: android.content.SharedPreferences) {
        if (prefs.getInt(KEY_CLASSIFIER_VERSION, 0) >= com.marksy.os.notification.NotificationClassifier.VERSION) return
        var changed = 0
        dao.findNonTrading().forEach { event ->
            val result = com.marksy.os.notification.NotificationClassifier.classify(event.sourcePackage, event.title, event.body)
            if (result.category.name != event.category) {
                val trading = result.category == com.marksy.os.notification.NotificationClassifier.Category.TRADING
                changed += dao.updateClassification(event.id, result.category.name, result.priority, result.confidence, trading)
            }
        }
        com.marksy.os.ai.DiagLog.i("MarksyClassifier", "reclassified $changed stored event(s) for v${com.marksy.os.notification.NotificationClassifier.VERSION}")
        prefs.edit().putInt(KEY_CLASSIFIER_VERSION, com.marksy.os.notification.NotificationClassifier.VERSION).apply()
    }

    suspend fun archive(eventId: Long): Boolean {
        learning?.recordAction(listOf(eventId), PersonalLearning.Signal.ARCHIVED_UNOPENED)
        interaction()
        return dao.setArchived(eventId, true) > 0
    }

    suspend fun unarchive(eventId: Long): Boolean = dao.setArchived(eventId, false) > 0

    /** NEW -> ACTIVE once the user has opened the event; no-op for any other state. */
    suspend fun markSeen(eventId: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        learning?.recordAction(listOf(eventId), PersonalLearning.Signal.OPENED)
        interaction()
        return dao.transitionLifecycle(eventId, EventLifecycle.State.NEW.name, EventLifecycle.State.ACTIVE.name, null, nowMillis) > 0
    }

    fun observeInbox(limit: Int = INBOX_LIMIT): Flow<List<NotificationEventEntity>> = dao.observeInbox(limit)

    suspend fun markThreadSeen(ids: List<Long>, nowMillis: Long = System.currentTimeMillis()): Int {
        learning?.recordAction(ids, PersonalLearning.Signal.OPENED)
        interaction()
        return dao.markSeen(ids, nowMillis)
    }

    suspend fun resolveThread(ids: List<Long>, nowMillis: Long = System.currentTimeMillis()): Int {
        learning?.recordAction(ids, PersonalLearning.Signal.RESOLVED)
        interaction()
        return dao.resolve(ids, "Resolved by you", nowMillis)
    }

    suspend fun reopenThread(ids: List<Long>, nowMillis: Long = System.currentTimeMillis()): Int = dao.reopen(ids, nowMillis)

    suspend fun snoozeThread(ids: List<Long>, untilMillis: Long): Int {
        learning?.recordAction(ids, PersonalLearning.Signal.SNOOZED)
        interaction()
        return dao.setSnoozedUntil(ids, untilMillis)
    }

    suspend fun unsnoozeThread(ids: List<Long>): Int = dao.setSnoozedUntil(ids, null)

    suspend fun archiveThread(ids: List<Long>, nowMillis: Long = System.currentTimeMillis()): Int {
        learning?.recordAction(ids, PersonalLearning.Signal.ARCHIVED_UNOPENED)
        interaction()
        return dao.archiveAll(ids, nowMillis)
    }

    suspend fun event(eventId: Long): NotificationEventEntity? = dao.getById(eventId)

    suspend fun clearAll() = dao.deleteAll()

    suspend fun delete(eventId: Long): Boolean = dao.deleteById(eventId) > 0

    /** Undo for delete: re-inserts the exact row, id included. */
    suspend fun restore(event: NotificationEventEntity) { dao.insert(event) }

    suspend fun setRead(eventId: Long, read: Boolean): Int {
        if (read) learning?.recordAction(listOf(eventId), PersonalLearning.Signal.OPENED)
        interaction()
        return dao.setRead(eventId, read)
    }

    suspend fun setKept(eventId: Long, kept: Boolean): Int {
        interaction()
        return dao.setKept(eventId, kept)
    }

    suspend fun setReminder(eventId: Long, remindAt: Long?): Int {
        interaction()
        return dao.setReminder(eventId, remindAt)
    }
}
