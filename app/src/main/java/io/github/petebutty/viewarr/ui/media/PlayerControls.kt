package io.github.petebutty.viewarr.ui.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.playback.SeekThumbs
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Seek-bar scrubbing shared by both players. Down puts you on the seek bar; Left / Right move a marker (faster the
 * longer you hold) while the film keeps playing; OK jumps there; Up or Back leaves it where it was.
 */
class Scrubber {
    /** Where the marker is, while on the seek bar; null otherwise. */
    var targetMs by mutableStateOf<Long?>(null)
        private set
    val active get() = targetMs != null

    fun begin(positionMs: Long) { targetMs = positionMs.coerceAtLeast(0) }
    fun cancel() { targetMs = null }

    /** One press or repeat of Left (-1) / Right (+1). Steps grow while the button is held. */
    fun step(direction: Int, repeatCount: Int, durationMs: Long) {
        val t = targetMs ?: return
        val step = when {
            repeatCount < 4 -> 10_000L
            repeatCount < 12 -> 30_000L
            repeatCount < 25 -> 60_000L
            else -> maxOf(120_000L, durationMs / 50)
        }
        val max = if (durationMs > 0) durationMs - 1_000 else Long.MAX_VALUE
        targetMs = (t + direction * step).coerceIn(0, max.coerceAtLeast(0))
    }

    /** OK: where to jump to; leaves the seek bar. */
    fun commit(): Long? = targetMs.also { targetMs = null }
}

/** The controls over a playing film or episode: title, seek bar (with preview while scrubbing) and times. */
@Composable
fun PlayerOsd(
    title: String,
    positionMs: Long,
    durationMs: Long,
    paused: Boolean,
    buffering: Boolean,
    scrubMs: Long?,
    thumbs: SeekThumbs?,
    modifier: Modifier = Modifier,
) {
    val scrubbing = scrubMs != null
    // The preview: fetched once the marker rests for a moment, the last one stays up meanwhile.
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(scrubMs?.div(5_000), thumbs) {
        if (scrubMs == null || thumbs == null) { if (scrubMs == null) thumb = null; return@LaunchedEffect }
        delay(120)
        thumbs.at(scrubMs)?.let { thumb = it }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.9f)))
            .padding(start = 56.dp, end = 56.dp, top = if (scrubbing && thumbs != null) 24.dp else 80.dp, bottom = 36.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (buffering) "◌" else if (paused) "❚❚" else "▶", fontSize = 22.sp, color = Color.White, modifier = Modifier.width(40.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (buffering) "Loading…" else "", fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val barWidth = maxWidth
            val played = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val marker = if (durationMs > 0 && scrubMs != null) (scrubMs.toFloat() / durationMs).coerceIn(0f, 1f) else played
            Column {
                if (scrubbing && thumbs != null) {
                    // Preview above the marker, kept on screen at the ends.
                    val w = 320.dp
                    val x = (barWidth * marker - w / 2).coerceIn(0.dp, (barWidth - w).coerceAtLeast(0.dp))
                    Column(Modifier.offset(x = x).width(w), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.width(w).height(180.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)
                                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val b = thumb
                            if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
                            else Text("…", fontSize = 22.sp, color = Color.White.copy(alpha = 0.6f))
                        }
                        Text(clockMs(scrubMs ?: 0), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    }
                } else if (scrubbing) {
                    val w = 120.dp
                    val x = (barWidth * marker - w / 2).coerceIn(0.dp, (barWidth - w).coerceAtLeast(0.dp))
                    Text(clockMs(scrubMs ?: 0), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White,
                        modifier = Modifier.offset(x = x).width(w).padding(bottom = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                val barHeight = if (scrubbing) 10.dp else 6.dp
                Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(played).background(if (scrubbing) Color.White.copy(alpha = 0.55f) else AppColors.Accent))
                    }
                    if (scrubbing) Box(
                        Modifier.offset(x = (barWidth * marker - 9.dp).coerceAtLeast(0.dp)).size(18.dp).clip(CircleShape).background(AppColors.Accent)
                            .border(2.dp, Color.White, CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(clockMs(positionMs), fontSize = 14.sp, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text(
                if (scrubbing) "◀ ▶ move  ·  OK: jump here  ·  Back: cancel" else "▼ seek bar  ·  Hold OK: audio & subtitles",
                fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            Text(if (durationMs > 0) "-" + clockMs(durationMs - positionMs) else "", fontSize = 14.sp, color = Color.White)
        }
    }
}

fun clockMs(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
