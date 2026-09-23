package com.marksy.os

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.marksy.os.gateway.GatewayQrParser
import com.marksy.os.gateway.SecureCredentialStore
import com.marksy.os.gateway.TradingDeliveryScheduler
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.BackHandler
import com.marksy.os.intelligence.DashboardSnapshot
import com.marksy.os.intelligence.NotificationTrend
import com.marksy.os.weather.Weather
import com.marksy.os.weather.WeatherRepository
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import com.marksy.os.intelligence.SmartInboxModel
import com.marksy.os.notification.NotificationListenerStatus
import com.marksy.os.notification.WhatsAppConnectorStatus
import com.marksy.os.notification.WhatsAppSettingsActivity
import com.marksy.os.ui.AskMarksyScreen
import com.marksy.os.ui.CalendarScreen
import com.marksy.os.ui.DailyDigestScreen
import com.marksy.os.ui.DashboardScreen
import com.marksy.os.ui.EventDetailDialog
import com.marksy.os.ui.InsightsScreen
import com.marksy.os.ui.MarksyTheme
import com.marksy.os.ui.MarksyViewModel
import com.marksy.os.ui.MarksyViewModelFactory
import com.marksy.os.ui.RulesScreen
import com.marksy.os.ui.SmartInboxScreen
import com.marksy.os.ui.TimelineScreen
import com.marksy.os.ui.TradingInsight
import com.marksy.os.ui.TradingInsightDetailDialog
import com.marksy.os.ui.TradingIntelligenceScreen
import com.marksy.os.ui.toTradingInsight

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
        val snapshot by vm.dashboardSnapshot.collectAsStateWithLifecycle(initialValue = DashboardSnapshot.from(emptyList()))
        val timelineEvents by vm.timelineEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val historyEvents by vm.historyEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val activePostedAt by vm.activePostedAt.collectAsStateWithLifecycle(initialValue = emptyList())
        val tradingInsights by vm.tradingInsights.collectAsStateWithLifecycle(initialValue = emptyList())

        val trend = remember(activePostedAt) { NotificationTrend.fromTimestamps(activePostedAt, System.currentTimeMillis()) }
        var locationGranted by remember { mutableStateOf(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
        val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locationGranted = it }
        val weather by produceState<Weather?>(null, locationGranted) {
            while (locationGranted) {
                value = WeatherRepository.current(applicationContext) ?: value
                delay(30 * 60 * 1000L)
            }
        }

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var inboxFilterName by rememberSaveable { mutableStateOf(SmartInboxModel.Filter.ALL.name) }
        var showTimeline by rememberSaveable { mutableStateOf(false) }
        var showCalendar by rememberSaveable { mutableStateOf(false) }
        var showInsights by rememberSaveable { mutableStateOf(false) }
        var showRules by rememberSaveable { mutableStateOf(false) }
        var showDigest by rememberSaveable { mutableStateOf(false) }
        var showGatewaySettings by rememberSaveable { mutableStateOf(false) }
        var selectedEvent by remember { mutableStateOf<NotificationEventEntity?>(null) }
        var selectedTradingInsight by remember { mutableStateOf<TradingInsight?>(null) }

        val openEvent: (NotificationEventEntity) -> Unit = { selectedEvent = it }
        val openCategory: (String) -> Unit = { label ->
            inboxFilterName = SmartInboxModel.Filter.forCategoryLabel(label).name
            selectedTab = 1
        }

        // System back / swipe: close an open sub-screen, else return to Home, else exit.
        val hostOpen = showTimeline || showCalendar || showInsights || showRules || showDigest || showGatewaySettings
        BackHandler(enabled = hostOpen || selectedTab != 0) {
            when {
                showTimeline -> showTimeline = false
                showCalendar -> showCalendar = false
                showInsights -> showInsights = false
                showRules -> showRules = false
                showDigest -> showDigest = false
                showGatewaySettings -> showGatewaySettings = false
                selectedTab != 0 -> selectedTab = 0
            }
        }
        val tabs = listOf(
            "Home" to Icons.Default.Home,
            "Inbox" to Icons.Default.Inbox,
            "Ask" to Icons.Default.AutoAwesome,
            "Trading" to Icons.Default.ShowChart,
            "More" to Icons.Default.MoreHoriz
        )

        Scaffold(
            containerColor = MarksyTheme.Background,
            bottomBar = {
                NavigationBar(
                    containerColor = MarksyTheme.Surface,
                    contentColor = MarksyTheme.TextSecondary
                ) {
                    tabs.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = {
                                // Tapping a tab also closes any open sub-screen (Timeline, Calendar, …).
                                showTimeline = false; showCalendar = false; showInsights = false
                                showRules = false; showDigest = false; showGatewaySettings = false
                                selectedTab = index
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = MarksyTheme.PrimaryEmerald,
                                indicatorColor = MarksyTheme.PrimaryEmerald,
                                unselectedIconColor = MarksyTheme.TextMuted,
                                unselectedTextColor = MarksyTheme.TextMuted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when {
                showTimeline -> TimelineHost(timelineEvents, padding, openEvent)
                showCalendar -> CalendarHost(historyEvents, padding, openEvent)
                showInsights -> InsightsHost(historyEvents, padding)
                showRules -> RulesHost(padding)
                showDigest -> DigestHost(events, padding)
                showGatewaySettings -> GatewaySettingsHost(padding)
                selectedTab == 0 -> DashboardScreen(
                    snapshot = snapshot,
                    events = events,
                    onEventSelected = openEvent,
                    onCategorySelected = openCategory,
                    onOpenTimeline = { showTimeline = true },
                    onOpenCalendar = { showCalendar = true },
                    onOpenInsights = { showInsights = true },
                    trend = trend,
                    weather = weather,
                    weatherAvailable = locationGranted,
                    onRequestWeather = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                selectedTab == 1 -> SmartInboxScreen(
                    events = events,
                    padding = padding,
                    onEventSelected = openEvent,
                    selectedFilterName = inboxFilterName,
                    onFilterSelected = { inboxFilterName = it }
                )
                selectedTab == 2 -> AskMarksyScreen(padding)
                selectedTab == 3 -> TradingIntelligenceScreen(tradingInsights, padding) { selectedTradingInsight = it }
                else -> MoreScreen(
                    access = notificationAccessEnabled,
                    whatsappAccess = whatsappConnectorEnabled,
                    openAccess = ::openNotificationAccess,
                    openWhatsAppAccess = ::openWhatsAppConnector,
                    openGatewaySettings = { showGatewaySettings = true },
                    clearAll = {
                        TradingDeliveryScheduler.cancelPendingDelivery(applicationContext)
                        repository.clearAll()
                        TradingDeliveryScheduler.schedule(applicationContext)
                    },
                    openTimeline = { showTimeline = true },
                    openCalendar = { showCalendar = true },
                    openInsights = { showInsights = true },
                    openRules = { showRules = true },
                    openDigest = { showDigest = true },
                    padding = padding
                )
            }
        }

        selectedEvent?.let { event ->
            EventDetailDialog(
                event = event,
                onArchive = { vm.archive(event.id); selectedEvent = null },
                onUnarchive = { vm.unarchive(event.id); selectedEvent = null },
                onDismiss = { selectedEvent = null }
            )
        }

        selectedTradingInsight?.let { insight ->
            TradingInsightDetailDialog(insight) { selectedTradingInsight = null }
        }
    }
}

@Composable private fun GatewaySettingsHost(padding: PaddingValues) {
    val store = remember { SecureCredentialStore(AppContext.get()) }
    var key by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(store.getBaseUrl() ?: BuildConfig.MARKSY_API_BASE_URL) }
    var configured by remember { mutableStateOf(store.getIntegrationKey() != null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var revealKey by rememberSaveable { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            message = "Scan cancelled."
        } else {
            val parsed = GatewayQrParser.parse(contents)
            parsed.integrationKey?.let { key = it }
            parsed.baseUrl?.let { baseUrl = it }
            revealKey = true
            message = if (parsed.hasAny) "Scanned. Review the fields and Save." else "QR code not recognized."
        }
    }

    Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
        ScreenHeader("Marksy Gateway")
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (configured) "Gateway configured" else "Gateway not configured", color = if (configured) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, fontWeight = FontWeight.SemiBold)
            Text("Scan the QR code from the Marksy admin, or enter the values manually. The integration key is encrypted with Android Keystore and is never displayed after saving.", color = MarksyTheme.TextSecondary, fontSize = 13.sp)

            OutlinedButton(
                onClick = {
                    scanLauncher.launch(ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Scan the Marksy gateway QR")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    })
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MarksyTheme.PrimaryEmerald)
                Spacer(Modifier.width(8.dp))
                Text("Scan QR code", color = MarksyTheme.PrimaryEmerald)
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                singleLine = true,
                colors = gatewayFieldColors()
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Integration key") },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { revealKey = !revealKey }) { Text(if (revealKey) "Hide" else "Show", color = MarksyTheme.PrimaryEmerald) }
                },
                colors = gatewayFieldColors()
            )
            Button(
                onClick = {
                    val url = baseUrl.trim()
                    if (url.isNotBlank() && !url.startsWith("https://", ignoreCase = true)) {
                        message = "Base URL must start with https://"
                        return@Button
                    }
                    runCatching {
                        store.setBaseUrl(url)
                        store.setIntegrationKey(key.trim())
                    }.onSuccess {
                        key = ""
                        revealKey = false
                        configured = true
                        message = "Saved securely on this device."
                    }.onFailure {
                        message = "Could not save the credential. Try again."
                    }
                },
                enabled = key.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)
            ) { Text("Save Securely", color = Color.Black) }
            OutlinedButton(onClick = {
                store.clearIntegrationKey()
                store.clearBaseUrl()
                key = ""
                revealKey = false
                configured = false
                baseUrl = BuildConfig.MARKSY_API_BASE_URL
                message = "Credential removed. Trading delivery is disabled until configured."
            }) { Text("Remove Credential", color = MarksyTheme.RedUrgent) }
            message?.let { Text(it, color = MarksyTheme.TextSecondary, fontSize = 13.sp) }
        }
    }
}

// Visible input colors: without these the field text renders in the default
// on-surface color against the dark background and is effectively invisible.
@Composable private fun gatewayFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MarksyTheme.TextPrimary,
    unfocusedTextColor = MarksyTheme.TextPrimary,
    cursorColor = MarksyTheme.PrimaryEmerald,
    focusedBorderColor = MarksyTheme.PrimaryEmerald,
    unfocusedBorderColor = MarksyTheme.BorderGlow,
    focusedLabelColor = MarksyTheme.PrimaryEmerald,
    unfocusedLabelColor = MarksyTheme.TextSecondary,
    focusedContainerColor = MarksyTheme.Surface,
    unfocusedContainerColor = MarksyTheme.Surface
)

@Composable private fun TimelineHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { ScreenHeader("Timeline"); TimelineScreen(events, PaddingValues(), onEventSelected) } }
@Composable private fun CalendarHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { ScreenHeader("Calendar"); CalendarScreen(events = events, padding = PaddingValues(), onEventSelected = onEventSelected) } }
@Composable private fun InsightsHost(events: List<NotificationEventEntity>, padding: PaddingValues) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { ScreenHeader("Insights"); InsightsScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding())) } }
@Composable private fun RulesHost(padding: PaddingValues) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { ScreenHeader("Rules & Automation"); RulesScreen(PaddingValues(bottom = padding.calculateBottomPadding())) } }
@Composable private fun DigestHost(events: List<NotificationEventEntity>, padding: PaddingValues) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { ScreenHeader("Daily Digest"); DailyDigestScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding())) } }

@Composable private fun ScreenHeader(title: String) {
    // Same title style as the tab screens (Inbox, Trading); navigation is via the bottom bar and system back.
    Text(title, color = MarksyTheme.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
}

@Composable private fun MoreScreen(
    access: Boolean,
    whatsappAccess: Boolean,
    openAccess: () -> Unit,
    openWhatsAppAccess: () -> Unit,
    openGatewaySettings: () -> Unit,
    clearAll: suspend () -> Unit,
    openTimeline: () -> Unit,
    openCalendar: () -> Unit,
    openInsights: () -> Unit,
    openRules: () -> Unit,
    openDigest: () -> Unit,
    padding: PaddingValues
) {
    var showClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val store = remember { SecureCredentialStore(AppContext.get()) }
    var gatewayConfigured by remember { mutableStateOf(store.getIntegrationKey() != null) }

    LazyColumn(
        Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("More & Settings", color = MarksyTheme.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Rules, daily digest, insights and local data controls.", color = MarksyTheme.TextSecondary, fontSize = 12.sp)
        }
        item {
            // Profile Banner Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(MarksyTheme.BadgeTradingBg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MarksyTheme.PrimaryEmerald)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Marksy User", color = MarksyTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Premium Member", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.TextMuted)
                }
            }
        }
        item { SettingsCard("Daily Digest", "8 PM REPORT", "Get an AI-generated summary of your day's notifications.") { Button(onClick = openDigest, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Daily Digest", color = Color.Black) } } }
        item { SettingsCard("Rules & Automation", "LOCAL", "Create custom rules to filter, group and route notifications.") { Button(onClick = openRules, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Rules", color = Color.Black) } } }
        item { SettingsCard("Notification access", if (access) "ON" else "OFF", if (access) "Marksy OS can capture notifications." else "Enable notification access to start capturing.") { Button(onClick = openAccess, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text(if (access) "Manage Access" else "Open Access", color = Color.Black) } } }
        item { SettingsCard("WhatsApp connector", if (whatsappAccess) "ON" else "OPTIONAL", "Reads visible WhatsApp accessibility text for watchlist contacts.") { Button(onClick = openWhatsAppAccess, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text(if (whatsappAccess) "Manage Connector" else "Set Up Connector", color = Color.Black) } } }
        item { SettingsCard("Marksy Gateway", if (gatewayConfigured) "READY" else "NOT CONFIGURED", "Integration credentials stored securely.") { Button(onClick = openGatewaySettings, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Configure Gateway", color = Color.Black) } } }
        item { SettingsCard("Insights", "LOCAL", "Review notification patterns and attention levels.") { Button(onClick = openInsights, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Insights", color = Color.Black) } } }
        item { SettingsCard("Timeline", "LOCAL", "Review meaningful events chronologically.") { Button(onClick = openTimeline, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Timeline", color = Color.Black) } } }
        item { SettingsCard("Calendar", "LOCAL", "Browse retained notification history by day.") { Button(onClick = openCalendar, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Calendar", color = Color.Black) } } }
        item { SettingsCard("Local data", "7d / 30d", "Notifications expire after 7 days; trading events retained 30 days.") { Button(onClick = { showClear = true }, enabled = !clearing, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.RedUrgent)) { Text(if (clearing) "Clearing…" else "Clear All Data", color = Color.White) } } }
    }
    if (showClear) AlertDialog(onDismissRequest = { if (!clearing) showClear = false }, title = { Text("Clear local data?", color = MarksyTheme.TextPrimary) }, text = { Text("This removes captured notifications and trading intelligence stored on this device.", color = MarksyTheme.TextSecondary) }, confirmButton = { TextButton(enabled = !clearing, onClick = { clearing = true; scope.launch { try { clearAll(); gatewayConfigured = store.getIntegrationKey() != null } finally { clearing = false; showClear = false } } }) { Text("Clear", color = MarksyTheme.RedUrgent) } }, dismissButton = { TextButton(enabled = !clearing, onClick = { showClear = false }) { Text("Cancel", color = MarksyTheme.TextSecondary) } }, containerColor = MarksyTheme.SurfaceRaised)
}

@Composable private fun SettingsCard(title: String, status: String, description: String, action: (@Composable () -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = MarksyTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(status, color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(description, color = MarksyTheme.TextSecondary, fontSize = 12.sp)
            action?.let { Spacer(Modifier.height(10.dp)); it() }
        }
    }
}

@Composable internal fun EmptyState(title: String, message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = MarksyTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(message, color = MarksyTheme.TextSecondary, fontSize = 12.sp)
        }
    }
}
