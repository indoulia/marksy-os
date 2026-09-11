package com.marksy.os.notification

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Small, side-effect-free boundary for checking notification listener access. */
object NotificationListenerStatus {
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS
        ).orEmpty()
        val component = ComponentName(
            context,
            MarksyNotificationListenerService::class.java
        )
        return containsListener(enabled, component.flattenToString())
    }

    internal fun containsListener(enabledListeners: String, target: String): Boolean =
        enabledListeners.split(':').any { entry ->
            runCatching { ComponentName.unflattenFromString(entry) == ComponentName.unflattenFromString(target) }
                .getOrDefault(false)
        }
}
