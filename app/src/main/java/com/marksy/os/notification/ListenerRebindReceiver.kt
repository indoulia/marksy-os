package com.marksy.os.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** An app update unbinds the notification listener and Android doesn't rebind it on its own. */
class ListenerRebindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && NotificationListenerStatus.isEnabled(context)) {
            MarksyNotificationListenerService.requestRebind(context)
        }
    }
}
