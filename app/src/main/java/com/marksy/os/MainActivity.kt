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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.marksy.os.ui.HeaderIconBadge
import com.marksy.os.ui.collapsingHeader
import com.marksy.os.ui.rememberCollapsingHeaderState
import com.marksy.os.ui.MarksyRefreshBox
import com.marksy.os.ui.UpstoxScreen
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxFeed
import com.marksy.os.upstox.UpstoxLiveState
import com.marksy.os.upstox.UpstoxTokenStore
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import com.marksy.os.ui.rememberRefreshState
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
import com.marksy.os.ui.MarketTab
import com.marksy.os.ui.TradingFilters
import com.marksy.os.ui.CompactTextField
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
        LaunchedEffect(Unit) {
            repository.stripStoredMarkup()
            repository.reclassifyIfClassifierChanged(getSharedPreferences("marksy_classifier", MODE_PRIVATE))
            // Newly trading rows are PENDING; hand them to delivery now rather than at the next periodic run.
            TradingDeliveryScheduler.schedule(applicationContext)
        }
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
        val marketRefresh = rememberRefreshState()
        val market by produceState<MarketState>(MarketState.Loading, marketRefresh.key) {
            while (true) {
                value = MarketRepository.fetch().let { fresh -> if (fresh is MarketState.Unavailable && value is MarketState.Loaded) value else fresh }
                marketRefresh.done()
                delay(5 * 60 * 1000L)
            }
        }
        val todayDigest = remember(inboxEvents) { DailyDigestModel.build(inboxEvents) }
        val askConversation = remember { mutableStateListOf<AskExchange>() }

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var inboxFilterName by rememberSaveable { mutableStateOf(SmartInboxModel.Filter.ALL.name) }
        var tradingFilter by rememberSaveable { mutableStateOf(TradingFilters.first()) }
        var marketTabName by rememberSaveable { mutableStateOf(MarketTab.OVERVIEW.name) }
        var marketSymbol by rememberSaveable { mutableStateOf<String?>(null) }
        var stockQuery by rememberSaveable { mutableStateOf("") }
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
        var showUpstox by rememberSaveable { mutableStateOf(false) }
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
        val hostOpen = showTimeline || showCalendar || showInsights || showRules || showDigest || showGatewaySettings || showLearning || showMemory || showHealth || showValidation || showBriefing || showUpstox
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
                showUpstox -> showUpstox = false
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
        // The page title lives in the same bar as the Ask/profile icons -- one header row per
        // screen, like Home's -- instead of a second title row underneath.
        val screenTitle = when {
            showValidation -> "30-day validation"
            showHealth -> "Marksy Health"
            showMemory -> "What Marksy remembers"
            showLearning -> "What Marksy learned"
            showBriefing -> "Daily Briefing"
            showTimeline -> "Timeline"
            showCalendar -> "Calendar"
            showInsights -> "Insights"
            showRules -> "Rules & Automation"
            showDigest -> "Daily Digest"
            showUpstox -> "Upstox"
            showGatewaySettings -> "Marksy Account"
            selectedTab == 1 -> "Smart Inbox"
            selectedTab == 2 -> "Ask Marksy"
            selectedTab == 3 -> "Trading Intelligence"
            selectedTab == 4 -> "Market"
            selectedTab == tabs.size -> "More & Settings"
            else -> null
        }
        val onHome = selectedTab == 0 && !hostOpen

        // Live prices from the user's own Upstox token over one WebSocket, open only while the app
        // is in the foreground; screens just watch the keys they show.
        val upstoxStore = remember { UpstoxTokenStore(applicationContext) }
        var upstoxVersion by remember { mutableIntStateOf(0) }
        val upstoxConfigured = remember(upstoxVersion) { upstoxStore.hasToken() }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(upstoxConfigured, upstoxVersion) {
            if (!upstoxConfigured) { UpstoxFeed.clear(); return@LaunchedEffect }
            UpstoxFeed.watch(UpstoxIndices.HOME.map { it.first })
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { UpstoxFeed.run { upstoxStore.getToken() } }
        }
        val feedQuotes by UpstoxFeed.quotes.collectAsStateWithLifecycle()
        val feedStatus by UpstoxFeed.status.collectAsStateWithLifecycle()
        val feedTickAt by UpstoxFeed.lastTickAt.collectAsStateWithLifecycle()
        val feedNseOpen by UpstoxFeed.nseOpen.collectAsStateWithLifecycle()
        val marketOpen = feedNseOpen ?: UpstoxFeed.isMarketOpen()
        val upstoxLive: UpstoxLiveState = when {
            !upstoxConfigured -> UpstoxLiveState.NotConfigured
            feedStatus is UpstoxFeed.Status.TokenRejected -> UpstoxLiveState.Failed((feedStatus as UpstoxFeed.Status.TokenRejected).reason, tokenRejected = true)
            feedQuotes.isNotEmpty() -> UpstoxLiveState.Live(feedQuotes, feedTickAt, streaming = feedStatus is UpstoxFeed.Status.Live, marketOpen = marketOpen)
            feedStatus is UpstoxFeed.Status.Reconnecting -> UpstoxLiveState.Failed((feedStatus as UpstoxFeed.Status.Reconnecting).reason, tokenRejected = false)
            else -> UpstoxLiveState.Loading
        }

        val headerState = rememberCollapsingHeaderState()
        LaunchedEffect(screenTitle) { headerState.reset() }

        Scaffold(
            // Home's header is already the first item of its own list, so it scrolls natively.
            modifier = if (!onHome) Modifier.nestedScroll(headerState.connection) else Modifier,
            containerColor = MarksyTheme.Background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                // Home's own header already carries the same icons to the same destinations
                // (onOpenAsk/onOpenProfile below) -- a second bar here would just duplicate it.
                if (!onHome) {
                    fun closeSubScreens() {
                        showTimeline = false; showCalendar = false; showInsights = false
                        showRules = false; showDigest = false; showGatewaySettings = false
                        showLearning = false; showMemory = false; showHealth = false; showValidation = false; showBriefing = false; showUpstox = false
                    }
                    Column(Modifier.fillMaxWidth().background(MarksyTheme.Surface)) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .collapsingHeader(headerState)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Market pages carry a small LIVE mark on the title, only while NSE is in session.
                        val titleLive = (selectedTab == 3 || selectedTab == 4) && !hostOpen && feedStatus is UpstoxFeed.Status.Live && marketOpen && feedQuotes.isNotEmpty()
                        val stockSearch = selectedTab == 4 && !hostOpen && marketTabName == MarketTab.STOCKS.name
                        Row(if (stockSearch) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                            Text(
                                screenTitle.orEmpty(),
                                color = MarksyTheme.TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (selectedTab == 3 && !hostOpen) {
                                Spacer(Modifier.width(4.dp))
                                Text(tradingFilter, color = MarksyTheme.PrimaryEmerald, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            if (titleLive) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "LIVE",
                                    color = Color.Black,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(MarksyTheme.PrimaryEmerald, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (stockSearch) {
                            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                            fun openStock() { stockQuery.trim().takeIf { it.isNotEmpty() }?.let { marketSymbol = it; keyboard?.hide() } }
                            CompactTextField(
                                value = stockQuery,
                                onValueChange = { stockQuery = it.uppercase() },
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                placeholder = "Search symbol",
                                leadingIcon = Icons.Default.Search,
                                height = 36.dp,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { openStock() })
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            HeaderIconBadge(icon = Icons.Default.AutoAwesome, contentDescription = "Ask Marksy") { closeSubScreens(); selectedTab = 2 }
                            HeaderIconBadge(icon = Icons.Default.Person, contentDescription = "Profile & settings") { closeSubScreens(); selectedTab = tabs.size }
                        }
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
                                showLearning = false; showMemory = false; showHealth = false; showValidation = false; showBriefing = false; showUpstox = false
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
        ) { scaffoldPadding ->
            // Every page below the shared top bar starts the same distance from it.
            val padding = if (onHome) scaffoldPadding
            else PaddingValues(top = scaffoldPadding.calculateTopPadding() + 10.dp, bottom = scaffoldPadding.calculateBottomPadding())
            when {
                showValidation -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
                    ValidationScreen(validationRepository, padding)
                }
                showHealth -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
                    HealthScreen(padding) { healthRepository.report() }
                }
                showMemory -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
                    MemoryScreen(memoryRepository, padding)
                }
                showLearning -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) {
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
                showUpstox -> UpstoxScreen(upstoxStore, padding) { upstoxVersion++ }
                showGatewaySettings -> Column(Modifier.fillMaxSize().background(MarksyTheme.Background)) {
                    LoginScreen(
                        authRepository = remember { MarksyContainer.authRepository(applicationContext) },
                        padding = padding,
                        currentUserId = remember { com.marksy.os.gateway.AuthSessionStore(applicationContext).let { if (it.isSessionActive()) it.getUserId() else null } },
                        onSignedIn = { showGatewaySettings = false }
                    )
                }
                selectedTab == 0 -> MarksyRefreshBox(marketRefresh, Modifier.fillMaxSize().padding(padding)) { DashboardScreen(
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
                    liveIndices = upstoxLive,
                    todayDigest = todayDigest,
                    onOpenTrading = { selectedTab = 3 },
                    onOpenAsk = { selectedTab = 2 },
                    onOpenProfile = { selectedTab = 5 },
                    onArchive = archiveWithUndo,
                    onDelete = deleteNow,
                    onHide = { homeHidden = homeHidden + it.id },
                    modifier = Modifier.fillMaxSize()
                ) }
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
                selectedTab == 3 -> MarksyRefreshBox(marketRefresh, Modifier.padding(top = padding.calculateTopPadding())) {
                    TradingIntelligenceScreen(
                        tradingInsights, PaddingValues(bottom = padding.calculateBottomPadding()), market,
                        selectedFilter = tradingFilter,
                        onFilterSelected = { tradingFilter = it }
                    ) { selectedTradingInsight = it }
                }
                selectedTab == 4 -> MarketScreen(
                    repository = remember { MarksyContainer.marketIntelligence(applicationContext) },
                    padding = padding,
                    tabName = marketTabName,
                    onTabSelected = { marketTabName = it },
                    selectedSymbol = marketSymbol,
                    onSymbolSelected = { marketSymbol = it },
                    marketEvents = remember(inboxEvents) { inboxEvents.filter { it.category == "MARKET" } },
                    onEventSelected = openEvent
                )
                else -> MoreScreen(
                    access = notificationAccessEnabled,
                    whatsappAccess = whatsappConnectorEnabled,
                    openAccess = ::openNotificationAccess,
                    openWhatsAppAccess = ::openWhatsAppConnector,
                    openGatewaySettings = { showGatewaySettings = true },
                    openUpstox = { showUpstox = true },
                    upstoxConnected = upstoxLive is UpstoxLiveState.Live,
                    upstoxConfigured = upstoxLive !is UpstoxLiveState.NotConfigured,
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

@Composable private fun TimelineHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { TimelineScreen(events, PaddingValues(), onEventSelected) } }
@Composable private fun CalendarHost(events: List<NotificationEventEntity>, padding: PaddingValues, onEventSelected: (NotificationEventEntity) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { CalendarScreen(events = events, padding = PaddingValues(), onEventSelected = onEventSelected) } }
@Composable private fun InsightsHost(events: List<NotificationEventEntity>, padding: PaddingValues, market: MarketState, onCategorySelected: (String) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { InsightsScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding()), market = market, onCategorySelected = onCategorySelected) } }
@Composable private fun RulesHost(padding: PaddingValues, onOpenLearning: () -> Unit, onOpenMemory: () -> Unit) { val links = rememberCollapsingHeaderState(); Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding()).nestedScroll(links.connection)) { Row(Modifier.collapsingHeader(links).padding(horizontal = 10.dp, vertical = 6.dp)) { TextButton(onClick = onOpenLearning) { Text("What Marksy learned", fontSize = 12.sp) }; TextButton(onClick = onOpenMemory) { Text("What Marksy remembers", fontSize = 12.sp) } }; RulesScreen(PaddingValues(bottom = padding.calculateBottomPadding())) } }
@Composable private fun DigestHost(events: List<NotificationEventEntity>, padding: PaddingValues, onOpenInbox: (String) -> Unit, onOpenTrading: () -> Unit, onEventSelected: (NotificationEventEntity) -> Unit) { Column(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(top = padding.calculateTopPadding())) { DailyDigestScreen(events = events, padding = PaddingValues(bottom = padding.calculateBottomPadding()), onOpenInbox = onOpenInbox, onOpenTrading = onOpenTrading, onEventSelected = onEventSelected) } }

@Composable private fun MoreScreen(
    access: Boolean,
    whatsappAccess: Boolean,
    openAccess: () -> Unit,
    openWhatsAppAccess: () -> Unit,
    openGatewaySettings: () -> Unit,
    openUpstox: () -> Unit,
    upstoxConnected: Boolean,
    upstoxConfigured: Boolean,
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
    val signedInUserId = remember { com.marksy.os.gateway.AuthSessionStore(AppContext.get()).let { if (it.isSessionActive()) it.getUserId() else null } }
    LazyColumn(
        Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
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
                    .clickable(onClick = openGatewaySettings)
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
                        Text(signedInUserId ?: "Not signed in", color = MarksyTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(if (signedInUserId != null) "Signed in" else "Tap to sign in", color = MarksyTheme.PrimaryEmerald, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.TextMuted)
                }
            }
        }
        item { SettingsCard("Daily Digest", "TODAY", "Summary of today's notifications, built on this device.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openDigest, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Daily Digest", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Daily Briefing", "LOCAL", "Morning, evening and overnight briefings built only from your notifications.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openBriefing, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Briefing", color = Color.Black, fontSize = 12.sp) } } }
        item {
            SettingsCard(
                "Upstox market data",
                when { upstoxConnected -> "LIVE"; upstoxConfigured -> "SAVED"; else -> "OFF" },
                if (upstoxConfigured) "Live NIFTY / BANK NIFTY on Home from your own Upstox token." else "Add your Upstox Analytics Token to see live index prices on Home."
            ) {
                Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openUpstox, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) {
                    Text(if (upstoxConfigured) "Manage Upstox" else "Connect Upstox", color = Color.Black, fontSize = 12.sp)
                }
            }
        }
        item { SettingsCard("Marksy Health", "LIVE", "Capture, connectors, processing, storage and battery status.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openHealth, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Health", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("30-day validation", "LOCAL", "Automatically collected accuracy, reliability and resource metrics.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openValidation, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Validation", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Rules & Automation", "LOCAL", "Create custom rules to filter, group and route notifications.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openRules, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text("Open Rules", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("Notification access", if (access) "ON" else "OFF", if (access) "Marksy OS can capture notifications." else "Enable notification access to start capturing.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openAccess, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text(if (access) "Manage Access" else "Open Access", color = Color.Black, fontSize = 12.sp) } } }
        item { SettingsCard("WhatsApp connector", if (whatsappAccess) "ON" else "OPTIONAL", "Reads visible WhatsApp accessibility text for watchlist contacts.") { Button(modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), onClick = openWhatsAppAccess, colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)) { Text(if (whatsappAccess) "Manage Connector" else "Set Up Connector", color = Color.Black, fontSize = 12.sp) } } }
        item {
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
