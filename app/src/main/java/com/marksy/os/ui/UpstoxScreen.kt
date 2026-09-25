package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxTokenStore
import kotlinx.coroutines.launch
import java.util.Locale

/** Enter / replace / remove the user's own Upstox Analytics Token. */
@Composable
fun UpstoxScreen(store: UpstoxTokenStore, padding: PaddingValues, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(store.hasToken()) }
    var input by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Check?>(null) }

    suspend fun check(token: String): Check = try {
        val quote = UpstoxApiClient { token }.ltp(listOf(UpstoxIndices.NIFTY_50))[UpstoxIndices.NIFTY_50]
        Check(ok = true, rejected = false, message = quote?.let { "Connected · NIFTY 50 ${String.format(Locale.getDefault(), "%,.2f", it.lastPrice)}" } ?: "Connected")
    } catch (e: UpstoxAuthException) {
        Check(ok = false, rejected = true, message = e.message ?: "Upstox rejected the token")
    } catch (e: Exception) {
        Check(ok = false, rejected = false, message = "Couldn't reach Upstox: ${e.message ?: e.javaClass.simpleName}. Token saved; Home will retry.")
    }

    Column(
        Modifier.fillMaxSize().background(MarksyTheme.Background)
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Live NIFTY and BANK NIFTY prices on Home come straight from Upstox using your own read-only Analytics Token " +
                "(Upstox Developer Apps → Analytics Token). It is stored encrypted on this phone and only sent to api.upstox.com.",
            color = MarksyTheme.TextSecondary, fontSize = 13.sp
        )
        Text(
            if (saved) "Token saved on this device" else "No token saved — Home shows Marksy's market snapshot",
            color = if (saved) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
        CompactTextField(
            value = input,
            onValueChange = { input = it; status = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = if (saved) "Paste a new token to replace it" else "Paste your Upstox Analytics Token",
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            height = 48.dp,
            trailing = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show", fontSize = 12.sp) } }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = input.isNotBlank() && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MarksyTheme.PrimaryEmerald,
                    contentColor = Color.Black,
                    // Default disabled colours are near-invisible on the dark theme.
                    disabledContainerColor = MarksyTheme.SurfaceRaised,
                    disabledContentColor = MarksyTheme.TextMuted
                ),
                onClick = {
                    busy = true
                    scope.launch {
                        val result = check(input.trim())
                        // Only a definite rejection blocks saving; a network hiccup shouldn't lose a good token.
                        if (!result.rejected) {
                            store.save(input)
                            saved = true
                            input = ""
                            onChanged()
                        }
                        status = result
                        busy = false
                    }
                }
            ) { Text(if (busy) "Checking…" else "Save & test") }
            if (saved) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch { store.getToken()?.let { status = check(it) }; busy = false }
                }) { Text("Test", color = MarksyTheme.PrimaryEmerald) }
                TextButton(enabled = !busy, onClick = { store.clear(); saved = false; status = null; onChanged() }) {
                    Text("Remove", color = MarksyTheme.RedUrgent)
                }
            }
        }
        if (busy) MarksyInlineLoader("Contacting Upstox…")
        status?.let { Text(it.message, color = if (it.ok) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 13.sp) }
    }
}

private data class Check(val ok: Boolean, val rejected: Boolean, val message: String)
