package com.marksy.os.data

import android.content.Context
import com.marksy.os.data.local.MarksyDatabase

object MarksyContainer {
    fun repository(context: Context): NotificationRepository =
        NotificationRepository(MarksyDatabase.getInstance(context).notificationEventDao())
}
