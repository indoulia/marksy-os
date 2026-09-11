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

    fun eventsForCategory(category: String): Flow<List<NotificationEventEntity>> =
        repository.observeCategory(category)

    fun countByCategory(events: List<NotificationEventEntity>, category: String): Int =
        events.count { it.category == category }
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
