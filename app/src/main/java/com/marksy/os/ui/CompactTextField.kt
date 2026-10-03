package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    height: Dp = 40.dp,
    shape: androidx.compose.ui.graphics.Shape = MarksyShape.Field,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
    fieldModifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier) {
        if (label != null) {
            Text(label, color = MarksyTheme.TextSecondary, style = MarksyType.Meta, modifier = Modifier.padding(start = MarksySpace.Tight, bottom = MarksySpace.Tight))
        }
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
                        .height(height)
                        .clip(shape)
                        .background(MarksyTheme.Surface)
                        .border(MarksySpace.Border, if (focused) MarksyTheme.PrimaryEmerald else MarksyTheme.BorderGlow, shape)
                        .padding(start = MarksySpace.CardPadding, end = if (trailing != null) MarksySpace.Tight else MarksySpace.CardPadding),
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
