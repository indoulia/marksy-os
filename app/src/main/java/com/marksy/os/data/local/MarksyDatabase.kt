package com.marksy.os.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NotificationEventEntity::class, LearningSignalEntity::class, LearningOverrideEntity::class, EventActionEntity::class, ContextEntity::class, ContextLink::class, ContextRelation::class, RuleExecutionEntity::class, AiInvocationEntity::class, MemoryEntryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class MarksyDatabase : RoomDatabase() {
    abstract fun notificationEventDao(): NotificationEventDao
    abstract fun learningDao(): LearningDao
    abstract fun eventActionDao(): EventActionDao
    abstract fun contextGraphDao(): ContextGraphDao
    abstract fun ruleExecutionDao(): RuleExecutionDao
    abstract fun aiInvocationDao(): AiInvocationDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notification_events ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_archived ON notification_events(archived)")
            }
        }

        // EPIC-010: derived intelligence columns. Additive only; existing rows keep their data and
        // are re-derived by EventIntelligenceWorker (intelligenceVersion = 0).
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notification_events ADD COLUMN lifecycleState TEXT NOT NULL DEFAULT 'ACTIVE'")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN lifecycleUpdatedAt INTEGER")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN lifecycleReason TEXT")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN importanceScore INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN intelligenceConfidence REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN threadKey TEXT")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN correlationKey TEXT")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN duplicateOfId INTEGER")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN intelligenceJson TEXT")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN intelligenceVersion INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN snoozedUntil INTEGER")
                database.execSQL("UPDATE notification_events SET lifecycleState = 'ARCHIVED' WHERE archived = 1")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_threadKey ON notification_events(threadKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_correlationKey ON notification_events(correlationKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_lifecycleState ON notification_events(lifecycleState)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_intelligenceVersion ON notification_events(intelligenceVersion)")
                // EPIC-012 learning history + explicit corrections.
                database.execSQL("CREATE TABLE IF NOT EXISTS `learning_signals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` INTEGER NOT NULL, `subjectType` TEXT NOT NULL, `subjectKey` TEXT NOT NULL, `label` TEXT NOT NULL, `signal` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_signals_eventId_subjectType_signal` ON `learning_signals` (`eventId`, `subjectType`, `signal`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_signals_subjectType_subjectKey` ON `learning_signals` (`subjectType`, `subjectKey`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_signals_createdAt` ON `learning_signals` (`createdAt`)")
                // EPIC-020 personal memory.
                database.execSQL("CREATE TABLE IF NOT EXISTS `memory_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `memoryKey` TEXT NOT NULL, `label` TEXT NOT NULL, `confidence` REAL NOT NULL, `firstObservedAt` INTEGER NOT NULL, `lastObservedAt` INTEGER NOT NULL, `observations` INTEGER NOT NULL, `expiresAt` INTEGER, `origin` TEXT NOT NULL, `state` TEXT NOT NULL, `detailJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_entries_kind_memoryKey` ON `memory_entries` (`kind`, `memoryKey`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_entries_state` ON `memory_entries` (`state`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_entries_expiresAt` ON `memory_entries` (`expiresAt`)")
                // EPIC-019 AI invocation metrics (no content).
                database.execSQL("CREATE TABLE IF NOT EXISTS `ai_invocations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `task` TEXT NOT NULL, `modelId` TEXT, `modelVersion` TEXT, `templateId` TEXT NOT NULL, `templateVersion` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `latencyMs` INTEGER NOT NULL, `confidence` REAL, `at` INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_invocations_at` ON `ai_invocations` (`at`)")
                // EPIC-018 rule audit trail.
                database.execSQL("CREATE TABLE IF NOT EXISTS `rule_executions` (`ruleId` TEXT NOT NULL, `ruleVersion` INTEGER NOT NULL, `eventId` INTEGER NOT NULL, `action` TEXT NOT NULL, `trigger` TEXT NOT NULL, `applied` INTEGER NOT NULL, `note` TEXT, `executedAt` INTEGER NOT NULL, PRIMARY KEY(`ruleId`, `ruleVersion`, `eventId`))")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_rule_executions_eventId` ON `rule_executions` (`eventId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_rule_executions_executedAt` ON `rule_executions` (`executedAt`)")
                // EPIC-015 context graph.
                database.execSQL("CREATE TABLE IF NOT EXISTS `context_entities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `canonicalKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `confidence` REAL NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, `mentionCount` INTEGER NOT NULL, `sourceCount` INTEGER NOT NULL, `mergedIntoId` INTEGER)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_context_entities_type_canonicalKey` ON `context_entities` (`type`, `canonicalKey`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_context_entities_mergedIntoId` ON `context_entities` (`mergedIntoId`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `context_links` (`entityId` INTEGER NOT NULL, `eventId` INTEGER NOT NULL, `sourcePackage` TEXT NOT NULL, `confidence` REAL NOT NULL, `signal` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`entityId`, `eventId`))")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_context_links_eventId` ON `context_links` (`eventId`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `context_relations` (`fromId` INTEGER NOT NULL, `toId` INTEGER NOT NULL, `weight` INTEGER NOT NULL, `confidence` REAL NOT NULL, `lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`fromId`, `toId`))")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_context_relations_toId` ON `context_relations` (`toId`)")
                // EPIC-014 action history / audit trail.
                database.execSQL("CREATE TABLE IF NOT EXISTS `event_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` INTEGER NOT NULL, `type` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `scheduledFor` INTEGER, `detail` TEXT, `error` TEXT, `attempts` INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_event_actions_eventId` ON `event_actions` (`eventId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_event_actions_state` ON `event_actions` (`state`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_event_actions_type` ON `event_actions` (`type`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `learning_overrides` (`subjectType` TEXT NOT NULL, `subjectKey` TEXT NOT NULL, `preference` TEXT NOT NULL, `label` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`subjectType`, `subjectKey`))")
            }
        }

        @Volatile private var INSTANCE: MarksyDatabase? = null

        fun getInstance(context: Context): MarksyDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MarksyDatabase::class.java,
                    "marksy_os.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
