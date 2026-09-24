package com.marksy.os.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import com.marksy.os.intelligence.AskMarksy
import kotlinx.coroutines.launch

// Starter questions the grounded engine (EPIC-016) actually answers from local data.
private val promptOptions = listOf(
    "What are the important things today?",
    "What payments did I make this week?",
    "What deliveries are coming tomorrow?",
    "Which bills are due?",
    "What did I miss?"
)

private data class Turn(val question: String, val answer: AskMarksy.Answer?)

@Composable
fun AskMarksyScreen(
    padding: PaddingValues,
    ask: suspend (String, AskMarksy.Query?) -> AskMarksy.Answer = { _, _ -> error("Ask Marksy is not connected") },
    onOpenEvent: (Long) -> Unit = {}
) {
    val turns = remember { mutableStateListOf<Turn>() }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        input = ""
        busy = true
        val previous = turns.lastOrNull()?.answer?.query
        turns += Turn(q, null)
        val index = turns.lastIndex
        scope.launch {
            val answer = runCatching { ask(q, previous) }.getOrNull()
            turns[index] = Turn(q, answer ?: failedAnswer(q))
            busy = false
        }
    }
    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulse")
    val orbScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MarksyTheme.Background)
            .padding(padding)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Ask Marksy",
                color = MarksyTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MarksyTheme.Surface)
                    .border(1.dp, MarksyTheme.BorderGlow, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "Help",
                    tint = MarksyTheme.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (turns.isEmpty()) item {
                Spacer(Modifier.height(10.dp))
                
                // Animated 3D Glowing AI Orb Sphere
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(170.dp)
                        .scale(orbScale)
                ) {
                    // Outer Pulse Ring
                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0x4000E676),
                                        Color(0x1000E5FF),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    // Inner Glow Sphere
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF00FF87),
                                        Color(0xFF00E5FF),
                                        Color(0xFF07241A),
                                        Color.Black
                                    )
                                )
                            )
                            .border(2.dp, MarksyTheme.PrimaryEmerald, CircleShape)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "Hi! I'm Marksy.",
                    color = MarksyTheme.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ask about your notifications.\nAnswers come only from data on this device.",
                    color = MarksyTheme.TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(10.dp))
            }

            if (turns.isEmpty()) items(promptOptions) { prompt ->
                Card(
                    onClick = { submit(prompt) },
                    colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            prompt,
                            color = MarksyTheme.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MarksyTheme.TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            items(turns.size) { i ->
                val turn = turns[i]
                AnswerCard(turn, onOpenEvent = onOpenEvent, onFollowUp = ::submit)
            }
        }

        // Text input only: voice and camera were placeholders with no implementation behind them.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MarksyTheme.Surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about payments, deliveries, people...", color = MarksyTheme.TextMuted, fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit(input) }),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MarksyTheme.Background,
                    unfocusedContainerColor = MarksyTheme.Background,
                    focusedBorderColor = MarksyTheme.PrimaryEmerald,
                    unfocusedBorderColor = MarksyTheme.BorderGlow,
                    focusedTextColor = MarksyTheme.TextPrimary,
                    unfocusedTextColor = MarksyTheme.TextPrimary
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { submit(input) }, enabled = input.isNotBlank() && !busy) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask", tint = MarksyTheme.PrimaryEmerald)
            }
        }
    }
}

private fun failedAnswer(q: String) = AskMarksy.Answer(
    query = AskMarksy.Query(AskMarksy.Intent.SEARCH, AskMarksy.TimeRange(0, 0, ""), rawText = q),
    headline = "I couldn't read your local data just now. Please try again.",
    items = emptyList(), derivedFromEventIds = emptyList(), followUps = emptyList(), noResult = true, interpretedBy = "error"
)

@Composable
private fun AnswerCard(turn: Turn, onOpenEvent: (Long) -> Unit, onFollowUp: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(turn.question, color = MarksyTheme.PrimaryEmerald, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val answer = turn.answer
        if (answer == null) {
            Text("Looking through your notifications...", color = MarksyTheme.TextMuted, fontSize = 13.sp)
            return@Column
        }
        Text(answer.headline, color = MarksyTheme.TextPrimary, fontSize = 14.sp)
        answer.items.forEach { item ->
            Card(
                onClick = { onOpenEvent(item.eventId) },
                colors = CardDefaults.cardColors(containerColor = MarksyTheme.Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, MarksyTheme.BorderGlow, RoundedCornerShape(12.dp))
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(item.title, color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        "${item.source} · ${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.postedAt))}",
                        color = MarksyTheme.TextMuted, fontSize = 11.sp
                    )
                    item.detail?.let { Text(it, color = MarksyTheme.TextSecondary, fontSize = 12.sp, maxLines = 2) }
                }
            }
        }
        if (answer.derivedFromEventIds.size > answer.items.size) {
            Text("Showing ${answer.items.size} of ${answer.derivedFromEventIds.size}", color = MarksyTheme.TextMuted, fontSize = 11.sp)
        }
        if (!answer.noResult || answer.followUps.isNotEmpty()) {
            Text(
                "Based on ${answer.derivedFromEventIds.size} local notification${if (answer.derivedFromEventIds.size == 1) "" else "s"} · ${answer.query.range.label}",
                color = MarksyTheme.TextMuted, fontSize = 10.sp
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            answer.followUps.forEach { f ->
                AssistChip(onClick = { onFollowUp(f) }, label = { Text(f, fontSize = 11.sp) })
            }
        }
    }
}
