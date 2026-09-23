package com.marksy.os.data

import android.content.Context
import com.marksy.os.data.local.MarksyDatabase

object MarksyContainer {
    fun database(context: Context): MarksyDatabase =
        MarksyDatabase.getInstance(context)

    fun learning(context: Context): LearningRepository {
        val db = database(context)
        val settings = LearningSettings(context.applicationContext)
        return LearningRepository(db.learningDao(), db.notificationEventDao(), isEnabled = { settings.enabled })
    }

    fun repository(context: Context): NotificationRepository =
        NotificationRepository(database(context).notificationEventDao(), learning(context))
}
