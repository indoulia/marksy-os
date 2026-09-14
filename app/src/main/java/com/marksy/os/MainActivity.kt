package com.marksy.os

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.RetentionScheduler
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.SecureCredentialStore
import com.marksy.os.gateway.TradingDeliveryScheduler
import com.marksy.os.notification.NotificationListenerStatus
import com.marksy.os.notification.WhatsAppConnectorStatus
import com.marksy.os.notification.WhatsAppSettingsActivity
import com.marksy.os.ui.AskMarksyScreen
import com.marksy.os.ui.CalendarScreen
import com.marksy.os.ui.DashboardScreen
import com.marksy.os.ui.EventDetailDialog
import com.marksy.os.ui.InsightsScreen
import com.marksy.os.ui.MarksyViewModel
import com.marksy.os.ui.MarksyViewModelFactory
import com.marksy.os.ui.RulesScreen
import com.marksy.os.ui.SmartInboxScreen
import com.marksy.os.ui.TimelineScreen
import com.marksy.os.ui.TradingInsight
import com.marksy.os.ui.TradingInsightDetailDialog

private val Background = Color(0xFF070A09)
private val Surface = Color(0xFF101613)
private val Raised = Color(0xFF151C18)
private val Primary = Color(0xFF72D49A)
private val TextPrimary = Color(0xFFE8F1EC)
private val TextSecondary = Color(0xFF9AA9A1)
private val TextMuted = Color(0xFF657169)

class MainActivity : ComponentActivity() {
    private var notificationAccessEnabled by mutableStateOf(false)
    private var whatsappConnectorEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetentionScheduler.schedule(applicationContext)
        TradingDeliveryScheduler.schedule(applicationContext)
        notificationAccessEnabled = NotificationListenerStatus.isEnabled(this)
        whatsappConnectorEnabled = WhatsAppConnectorStatus.isAccessibilityServiceEnabled(this)
        setContent { MarksyApp() }
    }

    override fun onResume() {
        super.onResume()
        notificationAccessEnabled = NotificationListenerStatus.isEnabled(this)
        whatsappConnectorEnabled = WhatsAppConnectorStatus.isAccessibilityServiceEnabled(this)
    }

    private fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    private fun openWhatsAppConnector() = startActivity(Intent(this, WhatsAppSettingsActivity::class.java))

    @Composable
    private fun MarksyApp() {
        val repository = remember { MarksyContainer.repository(applicationContext) }
        val vm: MarksyViewModel = viewModel(factory = MarksyViewModelFactory(repository))
        val events by vm.recentEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val snapshot by vm.dashboardSnapshot.collectAsStateWithLifecycle(initialValue = com.marksy.os.intelligence.DashboardSnapshot.from(emptyList()))
        val timelineEvents by vm.timelineEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val historyEvents by vm.historyEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val tradingInsights by vm.tradingInsights.collectAsStateWithLifecycle(initialValue = emptyList())

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var showTimeline by rememberSaveable { mutableStateOf(false) }
        var showCalendar by rememberSaveable { mutableStateOf(false) }
        var showInsights by rememberSaveable { mutableStateOf(false) }
        var showRules by rememberSaveable { mutableStateOf(false) }
        var showGatewaySettings by rememberSaveable { mutableStateOf(false) }
        var selectedEvent by remember { mutableStateOf<NotificationEventEntity?>(null) }

        val openEvent: (NotificationEventEntity) -> Unit = { selectedEvent = it }
        val tabs = listOf("Home" to Icons.Default.Home, "Inbox" to Icons.Default.Inbox, "Ask" to Icons.Default.SmartToy, "Trading" to Icons.Default.ShowChart, "More" to Icons.Default.MoreHoriz)

        Scaffold(containerColor = Background, bottomBar = {
            if (!showTimeline && !showCalendar && !showInsights && !showRules && !showGatewaySettings) {
                NavigationBar(containerColor = Color(0xFF0D1210)) {
                    tabs.forEachIndexed { index, (label, icon) -> NavigationBarItem(selected = selectedTab == index, onClick = { selectedTab = index }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label) }) }
                }
            }
        }) { padding ->
            when {
                showTimeline -> TimelineHost(timelineEvents, padding, openEvent) { showTimeline = false }
                showCalendar -> CalendarHost(historyEvents, padding, openEvent) { showCalendar = false }
                showInsights -> InsightsHost(historyEvents, padding) { showInsights = false }
                showRules -> RulesHost(padding) { showRules = false }
                showGatewaySettings -> GatewaySettingsHost(padding) { showGatewaySettings = false }
                selectedTab == 0 -> DashboardScreen(snapshot = snapshot, events = events, onEventSelected = openEvent, modifier = Modifier.fillMaxSize().padding(padding))
                selectedTab == 1 -> SmartInboxScreen(events = events, padding = padding, onEventSelected = openEvent)
                selectedTab == 2 -> AskMarksyScreen(padding)
                selectedTab == 3 -> TradingScreen(tradingInsights, padding)
                else -> MoreScreen(access = notificationAccessEnabled, whatsappAccess = whatsappConnectorEnabled, openAccess = ::openNotificationAccess, openWhatsAppAccess = ::openWhatsAppConnector, openGatewaySettings = { showGatewaySettings = true }, clearAll = {
                    TradingDeliveryScheduler.cancelPendingDelivery(applicationContext)
                    repository.clearAll()
                    TradingDeliveryScheduler.schedule(applicationContext)
                }, openTimeline = { showTimeline = true }, openCalendar = { showCalendar = true }, openInsights = { showInsights = true }, openRules = { showRules = true }, padding = padding)
            }
        }
        selectedEvent?.let { event ->
            EventDetailDialog(event = event, onArchive = { vm.archive(event.id); selectedEvent = null }, onUnarchive = { vm.unarchive(event.id); selectedEvent = null }, onDismiss = { selectedEvent = null })
        }
    }
}

@Composable private fun GatewaySettingsHost(padding: PaddingValues, onBack: () -> Unit) {
    val store = remember { SecureCredentialStore(AppContext.get()) }
    var key by rememberSaveable { mutableStateOf("") }
    var configured by remember { mutableStateOf(store.getIntegrationKey() != null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var revealKey by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
        ScreenHeader("Marksy Gateway", onBack)
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (configured) "Gateway configured" else "Gateway not configured", color = if (configured) Primary else TextSecondary, fontWeight = FontWeight.SemiBold)
            Text("The integration key is encrypted with Android Keystore and is never displayed after saving.", color = TextSecondary, fontSize = 13.sp)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Integration key") },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { revealKey = !revealKey }) { Text(if (revealKey) "Hide" else "Show") }
                }
            )
            Button(onClick = {
                runCatching { store.setIntegrationKey(key.trim()) }.onSuccess {
                    key = ""
                    revealKey = false
                    configured = true
                    message = "Saved securely on this device."
                }.onFailure {
                    message = "Could not save the credential. Try again."
                }
            }, enabled = key.isNotBlank()) { Text("Save Securely") }
            OutlinedButton(onClick = {
                store.clearIntegrationKey()
                key = ""
                revealKey = false
                configured = false
                message = "Credential removed. Trading delivery is disabled until configured."
            }) { Text("Remove Credential") }
            message?.let { Text(it, color = TextSecondary, fontSize = 13.sp) }
        }
    }
}

@Composable private fun TimelineHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit, onBack: () -> Unit) { Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) { ScreenHeader("Timeline", onBack); TimelineScreen(events, PaddingValues(), onEventSelected) } }
@Composable private fun CalendarHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit, onBack: () -> Unit) { Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) { ScreenHeader("Calendar", onBack); CalendarScreen(events = events, padding = PaddingValues(), onEventSelected = onEventSelected) } }
@Composable private fun InsightsHost(events: List<NotificationEventEntity>, padding: PaddingValues, onBack: () -> Unit) { Column(Modifier.fillMaxSize()) { ScreenHeader("Insights", onBack); InsightsScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding())) } }
@Composable private fun RulesHost(padding: PaddingValues, onBack: () -> Unit) { Column(Modifier.fillMaxSize()) { ScreenHeader("Rules & Automation", onBack); RulesScreen(PaddingValues(bottom = padding.calculateBottomPadding())) } }
@Composable private fun ScreenHeader(title: String, onBack: () -> Unit) { Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }; Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) } }

@Composable private fun TradingScreen(insights: List<TradingInsight>, padding: PaddingValues) { var selected by remember { mutableStateOf<TradingInsight?>(null) }; LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text("Trading Intelligence", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(5.dp)); Text("Trading events are isolated for Marksy analysis. Execution is disabled in V1.", color = TextSecondary, fontSize = 13.sp); Spacer(Modifier.height(14.dp)) }; if (insights.isEmpty()) item { EmptyState("No trading events yet.", "Order and market notifications will appear here when captured.") } else items(insights, key = { it.eventId }) { insight -> TradingInsightCard(insight) { selected = insight } } }; selected?.let { TradingInsightDetailDialog(it) { selected = null } } }
@Composable private fun TradingInsightCard(insight: TradingInsight, onClick: () -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = Raised), modifier = Modifier.fillMaxWidth(), onClick = onClick) { Column(Modifier.padding(15.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(insight.source.ifBlank { "Trading source" }, color = Primary, fontWeight = FontWeight.SemiBold); Text(insight.status.ifBlank { "Unknown" }, color = TextSecondary, fontSize = 11.sp) }; Spacer(Modifier.height(7.dp)); Text(insight.headline.ifBlank { "Trading event" }, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); if (insight.body.isNotBlank()) Text(insight.body, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)); insight.marksySummary?.takeIf { it.isNotBlank() }?.let { Text(it, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }; Spacer(Modifier.height(9.dp)); Text("Classifier ${(insight.confidence * 100).toInt()}%  •  Tap for details", color = TextMuted, fontSize = 11.sp) } } }

@Composable private fun MoreScreen(access: Boolean, whatsappAccess: Boolean, openAccess: () -> Unit, openWhatsAppAccess: () -> Unit, openGatewaySettings: () -> Unit, clearAll: suspend () -> Unit, openTimeline: () -> Unit, openCalendar: () -> Unit, openInsights: () -> Unit, openRules: () -> Unit, padding: PaddingValues) {
    var showClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val store = remember { SecureCredentialStore(AppContext.get()) }
    var gatewayConfigured by remember { mutableStateOf(store.getIntegrationKey() != null) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("More", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("Marksy OS settings and local data controls.", color = TextSecondary, fontSize = 13.sp) }
        item { SettingsCard("Notification access", if (access) "ON" else "OFF", if (access) "Marksy OS can capture notifications." else "Enable notification access to start capturing.") { Button(onClick = openAccess) { Text(if (access) "Manage Access" else "Open Access") } } }
        item { SettingsCard("WhatsApp connector", if (whatsappAccess) "ON" else "OPTIONAL", "Reads visible WhatsApp accessibility text only for senders on your local allow-list.") { Button(onClick = openWhatsAppAccess) { Text(if (whatsappAccess) "Manage Connector" else "Set Up Connector") } } }
        item { SettingsCard("Marksy Gateway", if (gatewayConfigured) "READY" else "NOT CONFIGURED", "Integration credentials are stored securely on this device. Live brokerage execution is disabled in V1.") { Button(onClick = openGatewaySettings) { Text("Configure Gateway") } } }
        item { SettingsCard("Insights", "LOCAL", "Review notification patterns, attention levels, and trading intelligence delivery health.") { Button(onClick = openInsights) { Text("Open Insights") } } }
        item { SettingsCard("Rules & Automation", "LOCAL", "Create deterministic local rules for highlighting and prioritizing events. Brokerage execution is not available.") { Button(onClick = openRules) { Text("Open Rules") } } }
        item { SettingsCard("Timeline", "LOCAL", "Review meaningful events chronologically.") { Button(onClick = openTimeline) { Text("Open Timeline") } } }
        item { SettingsCard("Calendar", "LOCAL", "Browse retained notification history by day.") { Button(onClick = openCalendar) { Text("Open Calendar") } } }
        item { SettingsCard("Local data", "7d / 30d", "Ordinary notifications expire after 7 days; trading events are retained for 30 days.") { Button(onClick = { showClear = true }, enabled = !clearing) { Text(if (clearing) "Clearing…" else "Clear All Local Data") } } }
    }
    if (showClear) AlertDialog(onDismissRequest = { if (!clearing) showClear = false }, title = { Text("Clear local data?") }, text = { Text("This removes captured notifications and trading intelligence stored on this device. It cannot be undone.") }, confirmButton = { TextButton(enabled = !clearing, onClick = { clearing = true; scope.launch { try { clearAll(); gatewayConfigured = store.getIntegrationKey() != null } finally { clearing = false; showClear = false } } }) { Text("Clear") } }, dismissButton = { TextButton(enabled = !clearing, onClick = { showClear = false }) { Text("Cancel") } })
}

@Composable private fun SettingsCard(title: String, status: String, description: String, action: (@Composable () -> Unit)? = null) { Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(15.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold); Text(status, color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }; Spacer(Modifier.height(6.dp)); Text(description, color = TextSecondary, fontSize = 13.sp); action?.let { Spacer(Modifier.height(10.dp)); it() } } } }
@Composable internal fun EmptyState(title: String, message: String) { Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); Text(message, color = TextSecondary, fontSize = 13.sp) } } }