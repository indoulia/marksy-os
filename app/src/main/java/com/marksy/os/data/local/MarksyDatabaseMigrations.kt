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
        // A notification key identifies the Android notification instance. The
        // old index also included postedAt, so an update/re-post of the same
        // key could create another local row. Keep the newest row and remove
        // older duplicates before enforcing the stronger invariant.
        db.execSQL(
            """
            DELETE FROM notification_events
            WHERE id NOT IN (
                SELECT MAX(id)
                FROM notification_events
                WHERE sourceKey != ''
                GROUP BY sourcePackage, sourceKey
            )
            AND sourceKey != ''
            """.trimIndent()
        )
        db.execSQL("DROP INDEX IF EXISTS index_notification_events_sourcePackage_sourceKey_postedAt")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notification_events_sourcePackage_sourceKey ON notification_events(sourcePackage, sourceKey)")
    }
}
