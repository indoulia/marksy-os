package com.marksy.os.capture

import com.marksy.os.data.local.CaptureDao
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.TipCandidateEntity
import com.marksy.os.gateway.CaptureContext
import com.marksy.os.gateway.CaptureDecision
import com.marksy.os.gateway.CaptureGate
import com.marksy.os.gateway.CapturedMessage
import com.marksy.os.gateway.MarksyGatewayClient
import com.marksy.os.gateway.MarksyInsight
import com.marksy.os.gateway.TradingDeliveryPolicy
import com.marksy.os.notification.CaptureMedium
import com.marksy.os.notification.NotificationClassifier
import com.marksy.os.notification.TipTextCleaner
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

/** The only way a reviewed capture leaves the phone: the user's own Send, reviewed fields only, an allow-listed app source. */
object CandidateDeliveryPolicy {
    const val NOT_ACCEPTED = "not-accepted"
    const val KEPT_ON_PHONE = "kept-on-phone"
    const val CHAT_SOURCE = "chat-source"
    const val UNSUPPORTED_SOURCE = CaptureFailure.UNSUPPORTED_SOURCE

    private val ticker = Regex("""(?=[A-Z0-9&._-]*[A-Z])[A-Z0-9][A-Z0-9&._-]{0,29}""")

    fun decide(candidate: TipCandidateEntity, context: CaptureContext): CaptureDecision {
        require(context.deviceSalt.isNotBlank()) { "deviceSalt must not be blank" }
        reviewKeep(candidate)?.let { return CaptureDecision.Keep(it) }
        val packages = context.capturePackages
        val source = CaptureSourceRegistry({ packages }, { it }).resolve(candidate.sourcePackage)
            // Until the capture list is known, a package outside the phone's broker hints may still turn out listed.
            ?: return if (packages == null && !candidate.sourcePackage.isNullOrBlank()) CaptureDecision.Wait else CaptureDecision.Keep(UNSUPPORTED_SOURCE)
        if (source.medium != CaptureMedium.APP_NOTIFICATION) return CaptureDecision.Keep(CHAT_SOURCE)
        if (packages == null) return CaptureDecision.Wait
        if (!source.deliverable) return CaptureDecision.Keep(CaptureGate.OUTSIDE_CAPTURE_SET)
        val channelLabel = TipTextCleaner.channelLabel(CaptureMedium.APP_NOTIFICATION, candidate.sourceName ?: source.packageName, context.username)
            ?: return CaptureDecision.Keep(CaptureGate.NO_LABEL)
        if (TipTextCleaner.isMaskOnly(channelLabel)) return CaptureDecision.Keep(CaptureGate.MASKED_LABEL)
        // Never the recognized text: only the line rebuilt from the reviewed fields.
        val text = TipTextCleaner.clean(canonicalLine(candidate.fields)!!, context.username).trim()
        if (text.isEmpty()) return CaptureDecision.Keep(CaptureGate.EMPTY_TEXT)
        return CaptureDecision.Send(
            CapturedMessage(
                deviceEventKey = CaptureGate.deviceEventKey(context.deviceSalt, source.packageName, "capture:${candidate.id}"),
                medium = CaptureMedium.APP_NOTIFICATION.name,
                appPackage = source.packageName,
                channelLabel = channelLabel,
                text = text,
                devicePostedAt = Instant.ofEpochMilli(candidate.capturedAt).toString()
            )
        )
    }

    /** Whether a just-reviewed candidate goes PENDING; delivery decides again and waits while the capture list is unknown. */
    fun mayQueue(candidate: TipCandidateEntity, registry: CaptureSourceRegistry): Boolean {
        if (reviewKeep(candidate) != null) return false
        val source = registry.resolve(candidate.sourcePackage) ?: return false
        return source.medium == CaptureMedium.APP_NOTIFICATION && (source.deliverable || !registry.captureListKnown())
    }

    /** E.g. `BUY ABC @ 500 Target 650 SL 470 Intraday`; null unless the reviewed fields are a forwardable call. */
    fun canonicalLine(fields: TipFields): String? {
        if (!fields.forwardable) return null
        val symbol = fields.symbol!!.trim().uppercase(Locale.ROOT).takeIf { ticker.matches(it) } ?: return null
        val levels = listOf("@" to fields.entry, "Target" to fields.target, "SL" to fields.stopLoss).filter { it.second != null }
        if (levels.any { (_, v) -> !v!!.isFinite() || v <= 0 }) return null
        return (listOf("${fields.side!!.name} $symbol") + levels.map { (label, v) -> "$label ${BigDecimal.valueOf(v!!).stripTrailingZeros().toPlainString()}" } +
            listOfNotNull(TipExtractor.horizonOf(fields.horizon))).joinToString(" ")
    }

    private fun reviewKeep(candidate: TipCandidateEntity): String? {
        val text = candidate.extractedText.orEmpty()
        return when {
            candidate.state != CaptureState.ACCEPTED.name -> NOT_ACCEPTED
            !candidate.userChoseSend -> KEPT_ON_PHONE
            canonicalLine(candidate.fields) == null -> CaptureGate.NOT_A_CANDIDATE
            NotificationClassifier.isOwnOrderEvent("", text) -> CaptureGate.OWN_ORDER
            NotificationClassifier.isOwnAccountEvent("", text) -> CaptureGate.OWN_ACCOUNT
            else -> null
        }
    }
}

/** One delivery pass over PENDING candidates, mirroring TradingDeliveryRun; true when a transient failure wants a retry. */
internal class CandidateDeliveryRun(
    private val dao: CaptureDao,
    private val client: MarksyGatewayClient,
    private val capture: CaptureContext,
    private val isStopped: () -> Boolean = { false },
    private val decide: (TipCandidateEntity, CaptureContext) -> CaptureDecision = CandidateDeliveryPolicy::decide,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    suspend fun drain(): Boolean {
        while (!isStopped()) {
            val pending = dao.findPendingDelivery(TradingDeliveryPolicy.BATCH_SIZE)
            if (pending.isEmpty()) return false
            if (deliverBatch(pending)) return true
        }
        return false
    }

    private suspend fun deliverBatch(pending: List<TipCandidateEntity>): Boolean {
        var retryRequested = false
        for (candidate in pending) {
            if (isStopped()) return false
            val attempts = candidate.deliveryAttempts + 1
            if (dao.claimPendingDelivery(candidate.id, attempts, clock()) != 1) continue
            try {
                val decision = try {
                    decide(candidate, capture)
                } catch (cancellation: CancellationException) {
                    dao.updateInFlightDelivery(candidate.id, DeliveryState.PENDING.name, attempts, clock())
                    throw cancellation
                } catch (gateError: Throwable) {
                    dao.updateInFlightDelivery(candidate.id, DeliveryState.NOT_APPLICABLE.name, attempts, clock())
                    log("Capture candidate ${candidate.id} stays on the phone (gate-error: ${gateError::class.java.simpleName})")
                    continue
                }
                val message = when (decision) {
                    is CaptureDecision.Send -> decision.message
                    CaptureDecision.Wait -> {
                        dao.updateInFlightDelivery(candidate.id, DeliveryState.PENDING.name, attempts, clock())
                        retryRequested = true
                        continue
                    }
                    is CaptureDecision.Keep -> {
                        dao.updateInFlightDelivery(candidate.id, DeliveryState.NOT_APPLICABLE.name, attempts, clock())
                        dao.setDeliveryNote(candidate.id, decision.reason)
                        log("Capture candidate ${candidate.id} stays on the phone (${decision.reason})")
                        continue
                    }
                }
                val result: Result<MarksyInsight> = try {
                    client.capture(candidate.id, message)
                } catch (cancellation: CancellationException) {
                    dao.updateInFlightDelivery(candidate.id, DeliveryState.PENDING.name, attempts, clock())
                    throw cancellation
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                result.fold(
                    onSuccess = { dao.updateInFlightDelivery(candidate.id, DeliveryState.DELIVERED.name, attempts, clock()) },
                    onFailure = { error ->
                        val state = TradingDeliveryPolicy.retryState(error)
                        if (dao.updateInFlightDelivery(candidate.id, state.name, attempts, clock()) != 1) return@fold
                        if (state == DeliveryState.PENDING) {
                            retryRequested = true
                        } else {
                            dao.setDeliveryNote(candidate.id, TradingDeliveryPolicy.failureCode(error))
                            log("Capture candidate ${candidate.id} permanently rejected")
                        }
                    }
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                log("Capture delivery storage operation failed for candidate ${candidate.id}; retrying")
                retryRequested = true
            }
        }
        return retryRequested
    }
}
