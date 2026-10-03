package com.marksy.os.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marksy.os.MarksyFormat
import com.marksy.os.alerts.PriceAlertRules
import com.marksy.os.alerts.PriceAlertStore
import java.util.Locale

/** Set a one-time alert when [symbol] crosses a price; lists and removes its existing alerts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriceAlertDialog(symbol: String, lastPrice: Double?, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val all by PriceAlertStore.alerts(context).collectAsStateWithLifecycle()
    val mine = all.orEmpty().filter { it.symbol == symbol }
    var text by remember { mutableStateOf(lastPrice?.let { MarksyFormat.number(it).replace(",", "") }.orEmpty()) }
    val target = text.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Price alert · $symbol", color = MarksyTheme.TextPrimary, style = MarksyType.Lead) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                lastPrice?.let { Text("Now ${MarksyFormat.rupees(it)}", color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
                CompactTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(), placeholder = "Alert price",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                lastPrice?.let { p ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                        listOf(-5, -2, 2, 5).forEach { pct -> Pill(MarksyFormat.percent(pct.toDouble(), 0)) { text = MarksyFormat.number(p * (1 + pct / 100.0)).replace(",", "") } }
                    }
                }
                if (target != null && lastPrice != null) {
                    Text(
                        "Alerts once when $symbol ${if (PriceAlertRules.above(target, lastPrice)) "rises to" else "falls to"} ${MarksyFormat.rupees(target)}",
                        color = MarksyTheme.TextMuted, style = MarksyType.Meta
                    )
                }
                if (mine.isNotEmpty()) {
                    Text("Active", color = MarksyTheme.TextMuted, style = MarksyType.Meta, modifier = Modifier.padding(top = MarksySpace.Tight))
                    mine.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${if (a.above) "Above" else "Below"} ${MarksyFormat.rupees(a.price)}", color = MarksyTheme.TextPrimary, style = MarksyType.Body, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.Close, "Remove alert", tint = MarksyTheme.TextSecondary,
                                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { PriceAlertStore.remove(context, listOf(a.id)) }.padding(MarksySpace.Inner))
                        }
                    }
                }
            }
        },
        confirmButton = {
            MarksyButton("Set alert", enabled = target != null && lastPrice != null, style = MarksyButtonStyle.Text, onClick = {
                PriceAlertStore.add(context, symbol, target!!, lastPrice!!)
                if (Build.VERSION.SDK_INT >= 33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                onDismiss()
            })
        },
        dismissButton = { MarksyButton("Close", onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) }
    )
}
