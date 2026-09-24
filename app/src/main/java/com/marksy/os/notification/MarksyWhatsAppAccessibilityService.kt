package com.marksy.os.notification

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.marksy.os.connector.ConnectorRegistry
import com.marksy.os.connector.RawCapture
import com.marksy.os.data.MarksyContainer
import com.marksy.os.gateway.TradingDeliveryScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Optional personal-device WhatsApp connector.
 *
 * This service is allow-list first: if no configured sender matches the
 * accessibility event, nothing is persisted. It does not open WhatsApp or
 * inspect its private database.
 */
class MarksyWhatsAppAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ingestion by lazy {
        MarksyContainer.ingestion(applicationContext) { if (isActive) TradingDeliveryScheduler.requestImmediateDelivery(applicationContext) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString()?.let(SourceRegistry::isWhatsApp) != true) return
        if (!isActive) return

        val watchlist = WhatsAppSenderWatchlist.get(applicationContext)
        if (watchlist.isEmpty()) return

        val values = event.text.map { it?.toString()?.trim().orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
        if (values.isEmpty()) return

        val sender = values.firstOrNull { WhatsAppSenderWatchlist.matches(watchlist, it) }
            ?: values.firstOrNull { candidate ->
                watchlist.any { watched ->
                    val candidateLower = candidate.trim().lowercase()
                    val watchedLower = watched.trim().lowercase()
                    candidateLower.startsWith("$watchedLower:") ||
                        candidateLower.startsWith("$watchedLower -") ||
                        candidateLower.startsWith("$watchedLower —")
                }
            }
            ?: return

        val senderNormalized = sender.trim().lowercase()
        val message = values
            .flatMap { it.split("\n") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.lowercase() == senderNormalized }
            .filterNot { it.lowercase().startsWith("$senderNormalized:") }
            .filterNot { it.lowercase().startsWith("$senderNormalized -") }
            .filterNot { it.lowercase().startsWith("$senderNormalized —") }
            .distinct()
            .joinToString("\n")
            .take(NotificationTextExtractor.MAX_BODY_LENGTH)

        if (message.isBlank()) return

        val occurredAt = System.currentTimeMillis()
        val sourcePackage = event.packageName.toString().trim().lowercase()
        val category = NotificationClassifier.classify(sourcePackage, sender, message)
        // Accessibility emits several callbacks for one visible state; the 5-minute fingerprint
        // bucket gives them one stable source key, which the ingestion pipeline dedups on.
        val fingerprint = EventFingerprint.create(sourcePackage, category.category.name, sender, message, occurredAt)
        val raw = RawCapture(
            connectorId = ConnectorRegistry.WHATSAPP_ACCESSIBILITY,
            sourcePackage = sourcePackage,
            sourceName = SourceRegistry.displayName(applicationContext, sourcePackage),
            sourceKey = "wa-accessibility:$fingerprint",
            title = sender,
            body = message,
            postedAt = occurredAt
        )
        scope.launch { runCatching { ingestion.ingest(raw) } }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.launch { runCatching { ingestion.connected(ConnectorRegistry.WHATSAPP_ACCESSIBILITY) } }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        scope.launch { runCatching { ingestion.disconnected(ConnectorRegistry.WHATSAPP_ACCESSIBILITY) } }
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        isActive = false
        scope.cancel()
        super.onDestroy()
    }

    private var isActive: Boolean = true
}
