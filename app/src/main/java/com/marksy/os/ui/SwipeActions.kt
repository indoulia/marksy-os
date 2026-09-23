package com.marksy.os.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val ActionWidth = 52.dp
// A slight drag commits: opens the tray fully, or closes it when dragged back.
private val NudgeThreshold = 8.dp

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
    val nudgePx = with(LocalDensity.current) { NudgeThreshold.toPx() }
    val offset = remember { Animatable(0f) }
    var anchor by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    fun settle(target: Float) { anchor = target; scope.launch { offset.animateTo(target) } }
    fun close() = settle(0f)
    fun act(action: () -> Unit) { action(); anchor = 0f; scope.launch { offset.snapTo(0f) } }

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
                        val moved = offset.value - anchor
                        settle(
                            when {
                                anchor != 0f -> if (moved * anchor < 0 && abs(moved) > nudgePx) 0f else anchor
                                moved > nudgePx -> trayPx
                                moved < -nudgePx -> -trayPx
                                else -> 0f
                            }
                        )
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
    Box(
        Modifier
            .width(ActionWidth)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(30.dp))
    }
}
