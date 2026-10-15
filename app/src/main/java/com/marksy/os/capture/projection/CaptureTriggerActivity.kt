package com.marksy.os.capture.projection

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.marksy.os.capture.CaptureRouting
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Capture now": an invisible activity so the notification shade collapses over the source app,
 * then one frame is taken and Marksy opens on its review.
 */
class CaptureTriggerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = CaptureSessionController.shared
        val active = controller.state.value as? CaptureSessionState.Active
        if (savedInstanceState != null || active == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            delay(SHADE_SETTLE_MS)
            val asked = runCatching { startService(ScreenCaptureService.captureIntent(this@CaptureTriggerActivity)) }.isSuccess
            val done = if (!asked) null else withTimeoutOrNull(OUTCOME_WAIT_MS) {
                controller.state.first { it is CaptureSessionState.Finished && it.workflowId == active.workflowId } as CaptureSessionState.Finished
            }
            startActivity(CaptureRouting.mainIntent(this@CaptureTriggerActivity, done?.outcome))
            finish()
        }
    }

    companion object {
        private const val SHADE_SETTLE_MS = 500L
        private const val OUTCOME_WAIT_MS = 20_000L
    }
}
