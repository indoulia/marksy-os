package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marksy.os.MarksyFormat

enum class TradeSide { BUY, SELL }

/** What the ticket opens with; [target]/[stop] are Marksy's levels when the trade comes from a call. */
data class TradeIntent(val symbol: String, val side: TradeSide, val price: Double?, val target: Double? = null, val stop: Double? = null)

/** Order ticket preview. Marksy can't place orders yet, so the final button only says so; nothing leaves the device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeTicketSheet(intent: TradeIntent, onDismiss: () -> Unit) {
    var side by remember(intent) { mutableStateOf(intent.side) }
    var product by remember { mutableStateOf("Delivery") }
    var type by remember { mutableStateOf("Market") }
    var quantity by remember { mutableStateOf(1) }
    var limit by remember(intent) { mutableStateOf(intent.price?.let { MarksyFormat.plain(it) }.orEmpty()) }
    var submitted by remember { mutableStateOf(false) }
    val tint = if (side == TradeSide.BUY) MarksyTheme.Positive else MarksyTheme.Negative
    val price = if (type == "Limit") limit.toDoubleOrNull() else intent.price
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MarksyTheme.SurfaceRaised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = MarksySpace.Gutter).padding(bottom = MarksySpace.Gutter), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(intent.symbol, color = MarksyTheme.TextPrimary, style = MarksyType.Heading, modifier = Modifier.weight(1f))
                intent.price?.let { Text("LTP ${MarksyFormat.rupees(it)}", color = MarksyTheme.TextSecondary, style = MarksyType.Body) }
            }
            MarksySegmented(
                TradeSide.entries.map { it.name }, side.name, { side = TradeSide.valueOf(it); submitted = false }, Modifier.fillMaxWidth(),
                color = { if (it == TradeSide.BUY.name) MarksyTheme.Positive else MarksyTheme.Negative }
            )
            Choice("Product", listOf("Delivery", "Intraday"), product) { product = it; submitted = false }
            Choice("Order type", listOf("Market", "Limit"), type) { type = it; submitted = false }
            if (type == "Limit") CompactTextField(
                value = limit, onValueChange = { v -> limit = v.filter { it.isDigit() || it == '.' }; submitted = false },
                label = "Limit price", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Quantity", color = MarksyTheme.TextMuted, style = MarksyType.Small, modifier = Modifier.weight(1f))
                Stepper(Icons.Default.Remove, "Fewer") { if (quantity > 1) { quantity--; submitted = false } }
                Text("$quantity", color = MarksyTheme.TextPrimary, style = MarksyType.Lead, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                Stepper(Icons.Default.Add, "More") { quantity++; submitted = false }
            }
            price?.let { Text("Approx. ${MarksyFormat.rupees(it * quantity)}", color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
            if (intent.target != null || intent.stop != null) Text(
                "Marksy levels · target ${intent.target?.let { MarksyFormat.rupees(it) } ?: "–"} · stop ${intent.stop?.let { MarksyFormat.rupees(it) } ?: "–"}",
                color = MarksyTheme.TextMuted, style = MarksyType.Meta
            )
            MarksyButton("${side.name.lowercase().replaceFirstChar { it.uppercase() }} ${intent.symbol}", { submitted = true }, Modifier.fillMaxWidth(), color = tint)
            Text(
                if (submitted) "Coming soon — Marksy can't place orders yet. Nothing was sent to your broker." else "Orders aren't enabled in Marksy yet.",
                color = if (submitted) MarksyTheme.Warning else MarksyTheme.TextMuted, style = MarksyType.Small, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Choice(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MarksyTheme.TextMuted, style = MarksyType.Small, modifier = Modifier.weight(1f))
        MarksySegmented(options, selected, onSelect, Modifier.width(176.dp))
    }
}

@Composable
private fun Stepper(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(MarksyTheme.Surface).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = MarksyTheme.TextPrimary, modifier = Modifier.size(MarksySize.Icon))
    }
}
