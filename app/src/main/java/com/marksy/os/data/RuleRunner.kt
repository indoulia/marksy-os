package com.marksy.os.data

import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.RuleExecutionDao
import com.marksy.os.data.local.RuleExecutionEntity
import com.marksy.os.intelligence.RuleEngine
import java.time.ZoneId

/** EPIC-018 persistence side of rules: capture audit, historical simulation, idempotent apply-to-history. */
class RuleRunner(
    private val eventDao: NotificationEventDao,
    private val executions: RuleExecutionDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    suspend fun recordCapture(eventId: Long, evaluation: RuleEngine.Evaluation) {
        if (evaluation.matchedRules.isEmpty()) return
        val now = clock()
        val winner = evaluation.matchedRules.let { RuleEngine.ordered(it) }
            .firstOrNull { it.action == evaluation.stateAction && evaluation.stateAction != null }
        executions.insert(evaluation.matchedRules.map { r ->
            val exclusive = r.action == RuleEngine.Action.ARCHIVE || r.action == RuleEngine.Action.MARK_RESOLVED
            val overridden = exclusive && r !== winner
            RuleExecutionEntity(
                r.id, r.version, eventId, r.action.name, TRIGGER_CAPTURE, applied = !overridden,
                note = if (overridden) "Overridden by rule ${winner?.id}" else null, executedAt = now
            )
        })
    }

    /** Returns (events considered, hits) over recent history without writing anything. */
    suspend fun simulate(rule: RuleEngine.Rule, days: Int = 7): Pair<Int, List<RuleEngine.SimulationHit>> {
        val now = clock()
        val events = eventDao.findInRange(now - days * DAY, now + 1, LIMIT)
        return events.size to RuleEngine.simulate(rule, events, zone())
    }

    /** Applies one rule version to recent history; rerunning is a no-op for events it already handled. */
    suspend fun applyToHistory(rule: RuleEngine.Rule, days: Int = 7): Int {
        val now = clock()
        val done = executions.executedEventIds(rule.id, rule.version).toHashSet()
        val hits = simulate(rule, days).second.filter { it.eventId !in done }
        hits.forEach { hit ->
            when (rule.action) {
                RuleEngine.Action.ARCHIVE -> eventDao.archiveAll(listOf(hit.eventId), now)
                RuleEngine.Action.MARK_RESOLVED -> eventDao.resolve(listOf(hit.eventId), "Rule ${rule.name}", now)
                RuleEngine.Action.HIGHLIGHT, RuleEngine.Action.MARK_TRADING_PRIORITY -> eventDao.setPriority(hit.eventId, hit.priorityAfter)
            }
        }
        executions.insert(hits.map { RuleExecutionEntity(rule.id, rule.version, it.eventId, rule.action.name, TRIGGER_HISTORY, true, null, now) })
        return hits.size
    }

    suspend fun history(ruleId: String, limit: Int = 20) = executions.history(ruleId, limit)

    suspend fun executionCount(ruleId: String) = executions.count(ruleId)

    companion object {
        const val TRIGGER_CAPTURE = "CAPTURE"
        const val TRIGGER_HISTORY = "HISTORY"
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val LIMIT = 2000
    }
}
