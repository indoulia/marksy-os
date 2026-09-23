package com.marksy.os.data

import com.marksy.os.data.local.EventActionDao
import com.marksy.os.data.local.EventActionEntity
import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.ActionEngine
import com.marksy.os.intelligence.ActionEngine.Type
import com.marksy.os.intelligence.EventNormalizer
import com.marksy.os.intelligence.PersonalLearning
import kotlinx.coroutines.flow.Flow

/** Side-effecting half of EPIC-014; implemented on Android by AndroidActionPlatform. */
interface ActionPlatform : ActionEngine.Capabilities {
    fun launch(sourcePackage: String): Boolean
    fun scheduleReminder(actionId: Long, atMillis: Long)
}

enum class ActionState { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

/**
 * Executes discovered actions with a full audit row per attempt. Failures are recorded, never
 * swallowed, and interrupted work is recovered by [recover].
 */
class ActionRepository(
    private val actionDao: EventActionDao,
    private val eventDao: NotificationEventDao,
    private val notifications: NotificationRepository,
    private val learning: LearningRepository?,
    private val platform: ActionPlatform,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Outcome(val actionId: Long, val state: ActionState, val message: String)

    fun discover(event: NotificationEventEntity) = ActionEngine.discover(event, platform, clock())

    fun history(eventId: Long): Flow<List<EventActionEntity>> = actionDao.observeForEvent(eventId)

    suspend fun execute(eventId: Long, type: Type, scheduledFor: Long? = null, detail: String? = null): Outcome {
        val now = clock()
        val event = eventDao.getById(eventId) ?: return Outcome(-1, ActionState.FAILED, "Event no longer exists")
        // Re-check at execution time: the app may have been uninstalled since discovery.
        val offered = ActionEngine.discover(event, platform, now).firstOrNull { it.type == type }
        if (offered == null || !offered.enabled) {
            val id = actionDao.insert(row(eventId, type, ActionState.FAILED, now, scheduledFor, detail, error = offered?.reason ?: "Not available for this event"))
            return Outcome(id, ActionState.FAILED, offered?.reason ?: "Not available for this event")
        }

        return when (type) {
            Type.OPEN_SOURCE, Type.TRACK, Type.ORDER, Type.PAYMENT -> runNow(eventId, type, now, detail) {
                if (platform.launch(event.sourcePackage)) null else "${event.sourceName} could not be opened"
            }
            Type.REMIND -> {
                val at = scheduledFor?.takeIf { it > now }
                    ?: return Outcome(actionDao.insert(row(eventId, type, ActionState.FAILED, now, scheduledFor, detail, error = "Time is not in the future")), ActionState.FAILED, "Pick a time in the future")
                val id = actionDao.insert(row(eventId, type, ActionState.PENDING, now, at, detail))
                platform.scheduleReminder(id, at)
                Outcome(id, ActionState.PENDING, "Reminder set")
            }
            Type.MARK_EXPECTED -> {
                val expectedAt = scheduledFor ?: EventNormalizer.factsFromJson(event.intelligenceJson).times.firstOrNull { it.epochMillis > now }?.epochMillis
                val id = actionDao.insert(row(eventId, type, ActionState.SUCCEEDED, now, expectedAt, detail))
                Outcome(id, ActionState.SUCCEEDED, "Marked as expected")
            }
            Type.MARK_RESOLVED -> runNow(eventId, type, now, detail) {
                if (notifications.resolveThread(listOf(eventId), now) > 0) null else "Already resolved"
            }
            Type.REPORT -> runNow(eventId, type, now, "${event.category}->${detail ?: "UNSPECIFIED"}") {
                // Trading routing (isTrading/delivery state) is source-driven, so it is recorded but never re-labelled.
                val corrected = detail?.takeIf { it in CATEGORIES && it != "TRADING" && !event.isTrading }
                if (corrected != null) eventDao.updateCategory(eventId, corrected)
                null
            }
            Type.IGNORE -> runNow(eventId, type, now, detail) {
                learning?.record(listOf(event), PersonalLearning.Signal.IGNORED)
                if (notifications.archive(eventId)) null else "Could not archive"
            }
        }
    }

    /** Called by the reminder worker when it fires. */
    suspend fun completeReminder(actionId: Long, post: (EventActionEntity, NotificationEventEntity?) -> Boolean): ActionState {
        val now = clock()
        if (actionDao.transition(actionId, ActionState.PENDING.name, ActionState.RUNNING.name, now, attemptDelta = 1) == 0) {
            return actionDao.get(actionId)?.state?.let { ActionState.valueOf(it) } ?: ActionState.CANCELLED
        }
        val action = actionDao.get(actionId)!!
        val ok = runCatching { post(action, eventDao.getById(action.eventId)) }.getOrDefault(false)
        val to = if (ok) ActionState.SUCCEEDED else ActionState.FAILED
        actionDao.transition(actionId, ActionState.RUNNING.name, to.name, clock(), if (ok) null else "Reminder notification could not be shown")
        return to
    }

    suspend fun cancel(actionId: Long): Boolean =
        actionDao.transition(actionId, ActionState.PENDING.name, ActionState.CANCELLED.name, clock()) > 0

    /** Startup/boot recovery: interrupted rows become PENDING and every pending reminder is (idempotently) rescheduled. */
    suspend fun recover(): Int {
        val now = clock()
        actionDao.recoverStaleRunning(now - STALE_RUNNING_MS, now)
        val pending = actionDao.pendingOfType(Type.REMIND.name)
        pending.forEach { platform.scheduleReminder(it.id, (it.scheduledFor ?: now).coerceAtLeast(now)) }
        return pending.size
    }

    private suspend fun runNow(eventId: Long, type: Type, now: Long, detail: String?, block: suspend () -> String?): Outcome {
        val id = actionDao.insert(row(eventId, type, ActionState.RUNNING, now, null, detail))
        val error = try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.javaClass.simpleName
        }
        val to = if (error == null) ActionState.SUCCEEDED else ActionState.FAILED
        actionDao.transition(id, ActionState.RUNNING.name, to.name, clock(), error, attemptDelta = 1)
        return Outcome(id, to, error ?: "Done")
    }

    private fun row(eventId: Long, type: Type, state: ActionState, now: Long, scheduledFor: Long?, detail: String?, error: String? = null) =
        EventActionEntity(eventId = eventId, type = type.name, state = state.name, createdAt = now, updatedAt = now,
            scheduledFor = scheduledFor, detail = detail?.take(MAX_DETAIL), error = error)

    companion object {
        val CATEGORIES = setOf("TRADING", "BANKING", "BILLS", "PAYMENTS", "OTP", "REMINDERS", "MESSAGES", "WORK", "EMAIL", "DELIVERY", "PROMOTIONS", "SYSTEM", "OTHER")
        private const val STALE_RUNNING_MS = 10 * 60 * 1000L
        private const val MAX_DETAIL = 80
    }
}
