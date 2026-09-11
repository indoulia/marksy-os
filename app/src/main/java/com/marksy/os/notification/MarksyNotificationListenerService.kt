package com.marksy.os.notification

import android.app.Notification
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
        // Never consume ongoing notifications such as music playback, active
        // calls, navigation, VPN, or foreground services.
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = sbn.notification.extras
        val title = NotificationTextExtractor.extractTitle(extras)
        val text = NotificationTextExtractor.extract(extras)
        if (title.isBlank() && text.isBlank()) return

        val packageName = sbn.packageName
        if (packageName == applicationContext.packageName) return

        val result = NotificationClassifier.classify(packageName, title, text)
        val isTrading = result.category == NotificationClassifier.Category.TRADING
        val sourceName = SourceRegistry.displayName(applicationContext, packageName)

        serviceScope.launch {
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

            // Raw notification content never leaves through this collector.
            // Cancellation happens only after the local insert operation returns.
            try {
                cancelNotification(sbn.key)
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to cancel notification", e)
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
