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
        Index(value = ["deliveryState"]),
        Index(value = ["archived"]),
        Index(value = ["threadKey"]),
        Index(value = ["correlationKey"]),
        Index(value = ["lifecycleState"]),
        Index(value = ["intelligenceVersion"])
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
    /** True when the user has archived the event from active surfaces. */
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Opened by the user; unread rows render bold. New content for the same source key resets it. */
    val isRead: Boolean = false,
    /** Kept forever: retention pruning never deletes it. */
    val kept: Boolean = false,
    /** Pending reminder time; retention skips the row until the reminder fires. */
    val remindAt: Long? = null,
    // EPIC-010 derived intelligence. Written only by EventIntelligencePipeline; raw capture fields above stay authoritative.
    /** EventLifecycle.State name. Rows from before v3 migrate to ACTIVE/ARCHIVED. */
    val lifecycleState: String = "NEW",
    val lifecycleUpdatedAt: Long? = null,
    /** Why the lifecycle last changed automatically (e.g. resolved by a later event), for explainability. */
    val lifecycleReason: String? = null,
    val importanceScore: Int = 0,
    val intelligenceConfidence: Float = 0f,
    val threadKey: String? = null,
    val correlationKey: String? = null,
    /** Canonical event this one duplicates (cross-source); the row is kept, only collapsed on surfaces. */
    val duplicateOfId: Long? = null,
    /** Bounded JSON: extracted entities/amounts/references/times and explanation reasons. */
    val intelligenceJson: String? = null,
    /** 0 = not yet processed; bumping EventIntelligencePipeline.VERSION triggers a background re-derive. */
    val intelligenceVersion: Int = 0,
    /** EPIC-011: hidden from the inbox until this time; persisted so snoozes survive restarts. */
    val snoozedUntil: Long? = null
)

enum class DeliveryState {
    NOT_APPLICABLE,
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED
}
