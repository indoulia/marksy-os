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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.RetentionScheduler
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.MarketRepository
import com.marksy.os.gateway.MarketState
import com.marksy.os.gateway.TradingDeliveryScheduler
import androidx.activity.compose.BackHandler
import com.marksy.os.intelligence.DashboardSnapshot
import com.marksy.os.intelligence.NotificationTrend
import com.marksy.os.ui.SecondZonePickerDialog
import com.marksy.os.ui.WorldClock
import com.marksy.os.ui.WorldClockSettings
import com.marksy.os.weather.Weather
import com.marksy.os.weather.WeatherRepository
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import com.marksy.os.intelligence.SmartInboxModel
import com.marksy.os.notification.MarksyNotificationListenerService
import com.marksy.os.notification.ReminderScheduler
import com.marksy.os.notification.NotificationListenerStatus
import com.marksy.os.notification.WhatsAppConnectorStatus
import com.marksy.os.notification.WhatsAppSettingsActivity
import com.marksy.os.ui.AskExchange
import com.marksy.os.ui.AskMarksyScreen
import com.marksy.os.ui.CalendarScreen
import com.marksy.os.ui.DailyDigestModel
import com.marksy.os.ui.DailyDigestScreen
import com.marksy.os.ui.DashboardScreen
import com.marksy.os.intelligence.EventIntelligenceWorker
import com.marksy.os.intelligence.ContextGraph
import com.marksy.os.ui.EventDetailDialog
import com.marksy.os.ui.InboxActions
import com.marksy.os.ui.BriefingScreen
import com.marksy.os.ui.LearningScreen
import com.marksy.os.ui.MemoryScreen
import com.marksy.os.ui.HealthScreen
import com.marksy.os.ui.ValidationScreen
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
import com.marksy.os.ui.MarketScreen
import com.marksy.os.ui.LoginScreen

class MainActivity : ComponentActivity() {
    private var notificationAccessEnabled by mutableStateOf(false)
    private var whatsappConnectorEnabled by mutableStateOf(false)
    /** Set when a reminder notification is tapped; the event dialog opens for it. */
    private var pendingEventId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingEventId = intent?.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L)?.takeIf { it >= 0 }
        RetentionScheduler.schedule(applicationContext)
        TradingDeliveryScheduler.schedule(applicationContext)
        EventIntelligenceWorker.schedule(applicationContext)
        lifecycleScope.launch { runCatching { MarksyContainer.actions(applicationContext).recover() } }
        notificationAccessEnabled = NotificationListenerStatus.isEnabled(this)
        whatsappConnectorEnabled = WhatsAppConnectorStatus.isAccessibilityServiceEnabled(this)
        setContent { MarksyApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L).takeIf { it >= 0 }?.let { pendingEventId = it }
    }

    override fun onResume() {
        super.onResume()
        notificationAccessEnabled = NotificationListenerStatus.isEnabled(this)
        // Reinstalls/updates can leave access granted but the listener unbound.
        if (notificationAccessEnabled) MarksyNotificationListenerService.requestRebind(this)
        whatsappConnectorEnabled = WhatsAppConnectorStatus.isAccessibilityServiceEnabled(this)
    }

    private fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    private fun openWhatsAppConnector() = startActivity(Intent(this, WhatsAppSettingsActivity::class.java))

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MarksyApp() {
        val repository = remember { MarksyContainer.repository(applicationContext) }
        val learning = remember { MarksyContainer.learning(applicationContext) }
        val learningSettings = remember { LearningSettings(applicationContext) }
        val actionRepository = remember { MarksyContainer.actions(applicationContext) }
        val askRepository = remember { MarksyContainer.ask(applicationContext) }
        val briefingRepository = remember { MarksyContainer.briefing(applicationContext) }
        val vm: MarksyViewModel = viewModel(factory = MarksyViewModelFactory(
            repository, learning, learningSettings, actionRepository,
            ContextGraph(MarksyContainer.database(applicationContext).contextGraphDao())
        ))
        LaunchedEffect(Unit) { repository.stripStoredMarkup() }
        val actionMessage by vm.actionMessage.collectAsStateWithLifecycle()
        val learningProfile by vm.learningProfile.collectAsStateWithLifecycle(initialValue = PersonalLearning.Profile.EMPTY)
        val learningEnabled by vm.learningEnabled.collectAsStateWithLifecycle()
        val events by vm.recentEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val inboxEvents by vm.activeEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val snapshot by vm.dashboardSnapshot.collectAsStateWithLifecycle(initialValue = DashboardSnapshot.from(emptyList()))
        val timelineEvents by vm.timelineEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val historyEvents by vm.historyEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val trend by vm.notificationTrend.collectAsStateWithLifecycle(initialValue = NotificationTrend(0, 0, 0, List(7) { 0 }, 0))
        val categoryStats by vm.homeCategoryStats.collectAsStateWithLifecycle(initialValue = emptyMap())
        val tradingInsights by vm.tradingInsights.collectAsStateWithLifecycle(initialValue = emptyList())

        var locationGranted by remember { mutableStateOf(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
        val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locationGranted = it }
        val weather by produceState<Weather?>(null, locationGranted) {
            while (locationGranted) {
                value = WeatherRepository.current(applicationContext) ?: value
                delay(30 * 60 * 1000L)
            }
        }

        // Marksy snapshot refreshes while the app is open; screens show honest states when it is missing.
        val market by produceState<MarketState>(MarketState.Loading) {
            while (true) {
                value = MarketRepository.fetch().let { fresh -> if (fresh is MarketState.Unavailable && value is MarketState.Loaded) value else fresh }
                delay(5 * 60 * 1000L)
            }
        }
        val todayDigest = remember(inboxEvents) { DailyDigestModel.build(inboxEvents) }
        val askConversation = remember { mutableStateListOf<AskExchange>() }

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var inboxFilterName by rememberSaveable { mutableStateOf(SmartInboxModel.Filter.ALL.name) }
        var showTimeline by rememberSaveable { mutableStateOf(false) }
        var showCalendar by rememberSaveable { mutableStateOf(false) }
        var showInsights by rememberSaveable { mutableStateOf(false) }
        var showRules by rememberSaveable { mutableStateOf(false) }
        var showLearning by rememberSaveable { mutableStateOf(false) }
        var showMemory by rememberSaveable { mutableStateOf(false) }
        var showHealth by rememberSaveable { mutableStateOf(false) }
        var showValidation by rememberSaveable { mutableStateOf(false) }
        val validationRepository = remember { com.marksy.os.data.ValidationRepository(applicationContext) }
        val healthRepository = remember { com.marksy.os.data.HealthRepository(applicationContext) }
        val memoryRepository = remember { MarksyContainer.memory(applicationContext) }
        var showDigest by rememberSaveable { mutableStateOf(false) }
        var showBriefing by rememberSaveable { mutableStateOf(false) }
        var showGatewaySettings by rememberSaveable { mutableStateOf(false) }
        var selectedEvent by remember { mutableStateOf<NotificationEventEntity?>(null) }
        var selectedTradingInsight by remember { mutableStateOf<TradingInsight?>(null) }

        val openEvent: (NotificationEventEntity) -> Unit = { event ->
            selectedEvent = event
            if (!event.isRead) vm.setRead(event.id, true)
        }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(pendingEventId, inboxEvents) {
            val id = pendingEventId ?: return@LaunchedEffect
            inboxEvents.firstOrNull { it.id == id }?.let { openEvent(it); pendingEventId = null }
        }
        val archiveWithUndo: (NotificationEventEntity) -> Unit = { event ->
            vm.archive(event.id)
            scope.launch {
                if (snackbar.showSnackbar("Archived", actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.unarchive(event.id)
            }
        }
        // Delete is permanent and immediate by request; Archive keeps its undo.
        val deleteNow: (NotificationEventEntity) -> Unit = { event ->
            vm.delete(event.id)
            ReminderScheduler.cancel(applicationContext, event.id)
            if (selectedEvent?.id == event.id) selectedEvent = null
        }
        // Inbox swipes act on a whole thread; one undo restores every row of it.
        val archiveThreadWithUndo: (List<NotificationEventEntity>) -> Unit = { rows ->
            vm.archiveThread(rows.map { it.id })
            scope.launch {
                if (snackbar.showSnackbar("Archived", actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) rows.forEach { vm.unarchive(it.id) }
            }
        }
        // "Hide" drops a row from one page only; nothing changes in storage.
        var inboxHidden by rememberSaveable { mutableStateOf(listOf<Long>()) }
        var homeHidden by rememberSaveable { mutableStateOf(listOf<Long>()) }
        val homeEvents = remember(events, homeHidden) { events.filterNot { it.id in homeHidden } }
        val homeSnapshot = remember(homeEvents) { DashboardSnapshot.from(homeEvents) }
        val openCategory: (String) -> Unit = { label ->
            inboxFilterName = SmartInboxModel.Filter.forCategoryLabel(label).name
            selectedTab = 1
        }

        // System back / swipe: close an open sub-screen, else return to Home, else exit.
        val hostOpen = showTimeline || showCalendar || showInsights || showRules || showDigest || showGatewaySettings || showLearning || showMemory || showHealth || showValidation || showBriefing
        BackHandler(enabled = hostOpen || selectedTab != 0) {
            when {
                showBriefing -> showBriefing = false
                showValidation -> showValidation = false
                showHealth -> showHealth = false
                showMemory -> showMemory = false
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
            "Market" to Icons.Default.QueryStats
        )
        val moreOpen = selectedTab == tabs.size

        Scaffold(
            containerColor = MarksyTheme.Background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                // Home's own header already carries a profile icon to the same destination
                // (onOpenProfile below) -- a second bar here would just duplicate it.
                if (selectedTab != 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MarksyTheme.Surface)
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = {
                            showTimeline = false; showCalendar = false; showInsights = false
                            showRules = false; showDigest = false; showGatewaySettings = false
                            showLearning = false; showMemory = false; showHealth = false; showValidation = false; showBriefing = false
                            selectedTab = tabs.size
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = "Profile & settings",
                                tint = if (moreOpen) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary
                            )
                        }
                    }
                }
            },
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
                                showLearning = false; showMemory = false; showHealth = false; showValidation = false; showBriefing = false
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
                showValidation -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
                    ScreenHeader("30-day validation")
                    ValidationScreen(validationRepository, padding)
                }
                showHealth -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
                    ScreenHeader("Marksy Health")
                    HealthScreen(padding) { healthRepository.report() }
                }
                showMemory -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
                    ScreenHeader("What Marksy remembers")
                    MemoryScreen(memoryRepository, padding)
                }
                showLearning -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
                    ScreenHeader("What Marksy learned")
                    LearningScreen(
                        profile = learningProfile,
                        enabled = learningEnabled,
                        padding = padding,
                        onEnabledChanged = vm::setLearningEnabled,
                        onPreference = vm::setPreference,
                        onResetLearning = vm::resetLearning,
                        onClearCorrections = vm::clearCorrections,
                        aiStatus = remember { MarksyContainer.intelligence(applicationContext).status() }
                    )
                }
                showBriefing -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
                    ScreenHeader("Daily Briefing")
                    BriefingScreen(
                        padding = padding,
                        load = { kind -> briefingRepository.briefing(kind) },
                        onOpenEvent = { id -> lifecycleScope.launch { repository.event(id)?.let(openEvent) } }
                    )
                }
                showTimeline -> TimelineHost(timelineEvents, padding, openEvent)
                showCalendar -> CalendarHost(historyEvents, padding, openEvent)
                showInsights -> InsightsHost(inboxEvents, padding, market) { showInsights = false; openCategory(it) }
                showRules -> RulesHost(padding, onOpenLearning = { showLearning = true }, onOpenMemory = { showMemory = true })
                showDigest -> DigestHost(
                    inboxEvents, padding,
                    onOpenInbox = { showDigest = false; inboxFilterName = it; selectedTab = 1 },
                    onOpenTrading = { showDigest = false; selectedTab = 3 },
                    onEventSelected = openEvent
                )
                showGatewaySettings -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background)) {
                    LoginScreen(
                        authRepository = remember { MarksyContainer.authRepository(applicationContext) },
                        padding = padding,
                        currentUserId = remember { com.marksy.os.gateway.AuthSessionStore(applicationContext).let { if (it.isSessionActive()) it.getUserId() else null } },
                        onSignedIn = { showGatewaySettings = false }
                    )
                }
                selectedTab == 0 -> DashboardScreen(
                    snapshot = homeSnapshot,
                    events = homeEvents,
                    onEventSelected = openEvent,
                    onCategorySelected = openCategory,
                    onOpenTimeline = { showTimeline = true },
                    onOpenCalendar = { showCalendar = true },
                    onOpenInsights = { showInsights = true },
                    trend = trend,
                    categoryStats = categoryStats,
                    weather = weather,
                    weatherAvailable = locationGranted,
                    onRequestWeather = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    market = market,
                    todayDigest = todayDigest,
                    onOpenTrading = { selectedTab = 3 },
                    onOpenProfile = { selectedTab = 5 },
                    onArchive = archiveWithUndo,
                    onDelete = deleteNow,
                    onHide = { homeHidden = homeHidden + it.id },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                selectedTab == 1 -> SmartInboxScreen(
                    events = inboxEvents.filterNot { it.id in inboxHidden },
                    padding = padding,
                    onEventSelected = openEvent,
                    selectedFilterName = inboxFilterName,
                    onFilterSelected = { inboxFilterName = it },
                    onArchive = archiveThreadWithUndo,
                    onDelete = { rows -> rows.forEach(deleteNow) },
                    onHide = { rows -> inboxHidden = inboxHidden + rows.map { it.id } },
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
                selectedTab == 2 -> AskMarksyScreen(
                    padding, inboxEvents, market, openEvent, askConversation,
                    askGrounded = { q, prev -> askRepository.ask(q, prev) },
                    askRouted = { q, prev, intents -> askRepository.askIf(q, prev, intents) },
                    loadEvent = { id -> repository.event(id) }
                )
                selectedTab == 3 -> TradingIntelligenceScreen(tradingInsights, padding, market) { selectedTradingInsight = it }
                selectedTab == 4 -> MarketScreen(repository = remember { MarksyContainer.marketIntelligence(applicationContext) }, padding = padding)
                else -> MoreScreen(
                    access = notificationAccessEnabled,
                    whatsappAccess = whatsappConnectorEnabled,
                    openAccess = ::openNotificationAccess,
                    openWhatsAppAccess = ::openWhatsAppConnector,
                    openGatewaySettings = { showGatewaySettings = true },
                    openHealth = { showHealth = true },
                    openValidation = { showValidation = true },
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
                    openBriefing = { showBriefing = true },
                    padding = padding
                )
            }
        }

        selectedEvent?.let { opened ->
            // Follow the live row so Keep/Remind changes show immediately.
            val event = inboxEvents.firstOrNull { it.id == opened.id } ?: opened
            LaunchedEffect(event.id) { vm.clearActionMessage() }
            // Re-discovered after each action so the list reflects the new state (e.g. resolved).
            val available = remember(event, actionMessage) { vm.availableActions(event) }
            var relatedVersion by remember(event.id) { mutableIntStateOf(0) }
            val related by produceState(emptyList<com.marksy.os.data.local.ContextEntity>(), event.id, relatedVersion) { value = vm.relatedEntities(event.id) }
            EventDetailDialog(
                event = event,
                engineActions = available,
                related = related,
                onUnlinkEntity = { entityId -> vm.unlinkEntity(entityId, event.id); relatedVersion++ },
                actionMessage = actionMessage,
                onAction = { type, at, detail -> vm.runAction(event.id, type, at, detail) },
                onArchive = { vm.archive(event.id); selectedEvent = null },
                onUnarchive = { vm.unarchive(event.id); selectedEvent = null },
                onDismiss = { selectedEvent = null },
                onToggleKeep = { vm.setKept(event.id, !event.kept) },
                onSetReminder = { at ->
                    vm.setReminder(event.id, at)
                    if (at == null) {
                        ReminderScheduler.cancel(applicationContext, event.id)
                    } else {
                        ReminderScheduler.schedule(applicationContext, event.id, at)
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onMarkUnread = { vm.setRead(event.id, false) }
            )
        }

        selectedTradingInsight?.let { insight ->
            TradingInsightDetailDialog(insight) { selectedTradingInsight = null }
        }
    }
}

@Composable private fun TimelineHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { ScreenHeader("Timeline"); TimelineScreen(events, PaddingValues(), onEventSelected) } }
@Composable private fun CalendarHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { ScreenHeader("Calendar"); CalendarScreen(events = events, padding = PaddingValues(), onEventSelected = onEventSelected) } }
@Composable private fun InsightsHost(events: List<NotificationEventEntity>, padding: PaddingValues, market: MarketState, onCategorySelected: (String) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { ScreenHeader("Insights"); InsightsScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding()), market = market, onCategorySelected = onCategorySelected) } }
@Composable private fun RulesHost(padding: PaddingValues, onOpenLearning: () -> Unit, onOpenMemory: () -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { ScreenHeader("Rules & Automation"); Row(Modifier.padding(horizontal = 10.dp)) { TextButton(onClick = onOpenLearning) { Text("What Marksy learned", fontSize = 12.sp) }; TextButton(onClick = onOpenMemory) { Text("What Marksy remembers", fontSize = 12.sp) } }; RulesScreen(PaddingValues(bottom = padding.calculateBottomPadding())) } }
@Composable private fun DigestHost(events: List<NotificationEventEntity>, padding: PaddingValues, onOpenInbox: (String) -> Unit, onOpenTrading: () -> Unit, onEventSelected: (NotificationEventEntity) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { ScreenHeader("Daily Digest"); DailyDigestScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding()), onOpenInbox = onOpenInbox, onOpenTrading = onOpenTrading, onEventSelected = onEventSelected) } }

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
    openHealth: () -> Unit,
    openValidation: () -> Unit,
    clearAll: suspend () -> Unit,
    openTimeline: () -> Unit,
    openCalendar: () -> Unit,
    openInsights: () -> Unit,
    openRules: () -> Unit,
    openDigest: () -> Unit,
    openBriefing: () -> Unit,
    padding: PaddingValues
) {
    var showClear by remember { mutableStateOf(false) }
    var showZonePicker by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
        item { SettingsCard("Daily Digest", "TODAY", "Summary of today's notifications, built on this device.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openDigest, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Daily Digest", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Daily Briefing", "LOCAL", "Morning, evening and overnight briefings built only from your notifications.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openBriefing, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Briefing", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Marksy Health", "LIVE", "Capture, connectors, processing, storage and battery status.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openHealth, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Health", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("30-day validation", "LOCAL", "Automatically collected accuracy, reliability and resource metrics.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openValidation, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Validation", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Rules & Automation", "LOCAL", "Create custom rules to filter, group and route notifications.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openRules, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Rules", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Notification access", if (access) "ON" else "OFF", if (access) "Marksy OS can capture notifications." else "Enable notification access to start capturing.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openAccess, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text(if (access) "Manage Access" else "Open Access", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("WhatsApp connector", if (whatsappAccess) "ON" else "OPTIONAL", "Reads visible WhatsApp accessibility text for watchlist contacts.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openWhatsAppAccess, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text(if (whatsappAccess) "Manage Connector" else "Set Up Connector", color = Color.Black, fontSize = 12.sp) } } }
        item {
            val signedInUserId = remember { com.marksy.os.gateway.AuthSessionStore(AppContext.get()).let { if (it.isSessionActive()) it.getUserId() else null } }
            SettingsCard(
                "Marksy Account",
                if (signedInUserId != null) "SIGNED IN" else "NOT SIGNED IN",
                if (signedInUserId != null) "Signed in as $signedInUserId." else "Sign in to enable Trading delivery and Market Intelligence."
            ) {
                Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openGatewaySettings, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) {
                    Text(if (signedInUserId != null) "Manage Account" else "Sign In", color = Color.Black, fontSize = 12.sp)
                }
            }
        }
        item {
            val zone by WorldClockSettings.secondZone
            SettingsCard("World clock", WorldClock.abbreviation(java.time.ZonedDateTime.now(zone)), "Home shows IST and ${WorldClock.cityName(zone)}. Tap the clocks to convert meeting times.") {
                Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = { showZonePicker = true }, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Change Second Clock", color = Color.Black, fontSize = 12.sp) }
            }
        }
        item { SettingsCard("Insights", "LOCAL", "Review notification patterns and attention levels.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openInsights, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Insights", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Timeline", "LOCAL", "Review meaningful events chronologically.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openTimeline, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Timeline", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Calendar", "LOCAL", "Browse retained notification history by day.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openCalendar, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Calendar", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Local data", "7d / 30d", "Notifications expire after 7 days; trading events retained 30 days.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = { showClear = true }, enabled = !clearing, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.RedUrgent)) { Text(if (clearing) "Clearing…" else "Clear All Data", color = Color.White, fontSize = 12.sp) } } }
    }
    if (showZonePicker) SecondZonePickerDialog { showZonePicker = false }
    if (showClear) AlertDialog(onDismissRequest = { if (!clearing) showClear = false }, title = { Text("Clear local data?", color = MarksyTheme.TextPrimary) }, text = { Text("This removes captured notifications and trading intelligence stored on this device.", color = MarksyTheme.TextSecondary) }, confirmButton = { TextButton(enabled = !clearing, onClick = { clearing = true; scope.launch { try { clearAll() } finally { clearing = false; showClear = false } } }) { Text("Clear", color = MarksyTheme.RedUrgent) } }, dismissButton = { TextButton(enabled = !clearing, onClick = { showClear = false }) { Text("Cancel", color = MarksyTheme.TextSecondary) } }, containerColor = MarksyTheme.SurfaceRaised)
}

@Composable private fun SettingsCard(title: String, status: String, description: String, action: (@Composable () -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = MarksyTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(status, color = MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(description, color = MarksyTheme.TextSecondary, fontSize = 12.sp)
            action?.let { Spacer(Modifier.height(8.dp)); it() }
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
