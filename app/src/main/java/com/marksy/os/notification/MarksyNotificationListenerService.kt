package com.marksy.os.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Entry point for Marksy OS notification capture.
 *
 * V1 deliberately keeps this service thin: it observes Android notifications and
 * delegates normalization/classification to the application domain layer as those
 * layers are introduced in EPIC-002 and EPIC-003.
 */
class MarksyNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()

        Log.d(TAG, "Captured notification from $packageName: $title")

        // EPIC-002/003 will normalize, classify, persist and route this event.
        // Do not forward notification contents to the network from the listener.
    }

    companion object {
        private const val TAG = "MarksyNotification"
    }
}
