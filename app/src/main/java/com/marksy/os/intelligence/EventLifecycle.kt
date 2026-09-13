package com.marksy.os.intelligence

import com.marksy.os.data.local.DeliveryState

object EventLifecycle {
    enum class Outcome { KEEP_ACTIVE, ARCHIVE, RETRY, COMPLETE, FAIL }

    data class Decision(val outcome: Outcome, val nextDeliveryState: DeliveryState)

    fun afterRuleEvaluation(isTrading: Boolean, matchedArchiveRule: Boolean): Decision = when {
        matchedArchiveRule -> Decision(Outcome.ARCHIVE, if (isTrading) DeliveryState.PENDING else DeliveryState.NOT_APPLICABLE)
        isTrading -> Decision(Outcome.KEEP_ACTIVE, DeliveryState.PENDING)
        else -> Decision(Outcome.KEEP_ACTIVE, DeliveryState.NOT_APPLICABLE)
    }

    fun afterDeliverySuccess() = Decision(Outcome.COMPLETE, DeliveryState.DELIVERED)
    fun afterTransientDeliveryFailure() = Decision(Outcome.RETRY, DeliveryState.PENDING)
    fun afterTerminalDeliveryFailure() = Decision(Outcome.FAIL, DeliveryState.FAILED)
    fun afterCancellation() = Decision(Outcome.RETRY, DeliveryState.PENDING)
}
