package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow

/** Single-line field sized to its content; M3 OutlinedTextField can't go below 56dp without clipping its text. */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
    fieldModifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier) {
        if (label != null) FieldLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            visualTransformation = visualTransformation,
            textStyle = MarksyType.Body.copy(color = MarksyTheme.TextPrimary),
            cursorBrush = SolidColor(MarksyTheme.PrimaryEmerald),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .fieldBox(focused)
                        .padding(start = MarksySpace.Control, end = if (trailing != null) MarksySpace.Tight else MarksySpace.Control),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, tint = MarksyTheme.TextSecondary, modifier = Modifier.size(MarksySize.Icon))
                        Spacer(Modifier.width(MarksySpace.Gap))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, color = MarksyTheme.TextMuted, style = MarksyType.Body, maxLines = 1)
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            }
        )
    }
}

/** A dropdown or picker trigger in the same box as [CompactTextField]: the value, or [placeholder], and a chevron. */
@Composable
fun SelectField(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    fieldModifier: Modifier = Modifier
) {
    // Wraps its value unless the caller gives it a width (fillMaxWidth, weight).
    Column(modifier.width(IntrinsicSize.Max)) {
        if (label != null) FieldLabel(label)
        Box(
            fieldModifier
                .fillMaxWidth()
                .fieldBox(focused = false)
                .clickable(onClick = onClick)
                .padding(start = MarksySpace.Control, end = MarksySpace.Gap),
            contentAlignment = Alignment.CenterStart
        ) {
            // End padding keeps a long value clear of the chevron.
            Row(Modifier.padding(end = MarksySize.Icon + MarksySpace.Tight), verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, tint = MarksyTheme.TextSecondary, modifier = Modifier.size(MarksySize.Icon))
                    Spacer(Modifier.width(MarksySpace.Gap))
                }
                Text(
                    text ?: placeholder, color = if (text == null) MarksyTheme.TextMuted else MarksyTheme.TextPrimary,
                    style = MarksyType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MarksyTheme.TextMuted, modifier = Modifier.align(Alignment.CenterEnd).size(MarksySize.Icon))
        }
    }
}

@Composable
private fun FieldLabel(label: String) =
    Text(label, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, modifier = Modifier.padding(start = MarksySpace.Tight, bottom = MarksySpace.Tight))

// Min height, not fixed, so large font scales grow the field instead of clipping its text.
private fun Modifier.fieldBox(focused: Boolean) = this
    .heightIn(min = MarksySize.Control)
    .clip(MarksyShape.Field)
    .background(MarksyTheme.Surface)
    .border(MarksySpace.Border, if (focused) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, MarksyShape.Field)
