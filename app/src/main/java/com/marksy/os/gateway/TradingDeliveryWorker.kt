package com.marksy.os.gateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marksy.os.capture.CandidateDeliveryRun
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.notification.WhatsAppSenderWatchlist
import kotlinx.coroutines.CancellationException
import kotlin.Result as KotlinResult

class TradingDeliveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            deliverPending()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Trading delivery storage operation failed; retrying")
            Result.retry()
        }
    }

    private suspend fun deliverPending(): Result {
        val dao = MarksyContainer.database(applicationContext).notificationEventDao()
        val captureDao = MarksyContainer.database(applicationContext).captureDao()
        val client = MarksyGatewayProvider.client()
        val now = System.currentTimeMillis()

        dao.recoverStaleInFlight(TradingDeliveryPolicy.staleCutoff(now))
        captureDao.recoverStaleInFlight(TradingDeliveryPolicy.staleCutoff(now))
        if (dao.findPendingCapture(1).isEmpty() && captureDao.findPendingDelivery(1).isEmpty()) return Result.success()

        if (client is UnconfiguredMarksyGatewayClient) {
            Log.i(TAG, "Trading delivery deferred: Marksy Gateway is not configured")
            return Result.success()
        }
        if (MarksyGatewayProvider.currentAuthToken() == null) {
            // Never-signed-in is already caught above; this additionally catches a
            // remembered session that has lapsed and could not be refreshed -- avoids
            // claiming (and per-event failing) an entire batch we already know cannot deliver.
            Log.i(TAG, "Trading delivery deferred: Marksy session is not currently usable")
            return Result.success()
        }

        val capture = loadCaptureContext(client, now)
        if (capture == null) {
            Log.i(TAG, "Trading delivery deferred: no signed-in customer id")
            return Result.success()
        }

        // Finding C2(a): re-read chatSenders right after each findPendingCapture call (inside drain()),
        // not once for the whole run, so a chat row inserted mid-run still masks its own sender.
        val store = CaptureStore(applicationContext)
        val retryEvents = TradingDeliveryRun(dao, client, capture, isStopped = { isStopped }, chatSenders = store::chatSenders).drain()
        // EPIC-036: reviewed captures the user chose to send go after the notification queue.
        val retryCandidates = !isStopped && CandidateDeliveryRun(captureDao, client, capture, isStopped = { isStopped }, log = { Log.i(TAG, it) }).drain()
        return if (retryEvents || retryCandidates) Result.retry() else Result.success()
    }

    private suspend fun loadCaptureContext(client: MarksyGatewayClient, now: Long): CaptureContext? {
        val username = AuthSessionStore(applicationContext).getUserId()?.takeIf { it.isNotBlank() } ?: return null
        val store = CaptureStore(applicationContext)
        if (store.isCaptureListStale(now)) {
            client.captureList()
                .onSuccess { store.saveCapturePackages(it, now) }
                .onFailure { Log.w(TAG, "Capture list refresh failed; using the cached list") }
        }
        return CaptureContext(
            capturePackages = store.capturePackages(),
            chatAllowList = WhatsAppSenderWatchlist.get(applicationContext),
            chatSenders = store.chatSenders(),
            username = username,
            deviceSalt = store.deviceSalt()
        )
    }
}

/** One delivery pass over the PENDING trading queue; true when a transient failure wants a retry. */
internal class TradingDeliveryRun(
    private val dao: NotificationEventDao,
    private val client: MarksyGatewayClient,
    private val capture: CaptureContext,
    private val isStopped: () -> Boolean = { false },
    private val decide: (NotificationEventEntity, CaptureContext) -> CaptureDecision = CaptureGate::decide,
    // Finding C2(a): defaults to the run's initial snapshot (no-op refresh) for callers that don't care;
    // the worker passes a live CaptureStore.chatSenders reference so each batch gets a fresh read.
    private val chatSenders: () -> Set<String> = { capture.chatSenders }
) {
    // Every handled call leaves PENDING, so this ends; a retry stops it so the backoff can run.
    suspend fun drain(): Boolean {
        while (!isStopped()) {
            val pending = dao.findPendingCapture(TradingDeliveryPolicy.BATCH_SIZE)
            if (pending.isEmpty()) return false
            // Re-read right after the query, before this batch is decided: a row inserted mid-run has its
            // sender recorded (and committed, finding C2b) before the row itself is inserted, so this read
            // is guaranteed to already include it.
            val batchCapture = capture.copy(chatSenders = chatSenders())
            if (deliverBatch(pending, batchCapture)) return true
        }
        return false
    }

    private suspend fun deliverBatch(pending: List<NotificationEventEntity>, capture: CaptureContext): Boolean {
        var retryRequested = false
        for (event in pending) {
            if (isStopped()) return false

            val attempts = event.deliveryAttempts + 1
            val claimed = dao.claimPendingTrading(event.id, attempts, System.currentTimeMillis())
            if (claimed != 1) continue

            if (isStopped()) {
                dao.updateInFlightDeliveryState(event.id, DeliveryState.PENDING.name, attempts, System.currentTimeMillis())
                return false
            }

            try {
                val decision = try {
                    decide(event, capture)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (gateError: Throwable) {
                    dao.updateInFlightDeliveryState(event.id, DeliveryState.NOT_APPLICABLE.name, attempts, System.currentTimeMillis())
                    // Finding M3: the exception's class name is diagnosable; its message never is (could carry notification content).
                    Log.i(TAG, "Trading event ${event.id} stays on the phone (gate-error: ${gateError::class.java.simpleName})")
                    continue
                }

                val message = when (decision) {
                    is CaptureDecision.Send -> decision.message
                    CaptureDecision.Wait -> {
                        dao.updateInFlightDeliveryState(event.id, DeliveryState.PENDING.name, attempts, System.currentTimeMillis())
                        retryRequested = true
                        continue
                    }
                    is CaptureDecision.Keep -> {
                        dao.updateInFlightDeliveryState(event.id, DeliveryState.NOT_APPLICABLE.name, attempts, System.currentTimeMillis())
                        dao.setDeliveryNote(event.id, decision.reason)
                        Log.i(TAG, "Trading event ${event.id} stays on the phone (${decision.reason})")
                        continue
                    }
                }

                val result: KotlinResult<MarksyInsight> = try {
                    client.capture(event.id, message)
                } catch (cancellation: CancellationException) {
                    dao.updateInFlightDeliveryState(event.id, DeliveryState.PENDING.name, attempts, System.currentTimeMillis())
                    throw cancellation
                } catch (t: Throwable) {
                    KotlinResult.failure(t)
                }

                result.fold(
                onSuccess = { insight ->
                    val receivedAt = System.currentTimeMillis()
                    val updated = dao.markDeliveredWithInsight(
                        eventId = event.id,
                        state = DeliveryState.DELIVERED.name,
                        attempts = attempts,
                        attemptedAt = receivedAt,
                        summary = insight.summary,
                        action = insight.action,
                        confidence = insight.confidence,
                        receivedAt = receivedAt,
                        tipId = insight.tipId,
                        responseJson = insight.rawResponseJson
                    )
                    if (updated != 1) Log.i(TAG, "Trading event ${event.id} was already transitioned by another worker")
                },
                onFailure = { error ->
                    val state = TradingDeliveryPolicy.retryState(error)
                    val updated = dao.updateInFlightDeliveryState(
                        event.id,
                        state.name,
                        attempts,
                        System.currentTimeMillis()
                    )
                    if (updated != 1) {
                        Log.i(TAG, "Trading event ${event.id} was already transitioned by another worker")
                    } else if (state == DeliveryState.PENDING) {
                        retryRequested = true
                    } else {
                        dao.setDeliveryNote(event.id, TradingDeliveryPolicy.failureCode(error))
                        // Deliberately do not log the exception message: backend error
                        // details must never become a notification-content side channel.
                        Log.w(TAG, "Trading event ${event.id} permanently rejected")
                    }
                }
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.w(TAG, "Trading delivery storage operation failed for row ${event.id}; retrying")
                retryRequested = true
            }
        }
        return retryRequested
    }
}

private const val TAG = "MarksyTradingDelivery"
