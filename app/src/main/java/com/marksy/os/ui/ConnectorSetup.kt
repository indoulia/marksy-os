package com.marksy.os.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.connector.CalendarConnector
import com.marksy.os.connector.ConnectorState
import com.marksy.os.connector.ConnectorSyncWorker
import com.marksy.os.connector.SyncConnectors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** EPIC-021 pull-connector controls with honest status: nothing is shown as syncing unless it actually can. */
@Composable
internal fun ConnectorSetupCard() {
    val context = LocalContext.current.applicationContext
    val syncer = remember(context) { SyncConnectors.syncer(context) }
    val connectors = remember(context) { SyncConnectors.all(context) }
    var tick by remember { mutableIntStateOf(0) }
    fun connect(id: String, on: Boolean) {
        syncer.setEnabled(id, on)
        if (on) ConnectorSyncWorker.schedule(context)
        else if (connectors.none { syncer.status(it.descriptor.id).enabled }) ConnectorSyncWorker.cancel(context)
        tick++
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) connect(CalendarConnector.ID, true) else tick++
    }
    val time = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxWidth().background(MarksyTheme.Surface, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text("Direct sources", color = MarksyTheme.TextPrimary, fontSize = 13.sp)
        connectors.forEach { c ->
            val id = c.descriptor.id
            val status = remember(tick) { syncer.status(id) }
            val state = remember(tick) { runCatching { c.state() }.getOrDefault(ConnectorState.NOT_AVAILABLE) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.descriptor.label, color = MarksyTheme.TextSecondary, fontSize = 12.sp)
                    val detail = when {
                        state == ConnectorState.NOT_CONFIGURED -> "Not available yet: needs Google sign-in setup. Gmail still arrives via its notifications."
                        !status.enabled -> c.descriptor.mechanism
                        state == ConnectorState.NEEDS_PERMISSION -> "Permission was removed; reconnect to grant it again."
                        else -> buildString {
                            append(status.lastSuccessAt?.let { "Last sync ${time.format(Date(it))} (+${status.lastAdded} new, ${status.lastUpdated} updated, ${status.lastRemoved} removed)" } ?: "Waiting for first sync")
                            status.lastError?.let { append(" · last problem: $it") }
                        }
                    }
                    Text(detail, color = MarksyTheme.TextMuted, fontSize = 10.sp)
                }
                Switch(
                    checked = status.enabled && state != ConnectorState.NOT_CONFIGURED,
                    enabled = state != ConnectorState.NOT_CONFIGURED && state != ConnectorState.NOT_AVAILABLE,
                    onCheckedChange = { on ->
                        if (on && id == CalendarConnector.ID && state == ConnectorState.NEEDS_PERMISSION) calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                        else connect(id, on)
                    }
                )
            }
        }
        Text("SMS · via notifications only. Android restricts direct SMS access (READ_SMS) to default SMS apps.", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
