package io.github.petebutty.viewarr.ui.player

import io.github.petebutty.viewarr.ui.common.videoBleed
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import io.github.petebutty.viewarr.ui.common.glass
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
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
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId

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
    /** The browsed channel's programmes have been looked up (false: still fetching, so nothing is claimed yet). */
    browseKnown: Boolean = true,
) {
    val channel by vm.channel.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    // Read so a new player (after switching source) is attached in update below.
    val playerVersion by vm.playerVersion.collectAsStateWithLifecycle()
    val streamSource by vm.streamSource.collectAsStateWithLifecycle()

    // The stream's frame rate, once it's certain: one reading for the info bar's badge and frame rate matching. The
    // stream's own rate if it gives one; else Dispatcharr's probe of it (admins; asked a few seconds apart as it
    // probes on starting); else frames counted each second, three in a row agreeing (the first second or two after
    // tuning come in bursts and read wrong: the badge said 45 or 48 for a 50 fps channel). Null until then.
    var liveFps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val streamPlaying = state is PlaybackState.Playing
    val streamPlayingNow = androidx.compose.runtime.rememberUpdatedState(streamPlaying)
    KnownFps.init(androidx.compose.ui.platform.LocalContext.current)
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
            // Dispatcharr's probe (admins): asked straight away, before the picture starts (it probes as the stream
            // opens), then every couple of seconds for the first ten.
            if (catchup == null && tick >= nextAsk && tick <= 20) {
                nextAsk = tick + 4
                runCatching { vm.sourceFps() }.getOrNull()?.let { liveFps = it; KnownFps.put(key, it); return@LaunchedEffect }
            }
            if (!streamPlayingNow.value) { counter.reset(); continue }
            val p = vm.player ?: continue
            p.videoFormat?.frameRate?.takeIf { it > 0 }?.let { liveFps = it; KnownFps.put(key, it); return@LaunchedEffect }
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
            modifier = Modifier.fillMaxSize().videoBleed(),
        )
        // A channel the built-in player couldn't start, played by mpv instead (over the built-in player's view).
        val mpvUrl by vm.mpvLive.collectAsStateWithLifecycle()
        mpvUrl?.let { u -> LiveMpvPicture(u, vm.mpvApiKey(), vm::mpvStarted, vm::mpvFailed, Modifier.fillMaxSize()) }

        when (val s = state) {
            PlaybackState.Buffering -> if (fullscreen) {
                Text(
                    "Loading…",
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                        maxLines = 1, softWrap = false,
                )
            }
            is PlaybackState.Error -> Column(
                Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, fontSize = if (fullscreen) 18.sp else 12.sp, color = Color.White)
                if (fullscreen) {
                    Spacer(Modifier.height(8.dp))
                    Text("OK: try again", fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, softWrap = false)
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
                    quality = p?.videoFormat?.let { v -> StreamQuality.of(v.width, v.height, liveFps ?: 0f, p.audioFormat?.channelCount ?: 0, StreamQuality.hdrOf(v), StreamQuality.audioOf(p.audioFormat)) }
                    LiveQuality.now = quality
                    kotlinx.coroutines.delay(1_000)
                }
            }
            InfoBanner(
                browse?.first ?: channel!!, if (browse != null) browse.second else current, if (browse != null) browse.third else next, now, serverUrl,
                catchupPosition = if (catchup != null && browse == null) position else null,
                paused = paused && browse == null,
                quality = if (browse == null) quality else null,
                source = if (browse == null) streamSource else null,
                guideKnown = browse == null || browseKnown,
                // (Room under it for the news ticker, when that's on with the info bar.)
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (io.github.petebutty.viewarr.ui.common.Ticker.withInfoBar && io.github.petebutty.viewarr.ui.common.newsTickerHasHeadlines()) 34.dp else 0.dp),
            )
            if (io.github.petebutty.viewarr.ui.common.Ticker.withInfoBar) io.github.petebutty.viewarr.ui.common.NewsTicker(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

/**
 * Each channel's frame rate once measured (by uuid), kept on the TV (a channel's rate doesn't change): after the first time, frame
 * rate matching switches the moment it starts, even after Viewarr restarts (it used to measure again every time).
 */
private object KnownFps {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private var prefs: android.content.SharedPreferences? = null
    fun init(context: android.content.Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("known_fps", android.content.Context.MODE_PRIVATE).also { p ->
            p.all.forEach { (k, v) -> (v as? Float)?.let { map[k] = it } }
        }
    }
    fun get(k: String) = map[k]
    fun put(k: String, v: Float) { map[k] = v; prefs?.edit()?.putFloat(k, v)?.apply() }
}

/** What the live stream is: picture height, frames per second, sound channels (the info bar's badges). */
data class StreamQuality(val height: Int, val fps: Float, val channels: Int,
    /** The picture's dynamic range as playing: "Dolby Vision", "HDR10", "HLG" (null: SDR or not known). */
    val hdr: String? = null,
    /** The sound's format as playing: "Dolby Atmos", "Dolby Digital+", "TrueHD", "DTS-HD"… (null: plain or not known). */
    val audio: String? = null,
) {
    companion object {
        /**
         * The picture's class by its width as well as its height: a film cropped to a wide picture (1920×960, 3840×1600)
         * is still 1080p / 4K, not 720p (2026-11-24: Carrie's 1080p episodes showed "720p").
         */
        fun of(width: Int, height: Int, fps: Float, channels: Int, hdr: String? = null, audio: String? = null) =
            StreamQuality(maxOf(height, if (width > 0) (width * 9 + 8) / 16 else 0), fps, channels, hdr, audio)

        /** The built-in player's video track: Dolby Vision by its codec, HDR10 / HLG by its transfer (2026-11-24). */
        fun hdrOf(v: androidx.media3.common.Format?): String? {
            v ?: return null
            val codecs = v.codecs.orEmpty().lowercase()
            return when {
                v.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION || codecs.startsWith("dvh") || codecs.startsWith("dva") -> "Dolby Vision"
                v.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
                v.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
                else -> null
            }
        }

        /** The built-in player's sound track's format. */
        fun audioOf(a: androidx.media3.common.Format?): String? = audioName(a?.sampleMimeType?.removePrefix("audio/"))

        /** A codec name (the built-in player's mime type, or mpv's codec) as a badge: "eac3-joc" → "Dolby Atmos". */
        fun audioName(codec: String?, profile: String? = null): String? {
            val c = codec.orEmpty().lowercase(); val pr = profile.orEmpty().lowercase()
            return when {
                c == "eac3-joc" || c == "ac4" -> "Dolby Atmos"
                c == "true-hd" || c == "truehd" -> if ("atmos" in pr) "Dolby Atmos" else "TrueHD"
                c == "eac3" -> "Dolby Digital+"
                c == "ac3" -> "Dolby Digital"
                c == "vnd.dts.uhd" || "dts:x" in pr || "dts-x" in pr -> "DTS:X"
                c == "vnd.dts.hd" || (c == "dts" && ("ma" in pr || "hd" in pr)) -> "DTS-HD"
                c == "vnd.dts" || c == "dts" -> "DTS"
                else -> null
            }
        }
    }
}

/** The live stream's picture / sound / frame rate, last measured (the controls show the same badges). */
internal object LiveQuality {
    var now by androidx.compose.runtime.mutableStateOf<StreamQuality?>(null)
    /** The playing channel's frame rate once certain (whether or not the info bar is up), for Stream info. */
    var fps by androidx.compose.runtime.mutableStateOf<Float?>(null)
}

/** The info bar's panel: the one bar for live TV and films alike (the info bar, and the controls with their buttons inside it). */
@Composable
internal fun InfoBarPanel(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // Settings → Appearance → Info bar & controls → Info bar style (and corners); Info bar width: narrower bars are
    // centred at the bottom (the fade and the full-width bar always span the screen).
    val p = AppColors.palette
    val w = p.infoBarWidth
    val r = when (p.opt(UiOpts.INFOBAR_CORNERS)) { 1 -> 10.dp; 2 -> 28.dp; else -> 18.dp }
    val bg = AppColors.tinted(AppColors.Background)
    // Settings → Appearance → Pop-up glass (0–100% see-through): every style follows it, all the way to clear.
    val glassAlpha = p.popupAlpha
    // Every style runs the whole way: solid at 0% see-through, clear at 100%. The styles drawn more solid than the
    // plain glass ([k] > 1: Floating card, Slim strip, the dark edge of Fade / Side fade) or lighter ([k] < 1: Outline,
    // the fades' soft parts) bend the slider instead of capping it (a multiply-and-clamp left Floating card the same
    // from 0 to 30%, and Outline could never be solid).
    val see = 1f - glassAlpha
    // (Lighter styles bend the other way: alpha = (1 − see)^(1/k). The first way, 1 − see^k, made them jump to about
    // half see-through at the slider's first step and barely change after; 2026-11-16.)
    fun more(k: Float) = (if (k >= 1f) 1f - Math.pow(see.toDouble(), k.toDouble()).toFloat()
        else Math.pow(glassAlpha.toDouble(), 1.0 / k).toFloat()).coerceIn(0f, 1f)
    val outer = when (p.opt(UiOpts.INFOBAR_STYLE)) {
        // Square glass: the same panel, sharp corners.
        1 -> modifier.fillMaxWidth(w).padding(start = 40.dp, end = 40.dp, bottom = 28.dp).glass(radius = 0.dp).padding(horizontal = 24.dp, vertical = 18.dp)
        // Pill: fully rounded ends, floating a little higher.
        2 -> modifier.fillMaxWidth(w).padding(start = 56.dp, end = 56.dp, bottom = 40.dp).glass(radius = 56.dp).padding(horizontal = 40.dp, vertical = 20.dp)
        // Fade: no panel, a dark fade up from the bottom edge, the text straight on the picture.
        3 -> modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to bg.copy(alpha = more(0.7f)), 1f to bg.copy(alpha = more(2.2f))))
            .padding(start = 64.dp, end = 64.dp, top = 64.dp, bottom = 36.dp)
        // Full-width bar: edge to edge along the bottom, square, no border (a classic TV box).
        4 -> modifier.fillMaxWidth().background(bg.copy(alpha = glassAlpha)).padding(horizontal = 64.dp, vertical = 20.dp)
        // Minimal: just the text and buttons, a soft shade behind for reading.
        5 -> modifier.fillMaxWidth(w)
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to bg.copy(alpha = more(1f))))
            .padding(start = 64.dp, end = 64.dp, top = 28.dp, bottom = 30.dp)
        // Faded glass: the Glass panel (inset, rounded, its width), solid along the bottom and fading to clear at the
        // top, its edge fading with it, so no hard line crosses the picture. (Fade spans the whole screen; this doesn't.)
        6 -> {
            val shape = RoundedCornerShape(r)
            modifier.fillMaxWidth(w).padding(start = 40.dp, end = 40.dp, bottom = 28.dp)
                .background(Brush.verticalGradient(0f to bg.copy(alpha = 0f), 0.35f to bg.copy(alpha = more(0.55f)), 1f to bg.copy(alpha = glassAlpha)), shape)
                .then(if (p.opt(UiOpts.BANNER_BORDER) == 0)
                    Modifier.border(1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0f), 1f to Color.White.copy(alpha = 0.28f)), shape) else Modifier)
                .padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 18.dp)
        }
        // Feathered glass: glass with no edge anywhere, melting into the picture on every side.
        7 -> modifier.fillMaxWidth(w).padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .drawBehind { featheredGlass(bg.copy(alpha = glassAlpha)) }
            .padding(start = 60.dp, end = 60.dp, top = 34.dp, bottom = 28.dp)
        // Side fade: dark behind the text on the left, fading to clear by two thirds across, and up from the bottom;
        // the text and buttons keep to the left part.
        8 -> modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(0f to bg.copy(alpha = more(1.9f)), 0.35f to bg.copy(alpha = more(0.7f)), 0.68f to bg.copy(alpha = 0f)))
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to bg.copy(alpha = more(0.5f))))
            .padding(start = 64.dp, top = 56.dp, bottom = 34.dp)
            .wrapContentWidth(Alignment.Start).fillMaxWidth(0.62f)
        // Outline: almost clear glass with a fine edge in the highlight colour.
        9 -> {
            val shape = RoundedCornerShape(r)
            modifier.fillMaxWidth(w).padding(start = 40.dp, end = 40.dp, bottom = 28.dp)
                .background(bg.copy(alpha = more(0.35f)), shape)
                .border(1.dp, AppColors.Accent.copy(alpha = 0.75f), shape)
                .padding(horizontal = 24.dp, vertical = 18.dp)
        }
        // Corner card: a smaller glass panel in the bottom left, leaving most of the picture clear.
        10 -> modifier.fillMaxWidth().padding(start = 40.dp, bottom = 32.dp)
            .wrapContentWidth(Alignment.Start).fillMaxWidth(0.58f)
            .glass(radius = r).padding(horizontal = 22.dp, vertical = 16.dp)
        // Floating card: a more solid card lifted off the picture, a soft shadow under it, sitting higher up.
        11 -> {
            val shape = RoundedCornerShape(maxOf(r, 22.dp))
            modifier.fillMaxWidth(w).padding(start = 64.dp, end = 64.dp, bottom = 52.dp)
                // (No shadow once it's clear: it left a dark halo round nothing.)
                .then(if (glassAlpha > 0.05f) Modifier.shadow((28 * glassAlpha.coerceAtMost(1f)).dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
                .background(bg.copy(alpha = more(2.2f)), shape)
                .padding(horizontal = 28.dp, vertical = 20.dp)
        }
        // Slim strip: a thin full-width band along the bottom, a line in the highlight colour across its top.
        12 -> modifier.fillMaxWidth()
            .background(bg.copy(alpha = more(1.5f)))
            .drawBehind { drawRect(AppColors.Accent, size = Size(size.width, 2.dp.toPx())) }
            .padding(start = 64.dp, end = 64.dp, top = 12.dp, bottom = 12.dp)
        // Frosted: milky light glass (white-tinted, a soft sheen from the top), rather than the dark tint.
        13 -> {
            val shape = RoundedCornerShape(r)
            modifier.fillMaxWidth(w).padding(start = 40.dp, end = 40.dp, bottom = 28.dp)
                .background(Color.Black.copy(alpha = more(0.75f)), shape)
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.45f * more(1f)), 1f to Color.White.copy(alpha = 0.16f * more(1f))), shape)
                .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
                .padding(horizontal = 24.dp, vertical = 18.dp)
        }
        // Glass (the Viewarr look): the panel inset from the edges, everything inside it.
        else -> modifier.fillMaxWidth(w).padding(start = 40.dp, end = 40.dp, bottom = 28.dp).glass(radius = r).padding(horizontal = 24.dp, vertical = 18.dp)
    }
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

/** Feathered glass's panel: the glass colour, faded out towards all four edges (drawn on its own layer so only the
 *  panel fades, not what's drawn over it). */
private fun DrawScope.featheredGlass(c: Color) {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint())
        drawRect(c)
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.07f to Color.Black, 0.93f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        canvas.restore()
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
    /** Keep the "Next" line's room even without one (live TV's info bar, so it doesn't change size between channels). */
    reserveNext: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (number != null) Text(
            number,
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.width(90.dp),
            maxLines = 1, softWrap = false,
        )
        Box(Modifier.size(width = 110.dp, height = 62.dp), contentAlignment = Alignment.Center, content = logo)
        Column(Modifier.weight(1f).padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The same size from one channel to the next (2026-11-16: it grew and shrank while changing channel):
            // the badge line keeps room for its badges (they arrive a moment after the channel starts, and are a little
            // taller than its text), and the "Next" line keeps its place when a channel has none.
            Row(Modifier.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically, content = smallLine)
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            progress()
            if (reserveNext || nextLine != null) Text(nextLine ?: " ", fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Settings → Appearance → Info bar → Clock: the time at the right of the top row.
        if (AppColors.palette.opt(UiOpts.INFOBAR_CLOCK) == 1) InfoBarClock()
    }
}

/** The time on the info bar (in your time format), kept up to date. */
@Composable
private fun InfoBarClock() {
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(io.github.petebutty.viewarr.ui.theme.Dates.time(System.currentTimeMillis())) }
    androidx.compose.runtime.LaunchedEffect(AppColors.palette) {
        while (true) { now = io.github.petebutty.viewarr.ui.theme.Dates.time(System.currentTimeMillis()); kotlinx.coroutines.delay(5_000) }
    }
    Text(now, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, modifier = Modifier.padding(start = 20.dp))
}

/** Where the live stream comes from ("Happy TV", "TRex (backup)"), beside the badges (Settings → Appearance → Info bar: source). */
@Composable
internal fun SourceBadge(source: String?) {
    if (source == null || AppColors.palette.opt(UiOpts.BANNER_SOURCE) != 0) return
    Spacer(Modifier.width(8.dp))
    Text(source, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, softWrap = false,
        modifier = Modifier.border(1.dp, AppColors.TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

/** The stream's own picture / sound / frame-rate badges (Settings → Appearance → Live TV). */
@Composable
internal fun QualityBadges(q: StreamQuality?) {
    q ?: return
    val opt = AppColors.palette
    val badgeStyle = opt.opt(UiOpts.BANNER_BADGES)
    val showFps = opt.opt(UiOpts.BANNER_FPS) == 0
    val res = when {
        q.height <= 0 -> null
        badgeStyle == 2 -> null
        badgeStyle == 1 -> when { q.height >= 2000 -> "2160p"; q.height >= 1000 -> "1080p"; q.height >= 700 -> "720p"; else -> "${q.height}p" }
        else -> when { q.height >= 2000 -> "4K"; q.height >= 1000 -> "FHD"; q.height >= 700 -> "HD"; else -> "SD" }
    }
    val sound = if (badgeStyle != 2 && q.channels >= 6) (if (q.channels >= 8) "7.1" else "5.1") else null
    val fps = if (showFps && q.fps > 0) (if (kotlin.math.abs(q.fps - kotlin.math.round(q.fps)) > 0.01f) "%.2f".format(q.fps).trimEnd('0') else "${kotlin.math.round(q.fps).toInt()}") + " fps" else null
    // The picture's range and the sound's format (Dolby Vision, HDR10, Dolby Atmos…; 2026-11-24), with the others.
    val hdr = q.hdr?.takeIf { badgeStyle != 2 }
    val audio = q.audio?.takeIf { badgeStyle != 2 }
    listOfNotNull(res, hdr, audio, sound, fps).forEach { b ->
        Spacer(Modifier.width(8.dp))
        Text(b, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false,
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
    source: String? = null,
    /** False while the channel's programmes are still being fetched: the title waits (no "No guide information" flash). */
    guideKnown: Boolean = true,
) {
    val opt = AppColors.palette
    val showNumber = opt.opt(UiOpts.BANNER_NUMBER) == 0
    val showEpisode = opt.opt(UiOpts.BANNER_EPISODE) == 0
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
                // (Shrinks with "…" in the narrow info bar styles, Corner card / Side fade, so the badges after it
                // aren't cut off: UI review, 2026-11-17.)
                Text(channel.name, fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (catchupPosition != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (paused) "CATCH-UP · PAUSED" else "CATCH-UP",
                        maxLines = 1, softWrap = false,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.OnAccent,
                        modifier = Modifier.background(AppColors.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                QualityBadges(quality)
                SourceBadge(source)
            },
            title = current?.let(::title) ?: if (guideKnown) "No guide information" else "",
            progress = {
                // No programme: the row's space is kept (an empty line the same height), so the bar doesn't grow and
                // shrink between channels with and without one.
                if (current == null) Text(" ", fontSize = 14.sp, maxLines = 1, softWrap = false)
                if (current != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${current.startTime.hhmm()} – ${current.endTime.hhmm()}", fontSize = 14.sp, color = Color.White, maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(12.dp))
                    val total = catchupPosition?.second?.takeIf { it > 0 } ?: (current.endTime - current.startTime)
                    val elapsed = catchupPosition?.first ?: (now - current.startTime)
                    val fraction = (elapsed.toFloat() / total).coerceIn(0f, 1f)
                    // (Up to 220 dp, less in a narrow style, so "… min left" still fits after it.)
                    Box(Modifier.weight(1f, fill = false).width(220.dp).height(4.dp).background(Color(0x44FFFFFF), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent, RoundedCornerShape(2.dp)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (catchupPosition != null) "${clock(elapsed)} / ${clock(total)}"
                        else "${((current.endTime - now) / 60_000).coerceAtLeast(0)} min left",
                        fontSize = 14.sp,
                        color = AppColors.TextSecondary,
                        maxLines = 1, softWrap = false,
                    )
                }
            },
            nextLine = next?.let { "Next  ${it.startTime.hhmm()}  ${title(it)}" },
            reserveNext = true,
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

