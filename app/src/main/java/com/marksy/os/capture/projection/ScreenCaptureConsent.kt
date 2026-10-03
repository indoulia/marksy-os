package com.marksy.os.capture.projection

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.marksy.os.capture.CaptureFailure
import com.marksy.os.capture.CaptureMethod
import com.marksy.os.capture.WorkflowState
import com.marksy.os.data.MarksyContainer
import kotlinx.coroutines.launch

enum class ScreenCaptureStart {
    STARTED,
    /** Another capture session is running or waiting for consent. */
    BUSY,
    /** The ongoing notification (with "Capture now") could not be shown; ask for notifications first. */
    NOTIFICATIONS_OFF,
    WORKFLOW_CLOSED,
    UNSUPPORTED
}

/** Starts "Capture tip" for a workflow: the system consent screen, then the capture service. */
class ScreenCaptureLauncher internal constructor(private val context: Context, private val consent: ActivityResultLauncher<Intent>) {
    suspend fun start(workflowId: Long): ScreenCaptureStart = ScreenCaptureConsent.request(context, workflowId, consent::launch)
}

@Composable
fun rememberScreenCaptureLauncher(): ScreenCaptureLauncher {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch { ScreenCaptureConsent.onResult(context, result.resultCode, result.data) }
    }
    return remember(consent) { ScreenCaptureLauncher(context, consent) }
}

/** Consent is asked every time and never cached: one consent, one session, one frame. */
object ScreenCaptureConsent {
    private val controller get() = CaptureSessionController.shared

    /** API 34+ lets the user share a single app window instead of the whole screen. */
    fun intent(context: Context): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else manager.createScreenCaptureIntent()
    }

    fun notificationsShown(context: Context): Boolean {
        val notifications = NotificationManagerCompat.from(context)
        val channel = notifications.getNotificationChannelCompat(ScreenCaptureService.CHANNEL_ID)
        return notifications.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    suspend fun request(context: Context, workflowId: Long, launch: (Intent) -> Unit): ScreenCaptureStart {
        if (!notificationsShown(context)) return ScreenCaptureStart.NOTIFICATIONS_OFF
        if (!controller.requestConsent(workflowId)) return ScreenCaptureStart.BUSY
        val gateway = MarksyContainer.captureGateway(context)
        // A consent answer lost to a recreated screen leaves the workflow at CAPTURE_REQUESTED; asking again is fine.
        val requested = gateway.captureRequested(workflowId) ||
            MarksyContainer.database(context).captureDao().workflow(workflowId)?.state == WorkflowState.CAPTURE_REQUESTED.name
        if (!requested) {
            controller.abandon(workflowId)
            return ScreenCaptureStart.WORKFLOW_CLOSED
        }
        return try {
            launch(intent(context))
            ScreenCaptureStart.STARTED
        } catch (e: ActivityNotFoundException) {
            controller.finish(workflowId, gateway.submitFailure(CaptureMethod.MEDIA_PROJECTION, workflowId, CaptureFailure.UNSUPPORTED_ANDROID))
            ScreenCaptureStart.UNSUPPORTED
        }
    }

    suspend fun onResult(context: Context, resultCode: Int, data: Intent?) {
        // After process death the session is gone and the token is dropped unused.
        val workflowId = (controller.state.value as? CaptureSessionState.AwaitingConsent)?.workflowId ?: return
        val gateway = MarksyContainer.captureGateway(context)
        if (resultCode != Activity.RESULT_OK || data == null) {
            controller.finish(workflowId, gateway.submitFailure(CaptureMethod.MEDIA_PROJECTION, workflowId, CaptureFailure.CAPTURE_DENIED))
            return
        }
        if (!gateway.captureAuthorized(workflowId)) {
            controller.abandon(workflowId)
            return
        }
        try {
            ContextCompat.startForegroundService(context, ScreenCaptureService.startIntent(context, resultCode, data, workflowId))
        } catch (e: Exception) {
            Log.i("MarksyCapture", "capture session workflow=$workflowId service-refused (${e.javaClass.simpleName})")
            controller.finish(workflowId, gateway.submitFailure(CaptureMethod.MEDIA_PROJECTION, workflowId, CaptureFailure.CAPTURE_FAILED))
        }
    }
}
