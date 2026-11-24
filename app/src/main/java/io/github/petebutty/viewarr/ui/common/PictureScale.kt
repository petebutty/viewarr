package io.github.petebutty.viewarr.ui.common

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/*
 * How Viewarr is scaled (AppRoot sets all of this up):
 *
 *  1. One canvas. Every page is laid out on the same 1067 x 600 units on every TV, whatever its resolution or the
 *     density its firmware picks. A page that fits on one box fits on all of them.
 *  2. Settings → Appearance → Picture size shrinks the finished picture, centred (for a TV that cuts off the edges).
 *     The canvas keeps its 1067 x 600 units, so no page is laid out differently at any setting: nothing can move out
 *     of the picture. The background still runs to the screen's edges, and so does video ([videoBleed]).
 *  3. Text size changes only the words, on top. It's the one setting a page has to cope with (line limits, "…").
 *
 * (2026-11-20: "scaling should keep all pages correct and prevent things coming out of the picture". The old
 * settings changed how many units the screen held, so pages tuned at one setting overflowed at another.)
 */

/** The canvas, in layout units: 1067 x 600 on a TV. For sizes that are a share of the screen (never the system's dp). */
val LocalCanvas = staticCompositionLocalOf { DpSize(1067.dp, 600.dp) }

/** The border Picture size leaves around the canvas, in pixels each side (zero at 100%), and the canvas's own size. */
data class PictureInset(val x: Int = 0, val y: Int = 0, val canvas: IntSize = IntSize.Zero)

val LocalPictureInset = staticCompositionLocalOf { PictureInset() }

/**
 * How much of the border a video may spread into right now (1: all of it). The film player, when it shrinks to the
 * corner by being scaled rather than resized, turns this down as it shrinks.
 */
val LocalBleedAmount = compositionLocalOf<() -> Float> { { 1f } }

/** The canvas placed inside the screen with Picture size's border around it. What's before this in the chain (the background) still fills the screen. */
fun Modifier.pictureInset(inset: PictureInset): Modifier =
    if (inset.x == 0 && inset.y == 0) this else layout { measurable, constraints ->
        val w = (constraints.maxWidth - 2 * inset.x).coerceAtLeast(1)
        val h = (constraints.maxHeight - 2 * inset.y).coerceAtLeast(1)
        val p = measurable.measure(Constraints.fixed(w, h))
        layout(constraints.maxWidth, constraints.maxHeight) { p.place(inset.x, inset.y) }
    }

/**
 * A video picture fills the whole screen when it's full screen, whatever Picture size is: it spreads over the border.
 * Shrunk to a window (the guide's preview, the corner) it stays exactly in its place; in between, on the way.
 */
fun Modifier.videoBleed(): Modifier = composed {
    val inset = LocalPictureInset.current
    val amount = LocalBleedAmount.current
    if ((inset.x == 0 && inset.y == 0) || inset.canvas.width <= 0) this else layout { measurable, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // Full size (or nearly): all of the border. A window of 60% of the screen or less: none.
        val share = maxOf(w.toFloat() / inset.canvas.width, h.toFloat() / inset.canvas.height)
        val t = (((share - 0.6f) / 0.4f).coerceIn(0f, 1f) * amount()).coerceIn(0f, 1f)
        val bx = (inset.x * t).toInt()
        val by = (inset.y * t).toInt()
        val p = measurable.measure(Constraints.fixed(w + 2 * bx, h + 2 * by))
        layout(w, h) { p.place(IntOffset(-bx, -by)) }
    }
}
