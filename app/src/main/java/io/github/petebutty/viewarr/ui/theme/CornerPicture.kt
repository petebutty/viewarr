package io.github.petebutty.viewarr.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The picture in the corner (live TV over another page, or a film): Settings → Playback → Corner size and Corner.
 * One place for its size, so the picture, its highlight and search's window over live TV all agree.
 */
object CornerPicture {
    /** Width and height: Medium, Small or Large. */
    fun size(): Pair<Dp, Dp> = when (AppColors.palette.opt(UiOpts.MINI_SIZE)) { 1 -> 320.dp to 180.dp; 2 -> 512.dp to 288.dp; else -> 400.dp to 225.dp }
    /** Which corner: 0 bottom right, 1 bottom left, 2 top right, 3 top left. */
    fun corner(): Int = AppColors.palette.opt(UiOpts.MINI_CORNER)
}
