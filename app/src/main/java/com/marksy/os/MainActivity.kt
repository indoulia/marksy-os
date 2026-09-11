package com.marksy.os

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MarksyApp() }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun MarksyApp() {
    val tabs = listOf(
        Tab("Home", Icons.Default.Home),
        Tab("Inbox", Icons.Default.Inbox),
        Tab("Ask", Icons.Default.SmartToy),
        Tab("Trading", Icons.Default.ShowChart),
        Tab("More", Icons.Default.MoreHoriz),
    )
    var selected by remember { mutableIntStateOf(0) }

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
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when (selected) {
                    0 -> "Marksy OS\nYour Life. One Intelligent View."
                    1 -> "Smart Inbox\nComing Soon"
                    2 -> "Ask Marksy\nComing Soon"
                    3 -> "Trading Intelligence\nComing Soon"
                    else -> "More\nComing Soon"
                },
                color = Color(0xFFE8F1EC),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
