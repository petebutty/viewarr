package io.github.petebutty.viewarr.ui.music

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.playback.MusicPlayer
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.common.glass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** At the root, over every page: the Now Playing screen, or the small now-playing bar while you browse. */
@Composable
fun MusicLayer() {
    val context = LocalContext.current
    // Music stops being heard when Viewarr leaves the screen (there's no background service yet).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) MusicPlayer.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val err = MusicPlayer.error
    LaunchedEffect(err) { err?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); MusicPlayer.error = null } }
    if (!MusicPlayer.active) return
    // Position for the progress bars, and counting a listen at half way.
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { position = MusicPlayer.positionMs; MusicPlayer.tick(); delay(500) } }
    // The visualiser's bars move once per frame here (however many visualisers are on screen), and rest while paused.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 16L else ((t - last) / 1_000_000L).coerceIn(1, 100)
                last = t
                MusicPlayer.analyser.step(dt)
            }
            if (MusicPlayer.analyser.idle) { last = 0L; delay(250) }
        }
    }

    // The screensaver (Settings → Playback → Music): after a quiet spell with music playing, Now Playing comes up big
    // (artwork and lyrics); any button puts things back as they were.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            val wait = when (io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.MUSIC_SAVER)) { 0 -> 120_000L; 1 -> 300_000L; 2 -> 600_000L; else -> null } ?: continue
            if (MusicPlayer.isPlaying && !MusicSaver.on && !io.github.petebutty.viewarr.ui.media.VodHost.active &&
                android.os.SystemClock.elapsedRealtime() - UserActivity.lastKeyAt > wait) {
                MusicSaver.wasOpen = MusicPlayer.nowPlayingOpen
                MusicSaver.on = true
                MusicPlayer.nowPlayingOpen = true
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(!MusicPlayer.nowPlayingOpen && !io.github.petebutty.viewarr.ui.media.VodHost.active, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd)) {
            MiniBar(position)
        }
        AnimatedVisibility(MusicPlayer.nowPlayingOpen, enter = fadeIn(), exit = fadeOut()) {
            NowPlaying(position)
        }
    }
}

@Composable
private fun MiniBar(position: Long) {
    val s = MusicPlayer.current ?: return
    Row(
        // The same glass as the info bar and every pop-up.
        Modifier.padding(end = 40.dp, bottom = 28.dp).width(420.dp).height(78.dp)
            .glass(12.dp).clip(RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(MusicPlayer.cover(s, 150), null, contentScale = ContentScale.Crop, modifier = Modifier.size(78.dp).background(Color.Black))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (MusicPlayer.isPlaying) "Hold Back: Now playing" else "Paused · ⏯ to play", fontSize = 10.sp, color = AppColors.TextSecondary.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp))
            Progress(position, MusicPlayer.durationMs, Modifier.padding(top = 5.dp).height(3.dp))
        }
        Visualiser(Modifier.width(70.dp).height(44.dp).padding(end = 12.dp), bands = 12, reflection = false)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NowPlaying(position: Long) {
    val s = MusicPlayer.current
    val vm: MusicViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    LaunchedEffect(s?.id) { vm.loadLyrics(s) }
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val starred by vm.starred.collectAsStateWithLifecycle()
    val lyricsOn = io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.MUSIC_LYRICS) == 0
    val saver = MusicSaver.on
    val playFocus = remember { FocusRequester() }
    var immersive by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(50); runCatching { playFocus.requestFocus() } }
    BackHandler { if (immersive) immersive = false else MusicPlayer.nowPlayingOpen = false }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
            .onPreviewKeyEvent { e ->
                // The screensaver: any button ends it (and does nothing else).
                if (MusicSaver.on) {
                    if (e.type == KeyEventType.KeyUp) { MusicSaver.on = false; if (!MusicSaver.wasOpen) MusicPlayer.nowPlayingOpen = false }
                    return@onPreviewKeyEvent true
                }
                // The rest of the held Back that opened this screen.
                if (e.key == Key.Back && MusicPlayer.eatBackRelease) { if (e.type == KeyEventType.KeyUp) MusicPlayer.eatBackRelease = false; return@onPreviewKeyEvent true }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { MusicPlayer.toggle(); true }
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { MusicPlayer.next(); true }
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { MusicPlayer.previous(); true }
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { MusicPlayer.seekBy(10_000); true }
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { MusicPlayer.seekBy(-10_000); true }
                    android.view.KeyEvent.KEYCODE_MEDIA_STOP -> { MusicPlayer.stop(); true }
                    else -> false
                }
            },
    ) {
        // The cover, blurred and dimmed, fills the screen.
        // Blur needs Android 12; before that a sharp cover would be too busy, so it's much fainter there.
        val canBlur = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        AsyncImage(MusicPlayer.cover(s, 600), null, contentScale = ContentScale.Crop, alpha = if (canBlur) 0.55f else 0.22f, modifier = Modifier.fillMaxSize().blur(48.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.55f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.92f))))
        // The visualiser along the bottom (the whole screen in immersive mode).
        Visualiser(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (immersive) 0.62f else 0.26f).padding(horizontal = 48.dp),
            bands = io.github.petebutty.viewarr.playback.MusicPlayer.Analyser.BANDS, reflection = true,
        )
        // The screensaver: the cover big, the song, and its lyrics.
        if (s != null && saver) Row(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            NowPlayingArt(MusicPlayer.cover(s, 800), 420.dp)
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(s.artist, fontSize = 22.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 26.dp))
                lyrics?.takeIf { lyricsOn }?.let { LyricsPanel(it, position, MusicPlayer.durationMs, Modifier.fillMaxWidth(), big = true) }
                    ?: MusicPlayer.upNext(3).takeIf { it.isNotEmpty() }?.let { next ->
                        Text("Up next", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Accent, modifier = Modifier.padding(bottom = 8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { next.forEach { MiniSong(it) } }
                    }
            }
        }
        if (s != null && !saver) Row(
            Modifier.padding(start = 64.dp, top = if (immersive) 40.dp else 44.dp, end = 64.dp),
            verticalAlignment = if (immersive) Alignment.CenterVertically else Alignment.Top,
        ) {
            val coverSize = if (immersive) 120.dp else 280.dp
            NowPlayingArt(MusicPlayer.cover(s, 600), coverSize)
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                if (!immersive) Text("NOW PLAYING", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Accent, letterSpacing = 2.sp)
                Text(s.title, fontSize = if (immersive) 24.sp else 32.sp, lineHeight = if (immersive) 30.sp else 40.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(s.artist, fontSize = if (immersive) 16.sp else 20.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                if (!immersive) Text(listOfNotNull(s.album.takeIf { it.isNotBlank() }, s.year?.toString()).joinToString(" · "), fontSize = 15.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(if (immersive) 14.dp else 16.dp))
                Progress(position, MusicPlayer.durationMs, Modifier.fillMaxWidth().height(5.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(io.github.petebutty.viewarr.ui.media.clockMs(position), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.weight(1f))
                    Text(io.github.petebutty.viewarr.ui.media.clockMs(MusicPlayer.durationMs), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Control(AppIcons.SkipPrevious, "Previous") { MusicPlayer.previous() }
                    Control(if (MusicPlayer.isPlaying) AppIcons.Pause else AppIcons.Play, if (MusicPlayer.isPlaying) "Pause" else "Play", big = true, modifier = Modifier.focusRequester(playFocus)) { MusicPlayer.toggle() }
                    Control(AppIcons.SkipNext, "Next") { MusicPlayer.next() }
                    Spacer(Modifier.width(10.dp))
                    Control(AppIcons.Shuffle, "Shuffle", on = MusicPlayer.shuffle) { MusicPlayer.toggleShuffle() }
                    Control(if (MusicPlayer.repeat == Player.REPEAT_MODE_ONE) AppIcons.RepeatOne else AppIcons.Repeat, "Repeat", on = MusicPlayer.repeat != Player.REPEAT_MODE_OFF) { MusicPlayer.cycleRepeat() }
                    Control(AppIcons.Stats, if (immersive) "Less visualiser" else "Big visualiser", on = immersive) { immersive = !immersive }
                    Control(AppIcons.Layers, "Queue") { queueOpen = true }
                    Control(AppIcons.MusicNote, "Song radio") { vm.radio(s) }
                    Control(if (s.id in starred) AppIcons.BookmarkFilled else AppIcons.Bookmark, if (s.id in starred) "Favourite (OK to remove)" else "Add to favourites", on = s.id in starred) { vm.toggleStar(s.id, "song") }
                    Control(AppIcons.Clock, MusicPlayer.sleepMinutesLeft()?.let { "Sleep: $it min" } ?: MusicPlayer.sleepAfter?.let { "Sleep: end of $it" } ?: "Sleep timer",
                        on = MusicPlayer.sleepAt != null || MusicPlayer.sleepAfter != null) { sleepOpen = true }
                    Control(AppIcons.Stop, "Stop") { MusicPlayer.stop() }
                }
                // Under the controls: the words when the song has some (Settings → Playback → Music → Lyrics), else
                // the next few tracks in a row.
                val l = lyrics
                if (!immersive && lyricsOn && l != null) LyricsPanel(l, position, MusicPlayer.durationMs, Modifier.fillMaxWidth().padding(top = 16.dp))
                else if (!immersive) {
                    val next = remember(MusicPlayer.index, MusicPlayer.shuffle, MusicPlayer.repeat, MusicPlayer.queue) { MusicPlayer.upNext(3) }
                    if (next.isNotEmpty()) {
                        Text("Up next", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Accent, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            next.forEach { n ->
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(MusicPlayer.cover(n, 100), null, contentScale = ContentScale.Crop, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(AppColors.Panel))
                                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                        Text(n.title, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(n.artist, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // The queue slides in from the right; the sleep timer pops up.
        if (queueOpen) Box(Modifier.align(Alignment.CenterEnd)) { QueuePanel(onClose = { queueOpen = false; runCatching { playFocus.requestFocus() } }) }
        if (sleepOpen) SleepDialog(onClose = { sleepOpen = false })
    }
}

/** The music screensaver's state (MusicLayer starts it; any button ends it). */
object MusicSaver {
    var on by mutableStateOf(false)
    /** Now Playing was already open when it started (so it stays open after). */
    var wasOpen = false
}

@Composable
private fun Control(icon: ImageVector, label: String, modifier: Modifier = Modifier, big: Boolean = false, on: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(if (big) 64.dp else 48.dp).onFocusChanged { focused = it.isFocused },
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (big) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f),
                focusedContainerColor = AppColors.Accent,
                contentColor = if (on) AppColors.Accent else Color.White, focusedContentColor = AppColors.OnAccent,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, label, modifier = Modifier.size(if (big) 34.dp else 24.dp), tint = androidx.tv.material3.LocalContentColor.current)
            }
        }
        Text(if (focused) label else "", fontSize = 11.sp, color = Color.White, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Progress(position: Long, duration: Long, modifier: Modifier) {
    val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(played).background(AppColors.Accent))
    }
}

/**
 * Bars that follow the music: bass on the left, treble on the right, each with a small cap that floats down after
 * a peak, coloured from the theme's highlight to white at the top, with a faint reflection underneath.
 */
@Composable
fun Visualiser(modifier: Modifier, bands: Int, reflection: Boolean) {
    // Redrawn every frame while there's movement (MusicLayer moves the bars); at rest, checked now and then.
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frame++ }
            if (MusicPlayer.analyser.idle) delay(250)
        }
    }
    val accent = AppColors.Accent
    // Settings → Playback → Music → Visualiser: bars, mirrored bars, a wave, dots, or none.
    val style = io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.MUSIC_VIS)
    if (style == 4) return
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        val a = MusicPlayer.analyser
        val total = MusicPlayer.Analyser.BANDS
        fun level(i: Int): Float {
            val from = i * total / bands
            val to = ((i + 1) * total / bands).coerceAtLeast(from + 1)
            var v = 0f
            for (k in from until to) v = maxOf(v, a.bands[k])
            return v
        }
        when (style) {
            // Mirrored: each bar grows up and down from the middle.
            1 -> {
                val gap = size.width / bands * 0.28f
                val w = size.width / bands - gap
                val mid = size.height / 2
                for (i in 0 until bands) {
                    val v = level(i)
                    val h = (v * size.height / 2).coerceAtLeast(w * 0.3f)
                    val x = i * (w + gap) + gap / 2
                    val top = lerp(accent, Color.White, (v * 0.8f).coerceIn(0f, 1f))
                    drawRoundRect(brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.5f), top, accent.copy(alpha = 0.5f)), startY = mid - h, endY = mid + h),
                        topLeft = Offset(x, mid - h), size = Size(w, h * 2), cornerRadius = CornerRadius(w / 2, w / 2))
                }
                return@Canvas
            }
            // Wave: one smooth line through the levels, filled softly underneath.
            2 -> {
                val path = androidx.compose.ui.graphics.Path()
                val step = size.width / (bands - 1).coerceAtLeast(1)
                val base = size.height
                path.moveTo(0f, base)
                var px = 0f; var py = base - level(0) * base * 0.9f
                path.lineTo(px, py)
                for (i in 1 until bands) {
                    val x = i * step; val y = base - level(i) * base * 0.9f
                    path.quadraticTo(px, py, (px + x) / 2, (py + y) / 2)
                    px = x; py = y
                }
                path.lineTo(size.width, py); path.lineTo(size.width, base); path.close()
                drawPath(path, Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)))
                return@Canvas
            }
            // Dots: a column of dots per band, lit up to its level.
            3 -> {
                val colW = size.width / bands
                val d = (colW * 0.55f).coerceAtMost(size.height / 14f)
                val rows = (size.height / (d * 1.5f)).toInt().coerceAtLeast(1)
                for (i in 0 until bands) {
                    val lit = (level(i) * rows).toInt()
                    for (r in 0 until rows) {
                        val on = r < lit
                        drawCircle(if (on) lerp(accent, Color.White, r.toFloat() / rows) else Color.White.copy(alpha = 0.06f), radius = d / 2,
                            center = Offset(i * colW + colW / 2, size.height - d - r * d * 1.5f))
                    }
                }
                return@Canvas
            }
        }
        val gap = size.width / bands * 0.28f
        val w = size.width / bands - gap
        val base = if (reflection) size.height * 0.78f else size.height
        val maxH = base
        for (i in 0 until bands) {
            // Fewer bars: each takes the loudest of the bands it covers.
            val from = i * total / bands
            val to = ((i + 1) * total / bands).coerceAtLeast(from + 1)
            var v = 0f; var pk = 0f
            for (k in from until to) { v = maxOf(v, a.bands[k]); pk = maxOf(pk, a.peak(k)) }
            val h = (v * maxH).coerceAtLeast(w * 0.35f)
            val x = i * (w + gap) + gap / 2
            val top = lerp(accent, Color.White, (v * 0.8f).coerceIn(0f, 1f))
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(top, accent.copy(alpha = 0.85f)), startY = base - h, endY = base),
                topLeft = Offset(x, base - h), size = Size(w, h), cornerRadius = CornerRadius(w / 2, w / 2),
            )
            if (reflection) {
                val rh = h * 0.28f
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent), startY = base + 4f, endY = base + 4f + rh),
                    topLeft = Offset(x, base + 4f), size = Size(w, rh), cornerRadius = CornerRadius(w / 2, w / 2),
                )
                // The peak cap.
                val py = base - pk * maxH - w * 0.6f
                if (pk > 0.02f) drawRoundRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(x, py), size = Size(w, w * 0.35f), cornerRadius = CornerRadius(w / 4, w / 4))
            }
        }
    }
}
