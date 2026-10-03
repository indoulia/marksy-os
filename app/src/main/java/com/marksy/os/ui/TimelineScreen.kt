package com.marksy.os.ui

import java.time.LocalDate
import com.marksy.os.MarksyFormat
import com.marksy.os.EmptyState

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marksy.os.data.local.NotificationEventEntity

@Composable
fun TimelineScreen(
    events: List<NotificationEventEntity>,
    padding: PaddingValues,
    onEventSelected: (NotificationEventEntity) -> Unit = {}
) {
    MarksyList(Modifier.background(MarksyTheme.Background).padding(padding)) {
        item {
            Text(
                "Today · " + MarksyFormat.weekdayDay(LocalDate.now()),
                color = MarksyTheme.TextSecondary,
                style = MarksyType.Body
            )
        }

        if (events.isEmpty()) {
            item { EmptyState("Nothing here yet", "Captured notifications will appear here, newest first.") }
        } else {
            items(events, key = { it.id }) { event ->
                TimelineNodeRow(
                    time = compactTime(event.postedAt).orEmpty(),
                    title = event.title.ifBlank { "Captured notification" },
                    source = event.sourceName,
                    icon = if (event.isTrading) Icons.Default.ShowChart else Icons.Default.Notifications,
                    tile = if (event.isTrading) MarksySource.TRADING else MarksySource.OTHER,
                    badge = null
                )
            }
        }
    }
}

@Composable
private fun TimelineNodeRow(
    time: String,
    title: String,
    source: String,
    icon: ImageVector,
    tile: MarksySource,
    badge: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Time + category icon share one column instead of two, so the card gets the width back.
        Column(
            modifier = Modifier.width(46.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                time,
                color = MarksyTheme.TextMuted,
                style = MarksyType.Meta,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(MarksySpace.Tight))
            Box(
                modifier = Modifier
                    .size(MarksySize.IconLarge)
                    .clip(CircleShape)
                    .background(tile.background),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tile.accent, modifier = Modifier.size(MarksySize.IconSmall))
            }
        }

        Spacer(Modifier.width(MarksySpace.ListGap))

        MarksyRowCard(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.Bold)
                    Text(source, color = MarksyTheme.TextSecondary, style = MarksyType.Meta)
                }
                badge?.let {
                    MarksyBadge(it, MarksyTheme.Negative, MarksyTheme.BadgeUrgentBg)
                }
            }
        }
    }
}

