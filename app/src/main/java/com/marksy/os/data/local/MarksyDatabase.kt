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
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notification_events ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_archived ON notification_events(archived)")
            }
        }

        // Existing rows start read so the upgrade doesn't turn the whole inbox bold.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notification_events ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN kept INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN remindAt INTEGER")
                database.execSQL("UPDATE notification_events SET isRead = 1")
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
