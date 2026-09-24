package com.marksy.os.data

import android.content.Context
import com.marksy.os.ai.AiModelRegistry
import com.marksy.os.ai.IntelligenceService
import com.marksy.os.ai.ModelQueryInterpreter
import com.marksy.os.ai.RoomAiInvocationSink
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

    fun rules(context: Context): RuleRunner {
        val db = database(context)
        return RuleRunner(db.notificationEventDao(), db.ruleExecutionDao())
    }

    fun briefing(context: Context): BriefingRepository {
        val db = database(context)
        return BriefingRepository(db.notificationEventDao(), db.eventActionDao(), learning(context))
    }

    /** No external provider exists, so external processing is hard-off rather than a setting. */
    fun intelligence(context: Context): IntelligenceService =
        IntelligenceService(AiModelRegistry.installed(), allowExternal = { false }, sink = RoomAiInvocationSink(database(context).aiInvocationDao()))

    fun ask(context: Context): AskMarksyRepository {
        val db = database(context)
        return AskMarksyRepository(db.notificationEventDao(), db.contextGraphDao(), listOf(ModelQueryInterpreter(intelligence(context))))
    }

    fun repository(context: Context): NotificationRepository =
        NotificationRepository(database(context).notificationEventDao(), learning(context))
}
