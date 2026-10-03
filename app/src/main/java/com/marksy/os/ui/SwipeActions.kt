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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
// Opening needs a deliberate swipe (distance or fling) so accidental nudges don't reveal actions; closing needs only a nudge.
private val NudgeThreshold = 8.dp
private val OpenThreshold = 64.dp
private val OpenFlingVelocity = 1200.dp

/**
 * Swipe either way to reveal Archive / Hide. Archive moves it out of active lists, Hide only drops it from the
 * current page. No swipe delete: retention cleanup removes old notifications.
 */
@Composable
fun SwipeActionsRow(
    onArchive: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) = SwipeActionsRow(
    listOf(
        SwipeTrayAction(Icons.Default.Archive, "Archive", MarksyTheme.PrimaryEmerald, onArchive),
        SwipeTrayAction(Icons.Default.VisibilityOff, "Hide", MarksyTheme.TextSecondary, onHide)
    ),
    modifier, content
)

data class SwipeTrayAction(val icon: ImageVector, val label: String, val tint: Color, val onClick: () -> Unit)

/** Swipe either way to reveal [actions]; nothing happens until one is tapped. */
@Composable
fun SwipeActionsRow(
    actions: List<SwipeTrayAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) = SwipeTray(actions, stacked = false, modifier) { content() }

/**
 * A group card that slides as one when its header is swiped, revealing [actions] in one column ([stacked]) or a row.
 * [content] puts the given modifier on its header, so rows inside keep their own swipe.
 */
@Composable
fun SwipeGroupCard(
    actions: List<SwipeTrayAction>,
    modifier: Modifier = Modifier,
    stacked: Boolean = true,
    content: @Composable (headerDrag: Modifier) -> Unit
) = SwipeTray(actions, stacked, modifier, headerOnly = true, content)

@Composable
private fun SwipeTray(
    actions: List<SwipeTrayAction>,
    stacked: Boolean,
    modifier: Modifier,
    headerOnly: Boolean = false,
    content: @Composable (headerDrag: Modifier) -> Unit
) {
    val trayPx = with(LocalDensity.current) { (ActionWidth * (if (stacked) 1 else actions.size)).toPx() }
    val density = LocalDensity.current
    val nudgePx = with(density) { NudgeThreshold.toPx() }
    val openPx = with(density) { OpenThreshold.toPx() }
    val flingPx = with(density) { OpenFlingVelocity.toPx() }
    val offset = remember { Animatable(0f) }
    var anchor by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    fun settle(target: Float) { anchor = target; scope.launch { offset.animateTo(target) } }
    fun close() = settle(0f)
    fun act(action: () -> Unit) { action(); anchor = 0f; scope.launch { offset.snapTo(0f) } }

    val drag = Modifier.draggable(
        orientation = Orientation.Horizontal,
        state = rememberDraggableState { delta ->
            scope.launch { offset.snapTo((offset.value + delta).coerceIn(-trayPx, trayPx)) }
        },
        onDragStopped = { velocity -> settle(swipeSettle(offset.value - anchor, velocity, anchor, trayPx, nudgePx, openPx, flingPx)) }
    )

    Box(modifier.fillMaxWidth()) {
        if (offset.value != 0f) {
            val tray = Modifier.matchParentSize().clip(MarksyShape.Panel).background(MarksyTheme.SurfaceRaised)
            if (stacked) Box(tray) {
                Column(
                    Modifier.align(if (offset.value > 0) Alignment.CenterStart else Alignment.CenterEnd).width(ActionWidth).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    actions.forEach { a -> StackedAction(a) { act(a.onClick) } }
                }
            } else Row(
                tray,
                horizontalArrangement = if (offset.value > 0) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { a -> SwipeAction(a.icon, a.label, a.tint) { act(a.onClick) } }
            }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.then(if (headerOnly) Modifier else drag)) {
            content(if (headerOnly) drag else Modifier)
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

/** Where a tray settles after a drag of [moved]: open on a deliberate pull or a fling; a nudge closes an open tray. */
internal fun swipeSettle(moved: Float, velocity: Float, anchor: Float, trayPx: Float, nudgePx: Float, openPx: Float, flingPx: Float): Float {
    // A one-column group tray is narrower than openPx, so it opens once pulled most of its width.
    val openAt = minOf(openPx, trayPx * 0.6f)
    return when {
        anchor != 0f -> if (moved * anchor < 0 && abs(moved) > nudgePx) 0f else anchor
        moved > openAt || (moved > nudgePx * 3 && velocity > flingPx) -> trayPx
        moved < -openAt || (moved < -nudgePx * 3 && velocity < -flingPx) -> -trayPx
        else -> 0f
    }
}

@Composable
private fun ColumnScope.StackedAction(action: SwipeTrayAction, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().weight(1f).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(action.icon, contentDescription = action.label, tint = action.tint, modifier = Modifier.size(24.dp))
    }
}
