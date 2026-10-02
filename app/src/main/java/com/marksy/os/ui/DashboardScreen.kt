package com.marksy.os.ui

import com.marksy.os.gateway.MarketState

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.DashboardSnapshot
import com.marksy.os.intelligence.EventIntelligence
import com.marksy.os.intelligence.HomeCategoryStats
import com.marksy.os.intelligence.HomePeriod
import com.marksy.os.intelligence.NotificationTrend
import com.marksy.os.intelligence.SmartInboxModel
import com.marksy.os.weather.Weather
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxLiveState

// Latest Activity: the newest groups from a recent window of captures.
private const val LATEST_POOL = 40
private const val LATEST_GROUPS = 5

@Composable
fun DashboardScreen(
    snapshot: DashboardSnapshot,
    events: List<NotificationEventEntity>,
    onEventSelected: (NotificationEventEntity) -> Unit,
    onCategorySelected: (String) -> Unit = {},
    onOpenTimeline: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    trend: NotificationTrend = NotificationTrend(0, 0, 0, List(7) { 0 }, 0),
    categoryStats: Map<HomePeriod, HomeCategoryStats> = emptyMap(),
    weather: Weather? = null,
    weatherAvailable: Boolean = false,
    onRequestWeather: () -> Unit = {},
    market: MarketState = MarketState.Loading,
    liveIndices: UpstoxLiveState = UpstoxLiveState.NotConfigured,
    todayDigest: DailyDigest? = null,
    onOpenTrading: () -> Unit = {},
    onOpenAsk: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    // Swipes act on a whole thread or, from a group header, on every row in the group; nothing deletes.
    onArchive: (List<NotificationEventEntity>) -> Unit = {},
    onHide: (List<NotificationEventEntity>) -> Unit = {},
    onMarkRead: (List<Long>) -> Unit = {},
    onMarkUnread: (List<Long>) -> Unit = {},
    planItems: List<com.marksy.os.data.local.PlanItemEntity> = emptyList(),
    onOpenPlan: () -> Unit = {},
    onAddReminder: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTimeFilter by remember { mutableStateOf("Today") }
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun toggle(key: String) { expanded = if (key in expanded) expanded - key else expanded + key }
    val scores = remember(snapshot) { snapshot.topAttention.associateBy { it.eventId } }
    val attention = remember(snapshot, events) {
        SmartInboxModel.sourceStacks(events.filter { it.id in scores }, snapshot.generatedAt)
            .sortedByDescending { s -> s.allRows.maxOf { scores[it.id]?.attentionScore ?: 0 } }
    }
    val latest = remember(events) { SmartInboxModel.sourceStacks(events.take(LATEST_POOL), snapshot.generatedAt, byRecency = true).take(LATEST_GROUPS) }
    val swipe = ThreadSwipe(
        archive = { onArchive(it.events + it.duplicates) },
        hide = { onHide(it.events + it.duplicates) }
    )
    val group = GroupSwipe(
        markRead = { s -> onMarkRead(s.allRows.map { it.id }) },
        markUnread = { s -> onMarkUnread(s.allRows.map { it.id }) },
        archive = { s -> onArchive(s.allRows) }
    )
    // Why it's here, in words ("High · Bill due"); Normal/Low rows without a reason say nothing.
    fun attentionNote(thread: SmartInboxModel.InboxThread): RowNote? {
        val r = thread.events.mapNotNull { scores[it.id] }.maxByOrNull { it.attentionScore } ?: return null
        val reason = EventText.usefulReasons(r.reasons).firstOrNull()
        val (level, tint) = when (r.attentionLevel) {
            EventIntelligence.AttentionLevel.CRITICAL -> "Critical" to MarksyTheme.RedUrgent
            EventIntelligence.AttentionLevel.HIGH -> "High" to MarksyTheme.YellowImportant
            EventIntelligence.AttentionLevel.NORMAL -> "Normal" to MarksyTheme.TextMuted
            EventIntelligence.AttentionLevel.LOW -> "Low" to MarksyTheme.TextMuted
        }
        if (reason == null && tint == MarksyTheme.TextMuted) return null
        return RowNote(listOfNotNull(level, reason).joinToString(" · "), tint)
    }

    LazyColumn(
        modifier = modifier.background(MarksyTheme.Background),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                // Header Branding
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))) {
                            // The launcher foreground pads its mark for the adaptive mask; scaling crops to the mark.
                            androidx.compose.foundation.Image(
                                androidx.compose.ui.res.painterResource(com.marksy.os.R.mipmap.ic_launcher_foreground), contentDescription = "Marksy",
                                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.6f, scaleY = 1.6f)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "MARKSY",
                            color = MarksyTheme.TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "OS",
                            color = MarksyTheme.PrimaryEmerald,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MarksyTheme.BadgeTradingBg)
                                .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(12.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "V2",
                                color = MarksyTheme.PrimaryEmerald,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        HeaderIconBadge(icon = Icons.Default.AutoAwesome, contentDescription = "Ask Marksy", onClick = onOpenAsk)
                        HeaderIconBadge(icon = Icons.Default.EventNote, contentDescription = "Plan", onClick = onOpenPlan)
                        HeaderIconBadge(icon = Icons.Default.Person, contentDescription = "Profile", onClick = onOpenProfile)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Time Filter Pills
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Today", "This Week", "All Time").forEach { filter ->
                        val isSelected = filter == selectedTimeFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.Surface)
                                .border(
                                    1.dp,
                                    if (isSelected) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow,
                                    RoundedCornerShape(20.dp)
                                )
                                .clickable { selectedTimeFilter = filter }
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        ) {
                            Text(
                                filter,
                                color = if (isSelected) Color.Black else MarksyTheme.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    WeatherBadge(weather, weatherAvailable, onRequestWeather)
                }

                Spacer(Modifier.height(12.dp))

                // Notification Counter Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        "${when (selectedTimeFilter) { "Today" -> trend.today; "This Week" -> trend.lastSevenDays.sum(); else -> trend.total }}",
                                        color = MarksyTheme.TextPrimary,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Notifications",
                                        color = MarksyTheme.TextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(bottom = 3.dp)
                                    )
                                }
                                val change = trend.changeVsYesterdayPercent
                                val today = selectedTimeFilter == "Today"
                                Text(
                                    when {
                                        selectedTimeFilter == "This Week" -> "Last 7 days"
                                        !today -> "All retained history"
                                        // Just after midnight the same-time window is empty; show yesterday's full count instead.
                                        change == null && trend.yesterdayTotal > 0 -> "${trend.yesterdayTotal} yesterday"
                                        change == null -> "No data from yesterday yet"
                                        change > 0 -> "↗ +$change% vs yesterday"
                                        change < 0 -> "↘ $change% vs yesterday"
                                        else -> "Same as yesterday"
                                    },
                                    // Fewer notifications than yesterday reads as good news.
                                    color = when {
                                        !today || change == null || change == 0 -> MarksyTheme.TextMuted
                                        change < 0 -> MarksyTheme.PrimaryEmerald
                                        else -> MarksyTheme.YellowImportant
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            WorldClockPair()
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAccessButton("Timeline", Icons.Default.Timeline, onOpenTimeline, Modifier.weight(1f))
                    QuickAccessButton("Calendar", Icons.Default.CalendarMonth, onOpenCalendar, Modifier.weight(1f))
                    QuickAccessButton("Insights", Icons.Default.Insights, onOpenInsights, Modifier.weight(1f))
                }

                Spacer(Modifier.height(8.dp))

                // Category Quick Cards Grid
                val stats = categoryStats[HomePeriod.forLabel(selectedTimeFilter)]
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryGridCard("Trading", stats?.trading?.count ?: 0, Icons.Default.ShowChart, MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg, { onCategorySelected("Trading") }, Modifier.weight(1f))
                        CategoryGridCard("Important", stats?.important?.count ?: 0, Icons.Default.Bolt, MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg, { onCategorySelected("Important") }, Modifier.weight(1f))
                        CategoryGridCard("Messages", stats?.messages?.count ?: 0, Icons.Default.Chat, MarksyTheme.SecondaryCyan, MarksyTheme.BadgeFinanceBg, { onCategorySelected("Messages") }, Modifier.weight(1f))
                        CategoryGridCard("Teams", stats?.teams?.count ?: 0, Icons.Default.Groups, Color(0xFF9EA7FF), Color(0xFF1A1B33), { onCategorySelected("Teams") }, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryGridCard("Emails", stats?.emails?.count ?: 0, Icons.Default.Email, Color(0xFF82B1FF), Color(0xFF0F1B2E), { onCategorySelected("Emails") }, Modifier.weight(1f))
                        CategoryGridCard("Banking", stats?.banking?.count ?: 0, Icons.Default.AccountBalance, MarksyTheme.BlueFinance, MarksyTheme.BadgeFinanceBg, { onCategorySelected("Banking") }, Modifier.weight(1f))
                        CategoryGridCard("Payments", stats?.payments?.count ?: 0, Icons.Default.Payments, MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg, { onCategorySelected("Payments") }, Modifier.weight(1f))
                        CategoryGridCard("Delivery", stats?.delivery?.count ?: 0, Icons.Default.LocalShipping, MarksyTheme.OrangeDelivery, MarksyTheme.BadgeDeliveryBg, { onCategorySelected("Delivery") }, Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Market Pulse Card
                MarketPulseCard(market, todayDigest, onOpenTrading, liveIndices)

                Spacer(Modifier.height(14.dp))
                PlanUpcomingCard(planItems, onOpenPlan, onAddReminder)

                Spacer(Modifier.height(14.dp))

                // AI Summary Card
                val liveNifty = (liveIndices as? UpstoxLiveState.Live)?.quotes?.get(UpstoxIndices.NIFTY_50)?.changePct?.let { "NIFTY 50" to it }
                todayDigest?.let { AiSummaryBanner(HomeSummary.text(it, (market as? MarketState.Loaded)?.snapshot, liveNifty)) }
            }
        }

        item { SectionTitle("AI Attention Required") }

        if (attention.isEmpty()) {
            item {
                EmptyDashboardCard(
                    "Nothing needs attention yet.",
                    "Marksy OS will surface high-priority events here."
                )
            }
        } else {
            // Keys are namespaced per section: the same event can be in both Attention and Latest Activity.
            items(attention, key = { "attention-${it.key}" }) { stack ->
                Box(Modifier.padding(horizontal = 18.dp)) {
                    SourceStackCard(
                        stack, expanded = "a-${stack.key}" in expanded, swipe = swipe, group = group,
                        onToggle = { toggle("a-${stack.key}") }, onOpen = { onEventSelected(it.latest) }, onLongClick = { onEventSelected(it.latest) },
                        note = ::attentionNote
                    )
                }
            }
        }

        item { SectionTitle("Latest Activity", link = "Timeline", onLink = onOpenTimeline) }
        if (latest.isEmpty()) {
            item {
                EmptyDashboardCard(
                    "Everything is quiet.",
                    "Captured events will appear here when they arrive."
                )
            }
        } else {
            items(latest, key = { "latest-${it.key}" }) { stack ->
                Box(Modifier.padding(horizontal = 18.dp)) {
                    SourceStackCard(
                        stack, expanded = "l-${stack.key}" in expanded, swipe = swipe, group = group,
                        onToggle = { toggle("l-${stack.key}") }, onOpen = { onEventSelected(it.latest) }, onLongClick = { onEventSelected(it.latest) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherBadge(weather: Weather?, available: Boolean, onRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MarksyTheme.Surface)
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(20.dp))
            .then(if (available) Modifier else Modifier.clickable(onClick = onRequest))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            weather?.let { weatherIcon(it.condition) } ?: if (available) Icons.Default.Cloud else Icons.Default.LocationOn,
            contentDescription = weather?.condition?.name ?: "Enable weather",
            tint = weather?.let { weatherTint(it.condition) } ?: MarksyTheme.TextMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            weather?.let { "${it.temperatureC}°" } ?: if (available) "--°" else "Tap",
            color = MarksyTheme.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun weatherIcon(condition: Weather.Condition): ImageVector = when (condition) {
    Weather.Condition.CLEAR -> Icons.Default.WbSunny
    Weather.Condition.CLEAR_NIGHT -> Icons.Default.NightsStay
    Weather.Condition.PARTLY_CLOUDY -> Icons.Default.WbCloudy
    Weather.Condition.CLOUDY -> Icons.Default.Cloud
    Weather.Condition.RAIN -> Icons.Default.Umbrella
    Weather.Condition.SNOW -> Icons.Default.AcUnit
    Weather.Condition.THUNDER -> Icons.Default.Thunderstorm
}

private fun weatherTint(condition: Weather.Condition): Color = when (condition) {
    Weather.Condition.CLEAR -> MarksyTheme.YellowImportant
    Weather.Condition.RAIN, Weather.Condition.THUNDER -> MarksyTheme.SecondaryCyan
    else -> MarksyTheme.TextSecondary
}

@Composable
private fun CategoryGridCard(
    title: String,
    count: Int,
    icon: ImageVector,
    iconColor: Color,
    bgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Plain clickable Box: a clickable Card enforces a 48dp minimum height.
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MarksyTheme.Surface)
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(23.dp)
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text("$count", color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun QuickAccessButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Plain clickable Box: a clickable Card enforces a 48dp minimum height.
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MarksyTheme.Surface)
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun MarketPulseCard(market: MarketState, digest: DailyDigest?, onOpenTrading: () -> Unit, live: UpstoxLiveState = UpstoxLiveState.NotConfigured) {
    val snapshot = (market as? MarketState.Loaded)?.snapshot
    // Whole-source preference: the user's own Upstox feed when it's answering, else Marksy's snapshot.
    val liveQuotes = (live as? UpstoxLiveState.Live)?.let { l -> UpstoxIndices.HOME.mapNotNull { (key, name) -> l.quotes[key]?.let { name to it } } }?.takeIf { it.isNotEmpty() }
    var showSource by remember { mutableStateOf(false) }
    // A streaming socket needs no explanation; the icon appears only when something needs one.
    val streaming = (live as? UpstoxLiveState.Live)?.streaming == true && liveQuotes != null
    val closed = (live as? UpstoxLiveState.Live)?.marketOpen == false && liveQuotes != null
    val sourceLine = when {
        streaming || closed -> null
        liveQuotes != null -> "Upstox reconnecting · last tick ${liveTime.format(java.util.Date((live as UpstoxLiveState.Live).lastTickAt))}"
        live is UpstoxLiveState.Failed && live.tokenRejected -> "Upstox token rejected — update it in More → Upstox. Showing Marksy data."
        live is UpstoxLiveState.Failed -> "Upstox unavailable (${live.message}). Showing Marksy data."
        snapshot != null -> "Marksy market snapshot" + (snapshot.asOf?.let { " · as of $it" } ?: "")
        else -> null
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenTrading)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TrendingUp, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Market Pulse", color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        closed -> MarketStatusBadge("CLOSED")
                        liveQuotes != null && streaming -> MarketStatusBadge("LIVE")
                        liveQuotes == null -> snapshot?.marketStatus?.let { MarketStatusBadge(it) }
                    }
                    if (sourceLine != null) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Data source",
                            tint = if (live is UpstoxLiveState.Failed) MarksyTheme.YellowImportant else MarksyTheme.TextMuted,
                            modifier = Modifier.size(18.dp).clip(CircleShape).clickable { showSource = !showSource }
                        )
                    }
                }
            }
            // Source/freshness is one tap away instead of a permanent line.
            if (showSource && sourceLine != null) {
                Text(sourceLine, color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(10.dp))

            when {
                liveQuotes != null -> {
                    IndexGrid(liveQuotes.map { (name, q) -> Triple(name, q.lastPrice, q.changePct) })
                    val nifty = rememberUpstoxQuotes(Nifty50.SYMBOLS)
                    val breadth = remember(nifty) { com.marksy.os.market.MarketBreadth.of(nifty.values.map { it.changePct }) }
                    if (breadth.total >= 12) BreadthBar(breadth, "NIFTY 50 breadth", Modifier.padding(top = 10.dp))
                }
                snapshot != null && snapshot.indices.isNotEmpty() -> IndexGrid(snapshot.indices.take(6).map { Triple(it.name, it.value, it.changePct) })
                market is MarketState.Loading -> MarksyInlineLoader("Loading market data…")
                else -> Text(
                    when (market) {
                        MarketState.Loading -> ""
                        MarketState.NotConfigured -> "Connect the Marksy gateway in Settings to see live indices."
                        is MarketState.Unavailable -> "Market data unavailable right now."
                        is MarketState.Loaded -> "No index data in the latest Marksy snapshot."
                    },
                    color = MarksyTheme.TextSecondary,
                    fontSize = 12.sp
                )
            }

        }
    }
}

/** Two indices per row, each on a single line: name, value, day change. */
@Composable
private fun IndexGrid(items: List<Triple<String, Double, Double?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            // A clear gutter between the left column's % and the right column's name.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { (name, value, change) ->
                    // Name left; price and change in fixed right-aligned columns so they line up row to row.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = MarksyTheme.TextSecondary, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(formatIndex(value), color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.End, modifier = Modifier.width(46.dp))
                        Text(change?.let(::formatChange).orEmpty(), color = if ((change ?: 0.0) < 0) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.End, modifier = Modifier.width(37.dp))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The one profile/quick-action icon style used on every screen's header, so it never looks
 * different depending on which tab you're on. */
@Composable
fun HeaderIconBadge(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MarksyTheme.SurfaceRaised)
            .border(1.dp, MarksyTheme.BorderGlow, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun MarketStatusBadge(status: String) {
    val open = status.equals("OPEN", ignoreCase = true) || status.equals("LIVE", ignoreCase = true) || status.equals("MARKET_HOURS", ignoreCase = true)
    val tint = if (open) MarksyTheme.PrimaryEmerald else MarksyTheme.TextSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (open) MarksyTheme.BadgeTradingBg else MarksyTheme.SurfaceRaised)
            .border(1.dp, tint, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(5.dp))
        Text(if (open) "LIVE" else formatMarketStatus(status).uppercase(), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// Small indices (INDIA VIX ~12) need decimals to be meaningful.
private fun formatIndex(value: Double): String = String.format(java.util.Locale.getDefault(), if (value < 1000) "%,.2f" else "%,.0f", value)

private val liveTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

private fun formatChange(pct: Double): String = String.format(java.util.Locale.US, "%+.2f%%", pct)

@Composable
private fun AiSummaryBanner(summary: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0F261C), Color(0xFF0C1B13))))
            .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(26.dp).clip(CircleShape).background(MarksyTheme.BadgeTradingBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("Today at a glance", color = MarksyTheme.PrimaryEmerald, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(summary, color = MarksyTheme.TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun SectionTitle(title: String, link: String? = null, onLink: () -> Unit = {}) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Text(title, color = MarksyTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    link?.let {
        Text(
            "$it ›", color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLink).padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun EmptyDashboardCard(title: String, description: String) = Card(
    colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp)
        .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
) {
    Column(Modifier.padding(16.dp)) {
        Text(title, color = MarksyTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
        Text(description, color = MarksyTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
