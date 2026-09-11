package com.marksy.os.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_events",
    indices = [
        Index(value = ["sourcePackage", "sourceKey", "postedAt"], unique = true),
        Index(value = ["category"]),
        Index(value = ["postedAt"])
    ]
)
data class NotificationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val sourceName: String,
    val sourceKey: String,
    val title: String,
    val body: String,
    val postedAt: Long,
    val category: String,
    val priority: Int,
    val confidence: Float,
    val isTrading: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)
