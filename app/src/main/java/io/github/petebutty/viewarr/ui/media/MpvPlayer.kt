package io.github.petebutty.viewarr.ui.media

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
) {
    val context = LocalContext.current
    val settings = LocalPlayback.current
    val seekSec = LocalRemote.current.seek.ms / 1000.0
    val main = remember { Handler(Looper.getMainLooper()) }

    var position by remember { mutableDoubleStateOf(0.0) }
    var duration by remember { mutableDoubleStateOf(0.0) }
    var paused by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var osdUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 4_000) }
    var tracksOpen by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    val mpv = remember(stream) {
        MpvHandle(context).apply {
            configure(this, context, stream, settings)
            init()
        }
    }

    DisposableEffect(mpv) {
        val observer = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}
            override fun eventProperty(property: String, value: Boolean) {
                main.post {
                    when (property) {
                        "pause" -> {
                            paused = value
                            onProgress(if (value) "paused" else "playing", (position * 1000).toLong(), (duration * 1000).toLong())
                        }
                        "paused-for-cache", "seeking" -> buffering = value
                        "eof-reached" -> if (value) {
                            ended = true
                            onProgress("stopped", (duration * 1000).toLong(), (duration * 1000).toLong())
                            onClose()
                        }
                    }
                }
            }
            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {
                main.post {
                    when (property) {
                        "time-pos" -> { position = value; if (value > 0) buffering = false }
                        "duration" -> duration = value
                    }
                }
            }
            override fun event(eventId: Int) {
                // MPV_EVENT_END_FILE = 7: if nothing ever played, the stream failed to open.
                if (eventId == 7) main.post { if (position <= 0.0 && !ended) error = "Couldn't play this. Press Back and try another source." }
            }
        }
        mpv.addObserver(observer)
        mpv.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        mpv.observeProperty("seeking", MPVLib.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)
        mpv.command(arrayOf("loadfile", stream.url))
        onDispose {
            if (!ended && position > 0) onProgress("stopped", (position * 1000).toLong(), (duration * 1000).toLong())
            mpv.removeObserver(observer)
            runCatching { mpv.detachSurface() }
            runCatching { mpv.destroy() }
        }
    }

    // Progress to Plex/Trakt every 15 s while playing.
    LaunchedEffect(mpv) {
        while (true) {
            delay(15_000)
            if (!paused && position > 0) onProgress("playing", (position * 1000).toLong(), (duration * 1000).toLong())
        }
    }
    LaunchedEffect(Unit) { repeat(3) { awaitFrame(); runCatching { focus.requestFocus() } } }
    LaunchedEffect(hasFocus, tracksOpen) {
        if (!hasFocus && !tracksOpen) { delay(150); runCatching { focus.requestFocus() } }
    }
    // Repaint the clock for the auto-hiding controls.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }

    BackHandler { if (tracksOpen) tracksOpen = false else onClose() }

    fun showOsd() { osdUntil = System.currentTimeMillis() + 4_000 }
    fun seek(by: Double) {
        mpv.command(arrayOf("seek", by.toString(), "relative"))
        showOsd()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable(enabled = !tracksOpen)
            .onPreviewKeyEvent { e ->
                if (tracksOpen) return@onPreviewKeyEvent false
                if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> okDown = true
                        e.type == KeyEventType.KeyDown && okDown -> { okDown = false; tracksOpen = true }
                        e.type == KeyEventType.KeyUp && okDown -> {
                            okDown = false
                            mpv.setPropertyBoolean("pause", !paused)
                            showOsd()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft, Key.MediaRewind -> seek(-seekSec)
                    Key.DirectionRight, Key.MediaFastForward -> seek(seekSec)
                    Key.DirectionUp, Key.DirectionDown, Key.Info -> showOsd()
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { mpv.setPropertyBoolean("pause", !paused); showOsd() }
                    Key.Menu -> tracksOpen = true
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        var surface: Surface? = null
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            surface = Surface(st)
                            mpv.attachSurface(surface!!)
                            mpv.setOptionString("force-window", "yes")
                            mpv.setPropertyString("android-surface-size", "${w}x$h")
                            mpv.setOptionString("vo", "gpu")
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
            modifier = Modifier.fillMaxSize(),
        )

        val osdVisible = paused || buffering || now < osdUntil
        if (osdVisible) MpvOsd(stream.title, position, duration, paused, buffering, Modifier.align(Alignment.BottomCenter))
        error?.let {
            Text(it, fontSize = 18.sp, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(20.dp))
        }
        if (tracksOpen) MpvTrackPicker(mpv, onClose = { tracksOpen = false; runCatching { focus.requestFocus() } }, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** mpv options that must be set before init(). */
private fun configure(mpv: MpvHandle, context: android.content.Context, stream: VodStream, s: PlaybackSettings) {
    val dir = context.filesDir.absolutePath
    fun opt(k: String, v: String) { if (mpv.setOptionString(k, v) < 0) Log.w(TAG, "option $k=$v rejected") }
    opt("config", "no")
    opt("config-dir", dir)
    opt("gpu-shader-cache-dir", context.cacheDir.absolutePath)
    opt("vo", "gpu")
    opt("gpu-context", "android")
    opt("opengl-es", "yes")
    opt("hwdec", if (s.mpvDecoding == MpvDecoding.Hardware) "mediacodec,mediacodec-copy" else "no")
    opt("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
    opt("ao", "audiotrack,opensles")
    // A bigger audio buffer stops dropouts while a weak box decodes TrueHD/DTS-HD in software.
    opt("audio-buffer", "1.0")
    opt("tls-verify", "no")
    opt("input-default-bindings", "no")
    opt("osd-level", "0")
    opt("keep-open", "no")
    opt("save-position-on-quit", "no")
    // Memory: the box has 2 GB, so keep the read-ahead modest.
    opt("cache", "yes")
    opt("demuxer-max-bytes", "64MiB")
    opt("demuxer-max-back-bytes", "16MiB")
    opt("user-agent", "Viewarr/0.9")
    if (stream.headers.isNotEmpty()) {
        opt("http-header-fields", stream.headers.entries.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" })
    }
    // Audio
    s.audioCode()?.let { opt("alang", "$it,${iso3(it)}") }
    if (s.passthrough == Passthrough.On) opt("audio-spdif", "ac3,eac3,dts,dts-hd,truehd")
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
    opt("sub-fonts-dir", "/system/fonts")
    opt("sub-font", "Roboto")
    // Resume point
    val start = if (s.resume == ResumeMode.Start) 0 else stream.resumeMs / 1000
    if (start > 0) opt("start", start.toString())
}

/**
 * libmpv 0.5.x is one process-wide player with static calls; this wraps it so the screen code reads like an object.
 * Only one MpvPlayer is ever on screen, and it destroys mpv when it leaves.
 */
class MpvHandle(context: android.content.Context) {
    init { MPVLib.create(context) }
    fun init() = MPVLib.init()
    fun destroy() = MPVLib.destroy()
    fun attachSurface(s: Surface) = MPVLib.attachSurface(s)
    fun detachSurface() = MPVLib.detachSurface()
    fun command(args: Array<String>) = MPVLib.command(args)
    fun setOptionString(k: String, v: String): Int = MPVLib.setOptionString(k, v)
    fun getPropertyInt(k: String): Int? = MPVLib.getPropertyInt(k)
    fun getPropertyBoolean(k: String): Boolean? = MPVLib.getPropertyBoolean(k)
    fun getPropertyString(k: String): String? = MPVLib.getPropertyString(k)
    fun setPropertyInt(k: String, v: Int) = MPVLib.setPropertyInt(k, v)
    fun setPropertyBoolean(k: String, v: Boolean) = MPVLib.setPropertyBoolean(k, v)
    fun setPropertyString(k: String, v: String) = MPVLib.setPropertyString(k, v)
    fun observeProperty(k: String, format: Int) = MPVLib.observeProperty(k, format)
    fun addObserver(o: MPVLib.EventObserver) = MPVLib.addObserver(o)
    fun removeObserver(o: MPVLib.EventObserver) = MPVLib.removeObserver(o)
}

private fun iso3(code: String) = runCatching { java.util.Locale(code).isO3Language }.getOrDefault(code)

@Composable
private fun MpvOsd(title: String, position: Double, duration: Double, paused: Boolean, buffering: Boolean, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (buffering) "◌" else if (paused) "❚❚" else "▶", fontSize = 22.sp, color = Color.White, modifier = Modifier.width(40.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (buffering) "Loading…" else "", fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
            val f = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(AppColors.Accent))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(clock(position), fontSize = 14.sp, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text("Hold OK: audio & subtitles", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) "-" + clock(duration - position) else "", fontSize = 14.sp, color = Color.White)
        }
    }
}

private fun clock(sec: Double): String {
    val t = sec.toLong().coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Audio and subtitle tracks from mpv's track-list. */
@Composable
private fun MpvTrackPicker(mpv: MpvHandle, onClose: () -> Unit, modifier: Modifier) {
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
        modifier.fillMaxHeight().width(420.dp).background(AppColors.Panel.copy(alpha = 0.95f)).padding(20.dp).trapFocus(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { Text("Audio", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)) }
        tracks.filter { it.type == "audio" }.forEachIndexed { i, t ->
            item {
                MenuItem(
                    text = (if (t.selected) "✓ " else "   ") + t.label, selected = false,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { mpv.setPropertyInt("aid", t.id); onClose() },
                )
            }
        }
        item { Text("Subtitles", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
        item { MenuItem(text = (if (subsOff) "✓ " else "   ") + "Off", selected = false, onClick = { mpv.setPropertyString("sid", "no"); onClose() }) }
        tracks.filter { it.type == "sub" }.forEach { t ->
            item { MenuItem(text = (if (t.selected) "✓ " else "   ") + t.label, selected = false, onClick = { mpv.setPropertyInt("sid", t.id); onClose() }) }
        }
    }
}

/** Picks the player from Settings → Playback. */
@Composable
fun PlayerHost(
    stream: VodStream,
    onClose: () -> Unit,
    onProgress: (state: String, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
) {
    when (LocalPlayback.current.engine) {
        io.github.petebutty.viewarr.data.settings.PlayerEngine.Mpv -> MpvPlayer(stream, onClose, onProgress)
        io.github.petebutty.viewarr.data.settings.PlayerEngine.Builtin -> VodPlayer(stream, onClose, onProgress)
    }
}
