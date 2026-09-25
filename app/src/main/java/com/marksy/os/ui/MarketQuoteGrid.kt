package com.marksy.os.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One boxed section, two entries per row: name on top, price and day change beneath. */
@Composable
internal fun QuoteGridCard(title: String, items: List<Triple<String, String, Double?>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            items.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { (name, price, change) ->
                        Column(Modifier.weight(1f)) {
                            Text(name, color = MarksyTheme.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(price, color = MarksyTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                change?.let { pct ->
                                    Spacer(Modifier.width(6.dp))
                                    Text(String.format(java.util.Locale.US, "%+.2f%%", pct), color = if (pct < 0) MarksyTheme.RedUrgent else MarksyTheme.PrimaryEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** NIFTY 50 constituents used to rank live movers; symbols Upstox doesn't know are simply skipped. */
internal object Nifty50 {
    val SYMBOLS = listOf(
        "ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK", "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV",
        "BEL", "BHARTIARTL", "CIPLA", "COALINDIA", "DRREDDY", "EICHERMOT", "ETERNAL", "GRASIM", "HCLTECH", "HDFCBANK",
        "HDFCLIFE", "HEROMOTOCO", "HINDALCO", "HINDUNILVR", "ICICIBANK", "INDIGO", "INFY", "ITC", "JIOFIN", "JSWSTEEL",
        "KOTAKBANK", "LT", "M&M", "MARUTI", "MAXHEALTH", "NESTLEIND", "NTPC", "ONGC", "POWERGRID", "RELIANCE",
        "SBILIFE", "SBIN", "SHRIRAMFIN", "SUNPHARMA", "TATACONSUM", "TATAMOTORS", "TATASTEEL", "TCS", "TECHM",
        "TITAN", "TRENT", "ULTRACEMCO", "WIPRO"
    )
}
