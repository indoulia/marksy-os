package com.marksy.os.ui

import com.marksy.os.gateway.MarketState

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.marksy.os.EmptyState
import com.marksy.os.MarksyFormat
import java.time.Instant
import java.time.ZoneId
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

    MarksyList(modifier.background(MarksyTheme.Background), bottom = MarksySpace.Gutter) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(MarksySize.Avatar).clip(MarksyShape.Chip)) {
                        // The launcher foreground pads its mark for the adaptive mask; scaling crops to the mark.
                        androidx.compose.foundation.Image(
                            androidx.compose.ui.res.painterResource(com.marksy.os.R.mipmap.ic_launcher_foreground), contentDescription = "Marksy",
                            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.6f, scaleY = 1.6f)
                        )
                    }
                    Spacer(Modifier.width(MarksySpace.ListGap))
                    Text("MARKSY", color = MarksyTheme.TextPrimary, style = MarksyType.Wordmark)
                    Spacer(Modifier.width(MarksySpace.Inner))
                    Text("OS", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Wordmark)
                    Spacer(Modifier.width(MarksySpace.Gap))
                    MarksyBadge("V2", MarksyTheme.PrimaryEmerald, MarksyTheme.BadgeTradingBg)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MarksySpace.ListGap), verticalAlignment = Alignment.CenterVertically) {
                    HeaderIconBadge(icon = Icons.Default.AutoAwesome, contentDescription = "Ask Marksy", onClick = onOpenAsk)
                    HeaderIconBadge(icon = Icons.Default.EventNote, contentDescription = "Plan", onClick = onOpenPlan)
                    HeaderIconBadge(icon = Icons.Default.Person, contentDescription = "Profile", onClick = onOpenProfile)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap), verticalAlignment = Alignment.CenterVertically) {
                listOf("Today", "This Week", "All Time").forEach { filter ->
                    Pill(filter, selected = filter == selectedTimeFilter) { selectedTimeFilter = filter }
                }
                Spacer(Modifier.weight(1f))
                WeatherBadge(weather, weatherAvailable, onRequestWeather)
            }
        }

        item {
            MarksyCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${when (selectedTimeFilter) { "Today" -> trend.today; "This Week" -> trend.lastSevenDays.sum(); else -> trend.total }}",
                                color = MarksyTheme.TextPrimary,
                                style = MarksyType.Display
                            )
                            Spacer(Modifier.width(MarksySpace.Gap))
                            Text("Notifications", color = MarksyTheme.TextSecondary, style = MarksyType.Body, fontWeight = FontWeight.Medium)
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
                                change > 0 -> "↗ ${MarksyFormat.percent(change.toDouble(), 0)} vs yesterday"
                                change < 0 -> "↘ ${MarksyFormat.percent(change.toDouble(), 0)} vs yesterday"
                                else -> "Same as yesterday"
                            },
                            // Fewer notifications than yesterday reads as good news.
                            color = when {
                                !today || change == null || change == 0 -> MarksyTheme.TextMuted
                                change < 0 -> MarksyTheme.Positive
                                else -> MarksyTheme.Warning
                            },
                            style = MarksyType.Meta,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    WorldClockPair()
                }
            }
        }

        item {
            MarksyStatRow {
                QuickAccessButton("Timeline", Icons.Default.Timeline, onOpenTimeline, Modifier.weight(1f))
                QuickAccessButton("Calendar", Icons.Default.CalendarMonth, onOpenCalendar, Modifier.weight(1f))
                QuickAccessButton("Insights", Icons.Default.Insights, onOpenInsights, Modifier.weight(1f))
            }
        }

        val stats = categoryStats[HomePeriod.forLabel(selectedTimeFilter)]
        item {
            MarksyStatRow {
                CategoryGridCard("Trading", stats?.trading?.count ?: 0, Icons.Default.ShowChart, MarksySource.TRADING, { onCategorySelected("Trading") }, Modifier.weight(1f))
                CategoryGridCard("Important", stats?.important?.count ?: 0, Icons.Default.Bolt, MarksySource.IMPORTANT, { onCategorySelected("Important") }, Modifier.weight(1f))
                CategoryGridCard("Messages", stats?.messages?.count ?: 0, Icons.Default.Chat, MarksySource.WHATSAPP, { onCategorySelected("Messages") }, Modifier.weight(1f))
                CategoryGridCard("Teams", stats?.teams?.count ?: 0, Icons.Default.Groups, MarksySource.WORK, { onCategorySelected("Teams") }, Modifier.weight(1f))
            }
        }
        item {
            MarksyStatRow {
                CategoryGridCard("Emails", stats?.emails?.count ?: 0, Icons.Default.Email, MarksySource.EMAIL, { onCategorySelected("Emails") }, Modifier.weight(1f))
                CategoryGridCard("Banking", stats?.banking?.count ?: 0, Icons.Default.AccountBalance, MarksySource.BANK, { onCategorySelected("Banking") }, Modifier.weight(1f))
                CategoryGridCard("Payments", stats?.payments?.count ?: 0, Icons.Default.Payments, MarksySource.TRADING, { onCategorySelected("Payments") }, Modifier.weight(1f))
                CategoryGridCard("Delivery", stats?.delivery?.count ?: 0, Icons.Default.LocalShipping, MarksySource.DELIVERY, { onCategorySelected("Delivery") }, Modifier.weight(1f))
            }
        }

        item { MarketPulseCard(market, todayDigest, onOpenTrading, liveIndices) }
        item { PlanUpcomingCard(planItems, onOpenPlan, onAddReminder) }
        todayDigest?.let { digest ->
            val liveNifty = (liveIndices as? UpstoxLiveState.Live)?.quotes?.get(UpstoxIndices.NIFTY_50)?.changePct?.let { "NIFTY 50" to it }
            item { AiSummaryBanner(HomeSummary.text(digest, (market as? MarketState.Loaded)?.snapshot, liveNifty)) }
        }

        item { SectionLabel("AI Attention Required") }

        if (attention.isEmpty()) {
            item {
                EmptyDashboardCard(
                    "Nothing needs attention yet",
                    "Marksy OS will surface high-priority events here."
                )
            }
        } else {
            // Keys are namespaced per section: the same event can be in both Attention and Latest Activity.
            items(attention, key = { "attention-${it.key}" }) { stack ->
                SourceStackCard(
                    stack, expanded = "a-${stack.key}" in expanded, swipe = swipe, group = group,
                    onToggle = { toggle("a-${stack.key}") }, onOpen = { onEventSelected(it.latest) }, onLongClick = { onEventSelected(it.latest) },
                    note = ::attentionNote
                )
            }
        }

        item {
            SectionLabel("Latest Activity", trailing = {
                Row(Modifier.clip(MarksyShape.Chip).clickable(onClick = onOpenTimeline), verticalAlignment = Alignment.CenterVertically) {
                    Text("Timeline", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.Icon))
                }
            })
        }
        if (latest.isEmpty()) {
            item {
                EmptyDashboardCard(
                    "Everything is quiet",
                    "Captured events will appear here when they arrive."
                )
            }
        } else {
            items(latest, key = { "latest-${it.key}" }) { stack ->
                SourceStackCard(
                    stack, expanded = "l-${stack.key}" in expanded, swipe = swipe, group = group,
                    onToggle = { toggle("l-${stack.key}") }, onOpen = { onEventSelected(it.latest) }, onLongClick = { onEventSelected(it.latest) }
                )
            }
        }
    }
}

@Composable
private fun WeatherBadge(weather: Weather?, available: Boolean, onRequest: () -> Unit) {
    MarksyBadge(
        weather?.let { "${it.temperatureC}°" } ?: if (available) "--°" else "Tap",
        weather?.let { weatherTint(it.condition) } ?: MarksyTheme.TextMuted,
        MarksyTheme.SurfaceRaised,
        modifier = if (available) Modifier else Modifier.clickable(onClick = onRequest),
        icon = weather?.let { weatherIcon(it.condition) } ?: if (available) Icons.Default.Cloud else Icons.Default.LocationOn
    )
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
    Weather.Condition.CLEAR -> MarksyTheme.Warning
    Weather.Condition.RAIN, Weather.Condition.THUNDER -> MarksyTheme.SecondaryCyan
    else -> MarksyTheme.TextSecondary
}

@Composable
private fun CategoryGridCard(
    title: String,
    count: Int,
    icon: ImageVector,
    source: MarksySource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MarksyCard(modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(MarksySize.Icon).clip(CircleShape).background(source.background), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = title, tint = source.accent, modifier = Modifier.size(MarksySize.IconSmall))
            }
            Spacer(Modifier.width(MarksySpace.Inner))
            Text("$count", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun QuickAccessButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MarksyCard(modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.IconSmall))
            Spacer(Modifier.width(MarksySpace.Inner))
            Text(label, color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
        liveQuotes != null -> "Upstox reconnecting · last tick ${MarksyFormat.time(Instant.ofEpochMilli((live as UpstoxLiveState.Live).lastTickAt).atZone(ZoneId.systemDefault()))}"
        live is UpstoxLiveState.Failed && live.tokenRejected -> "Upstox token rejected — update it in More → Upstox. Showing Marksy data."
        live is UpstoxLiveState.Failed -> "Upstox unavailable (${live.message}). Showing Marksy data."
        snapshot != null -> "Marksy market snapshot" + (snapshot.asOf?.let { " · as of $it" } ?: "")
        else -> null
    }
    MarksyCard(onClick = onOpenTrading) {
            MarksyCardHeader("Market Pulse", icon = Icons.Default.TrendingUp, trailing = {
                when {
                    closed -> MarketStatusBadge("CLOSED")
                    liveQuotes != null && streaming -> MarketStatusBadge("LIVE")
                    liveQuotes == null -> snapshot?.marketStatus?.let { MarketStatusBadge(it) }
                }
                if (sourceLine != null) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Data source",
                        tint = if (live is UpstoxLiveState.Failed) MarksyTheme.Warning else MarksyTheme.TextMuted,
                        modifier = Modifier.size(MarksySize.Icon).clip(CircleShape).clickable { showSource = !showSource }
                    )
                }
            })
            // Source/freshness is one tap away instead of a permanent line.
            if (showSource && sourceLine != null) {
                Text(sourceLine, color = MarksyTheme.TextMuted, style = MarksyType.Caption)
            }

            when {
                liveQuotes != null -> {
                    IndexGrid(liveQuotes.map { (name, q) -> Triple(name, q.lastPrice, q.changePct) })
                    val nifty = rememberUpstoxQuotes(Nifty50.SYMBOLS)
                    val breadth = remember(nifty) { com.marksy.os.market.MarketBreadth.of(nifty.values.map { it.changePct }) }
                    if (breadth.total >= 12) BreadthBar(breadth, "NIFTY 50 breadth")
                }
                snapshot != null && snapshot.indices.isNotEmpty() -> IndexGrid(snapshot.indices.take(6).map { Triple(it.name, it.value, it.changePct) })
                market is MarketState.Loading -> MarksyInlineLoader("Loading market data…")
                else -> Text(
                    when (market) {
                        MarketState.Loading -> ""
                        MarketState.NotConfigured -> "Marksy is not connected. Sign in to your Marksy account in More to see live indices."
                        is MarketState.Unavailable -> "Market data unavailable right now."
                        is MarketState.Loaded -> "No index data in the latest Marksy snapshot."
                    },
                    color = MarksyTheme.TextSecondary,
                    style = MarksyType.Small
                )
            }
    }
}

/** Two indices per row, each on a single line: name, value, day change. */
@Composable
private fun IndexGrid(items: List<Triple<String, Double, Double?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
        items.chunked(2).forEach { row ->
            // A clear gutter between the left column's % and the right column's name.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                row.forEach { (name, value, change) ->
                    // Name left; price and change in fixed right-aligned columns so they line up row to row.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = MarksyTheme.TextSecondary, style = MarksyType.Caption, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(formatIndex(value), color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.End, modifier = Modifier.padding(horizontal = MarksySpace.Inner))
                        Text(change?.let(::formatChange).orEmpty(), color = if ((change ?: 0.0) < 0) MarksyTheme.Negative else MarksyTheme.Positive, style = MarksyType.Caption, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.End)
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
            .size(MarksySize.HeaderButton)
            .clip(CircleShape)
            .background(MarksyTheme.SurfaceRaised)
            .border(MarksySpace.Border, MarksyTheme.BorderGlow, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.Icon))
    }
}

@Composable
internal fun MarketStatusBadge(status: String) {
    val open = status.equals("OPEN", ignoreCase = true) || status.equals("LIVE", ignoreCase = true) || status.equals("MARKET_HOURS", ignoreCase = true)
    if (open) { MarksyBadge("LIVE", MarksyTheme.OnAccent, MarksyTheme.PrimaryEmerald); return }
    val tint = MarksyTheme.TextSecondary
    Row(
        modifier = Modifier
            .clip(MarksyShape.Pill)
            .background(MarksyTheme.SurfaceRaised)
            .border(MarksySpace.Border, tint, MarksyShape.Pill)
            .padding(horizontal = MarksySpace.Gap, vertical = MarksySpace.Tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(MarksySize.Dot).clip(CircleShape).background(tint))
        Spacer(Modifier.width(MarksySpace.Inner))
        Text(formatMarketStatus(status).uppercase(), color = tint, style = MarksyType.Caption, fontWeight = FontWeight.Bold)
    }
}

// Same two decimals as Market Overview.
private fun formatIndex(value: Double): String = MarksyFormat.number(value, 2)

private fun formatChange(pct: Double): String = MarksyFormat.percent(pct)

@Composable
private fun AiSummaryBanner(summary: String) {
    MarksyCard(border = MarksyTheme.PrimaryEmerald) {
        MarksyCardHeader("Today at a glance", icon = Icons.Default.AutoAwesome)
        Text(summary, color = MarksyTheme.TextPrimary, style = MarksyType.Body)
    }
}

@Composable
private fun EmptyDashboardCard(title: String, description: String) = EmptyState(title, description)
