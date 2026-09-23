package com.marksy.os.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NotificationEventEntity::class],
    version = 3,
    exportSchema = false
)
abstract class MarksyDatabase : RoomDatabase() {
    abstract fun notificationEventDao(): NotificationEventDao

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
                database.execSQL("UPDATE notification_events SET lifecycleState = 'ARCHIVED' WHERE archived = 1")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_threadKey ON notification_events(threadKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_correlationKey ON notification_events(correlationKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_lifecycleState ON notification_events(lifecycleState)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_intelligenceVersion ON notification_events(intelligenceVersion)")
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
