package com.marksy.os

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.notification.MarksyNotificationListenerService
import com.marksy.os.ui.MarksyViewModel
import com.marksy.os.ui.MarksyViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MarksyApp() }
    }

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    @Composable
    private fun MarksyApp() {
        val repository = remember { MarksyContainer.repository(applicationContext) }
        val vm: MarksyViewModel = viewModel(factory = MarksyViewModelFactory(repository))
        val events by vm.recentEvents.collectAsStateWithLifecycle()
        var selected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

        val tabs = listOf(
            Tab("Home", Icons.Default.Home), Tab("Inbox", Icons.Default.Inbox),
            Tab("Ask", Icons.Default.SmartToy), Tab("Trading", Icons.Default.ShowChart),
            Tab("More", Icons.Default.MoreHoriz)
        )

        Scaffold(
            containerColor = Color(0xFF070A09),
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0D1210)) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                0 -> HomeScreen(events, padding)
                1 -> InboxScreen(events, padding)
                2 -> PlaceholderScreen("Ask Marksy", padding)
                3 -> TradingScreen(events, padding)
                else -> MoreScreen(::openNotificationAccess, padding)
            }
        }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun HomeScreen(events: List<NotificationEventEntity>, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Marksy OS", color = Color(0xFFE8F1EC), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Your Life. One Intelligent View.", color = Color(0xFF9AA9A1), fontSize = 16.sp)
            Spacer(Modifier.height(20.dp))
            Text("${events.size} events captured locally", color = Color(0xFF72D49A))
        }
    }
}

@Composable
private fun InboxScreen(events: List<NotificationEventEntity>, padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Smart Inbox", color = Color(0xFFE8F1EC), fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item { Text("Captured locally. No notification warehouse.", color = Color(0xFF8F9D95)) }
        items(events, key = { it.id }) { event -> EventCard(event) }
        if (events.isEmpty()) item { Text("Waiting for notifications…", color = Color(0xFF7F8B84)) }
    }
}

@Composable
private fun EventCard(event: NotificationEventEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101613)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(event.sourceName, color = Color(0xFF72D49A), fontWeight = FontWeight.SemiBold)
                Text(event.category, color = Color(0xFF8F9D95), fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(event.title, color = Color(0xFFE8F1EC), fontWeight = FontWeight.Medium)
            if (event.body.isNotBlank()) Text(event.body, color = Color(0xFFB2BDB6), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TradingScreen(events: List<NotificationEventEntity>, padding: PaddingValues) {
    val trading = events.filter { it.isTrading }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Trading Intelligence", color = Color(0xFFE8F1EC), fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item { Text("Trading events are isolated for Marksy analysis. Execution is disabled in V1.", color = Color(0xFF8F9D95)) }
        items(trading, key = { it.id }) { EventCard(it) }
        if (trading.isEmpty()) item { Text("No trading events yet.", color = Color(0xFF7F8B84)) }
    }
}

@Composable
private fun MoreScreen(openAccess: () -> Unit, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
        Text("More", color = Color(0xFFE8F1EC), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Text("Notification access", color = Color(0xFFE8F1EC), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Allow Marksy OS to capture and organize notifications on this device.", color = Color(0xFF9AA9A1))
        Spacer(Modifier.height(12.dp))
        Button(onClick = openAccess) { Text("Open Notification Access") }
        Spacer(Modifier.height(28.dp))
        Text("Ask Marksy • Insights • Timeline • advanced settings", color = Color(0xFF657169))
        Text("Coming Soon", color = Color(0xFF72D49A), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PlaceholderScreen(title: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color(0xFFE8F1EC), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Coming Soon", color = Color(0xFF72D49A), modifier = Modifier.padding(top = 8.dp))
        }
    }
}
