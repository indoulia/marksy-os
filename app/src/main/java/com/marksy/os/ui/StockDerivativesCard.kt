package com.marksy.os.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marksy.os.MarksyFormat
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxDerivatives
import com.marksy.os.upstox.UpstoxTokenStore
import java.time.LocalDate

/** Options for F&O stocks: expiry, PCR, highest-OI strikes and the chain around the money. Hidden for non-F&O stocks. */
@Composable
internal fun DerivativesCard(instrumentKey: String) {
    val context = LocalContext.current.applicationContext
    val client = remember { UpstoxTokenStore(context).let { store -> UpstoxApiClient { store.getToken() } } }
    val expiries by produceState<List<String>?>(null, instrumentKey) { value = runCatching { client.optionExpiries(instrumentKey) }.getOrDefault(emptyList()) }
    val list = expiries ?: return
    if (list.isEmpty()) return
    var expiry by remember(list) { mutableStateOf(list.first()) }
    var expanded by remember { mutableStateOf(false) }
    val chain by produceState<Result<UpstoxDerivatives.Chain>?>(null, instrumentKey, expiry) { value = null; value = runCatching { client.optionChain(instrumentKey, expiry) } }

    MarksyCard(Modifier.animateContentSize()) {
        MarksyCardHeader("Options")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            list.take(4).forEach { e -> Pill(day(e), selected = e == expiry) { expiry = e } }
        }
        when (val c = chain) {
            null -> MarksyInlineLoader("Loading option chain…")
            else -> c.fold(
                onSuccess = { ch -> ChainBody(ch, expanded) { expanded = !expanded } },
                onFailure = { Text("Option chain unavailable: ${it.message}", color = MarksyTheme.TextMuted, style = MarksyType.Meta) }
            )
        }
    }
}

@Composable
private fun ChainBody(ch: UpstoxDerivatives.Chain, expanded: Boolean, onToggle: () -> Unit) {
    if (ch.strikes.isEmpty()) { Text("No strikes for this expiry.", color = MarksyTheme.TextMuted, style = MarksyType.Meta); return }
    MarksyStatRow {
        MarksyStat("PCR", ch.pcr?.let { MarksyFormat.number(it) } ?: "—", Modifier.weight(1f), note = "open interest")
        MarksyStat("Support", ch.support?.let(::strike) ?: "—", Modifier.weight(1f), note = "max put OI")
        MarksyStat("Resistance", ch.resistance?.let(::strike) ?: "—", Modifier.weight(1f), note = "max call OI")
    }
    val rows = ch.aroundSpot(if (expanded) 10 else 4)
    Column {
    Row(Modifier.fillMaxWidth().padding(bottom = MarksySpace.Hair)) {
        listOf("Call OI", "Call LTP", "Strike", "Put LTP", "Put OI").forEach { h -> Text(h, color = MarksyTheme.TextMuted, style = MarksyType.Caption, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
    }
    val spot = ch.spot
    rows.forEachIndexed { i, s ->
        // A line under the last strike below spot marks where the money is.
        val crossesSpot = spot != null && s.strike < spot && rows.getOrNull(i + 1)?.strike?.let { it >= spot } == true
        val itmCall = spot != null && s.strike < spot
        val itmPut = spot != null && s.strike > spot
        Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Border), verticalAlignment = Alignment.CenterVertically) {
            Cell(s.call?.oi?.let(::compact), Modifier.weight(1f), itmCall)
            Cell(s.call?.ltp?.let(::price), Modifier.weight(1f), itmCall, bold = true)
            Text(strike(s.strike), color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Cell(s.put?.ltp?.let(::price), Modifier.weight(1f), itmPut, bold = true)
            Cell(s.put?.oi?.let(::compact), Modifier.weight(1f), itmPut)
        }
        if (crossesSpot) Row(Modifier.fillMaxWidth().padding(vertical = MarksySpace.Hair), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(MarksySpace.Border).background(MarksyTheme.Positive))
            Text(" Spot ${price(spot!!)} ", color = MarksyTheme.Positive, style = MarksyType.Caption, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f).height(MarksySpace.Border).background(MarksyTheme.Positive))
        }
    }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Upstox option chain · expiry ${ch.expiry?.let(::day) ?: "—"}", color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.weight(1f))
        Text(if (expanded) "Fewer strikes" else "More strikes", color = MarksyTheme.Positive, style = MarksyType.Meta, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(MarksyShape.Chip).clickable(onClick = onToggle).padding(horizontal = MarksySpace.Inner, vertical = MarksySpace.Tight))
    }
}

@Composable
private fun Cell(text: String?, modifier: Modifier, inTheMoney: Boolean, bold: Boolean = false) {
    Text(
        text ?: "—", color = if (bold) MarksyTheme.TextPrimary else MarksyTheme.TextSecondary, style = MarksyType.Meta,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center,
        modifier = modifier.background(if (inTheMoney) MarksyTheme.BadgeTradingBg else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = MarksySpace.Tight)
    )
}

private fun day(iso: String) = runCatching { MarksyFormat.day(LocalDate.parse(iso.take(10))) }.getOrDefault(iso)
private fun strike(v: Double) = MarksyFormat.number(v, if (v % 1.0 == 0.0) 0 else 1)
private fun price(v: Double) = MarksyFormat.number(v)
