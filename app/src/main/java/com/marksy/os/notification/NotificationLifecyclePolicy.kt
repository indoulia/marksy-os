package com.marksy.os.notification

import android.app.Notification

/** Pure rules for deciding whether a posted notification should enter Marksy OS. */
object NotificationLifecyclePolicy {
    /**
     * Ongoing notifications and group-summary notifications are container/system UI
     * rather than individual user events, so V1 does not consume them.
     */
    fun shouldCapture(notificationFlags: Int, sourcePackage: String, ownPackage: String): Boolean {
        if ((notificationFlags and Notification.FLAG_ONGOING_EVENT) != 0) return false
        if ((notificationFlags and Notification.FLAG_GROUP_SUMMARY) != 0) return false

        val normalizedSource = sourcePackage.trim().lowercase()
        val normalizedOwnPackage = ownPackage.trim().lowercase()
        if (normalizedSource.isBlank() || normalizedOwnPackage.isBlank()) return false
        if (normalizedSource == normalizedOwnPackage) return false

        return true
    }
}
