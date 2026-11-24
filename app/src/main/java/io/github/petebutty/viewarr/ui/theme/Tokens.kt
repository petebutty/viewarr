package io.github.petebutty.viewarr.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults

/**
 * The app's shared sizes and shapes, so every page lines up and every highlight looks the same (UI review, Tier 3).
 * Use these instead of writing a radius, gutter or "beside the corner window" offset by hand.
 */
object Tokens {
    /** List rows (menu rows, setting rows, fixture lines…). */
    val RowRadius = 8.dp
    /** Cards and posters (2:3 posters, 16:9 cards, reviews). */
    val CardRadius = 8.dp
    val RowShape = RoundedCornerShape(RowRadius)
    val CardShape = RoundedCornerShape(CardRadius)
    /** Pills and tabs: fully round. */
    val PillShape = RoundedCornerShape(50)
    /** Round cards (cast photos, artists) and round buttons. */
    val RoundShape = CircleShape

    /** The one left (and right) page margin. */
    val Gutter = 48.dp

    /**
     * Content beside the corner window (live TV or a minimised film, theme/CornerPicture.kt, covers x 12–332, y 20–200):
     * start at [BesideWindowStart] to sit right of it.
     */
    val BesideWindowStart = 356.dp

    /** The focus ring every card / poster uses ([io.github.petebutty.viewarr.ui.common.FocusCard]). */
    val FocusBorder = 3.dp

    /** A selected (chosen, not highlighted) pill or tab: one tint everywhere. Highlighted is always the accent fill. */
    // (On a dark backing of the theme colour, so the text reads over any wallpaper or artwork. 2026-11-18:
    // the Sports pills were hard to read over the new background.)
    val SelectedTint: Color get() = AppColors.Accent.copy(alpha = 0.45f).compositeOver(AppColors.palette.shade.copy(alpha = 0.7f))
    /** A pill that's neither selected nor highlighted. */
    val PillIdle: Color get() = Color.White.copy(alpha = 0.12f).compositeOver(AppColors.palette.shade.copy(alpha = 0.7f))

    /** Pop-ups ([io.github.petebutty.viewarr.ui.common.PopupPanel]): one glass radius and three widths. */
    val PopupRadius = 18.dp
    val PopupNarrow = 360.dp
    val PopupStandard = 440.dp
    val PopupWide = 600.dp
}

/**
 * No growing and no press shrink, for any TV Surface: `scale = NoScale`. (TV Material's default grows on focus and
 * shrinks on press; Viewarr highlights by colour / border instead.)
 */
val NoScale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
