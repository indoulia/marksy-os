package com.marksy.os.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.marksy.os.connector.ConnectorRegistry
import com.marksy.os.connector.IngestionPipeline
import com.marksy.os.connector.RawCapture
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.RetentionScheduler
import com.marksy.os.gateway.TradingDeliveryScheduler
import com.marksy.os.intelligence.EventIntelligenceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Connector for all Android notifications (EPIC-021); processing lives in [IngestionPipeline]. */
class MarksyNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ingestion by lazy {
        MarksyContainer.ingestion(applicationContext) { TradingDeliveryScheduler.requestImmediateDelivery(applicationContext) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        RetentionScheduler.schedule(applicationContext)
        TradingDeliveryScheduler.schedule(applicationContext)
        EventIntelligenceWorker.schedule(applicationContext)
        serviceScope.launch {
            runCatching { ingestion.connected(ConnectorRegistry.NOTIFICATIONS) }
            runCatching { MarksyContainer.actions(applicationContext).recover() }
        }
        Log.i(TAG, "Notification listener connected; background work scheduled")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected")
        serviceScope.launch { runCatching { ingestion.disconnected(ConnectorRegistry.NOTIFICATIONS) } }
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

        val raw = RawCapture(
            connectorId = ConnectorRegistry.NOTIFICATIONS,
            sourcePackage = packageName,
            sourceName = SourceRegistry.displayName(applicationContext, packageName),
            sourceKey = sbn.key,
            title = title,
            body = text,
            postedAt = sbn.postTime
        )

        serviceScope.launch {
            val result = ingestion.ingest(raw)
            if (result is IngestionPipeline.Result.Failed) {
                // Not stored, so leave it in the shade rather than lose it.
                Log.e(TAG, "Failed to persist notification event (${result.reason})")
                return@launch
            }
            // Preserves V1 behaviour: the captured notification is removed from the shade.
            try {
                cancelNotification(sbn.key)
            } catch (_: SecurityException) {
                Log.w(TAG, "Unable to cancel notification")
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
