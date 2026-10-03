package com.marksy.os.notification

import com.marksy.os.ui.MarksyMaterialTheme
import com.marksy.os.ui.InlineEmpty
import com.marksy.os.ui.SectionLabel
import com.marksy.os.ui.MarksySpace
import com.marksy.os.ui.MarksyButtonStyle
import com.marksy.os.ui.MarksyButton
import com.marksy.os.ui.MarksyCard
import com.marksy.os.ui.MarksyCardHeader
import com.marksy.os.ui.MarksySize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import com.marksy.os.ui.MarksyList
import com.marksy.os.ui.MarksyRowCard
import com.marksy.os.ui.MarksyType
import com.marksy.os.ui.MarksyTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import com.marksy.os.ui.CompactTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class WhatsAppSettingsActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)
    private var watchedSenders by mutableStateOf(emptySet<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        setContent {
            MarksyMaterialTheme { androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MarksyTheme.Background) {
                WhatsAppSettingsScreen(
                    isEnabled = accessibilityEnabled,
                    senders = watchedSenders,
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onAddSender = {
                        WhatsAppSenderWatchlist.add(this, it)
                        refreshState()
                    },
                    onRemoveSender = {
                        WhatsAppSenderWatchlist.remove(this, it)
                        refreshState()
                    },
                    onBack = { finish() }
                )
            } }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        accessibilityEnabled = WhatsAppConnectorStatus.isAccessibilityServiceEnabled(this)
        watchedSenders = WhatsAppSenderWatchlist.get(this)
    }
}

@Composable
private fun WhatsAppSettingsScreen(
    isEnabled: Boolean,
    senders: Set<String>,
    onOpenAccessibility: () -> Unit,
    onAddSender: (String) -> Unit,
    onRemoveSender: (String) -> Unit,
    onBack: () -> Unit
) {
    var sender by remember { mutableStateOf("") }
    val watchedSenders = remember(senders) { senders.toList().sorted() }

    MarksyList {
        item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = MarksyTheme.TextPrimary, modifier = Modifier.size(MarksySize.Icon)) }
            Column(Modifier.weight(1f)) {
                Text("WhatsApp Connector", color = MarksyTheme.TextPrimary, style = MarksyType.Display)
                Text("Sender allow-list", color = MarksyTheme.TextSecondary, style = MarksyType.Body)
            }
        }
        }

        item {
        MarksyCard {
            MarksyCardHeader(if (isEnabled) "Connector enabled" else "Connector needs permission", titleColor = MarksyTheme.PrimaryEmerald)
            Text(
                "Marksy OS only persists WhatsApp text when a configured sender is matched. It does not inspect WhatsApp's private database.",
                color = MarksyTheme.TextSecondary,
                style = MarksyType.Body
            )
            MarksyButton(if (isEnabled) "Manage Accessibility Access" else "Enable Accessibility Access", onClick = onOpenAccessibility)
        }
        }

        item { SectionLabel("Watched senders · ${watchedSenders.size}/25") }
        item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CompactTextField(
                value = sender,
                onValueChange = { sender = it.take(120) },
                modifier = Modifier.weight(1f),
                placeholder = "Sender name, e.g. Trading Desk"
            )
            Spacer(Modifier.width(MarksySpace.Gap))
            MarksyButton(
                "Add",
                enabled = sender.trim().isNotBlank() && watchedSenders.size < 25,
                onClick = {
                    onAddSender(sender)
                    sender = ""
                }
            )
        }
        }

        if (watchedSenders.isEmpty()) {
            item { InlineEmpty("No senders are watched. The connector will capture nothing from WhatsApp until you add one.") }
        } else {
            items(watchedSenders, key = { it }) { watched ->
                MarksyRowCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(watched, color = MarksyTheme.TextPrimary, style = MarksyType.Body, modifier = Modifier.weight(1f))
                        MarksyButton("Remove", onClick = { onRemoveSender(watched) }, style = MarksyButtonStyle.Text)
                    }
                }
            }
        }

        item {
        Text(
            "Privacy rule: empty allow-list = zero WhatsApp capture. Matching is local and case-insensitive.",
            color = MarksyTheme.TextSecondary,
            style = MarksyType.Small
        )
        }
    }
}
