package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pull-to-refresh trigger for remote-backed screens. Use [key] as a `produceState` key: its
 * value survives a key change, so the old data stays on screen while the refetch runs.
 */
@Stable
class RefreshState {
    var key by mutableIntStateOf(0)
        private set
    var refreshing by mutableStateOf(false)
        private set

    fun refresh() { refreshing = true; key++ }
    fun done() { refreshing = false }
}

@Composable
fun rememberRefreshState(): RefreshState = remember { RefreshState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarksyRefreshBox(state: RefreshState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = state::refresh,
        modifier = modifier,
        state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = state.refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MarksyTheme.SurfaceRaised,
                color = MarksyTheme.PrimaryEmerald
            )
        },
        content = content
    )
}

/** First-load placeholder for remote data: a small spinner plus what is being fetched. */
@Composable
fun MarksyLoader(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = MarksyTheme.PrimaryEmerald, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = MarksyTheme.TextMuted, fontSize = 13.sp)
    }
}

/** Inline variant for a loader inside an existing card row. */
@Composable
fun MarksyInlineLoader(label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), color = MarksyTheme.PrimaryEmerald, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = MarksyTheme.TextSecondary, fontSize = 13.sp)
    }
}
