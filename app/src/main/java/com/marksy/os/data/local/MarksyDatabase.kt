package com.marksy.os.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotificationEventEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MarksyDatabase : RoomDatabase() {
    abstract fun notificationEventDao(): NotificationEventDao

    companion object {
        @Volatile private var INSTANCE: MarksyDatabase? = null

        fun getInstance(context: Context): MarksyDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MarksyDatabase::class.java,
                    "marksy_os.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
