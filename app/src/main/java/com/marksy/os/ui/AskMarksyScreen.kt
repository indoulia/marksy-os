package com.marksy.os.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.AskMarksy
import kotlinx.coroutines.launch
import com.marksy.os.gateway.MarketState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val promptOptions = listOf(
    "💡 What happened today?",
    "💬 Summarize my WhatsApp",
    "📈 Show trading opportunities",
    "✉️ Any important emails?",
    "🔍 What did I miss?",
    "💳 What payments did I make this week?",
    "📦 What deliveries are coming tomorrow?",
    "🧾 Which bills are due?"
)

/**
 * Intents answered by the EPIC-016 grounded engine (typed retrieval over all stored rows incl. archived,
 * duplicate folding, provenance, follow-ups). Everything else keeps the gateway engine, which also
 * knows the Marksy market snapshot and does named-app / fuzzy search.
 */
private val GROUNDED_INTENTS = setOf(AskMarksy.Intent.PAYMENTS, AskMarksy.Intent.DELIVERIES, AskMarksy.Intent.BILLS_DUE, AskMarksy.Intent.FROM_PERSON)

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
    loadEvent: suspend (Long) -> NotificationEventEntity? = { null }
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
        if (grounded == null || intent !in GROUNDED_INTENTS) {
            conversation += AskExchange(clean, AskMarksyEngine.answer(clean, events, (market as? MarketState.Loaded)?.snapshot))
            return
        }
        conversation += AskExchange(clean, AskMarksyEngine.Answer("Looking through your notifications…"), pending = true)
        val index = conversation.lastIndex
        scope.launch {
            val result = runCatching { grounded(clean, previous) }.getOrNull()
            val exchange = if (result == null) {
                // Grounded retrieval failed: fall back to the local engine rather than show nothing.
                AskExchange(clean, AskMarksyEngine.answer(clean, events, (market as? MarketState.Loaded)?.snapshot))
            } else {
                val byId = events.associateBy { it.id }
                val shown = result.items.take(5).mapNotNull { byId[it.eventId] ?: loadEvent(it.eventId) }
                AskExchange(clean, AskMarksyEngine.Answer(result.headline, shown), grounded = result)
            }
            if (index < conversation.size) conversation[index] = exchange
        }
    }

    LaunchedEffect(conversation.size) {
        if (conversation.isNotEmpty()) listState.animateScrollToItem(conversation.size)
    }

    val voice = rememberVoiceInput(
        onPartial = { input = it },
        onFinal = { ask(it) },
        onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    )
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start()
        else Toast.makeText(context, "Microphone permission is needed for voice questions.", Toast.LENGTH_SHORT).show()
    }
    val onMic = {
        when {
            voice.listening -> voice.stop()
            !voice.available -> Toast.makeText(context, "Voice recognition isn't available on this device.", Toast.LENGTH_SHORT).show()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voice.start()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
    ) {
        Text(
            "Ask Marksy",
            color = MarksyTheme.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Greeting(compact = conversation.isNotEmpty()) }

            if (conversation.isEmpty()) {
                itemsIndexed(promptOptions) { _, prompt ->
                    Card(
                        onClick = { ask(prompt) },
                        colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(14.dp))
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(prompt, color = MarksyTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            } else {
                itemsIndexed(conversation) { _, exchange -> ExchangeView(exchange, onEventSelected, onFollowUp = ::ask) }
            }
        }

        if (conversation.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                promptOptions.forEach { prompt ->
                    Text(
                        prompt,
                        color = MarksyTheme.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MarksyTheme.Surface)
                            .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
                            .clickable { ask(prompt) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MarksyTheme.Surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactTextField(
                value = input,
                onValueChange = { input = it.take(200) },
                modifier = Modifier.weight(1f),
                placeholder = if (voice.listening) "Listening…" else "Ask about your notifications…",
                cornerRadius = 20.dp,
                trailing = if (input.isNotBlank()) {
                    {
                        IconButton(onClick = { ask(input) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(18.dp))
                        }
                    }
                } else null
            )
            Spacer(Modifier.width(10.dp))
            val micScale = if (voice.listening) 1f + voice.level * 0.25f else 1f
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .scale(micScale)
                    .clip(CircleShape)
                    .background(
                        if (voice.listening) Brush.linearGradient(listOf(MarksyTheme.RedUrgent, Color(0xFFFF7043)))
                        else Brush.linearGradient(listOf(MarksyTheme.PrimaryEmerald, MarksyTheme.AccentGreen))
                    )
                    .clickable(onClick = onMic),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (voice.listening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (voice.listening) "Stop listening" else "Speak a question",
                    tint = Color.Black,
                    modifier = Modifier.size(22.dp)
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
                    .background(Brush.radialGradient(listOf(Color(0x4000E676), Color(0x1000E5FF), Color.Transparent)))
            )
            Box(
                Modifier.size(orb * 0.7f).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF00FF87), Color(0xFF00E5FF), Color(0xFF07241A), Color.Black)))
                    .border(2.dp, MarksyTheme.PrimaryEmerald, CircleShape)
            )
        }
        if (!compact) {
            Spacer(Modifier.height(10.dp))
            Text("Hi! I'm Marksy.", color = MarksyTheme.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                "Ask about your notifications, emails, messages or markets.",
                color = MarksyTheme.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ExchangeView(exchange: AskExchange, onEventSelected: (NotificationEventEntity) -> Unit, onFollowUp: (String) -> Unit = {}) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            exchange.question,
            color = Color.Black,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.End)
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(MarksyTheme.PrimaryEmerald)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(MarksyTheme.Surface)
                .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .padding(12.dp)
        ) {
            Text(exchange.answer.text, color = MarksyTheme.TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
            exchange.answer.events.forEach { event ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MarksyTheme.SurfaceRaised)
                        .clickable { onEventSelected(event) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(event.title.ifBlank { "Notification" }, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        event.body.lines().lastOrNull { it.isNotBlank() }?.let {
                            Text(it.trim(), color = MarksyTheme.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            event.sourceName.ifBlank { "System" } + " · " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.postedAt)),
                            color = MarksyTheme.TextMuted,
                            fontSize = 10.sp
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
            exchange.grounded?.let { g ->
                val n = g.derivedFromEventIds.size
                Text(
                    "Based on $n local notification${if (n == 1) "" else "s"} · ${g.query.range.label}" + if (n > exchange.answer.events.size) " · showing ${exchange.answer.events.size}" else "",
                    color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)
                )
                if (g.followUps.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.followUps.forEach { f ->
                        Text(
                            f, color = MarksyTheme.TextSecondary, fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MarksyTheme.SurfaceRaised).clickable { onFollowUp(f) }.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
