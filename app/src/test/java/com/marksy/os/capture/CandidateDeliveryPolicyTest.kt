package com.marksy.os.capture

import com.marksy.os.data.local.TipCandidateEntity
import com.marksy.os.gateway.CaptureContext
import com.marksy.os.gateway.CaptureDecision
import com.marksy.os.gateway.CaptureGate
import com.marksy.os.gateway.CapturedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CandidateDeliveryPolicyTest {
    private val context = CaptureContext(
        capturePackages = setOf("com.research.app", "com.whatsapp"), chatAllowList = emptySet(), chatSenders = emptySet(),
        username = "user-1", deviceSalt = "salt"
    )
    private val registry = CaptureSourceRegistry({ context.capturePackages }, { it })
    private val ocr = "BUY ABC @ 500 Target 650 SL 470\nShared by Rahul 9876543210 rahul@example.com SECRETNOTE"

    private fun accepted(
        pkg: String? = "com.research.app", state: CaptureState = CaptureState.ACCEPTED, send: Boolean = true, side: String? = "BUY",
        text: String? = ocr
    ) = TipCandidateEntity(
        id = 5, evidenceId = 1, method = CaptureMethod.USER_SHARED_IMAGE.name, sourcePackage = pkg, sourceName = "Research", sourceVerified = true,
        capturedAt = 1_790_000_000_000L, evidenceRef = "sha256:ab", extractedText = text, confidence = .9, symbol = "ABC", side = side,
        entry = 500.0, target = 650.0, stopLoss = 470.0, horizon = "intraday", visibleTimestamp = "10:42 AM", ambiguities = "",
        state = state.name, userChoseSend = send, updatedAt = 0
    )

    private fun keep(candidate: TipCandidateEntity) = (CandidateDeliveryPolicy.decide(candidate, context) as CaptureDecision.Keep).reason

    @Test
    fun nonTradingKeptRejectedAndUnreviewedCandidatesNeverQueue() {
        val cases = mapOf(
            accepted(state = CaptureState.REVIEW_REQUIRED) to CandidateDeliveryPolicy.NOT_ACCEPTED,
            accepted(state = CaptureState.EXTRACTED) to CandidateDeliveryPolicy.NOT_ACCEPTED,
            accepted(state = CaptureState.REJECTED, text = null) to CandidateDeliveryPolicy.NOT_ACCEPTED,
            accepted(send = false) to CandidateDeliveryPolicy.KEPT_ON_PHONE,
            accepted(side = null) to CaptureGate.NOT_A_CANDIDATE,
            accepted(text = "Your order for ABC is executed at 500") to CaptureGate.OWN_ORDER,
            accepted(text = "Your holdings: ABC 500, target 650, SL 470") to CaptureGate.OWN_ACCOUNT
        )
        cases.forEach { (candidate, reason) ->
            assertEquals(reason, keep(candidate))
            assertFalse(reason, CandidateDeliveryPolicy.mayQueue(candidate, registry))
        }
        assertTrue(CandidateDeliveryPolicy.mayQueue(accepted(), registry))
    }

    // Chats and SMS can never prove a group identity, even when the server lists the package.
    @Test
    fun chatSourceNeverSends() {
        assertEquals(CandidateDeliveryPolicy.CHAT_SOURCE, keep(accepted(pkg = "com.whatsapp")))
        assertEquals(CandidateDeliveryPolicy.CHAT_SOURCE, keep(accepted(pkg = "com.google.android.apps.messaging")))
        assertFalse(CandidateDeliveryPolicy.mayQueue(accepted(pkg = "com.whatsapp"), registry))
    }

    @Test
    fun payloadIsBuiltOnlyFromReviewedFields() {
        val send = CandidateDeliveryPolicy.decide(accepted(), context) as CaptureDecision.Send
        assertEquals(
            CapturedMessage(
                deviceEventKey = CaptureGate.deviceEventKey("salt", "com.research.app", "capture:5"), medium = "APP_NOTIFICATION",
                appPackage = "com.research.app", channelLabel = "Research", text = "BUY ABC @ 500 Target 650 SL 470 Intraday",
                devicePostedAt = Instant.ofEpochMilli(1_790_000_000_000L).toString()
            ),
            send.message
        )
        listOf("Rahul", "9876543210", "example.com", "SECRETNOTE", "10:42").forEach { assertFalse(it, send.message.toString().contains(it)) }
        // A free-text symbol typed in review is never forwarded.
        assertEquals(CaptureGate.NOT_A_CANDIDATE, keep(accepted().copy(symbol = "call me on 98765")))
        assertEquals(CaptureDecision.Wait, CandidateDeliveryPolicy.decide(accepted(), context.copy(capturePackages = null)))
    }

    @Test
    fun unsupportedSourceKeeps() {
        assertEquals(CandidateDeliveryPolicy.UNSUPPORTED_SOURCE, keep(accepted(pkg = null)))
        assertEquals(CandidateDeliveryPolicy.UNSUPPORTED_SOURCE, keep(accepted(pkg = "com.example.game")))
        // A broker known on the phone but not in the server capture list stays local.
        assertEquals(CaptureGate.OUTSIDE_CAPTURE_SET, keep(accepted(pkg = "com.zerodha.kite3")))
        assertFalse(CandidateDeliveryPolicy.mayQueue(accepted(pkg = null), registry))
    }
}
