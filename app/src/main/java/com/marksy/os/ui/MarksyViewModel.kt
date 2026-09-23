package com.marksy.os.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marksy.os.data.NotificationRepository
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

class MarksyViewModel(private val repository: NotificationRepository) : ViewModel() {
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

    fun unarchive(eventId: Long) {
        viewModelScope.launch { repository.unarchive(eventId) }
    }
}

class MarksyViewModelFactory(private val repository: NotificationRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MarksyViewModel::class.java)) {
            return MarksyViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
