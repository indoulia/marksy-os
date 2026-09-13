package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity

/** Applies deterministic rule outcomes to a captured event without performing external actions. */
object RuleApplication {
    data class Result(
        val event: NotificationEventEntity,
        val evaluation: RuleEngine.Evaluation,
        val archived: Boolean
    )

    fun apply(rules: List<RuleEngine.Rule>, event: NotificationEventEntity): Result {
        val evaluation = RuleEngine.evaluate(rules, event)
        val archived = evaluation.matchedRules.any { it.action == RuleEngine.Action.ARCHIVE }
        return Result(
            event = event.copy(
                priority = evaluation.priority,
                archived = archived
            ),
            evaluation = evaluation,
            archived = archived
        )
    }
}
