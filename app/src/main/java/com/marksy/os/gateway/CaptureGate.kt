package com.marksy.os.gateway

import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.market.StockMentions
import com.marksy.os.notification.CaptureMedium
import com.marksy.os.notification.ChatLabels
import com.marksy.os.notification.NotificationClassifier
import com.marksy.os.notification.TipTextCleaner
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** The tip-ledger spec §5.1 body of `POST /tips/ingest-text`: no title, body or sender. */
data class CapturedMessage(
    val deviceEventKey: String,
    val medium: String,
    val appPackage: String,
    val channelLabel: String,
    val text: String,
    val devicePostedAt: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("deviceEventKey", deviceEventKey)
        .put("medium", medium)
        .put("appPackage", appPackage)
        .put("channelLabel", channelLabel)
        .put("text", text)
        .put("devicePostedAt", devicePostedAt)
}

/** Everything the gate reads from the phone, loaded once per delivery run. */
data class CaptureContext(
    /** Null until the capture list was fetched once. */
    val capturePackages: Set<String>?,
    val chatAllowList: Set<String>,
    val chatSenders: Set<String>,
    val username: String,
    val deviceSalt: String
) {
    // Not a constructor property, so each per-batch .copy() gets its own fresh, unbuilt cache -- no state shared across batches or threads.
    val senderMask: Regex? by lazy { ChatLabels.buildSenderMask(chatSenders) }
}

sealed interface CaptureDecision {
    data class Send(val message: CapturedMessage) : CaptureDecision
    /** The capture list is not cached yet; the row stays queued. */
    data object Wait : CaptureDecision
    /** The row stays on the phone for good; `reason` is a fixed code, never content. */
    data class Keep(val reason: String) : CaptureDecision
}

/** The only way a captured notification leaves the phone (tip-ledger spec §2.11, §5.1). */
object CaptureGate {
    const val NOT_A_CANDIDATE = "not-a-candidate"
    const val OWN_ORDER = "own-order"
    const val OWN_ACCOUNT = "own-account"
    const val OUTSIDE_CAPTURE_SET = "outside-capture-set"
    const val ONE_TO_ONE_CHAT = "one-to-one-chat"
    const val GROUP_UNKNOWN = "group-unknown"
    const val NO_LABEL = "no-label"
    const val MASKED_LABEL = "masked-label"
    const val EMPTY_TEXT = "empty-text"
    // Fix round 1 finding C1: an SMS title that isn't shaped like a sender id is never a consented channel.
    const val SMS_NOT_SENDER_ID = "sms-not-sender-id"

    fun decide(event: NotificationEventEntity, context: CaptureContext): CaptureDecision {
        require(context.deviceSalt.isNotBlank()) { "deviceSalt must not be blank (fix round 1, defence in depth)" }
        val appPackage = event.sourcePackage.trim().lowercase(Locale.ROOT)
        val medium = CaptureMedium.of(appPackage)
        if (event.sourceKey.isBlank() || event.category !in candidateCategories(medium)) return CaptureDecision.Keep(NOT_A_CANDIDATE)
        if (needsMarketSignal(medium, event.category) && !hasMarketSignal(event.body)) return CaptureDecision.Keep(NOT_A_CANDIDATE)
        // Brokers tell the customer about their own orders and holdings in apps and SMS, not in chat groups.
        if (!medium.isChat && NotificationClassifier.isOwnOrderEvent(event.title, event.body)) return CaptureDecision.Keep(OWN_ORDER)
        if (!medium.isChat && NotificationClassifier.isOwnAccountEvent(event.title, event.body)) return CaptureDecision.Keep(OWN_ACCOUNT)
        val label = when (medium) {
            CaptureMedium.APP_NOTIFICATION -> {
                val packages = context.capturePackages ?: return CaptureDecision.Wait
                if (appPackage !in packages) return CaptureDecision.Keep(OUTSIDE_CAPTURE_SET)
                event.sourceName
            }
            CaptureMedium.SMS -> {
                if (!ChatLabels.isSenderIdShaped(event.title)) return CaptureDecision.Keep(SMS_NOT_SENDER_ID)
                ChatLabels.allowListedSmsSender(event.title, context.chatAllowList) ?: return CaptureDecision.Keep(OUTSIDE_CAPTURE_SET)
            }
            CaptureMedium.WHATSAPP, CaptureMedium.TELEGRAM -> {
                if (event.chatGroup != true) return CaptureDecision.Keep(if (event.chatGroup == false) ONE_TO_ONE_CHAT else GROUP_UNKNOWN)
                ChatLabels.allowListedChat(event.title, context.chatAllowList, context.chatSenders)
                    ?: return CaptureDecision.Keep(OUTSIDE_CAPTURE_SET)
            }
        }
        val channelLabel = TipTextCleaner.channelLabel(medium, label, context.username) ?: return CaptureDecision.Keep(NO_LABEL)
        if (TipTextCleaner.isMaskOnly(channelLabel)) return CaptureDecision.Keep(MASKED_LABEL)
        val raw = when (medium) {
            CaptureMedium.APP_NOTIFICATION -> listOf(event.title, event.body).filter { it.isNotBlank() }.joinToString("\n")
            CaptureMedium.SMS -> event.body
            CaptureMedium.WHATSAPP, CaptureMedium.TELEGRAM ->
                ChatLabels.withoutSenders(event.body, event.title, context.chatSenders, context.senderMask)
        }
        val text = TipTextCleaner.clean(raw, context.username).trim()
        if (text.isEmpty()) return CaptureDecision.Keep(EMPTY_TEXT)
        return CaptureDecision.Send(
            CapturedMessage(
                deviceEventKey = deviceEventKey(context.deviceSalt, appPackage, event.sourceKey),
                medium = medium.name,
                appPackage = appPackage,
                channelLabel = channelLabel,
                text = text,
                devicePostedAt = Instant.ofEpochMilli(event.postedAt).toString()
            )
        )
    }

    /** Whether a new row may ever leave the phone, decided at capture; the capture set is checked at delivery. */
    fun queues(sourcePackage: String, category: String, chatGroup: Boolean?): Boolean {
        val medium = CaptureMedium.of(sourcePackage)
        return category in candidateCategories(medium) && (!medium.isChat || chatGroup == true)
    }

    /** A chat or SMS row kept local for want of a market signal is decided again once an update adds one. */
    fun requeuesOnUpdate(sourcePackage: String, category: String, chatGroup: Boolean?, before: String, after: String): Boolean =
        queues(sourcePackage, category, chatGroup) && needsMarketSignal(CaptureMedium.of(sourcePackage), category) &&
            !hasMarketSignal(before) && hasMarketSignal(after)

    // Apps: executions and broker/market updates, where calls now land; chats and SMS: anything but private categories.
    private val APP_CATEGORIES = setOf("TRADING", "MARKET")
    private val CHAT_CATEGORIES = setOf("TRADING", "MARKET", "MESSAGES", "OTHER", "PROMOTIONS")

    private fun candidateCategories(medium: CaptureMedium) = if (medium == CaptureMedium.APP_NOTIFICATION) APP_CATEGORIES else CHAT_CATEGORIES

    // 4b ruling: chat and SMS text leaves only as a market message; a chat or SMS row was TRADING only for a parsed call.
    private fun needsMarketSignal(medium: CaptureMedium, category: String) = medium != CaptureMedium.APP_NOTIFICATION && category != "TRADING"

    private val marketLevel = Regex("""\b(?:cmp|ltp|sl|tgt|targets?|stop[\s-]*loss|entry)\b""")
    private val marketSide = Regex("""\b(?:buy|sell|short(?![\s-]*term)|accumulate)\b""")

    // A price level plus a side or a ticker, read from the body alone: SMS titles are upper-case sender ids.
    private fun hasMarketSignal(body: String): Boolean {
        val text = body.lowercase(Locale.ROOT)
        return marketLevel.containsMatchIn(text) && (marketSide.containsMatchIn(text) || StockMentions.find(body, { true }, 1).isNotEmpty())
    }

    /** One key per notification row, so a retry is the same receipt; salted so it reveals no notification key. */
    fun deviceEventKey(salt: String, sourcePackage: String, sourceKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt|$sourcePackage|$sourceKey".toByteArray(Charsets.UTF_8))
        return "n1-" + digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * `GET /channels/capture-list` data: the app packages a phone may send (tip-ledger spec §5.1).
 * Fix round 1 finding I2: malformed data throws, so the caller keeps its cached or null list and rows wait,
 * instead of silently emptying the capture set (which would have sent nothing from apps).
 */
fun parseCaptureList(data: JSONObject): Set<String> {
    val packages = data.optJSONArray("packages")
        ?: throw IllegalArgumentException("capture-list 'packages' is missing or not an array")
    return (0 until packages.length()).map { i ->
        val entry = packages.optJSONObject(i) ?: throw IllegalArgumentException("capture-list entry $i is not an object")
        val value = entry.opt("package")
        if (value !is String) throw IllegalArgumentException("capture-list entry $i has no string 'package'")
        value.trim().lowercase(Locale.ROOT)
    }.filter { it.isNotBlank() }.toSet()
}
