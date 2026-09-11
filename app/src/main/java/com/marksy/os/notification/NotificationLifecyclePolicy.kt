package com.marksy.os.notification

import android.app.Notification

/** Pure rules for deciding whether a posted notification should enter Marksy OS. */
object NotificationLifecyclePolicy {
    /** Ongoing notifications are persistent system UI and are not consumed by V1. */
    fun shouldCapture(notificationFlags: Int, sourcePackage: String, ownPackage: String): Boolean {
        if ((notificationFlags and Notification.FLAG_ONGOING_EVENT) != 0) return false

        val normalizedSource = sourcePackage.trim()
        val normalizedOwnPackage = ownPackage.trim()
        if (normalizedSource.isBlank() || normalizedOwnPackage.isBlank()) return false
        if (normalizedSource == normalizedOwnPackage) return false

        return true
    }
}
