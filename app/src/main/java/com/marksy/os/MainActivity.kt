package com.marksy.os

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
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
import com.marksy.os.gateway.SecureCredentialStore
import com.marksy.os.gateway.TradingDeliveryScheduler
import androidx.activity.compose.BackHandler
import com.marksy.os.intelligence.DashboardSnapshot
import com.marksy.os.intelligence.SmartInboxModel
import com.marksy.os.notification.NotificationListenerStatus
import com.marksy.os.notification.WhatsAppConnectorStatus
import com.marksy.os.notification.WhatsAppSettingsActivity
import com.marksy.os.ui.AskMarksyScreen
import com.marksy.os.ui.CalendarScreen
import com.marksy.os.ui.DailyDigestScreen
import com.marksy.os.ui.DashboardScreen
import com.marksy.os.intelligence.EventIntelligenceWorker
import com.marksy.os.intelligence.ContextGraph
import com.marksy.os.ui.EventDetailDialog
import com.marksy.os.ui.InboxActions
import com.marksy.os.ui.LearningScreen
import com.marksy.os.data.LearningSettings
import com.marksy.os.intelligence.PersonalLearning
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
        EventIntelligenceWorker.schedule(applicationContext)
        lifecycleScope.launch { runCatching { MarksyContainer.actions(applicationContext).recover() } }
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
        val learning = remember { MarksyContainer.learning(applicationContext) }
        val learningSettings = remember { LearningSettings(applicationContext) }
        val actionRepository = remember { MarksyContainer.actions(applicationContext) }
        val vm: MarksyViewModel = viewModel(factory = MarksyViewModelFactory(
            repository, learning, learningSettings, actionRepository,
            ContextGraph(MarksyContainer.database(applicationContext).contextGraphDao())
        ))
        val actionMessage by vm.actionMessage.collectAsStateWithLifecycle()
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val learningProfile by vm.learningProfile.collectAsStateWithLifecycle(initialValue = PersonalLearning.Profile.EMPTY)
        val learningEnabled by vm.learningEnabled.collectAsStateWithLifecycle()
        val events by vm.recentEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val snapshot by vm.dashboardSnapshot.collectAsStateWithLifecycle(initialValue = DashboardSnapshot.from(emptyList()))
        val timelineEvents by vm.timelineEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val historyEvents by vm.historyEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val inboxEvents by vm.inboxEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val tradingInsights by vm.tradingInsights.collectAsStateWithLifecycle(initialValue = emptyList())

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var inboxFilterName by rememberSaveable { mutableStateOf(SmartInboxModel.Filter.ALL.name) }
        var showTimeline by rememberSaveable { mutableStateOf(false) }
        var showCalendar by rememberSaveable { mutableStateOf(false) }
        var showInsights by rememberSaveable { mutableStateOf(false) }
        var showRules by rememberSaveable { mutableStateOf(false) }
        var showLearning by rememberSaveable { mutableStateOf(false) }
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
        val hostOpen = showTimeline || showCalendar || showInsights || showRules || showDigest || showGatewaySettings || showLearning
        BackHandler(enabled = hostOpen || selectedTab != 0) {
            when {
                showLearning -> showLearning = false
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
                if (!hostOpen) {
                    NavigationBar(
                        containerColor = MarksyTheme.Surface,
                        contentColor = MarksyTheme.TextSecondary
                    ) {
                        tabs.forEachIndexed { index, (label, icon) ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
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
            }
        ) { padding ->
            when {
                showLearning -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
                    ScreenHeader("What Marksy learned") { showLearning = false }
                    LearningScreen(
                        profile = learningProfile,
                        enabled = learningEnabled,
                        padding = padding,
                        onEnabledChanged = vm::setLearningEnabled,
                        onPreference = vm::setPreference,
                        onResetLearning = vm::resetLearning,
                        onClearCorrections = vm::clearCorrections
                    )
                }
                showTimeline -> TimelineHost(timelineEvents, padding, openEvent) { showTimeline = false }
                showCalendar -> CalendarHost(historyEvents, padding, openEvent) { showCalendar = false }
                showInsights -> InsightsHost(historyEvents, padding) { showInsights = false }
                showRules -> RulesHost(padding, onOpenLearning = { showLearning = true }) { showRules = false }
                showDigest -> DigestHost(events, padding) { showDigest = false }
                showGatewaySettings -> GatewaySettingsHost(padding) { showGatewaySettings = false }
                selectedTab == 0 -> DashboardScreen(
                    snapshot = snapshot,
                    events = events,
                    onEventSelected = openEvent,
                    onCategorySelected = openCategory,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                selectedTab == 1 -> SmartInboxScreen(
                    events = inboxEvents,
                    padding = padding,
                    onEventSelected = openEvent,
                    selectedFilterName = inboxFilterName,
                    onFilterSelected = { inboxFilterName = it },
                    learningProfile = learningProfile,
                    actions = remember(vm) {
                        InboxActions(
                            markSeen = vm::markThreadSeen,
                            resolve = vm::resolveThread,
                            reopen = vm::reopenThread,
                            snooze = vm::snoozeThread,
                            archive = vm::archiveThread,
                            prefer = vm::setPreference
                        )
                    }
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
            LaunchedEffect(event.id) { vm.markSeen(event.id); vm.clearActionMessage() }
            // Re-discovered after each action so the list reflects the new state (e.g. resolved).
            val available = remember(event.id, actionMessage) { vm.availableActions(event) }
            var relatedVersion by remember(event.id) { mutableIntStateOf(0) }
            val related by produceState(emptyList<com.marksy.os.data.local.ContextEntity>(), event.id, relatedVersion) { value = vm.relatedEntities(event.id) }
            EventDetailDialog(
                event = event,
                actions = available,
                related = related,
                onUnlinkEntity = { entityId -> vm.unlinkEntity(entityId, event.id); relatedVersion++ },
                actionMessage = actionMessage,
                onAction = { type, at, detail -> vm.runAction(event.id, type, at, detail) },
                onRequestNotificationPermission = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                },
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

@Composable private fun GatewaySettingsHost(padding: PaddingValues, onBack: () -> Unit) {
    val store = remember { SecureCredentialStore(AppContext.get()) }
    var key by rememberSaveable { mutableStateOf("") }
    var configured by remember { mutableStateOf(store.getIntegrationKey() != null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var revealKey by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
        ScreenHeader("Marksy Gateway", onBack)
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (configured) "Gateway configured" else "Gateway not configured", color = if (configured) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary, fontWeight = FontWeight.SemiBold)
            Text("The integration key is encrypted with Android Keystore and is never displayed after saving.", color = MarksyTheme.TextSecondary, fontSize = 13.sp)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Integration key") },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { revealKey = !revealKey }) { Text(if (revealKey) "Hide" else "Show", color = MarksyTheme.PrimaryEmerald) }
                }
            )
            Button(
                onClick = {
                    runCatching { store.setIntegrationKey(key.trim()) }.onSuccess {
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
                key = ""
                revealKey = false
                configured = false
                message = "Credential removed. Trading delivery is disabled until configured."
            }) { Text("Remove Credential", color = MarksyTheme.RedUrgent) }
            message?.let { Text(it, color = MarksyTheme.TextSecondary, fontSize = 13.sp) }
        }
    }
}

@Composable private fun TimelineHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit, onBack: () -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { ScreenHeader("Timeline", onBack); TimelineScreen(events, PaddingValues(), onEventSelected) } }
@Composable private fun CalendarHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit, onBack: () -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { ScreenHeader("Calendar", onBack); CalendarScreen(events = events, padding = PaddingValues(), onEventSelected = onEventSelected) } }
@Composable private fun InsightsHost(events: List<NotificationEventEntity>, padding: PaddingValues, onBack: () -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { ScreenHeader("Insights", onBack); InsightsScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding())) } }
@Composable private fun RulesHost(padding: PaddingValues, onOpenLearning: () -> Unit, onBack: () -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { ScreenHeader("Rules & Automation", onBack); TextButton(onClick = onOpenLearning, modifier = Modifier.padding(horizontal = 10.dp)) { Text("What Marksy learned") }; RulesScreen(PaddingValues(bottom = padding.calculateBottomPadding())) } }
@Composable private fun DigestHost(events: List<NotificationEventEntity>, padding: PaddingValues, onBack: () -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { ScreenHeader("Daily Digest", onBack); DailyDigestScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding())) } }

@Composable private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = MarksyTheme.TextPrimary) }
        Text(title, color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
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
            Text("More & Settings", color = MarksyTheme.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
