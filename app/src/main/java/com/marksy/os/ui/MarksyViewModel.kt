package com.marksy.os.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marksy.os.data.ActionRepository
import com.marksy.os.data.LearningRepository
import com.marksy.os.intelligence.ActionEngine
import com.marksy.os.intelligence.ContextGraph
import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.LearningSettings
import com.marksy.os.data.NotificationRepository
import com.marksy.os.intelligence.PersonalLearning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.DashboardSnapshot
import com.marksy.os.intelligence.EventIntelligence
import com.marksy.os.intelligence.HomeCategoryStats
import com.marksy.os.intelligence.HomePeriod
import com.marksy.os.intelligence.NotificationTrend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MarksyViewModel(
    private val repository: NotificationRepository,
    private val learning: LearningRepository? = null,
    private val learningSettings: LearningSettings? = null,
    private val actions: ActionRepository? = null,
    private val graph: ContextGraph? = null
) : ViewModel() {
    suspend fun relatedEntities(eventId: Long): List<ContextEntity> =
        graph?.entitiesFor(eventId).orEmpty().filter { it.type != ContextGraph.NodeType.APP.name }

    fun unlinkEntity(entityId: Long, eventId: Long) { viewModelScope.launch { graph?.unlink(entityId, eventId) } }

    private val actionMessageState = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = actionMessageState

    fun availableActions(event: NotificationEventEntity): List<ActionEngine.Available> =
        actions?.discover(event).orEmpty()

    fun runAction(eventId: Long, type: ActionEngine.Type, scheduledFor: Long?, detail: String?) {
        viewModelScope.launch {
            actionMessageState.value = actions?.execute(eventId, type, scheduledFor, detail)?.message
        }
    }

    fun clearActionMessage() { actionMessageState.value = null }

    private val learningEnabledState = MutableStateFlow(learningSettings?.enabled ?: false)
    val learningEnabled: StateFlow<Boolean> = learningEnabledState

    /** Learned adjustments only apply while learning is on; explicit corrections always apply. */
    val learningProfile: Flow<PersonalLearning.Profile> =
        (learning?.observeProfile() ?: flowOf(PersonalLearning.Profile.EMPTY))
            .combine(learningEnabledState) { profile, enabled -> if (enabled) profile else profile.correctionsOnly() }

    fun setLearningEnabled(enabled: Boolean) {
        learningSettings?.enabled = enabled
        learningEnabledState.value = enabled
    }

    fun setPreference(subject: PersonalLearning.Subject, preference: PersonalLearning.Preference?) {
        viewModelScope.launch { learning?.setPreference(subject, preference) }
    }

    fun resetLearning() { viewModelScope.launch { learning?.resetLearning() } }
    fun clearCorrections() { viewModelScope.launch { learning?.clearCorrections() } }

    val recentEvents: Flow<List<NotificationEventEntity>> = repository.observeRecent()
    val importantEvents: Flow<List<NotificationEventEntity>> = repository.observeImportant()
    val attentionEvents: Flow<List<NotificationEventEntity>> = repository.observeAttention()
    val tradingEvents: Flow<List<NotificationEventEntity>> = repository.observeTrading()
    val timelineEvents: Flow<List<NotificationEventEntity>> = repository.observeTimeline()
    val historyEvents: Flow<List<NotificationEventEntity>> = repository.observeHistory()
    val activeEvents: Flow<List<NotificationEventEntity>> = repository.observeActive()

    val notificationTrend: Flow<NotificationTrend> = activeEvents
        .map { NotificationTrend.from(it, System.currentTimeMillis()) }
        .flowOn(Dispatchers.Default)

    val homeCategoryStats: Flow<Map<HomePeriod, HomeCategoryStats>> = activeEvents
        .map { events ->
            val now = System.currentTimeMillis()
            HomePeriod.entries.associateWith { HomeCategoryStats.from(events, now, it) }
        }
        .flowOn(Dispatchers.Default)

    val inboxEvents: Flow<List<NotificationEventEntity>> = repository.observeInbox()

    val intelligentEvents: Flow<List<EventIntelligence.Result>> = recentEvents.map { events ->
        events.map { EventIntelligence.analyze(it) }
    }

    val dashboardSnapshot: Flow<DashboardSnapshot> = recentEvents.map { events ->
        DashboardSnapshot.from(events)
    }

    val tradingInsights: Flow<List<TradingInsight>> = tradingEvents.map { events ->
        events.mapNotNull(NotificationEventEntity::toTradingInsight)
    }

    fun eventsForCategory(category: String): Flow<List<NotificationEventEntity>> =
        repository.observeCategory(category)

    fun countByCategory(events: List<NotificationEventEntity>, category: String): Int =
        events.count { it.category == category }

    fun archive(eventId: Long) {
        viewModelScope.launch { repository.archive(eventId) }
    }

    fun delete(eventId: Long) {
        viewModelScope.launch { repository.delete(eventId) }
    }

    fun restore(event: NotificationEventEntity) {
        viewModelScope.launch { repository.restore(event) }
    }

    fun setRead(eventId: Long, read: Boolean) {
        viewModelScope.launch { repository.setRead(eventId, read) }
    }

    fun setKept(eventId: Long, kept: Boolean) {
        viewModelScope.launch { repository.setKept(eventId, kept) }
    }

    fun setReminder(eventId: Long, remindAt: Long?) {
        viewModelScope.launch { repository.setReminder(eventId, remindAt) }
    }

    fun markSeen(eventId: Long) {
        viewModelScope.launch { repository.markSeen(eventId) }
    }

    fun markThreadSeen(ids: List<Long>) { viewModelScope.launch { repository.markThreadSeen(ids) } }
    fun resolveThread(ids: List<Long>) { viewModelScope.launch { repository.resolveThread(ids) } }
    fun reopenThread(ids: List<Long>) { viewModelScope.launch { repository.reopenThread(ids) } }
    fun snoozeThread(ids: List<Long>, untilMillis: Long) { viewModelScope.launch { repository.snoozeThread(ids, untilMillis) } }
    fun archiveThread(ids: List<Long>) { viewModelScope.launch { repository.archiveThread(ids) } }

    fun unarchive(eventId: Long) {
        viewModelScope.launch { repository.unarchive(eventId) }
    }
}

class MarksyViewModelFactory(
    private val repository: NotificationRepository,
    private val learning: LearningRepository? = null,
    private val learningSettings: LearningSettings? = null,
    private val actions: ActionRepository? = null,
    private val graph: ContextGraph? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MarksyViewModel::class.java)) {
            return MarksyViewModel(repository, learning, learningSettings, actions, graph) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
