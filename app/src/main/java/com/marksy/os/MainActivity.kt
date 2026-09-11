package com.marksy.os

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.RetentionScheduler
import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.gateway.MarksyGatewayProvider
import com.marksy.os.gateway.TradingDeliveryScheduler
import com.marksy.os.ui.AskMarksyScreen
import com.marksy.os.ui.MarksyViewModel
import com.marksy.os.ui.MarksyViewModelFactory
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

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val component = ComponentName(this, com.marksy.os.notification.MarksyNotificationListenerService::class.java)
        return enabled.split(':').any { runCatching { ComponentName.unflattenFromString(it) == component }.getOrDefault(false) }
    }

    private fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    @Composable
    private fun MarksyApp() {
        val repository = remember { MarksyContainer.repository(applicationContext) }
        val vm: MarksyViewModel = viewModel(factory = MarksyViewModelFactory(repository))
        val events by vm.recentEvents.collectAsStateWithLifecycle(initialValue = emptyList())
        val tradingInsights by vm.tradingInsights.collectAsStateWithLifecycle(initialValue = emptyList())
        var selected by remember { mutableIntStateOf(0) }
        val tabs = listOf(Tab("Home", Icons.Default.Home), Tab("Inbox", Icons.Default.Inbox), Tab("Ask", Icons.Default.SmartToy), Tab("Trading", Icons.Default.ShowChart), Tab("More", Icons.Default.MoreHoriz))

        Scaffold(containerColor = Background, bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1210)) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(selected = selected == index, onClick = { selected = index }, icon = { Icon(tab.icon, tab.label) }, label = { Text(tab.label) })
                }
            }
        }) { padding ->
            when (selected) {
                0 -> HomeScreen(events, padding)
                1 -> InboxScreen(events, padding)
                2 -> AskMarksyScreen(padding)
                3 -> TradingScreen(tradingInsights, padding)
                else -> MoreScreen(notificationAccessEnabled, ::openNotificationAccess, repository::clearAll, padding)
            }
        }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun ScreenColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 20.dp), content = content)

@Composable
private fun HomeScreen(events: List<NotificationEventEntity>, padding: PaddingValues) {
    ScreenColumn(padding) {
        Text("MARKSY OS", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Spacer(Modifier.height(8.dp))
        Text("Less noise. More intelligence.", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Your notifications, organized locally. Trading events can flow to Marksy for analysis.", color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Inbox", events.size.toString(), Modifier.weight(1f))
            MetricCard("Trading", events.count { it.isTrading }.toString(), Modifier.weight(1f))
            MetricCard("Messages", events.count { it.category == "MESSAGES" }.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Text("Latest activity", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (events.isEmpty()) EmptyState("Your intelligent inbox is ready.", "Enable notification access to start capturing events.") else events.take(4).forEach { EventCard(it) }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) = Card(modifier, colors = CardDefaults.cardColors(containerColor = Surface)) {
    Column(Modifier.padding(12.dp)) { Text(value, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(2.dp)); Text(label, color = TextSecondary, fontSize = 12.sp) }
}

@Composable
private fun InboxScreen(events: List<NotificationEventEntity>, padding: PaddingValues) {
    val filters = listOf("All", "TRADING", "MESSAGES", "PAYMENTS", "BANKING", "OTHER")
    var selected by remember { mutableStateOf("All") }
    val filtered = if (selected == "All") events else events.filter { it.category == selected }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Smart Inbox", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp)); Text("Captured locally. Only eligible trading events leave the device.", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { filter -> FilterChip(selected == filter, { selected = filter }, label = { Text(if (filter == "All") "All" else filter.lowercase().replaceFirstChar { it.uppercase() }) }) }
            }
        }
        if (filtered.isEmpty()) item { EmptyState("Nothing here yet.", "New notifications matching this filter will appear here.") }
        else items(filtered, key = { it.id }) { EventCard(it) }
    }
}

@Composable
private fun EventCard(event: NotificationEventEntity) = Card(colors = CardDefaults.cardColors(containerColor = if (event.isTrading) SurfaceRaised else Surface), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(event.sourceName, color = if (event.isTrading) Primary else TextSecondary, fontWeight = FontWeight.SemiBold)
            Text(event.category.lowercase().replaceFirstChar { it.uppercase() }, color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp)); Text(event.title, color = TextPrimary, fontWeight = FontWeight.Medium)
        if (event.body.isNotBlank()) Text(event.body, color = Color(0xFFB2BDB6), Modifier.padding(top = 4.dp), maxLines = 3)
        Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatTime(event.postedAt), color = TextMuted, fontSize = 11.sp)
            if (event.isTrading) Text(deliveryLabel(event.deliveryState), color = if (event.deliveryState == DeliveryState.FAILED.name) TextSecondary else Primary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TradingScreen(insights: List<TradingInsight>, padding: PaddingValues) {
    var selectedInsight by remember { mutableStateOf<TradingInsight?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Trading Intelligence", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp)); Text("Trading events are isolated for Marksy analysis. Execution is disabled in V1.", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
        }
        if (insights.isEmpty()) item { EmptyState("No trading events yet.", "Order and market notifications will appear here when captured.") }
        else items(insights, key = { it.eventId }) { insight -> TradingInsightCard(insight) { selectedInsight = insight } }
    }
    selectedInsight?.let { TradingInsightDetailDialog(it) { selectedInsight = null } }
}

@Composable
private fun TradingInsightCard(insight: TradingInsight, onClick: () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = SurfaceRaised), modifier = Modifier.fillMaxWidth(), onClick = onClick) {
    Column(Modifier.padding(15.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(insight.source, color = Primary, fontWeight = FontWeight.SemiBold); Text(insight.status, color = TextSecondary, fontSize = 11.sp) }
        Spacer(Modifier.height(7.dp)); Text(insight.headline, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (insight.body.isNotBlank()) { Spacer(Modifier.height(5.dp)); Text(insight.body, color = TextSecondary, fontSize = 13.sp, maxLines = 4) }
        Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text("Confidence ${(insight.confidence * 100).toInt()}%", color = TextMuted, fontSize = 11.sp)
            Text("Tap for details", color = Primary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MoreScreen(access: Boolean, openAccess: () -> Unit, clearAll: suspend () -> Unit, padding: PaddingValues) {
    var showClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ScreenColumn(padding) {
        Text("More", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(18.dp))
        SettingsCard("Notification access", if (access) "ON" else "OFF", if (access) "Marksy OS can capture notifications on this device." else "Allow Marksy OS to capture and organize notifications on this device.") { Button(onClick = openAccess) { Text(if (access) "Manage Notification Access" else "Open Notification Access") } }
        Spacer(Modifier.height(12.dp))
        SettingsCard("Marksy Gateway", "NOT CONFIGURED", "Trading events remain queued locally until the confirmed Marksy Gateway connection is configured.")
        Spacer(Modifier.height(12.dp))
        SettingsCard("Local data", "LOCAL ONLY", "Ordinary notifications stay on this device. Non-trading events are never sent to the backend.") {
            Text("Retention: 7 days for ordinary events • 30 days for trading events", color = TextSecondary, fontSize = 12.sp); Spacer(Modifier.height(10.dp))
            Button(onClick = { showClear = true }, enabled = !clearing) { Text(if (clearing) "Clearing…" else "Clear all local data") }
        }
        Spacer(Modifier.height(20.dp)); Text("Insights • Timeline • advanced settings", color = TextMuted); Text("Coming Soon", color = Primary, Modifier.padding(top = 4.dp))
    }
    if (showClear) AlertDialog(onDismissRequest = { if (!clearing) showClear = false }, title = { Text("Clear local data?") }, text = { Text("This removes all captured Marksy OS notification events from this device. Backend data is not affected.") }, confirmButton = { TextButton(onClick = { clearing = true; scope.launch { clearAll(); clearing = false; showClear = false } }, enabled = !clearing) { Text("Clear") } }, dismissButton = { TextButton({ showClear = false }, enabled = !clearing) { Text("Cancel") } })
}

@Composable
private fun SettingsCard(title: String, value: String, description: String, action: (@Composable () -> Unit)? = null) = Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold); Text(value, color = if (value == "ON" || value == "LOCAL ONLY") Primary else TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(6.dp)); Text(description, color = TextSecondary, fontSize = 13.sp)
        if (action != null) { Spacer(Modifier.height(12.dp)); action() }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) = Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(18.dp)) { Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(5.dp)); Text(subtitle, color = TextSecondary, fontSize = 13.sp) }
}

private fun deliveryLabel(state: String) = when (state) {
    DeliveryState.DELIVERED.name -> "Sent to Marksy"
    DeliveryState.PENDING.name -> "Queued for Marksy"
    DeliveryState.IN_FLIGHT.name -> "Sending…"
    DeliveryState.FAILED.name -> "Delivery failed"
    else -> "Trading event"
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("dd MMM • HH:mm", Locale.getDefault()).format(Date(timestamp))
