package com.marksy.os.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.marksy.os.data.RetentionScheduler
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.TradingDeliveryScheduler
import com.marksy.os.intelligence.RuleApplication
import com.marksy.os.intelligence.RuleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MarksyNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { MarksyDatabase.getInstance(applicationContext).notificationEventDao() }
    private val ruleStore by lazy { RuleStore(applicationContext) }

    override fun onListenerConnected() {
        super.onListenerConnected()
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
        val fingerprint = EventFingerprint.create(
            packageName,
            result.category.name,
            title,
            text,
            sbn.postTime
        )
        val baseEvent = NotificationEventEntity(
            sourcePackage = packageName,
            sourceName = sourceName,
            sourceKey = sbn.key,
            eventFingerprint = fingerprint,
            title = title,
            body = text,
            postedAt = sbn.postTime,
            category = result.category.name,
            priority = result.priority,
            confidence = result.confidence,
            isTrading = isTrading,
            deliveryState = if (isTrading) DeliveryState.PENDING.name else DeliveryState.NOT_APPLICABLE.name
        )
        val applied = RuleApplication.apply(ruleStore.load(), baseEvent)
        val event = applied.event

        serviceScope.launch {
            try {
                val insertedId = dao.insert(event)

                if (insertedId != -1L && isTrading && !applied.archived) {
                    TradingDeliveryScheduler.requestImmediateDelivery(applicationContext)
                }

                try {
                    cancelNotification(sbn.key)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Unable to cancel notification", e)
                }
            } catch (e: Exception) {
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
