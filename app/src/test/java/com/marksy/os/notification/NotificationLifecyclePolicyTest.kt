package com.marksy.os.notification

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLifecyclePolicyTest {
    private val ownPackage = "com.marksy.os"

    @Test
    fun normalExternalNotificationIsCaptured() {
        assertTrue(NotificationLifecyclePolicy.shouldCapture(0, "com.example.app", ownPackage))
    }

    @Test
    fun ongoingNotificationIsNotCaptured() {
        assertFalse(
            NotificationLifecyclePolicy.shouldCapture(
                Notification.FLAG_ONGOING_EVENT,
                "com.example.app",
                ownPackage
            )
        )
    }

    @Test
    fun marksyOwnNotificationIsNotCaptured() {
        assertFalse(NotificationLifecyclePolicy.shouldCapture(0, ownPackage, ownPackage))
    }

    @Test
    fun blankSourcePackageIsNotCaptured() {
        assertFalse(NotificationLifecyclePolicy.shouldCapture(0, "   ", ownPackage))
    }

    @Test
    fun ongoingOwnNotificationIsNotCaptured() {
        assertFalse(
            NotificationLifecyclePolicy.shouldCapture(
                Notification.FLAG_ONGOING_EVENT,
                ownPackage,
                ownPackage
            )
        )
    }
}
