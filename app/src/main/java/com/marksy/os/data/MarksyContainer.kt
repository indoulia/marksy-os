package com.marksy.os.data

import android.content.Context
import com.marksy.os.data.local.MarksyDatabase

object MarksyContainer {
    fun database(context: Context): MarksyDatabase =
        MarksyDatabase.getInstance(context)

    fun repository(context: Context): NotificationRepository =
        NotificationRepository(database(context).notificationEventDao())
}
