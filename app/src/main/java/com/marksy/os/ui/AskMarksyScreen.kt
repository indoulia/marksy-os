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

private val promptOptions = listOf(
    "💡 What happened today?",
    "💬 Summarize my WhatsApp",
    "📈 Show trading opportunities",
    "✉️ Any important emails?",
    "🔍 What did I miss?"
)

@Composable
fun AskMarksyScreen(
    padding: PaddingValues,
    onPromptSelected: (String) -> Unit = {}
) {
    var selectedPrompt by remember { mutableStateOf<String?>(null) }
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
            item {
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
                    "I've analyzed your notifications.\nHow can I help?",
                    color = MarksyTheme.TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(10.dp))
            }

            items(promptOptions) { prompt ->
                Card(
                    onClick = {
                        selectedPrompt = prompt
                        onPromptSelected(prompt)
                    },
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
        }

        // Bottom Voice & Input Action Controls Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MarksyTheme.Surface)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { }) {
                Icon(
                    Icons.Default.Keyboard,
                    contentDescription = "Keyboard",
                    tint = MarksyTheme.TextSecondary
                )
            }

            // Microphone Button
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MarksyTheme.PrimaryEmerald,
                                MarksyTheme.AccentGreen
                            )
                        )
                    )
                    .clickable { selectedPrompt = "Voice input" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Tap to speak",
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                )
            }

            IconButton(onClick = { }) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Camera",
                    tint = MarksyTheme.TextSecondary
                )
            }
        }
    }

    selectedPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { selectedPrompt = null },
            title = { Text("Ask Marksy", color = MarksyTheme.TextPrimary) },
            text = {
                Column {
                    Text(prompt, fontWeight = FontWeight.SemiBold, color = MarksyTheme.TextPrimary)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Your question is queued. Marksy OS processes and connects your real-time notification intelligence.",
                        color = MarksyTheme.TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedPrompt = null }) {
                    Text("Got it", color = MarksyTheme.PrimaryEmerald)
                }
            },
            containerColor = MarksyTheme.SurfaceRaised
        )
    }
}
