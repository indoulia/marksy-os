package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marksy.os.capture.projection.CaptureSessionController
import com.marksy.os.capture.projection.CaptureSessionState
import com.marksy.os.capture.projection.ScreenCaptureService
import androidx.compose.foundation.layout.Arrangement

/** In-app marker of a running screen-capture session, with Stop; the service notification is the system-level one. */
@Composable
fun CaptureSessionBar() {
    val state by CaptureSessionController.shared.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (state !is CaptureSessionState.Active && state !is CaptureSessionState.Capturing) return
    Row(
        Modifier.fillMaxWidth().background(MarksyTheme.Surface).padding(horizontal = MarksySpace.Gutter, vertical = MarksySpace.Gap),
        horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MarksyBadge("Capture on", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
        Text(
            if (state is CaptureSessionState.Capturing) "Taking one screenshot…" else "Open the tip, then tap Capture now in the notification.",
            color = MarksyTheme.TextSecondary, style = MarksyType.Small, modifier = Modifier.weight(1f)
        )
        if (state is CaptureSessionState.Active) Pill("Stop") { ScreenCaptureService.stop(context) }
    }
}
