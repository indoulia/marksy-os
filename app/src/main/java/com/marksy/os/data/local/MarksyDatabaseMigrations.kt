package com.marksy.os.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notification_events ADD COLUMN deliveryState TEXT NOT NULL DEFAULT 'NOT_APPLICABLE'")
        db.execSQL("ALTER TABLE notification_events ADD COLUMN deliveryAttempts INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notification_events ADD COLUMN lastDeliveryAttemptAt INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_deliveryState ON notification_events(deliveryState)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notification_events ADD COLUMN insightSummary TEXT")
        db.execSQL("ALTER TABLE notification_events ADD COLUMN insightAction TEXT")
        db.execSQL("ALTER TABLE notification_events ADD COLUMN insightConfidence REAL")
        db.execSQL("ALTER TABLE notification_events ADD COLUMN insightReceivedAt INTEGER")
    }
}
