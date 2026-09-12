package com.marksy.os.notification

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val WhatsAppBackground = Color(0xFF070A09)
private val WhatsAppSurface = Color(0xFF101613)
private val WhatsAppPrimary = Color(0xFF72D49A)
private val WhatsAppText = Color(0xFFE8F1EC)
private val WhatsAppSecondary = Color(0xFF9AA9A1)

class WhatsAppSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WhatsAppSettingsScreen(
                    isEnabled = WhatsAppConnectorStatus.isAccessibilityServiceEnabled(this),
                    senders = WhatsAppSenderWatchlist.get(this),
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onAddSender = { WhatsAppSenderWatchlist.add(this, it) },
                    onRemoveSender = { WhatsAppSenderWatchlist.remove(this, it) },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setContent {
            MaterialTheme {
                WhatsAppSettingsScreen(
                    isEnabled = WhatsAppConnectorStatus.isAccessibilityServiceEnabled(this),
                    senders = WhatsAppSenderWatchlist.get(this),
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onAddSender = { WhatsAppSenderWatchlist.add(this, it) },
                    onRemoveSender = { WhatsAppSenderWatchlist.remove(this, it) },
                    onBack = { finish() }
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WhatsAppSettingsScreen(
    isEnabled: Boolean,
    senders: Set<String>,
    onOpenAccessibility: () -> Unit,
    onAddSender: (String) -> Unit,
    onRemoveSender: (String) -> Unit,
    onBack: () -> Unit
) {
    var sender by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    val currentSenders = remember(senders, refreshKey) { senders.toList().sorted() }

    Column(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("‹", color = WhatsAppText, fontSize = 30.sp) }
            Column(Modifier.weight(1f)) {
                Text("WhatsApp Connector", color = WhatsAppText, fontSize = 24.sp)
                Text("Sender allow-list", color = WhatsAppSecondary, fontSize = 13.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = WhatsAppSurface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isEnabled) "Connector enabled" else "Connector needs permission", color = WhatsAppPrimary, fontSize = 16.sp)
                Text(
                    "Marksy OS only persists WhatsApp text when a configured sender is matched. It does not inspect WhatsApp's private database.",
                    color = WhatsAppSecondary,
                    fontSize = 13.sp
                )
                Button(onClick = onOpenAccessibility) {
                    Text(if (isEnabled) "Manage Accessibility Access" else "Enable Accessibility Access")
                }
            }
        }

        Text("Watched senders (${currentSenders.size}/25)", color = WhatsAppText, fontSize = 18.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = sender,
                onValueChange = { sender = it.take(120) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Sender name") },
                placeholder = { Text("e.g. Trading Desk") }
            )
            Spacer(Modifier.padding(4.dp))
            Button(
                enabled = sender.trim().isNotBlank() && currentSenders.size < 25,
                onClick = {
                    onAddSender(sender)
                    sender = ""
                    refreshKey++
                }
            ) { Text("Add") }
        }

        if (currentSenders.isEmpty()) {
            Text("No senders are watched. The connector will capture nothing from WhatsApp until you add one.", color = WhatsAppSecondary, fontSize = 13.sp)
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(currentSenders, key = { it }) { watched ->
                    Card(colors = CardDefaults.cardColors(containerColor = WhatsAppSurface), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(watched, color = WhatsAppText, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onRemoveSender(watched); refreshKey++ }) { Text("Remove") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Privacy rule: empty allow-list = zero WhatsApp capture. Matching is local and case-insensitive.",
            color = WhatsAppSecondary,
            fontSize = 12.sp
        )
    }
}
