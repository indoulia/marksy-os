package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bottom padding lists need so their last item clears the floating one-hand buttons. */
val OneHandListBottomPadding = 120.dp

private val FloatingButtonSize = 44.dp
private val FloatingIconSize = 22.dp

/**
 * Thumb-reachable search/filter, bottom-right: filter above search. Search opens a multi-line field along the
 * bottom (above the keyboard); filter opens a list just above the buttons. Place inside a full-screen Box after
 * the content; that Box should consumeWindowInsets(its padding) so the field sits flush on the keyboard.
 */
@Composable
fun BoxScope.OneHandControls(
    filters: List<Pair<String, String>>,
    selectedFilter: String,
    onFilterSelected: (String) -> Unit,
    searchQuery: String? = null,
    onSearchChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "Search...",
    actions: List<FloatingAction> = emptyList()
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val filterActive = filters.isNotEmpty() && selectedFilter != filters.first().first
    val searchActive = !searchQuery.isNullOrEmpty()

    // Tapping outside the open filter list dismisses it.
    if (filtersOpen) {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { filtersOpen = false }
        )
    }

    Column(
        Modifier.align(Alignment.BottomEnd).fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (filtersOpen) {
            Column(
                Modifier
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(MarksyTheme.SurfaceRaised)
                    .border(1.dp, MarksyTheme.PrimaryEmerald, RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                filters.forEach { (key, label) ->
                    val selected = key == selectedFilter
                    Text(
                        label,
                        color = if (selected) Color.Black else MarksyTheme.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MarksyTheme.PrimaryEmerald else Color.Transparent)
                            .clickable { onFilterSelected(key); filtersOpen = false }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }
        }
        if (filters.isNotEmpty()) {
            // With a filter applied the button becomes X: one tap closes the list and resets the filter.
            val showClose = filtersOpen || filterActive
            FloatingRoundButton(if (showClose) Icons.Default.Close else Icons.Default.FilterList, if (filterActive) "Clear filter" else "Filters", false) {
                if (filterActive) { onFilterSelected(filters.first().first); filtersOpen = false } else filtersOpen = !filtersOpen
                searchOpen = false
            }
        }
        if (onSearchChange != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (searchOpen) {
                    SearchField(searchQuery.orEmpty(), onSearchChange, searchPlaceholder, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                }
                FloatingRoundButton(if (searchOpen) Icons.Default.Close else Icons.Default.Search, if (searchOpen) "Close search" else "Search", searchActive && !searchOpen) {
                    searchOpen = !searchOpen
                    filtersOpen = false
                }
            }
        }
        actions.forEach { action ->
            FloatingRoundButton(action.icon, action.label, false) { filtersOpen = false; action.onClick() }
        }
    }
}

/** A page action on the floating stack (e.g. Add), so pages need no in-content button rows. */
data class FloatingAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val onClick: () -> Unit)

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        maxLines = 4,
        textStyle = TextStyle(color = MarksyTheme.TextPrimary, fontSize = 15.sp),
        cursorBrush = SolidColor(MarksyTheme.PrimaryEmerald),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier.focusRequester(focus),
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = 52.dp)
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .background(MarksyTheme.SurfaceRaised)
                    .border(1.5.dp, MarksyTheme.PrimaryEmerald, shape)
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (value.isEmpty()) Text(placeholder, color = MarksyTheme.TextMuted, fontSize = 15.sp)
                    inner()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * Always-visible bottom-right option buttons (e.g. Morning / Evening / Overnight), same size and
 * placement as the filter/search buttons. The selected one is solid emerald; the rest are outlined.
 */
@Composable
fun BoxScope.OneHandToggleButtons(options: List<Triple<String, ImageVector, String>>, selected: String, onSelected: (String) -> Unit) {
    Column(
        Modifier.align(Alignment.BottomEnd).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        options.forEach { (key, icon, label) ->
            val on = key == selected
            Box(
                Modifier
                    .size(FloatingButtonSize)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (on) MarksyTheme.PrimaryEmerald else MarksyTheme.SurfaceRaised)
                    .border(1.5.dp, MarksyTheme.PrimaryEmerald, CircleShape)
                    .clickable(onClickLabel = label) { onSelected(key) },
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = if (on) Color.Black else MarksyTheme.PrimaryEmerald, modifier = Modifier.size(FloatingIconSize))
            }
        }
    }
}

/** Solid emerald so it stands out over list content; a dot marks an applied search/filter. */
@Composable
private fun FloatingRoundButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Box {
        Box(
            Modifier
                .size(FloatingButtonSize)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(MarksyTheme.PrimaryEmerald)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.Black, modifier = Modifier.size(FloatingIconSize))
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MarksyTheme.YellowImportant)
                    .border(2.dp, MarksyTheme.Background, CircleShape)
            )
        }
    }
}
