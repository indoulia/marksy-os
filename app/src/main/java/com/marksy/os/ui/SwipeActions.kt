package com.marksy.os.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ActionWidth = 64.dp

/**
 * Swipe either way to reveal Delete / Archive / Hide. Delete is permanent, Archive moves it out of active
 * lists, Hide only drops it from the current page.
 */
@Composable
fun SwipeActionsRow(
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val trayPx = with(LocalDensity.current) { (ActionWidth * 3).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun close() = scope.launch { offset.animateTo(0f) }
    fun act(action: () -> Unit) { action(); scope.launch { offset.snapTo(0f) } }

    Box(modifier.fillMaxWidth()) {
        if (offset.value != 0f) {
            Row(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MarksyTheme.SurfaceRaised),
                horizontalArrangement = if (offset.value > 0) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SwipeAction(Icons.Default.Delete, "Delete", MarksyTheme.RedUrgent) { act(onDelete) }
                SwipeAction(Icons.Default.Archive, "Archive", MarksyTheme.PrimaryEmerald) { act(onArchive) }
                SwipeAction(Icons.Default.VisibilityOff, "Hide", MarksyTheme.TextSecondary) { act(onHide) }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-trayPx, trayPx)) }
                    },
                    onDragStopped = {
                        val target = when {
                            offset.value > trayPx / 3 -> trayPx
                            offset.value < -trayPx / 3 -> -trayPx
                            else -> 0f
                        }
                        offset.animateTo(target)
                    }
                )
        ) {
            content()
            // While open, a tap on the card closes the tray instead of opening the notification.
            if (offset.value != 0f) Box(Modifier.matchParentSize().clickable { close() })
        }
    }
}

@Composable
private fun SwipeAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .width(ActionWidth)
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
