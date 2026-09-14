package com.marksy.os.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationListenerStatusTest {
    private val target = "com.marksy.os/com.marksy.os.notification.MarksyNotificationListenerService"

    @Test
    fun matchingListenerIsDetected() {
        assertTrue(NotificationListenerStatus.containsListener(target, target))
    }

    @Test
    fun matchingListenerAmongOtherServicesIsDetected() {
        val enabled = "com.example.one/Service:$target:com.example.two/Service"
        assertTrue(NotificationListenerStatus.containsListener(enabled, target))
    }

    @Test
    fun matchingListenerWithWhitespaceIsDetected() {
        val enabled = " com.example.one/Service : $target : com.example.two/Service "
        assertTrue(NotificationListenerStatus.containsListener(enabled, target))
    }

    @Test
    fun similarButDifferentServiceIsNotDetected() {
        val differentService = "com.marksy.os/com.marksy.os.notification.OtherNotificationListenerService"
        assertFalse(NotificationListenerStatus.containsListener(differentService, target))
    }

    @Test
    fun missingListenerIsNotDetected() {
        assertFalse(
            NotificationListenerStatus.containsListener(
                "com.example.one/Service:com.example.two/Service",
                target
            )
        )
    }

    @Test
    fun emptyOrMalformedEntriesAreIgnored() {
        assertFalse(NotificationListenerStatus.containsListener("::not-a-component", target))
    }

    @Test
    fun malformedTargetIsNotDetected() {
        assertFalse(NotificationListenerStatus.containsListener(target, "not-a-component"))
    }

    @Test
    fun blankTargetIsNotDetected() {
        assertFalse(NotificationListenerStatus.containsListener(target, "   "))
    }
}
