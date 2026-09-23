package com.marksy.os.notification

import android.content.ComponentName
import android.content.Context
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
        // Anything posted while the listener was unbound (app update, OS kill) is still in the shade.
        val backlog = runCatching { activeNotifications.orEmpty().toList() }.getOrDefault(emptyList())
        backlog.forEach(::capture)
        Log.i(TAG, "Notification listener connected; backfilled ${backlog.size} active notifications")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected; requesting rebind")
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
                if (insertedId == -1L) mergeRepost(event)

                if (insertedId != -1L && isTrading && !applied.archived) {
                    TradingDeliveryScheduler.requestImmediateDelivery(applicationContext)
                }

                if (DISMISS_AFTER_CAPTURE) {
                    try {
                        cancelNotification(sbn.key)
                    } catch (_: SecurityException) {
                        Log.w(TAG, "Unable to cancel notification")
                    }
                }
            } catch (_: Exception) {
                Log.e(TAG, "Failed to persist notification event")
            }
        }
    }

    /** Apps re-post the same key with new messages; fold the new content into the stored event. */
    private suspend fun mergeRepost(event: NotificationEventEntity) {
        val existing = dao.findBySourceKey(event.sourcePackage, event.sourceKey) ?: return
        val body = NotificationTextExtractor.merge(existing.body, event.body)
        val title = event.title.ifBlank { existing.title }
        if (body == existing.body && title == existing.title) return
        dao.updateContent(existing.id, title, body, maxOf(existing.postedAt, event.postedAt))
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
