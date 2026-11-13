package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    content: @Composable RowScope.() -> Unit,
) = androidx.tv.material3.Button(
    onClick = onClick,
    onLongClick = onLongClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.colors(
        focusedContainerColor = AppColors.Accent,
        focusedContentColor = AppColors.OnAccent,
        pressedContainerColor = AppColors.Accent,
        pressedContentColor = AppColors.OnAccent,
    ),
    content = content,
)
