package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ButtonDefaults
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * The app's pill button (Play on a title page, the setup wizard's and updater's buttons): the TV button in the
 * theme's highlight colour when it has the highlight, like every other highlighted thing, not the stock white.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /** A little smaller (the title page's Play / Resume, beside its round buttons: 2026-11-18). */
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) = androidx.tv.material3.Button(
    onClick = if (onLongClick != null) HeldOk.guard(onClick) else onClick,
    onLongClick = onLongClick,
    modifier = modifier,
    enabled = enabled,
    contentPadding = if (compact) androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 18.dp, top = 5.dp, bottom = 5.dp) else ButtonDefaults.ContentPadding,
    colors = ButtonDefaults.colors(
        focusedContainerColor = AppColors.Accent,
        focusedContentColor = AppColors.OnAccent,
        pressedContainerColor = AppColors.Accent,
        pressedContentColor = AppColors.OnAccent,
    ),
    content = content,
)
