package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * A round icon button with its name under it only while highlighted (the player's info-bar buttons and shortcuts,
 * a title page's actions). Highlighted: accent fill, OnAccent icon. [on]: a state that's set (watched, on the
 * watchlist) shows a lighter fill. [outlined]: a thin white ring (title pages). [focusedScale] > 1: grows when
 * highlighted, instantly with List scrolling on Instant. [width] / [height] / [shape]: a non-round face (shortcut
 * styles); [columnWidth]: the room the label gets. [face]: draw your own inside (a logo, an emoji…; gets whether it's
 * highlighted) instead of [icon]. [groupEnd]: a thin divider after it (call it inside a Row).
 */
@Composable
fun RoundIconButton(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    on: Boolean = false,
    outlined: Boolean = false,
    groupEnd: Boolean = false,
    width: Dp = 44.dp,
    height: Dp = width,
    columnWidth: Dp = width + 4.dp,
    shape: Shape = CircleShape,
    iconSize: Dp = width / 2,
    labelSize: TextUnit = 12.sp,
    labelGap: Dp = 4.dp,
    focusedScale: Float = 1f,
    containerColor: Color = Color.White.copy(alpha = 0.14f),
    onFocus: () -> Unit = {},
    face: (@Composable BoxScope.(focused: Boolean) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(columnWidth)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            enabled = enabled,
            modifier = modifier.size(width, height)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .then(if (focusedScale != 1f) Modifier.instantFocusScale(focusedScale) else Modifier),
            scale = focusScale(focusedScale),
            shape = ClickableSurfaceDefaults.shape(shape),
            border = if (outlined) ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.45f)), shape = shape))
                else ClickableSurfaceDefaults.border(),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (on) Color.White.copy(alpha = 0.22f) else containerColor,
                contentColor = Color.White,
                focusedContainerColor = AppColors.Accent,
                focusedContentColor = AppColors.OnAccent,
                pressedContainerColor = AppColors.Accent,
                pressedContentColor = AppColors.OnAccent,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (face != null) face(focused)
                else if (icon != null) androidx.compose.material3.Icon(
                    icon, contentDescription = label, tint = if (focused) AppColors.OnAccent else Color.White, modifier = Modifier.size(iconSize),
                )
            }
        }
        Text(
            if (focused) label else "", fontSize = labelSize, color = Color.White, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(top = labelGap).wrapContentWidth(unbounded = true),
        )
    }
    if (groupEnd) {
        val d = height / 48f
        Box(Modifier.padding(horizontal = d * 10, vertical = d * 10).width(1.dp).height(d * 28).background(Color.White.copy(alpha = 0.3f)))
    }
}
