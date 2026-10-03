package com.marksy.os.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.AskMarksy
import kotlinx.coroutines.launch
import com.marksy.os.gateway.MarketState
import com.marksy.os.MarksyFormat
import java.time.Instant
import java.time.ZoneId

private val promptOptions = listOf(
    "💡 What happened today?",
    "💬 Summarize my WhatsApp",
    "📈 Show trading opportunities",
    "✉️ How many emails today?",
    "🗓️ What's coming up?",
    "🔍 What did I miss?",
    "💳 What payments did I make this week?",
    "📦 What deliveries are coming tomorrow?",
    "🧾 Which bills are due?",
    "❓ What can you do?"
)

/**
 * Intents answered by the EPIC-016 grounded engine (typed retrieval over all stored rows incl. archived,
 * duplicate folding, provenance, follow-ups). Everything else keeps the gateway engine, which also
 * knows the Marksy market snapshot and does named-app / fuzzy search.
 */
private val GROUNDED_INTENTS = setOf(
    AskMarksy.Intent.PAYMENTS, AskMarksy.Intent.DELIVERIES, AskMarksy.Intent.BILLS_DUE, AskMarksy.Intent.FROM_PERSON,
    AskMarksy.Intent.SOURCE, AskMarksy.Intent.PLAN, AskMarksy.Intent.STOCK, AskMarksy.Intent.NAVIGATE, AskMarksy.Intent.HELP
)

data class AskExchange(
    val question: String,
    val answer: AskMarksyEngine.Answer,
    /** Present when the grounded engine answered; carries the query for follow-ups and provenance. */
    val grounded: AskMarksy.Answer? = null,
    val pending: Boolean = false
)

@Composable
fun AskMarksyScreen(
    padding: PaddingValues,
    events: List<NotificationEventEntity> = emptyList(),
    market: MarketState = MarketState.Loading,
    onEventSelected: (NotificationEventEntity) -> Unit = {},
    // Hoisted so the conversation survives switching tabs.
    conversation: SnapshotStateList<AskExchange> = remember { mutableStateListOf() },
    askGrounded: (suspend (String, AskMarksy.Query?) -> AskMarksy.Answer)? = null,
    /** Interprets with the on-device model when ready and answers only grounded intents; null means use the gateway engine. */
    askRouted: (suspend (String, AskMarksy.Query?, Set<AskMarksy.Intent>) -> AskMarksy.Answer?)? = null,
    loadEvent: suspend (Long) -> NotificationEventEntity? = { null },
    onAction: (AskMarksy.Action) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun ask(question: String) {
        val clean = question.replace(Regex("^[^\\p{L}\\p{N}]+"), "").trim()
        if (clean.isEmpty()) return
        input = ""
        val previous = conversation.lastOrNull { it.grounded != null }?.grounded?.query
        val intent = AskMarksy.parse(clean, previous, System.currentTimeMillis(), java.time.ZoneId.systemDefault()).intent
        val grounded = askGrounded
        val routed = askRouted
        if (routed == null && (grounded == null || intent !in GROUNDED_INTENTS)) {
            conversation += AskExchange(clean, AskMarksyEngine.answer(clean, events, (market as? MarketState.Loaded)?.snapshot))
            return
        }
        conversation += AskExchange(clean, AskMarksyEngine.Answer("Looking through your notifications…"), pending = true)
        val index = conversation.lastIndex
        scope.launch {
            // Routed: the model's interpretation (not the keyword parse) decides whether the grounded engine answers.
            val result = (if (routed != null) runCatching { routed(clean, previous, GROUNDED_INTENTS) } else runCatching { grounded!!(clean, previous) })
                .onFailure { com.marksy.os.ai.DiagLog.w("MarksyAsk", "grounded answer failed: ${it.javaClass.name}") }.getOrNull()
            val exchange = if (result == null) {
                // Grounded retrieval failed: fall back to the local engine rather than show nothing.
                AskExchange(clean, AskMarksyEngine.answer(clean, events, (market as? MarketState.Loaded)?.snapshot))
            } else {
                val byId = events.associateBy { it.id }
                val shown = result.items.take(5).mapNotNull { byId[it.eventId] ?: loadEvent(it.eventId) }
                AskExchange(clean, AskMarksyEngine.Answer(result.headline + pickNote(result.action, market), shown), grounded = result)
            }
            if (index < conversation.size) conversation[index] = exchange
            exchange.grounded?.action?.takeIf { it.auto }?.let(onAction)
        }
    }

    LaunchedEffect(conversation.size) {
        if (conversation.isNotEmpty()) listState.animateScrollToItem(conversation.size)
    }

    val notice = rememberNotice()
    val voice = rememberVoiceInput(
        onPartial = { input = it },
        onFinal = { ask(it) },
        onError = { notice(it) }
    )
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start()
        else notice("Microphone permission is needed for voice questions.")
    }
    val onMic = {
        when {
            voice.listening -> voice.stop()
            !voice.available -> notice("Voice recognition isn't available on this device.")
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voice.start()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
            // The bottom bar's padding sits under the keyboard; without this it's added on top as a black gap.
            .consumeWindowInsets(padding)
            .imePadding()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = MarksySpace.Gutter, vertical = MarksySpace.Gap),
            verticalArrangement = Arrangement.spacedBy(MarksySpace.CardPadding)
        ) {
            item { Greeting(compact = conversation.isNotEmpty()) }

            if (conversation.isEmpty()) {
                itemsIndexed(promptOptions) { _, prompt ->
                    MarksyCard(onClick = { ask(prompt) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(prompt, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon))
                        }
                    }
                }
            } else {
                itemsIndexed(conversation) { _, exchange -> ExchangeView(exchange, onEventSelected, onFollowUp = ::ask, onAction = onAction) }
            }
        }

        if (conversation.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = MarksySpace.Gutter, vertical = MarksySpace.Inner),
                horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)
            ) {
                promptOptions.forEach { prompt ->
                    Pill(prompt) { ask(prompt) }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MarksyTheme.Surface)
                .padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.Gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactTextField(
                value = input,
                onValueChange = { input = it.take(200) },
                modifier = Modifier.weight(1f),
                placeholder = if (voice.listening) "Listening…" else "Ask about your notifications…",
                shape = MarksyShape.Dialog,
                trailing = if (input.isNotBlank()) {
                    {
                        IconButton(onClick = { ask(input) }, modifier = Modifier.size(MarksySize.Button)) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.Icon))
                        }
                    }
                } else null
            )
            Spacer(Modifier.width(10.dp))
            val micScale = if (voice.listening) 1f + voice.level * 0.25f else 1f
            Box(
                modifier = Modifier
                    .size(MarksySize.Touch)
                    .scale(micScale)
                    .clip(CircleShape)
                    .background(
                        if (voice.listening) Brush.linearGradient(listOf(MarksyTheme.Negative, MarksyTheme.OrangeDelivery))
                        else Brush.linearGradient(listOf(MarksyTheme.PrimaryEmerald, MarksyTheme.AccentGreen))
                    )
                    .clickable(onClick = onMic),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (voice.listening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (voice.listening) "Stop listening" else "Speak a question",
                    tint = MarksyTheme.OnAccent,
                    modifier = Modifier.size(MarksySize.IconLarge)
                )
            }
        }
    }
}

@Composable
private fun Greeting(compact: Boolean) {
    val pulse = rememberInfiniteTransition(label = "OrbPulse")
    val orbScale by pulse.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse),
        label = "OrbScale"
    )
    val orb = if (compact) 56.dp else 130.dp
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(orb).scale(orbScale)) {
            Box(
                Modifier.size(orb).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(MarksyTheme.PrimaryEmerald.copy(alpha = .25f), MarksyTheme.SecondaryCyan.copy(alpha = .06f), Color.Transparent)))
            )
            Box(
                Modifier.size(orb * 0.7f).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(MarksyTheme.PrimaryEmerald, MarksyTheme.SecondaryCyan, MarksyTheme.BadgeTradingBg, MarksyTheme.Background)))
                    .border(2.dp, MarksyTheme.PrimaryEmerald, CircleShape)
            )
        }
        if (!compact) {
            Spacer(Modifier.height(10.dp))
            Text("Hi! I'm Marksy.", color = MarksyTheme.TextPrimary, style = MarksyType.Title, textAlign = TextAlign.Center)
            Text(
                "Ask about notifications, emails, reminders or stocks, or name a page to open.",
                color = MarksyTheme.TextSecondary,
                style = MarksyType.Body,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun pickNote(action: AskMarksy.Action?, market: MarketState): String {
    if (action?.page != AskMarksy.Page.STOCK) return ""
    val pick = (market as? MarketState.Loaded)?.snapshot?.opportunities?.firstOrNull { it.symbol.equals(action.arg, ignoreCase = true) } ?: return ""
    return " Marksy pick: target ${MarksyFormat.rupees(pick.targetPrice, 0)}, stop ${MarksyFormat.rupees(pick.stopLoss, 0)}" +
        (pick.upsidePct?.let { ", upside ${MarksyFormat.percent(it, 1, signed = false)}" } ?: "") + "."
}

private val questionShape = MarksyShape.Panel.copy(bottomEnd = CornerSize(4.dp))
private val answerShape = MarksyShape.Panel.copy(bottomStart = CornerSize(4.dp))

@Composable
private fun ExchangeView(
    exchange: AskExchange, onEventSelected: (NotificationEventEntity) -> Unit, onFollowUp: (String) -> Unit = {}, onAction: (AskMarksy.Action) -> Unit = {}
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
        Text(
            exchange.question,
            color = MarksyTheme.OnAccent,
            style = MarksyType.Body,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.End)
                .widthIn(max = 280.dp)
                .clip(questionShape)
                .background(MarksyTheme.PrimaryEmerald)
                .padding(horizontal = MarksySpace.CardPadding, vertical = MarksySpace.Gap)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(answerShape)
                .background(MarksyTheme.Surface)
                .border(MarksySpace.Border, MarksyTheme.BorderGlow, answerShape)
                .padding(MarksySpace.CardPadding)
        ) {
            Text(exchange.answer.text, color = MarksyTheme.TextPrimary, style = MarksyType.Body)
            exchange.answer.events.forEach { event ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = MarksySpace.Inner)
                        .clip(MarksyShape.Chip)
                        .background(MarksyTheme.SurfaceRaised)
                        .clickable { onEventSelected(event) }
                        .padding(horizontal = MarksySpace.ListGap, vertical = MarksySpace.Inner),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(event.title.ifBlank { "Notification" }, color = MarksyTheme.TextPrimary, style = MarksyType.Small, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        event.body.lines().lastOrNull { it.isNotBlank() }?.let {
                            Text(it.trim(), color = MarksyTheme.TextSecondary, style = MarksyType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            event.sourceName.ifBlank { "System" } + " · " + MarksyFormat.time(Instant.ofEpochMilli(event.postedAt).atZone(ZoneId.systemDefault())),
                            color = MarksyTheme.TextMuted,
                            style = MarksyType.Caption
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(MarksySize.Icon))
                }
            }
            exchange.grounded?.let { g ->
                g.action?.let { a ->
                    Pill("${a.label} →", modifier = Modifier.padding(top = MarksySpace.Inner)) { onAction(a) }
                }
                val n = g.derivedFromEventIds.size
                val basis = if (g.needsClarification) "Pick one to search" else
                    "Based on $n local notification${if (n == 1) "" else "s"} · ${g.query.range.label}" + if (n > exchange.answer.events.size) " · showing ${exchange.answer.events.size}" else ""
                // Help, navigation and manual reminders aren't derived from notifications, so no basis line.
                if (n > 0 || g.noResult || g.needsClarification) Text(
                    basis + if (g.interpretedBy != AskMarksy.DeterministicInterpreter.name) " · question understood by on-device AI" else "",
                    color = MarksyTheme.TextMuted, style = MarksyType.Caption, modifier = Modifier.padding(top = MarksySpace.Inner)
                )
                if (g.followUps.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = MarksySpace.Tight), horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
                    g.followUps.forEach { f ->
                        Pill(f) { onFollowUp(f) }
                    }
                }
            }
        }
    }
}
