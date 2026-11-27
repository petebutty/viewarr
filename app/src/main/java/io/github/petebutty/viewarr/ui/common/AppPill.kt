package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.NoScale
import io.github.petebutty.viewarr.ui.theme.Tokens

/**
 * The one pill / tab (filters, seasons, quick picks, library Sort / Filter buttons, detail tabs…): fully round, never
 * grows (so it's the same with List scrolling on Instant or Smooth, and never cut off at a strip's edge).
 * Highlighted: accent fill, OnAccent text. [selected] (the chosen one, not highlighted): [Tokens.SelectedTint] and
 * semibold text. [carried]: being moved (Modifier.reorderable): a white ring. [outlined]: a thin accent edge (an
 * active filter chip). Put "✕", "⌕" etc. in [text].
 */
@Composable
fun AppPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    carried: Boolean = false,
    enabled: Boolean = true,
    outlined: Boolean = false,
    height: Dp = 34.dp,
) {
    // Previewarr: a selected pill (the tab that's showing, a filter that's on) keeps the plain colour and gets a thin
    // white edge. The shared tint, a muted highlight colour, read there as the highlight itself (2026-11-26: "always
    // looks highlighted but it's not"). The highlight colour then means only where the remote is.
    val edged = selected && io.github.petebutty.viewarr.ui.launcher.LauncherLook.on
    val ring = when {
        carried -> Border(BorderStroke(3.dp, Color.White), shape = Tokens.PillShape)
        edged -> Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.9f)), shape = Tokens.PillShape)
        outlined -> Border(BorderStroke(1.dp, AppColors.Accent.copy(alpha = 0.6f)), shape = Tokens.PillShape)
        else -> Border.None
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.height(height),
        scale = NoScale,
        shape = ClickableSurfaceDefaults.shape(Tokens.PillShape),
        border = ClickableSurfaceDefaults.border(border = ring, focusedBorder = ring, pressedBorder = ring),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected && !edged) Tokens.SelectedTint else Tokens.PillIdle,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
            pressedContainerColor = AppColors.Accent,
            pressedContentColor = AppColors.OnAccent,
        ),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}
