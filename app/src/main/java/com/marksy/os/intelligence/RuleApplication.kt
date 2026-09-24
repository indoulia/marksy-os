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
        val archived = evaluation.stateAction == RuleEngine.Action.ARCHIVE
        val resolved = evaluation.stateAction == RuleEngine.Action.MARK_RESOLVED
        val stateRule = RuleEngine.ordered(evaluation.matchedRules).firstOrNull { it.action == evaluation.stateAction }
        return Result(
            event = event.copy(
                priority = evaluation.priority,
                archived = archived,
                lifecycleState = when {
                    archived -> EventLifecycle.State.ARCHIVED.name
                    resolved -> EventLifecycle.State.RESOLVED.name
                    else -> event.lifecycleState
                },
                lifecycleReason = stateRule?.let { "Rule: ${it.name}" } ?: event.lifecycleReason,
            ),
            evaluation = evaluation,
            archived = archived,
        )
    }
}
