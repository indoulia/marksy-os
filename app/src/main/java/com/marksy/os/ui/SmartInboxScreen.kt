package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.marksy.os.data.local.NotificationEventEntity

@Composable
fun SmartInboxScreen(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit) {
    InboxScreen(events, padding)
}
