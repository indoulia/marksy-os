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
import com.marksy.os.MarksyFormat
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxTokenStore
import com.marksy.os.upstox.UpstoxOAuth
import com.marksy.os.upstox.UpstoxOAuthStore
import com.marksy.os.portfolio.PortfolioRepository
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch

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
        Check(ok = true, rejected = false, message = quote?.let { "Connected · NIFTY 50 ${MarksyFormat.number(it.lastPrice)}" } ?: "Connected")
    } catch (e: UpstoxAuthException) {
        Check(ok = false, rejected = true, message = e.message ?: "Upstox rejected the token")
    } catch (e: Exception) {
        Check(ok = false, rejected = false, message = "Couldn't reach Upstox: ${e.message ?: e.javaClass.simpleName}. Token saved; Home will retry.")
    }

    Column(
        Modifier.fillMaxSize().background(MarksyTheme.Background)
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
            .verticalScroll(rememberScrollState())
            .padding(MarksySpace.Gutter),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Live NIFTY and BANK NIFTY prices on Home come straight from Upstox using your own read-only Analytics Token " +
                "(Upstox Developer Apps → Analytics Token). It is stored encrypted on this phone and only sent to api.upstox.com.",
            color = MarksyTheme.TextSecondary, style = MarksyType.Body
        )
        Text(
            if (saved) "Token saved on this device" else "No token saved — Home shows Marksy's market snapshot",
            color = if (saved) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, style = MarksyType.Body, fontWeight = FontWeight.SemiBold
        )
        CompactTextField(
            value = input,
            onValueChange = { input = it; status = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = if (saved) "Paste a new token to replace it" else "Paste your Upstox Analytics Token",
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            height = 48.dp,
            trailing = { MarksyButton(if (reveal) "Hide" else "Show", { reveal = !reveal }, style = MarksyButtonStyle.Text) }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MarksyButton(
                if (busy) "Checking…" else "Save & test",
                enabled = input.isNotBlank() && !busy,
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
            )
            if (saved) {
                MarksyButton("Test", {
                    busy = true
                    scope.launch { store.getToken()?.let { status = check(it) }; busy = false }
                }, style = MarksyButtonStyle.Outlined, enabled = !busy)
                MarksyButton("Remove", { store.clear(); saved = false; status = null; onChanged() }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative, enabled = !busy)
            }
        }
        if (busy) MarksyInlineLoader("Contacting Upstox…")
        status?.let { Text(it.message, color = if (it.ok) MarksyTheme.PrimaryEmerald else MarksyTheme.Negative, style = MarksyType.Body) }
        HoldingsSignInSection()
    }
}

/** Portfolio's daily Upstox sign-in: app keys, today's status, and Disconnect behind a confirmation. */
@Composable
private fun HoldingsSignInSection() {
    val context = LocalContext.current
    val repo = remember { PortfolioRepository(context) }
    var version by remember { mutableIntStateOf(0) }
    val hasKeys = remember(version) { repo.store.hasCredentials() }
    val signedIn = remember(version) { repo.store.accessToken() != null }
    val hasData = remember(version) { hasKeys || repo.store.holdings() != null }
    var keysOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    MarksyDivider(Modifier.padding(vertical = 6.dp))
    Text(
        "Market › Portfolio reads your holdings with a daily Upstox sign-in through your own Upstox app (OAuth). " +
            "The app keys and the day's token are stored encrypted on this phone and only sent to api.upstox.com. Read-only: no orders.",
        color = MarksyTheme.TextSecondary, style = MarksyType.Body
    )
    Text(
        when {
            signedIn -> "Holdings: signed in until 3:30 am"
            hasKeys -> "Holdings: app keys saved · sign in from Market › Portfolio"
            else -> "Holdings: not set up"
        },
        color = if (signedIn) MarksyTheme.PrimaryEmerald else MarksyTheme.TextMuted, style = MarksyType.Body, fontWeight = FontWeight.SemiBold
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MarksyButton(if (hasKeys) "Change app keys" else "Set up", { keysOpen = true }, style = MarksyButtonStyle.Outlined)
        if (hasData) MarksyButton("Disconnect", { confirm = true }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
    }
    if (keysOpen) UpstoxAppKeysDialog(repo.store, onDismiss = { keysOpen = false }) { keysOpen = false; version++ }
    if (confirm) MarksyDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Disconnect Upstox holdings?") },
        text = { Text("Marksy forgets your Upstox app keys, today's sign-in, the saved holdings and Upstox's login cookies. Your Analytics Token stays.") },
        dismissButton = { MarksyButton("Cancel", { confirm = false }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) },
        confirmButton = { MarksyButton("Disconnect", { repo.disconnect(); confirm = false; version++ }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative) }
    )
}

/** The user's own Upstox app keys for the holdings sign-in; a blank secret keeps the saved one. */
@Composable
internal fun UpstoxAppKeysDialog(store: UpstoxOAuthStore, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val existing = remember { store.credentials() }
    var key by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var redirect by remember { mutableStateOf(existing?.redirectUri ?: UpstoxOAuth.DEFAULT_REDIRECT) }
    val valid = key.isNotBlank() && (secret.isNotBlank() || existing != null) && (redirect.startsWith("http://") || redirect.startsWith("https://"))
    MarksyDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect your Upstox app") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In Upstox Developer Apps, create an app with this redirect URL, then paste its API key and secret. They're stored encrypted on this phone and only sent to api.upstox.com.")
                CompactTextField(redirect, { redirect = it.trim() }, Modifier.fillMaxWidth(), label = "Redirect URL")
                CompactTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = "API key")
                CompactTextField(
                    secret, { secret = it.trim() }, Modifier.fillMaxWidth(), label = "API secret",
                    placeholder = if (existing != null) "Leave blank to keep the saved one" else "",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                )
                Text("Read-only: Marksy reads holdings and never places orders.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        },
        dismissButton = { MarksyButton("Cancel", onDismiss, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) },
        confirmButton = {
            MarksyButton("Continue", {
                store.saveCredentials(key, secret.ifBlank { existing?.apiSecret.orEmpty() }, redirect)
                onSaved()
            }, style = MarksyButtonStyle.Text, enabled = valid)
        }
    )
}

private data class Check(val ok: Boolean, val rejected: Boolean, val message: String)
