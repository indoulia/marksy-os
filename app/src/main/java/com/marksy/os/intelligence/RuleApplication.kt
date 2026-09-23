package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity

/** Applies deterministic local rules before an event enters the active store. */
object RuleApplication {
    data class Result(
        val event: NotificationEventEntity,
        val evaluation: RuleEngine.Evaluation,
        val archived: Boolean,
    )

    fun apply(rules: List<RuleEngine.Rule>, event: NotificationEventEntity): Result {
        val evaluation = RuleEngine.evaluate(rules, event)
        val archived = evaluation.matchedRules.any { it.action == RuleEngine.Action.ARCHIVE }
        return Result(
            event = event.copy(
                priority = evaluation.priority,
                archived = archived,
                lifecycleState = if (archived) EventLifecycle.State.ARCHIVED.name else event.lifecycleState,
            ),
            evaluation = evaluation,
            archived = archived,
        )
    }
}
