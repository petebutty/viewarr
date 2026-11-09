package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.abs

/**
 * Box art in a 2:3 poster space, from any source. A 2:3 picture fills it; one that's another shape (square IPTV covers,
 * wide Jellyfin / Emby images, odd provider art) is shown whole, with a blurred copy of itself filling the rest, rather
 * than having its edges cut off.
 */
@Composable
fun PosterImage(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
    // The picture's own shape, once it's loaded.
    var ratio by remember(url) { mutableStateOf<Float?>(null) }
    val odd = ratio?.let { abs(it - 2f / 3f) > 0.07f } == true
    Box(modifier) {
        // The blurred fill needs Android 12; before that a sharp copy would look like a glitch, so it's a plain dark fill.
        if (odd && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            Box(Modifier.fillMaxSize().background(Color(0xFF15171C)))
        } else if (odd) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.55f,
                modifier = Modifier.fillMaxSize().background(Color.Black).blur(16.dp),
            )
        }
        AsyncImage(
            model = url, contentDescription = contentDescription,
            contentScale = if (odd) ContentScale.Fit else ContentScale.Crop,
            onSuccess = { s ->
                val size = s.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) ratio = size.width / size.height
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
