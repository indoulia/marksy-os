package com.marksy.os

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.RetentionScheduler
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.TradingDeliveryScheduler
import com.marksy.os.notification.NotificationListenerStatus
import com.marksy.os.notification.SourceRegistry
import com.marksy.os.ui.AskMarksyScreen
import com.marksy.os.ui.CalendarScreen
import com.marksy.os.ui.DashboardScreen
import com.marksy.os.ui.EventDetailDialog
import com.marksy.os.ui.MarksyViewModel
import com.marksy.os.ui.MarksyViewModelFactory
import com.marksy.os.ui.TimelineScreen
import com.marksy.os.ui.TradingInsight
import com.marksy.os.ui.TradingInsightDetailDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Background = Color(0xFF070A09)
private val Surface = Color(0xFF101613)
private val SurfaceRaised = Color(0xFF151C18)
private val Primary = Color(0xFF72D49A)
private val TextPrimary = Color(0xFFE8F1EC)
private val TextSecondary = Color(0xFF9AA9A1)
private val TextMuted = Color(0xFF657169)

class MainActivity : ComponentActivity() {
    private var notificationAccessEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetentionScheduler.schedule(applicationContext)
        TradingDeliveryScheduler.schedule(applicationContext)
        notificationAccessEnabled = isNotificationAccessEnabled()
        setContent { MarksyApp() }
    }

    override fun onResume() {
        super.onResume()
        notificationAccessEnabled = isNotificationAccessEnabled()
    }

    private fun isNotificationAccessEnabled(): Boolean = NotificationListenerStatus.isEnabled(this)

    private fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    @Composable
    private fun MarksyApp() {
        val repository = remember { MarksyContainer.repository(applicationContext) }
        val vm: MarksyViewModel = viewModel(factory = MarksyViewModelFactory(repository))
        val events by vm.recentEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val dashboardSnapshot by vm.dashboardSnapshot.collectAsStateWithLifecycle(initialValue = com.marksy.os.intelligence.DashboardSnapshot.from(emptyList()))
        val timelineEvents by vm.timelineEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val historyEvents by vm.historyEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val tradingInsights by vm.tradingInsights.collectAsStateWithLifecycle(initialValue = emptyList())
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var showTimeline by rememberSaveable { mutableStateOf(false) }
        var showCalendar by rememberSaveable { mutableStateOf(false) }
        var selectedEvent by remember { mutableStateOf<NotificationEventEntity?>(null) }
        val tabs = listOf(Tab("Home", Icons.Default.Home), Tab("Inbox", Icons.Default.Inbox), Tab("Ask", Icons.Default.SmartToy), Tab("Trading", Icons.Default.ShowChart), Tab("More", Icons.Default.MoreHoriz))

        Scaffold(
            containerColor = Background,
            bottomBar = {
                if (!showTimeline && !showCalendar) {
                    NavigationBar(containerColor = Color(0xFF0D1210)) {
                        tabs.forEachIndexed { index, tab -> NavigationBarItem(selected = selectedTab == index, onClick = { selectedTab = index }, icon = { Icon(tab.icon, tab.label) }, label = { Text(tab.label) }) }
                    }
                }
            }
        ) { padding ->
            when {
                showTimeline -> TimelineHost(events = timelineEvents, padding = padding, onBack = { showTimeline = false })
                showCalendar -> CalendarHost(events = historyEvents, padding = padding, onBack = { showCalendar = false })
                else -> when (selectedTab) {
                    0 -> DashboardScreen(snapshot = dashboardSnapshot, events = events, onEventSelected = { selectedEvent = it }, modifier = Modifier.fillMaxSize().padding(padding))
                    1 -> InboxScreen(events, padding)
                    2 -> AskMarksyScreen(padding)
                    3 -> TradingScreen(tradingInsights, padding)
                    else -> MoreScreen(access = notificationAccessEnabled, openAccess = ::openNotificationAccess, clearAll = { TradingDeliveryScheduler.cancelPendingDelivery(applicationContext); repository.clearAll(); TradingDeliveryScheduler.schedule(applicationContext) }, openTimeline = { showTimeline = true }, openCalendar = { showCalendar = true }, padding = padding)
                }
            }
        }
        selectedEvent?.let { EventDetailDialog(it) { selectedEvent = null } }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun TimelineHost(events: List<NotificationEventEntity>, padding: PaddingValues, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) { Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Timeline", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }; TimelineScreen(events, PaddingValues(bottom = padding.calculateBottomPadding())) }
}

@Composable
private fun CalendarHost(events: List<NotificationEventEntity>, padding: PaddingValues, onBack: () -> Unit) {
    var selectedEvent by remember { mutableStateOf<NotificationEventEntity?>(null) }
    Column(Modifier.fillMaxSize()) { Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Calendar", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }; CalendarScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding()), onEventSelected = { selectedEvent = it }) }
    selectedEvent?.let { EventDetailDialog(it) { selectedEvent = null } }
}

@Composable
private fun InboxScreen(events: List<NotificationEventEntity>, padding: PaddingValues) {
    val filters = listOf("All", "TRADING", "MESSAGES", "PAYMENTS", "BANKING", "OTHER")
    var selectedFilter by rememberSaveable { mutableStateOf("All") }
    var selectedEvent by remember { mutableStateOf<NotificationEventEntity?>(null) }
    val safeFilter = if (selectedFilter == "All" || selectedFilter in filters) selectedFilter else "All"
    val filtered = if (safeFilter == "All") events else events.filter { it.category == safeFilter }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Smart Inbox", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(5.dp)); Text("Captured locally. Only eligible trading events leave the device.", color = TextSecondary, fontSize = 13.sp); Spacer(Modifier.height(14.dp)); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { filters.forEach { filter -> FilterChip(selected = safeFilter == filter, onClick = { selectedFilter = filter }, label = { Text(if (filter == "All") "All" else filter.lowercase().replaceFirstChar { it.uppercase() }) }) } } }
        if (filtered.isEmpty()) item { EmptyState("Nothing here yet.", "New notifications matching this filter will appear here.") } else items(filtered, key = { it.id }) { event -> EventCard(event) { selectedEvent = event } }
    }
    selectedEvent?.let { EventDetailDialog(it) { selectedEvent = null } }
}

@Composable
private fun EventCard(event: NotificationEventEntity, onClick: () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = if (event.isTrading) SurfaceRaised else Surface), modifier = Modifier.fillMaxWidth(), onClick = onClick) {
    Column(Modifier.padding(14.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(event.sourceName.ifBlank { "Unknown source" }, color = if (event.isTrading) Primary else TextSecondary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text(event.category.lowercase().replaceFirstChar { it.uppercase() }, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp)) }; Spacer(Modifier.height(6.dp)); Text(event.title.ifBlank { "Untitled notification" }, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis); if (event.body.isNotBlank()) Text(event.body, color = Color(0xFFB2BDB6), Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(formatTime(event.postedAt), color = TextMuted, fontSize = 11.sp, maxLines = 1); if (event.isTrading) Text(deliveryLabel(event.deliveryState), color = if (event.deliveryState == DeliveryState.FAILED.name) TextSecondary else Primary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp)) } }
}

@Composable
private fun TradingScreen(insights: List<TradingInsight>, padding: PaddingValues) {
    var selectedInsight by remember { mutableStateOf<TradingInsight?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text("Trading Intelligence", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(5.dp)); Text("Trading events are isolated for Marksy analysis. Execution is disabled in V1.", color = TextSecondary, fontSize = 13.sp); Spacer(Modifier.height(14.dp)) }; if (insights.isEmpty()) item { EmptyState("No trading events yet.", "Order and market notifications will appear here when captured.") } else items(insights, key = { it.eventId }) { insight -> TradingInsightCard(insight) { selectedInsight = insight } } }
    selectedInsight?.let { TradingInsightDetailDialog(it) { selectedInsight = null } }
}

@Composable
private fun TradingInsightCard(insight: TradingInsight, onClick: () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = SurfaceRaised), modifier = Modifier.fillMaxWidth(), onClick = onClick) {
    Column(Modifier.padding(15.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(insight.source.ifBlank { "Trading source" }, color = Primary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text(insight.status.ifBlank { "Unknown status" }, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp)) }; Spacer(Modifier.height(7.dp)); Text(insight.headline.ifBlank { "Trading event" }, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); if (insight.body.isNotBlank()) { Spacer(Modifier.height(5.dp)); Text(insight.body, color = TextSecondary, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }; insight.marksySummary?.takeIf { it.isNotBlank() }?.let { summary -> Spacer(Modifier.height(8.dp)); Text(summary, color = TextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }; Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Classifier ${(insight.confidence * 100).toInt()}%", color = TextMuted, fontSize = 11.sp, maxLines = 1); Text(if (insight.marksySummary.isNullOrBlank()) "Tap for details" else "Marksy analysis • Tap for details", color = Primary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp)) } }
}

@Composable
private fun MoreScreen(access: Boolean, openAccess: () -> Unit, clearAll: suspend () -> Unit, openTimeline: () -> Unit, openCalendar: () -> Unit, padding: PaddingValues) {
    var showClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gatewayConfigured = BuildConfig.MARKSY_INTEGRATION_KEY.trim().isNotBlank() && BuildConfig.MARKSY_API_BASE_URL.trim().isNotBlank()
    ScreenColumn(padding) { Text("More", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(18.dp)); SettingsCard("Notification access", if (access) "ON" else "OFF", if (access) "Marksy OS can capture notifications on this device." else "Allow Marksy OS to capture and organize notifications on this device.") { Button(onClick = openAccess) { Text(if (access) "Manage Notification Access" else "Open Notification Access") } }; Spacer(Modifier.height(12.dp)); SettingsCard("Marksy Gateway", if (gatewayConfigured) "READY" else "NOT CONFIGURED", if (gatewayConfigured) "The Marksy Tips API endpoint and integration key are configured for trading analysis." else "Configure the Marksy API endpoint and integration key before trading events can be sent for analysis."); Spacer(Modifier.height(12.dp)); SettingsCard("Timeline", "LOCAL", "Review meaningful events chronologically. Low-value noise stays out of this view.") { Button(onClick = openTimeline) { Text("Open Timeline") } }; Spacer(Modifier.height(12.dp)); SettingsCard("Calendar", "LOCAL", "Browse retained notification history by month and day. Tap any event for its full details.") { Button(onClick = openCalendar) { Text("Open Calendar") } }; Spacer(Modifier.height(12.dp)); SettingsCard("Sources", "LOCAL", SourceRegistry.knownSources().joinToString(" • ")); Spacer(Modifier.height(12.dp)); SettingsCard("Local data", "7d / 30d", "Ordinary notifications expire after 7 days; trading events are retained locally for 30 days. You can clear everything now.") { Button(onClick = { showClear = true }, enabled = !clearing) { Text(if (clearing) "Clearing…" else "Clear All Local Data") } }; Spacer(Modifier.height(12.dp)); Text("Advanced settings will be added only when they are needed by V1.", color = TextMuted, fontSize = 12.sp) }
    if (showClear) AlertDialog(onDismissRequest = { if (!clearing) showClear = false }, title = { Text("Clear local data?") }, text = { Text("This removes captured notifications and trading intelligence stored on this device. It cannot be undone.") }, confirmButton = { TextButton(enabled = !clearing, onClick = { clearing = true; scope.launch { try { clearAll(); showClear = false } finally { clearing = false } } }) { Text("Clear") } }, dismissButton = { TextButton(enabled = !clearing, onClick = { showClear = false }) { Text("Cancel") } })
}

@Composable
private fun ScreenColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 18.dp, vertical = 20.dp), content = content)

@Composable
private fun SettingsCard(title: String, value: String, description: String, action: (@Composable () -> Unit)? = null) = Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(15.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text(value, color = Primary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp)) }; Spacer(Modifier.height(5.dp)); Text(description, color = TextSecondary, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis); action?.let { Spacer(Modifier.height(10.dp)); it() } } }

@Composable
private fun EmptyState(title: String, description: String) = Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(4.dp)); Text(description, color = TextSecondary, fontSize = 13.sp) } }

private fun deliveryLabel(state: String): String = when (state) { DeliveryState.DELIVERED.name -> "Marksy response received"; DeliveryState.PENDING.name -> "Waiting for Marksy"; DeliveryState.IN_FLIGHT.name -> "Sending to Marksy"; DeliveryState.FAILED.name -> "Delivery failed"; else -> "Local only" }
private fun formatTime(timestamp: Long): String = SimpleDateFormat("dd MMM • HH:mm", Locale.getDefault()).format(Date(timestamp))
