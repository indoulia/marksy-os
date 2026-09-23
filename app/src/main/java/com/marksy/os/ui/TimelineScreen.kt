package com.marksy.os.ui

import com.marksy.os.EmptyState

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TimelineScreen(
    events: List<NotificationEventEntity>,
    padding: PaddingValues,
    onEventSelected: (NotificationEventEntity) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "Today · " + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM", java.util.Locale.getDefault())),
                color = MarksyTheme.TextSecondary,
                fontSize = 13.sp
            )
        }

        if (events.isEmpty()) {
            item { EmptyState("Nothing here yet.", "Captured notifications will appear here, newest first.") }
        } else {
            items(events, key = { it.id }) { event ->
                TimelineNodeRow(
                    time = formatTimestamp(event.postedAt),
                    title = event.title.ifBlank { "Captured notification" },
                    source = event.sourceName,
                    icon = if (event.isTrading) Icons.Default.ShowChart else Icons.Default.Notifications,
                    iconBg = if (event.isTrading) Color(0xFFC62828) else MarksyTheme.SurfaceRaised,
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
    iconBg: Color,
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
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }

        Spacer(Modifier.width(10.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .weight(1f)
                .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(source, color = MarksyTheme.TextSecondary, fontSize = 11.sp)
                }
                badge?.let {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFFC62828))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
