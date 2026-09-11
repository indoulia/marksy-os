package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AskSurface = Color(0xFF101613)
private val AskPrimary = Color(0xFF72D49A)
private val AskText = Color(0xFFE8F1EC)
private val AskSecondary = Color(0xFF9AA9A1)
private val AskMuted = Color(0xFF657169)

private val prompts = listOf(
    "What changed in my trading notifications?",
    "Show me today's important trading events",
    "Why did Marksy flag this trade?",
    "What should I review before the market opens?"
)

/**
 * V1 interaction shell. It deliberately does not fabricate an AI answer.
 * The real conversation transport will be connected once the Marksy Gateway
 * request/response contract is confirmed.
 */
@Composable
fun AskMarksyScreen(
    padding: PaddingValues,
    onPromptSelected: (String) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Ask Marksy", color = AskText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Ask about information Marksy has received. Answers will appear here when the gateway conversation contract is connected.",
                color = AskSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            Text("Try a prompt", color = AskText, fontWeight = FontWeight.SemiBold)
        }
        items(prompts) { prompt ->
            Card(
                colors = CardDefaults.cardColors(containerColor = AskSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(15.dp)) {
                    Text(prompt, color = AskText, fontSize = 14.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("Ready when gateway is connected", color = AskMuted, fontSize = 11.sp)
                }
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text("No fabricated answers", color = AskPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Marksy OS will show a real Marksy response or an explicit unavailable state. It will never present demo intelligence as a live answer.",
                color = AskSecondary,
                fontSize = 12.sp
            )
        }
    }
}
