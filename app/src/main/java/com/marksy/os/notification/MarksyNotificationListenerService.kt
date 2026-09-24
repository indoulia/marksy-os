package com.marksy.os.notification

import android.content.ComponentName
import android.content.Context
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
        // Anything posted while the listener was unbound (app update, OS kill) is still in the shade.
        val backlog = runCatching { activeNotifications.orEmpty().toList() }.getOrDefault(emptyList())
        backlog.forEach(::capture)
        Log.i(TAG, "Notification listener connected; backfilled ${backlog.size} active notifications")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected; requesting rebind")
        serviceScope.launch { runCatching { ingestion.disconnected(ConnectorRegistry.NOTIFICATIONS) } }
        super.onListenerDisconnected()
        requestRebind(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn)

    private fun capture(sbn: StatusBarNotification) {
        // A malformed notification from any app must never take the listener down.
        try {
            captureUnsafe(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Skipped notification from ${sbn.packageName}", e)
        }
    }

    private fun captureUnsafe(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (!NotificationLifecyclePolicy.shouldCapture(
                notificationFlags = sbn.notification.flags,
                sourcePackage = packageName,
                ownPackage = applicationContext.packageName,
                sourceKey = sbn.key
            )
        ) return

        val extras = sbn.notification.extras ?: return
        val title = NotificationTextExtractor.extractTitle(extras)
        val text = NotificationTextExtractor.extract(extras)
        if (title.isBlank() && text.isBlank()) return
        OriginalAppLauncher.remember(sbn)

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
                // Not stored, so never dismiss it.
                Log.e(TAG, "Failed to persist notification event (${result.reason})")
                return@launch
            }
            if (DISMISS_AFTER_CAPTURE) {
                try {
                    cancelNotification(sbn.key)
                } catch (_: SecurityException) {
                    Log.w(TAG, "Unable to cancel notification")
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MarksyNotificationListener"
        // Off for now: captured notifications stay in the system shade (user request 2026-09-24).
        private const val DISMISS_AFTER_CAPTURE = false

        /** Safe to call any time; the system ignores it when access is off or already bound. */
        fun requestRebind(context: Context) {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(context, MarksyNotificationListenerService::class.java))
            }.onFailure { Log.w(TAG, "Rebind request failed", it) }
        }
    }
}
