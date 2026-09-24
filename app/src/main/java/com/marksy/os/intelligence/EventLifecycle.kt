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

    /**
     * User-facing event lifecycle (EPIC-010), independent of Marksy trading delivery state.
     * NEW = captured, not yet seen; ACTIVE = seen/in play; RESOLVED = the real-world item is done;
     * ARCHIVED = removed from active surfaces (mirrors the legacy `archived` flag).
     */
    enum class State { NEW, ACTIVE, RESOLVED, ARCHIVED }

    private val allowed: Map<State, Set<State>> = mapOf(
        State.NEW to setOf(State.ACTIVE, State.RESOLVED, State.ARCHIVED),
        State.ACTIVE to setOf(State.RESOLVED, State.ARCHIVED),
        State.RESOLVED to setOf(State.ACTIVE, State.ARCHIVED),
        State.ARCHIVED to setOf(State.ACTIVE)
    )

    fun canTransition(from: State, to: State): Boolean = allowed[from].orEmpty().contains(to)

    /** Unknown stored values (e.g. from a newer build) degrade to ACTIVE rather than crashing. */
    fun parse(value: String?, archived: Boolean = false): State =
        if (archived) State.ARCHIVED
        else State.entries.firstOrNull { it.name == value } ?: State.ACTIVE
}
