package com.marksy.os.capture

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.CaptureGate
import com.marksy.os.notification.NotificationClassifier

enum class TeaserReason { TEASER, REDACTED }

sealed interface NotificationCapturePlan {
    /** Informational: complete tips stay on the existing ingestion + CaptureGate path. */
    data class CompleteTip(val candidate: TipCandidate) : NotificationCapturePlan
    data class NeedsSourceView(val reason: TeaserReason) : NotificationCapturePlan
    data class NotApplicable(val code: String) : NotificationCapturePlan
}

object TeaserDetector {
    private val teaser = Regex(
        """\b(?:new\s+(?:recommendation|tip|call|trade|research|stock\s+pick|idea)s?|tap\s+to\s+(?:view|see|open|read)|click\s+to\s+(?:view|see|read)|""" +
            """(?:trade|trading|stock|investment)\s+ideas?|research\s+(?:call|report|recommendation)s?|(?:view|check|see)\s+now|open\s+(?:the\s+)?app\s+to\s+(?:view|see))\b""",
        RegexOption.IGNORE_CASE
    )
    private val redaction = Regex("""(?:sensitive\s+notification\s+content(?:\s+hidden)?|contents?\s+hidden|new\s+notification)\W*""", RegexOption.IGNORE_CASE)

    fun isTeaser(title: String, body: String): Boolean = teaser.containsMatchIn("$title\n$body")

    fun isRedacted(body: String): Boolean = body.isBlank() || redaction.matches(body.trim())
}

/** Decides whether a stored notification is a complete tip, a teaser/redacted one the user may open or capture, or neither. */
class NotificationCapturePlanner(private val registry: CaptureSourceRegistry) {
    fun plan(event: NotificationEventEntity): NotificationCapturePlan {
        val source = registry.resolve(event.sourcePackage) ?: return NotificationCapturePlan.NotApplicable(UNSUPPORTED_SOURCE)
        // Same precedence as CaptureGate: apps tell the customer about their own orders and holdings, groups don't.
        if (!source.medium.isChat && NotificationClassifier.isOwnOrderEvent(event.title, event.body)) return NotificationCapturePlan.NotApplicable(CaptureGate.OWN_ORDER)
        if (!source.medium.isChat && NotificationClassifier.isOwnAccountEvent(event.title, event.body)) return NotificationCapturePlan.NotApplicable(CaptureGate.OWN_ACCOUNT)
        val text = listOf(event.title, event.body).filter { it.isNotBlank() }.joinToString("\n")
        val provenance = CaptureProvenance(
            CaptureMethod.NOTIFICATION_LISTENER, source.packageName, event.sourceName.ifBlank { source.displayName }, sourceVerified = true,
            capturedAt = event.postedAt, evidenceRef = "event:${event.id}", notificationEventId = event.id
        )
        when (val extracted = TipExtractor.extract(text, provenance, null)) {
            ExtractionResult.OneTimeCode -> return NotificationCapturePlan.NotApplicable(ONE_TIME_CODE)
            is ExtractionResult.Candidate -> if (extracted.candidate.fields.forwardable) return NotificationCapturePlan.CompleteTip(extracted.candidate)
            ExtractionResult.NotATip -> Unit
        }
        if (!source.offersScreenCapture) return NotificationCapturePlan.NotApplicable(NO_SCREEN_CAPTURE)
        if (event.category == NotificationClassifier.Category.PROMOTIONS.name) return NotificationCapturePlan.NotApplicable(NOT_A_TEASER)
        if (TeaserDetector.isTeaser(event.title, event.body)) return NotificationCapturePlan.NeedsSourceView(TeaserReason.TEASER)
        if (TeaserDetector.isRedacted(event.body)) return NotificationCapturePlan.NeedsSourceView(TeaserReason.REDACTED)
        return NotificationCapturePlan.NotApplicable(NOT_A_TEASER)
    }

    companion object {
        const val UNSUPPORTED_SOURCE = "unsupported-source"
        const val NO_SCREEN_CAPTURE = "no-screen-capture"
        const val NOT_A_TEASER = "not-a-teaser"
        const val ONE_TIME_CODE = "one-time-code"
    }
}
