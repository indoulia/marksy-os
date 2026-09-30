package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.market.LedgerCalls
import com.marksy.os.market.LedgerTipDto
import com.marksy.os.market.MarketDataState
import com.marksy.os.market.MarketIntelligenceRepository
import com.marksy.os.market.ProgressPointDto
import com.marksy.os.market.TipDetailDto

/** One tip as the ledger tracks it: terms, state, returns and every session's progress (spec §9 `GET /tips/{id}`). */
@Composable
internal fun TipDetailDialog(repository: MarketIntelligenceRepository, tipId: String, onOpenStock: ((String) -> Unit)?, onDismiss: () -> Unit) {
    val detail by produceState<MarketDataState<TipDetailDto>>(MarketDataState.Loading, tipId) { value = repository.tipDetail(tipId) }
    val tip = (detail as? MarketDataState.Loaded)?.value?.ledger
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text(tip?.let { "${it.symbol} · ${it.direction ?: "No direction"}" } ?: "Tip", color = MarksyTheme.TextPrimary) },
        text = {
            when (val d = detail) {
                is MarketDataState.Loaded -> d.value.ledger?.let { TipDetailBody(it, d.value.progress) }
                    ?: Text("This tip is not in the ledger yet.", color = MarksyTheme.TextSecondary)
                is MarketDataState.Error -> Text(d.message, color = MarksyTheme.TextSecondary)
                MarketDataState.Unavailable -> Text("Sign in to your Marksy account in More.", color = MarksyTheme.TextSecondary)
                else -> MarksyLoader("Loading tip...")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = MarksyTheme.PrimaryEmerald, fontWeight = FontWeight.SemiBold) } },
        dismissButton = if (tip != null && onOpenStock != null) {
            { TextButton(onClick = { onOpenStock(tip.symbol) }) { Text("Stock page", color = MarksyTheme.TextSecondary) } }
        } else null
    )
}

@Composable
private fun TipDetailBody(t: LedgerTipDto, progress: List<ProgressPointDto>) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(LedgerCalls.state(t), color = toneColor(LedgerCalls.tone(t)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull(t.channel?.name, t.caller?.name).joinToString(" · "), color = MarksyTheme.TextSecondary, fontSize = 12.sp)
        Text(LedgerCalls.levelsText(t), color = MarksyTheme.TextPrimary, fontSize = 12.sp)
        Text(LedgerCalls.termsText(t), color = MarksyTheme.TextMuted, fontSize = 11.sp)
        LedgerCalls.returnsText(t)?.let { Text(it, color = toneColor(LedgerCalls.progressTone(t)), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        if (progress.isNotEmpty()) Text("Session by session", color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        progress.forEach { p ->
            Text(LedgerCalls.progressLine(p), color = if (p.dataBasis == "PROVISIONAL") MarksyTheme.YellowImportant else MarksyTheme.TextSecondary, fontSize = 11.sp)
        }
    }
}
