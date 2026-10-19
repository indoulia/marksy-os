package com.marksy.os.ui

import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.marksy.os.MarksyFormat
import com.marksy.os.portfolio.PortfolioRepository
import com.marksy.os.upstox.TokenCheck
import com.marksy.os.upstox.UpstoxApiClient
import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxDraft
import com.marksy.os.upstox.UpstoxField
import com.marksy.os.upstox.UpstoxIndices
import com.marksy.os.upstox.UpstoxOAuth
import com.marksy.os.upstox.UpstoxOAuthStore
import com.marksy.os.upstox.UpstoxQrImage
import com.marksy.os.upstox.UpstoxQrImport
import com.marksy.os.upstox.UpstoxSandboxStatus
import com.marksy.os.upstox.UpstoxSaved
import com.marksy.os.upstox.UpstoxSettingsSaver
import com.marksy.os.upstox.UpstoxTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

private sealed interface Notice {
    data class Filled(val fields: Set<UpstoxField>) : Notice
    data class Refused(val message: String) : Notice
    data class Done(val message: String, val ok: Boolean) : Notice
}

/** Settings › Upstox: the live account (read-only) and the sandbox token, typed or filled from a QR code. */
@Composable
fun UpstoxScreen(store: UpstoxTokenStore, padding: PaddingValues, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { PortfolioRepository(context) }
    val saver = remember { UpstoxSettingsSaver(repo.store, store, ::checkAnalyticsToken) }
    var saved by remember { mutableStateOf(UpstoxSaved.load(repo.store, store)) }
    var signedIn by remember { mutableStateOf(repo.store.accessToken() != null) }
    var hasAccountData by remember { mutableStateOf(saved.hasSecret || saved.sandboxExpiresAt != null || repo.store.holdings() != null) }
    var draft by remember { mutableStateOf(UpstoxDraft(saved)) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    SecureWindow()

    fun reload(): UpstoxSaved {
        val fresh = UpstoxSaved.load(repo.store, store)
        saved = fresh
        signedIn = repo.store.accessToken() != null
        hasAccountData = fresh.hasSecret || fresh.sandboxExpiresAt != null || repo.store.holdings() != null
        return fresh
    }
    fun reset() { draft = UpstoxDraft(reload()) }

    fun take(scanned: String?) {
        when (val result = scanned?.let(UpstoxQrImport::parse) ?: UpstoxQrImport.Rejected("No QR code found in that image.")) {
            is UpstoxQrImport.Ok -> { draft = draft.apply(result); notice = Notice.Filled(result.fields.keys) }
            is UpstoxQrImport.Rejected -> notice = Notice.Refused(result.message)
        }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::take) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { take(UpstoxQrImage.fromUri(context, uri)) }
    }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { reset() }
    val edit: (UpstoxField, String) -> Unit = { field, text -> draft = draft.edit(field, text) }
    val problem = draft.problem()

    Box(Modifier.fillMaxSize().background(MarksyTheme.Background).padding(padding).consumeWindowInsets(padding)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MarksySpace.Gutter),
            verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
        ) {
            notice?.let { NoticeCard(it) }
            LiveAccountCard(draft, saved, signedIn, edit,
                onSignIn = { if (!busy) signIn.launch(Intent(context, UpstoxSignInActivity::class.java)) },
                onRemoveAnalytics = { if (!busy) { store.clear(); onChanged(); reset() } })
            SandboxCard(draft, saved, edit, onRemove = { if (!busy) { repo.store.clearSandboxToken(); reset() } })
            if (draft.dirty && problem != null) Text(problem, color = MarksyTheme.Negative, style = MarksyType.Small)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Gap, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (draft.dirty) MarksyButton("Discard", { draft = draft.discard(); notice = null }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary, enabled = !busy)
                MarksyButton(if (busy) "Checking…" else "Save & test", {
                    busy = true
                    scope.launch {
                        try {
                            val result = saver.save(draft, System.currentTimeMillis())
                            notice = Notice.Done(saveMessage(result), result.analytics != TokenCheck.REJECTED)
                            draft = draft.afterSave(reload(), result)
                            onChanged()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            notice = Notice.Done("Couldn't save on this phone. Try again.", ok = false)
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = draft.dirty && problem == null && !busy)
            }
            if (busy) MarksyInlineLoader("Contacting Upstox…")
            if (hasAccountData) {
                MarksyButton("Disconnect Upstox", { confirm = true }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative, enabled = !busy)
                Text("Forgets the app keys, today's sign-in, saved holdings and the sandbox token. The Analytics Token stays.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        }
        OneHandControls(
            filters = emptyList(), selectedFilter = "", onFilterSelected = {},
            actions = listOf(
                FloatingAction(Icons.AutoMirrored.Filled.HelpOutline, "How to make the QR") { if (!busy) help = true },
                FloatingAction(Icons.Default.QrCodeScanner, "Import from QR") { if (!busy) sheet = true }
            )
        )
    }

    if (sheet) MarksyBottomSheet(onDismissRequest = { sheet = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = MarksySpace.Gutter).padding(bottom = MarksySpace.Gutter), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
            Text("Import from QR", color = MarksyTheme.TextPrimary, style = MarksyType.Heading)
            ImportOption(Icons.Default.PhotoCamera, "Scan with camera", "Point at the code on your computer screen.") {
                sheet = false
                scan.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Point at the Marksy Upstox QR")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                })
            }
            ImportOption(Icons.Default.PhotoLibrary, "Choose an image", "Android photo picker. Marksy sees only the image you pick.") {
                sheet = false
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            Text("The code only fills the form. Nothing is saved until you tap Save & test.", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
            MarksyButton("How to make the QR", { sheet = false; help = true }, style = MarksyButtonStyle.Text)
        }
    }
    if (help) MarksyDialog(
        onDismissRequest = { help = false },
        title = { Text("Make an Upstox QR") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
                QR_STEPS.forEachIndexed { i, step -> Text("${i + 1}. $step", color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
                Text(QR_FORMAT, color = MarksyTheme.TextPrimary, style = MarksyType.Code)
                Text(QR_RULES, color = MarksyTheme.TextSecondary, style = MarksyType.Small)
                Text(QR_WARNING, color = MarksyTheme.Warning, style = MarksyType.Small)
            }
        },
        confirmButton = { MarksyButton("Close", { help = false }, style = MarksyButtonStyle.Text) }
    )
    if (confirm) MarksyDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Disconnect Upstox?") },
        text = { Text("Marksy forgets your app keys, today's sign-in, the saved holdings, the sandbox token and Upstox's login cookies. Your Analytics Token stays.") },
        dismissButton = { MarksyButton("Cancel", { confirm = false }, style = MarksyButtonStyle.Text, color = MarksyTheme.TextSecondary) },
        confirmButton = { MarksyButton("Disconnect", { repo.disconnect(); confirm = false; notice = null; reset() }, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative) }
    )
}

/** Keeps the keys out of screenshots, screen recordings and the recent-apps preview. */
@Composable
private fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun LiveAccountCard(
    draft: UpstoxDraft, saved: UpstoxSaved, signedIn: Boolean, onEdit: (UpstoxField, String) -> Unit,
    onSignIn: () -> Unit, onRemoveAnalytics: () -> Unit
) {
    MarksyCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE ACCOUNT · READ-ONLY", color = MarksyTheme.TextMuted, style = MarksyType.Label, modifier = Modifier.weight(1f))
            when {
                signedIn -> Text("Signed in until 3:30 am", color = MarksyTheme.PrimaryEmerald, style = MarksyType.Meta, fontWeight = FontWeight.SemiBold)
                saved.hasSecret -> Pill("Sign in") { onSignIn() }
            }
        }
        Text("Quotes, holdings, positions, funds and today's orders. This side never places orders.", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
        SecretField(draft, UpstoxField.ANALYTICS_TOKEN, if (saved.hasAnalytics) "Saved · paste or import to replace" else "Paste or import your Analytics Token", onEdit)
        if (saved.hasAnalytics) {
            val exp = saved.analyticsExpiresAt
            val expired = exp != null && exp <= System.currentTimeMillis()
            MetaAction(
                when {
                    exp == null -> "Market data · saved."
                    expired -> "Market data · expired ${fullDay(exp)}."
                    else -> "Market data · valid until ${fullDay(exp)}."
                },
                if (expired) MarksyTheme.Negative else MarksyTheme.TextMuted, onRemoveAnalytics
            )
        }
        SecretField(draft, UpstoxField.API_KEY, "Paste or import your API key", onEdit)
        SecretField(draft, UpstoxField.API_SECRET, if (saved.hasSecret) "Saved · leave blank to keep it" else "Paste or import your API secret", onEdit)
        SecretField(draft, UpstoxField.REDIRECT, UpstoxOAuth.DEFAULT_REDIRECT, onEdit, secret = false)
        Text("Must match your Upstox app: https, or http only to 127.0.0.1 on this phone.", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
}

@Composable
private fun SandboxCard(draft: UpstoxDraft, saved: UpstoxSaved, onEdit: (UpstoxField, String) -> Unit, onRemove: () -> Unit) {
    val status = UpstoxSandboxStatus.of(saved.sandboxExpiresAt, System.currentTimeMillis())
    MarksyCard(Modifier.fillMaxWidth()) {
        Text("SANDBOX · PRACTICE ORDERS", color = MarksyTheme.TextMuted, style = MarksyType.Label)
        Text("Used only with Upstox's sandbox. Practice orders are simulated: they never reach your account, holdings or positions.", color = MarksyTheme.TextSecondary, style = MarksyType.Small)
        SecretField(
            draft, UpstoxField.SANDBOX_TOKEN,
            if (status.state == UpstoxSandboxStatus.State.NONE) "Paste or import a sandbox token" else "Saved · paste or import to replace", onEdit
        )
        val (line, color) = sandboxLine(status)
        if (status.state == UpstoxSandboxStatus.State.NONE) Text(line, color = color, style = MarksyType.Meta)
        else MetaAction(line, color, onRemove)
    }
}

@Composable
private fun SecretField(draft: UpstoxDraft, field: UpstoxField, placeholder: String, onEdit: (UpstoxField, String) -> Unit, secret: Boolean = true) {
    var shown by remember { mutableStateOf(false) }
    CompactTextField(
        value = draft.text(field),
        onValueChange = { onEdit(field, it) },
        modifier = Modifier.fillMaxWidth(),
        label = if (field in draft.fromQr) "${field.label} · from QR" else field.label,
        placeholder = placeholder,
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri, autoCorrectEnabled = false),
        trailing = if (secret) {
            { MarksyButton(if (shown) "Hide" else "Show", { shown = !shown }, style = MarksyButtonStyle.Text) }
        } else null
    )
}

@Composable
private fun MetaAction(text: String, color: Color, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, style = MarksyType.Meta, modifier = Modifier.weight(1f))
        MarksyButton("Remove", onRemove, style = MarksyButtonStyle.Text, color = MarksyTheme.Negative)
    }
}

@Composable
private fun ImportOption(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    MarksyCard(Modifier.fillMaxWidth(), onClick = onClick, onClickLabel = title) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MarksySpace.CardPadding)) {
            Icon(icon, contentDescription = null, tint = MarksyTheme.PrimaryEmerald)
            Column {
                Text(title, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
                Text(detail, color = MarksyTheme.TextMuted, style = MarksyType.Meta)
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: Notice) {
    val (border, title, detail) = when (notice) {
        is Notice.Filled -> Triple(MarksyTheme.PrimaryEmerald, "Filled from QR: ${notice.fields.sortedBy { it.ordinal }.joinToString { it.label }}.", "Check the fields, then Save & test. Nothing is saved yet.")
        is Notice.Refused -> Triple(MarksyTheme.Negative, "Not imported. ${notice.message}", "Nothing was changed.")
        is Notice.Done -> Triple(if (notice.ok) MarksyTheme.PrimaryEmerald else MarksyTheme.Warning, notice.message, null)
    }
    MarksyCard(Modifier.fillMaxWidth(), border = border) {
        Text(title, color = MarksyTheme.TextPrimary, style = MarksyType.Body, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it, color = MarksyTheme.TextSecondary, style = MarksyType.Small) }
    }
}

private fun fullDay(ms: Long): String = MarksyFormat.fullDay(Instant.ofEpochMilli(ms).atZone(IST))

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

private fun sandboxLine(s: UpstoxSandboxStatus): Pair<String, Color> {
    val day = s.expiresAt?.let(::fullDay).orEmpty()
    return when (s.state) {
        UpstoxSandboxStatus.State.NONE -> "No sandbox token. Practice orders are off." to MarksyTheme.TextMuted
        UpstoxSandboxStatus.State.VALID -> "Expires $day · ${days(s.daysLeft)} left." to MarksyTheme.PrimaryEmerald
        UpstoxSandboxStatus.State.EXPIRING -> "Expires $day · ${days(s.daysLeft)} left. Make a new sandbox token and import it." to MarksyTheme.Warning
        UpstoxSandboxStatus.State.EXPIRED -> "Expired $day. Practice orders are off; live orders stay off too." to MarksyTheme.Negative
    }
}

private fun saveMessage(r: UpstoxSettingsSaver.Result): String = buildList {
    add(
        when (r.analytics) {
            TokenCheck.OK -> "Saved. The Analytics Token works."
            TokenCheck.UNREACHABLE -> "Saved. Couldn't reach Upstox to test the Analytics Token; Home will retry."
            TokenCheck.REJECTED -> if (r.saved.isEmpty()) "Upstox rejected the Analytics Token, so nothing was saved." else "Saved, except the Analytics Token: Upstox rejected it."
            null -> "Saved on this phone."
        }
    )
    if (r.signInDropped) add("The app keys changed, so sign in to Upstox again for today's portfolio.")
}.joinToString(" ")

private suspend fun checkAnalyticsToken(token: String): TokenCheck = try {
    UpstoxApiClient { token }.ltp(listOf(UpstoxIndices.NIFTY_50))
    TokenCheck.OK
} catch (e: UpstoxAuthException) {
    TokenCheck.REJECTED
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    TokenCheck.UNREACHABLE
}

private val QR_STEPS = listOf(
    "Upstox Developer Apps › your one live app. Redirect URL ${UpstoxOAuth.DEFAULT_REDIRECT}. Copy the API key and secret. Don't create a second live app: Upstox keeps only the newest.",
    "Analytics Token › Generate. Read-only, valid for a year.",
    "Sandbox token: only after Upstox confirms a sandbox app can't remove your live app (see docs/UPSTOX-SETUP.md). Valid for 30 days.",
    "On your computer, offline: python tools/make_upstox_qr.py. It asks for each secret without showing it and saves upstox-qr.png in your home folder.",
    "Here: Import from QR, check the fields, Save & test. Then delete the PNG."
)

private val QR_FORMAT = """
{
  "type": "marksy-upstox",
  "v": 1,
  "api_key": "…",
  "api_secret": "…",
  "redirect_uri": "${UpstoxOAuth.DEFAULT_REDIRECT}",
  "analytics_token": "…",
  "sandbox_token": "…"
}
""".trimIndent()

private const val QR_RULES = "type and v are required. Leave out what you don't want to change. Empty values, extra fields, repeated fields and access tokens are refused, and so is a code over 4 KB."

private const val QR_WARNING = "The QR is not encrypted. Anyone with the image can read these keys. Treat it like a password file: never use an online QR site, keep it out of cloud photo backups, and delete it after importing."

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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MarksySpace.Gap)) {
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
