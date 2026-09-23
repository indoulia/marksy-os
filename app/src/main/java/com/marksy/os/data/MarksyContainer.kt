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

    fun actions(context: Context): ActionRepository {
        val db = database(context)
        val learning = learning(context)
        return ActionRepository(
            db.eventActionDao(), db.notificationEventDao(),
            NotificationRepository(db.notificationEventDao(), learning), learning,
            com.marksy.os.notification.AndroidActionPlatform(context.applicationContext)
        )
    }

    fun repository(context: Context): NotificationRepository =
        NotificationRepository(database(context).notificationEventDao(), learning(context))
}
