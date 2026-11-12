package io.github.petebutty.viewarr.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The picture in the corner (live TV over another page, or a film): Settings → Playback → Corner size and Corner.
 * One place for its size, so the picture, its highlight and search's window over live TV all agree.
 */
object CornerPicture {
    /** Width and height: Medium, Small or Large. */
    // The mini player is always the top-left window now (the guide's preview place), for live TV and films alike.
    fun size(): Pair<Dp, Dp> = 320.dp to 180.dp
    /** Which corner: 0 bottom right, 1 bottom left, 2 top right, 3 top left. */
    fun corner(): Int = 3
    /** Where it sits: from the left and from the top. */
    val inset: Pair<Dp, Dp> get() = 12.dp to 20.dp
    /** The menu's glass pushing the page right: the window goes along with it (px; the home screen sets it). */
    var pushPx: () -> Int = { 0 }
}
