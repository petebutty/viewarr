package io.github.petebutty.viewarr.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.petebutty.viewarr.data.media.PlayerArt
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.media.FilmPanels
import io.github.petebutty.viewarr.ui.media.FilmState
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Films, episodes and recordings on a phone or tablet: a tap shows the controls for a few seconds (always while
 * paused). Back and the title at the top, audio and subtitles at the top right; back 10 s, play / pause and on 10 s in
 * the middle; a bar to drag along the bottom with the time gone and left. Back (the phone's too) closes the player:
 * there's no small window on a phone.
 */
@Composable
internal fun MobileFilmControls(stream: VodStream, art: PlayerArt?, f: FilmState, panels: FilmPanels) = Mobile.Phone {
    var shown by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun poke() { shown = true; lastTouch = System.currentTimeMillis() }
    fun close() { VodHost.close(); VodHost.afterStop() }
    BackHandler { if (panels.tracksOpen) panels.tracksOpen = false else close() }
    LaunchedEffect(shown, lastTouch, f.paused) {
        if (shown && !f.paused) { delay(4_000); if (System.currentTimeMillis() - lastTouch >= 3_900) shown = false }
    }
    val remote = VodHost.remote
    var dragging by remember { mutableStateOf<Float?>(null) }
    // Where it is, read every half second (the player's own value isn't something the screen watches).
    var pos by remember { mutableLongStateOf(f.positionMs()) }
    LaunchedEffect(Unit) { while (true) { if (dragging == null) pos = f.positionMs(); delay(500) } }
    val dur = f.durationMs.coerceAtLeast(1)
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures(
            onTap = { if (shown) shown = false else poke() },
            onDoubleTap = { o -> if (o.x < size.width / 2) remote?.skip(-10_000) else remote?.skip(10_000); poke() },
        )
    }) {
        if (!(shown || f.paused) || !f.started) return@Box
        // Shade at the top and bottom so the white controls read over any picture.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.65f), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // ---- Top: back, the title (and episode), audio & subtitles ----
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(AppIcons.Back, "Close the player") { close() }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(stream.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    art?.episodeLine?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                // Play on TV: this recording, or this film / show, on Previewarr on a TV; the player here closes.
                val castLink = io.github.petebutty.viewarr.data.dvr.RecordingProgress.idOf(stream.url)?.let { io.github.petebutty.viewarr.data.cast.PlayOnTv.recordingLink(it) }
                    ?: VodHost.source?.item?.invoke()?.let { io.github.petebutty.viewarr.data.cast.PlayOnTv.titleLink(it) }
                if (MobilePlayOnTv.key != null && castLink != null) CircleIcon(AppIcons.Tv, "Play on TV") { MobilePlayOnTv.ask(castLink) { close() }; poke() }
                CircleIcon(AppIcons.Audio, "Audio") { panels.tracksSubs = false; panels.tracksOpen = true; poke() }
                CircleIcon(AppIcons.Subtitles, "Subtitles") { panels.tracksSubs = true; panels.tracksOpen = true; poke() }
            }
            // ---- Middle: back 10 s, play / pause, on 10 s ----
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(AppIcons.Rewind, "Back 10 seconds", 64.dp) { remote?.skip(-10_000); poke() }
                Box(Modifier.size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable { remote?.togglePause(); poke() }, contentAlignment = Alignment.Center) {
                    if (f.buffering && !f.paused) androidx.compose.material3.CircularProgressIndicator(color = Color.White, modifier = Modifier.size(44.dp))
                    else Icon(if (f.paused) AppIcons.Play else AppIcons.Pause, if (f.paused) "Play" else "Pause", tint = Color.White, modifier = Modifier.size(46.dp))
                }
                CircleIcon(AppIcons.FastForward, "On 10 seconds", 64.dp) { remote?.skip(10_000); poke() }
            }
            // ---- Bottom: the bar to drag, time gone and time left ----
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 10.dp)) {
                val shownAt = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
                Slider(
                    value = shownAt,
                    onValueChange = { dragging = it; poke() },
                    onValueChangeFinished = { dragging?.let { v -> val to = (v * dur).toLong(); remote?.seekTo(to); pos = to }; dragging = null; poke() },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppColors.Accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                )
                Row(Modifier.fillMaxWidth()) {
                    val at = (shownAt * dur).toLong()
                    Text(clockOf(at), color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text("-" + clockOf(dur - at) + "  ·  ends " + io.github.petebutty.viewarr.ui.theme.Dates.time(System.currentTimeMillis() + (dur - at)), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, label: String, size: Dp = 52.dp, onTap: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.52f))
    }
}

private fun clockOf(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
