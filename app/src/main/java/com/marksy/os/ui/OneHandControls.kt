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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/** Bottom padding lists need so their last item clears the floating one-hand buttons. */
val OneHandListBottomPadding = 120.dp

private val FloatingButtonSize = MarksySize.Touch

/** Bottom padding that lets a list's last item scroll clear of a stack of [buttons] floating buttons, plus 16dp. */
fun oneHandStackBottomPadding(buttons: Int): androidx.compose.ui.unit.Dp =
    14.dp + FloatingButtonSize * buttons + 10.dp * (buttons - 1).coerceAtLeast(0) + 16.dp

/** Extra end padding for a row's right-edge badge (inside the 18dp list and 12dp card insets) to sit left of the button column. */
val OneHandRowEndClearance = 34.dp
private val FloatingIconSize = 22.dp

/**
 * Thumb-reachable search/filter, bottom-right: filter above search. Search opens the app's one search field along
 * the bottom (above the keyboard); filter opens a list just above the buttons. Place inside a full-screen Box after
 * the content; that Box should consumeWindowInsets(its padding) so the field sits flush on the keyboard.
 */
@Composable
fun BoxScope.OneHandControls(
    filters: List<Pair<String, String>>,
    selectedFilter: String,
    onFilterSelected: (String) -> Unit,
    searchQuery: String? = null,
    onSearchChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "Search…",
    // Ticker entry: capitals, no autocorrect.
    searchSymbols: Boolean = false,
    // Keyboard search key; the field closes after it.
    onSearchSubmit: (() -> Unit)? = null,
    // The field closes when this changes (e.g. a stock was picked).
    searchResetKey: Any? = null,
    actions: List<FloatingAction> = emptyList(),
    // False when the view list is a switcher, not a filter: the button's dot then marks only [extrasActive].
    filterIsView: Boolean = true,
    extrasActive: Boolean = false,
    filterExtras: (@Composable () -> Unit)? = null
) {
    var searchOpen by rememberSaveable(searchResetKey) { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val filterActive = filters.isNotEmpty() && (if (filterIsView) selectedFilter != filters.first().first else extrasActive)
    val searchActive = !searchQuery.isNullOrEmpty()

    // Tapping outside the open filter list dismisses it.
    if (filtersOpen) {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { filtersOpen = false }
        )
    }

    Column(
        Modifier.align(Alignment.BottomEnd).fillMaxWidth().imePadding().padding(horizontal = MarksySpace.Wide, vertical = MarksySpace.Section),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        if (filtersOpen) FloatingMenuPanel(filters, selectedFilter, Alignment.End, filterExtras) { onFilterSelected(it); filtersOpen = false }
        if (filters.isNotEmpty()) {
            // Always opens the list so filters switch in place; the dot marks one applied, the list's default resets it.
            FloatingRoundButton(if (filtersOpen) Icons.Default.Close else Icons.Default.FilterList, if (filtersOpen) "Close filters" else "Filters", filterActive && !filtersOpen) {
                filtersOpen = !filtersOpen
                searchOpen = false
            }
        }
        if (onSearchChange != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (searchOpen) {
                    MarksySearchField(searchQuery.orEmpty(), onSearchChange, searchPlaceholder, Modifier.weight(1f), searchSymbols, onSearchSubmit?.let { s -> { s(); searchOpen = false } })
                    Spacer(Modifier.width(MarksySpace.ListGap))
                }
                FloatingRoundButton(if (searchOpen) Icons.Default.Close else Icons.Default.Search, if (searchOpen) "Close search" else "Search", searchActive && !searchOpen) {
                    searchOpen = !searchOpen
                    filtersOpen = false
                }
            }
        }
        actions.forEach { action ->
            FloatingRoundButton(action.icon, action.label, action.active) { filtersOpen = false; action.onClick() }
        }
    }
}

/** Bottom-left twin of the filter button, for a page's second switcher (e.g. which watchlist). */
@Composable
fun BoxScope.OneHandQuickMenu(options: List<Pair<String, String>>, selected: String?, onSelected: (String) -> Unit, icon: ImageVector, label: String, action: FloatingAction? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = false })
    }
    Column(
        Modifier.align(Alignment.BottomStart).padding(horizontal = MarksySpace.Wide, vertical = MarksySpace.Section),
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
    ) {
        if (open) FloatingMenuPanel(options, selected, Alignment.Start, action?.let { a -> { MenuActionRow(a) { open = false; a.onClick() } } }) { onSelected(it); open = false }
        FloatingRoundButton(if (open) Icons.Default.Close else icon, label, false) { open = !open }
    }
}

@Composable
private fun MenuActionRow(action: FloatingAction, onClick: () -> Unit) {
    Row(
        Modifier.clip(MarksyShape.Pill).clickable(onClick = onClick).padding(horizontal = MarksySpace.Gutter, vertical = MarksySpace.ListGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(action.icon, contentDescription = null, tint = MarksyTheme.PrimaryEmerald, modifier = Modifier.size(MarksySize.Icon))
        Spacer(Modifier.width(MarksySpace.Gap))
        Text(action.label, color = MarksyTheme.PrimaryEmerald, style = MarksyType.Subhead)
    }
}

@Composable
private fun FloatingMenuPanel(options: List<Pair<String, String>>, selected: String?, align: Alignment.Horizontal, extras: (@Composable () -> Unit)? = null, onPick: (String) -> Unit) {
    Column(
        Modifier
            .shadow(10.dp, MarksyShape.Panel)
            .clip(MarksyShape.Panel)
            .background(MarksyTheme.SurfaceRaised)
            .border(MarksySpace.Border, MarksyTheme.PrimaryEmerald, MarksyShape.Panel)
            .verticalScroll(rememberScrollState())
            .padding(MarksySpace.Inner),
        horizontalAlignment = align,
        verticalArrangement = Arrangement.spacedBy(MarksySpace.Hair)
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(
                label,
                color = if (on) MarksyTheme.OnAccent else MarksyTheme.TextPrimary,
                style = MarksyType.Subhead,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(MarksyShape.Pill)
                    .background(if (on) MarksyTheme.PrimaryEmerald else Color.Transparent)
                    .clickable { onPick(key) }
                    .padding(horizontal = MarksySpace.Gutter, vertical = MarksySpace.ListGap)
            )
        }
        extras?.invoke()
    }
}

/** A page action on the floating stack (e.g. Add), so pages need no in-content button rows. */
data class FloatingAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val active: Boolean = false, val onClick: () -> Unit)

/** The app's one search field; [lifted] adds the floating buttons' shadow, off inside a popup. */
@Composable
internal fun MarksySearchField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    symbols: Boolean = false, onSubmit: (() -> Unit)? = null, lifted: Boolean = true, autoFocus: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MarksyType.Lead.copy(color = MarksyTheme.TextPrimary, fontWeight = FontWeight.Normal),
        cursorBrush = SolidColor(MarksyTheme.PrimaryEmerald),
        keyboardOptions = if (symbols) KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Search)
            else KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit?.invoke() }),
        modifier = modifier.focusRequester(focus),
        decorationBox = { inner ->
            // Same height and lift as the round buttons beside it.
            Row(
                Modifier
                    .height(FloatingButtonSize)
                    .shadow(if (lifted) 6.dp else 0.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MarksyTheme.SurfaceRaised)
                    .border(1.5.dp, MarksyTheme.PrimaryEmerald, CircleShape)
                    .padding(start = MarksySpace.Wide, end = MarksySpace.Tight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = MarksyTheme.TextMuted, style = MarksyType.Lead, fontWeight = FontWeight.Normal)
                    inner()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = MarksyTheme.TextSecondary, modifier = Modifier.size(MarksySize.Icon))
                    }
                }
                trailing?.invoke()
            }
        }
    )
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * Always-visible bottom-right option buttons (e.g. Morning / Evening / Overnight), same size and
 * placement as the filter/search buttons. The selected one is solid emerald; the rest are outlined.
 */
@Composable
fun BoxScope.OneHandToggleButtons(options: List<Triple<String, ImageVector, String>>, selected: String, onSelected: (String) -> Unit) {
    Column(
        Modifier.align(Alignment.BottomEnd).padding(horizontal = MarksySpace.Wide, vertical = MarksySpace.Section),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(MarksySpace.ListGap)
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
                Icon(icon, contentDescription = label, tint = if (on) MarksyTheme.OnAccent else MarksyTheme.PrimaryEmerald, modifier = Modifier.size(FloatingIconSize))
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
            Icon(icon, contentDescription = label, tint = MarksyTheme.OnAccent, modifier = Modifier.size(FloatingIconSize))
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
