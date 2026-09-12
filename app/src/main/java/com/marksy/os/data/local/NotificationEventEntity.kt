package com.marksy.os.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_events",
    indices = [
        Index(value = ["sourcePackage", "sourceKey"], unique = true),
        Index(value = ["sourcePackage", "eventFingerprint"], unique = true),
        Index(value = ["category"]),
        Index(value = ["postedAt"]),
        Index(value = ["deliveryState"])
    ]
)
data class NotificationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val sourceName: String,
    val sourceKey: String,
    val eventFingerprint: String,
    val title: String,
    val body: String,
    val postedAt: Long,
    val category: String,
    val priority: Int,
    val confidence: Float,
    val isTrading: Boolean,
    val deliveryState: String = DeliveryState.NOT_APPLICABLE.name,
    val deliveryAttempts: Int = 0,
    val lastDeliveryAttemptAt: Long? = null,
    val insightSummary: String? = null,
    val insightAction: String? = null,
    val insightConfidence: Float? = null,
    val insightReceivedAt: Long? = null,
    val marksyTipId: String? = null,
    /** Bounded JSON from Marksy's GET /tips/{tipId} data object for forward compatibility. */
    val marksyResponseJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class DeliveryState {
    NOT_APPLICABLE,
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED
}
