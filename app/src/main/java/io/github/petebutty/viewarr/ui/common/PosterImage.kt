package io.github.petebutty.viewarr.ui.common

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import kotlin.math.abs

/**
 * Box art in a 2:3 poster space, from any source. A 2:3 picture fills it; one that's another shape (square IPTV covers,
 * wide Jellyfin / Emby images, odd provider art) is shown whole, with a blurred copy of itself filling the rest, rather
 * than having its edges cut off.
 */
@Composable
fun PosterImage(
    url: String, contentDescription: String?, modifier: Modifier = Modifier,
    /**
     * A colour washed over the picture (the "Other posters faded" shade): one plain rectangle drawn on top, with no
     * see-through layer (those cost graphics memory per poster). It was a colour filter on the picture, which on some
     * boxes did nothing at all (2026-11-20: "Other posters faded doesn't seem to work").
     */
    tint: Color? = null,
    /**
     * The same wash, read while drawing (a fade that changes every frame): only the drawing is redone each frame, not
     * the card around it. A null colour: no wash.
     */
    tintOf: (() -> Color?)? = null,
    /**
     * The space's size in pixels, when the caller knows it (the row posters): the picture is asked for at exactly that
     * size before the card is even laid out, under the same cache key the rows' look-ahead ([posterRequest]) uses.
     */
    sizePx: IntSize? = null,
    /**
     * Drawn under the picture until it has loaded (the poster's name as text). Not made at all when the picture is
     * already in memory, which is most posters on a second look: one less piece of text laid out per poster.
     */
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(url, sizePx) { sizePx?.let { posterRequest(context, url, it.width, it.height) } }
    // Already in memory: no placeholder needed (a failed load brings it back).
    var loaded by remember(url, sizePx) {
        mutableStateOf(request?.memoryCacheKey?.let { k -> context.imageLoader.memoryCache?.get(k) } != null)
    }
    // Whether the picture is another shape, once it's loaded (only that is kept, so a normal poster arriving doesn't
    // rebuild the card).
    var odd by remember(url) { mutableStateOf(false) }
    val wash = when {
        tintOf != null -> Modifier.drawWithContent { drawContent(); tintOf()?.let { drawRect(it) } }
        tint != null -> Modifier.drawWithContent { drawContent(); drawRect(tint) }
        else -> Modifier
    }
    Box(modifier.then(wash)) {
        if (!loaded && placeholder != null) placeholder()
        // The blurred fill needs Android 12; before that a sharp copy would look like a glitch, so it's a plain dark fill.
        if (odd && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            Box(Modifier.fillMaxSize().background(Color(0xFF15171C)))
        } else if (odd) {
            AsyncImage(
                model = request ?: url, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.55f,
                modifier = Modifier.fillMaxSize().background(Color.Black).blur(16.dp),
            )
        }
        AsyncImage(
            model = request ?: url, contentDescription = contentDescription,
            contentScale = if (odd) ContentScale.Fit else ContentScale.Crop,
            onSuccess = { s ->
                val size = s.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) odd = abs(size.width / size.height - 2f / 3f) > 0.07f
                loaded = true
            },
            onError = { loaded = false },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A poster picture at [w] × [h] pixels, as the rows show it and as their look-ahead loads it: one cache key per picture
 * and size (the address as given, not as it's been shrunk to suit the size), cropped to fill, decoded no bigger than
 * needed. Same key, same size: never decoded twice.
 */
fun posterRequest(context: android.content.Context, url: String, w: Int, h: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(w, h)
        .scale(Scale.FILL)
        .precision(Precision.INEXACT)
        .memoryCacheKey(MemoryCache.Key(url, mapOf("px" to "${w}x$h")))
        .build()
