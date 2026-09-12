package com.marksy.os.notification

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.marksy.os.data.local.MarksyDatabase
import com.marksy.os.data.local.NotificationEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Optional personal-device WhatsApp connector.
 *
 * This service is intentionally allow-list first: if no configured sender matches the
 * accessibility event, the event is discarded and nothing is persisted. It does not
 * open WhatsApp, inspect its private database, or transmit anything by itself.
 */
class MarksyWhatsAppAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { MarksyDatabase.getInstance(applicationContext).notificationEventDao() }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString()?.let(SourceRegistry::isWhatsApp) != true) return

        val watchlist = WhatsAppSenderWatchlist.get(applicationContext)
        if (watchlist.isEmpty()) return

        val values = event.text.map { it?.toString()?.trim().orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
        if (values.isEmpty()) return

        val sender = values.firstOrNull { WhatsAppSenderWatchlist.matches(watchlist, it) }
            ?: values.firstOrNull { candidate ->
                watchlist.any { watched ->
                    val normalizedCandidate = candidate.trim().lowercase()
                    val normalizedWatched = watched.trim().lowercase()
                    normalizedCandidate.startsWith("$normalizedWatched:") ||
                        normalizedCandidate.startsWith("$normalizedWatched -") ||
                        normalizedCandidate.startsWith("$normalizedWatched —")
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
        val sourceKey = "wa-accessibility:" + sha256("${event.packageName}|$sender|$occurredAt|$message")
        val result = NotificationClassifier.classify("com.whatsapp", sender, message)

        scope.launch {
            runCatching {
                dao.insert(
                    NotificationEventEntity(
                        sourcePackage = "com.whatsapp",
                        sourceName = "WhatsApp",
                        sourceKey = sourceKey,
                        title = sender,
                        body = message,
                        postedAt = occurredAt,
                        category = result.category.name,
                        priority = result.priority,
                        confidence = result.confidence,
                        isTrading = result.category == NotificationClassifier.Category.TRADING,
                        deliveryState = if (result.category == NotificationClassifier.Category.TRADING)
                            DeliveryState.PENDING.name
                        else DeliveryState.NOT_APPLICABLE.name
                    )
                )
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
