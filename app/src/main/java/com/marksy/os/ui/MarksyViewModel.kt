package com.marksy.os.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.marksy.os.data.NotificationRepository
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MarksyViewModel(private val repository: NotificationRepository) : ViewModel() {
    val recentEvents: Flow<List<NotificationEventEntity>> = repository.observeRecent()
    val tradingEvents: Flow<List<NotificationEventEntity>> = repository.observeTrading()
    val timelineEvents: Flow<List<NotificationEventEntity>> = repository.observeTimeline()
    val historyEvents: Flow<List<NotificationEventEntity>> = repository.observeHistory()

    val tradingInsights: Flow<List<TradingInsight>> = tradingEvents.map { events ->
        events.mapNotNull(NotificationEventEntity::toTradingInsight)
    }

    fun eventsForCategory(category: String): Flow<List<NotificationEventEntity>> =
        repository.observeCategory(category)

    fun countByCategory(events: List<NotificationEventEntity>, category: String): Int =
        events.count { it.category == category }
}

class MarksyViewModelFactory(private val repository: NotificationRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MarksyViewModel::class.java)) {
            return MarksyViewModelFactoryResult(repository, modelClass).create()
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}

private class MarksyViewModelFactoryResult<T : ViewModel>(
    private val repository: NotificationRepository,
    private val modelClass: Class<T>
) {
    @Suppress("UNCHECKED_CAST")
    fun create(): T = MarksyViewModel(repository) as T
}
