package com.marksy.os.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * V1 pre-release database baseline. Marksy OS has not shipped yet, so the schema
 * is intentionally kept clean rather than carrying compatibility migrations.
 */
@Database(
    entities = [NotificationEventEntity::class],
    version = 1,
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
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
