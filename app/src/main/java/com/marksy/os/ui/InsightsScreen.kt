package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.InsightsModel

private val InsightSurface = Color(0xFF101613)
private val InsightRaised = Color(0xFF151C18)
private val InsightPrimary = Color(0xFF72D49A)
private val InsightText = Color(0xFFE8F1EC)
private val InsightSecondary = Color(0xFF9AA9A1)
private val InsightMuted = Color(0xFF657169)

@Composable
fun InsightsScreen(events: List<NotificationEventEntity>, padding: PaddingValues) {
    val snapshot = InsightsModel.from(events)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Insights", color = InsightText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("Patterns from what Marksy OS has actually captured — no invented intelligence.", color = InsightSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
        }
        item { MetricCard("Meaningful activity", "${snapshot.meaningfulCount}", "of ${snapshot.eventCount} captured events", InsightRaised) }
        item { MetricCard("Needs attention", "${snapshot.attentionRate}%", "${snapshot.attentionCount} elevated events", InsightSurface) }
        snapshot.topCategory?.let { item { MetricCard("Top category", pretty(it), "most frequent meaningful category", InsightSurface) } }
        snapshot.topSource?.let { item { MetricCard("Top source", it, "most active meaningful source", InsightSurface) } }
        snapshot.busiestHour?.let { item { MetricCard("Peak hour", String.format("%02d:00", it), "local notification activity", InsightSurface) } }
        snapshot.tradingDeliveryRate?.let { item { MetricCard("Trading intelligence", "$it%", "events with Marksy responses", InsightRaised) } }
        item {
            Spacer(Modifier.height(6.dp))
            Text("What stands out", color = InsightText, fontWeight = FontWeight.SemiBold)
        }
        if (snapshot.observations.isEmpty()) {
            item { Text("More history is needed before Marksy OS can identify useful patterns.", color = InsightMuted, fontSize = 12.sp) }
        } else {
            snapshot.observations.forEach { observation ->
                item { ObservationCard(observation) }
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text("Local analysis only", color = InsightPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("Insights are derived from retained notification metadata and Event Intelligence. They do not imply a trading recommendation.", color = InsightMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, detail: String, surface: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp)) {
            Text(title, color = InsightSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = InsightText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = InsightMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ObservationCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = InsightSurface), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = InsightText, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
    }
}

private fun pretty(category: String): String = category.lowercase().replaceFirstChar { it.uppercase() }
