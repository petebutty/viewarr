package io.github.petebutty.viewarr.ui.media

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.trailers.TrailerRepository
import io.github.petebutty.viewarr.ui.theme.Wallpaper

/**
 * The Viewarr launcher's trailer (its home screen and every page, 2026-11-25): no frame, in exactly the corner
 * picture's place (the top right, 58% of the width, 16:9: appBackground's title artwork), with the still picture
 * fading away under it while it plays, so nothing shows round its edges (it was a smaller box with the still showing
 * round it). Faded into the page at its left edge and towards [fadeEnd] (the bottom of the space it's in). The picture
 * is enlarged a little inside its space, so a trailer's own black bars fall outside it.
 *
 * Put it in a full-width box at the top of the screen.
 */
@Composable
fun BoxScope.FadedTrailer(item: MediaItem?, active: Boolean, trailers: TrailerRepository, fadeEnd: Dp, onShowing: (Boolean) -> Unit = {},
                          /** The space is shorter than the corner picture (a page's header): the trailer that tall, 16:9,
                           *  so the whole picture shows (it was cut off at the header's foot, half of it out of sight). */
                          fitHeight: Dp? = null,
                          /** The still corner picture fades away while it plays (home); false: it stays (the pages). */
                          hideStill: Boolean = true,
                          /** How far down from the top it starts (the pages: level with the header's text). */
                          top: Dp = androidx.compose.ui.unit.Dp(0f)) {
    var showing by remember { mutableStateOf(false) }
    // The still corner picture out while the trailer shows, back when it stops (or this page goes).
    LaunchedEffect(showing && active) {
        val to = if (showing && active && hideStill) 1f else 0f
        animate(Wallpaper.trailerCover, to, animationSpec = tween(400)) { v, _ -> Wallpaper.trailerCover = v }
    }
    DisposableEffect(Unit) { onDispose { Wallpaper.trailerCover = 0f } }
    Box(Modifier.align(Alignment.TopEnd).padding(top = top)
        .then(if (fitHeight != null) Modifier.height(fitHeight).aspectRatio(16f / 9f, matchHeightConstraintsFirst = true) else Modifier.fillMaxWidth(0.58f).aspectRatio(16f / 9f))
        .clipToBounds()
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val end = fadeEnd.toPx().coerceIn(1f, size.height)
            // A little see-through all over (the page faintly through it), and softer edges: across the left fifth and
            // the bottom quarter of the space it's in (2026-11-25: between the first version, too much, and a narrow one).
            drawRect(Color.Black.copy(alpha = 0.85f), blendMode = BlendMode.DstIn)
            drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.2f to Color.Black), blendMode = BlendMode.DstIn)
            drawRect(Brush.verticalGradient(0f to Color.Black, (end * 0.75f) / size.height to Color.Black, end / size.height to Color.Transparent),
                blendMode = BlendMode.DstIn)
        }) {
        TrailerPreview(item = item, active = active, trailers = trailers, onShowing = { showing = it; onShowing(it) },
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.35f; scaleY = 1.35f })
    }
}
