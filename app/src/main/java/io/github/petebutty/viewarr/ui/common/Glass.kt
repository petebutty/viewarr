package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts

/**
 * The info bar's glass, for everything that pops up over the picture or a page (the info bar and controls, the next
 * episode, skip intro / credits, reminders, notes): see-through dark, rounded, with a thin light border.
 * Settings → Appearance → Info bar: how dark the glass is, and the border on or off. [edge]: a warning's own colour.
 */
fun Modifier.glass(radius: Dp = 18.dp, edge: Color? = null): Modifier {
    val p = AppColors.palette
    val shape = RoundedCornerShape(radius)
    // Settings → Appearance → Pop-up glass: 30% to fully solid.
    val alpha = 0.3f + 0.07f * p.opt(UiOpts.POPUP_GLASS).coerceIn(0, 10)
    val border = p.opt(UiOpts.BANNER_BORDER) == 0
    return this
        .background(AppColors.tinted(AppColors.Background).copy(alpha = alpha), shape)
        .then(if (edge != null || border) Modifier.border(1.dp, edge ?: Color.White.copy(alpha = 0.28f), shape) else Modifier)
}
