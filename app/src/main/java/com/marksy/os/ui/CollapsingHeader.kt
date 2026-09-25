package com.marksy.os.ui

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/** A header that scrolls away with the page content and comes back on scroll-up. */
@Stable
class CollapsingHeaderState {
    private val hidden = mutableFloatStateOf(0f)
    internal var maxPx = 0f

    fun reset() { hidden.floatValue = 0f }

    internal val hiddenPx: Float get() = hidden.floatValue

    private fun consume(dy: Float): Float {
        val old = hidden.floatValue
        val new = (old - dy).coerceIn(0f, maxPx)
        hidden.floatValue = new
        return old - new
    }

    // Parents see onPreScroll before the scrolling list (and before pull-to-refresh), so the
    // header moves first in both directions and pull-to-refresh only sees what's left over.
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource) = Offset(0f, consume(available.y))

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val current = hidden.floatValue
            if (current > 0f && current < maxPx) {
                val target = if (current > maxPx / 2) maxPx else 0f
                animate(current, target) { value, _ -> hidden.floatValue = value }
            }
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberCollapsingHeaderState(): CollapsingHeaderState = remember { CollapsingHeaderState() }

fun Modifier.collapsingHeader(state: CollapsingHeaderState): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    state.maxPx = placeable.height.toFloat()
    val visible = (placeable.height - state.hiddenPx).roundToInt().coerceIn(0, placeable.height)
    layout(placeable.width, visible) { placeable.place(0, visible - placeable.height) }
}
