package com.marksy.os.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.TradingDeliveryScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
        // Never consume ongoing notifications such as music playback, active
        // calls, navigation, VPN, or foreground services. Marksy OS only owns
        // ordinary user notifications that can safely be moved into its inbox.
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty().trim()
        val text = buildNotificationText(extras)
        if (title.isBlank() && text.isBlank()) return

        val packageName = sbn.packageName
        if (packageName == applicationContext.packageName) return

        val result = NotificationClassifier.classify(packageName, title, text)
        val isTrading = result.category == NotificationClassifier.Category.TRADING

        serviceScope.launch {
            // Room returns -1 when the unique source/package/timestamp key was
            // already stored. The duplicate can still be safely consumed.
            dao.insert(
                NotificationEventEntity(
                    sourcePackage = packageName,
                    sourceName = SourceRegistry.displayName(packageName),
                    sourceKey = sbn.key,
                    title = title,
                    body = text,
                    postedAt = sbn.postTime,
                    category = result.category.name,
                    priority = result.priority,
                    confidence = result.confidence,
                    isTrading = isTrading,
                    deliveryState = if (isTrading) DeliveryState.PENDING.name else DeliveryState.NOT_APPLICABLE.name
                )
            )

            if (isTrading) {
                TradingDeliveryScheduler.requestImmediateDelivery(applicationContext)
            }

            // Never send raw notifications to the network from this service.
            // Cancellation happens only after the local insert operation returns.
            try {
                cancelNotification(sbn.key)
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to cancel notification", e)
            }
        }
    }

    private fun buildNotificationText(extras: android.os.Bundle): String {
        val text = extras.getCharSequence("android.text")?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty().trim()
        val lines = extras.getCharSequenceArray("android.textLines")
            ?.map { it.toString().trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return listOf(text, bigText, lines.joinToString("\n"))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .take(MAX_BODY_LENGTH)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MarksyNotificationListener"
        private const val MAX_BODY_LENGTH = 4000
    }
}
