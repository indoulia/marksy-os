package com.marksy.os.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Android notification -> local Marksy OS event pipeline. */
class MarksyNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { MarksyDatabase.getInstance(applicationContext).notificationEventDao() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty().trim()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return

        val packageName = sbn.packageName
        val result = NotificationClassifier.classify(packageName, title, text)

        serviceScope.launch {
            val id = dao.insert(
                NotificationEventEntity(
                    sourcePackage = packageName,
                    sourceName = sourceName(packageName),
                    sourceKey = sbn.key,
                    title = title,
                    body = text,
                    postedAt = sbn.postTime,
                    category = result.category.name,
                    priority = result.priority,
                    confidence = result.confidence,
                    isTrading = result.category == NotificationClassifier.Category.TRADING
                )
            )

            // Consume only after successful local persistence. Never send raw
            // notifications to the network from this service.
            if (id != -1L) {
                try {
                    cancelNotification(sbn.key)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Unable to cancel notification", e)
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun sourceName(packageName: String): String = when (packageName) {
        "com.whatsapp" -> "WhatsApp"
        "com.upstox.pro" -> "Upstox"
        "com.icicidirect" -> "ICICI Direct"
        "com.etmoney" -> "ET Money"
        else -> packageName.substringAfterLast('.').ifBlank { packageName }
    }

    companion object {
        private const val TAG = "MarksyNotification"
    }
}
