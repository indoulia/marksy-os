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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM notification_events WHERE sourceKey = ''")
        db.execSQL(
            """
            DELETE FROM notification_events
            WHERE sourceKey != ''
              AND id NOT IN (
                  SELECT MAX(id)
                  FROM notification_events
                  WHERE sourceKey != ''
                  GROUP BY sourcePackage, sourceKey
              )
            """.trimIndent()
        )
        db.execSQL("DROP INDEX IF EXISTS index_notification_events_sourcePackage_sourceKey_postedAt")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notification_events_sourcePackage_sourceKey ON notification_events(sourcePackage, sourceKey)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notification_events ADD COLUMN marksyTipId TEXT")
        db.execSQL("ALTER TABLE notification_events ADD COLUMN marksyResponseJson TEXT")
    }
}
