package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.ui.common.videoBleed
import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.petebutty.viewarr.ui.common.glass
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import dev.jdtech.mpv.MPVLib
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.settings.Downmix
import io.github.petebutty.viewarr.data.settings.LocalPlayback
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.data.settings.MpvDecoding
import io.github.petebutty.viewarr.data.settings.NightMode
import io.github.petebutty.viewarr.data.settings.Passthrough
import io.github.petebutty.viewarr.data.settings.PlaybackSettings
import io.github.petebutty.viewarr.data.settings.ResumeMode
import io.github.petebutty.viewarr.data.settings.SubtitleMode
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import io.github.petebutty.viewarr.data.media.FoundMarkers
import io.github.petebutty.viewarr.data.media.PlayerArt
import io.github.petebutty.viewarr.data.net.Reach
import io.github.petebutty.viewarr.data.settings.AutoNext
import io.github.petebutty.viewarr.data.settings.Deinterlace
import io.github.petebutty.viewarr.data.settings.PictureSize
import io.github.petebutty.viewarr.data.settings.PlayerEngine
import io.github.petebutty.viewarr.data.settings.StatsOverlay
import io.github.petebutty.viewarr.data.settings.SubtitleBackground
import io.github.petebutty.viewarr.data.settings.VideoView
import io.github.petebutty.viewarr.playback.FrameRateMatcher
import io.github.petebutty.viewarr.ui.player.StreamQuality
import io.github.petebutty.viewarr.playback.AvSync

private const val TAG = "Mpv"

/**
 * Films, episodes and recordings through libmpv: plays nearly any file, codec or container.
 * Our own controls; the remote is handled here (tap OK pause, hold OK / Menu audio & subtitles, Left/Right skip).
 */
@Composable
fun MpvPlayer(
    stream: VodStream,
    onClose: () -> Unit,
    onProgress: (state: String, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
    /**
     * Hold-OK menu → "Switch to the built-in player": carry on there from this position, with the audio / subtitle
     * tracks in use (ordinals; subtitles -1 = off).
     */
    onSwitchPlayer: ((positionMs: Long, audio: Int?, subtitles: Int?) -> Unit)? = null,
    art: PlayerArt? = null,
) {
    val context = LocalContext.current
    val settings = LocalPlayback.current
    // Skip intro / credits (Settings → Playback → Episodes), from the server's markers.
    val skip = remember(stream) { IntroSkip() }
    val skipModes by androidx.compose.runtime.rememberUpdatedState(settings.skipIntro to settings.skipCredits)
    // The next episode's countdown over the end of this one (Settings → Playback → Episodes); 0: off.
    val nextCountdown by androidx.compose.runtime.rememberUpdatedState(
        if (settings.autoNext == AutoNext.On) maxOf(AppColors.palette.upNextSeconds * 1_000L, 1L) else 0L)
    // Its server's markers, else ones the community intro databases had (they can arrive a moment after it starts).
    val skipMarkers by androidx.compose.runtime.rememberUpdatedState(stream.markers.ifEmpty { FoundMarkers.byUrl[stream.url].orEmpty() })
    val seekSec = LocalRemote.current.seek.ms / 1000.0
    val main = remember { Handler(Looper.getMainLooper()) }
    var restoreMode by remember { mutableStateOf<Int?>(null) }

    var position by remember { mutableDoubleStateOf(0.0) }
    var duration by remember { mutableDoubleStateOf(0.0) }
    /** How far ahead mpv has loaded (seconds into the film), for the seek bar's buffer. */
    var cachedTo by remember { mutableDoubleStateOf(0.0) }
    var paused by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    /** Waiting on the network (not a seek: a seek that never finishes is what a stalled soundtrack looks like). */
    var cacheWait by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var osdUntil by remember { mutableLongStateOf(System.currentTimeMillis() + AppColors.palette.controlsMs) }
    // What's open over the film: shared with the other player (FilmControls.kt), kept under the old names here.
    val panels = remember { FilmPanels() }
    var tracksOpen by panels::tracksOpen
    // Audio & subtitles opened from the Subtitles button: start on the subtitles.
    var tracksSubs by panels::tracksSubs
    val menu = remember { PlayerMenu() }
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var subText by remember { mutableStateOf("") }
    val scrub = remember { Scrubber() }
    // Still drawing straight to the screen (Hardware); false once picture subtitles moved this film to the GPU.
    var direct by remember { mutableStateOf(settings.mpvDecoding == MpvDecoding.Hardware) }
    val thumbs = rememberFilmThumbs(stream)

    val mpv = remember(stream) {
        MpvHandle(context).apply {
            configure(this, context, stream, settings)
            init()
        }
    }

    DisposableEffect(mpv) {
        // Set when this player goes: events already queued for the main thread then do nothing (a late end-of-file
        // used to close the next episode's player, or skip it).
        val gone = booleanArrayOf(false)
        fun post(block: () -> Unit) { main.post { if (!gone[0]) block() } }
        val observer = object : MPVLib.EventObserver {
            // The last time-pos sent on (mpv's own thread): only quarter-second steps are posted, not every frame.
            private var sentPos = -1.0
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}
            override fun eventProperty(property: String, value: Boolean) {
                post {
                    when (property) {
                        "pause" -> {
                            paused = value
                            // (Not at 0: mpv reports pause as soon as it's watched, paused while Match frame rate
                            // switches the TV, which sent a pause and a start to Trakt / Plex on every start.)
                            if (position > 0) onProgress(if (value) "paused" else "playing", (position * 1000).toLong(), (duration * 1000).toLong())
                        }
                        "paused-for-cache", "seeking" -> {
                            buffering = value
                            if (property == "paused-for-cache") cacheWait = value
                        }
                        "eof-reached" -> if (value) {
                            ended = true
                            onProgress("stopped", (duration * 1000).toLong(), (duration * 1000).toLong())
                            // The next episode known (its countdown not turned down): on to it, not closing.
                            if (skip.nextAtEnd(NextUp.episode != null, nextCountdown > 0)) NextUp.play() else onClose()
                        }
                    }
                }
            }
            override fun eventProperty(property: String, value: String) {
                if (property == "sub-text") post { subText = value }
                // Picture subtitles can't be drawn on the direct-to-screen picture: switch this film to the GPU way.
                if (property == "current-tracks/sub/codec" && value in IMAGE_SUBS) post switch@{
                    if (!direct) return@switch
                    direct = false
                    Log.i(TAG, "picture subtitles ($value): switching to GPU output")
                    runCatching { mpv.setPropertyString("hwdec", "mediacodec-copy") }
                    runCatching { mpv.setPropertyString("vo", "gpu") }
                }
            }
            override fun eventProperty(property: String, value: Double) {
                // (Every frame from mpv: only a quarter-second step redraws the controls, not 25–60 a second.)
                if (property == "time-pos") {
                    if (kotlin.math.abs(value - sentPos) < 0.25 && value >= sentPos) return
                    sentPos = value
                }
                post {
                    when (property) {
                        "time-pos" -> { position = value; if (value > 0) buffering = false }
                        "duration" -> duration = value
                        "demuxer-cache-time" -> cachedTo = value
                    }
                }
            }
            override fun event(eventId: Int) {
                // MPV_EVENT_END_FILE = 7: if nothing ever played, the stream failed to open.
                if (eventId == 7) post { if (position <= 0.0 && !ended) error = "Couldn't play this. Press Back and try another source." }
            }
        }
        mpv.addObserver(observer)
        mpv.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("demuxer-cache-time", MPVLib.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        mpv.observeProperty("seeking", MPVLib.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)
        if (settings.mpvDecoding == MpvDecoding.Hardware) {
            mpv.observeProperty("sub-text", MPVLib.MPV_FORMAT_STRING)
            mpv.observeProperty("current-tracks/sub/codec", MPVLib.MPV_FORMAT_STRING)
        }
        // Audio & subtitles picked on the film's page (mpv numbers each kind's tracks from 1).
        stream.audioTrack?.let { mpv.setOptionString("aid", "${it + 1}") }
        stream.subtitleTrack?.let { mpv.setOptionString("sid", if (it < 0) "no" else "${it + 1}") }
        mpv.command(arrayOf("loadfile", Reach.map(stream.url)))
        onDispose {
            gone[0] = true
            if (!ended && position > 0) onProgress("stopped", (position * 1000).toLong(), (duration * 1000).toLong())
            mpv.removeObserver(observer)
            runCatching { mpv.detachSurface() }
            runCatching { mpv.destroy() }
        }
    }

    // Settings → Playback → Match frame rate: switch the TV once mpv knows the video's rate; switch back on exit.
    val activity = context as? android.app.Activity
    // The info bar's Audio sync (mpv's own audio delay; later is positive, as in AvSync).
    LaunchedEffect(mpv, AvSync.ms) {
        runCatching { mpv.setPropertyDouble("audio-delay", AvSync.ms / 1000.0) }
    }
    // (Once per film: keyed on the setting too, changing it from the TV settings panel ran this again, un-pausing a
    // paused film and switching the TV mid-playback.)
    LaunchedEffect(mpv) {
        if (!settings.frameRate.films) return@LaunchedEffect
        if (activity == null) { runCatching { mpv.setPropertyBoolean("pause", false) }; return@LaunchedEffect }
        // mpv was started paused (configure): switching the TV mid-playback stalls HDMI audio on Amlogic boxes.
        try {
            repeat(80) {
                delay(250)
                val fps = runCatching { mpv.getPropertyDouble("container-fps") }.getOrNull()
                if (fps != null && fps > 0) {
                    val previous = FrameRateMatcher.match(activity, fps.toFloat())
                    if (previous != null) { restoreMode = previous; delay(2_000) }
                    return@LaunchedEffect
                }
            }
        } finally {
            runCatching { mpv.setPropertyBoolean("pause", false) }
        }
    }
    // Audio watchdog: if passthrough audio never starts (the box accepted the format but the output stalls),
    // fall back to decoding in the box instead of sitting still. Judged by the clock not moving, not by 0:00,
    // so it also catches a film resumed part-way through.
    LaunchedEffect(mpv) {
        if (settings.passthrough != Passthrough.On) return@LaunchedEffect
        delay(if (settings.frameRate.films) 9_000 else 5_000)
        // Watched for the first two minutes, not checked once: a press of pause (people press it when nothing
        // happens) used to skip the only check, leaving the film stuck. Stuck = meant to be playing, not waiting on
        // the network, and the clock still for 5 s (including a resume jump that never completes). Once the clock runs normally, passthrough is working.
        var last = position
        var stillFor = 0
        var stuck = false
        for (i in 0 until 240) {
            delay(500)
            val step = position - last
            last = position
            if (error != null) return@LaunchedEffect
            if (step in 0.2..2.0) return@LaunchedEffect
            stillFor = if (paused || cacheWait || kotlin.math.abs(step) > 2.0) 0 else stillFor + 1
            if (stillFor >= 10) { stuck = true; break }
        }
        if (stuck) {
            Log.w(TAG, "audio never started with passthrough: decoding in the box instead")
            val aid = runCatching { mpv.getPropertyString("aid") }.getOrNull()
            runCatching { mpv.setPropertyString("audio-spdif", "") }
            // Changing audio-spdif alone keeps the pass-through decoder running (seen on a Formuler: the film never
            // started). Re-picking the soundtrack opens it again with the box decoding it.
            if (aid != null && aid != "no" && aid != "auto") {
                runCatching { mpv.setPropertyString("aid", "no") }
                delay(300)
                runCatching { mpv.setPropertyString("aid", aid) }
            }
            runCatching { mpv.command(arrayOf("ao-reload")) }
        }
    }
    DisposableEffect(Unit) { onDispose { restoreMode?.let { m -> activity?.let { FrameRateMatcher.restore(it, m) } } } }
    // Settings → Playback → Show playback info.
    var stats by remember { mutableStateOf("") }
    LaunchedEffect(mpv, settings.stats, menu.infoOn) {
        // Settings → Show playback info, or the Media info button.
        if (settings.stats != StatsOverlay.On && !menu.infoOn) { stats = ""; return@LaunchedEffect }
        while (true) {
            stats = runCatching {
                fun p(k: String) = mpv.getPropertyString(k)
                listOfNotNull(
                    "Video: ${p("video-codec") ?: "?"}  ${p("video-params/w") ?: "?"}×${p("video-params/h") ?: "?"}  ${p("container-fps")?.take(6) ?: "?"} fps",
                    "Decoder: ${p("hwdec-current")?.takeIf { it.isNotBlank() && it != "no" } ?: "software"}",
                    "Audio: ${p("audio-codec-name") ?: "?"}  ${p("audio-params/channel-count") ?: "?"} ch  ${if (settings.passthrough == Passthrough.On) "(passthrough if supported)" else ""}",
                    "Dropped frames: ${p("frame-drop-count") ?: "0"} (output)  ${p("decoder-frame-drop-count") ?: "0"} (decoder)",
                    "Buffered: ${p("demuxer-cache-duration")?.toDoubleOrNull()?.let { "%.0f s".format(it) } ?: "?"}",
                ).joinToString("\n")
            }.getOrDefault("")
            delay(1_000)
        }
    }

    LaunchedEffect(mpv, stream) {
        while (true) {
            delay(250)
            if (!paused && position > 0) skip.update(skipMarkers, (position * 1000).toLong(), (duration * 1000).toLong(), skipModes.first, skipModes.second,
                { ms -> mpv.command(arrayOf("seek", (ms / 1000.0).toString(), "absolute")) },
                hasNext = NextUp.episode != null, countdownMs = nextCountdown, onNext = { NextUp.play() })
        }
    }
    // Progress to Plex/Trakt every 10 s while playing.
    LaunchedEffect(mpv) {
        while (true) {
            delay(10_000)
            if (!paused && position > 0) onProgress("playing", (position * 1000).toLong(), (duration * 1000).toLong())
        }
    }
    // Shrunk to the corner (picture-in-picture): just the picture; the page has the remote.
    // (The menu over it counts too: the player lets go of the remote and its controls.)
    val mini = VodHost.mini || VodHost.behind
    // Repaint the clock for the auto-hiding controls.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // (In the corner the controls can't show: the clock rests.)
    LaunchedEffect(Unit) { while (true) { delay(500); if (!VodHost.mini && !VodHost.behind) now = System.currentTimeMillis() } }
    // The controls are up, and playing has begun: the ticks (the clock, time-pos) redraw nothing unless these change.
    val osdVisible by remember { androidx.compose.runtime.derivedStateOf { paused || buffering || now < osdUntil || scrub.active || menu.open } }
    val hasPosition by remember { androidx.compose.runtime.derivedStateOf { position > 0 } }

    fun showOsd() { osdUntil = System.currentTimeMillis() + AppColors.palette.controlsMs }
    // Back, one step at a time (FilmControls.kt filmBackStep): what's open closes, then the mini player. A film that
    // failed closes outright (it went to the corner, broken).
    fun backStep() = if (error != null) onClose() else filmBackStep(panels, menu, scrub, skip, onClose, hideOsd = { osdUntil = 0L }, showOsd = ::showOsd)
    BackHandler(enabled = !mini) { backStep() }

    fun seek(by: Double) {
        mpv.command(arrayOf("seek", by.toString(), "relative"))
        showOsd()
    }

    // The tracks in use, as ordinals of each kind (mpv numbers them from 1; subtitles -1 = off): handed over when
    // switching to the built-in player.
    fun picks(): Pair<Int?, Int?> {
        val a = mpv.getPropertyString("aid")?.toIntOrNull()?.minus(1)
        val sid = mpv.getPropertyString("sid")
        val t = if (sid == "no" || sid == "false") -1 else sid?.toIntOrNull()?.minus(1)
        return a to t
    }
    // The remote's Play / Pause with the film in the corner (HomeState): this player while it's the one playing.
    val remote = object : VodRemote {
        override val positionMs get() = (position * 1000).toLong()
        override val durationMs get() = (duration * 1000).toLong()
        override fun seekTo(ms: Long) = mpv.command(arrayOf("seek", (ms / 1000.0).toString(), "absolute"))
        override fun skip(ms: Long) = seek(ms / 1000.0)
        override fun togglePause() = mpv.setPropertyBoolean("pause", !paused)
        override fun pause() = mpv.setPropertyBoolean("pause", true)
        override val playing get() = !paused
    }
    // Carry on in the built-in player, with the tracks in use.
    val switchPlayer = onSwitchPlayer?.let { f -> { picks().let { (a, t) -> f((position * 1000).toLong(), a, t) } } }
    // The info bar, the remote, the timers and focus: shared with the built-in player (FilmShared.kt).
    val wiring = rememberFilmWiring(remote, panels, menu, scrub, skip, (seekSec * 1000).toLong(), switchPlayer, onClose, showOsd = ::showOsd,
        backStep = ::backStep)
    val buttons = wiring.buttons
    FilmEffects(panels, menu, scrub, paused, mini, hasFocus, focus, hideOsd = { osdUntil = 0L })

    Box(Modifier.filmKeys(focus, panels, mini, { hasFocus = it }, wiring, remote)) {
        // Settings → Playback → Video view: Standard draws into a SurfaceView with a buffer fixed at the screen's
        // size, so moving or shrinking the view (the corner) never changes what mpv draws into (no reconfiguring).
        if (settings.videoView == VideoView.Standard) AndroidView(
            factory = { ctx ->
                android.view.SurfaceView(ctx).apply {
                    val dm = ctx.resources.displayMetrics
                    val bw = maxOf(dm.widthPixels, dm.heightPixels)
                    val bh = minOf(dm.widthPixels, dm.heightPixels)
                    holder.setFixedSize(bw, bh)
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(h: android.view.SurfaceHolder) {
                            mpv.attachSurface(h.surface)
                            mpv.setOptionString("force-window", "yes")
                            mpv.setPropertyString("android-surface-size", "${bw}x$bh")
                            mpv.setOptionString("vo", if (direct) "mediacodec_embed" else "gpu")
                        }
                        override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, w: Int, hh: Int) {
                            mpv.setPropertyString("android-surface-size", "${w}x$hh")
                        }
                        override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                            mpv.setOptionString("vo", "null")
                            mpv.setOptionString("force-window", "no")
                            runCatching { mpv.detachSurface() }
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize().videoBleed(),
        ) else AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        var surface: Surface? = null
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            surface = Surface(st)
                            mpv.attachSurface(surface!!)
                            mpv.setOptionString("force-window", "yes")
                            mpv.setPropertyString("android-surface-size", "${w}x$h")
                            mpv.setOptionString("vo", if (direct) "mediacodec_embed" else "gpu")
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            mpv.setPropertyString("android-surface-size", "${w}x$h")
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            mpv.setOptionString("vo", "null")
                            mpv.setOptionString("force-window", "no")
                            runCatching { mpv.detachSurface() }
                            surface?.release()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize().videoBleed(),
        )

        if (direct && subText.isNotBlank()) Text(
            subText.trim(),
            fontSize = (30 * settings.subtitleSize.scale).sp,
            color = Color(settings.subtitleColour.argb),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, start = 120.dp, end = 120.dp)
                .then(if (settings.subtitleBackground == SubtitleBackground.Box) Modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 4.dp) else Modifier),
        )
        // Until the first picture: the loading screen (backdrop, breathing logo); then the controls carry the logo.
        var started by remember(stream) { mutableStateOf(false) }
        if (!buffering && hasPosition) started = true
        val state = FilmState({ (position * 1000).toLong() }, (duration * 1000).toLong(), { (cachedTo * 1000).toLong() }, paused, buffering, started,
            osdVisible = osdVisible,
            // The badges: the film's own picture, frame rate and sound, as mpv reads them.
            quality = runCatching {
                StreamQuality.of(
                    mpv.getPropertyInt("width") ?: 0,
                    mpv.getPropertyInt("height") ?: 0,
                    (mpv.getPropertyDouble("container-fps") ?: 0.0).toFloat(),
                    mpv.getPropertyInt("audio-params/channel-count") ?: 0,
                    // (mpv: Dolby Vision by its profile, else HDR10 / HLG by the picture's gamma.)
                    hdr = when {
                        (mpv.getPropertyString("current-tracks/video/dolby-vision-profile") ?: "").isNotBlank() -> "Dolby Vision"
                        mpv.getPropertyString("video-params/gamma") == "pq" -> "HDR10"
                        mpv.getPropertyString("video-params/gamma") == "hlg" -> "HLG"
                        else -> null
                    },
                    audio = StreamQuality.audioName(mpv.getPropertyString("current-tracks/audio/codec"), mpv.getPropertyString("current-tracks/audio/codec-profile")),
                )
            }.getOrNull())
        FilmOverlays(stream, art, state, mini, panels, menu, scrub, skip, buttons, thumbs, "the built-in player", error, focus,
            holdOsd = { show -> osdUntil = if (show) Long.MAX_VALUE else 0L }) {
            MpvTrackPicker(mpv, subtitlesFirst = tracksSubs, onSwitchPlayer = switchPlayer, onClose = { tracksOpen = false; takeFilmFocus(focus) }, modifier = Modifier.align(Alignment.CenterEnd))
        }
        // Hold OK on a bar button: the Info bar buttons list (FilmControls.kt).
        FilmBarEditor(panels, menu, canSwitch = switchPlayer != null, mini = mini, focus = focus)
        if (!mini && stats.isNotEmpty()) Text(
            stats, fontSize = 13.sp, color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(12.dp),
        )
    }
}

private val IMAGE_SUBS = setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub")

private fun videoOut(s: PlaybackSettings) = if (s.mpvDecoding == MpvDecoding.Hardware) "mediacodec_embed" else "gpu"

/** Soundtrack formats this box says it can send untouched over its current audio output (mpv audio-spdif names). */
private fun spdifFormats(context: android.content.Context): String {
    @Suppress("DEPRECATION")
    val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context)
    return listOfNotNull(
        "ac3".takeIf { caps.supportsEncoding(androidx.media3.common.C.ENCODING_AC3) },
        "eac3".takeIf { caps.supportsEncoding(androidx.media3.common.C.ENCODING_E_AC3) },
        "dts".takeIf { caps.supportsEncoding(androidx.media3.common.C.ENCODING_DTS) },
        "dts-hd".takeIf { caps.supportsEncoding(androidx.media3.common.C.ENCODING_DTS_HD) },
        "truehd".takeIf { caps.supportsEncoding(androidx.media3.common.C.ENCODING_DOLBY_TRUEHD) },
    ).joinToString(",")
}

/** mpv options that must be set before init(). */
private fun configure(mpv: MpvHandle, context: android.content.Context, stream: VodStream, s: PlaybackSettings) {
    val dir = context.filesDir.absolutePath
    // (Only the option's name is logged, not its value: headers can carry logins.)
    fun opt(k: String, v: String) { if (mpv.setOptionString(k, v) < 0) Log.w(TAG, "option $k rejected") }
    opt("config", "no")
    opt("config-dir", dir)
    opt("gpu-shader-cache-dir", context.cacheDir.absolutePath)
    // (No video output until the surface exists; the surface callbacks set videoOut(). With mediacodec_embed set before
    // a surface, a stream that opened first aborted on mpv's "WinID != 0" assertion and closed the app.)
    opt("vo", "null")
    opt("gpu-context", "android")
    opt("opengl-es", "yes")
    // Hardware: the decoder draws straight onto the screen surface (no copy back, no GPU pass). mpv then can't
    // draw subtitles, so the player shows text subtitles itself (sub-text).
    opt("hwdec", when (s.mpvDecoding) {
        MpvDecoding.Gpu -> "mediacodec,mediacodec-copy"
        MpvDecoding.Hardware -> "mediacodec"
        MpvDecoding.Software -> "no"
    })
    opt("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
    opt("ao", "audiotrack,opensles")
    // A bigger audio buffer stops dropouts while a weak box decodes TrueHD/DTS-HD in software.
    opt("audio-buffer", "1.0")
    // HTTPS streams (addons, debrid, servers away from home): checked against this TV's own trusted certificates,
    // copied where mpv can read them (it can't see Android's list itself, which is why checking used to be off).
    // A box without a readable list keeps the old way rather than failing every HTTPS stream.
    val ca = caBundle(context)
    if (ca != null) { opt("tls-ca-file", ca); opt("tls-verify", "yes") } else { opt("tls-verify", "no"); Log.w(TAG, "no CA bundle: HTTPS not verified") }
    opt("input-default-bindings", "no")
    opt("osd-level", "0")
    opt("keep-open", "no")
    opt("save-position-on-quit", "no")
    // Memory: the box has 2 GB, so keep the read-ahead modest.
    opt("cache", "yes")
    opt("demuxer-max-bytes", s.buffer.mpvBytes)
    opt("demuxer-max-back-bytes", "16MiB")
    opt("user-agent", "Viewarr/0.9")
    if (stream.headers.isNotEmpty()) {
        opt("http-header-fields", stream.headers.entries.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" })
    }
    // Audio
    s.audioCode()?.let { opt("alang", "$it,${iso3(it)}") }
    if (s.passthrough == Passthrough.On) spdifFormats(context).also { Log.i(TAG, "passthrough formats: '$it'") }.takeIf { it.isNotEmpty() }?.let { opt("audio-spdif", it) }
    // Match frame rate: start paused, switch the TV, let HDMI settle, then play (see MpvPlayer).
    if (s.frameRate.films) opt("pause", "yes")
    if (s.downmix == Downmix.Stereo) opt("audio-channels", "stereo") else opt("audio-channels", "auto")
    if (s.nightMode == NightMode.On) opt("af", "lavfi=[dynaudnorm=f=250:g=31:m=4]")
    // Subtitles
    val sl = s.subtitleCode()
    opt("slang", "$sl,${iso3(sl)}")
    when (s.subtitles) {
        SubtitleMode.Off -> opt("sid", "no")
        SubtitleMode.Forced -> { opt("subs-with-matching-audio", "forced"); opt("subs-fallback", "no"); opt("subs-fallback-forced", "yes") }
        SubtitleMode.On -> opt("subs-fallback", "yes")
    }
    opt("sub-scale", s.subtitleSize.scale.toString())
    opt("sub-color", "#%08X".format(s.subtitleColour.argb))
    if (s.subtitleBackground == SubtitleBackground.Box) {
        opt("sub-border-style", "opaque-box")
        opt("sub-back-color", "#C0000000")
    }
    // Video
    when (s.pictureSize) {
        PictureSize.Fit -> Unit
        PictureSize.Zoom -> opt("panscan", "1.0")
        PictureSize.Stretch -> opt("keepaspect", "no")
    }
    if (s.deinterlace == Deinterlace.Auto) opt("deinterlace", "auto")
    // Audio extras
    if (s.audioDelay.ms != 0) opt("audio-delay", (s.audioDelay.ms / 1000.0).toString())
    if (s.volumeBoost.percent != 100) { opt("volume-max", "200"); opt("volume", s.volumeBoost.percent.toString()) }
    opt("sub-fonts-dir", "/system/fonts")
    opt("sub-font", "Roboto")
    // Resume point
    val start = (stream.startMs ?: if (s.resume == ResumeMode.Start) 0L else stream.resumeMs) / 1000
    if (start > 0) opt("start", start.toString())
}

/**
 * libmpv 0.5.x is one process-wide player with static calls; this wraps it so the screen code reads like an object.
 * Only one MpvPlayer is ever on screen, and it destroys mpv when it leaves.
 *
 * The one mpv (libmpv is a single global instance: asking for a second while one is alive ends the app). A new handle
 * destroys the one before it first (the next episode's player is made before the old one's clean-up runs); a handle
 * that's been replaced, or destroyed, ignores every call, its own late clean-up included, so it can't touch the new one.
 */
class MpvHandle(context: android.content.Context) {
    init {
        synchronized(MpvHandle) {
            if (current != null) { runCatching { MPVLib.detachSurface() }; runCatching { MPVLib.destroy() }; current = null }
            MPVLib.create(context)
            current = this
        }
    }
    /** Still the mpv in use (not replaced or destroyed). */
    val alive: Boolean get() = current === this
    private inline fun <T> ifAlive(fallback: T, block: () -> T): T = if (alive) block() else fallback
    fun init() = ifAlive(Unit) { MPVLib.init() }
    fun destroy() = synchronized(MpvHandle) { if (alive) { MPVLib.destroy(); current = null } }
    fun attachSurface(s: Surface) = ifAlive(Unit) { MPVLib.attachSurface(s) }
    fun detachSurface() = ifAlive(Unit) { MPVLib.detachSurface() }
    fun command(args: Array<String>) = ifAlive(Unit) { MPVLib.command(args) }
    fun setOptionString(k: String, v: String): Int = ifAlive(-1) { MPVLib.setOptionString(k, v) }
    fun getPropertyInt(k: String): Int? = ifAlive(null) { MPVLib.getPropertyInt(k) }
    fun getPropertyDouble(k: String): Double? = ifAlive(null) { MPVLib.getPropertyDouble(k) }
    fun getPropertyBoolean(k: String): Boolean? = ifAlive(null) { MPVLib.getPropertyBoolean(k) }
    fun getPropertyString(k: String): String? = ifAlive(null) { MPVLib.getPropertyString(k) }
    fun setPropertyInt(k: String, v: Int) = ifAlive(Unit) { MPVLib.setPropertyInt(k, v) }
    fun setPropertyDouble(k: String, v: Double) = ifAlive(Unit) { MPVLib.setPropertyDouble(k, v) }
    fun setPropertyBoolean(k: String, v: Boolean) = ifAlive(Unit) { MPVLib.setPropertyBoolean(k, v) }
    fun setPropertyString(k: String, v: String) = ifAlive(Unit) { MPVLib.setPropertyString(k, v) }
    fun observeProperty(k: String, format: Int) = ifAlive(Unit) { MPVLib.observeProperty(k, format) }
    fun addObserver(o: MPVLib.EventObserver) = MPVLib.addObserver(o)
    fun removeObserver(o: MPVLib.EventObserver) = MPVLib.removeObserver(o)

    companion object {
        @Volatile private var current: MpvHandle? = null
    }
}

private fun iso3(code: String) = runCatching { java.util.Locale(code).isO3Language }.getOrDefault(code)

/** Audio and subtitle tracks from mpv's track-list. */
@Composable
private fun MpvTrackPicker(mpv: MpvHandle, subtitlesFirst: Boolean = false, onSwitchPlayer: (() -> Unit)?, onClose: () -> Unit, modifier: Modifier) {
    data class Track(val type: String, val id: Int, val label: String, val selected: Boolean)
    val tracks = remember {
        val n = mpv.getPropertyInt("track-list/count") ?: 0
        (0 until n).mapNotNull { i ->
            val type = mpv.getPropertyString("track-list/$i/type") ?: return@mapNotNull null
            if (type != "audio" && type != "sub") return@mapNotNull null
            val id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
            val label = listOfNotNull(
                mpv.getPropertyString("track-list/$i/title"),
                mpv.getPropertyString("track-list/$i/lang")?.uppercase(),
                mpv.getPropertyString("track-list/$i/codec")?.uppercase(),
                mpv.getPropertyInt("track-list/$i/demux-channel-count")?.let { "${it}ch" },
                if (mpv.getPropertyBoolean("track-list/$i/forced") == true) "forced" else null,
            ).distinct().joinToString(" · ").ifEmpty { "Track $id" }
            Track(type, id, label, mpv.getPropertyBoolean("track-list/$i/selected") == true)
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    val subsOff = tracks.none { it.type == "sub" && it.selected }
    LazyColumn(
        modifier.fillMaxHeight().padding(vertical = 28.dp).padding(end = 28.dp).width(420.dp).glass().padding(20.dp).trapFocus().then(Modifier.ignoreHeldOk()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { Text("Audio", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        tracks.filter { it.type == "audio" }.forEachIndexed { i, t ->
            item {
                MenuItem(
                    text = (if (t.selected) "✓ " else "   ") + t.label, selected = false,
                    modifier = if (i == 0 && !subtitlesFirst) Modifier.focusRequester(first) else Modifier,
                    onClick = { mpv.setPropertyInt("aid", t.id); onClose() },
                )
            }
        }
        item { Text("Subtitles", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        item { MenuItem(text = (if (subsOff) "✓ " else "   ") + "Off", selected = false, modifier = if (subtitlesFirst || tracks.none { it.type == "audio" }) Modifier.focusRequester(first) else Modifier, onClick = { mpv.setPropertyString("sid", "no"); onClose() }) }
        tracks.filter { it.type == "sub" }.forEach { t ->
            item { MenuItem(text = (if (t.selected) "✓ " else "   ") + t.label, selected = false, onClick = { mpv.setPropertyInt("sid", t.id); onClose() }) }
        }
        if (onSwitchPlayer != null) {
            item { Text("Player", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            item { MenuItem(text = "   Switch to the built-in player (carries on from here)", selected = false, onClick = { onSwitchPlayer() }) }
        }
    }
}

/** Picks the player from Settings → Playback. */
@Composable
fun PlayerHost(
    stream: VodStream,
    onClose: () -> Unit,
    art: PlayerArt? = null,
    onProgress: (state: String, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
) {
    // The engine from Settings, swappable for this film from the hold-OK menu (picks up at the same moment).
    val default = LocalPlayback.current.engine
    var engine by remember(stream) { mutableStateOf(default) }
    var current by remember(stream) { mutableStateOf(stream) }
    val switch: (Long, Int?, Int?) -> Unit = { pos, audio, subs ->
        // Same moment, same soundtrack and subtitles.
        current = current.copy(startMs = pos, audioTrack = audio ?: current.audioTrack, subtitleTrack = subs ?: current.subtitleTrack)
        engine = if (engine == PlayerEngine.Mpv) PlayerEngine.Builtin
        else PlayerEngine.Mpv
    }
    androidx.compose.runtime.key(engine) {
        when (engine) {
            PlayerEngine.Mpv -> MpvPlayer(current, onClose, onProgress, switch, art)
            PlayerEngine.Builtin -> VodPlayer(current, onClose, onProgress, switch, art)
        }
    }
}

/**
 * This TV's trusted certificates as one PEM file for mpv (tls-ca-file), built from Android's own store (Android 14
 * keeps it in the Conscrypt APEX, older versions in /system) and rebuilt when the store changes. Null if none can be
 * read (then mpv isn't told to check, as before).
 */
private fun caBundle(context: android.content.Context): String? = runCatching {
    val dir = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
        .map { java.io.File(it) }.firstOrNull { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) } ?: return@runCatching null
    val out = java.io.File(context.filesDir, "cacert.pem")
    val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
    val stamp = "${dir.path}|${files.size}|${files.maxOfOrNull { it.lastModified() } ?: 0}"
    val stampFile = java.io.File(context.filesDir, "cacert.stamp")
    if (!out.exists() || out.length() == 0L || stampFile.takeIf { it.exists() }?.readText() != stamp) {
        val pem = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")
        val certs = files.flatMap { f -> runCatching { pem.findAll(f.readText()).map { it.value }.toList() }.getOrDefault(emptyList()) }
        // (A store this small isn't the real one: don't check against it.)
        if (certs.size < 20) return@runCatching null
        out.writeText(certs.joinToString("\n", postfix = "\n"))
        stampFile.writeText(stamp)
        Log.i(TAG, "CA bundle: ${certs.size} certificates from ${dir.path}")
    }
    out.absolutePath
}.getOrNull()
