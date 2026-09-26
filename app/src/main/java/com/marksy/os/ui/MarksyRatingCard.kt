package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.rating.Contribution
import com.marksy.os.rating.HorizonRating
import com.marksy.os.rating.RatingResult
import com.marksy.os.rating.Verdict
import java.util.Locale

private fun Verdict.color(): Color = when (this) {
    Verdict.BUY -> MarksyTheme.PrimaryEmerald
    Verdict.SELL -> MarksyTheme.RedUrgent
    Verdict.HOLD -> MarksyTheme.YellowImportant
    Verdict.NOT_ENOUGH_DATA -> MarksyTheme.TextMuted
}

/** Marksy's own Buy/Hold/Sell per horizon with the reasons; hidden when neither horizon has enough data. */
@Composable
internal fun MarksyRatingCard(result: RatingResult) {
    if (result.ratings.all { it.verdict == Verdict.NOT_ENOUGH_DATA }) return
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MarksyTheme.Surface).border(1.dp, MarksyTheme.BorderGlow, shape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Marksy rating", color = MarksyTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Marksy calculation · not investment advice", color = MarksyTheme.TextMuted, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            result.ratings.forEach { r -> Verdict(r, Modifier.weight(1f)) }
        }
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Why", color = MarksyTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(20.dp))
        }
        if (open) {
            result.ratings.filter { it.verdict != Verdict.NOT_ENOUGH_DATA }.forEach { r ->
                Text(r.horizon.label, color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Reasons("For", r.support, MarksyTheme.PrimaryEmerald)
                Reasons("Against", r.against, MarksyTheme.RedUrgent)
            }
            Text("All factors", color = MarksyTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            result.factors.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    Text(f.factor.label, color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(110.dp))
                    Text(String.format(Locale.US, "%+.2f", if (kotlin.math.abs(f.score) < .005) 0.0 else f.score), color = if (f.score >= -.005) MarksyTheme.PrimaryEmerald else MarksyTheme.RedUrgent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                    Text(f.reason, color = MarksyTheme.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
            Text("Model ${result.version}. Scores run from −1 to +1; Buy at +0.30 or more, Sell at −0.30 or less.", color = MarksyTheme.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Verdict(r: HorizonRating, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(MarksyTheme.SurfaceRaised).padding(10.dp)) {
        Text(r.horizon.label, color = MarksyTheme.TextMuted, fontSize = 11.sp)
        Text(r.verdict.label.uppercase(), color = r.verdict.color(), fontSize = if (r.verdict == Verdict.NOT_ENOUGH_DATA) 12.sp else 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 2.dp))
        if (r.verdict != Verdict.NOT_ENOUGH_DATA) Text(
            String.format(Locale.US, "Score %+.2f · confidence %d%%", r.score, Math.round(r.confidence * 100)),
            color = MarksyTheme.TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun Reasons(title: String, items: List<Contribution>, tint: Color) {
    if (items.isEmpty()) return
    items.forEach { c ->
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Text(if (c === items.first()) title else "", color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
            Text("${c.factor.label}: ${c.reason}", color = MarksyTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
        }
    }
}
