package io.github.petebutty.viewarr.ui.player

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.petebutty.viewarr.ui.common.glass
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.playback.AspectMode
import io.github.petebutty.viewarr.playback.PlaybackState
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.guide.displayTitle
import io.github.petebutty.viewarr.ui.guide.formatNumber
import io.github.petebutty.viewarr.ui.guide.logoUrl
import io.github.petebutty.viewarr.ui.theme.AppColors
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat get() = io.github.petebutty.viewarr.ui.theme.Dates.time
private fun Long.hhmm() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(timeFormat)

@OptIn(UnstableApi::class)
@Composable
fun PlayerLayer(
    vm: PlaybackViewModel,
    fullscreen: Boolean,
    bannerVisible: Boolean,
    current: GuideProgram?,
    next: GuideProgram?,
    now: Long,
    serverUrl: String,
    modifier: Modifier = Modifier,
    /** A channel browsed to in the info bar (not changed to yet), with what's on it now and next: shown in place of the playing one. */
    browse: Triple<ChannelListItem, GuideProgram?, GuideProgram?>? = null,
) {
    val channel by vm.channel.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    // Read so a new player (after switching source) is attached in update below.
    val playerVersion by vm.playerVersion.collectAsStateWithLifecycle()

    // The stream's frame rate, once it's certain: one reading for the info bar's badge and frame rate matching. The
    // stream's own rate if it gives one; else Dispatcharr's probe of it (admins; asked a few seconds apart as it
    // probes on starting); else frames counted each second, three in a row agreeing (the first second or two after
    // tuning come in bursts and read wrong: the badge said 45 or 48 for a 50 fps channel). Null until then.
    var liveFps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val streamPlaying = state is PlaybackState.Playing
    val streamPlayingNow = androidx.compose.runtime.rememberUpdatedState(streamPlaying)
    androidx.compose.runtime.LaunchedEffect(playerVersion, channel?.id, catchup) {
        // A channel seen before: its rate straight away (it doesn't change between tunes).
        val key = channel?.uuid?.let { if (catchup == null) it else "$it:cu" }
        liveFps = key?.let { KnownFps.get(it) }
        if (liveFps != null || key == null) return@LaunchedEffect
        // Else counting frames (FpsCounter).
        val counter = FpsCounter()
        var tick = 0; var nextAsk = 1
        while (tick < 60) {
            kotlinx.coroutines.delay(500); tick++
            if (!streamPlayingNow.value) { counter.reset(); continue }
            val p = vm.player ?: continue
            p.videoFormat?.frameRate?.takeIf { it > 0 }?.let { liveFps = it; KnownFps.put(key, it); return@LaunchedEffect }
            // Dispatcharr's probe (admins): asked every couple of seconds for the first ten, as it probes on starting.
            if (catchup == null && tick >= nextAsk && tick <= 20) {
                nextAsk = tick + 4
                runCatching { vm.sourceFps() }.getOrNull()?.let { liveFps = it; KnownFps.put(key, it); return@LaunchedEffect }
            }
            val frames = FpsCounter.frames(p) ?: continue
            counter.feed(frames, android.os.SystemClock.elapsedRealtime())?.let { liveFps = it; KnownFps.put(key, it); return@LaunchedEffect }
        }
    }

    androidx.compose.runtime.LaunchedEffect(liveFps) { LiveQuality.fps = liveFps }

    // Settings → Playback → Match frame rate → Live TV or Both: the TV switched to that rate. Full screen only (the
    // guide's window doesn't flash the screen while you browse); the TV's earlier mode comes back when live TV stops.
    val afrActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val liveAfr = io.github.petebutty.viewarr.data.settings.LocalPlayback.current.frameRate.live
    androidx.compose.runtime.LaunchedEffect(liveFps, fullscreen, liveAfr, catchup == null) {
        val fps = liveFps ?: return@LaunchedEffect
        if (liveAfr && fullscreen && catchup == null && afrActivity != null) io.github.petebutty.viewarr.playback.FrameRateMatcher.Live.apply(afrActivity, fps)
    }
    androidx.compose.runtime.LaunchedEffect(state is PlaybackState.Idle) {
        if (state is PlaybackState.Idle && afrActivity != null) io.github.petebutty.viewarr.playback.FrameRateMatcher.Live.restore(afrActivity)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { afrActivity?.let { io.github.petebutty.viewarr.playback.FrameRateMatcher.Live.restore(it) } }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    keepScreenOn = true
                    // Keys are handled in Compose; the view must never take focus from the guide.
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { view ->
                @Suppress("UNUSED_EXPRESSION") playerVersion
                if (view.player !== vm.player) view.player = vm.player
                view.resizeMode = when (aspect) {
                    AspectMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectMode.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        when (val s = state) {
            PlaybackState.Buffering -> if (fullscreen) {
                Text(
                    "Loading…",
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            is PlaybackState.Error -> Column(
                Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, fontSize = if (fullscreen) 18.sp else 12.sp, color = Color.White)
                if (fullscreen) {
                    Spacer(Modifier.height(8.dp))
                    Text("Press OK to try again", fontSize = 14.sp, color = AppColors.TextSecondary)
                }
            }
            else -> Unit
        }

        if (fullscreen && bannerVisible && channel != null) {
            // The stream's picture and sound, for the info bar's badges (read while it's up).
            var quality by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<StreamQuality?>(null) }
            androidx.compose.runtime.LaunchedEffect(playerVersion, channel?.id) {
                // The frame rate is the certain one from above (blank until then, not a first-second guess).
                while (true) {
                    val p = vm.player
                    quality = p?.videoFormat?.let { v -> StreamQuality(v.height, liveFps ?: 0f, p.audioFormat?.channelCount ?: 0) }
                    LiveQuality.now = quality
                    kotlinx.coroutines.delay(1_000)
                }
            }
            InfoBanner(
                browse?.first ?: channel!!, if (browse != null) browse.second else current, if (browse != null) browse.third else next, now, serverUrl,
                catchupPosition = if (catchup != null && browse == null) position else null,
                paused = paused && browse == null,
                quality = if (browse == null) quality else null,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Channels' frame rates once known (by uuid), so a channel tuned again has its rate at once. */
private object KnownFps {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Float>()
    fun get(k: String) = map[k]
    fun put(k: String, v: Float) { map[k] = v }
}

/** What the live stream is: picture height, frames per second, sound channels (the info bar's badges). */
data class StreamQuality(val height: Int, val fps: Float, val channels: Int)

/** The live stream's picture / sound / frame rate, last measured (the controls show the same badges). */
internal object LiveQuality {
    var now by androidx.compose.runtime.mutableStateOf<StreamQuality?>(null)
    /** The playing channel's frame rate once certain (whether or not the info bar is up), for Stream info. */
    var fps by androidx.compose.runtime.mutableStateOf<Float?>(null)
}

/** The info bar's panel: the one bar for live TV and films alike (the info bar, and the controls with their buttons inside it). */
@Composable
internal fun InfoBarPanel(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // One look: the glass panel, inset from the edges, everything inside it.
    // Settings → Appearance → Info bar width: narrower bars are centred at the bottom.
    val outer = modifier
        .fillMaxWidth(AppColors.palette.infoBarWidth)
        .padding(start = 40.dp, end = 40.dp, bottom = 28.dp)
        .glass()
        .padding(horizontal = 24.dp, vertical = 18.dp)
    // Settings → Appearance → Info bar size: everything in the bar (text, buttons, the preview rows) drawn smaller or
    // larger together, by drawing it at a different scale, so it keeps its proportions.
    val k = AppColors.palette.infoBarScale
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * k, d.fontScale),
    ) {
        Column(outer, verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/**
 * The info bar's top: number, logo, the small line (channel name, badges), the title, the progress line and what's
 * next. Live TV fills it with the channel; films with the title's logo and the seek bar.
 */
@Composable
internal fun InfoBarTop(
    number: String?,
    logo: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    smallLine: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    title: String,
    progress: @Composable () -> Unit,
    nextLine: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (number != null) Text(
            number,
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.width(90.dp),
        )
        Box(Modifier.size(width = 110.dp, height = 62.dp), contentAlignment = Alignment.Center, content = logo)
        Column(Modifier.weight(1f).padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, content = smallLine)
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            progress()
            if (nextLine != null) Text(nextLine, fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The stream's own picture / sound / frame-rate badges (Settings → Appearance → Live TV). */
@Composable
internal fun QualityBadges(q: StreamQuality?) {
    q ?: return
    val opt = AppColors.palette
    val badgeStyle = opt.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.BANNER_BADGES)
    val showFps = opt.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.BANNER_FPS) == 0
    val res = when {
        q.height <= 0 -> null
        badgeStyle == 2 -> null
        badgeStyle == 1 -> when { q.height >= 2000 -> "2160p"; q.height >= 1000 -> "1080p"; q.height >= 700 -> "720p"; else -> "${q.height}p" }
        else -> when { q.height >= 2000 -> "4K"; q.height >= 1000 -> "FHD"; q.height >= 700 -> "HD"; else -> "SD" }
    }
    val sound = if (badgeStyle != 2 && q.channels >= 6) (if (q.channels >= 8) "7.1" else "5.1") else null
    val fps = if (showFps && q.fps > 0) (if (kotlin.math.abs(q.fps - kotlin.math.round(q.fps)) > 0.01f) "%.2f".format(q.fps).trimEnd('0') else "${kotlin.math.round(q.fps).toInt()}") + " fps" else null
    listOfNotNull(res, sound, fps).forEach { b ->
        Spacer(Modifier.width(8.dp))
        Text(b, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** The info bar over live TV (as channels change): [InfoBarPanel] with [InfoBarTop]. */
@Composable
internal fun InfoBanner(
    channel: ChannelListItem,
    current: GuideProgram?,
    next: GuideProgram?,
    now: Long,
    serverUrl: String,
    catchupPosition: Pair<Long, Long>?,
    paused: Boolean,
    modifier: Modifier,
    quality: StreamQuality? = null,
) {
    val opt = AppColors.palette
    val showNumber = opt.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.BANNER_NUMBER) == 0
    val showEpisode = opt.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.BANNER_EPISODE) == 0
    fun title(p: GuideProgram) = if (showEpisode) displayTitle(p) else p.title
    InfoBarPanel(modifier) {
        InfoBarTop(
            number = if (showNumber) channel.number?.let(::formatNumber).orEmpty() else null,
            logo = {
                io.github.petebutty.viewarr.ui.guide.channelLogo(serverUrl, channel)?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            },
            smallLine = {
                Text(channel.name, fontSize = 14.sp, color = AppColors.TextSecondary)
                if (catchupPosition != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (paused) "CATCH-UP · PAUSED" else "CATCH-UP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.OnAccent,
                        modifier = Modifier.background(AppColors.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                QualityBadges(quality)
            },
            title = current?.let(::title) ?: "No guide information",
            progress = {
                if (current != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${current.startTime.hhmm()} – ${current.endTime.hhmm()}", fontSize = 14.sp, color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    val total = catchupPosition?.second?.takeIf { it > 0 } ?: (current.endTime - current.startTime)
                    val elapsed = catchupPosition?.first ?: (now - current.startTime)
                    val fraction = (elapsed.toFloat() / total).coerceIn(0f, 1f)
                    Box(Modifier.width(220.dp).height(4.dp).background(Color(0x44FFFFFF), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent, RoundedCornerShape(2.dp)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (catchupPosition != null) "${clock(elapsed)} / ${clock(total)}"
                        else "${((current.endTime - now) / 60_000).coerceAtLeast(0)} min left",
                        fontSize = 14.sp,
                        color = AppColors.TextSecondary,
                    )
                }
            },
            nextLine = next?.let { "Next  ${it.startTime.hhmm()}  ${title(it)}" },
        )
    }
}

internal fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

