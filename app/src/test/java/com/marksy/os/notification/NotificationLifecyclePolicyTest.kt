package com.marksy.os.notification

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLifecyclePolicyTest {
    private val ownPackage = "com.marksy.os"
    private val sourceKey = "0|com.example.app|42|com.example.app|tag|1"

    @Test
    fun normalExternalNotificationIsCaptured() {
        assertTrue(NotificationLifecyclePolicy.shouldCapture(0, "com.example.app", ownPackage, sourceKey))
    }

    @Test
    fun ongoingNotificationIsNotCaptured() {
        assertFalse(
            NotificationLifecyclePolicy.shouldCapture(
                Notification.FLAG_ONGOING_EVENT,
                "com.example.app",
                ownPackage,
                sourceKey
            )
        )
    }

    @Test
    fun groupSummaryNotificationIsNotCaptured() {
        assertFalse(
            NotificationLifecyclePolicy.shouldCapture(
                Notification.FLAG_GROUP_SUMMARY,
                "com.example.app",
                ownPackage,
                sourceKey
            )
        )
    }

    @Test
    fun ongoingGroupSummaryNotificationIsNotCaptured() {
        assertFalse(
            NotificationLifecyclePolicy.shouldCapture(
                Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY,
                "com.example.app",
                ownPackage,
                sourceKey
            )
        )
    }

    @Test
    fun marksyOwnNotificationIsNotCaptured() {
        assertFalse(NotificationLifecyclePolicy.shouldCapture(0, ownPackage, ownPackage, sourceKey))
    }

    @Test
    fun packageIdentityIsCaseInsensitive() {
        assertFalse(
            NotificationLifecyclePolicy.shouldCapture(
                0,
                "  COM.MARKSY.OS  ",
                " com.marksy.os ",
                sourceKey
            )
        )
    }

    @Test
    fun whitespaceAroundOwnPackageIsStillRecognized() {
        assertFalse(
            NotificationLifecyclePolicy.shouldCapture(
                0,
                "  $ownPackage  ",
                " $ownPackage ",
                sourceKey
            )
        )
    }

    @Test
    fun blankSourcePackageIsNotCaptured() {
        assertFalse(NotificationLifecyclePolicy.shouldCapture(0, "   ", ownPackage, sourceKey))
    }

    @Test
    fun blankOwnPackageIsNotCaptured() {
        assertFalse(NotificationLifecyclePolicy.shouldCapture(0, "com.example.app", "   ", sourceKey))
    }

    @Test
    fun blankSourceKeyIsNotCaptured() {
        assertFalse(NotificationLifecyclePolicy.shouldCapture(0, "com.example.app", ownPackage, "   "))
    }

    @Test
    fun whitespaceAroundSourceKeyIsAllowed() {
        assertTrue(NotificationLifecyclePolicy.shouldCapture(0, "com.example.app", ownPackage, "  $sourceKey  "))
    }

    @Test
    fun ongoingOwnNotificationIsNotCaptured() {
        assertFalse(
            NotificationLifecyclePolicy.shouldCapture(
                Notification.FLAG_ONGOING_EVENT,
                ownPackage,
                ownPackage,
                sourceKey
            )
        )
    }
}
