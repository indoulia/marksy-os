package com.marksy.os.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.marksy.os.data.RetentionScheduler
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
        // Android can restore the listener without launching the UI. Re-register
        // durable background work here so capture remains useful after a reboot
        // or process restart as well as after a normal app launch.
        RetentionScheduler.schedule(applicationContext)
        TradingDeliveryScheduler.schedule(applicationContext)
        Log.i(TAG, "Notification listener connected; background work scheduled")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (!NotificationLifecyclePolicy.shouldCapture(
                notificationFlags = sbn.notification.flags,
                sourcePackage = packageName,
                ownPackage = applicationContext.packageName,
                sourceKey = sbn.key
            )
        ) return

        val extras = sbn.notification.extras
        val title = NotificationTextExtractor.extractTitle(extras)
        val text = NotificationTextExtractor.extract(extras)
        if (title.isBlank() && text.isBlank()) return

        val result = NotificationClassifier.classify(packageName, title, text)
        val isTrading = result.category == NotificationClassifier.Category.TRADING
        val sourceName = SourceRegistry.displayName(applicationContext, packageName)

        serviceScope.launch {
            try {
                val insertedId = dao.insert(
                    NotificationEventEntity(
                        sourcePackage = packageName,
                        sourceName = sourceName,
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

                // IGNORE conflicts make repeated Android callbacks harmless. Only
                // newly persisted trading events need a new delivery request.
                if (insertedId != -1L && isTrading) {
                    TradingDeliveryScheduler.requestImmediateDelivery(applicationContext)
                }

                // A successful local write means this notification identity is safely
                // represented in Marksy OS. A conflict means Android replayed an
                // already-captured notification, which can happen after a listener
                // restart. Cancel in both cases so consumed notifications cannot be
                // stranded in the system shade. If persistence fails, leave the
                // notification untouched so the event is not lost before capture.
                try {
                    cancelNotification(sbn.key)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Unable to cancel notification", e)
                }
            } catch (e: Exception) {
                // Never log notification content, title, body, or source key.
                // Capture failures should be diagnosable without exposing user data.
                Log.e(TAG, "Failed to persist notification event", e)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MarksyNotificationListener"
    }
}
