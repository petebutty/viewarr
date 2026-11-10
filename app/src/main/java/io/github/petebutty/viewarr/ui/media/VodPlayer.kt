package io.github.petebutty.viewarr.ui.media

import kotlinx.coroutines.delay
import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import io.github.petebutty.viewarr.ui.common.trapFocus
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.VodStream
import kotlinx.coroutines.android.awaitFrame

/**
 * Full-screen player for films, episodes and recordings: the stock Media3 controls (seek bar, pause,
 * audio and subtitle choice).
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayer(
    stream: VodStream,
    onClose: () -> Unit,
    /** Progress for Plex / Trakt: state is "playing", "paused" or "stopped". */
    onProgress: (state: String, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
    /** Hold-OK menu → "Switch to mpv": carry on there from this position. */
    /** Swap players for this film: where it is, and the audio / subtitle tracks in use (ordinals; subtitles -1 = off). */
    onSwitchPlayer: ((positionMs: Long, audio: Int?, subtitles: Int?) -> Unit)? = null,
    art: io.github.petebutty.viewarr.data.media.PlayerArt? = null,
) {
    val context = LocalContext.current
    var error by remember(stream) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val seekMs = io.github.petebutty.viewarr.data.settings.LocalRemote.current.seek.ms
    val pb = io.github.petebutty.viewarr.data.settings.LocalPlayback.current
    val activity = context as? android.app.Activity
    var restoreMode by remember { mutableStateOf<Int?>(null) }
    val startAt = stream.startMs ?: if (pb.resume == io.github.petebutty.viewarr.data.settings.ResumeMode.Start) 0L else stream.resumeMs
    var view by remember { mutableStateOf<PlayerView?>(null) }
    var tracksOpen by remember { mutableStateOf(false) }
    // Audio & subtitles opened from the Subtitles button: start on the subtitles.
    var tracksSubs by remember { mutableStateOf(false) }
    val menu = remember { PlayerMenu() }
    // What's playing (poster, synopsis): the Info button, the Details button, or a moment after pausing.
    var detailsOn by remember { mutableStateOf(false) }
    var pausedLong by remember { mutableStateOf(false) }
    val scrub = remember { Scrubber() }
    val thumbs = remember(stream) { io.github.petebutty.viewarr.playback.SeekThumbs.forStream(stream.url, stream.headers, stream.thumbs.takeIf { io.github.petebutty.viewarr.ui.theme.AppColors.palette.serverPreviews }, stream.boxPreviews && io.github.petebutty.viewarr.ui.theme.AppColors.palette.boxPreviews) }
    DisposableEffect(thumbs) { onDispose { thumbs?.close() } }
    // The app's own controls (as mpv's): state read from the player a few times a second.
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var osdUntil by remember { mutableStateOf(System.currentTimeMillis() + io.github.petebutty.viewarr.ui.theme.AppColors.palette.controlsMs) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    fun showOsd() { osdUntil = System.currentTimeMillis() + io.github.petebutty.viewarr.ui.theme.AppColors.palette.controlsMs }
    // Shrunk to the corner (picture-in-picture): just the picture; the page has the remote.
    val mini = VodHost.mini
    LaunchedEffect(mini) {
        if (mini) { menu.open = false; tracksOpen = false; detailsOn = false; if (scrub.active) scrub.cancel(); osdUntil = 0L }
    }
    val player = remember(stream) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Viewarr/0.7")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(stream.headers)
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                // FFmpeg audio (DTS, TrueHD…) only when the box can't decode or pass through a soundtrack itself.
                object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(ctx: android.content.Context, floatOutput: Boolean, audioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? =
                        androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(ctx)
                            // Passthrough off: always decode to PCM in the box (no Dolby/DTS bitstream to a receiver).
                            .apply { if (pb.passthrough == io.github.petebutty.viewarr.data.settings.Passthrough.Off) setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES) }
                            .setEnableFloatOutput(floatOutput)
                            .setEnableAudioTrackPlaybackParams(audioTrackPlaybackParams)
                            .build()
                }
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(io.github.petebutty.viewarr.data.net.reachOf(http)))
            // Settings → Playback → Buffer.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(minOf(15_000, pb.buffer.exoMs), pb.buffer.exoMs, 2_500, 5_000)
                    .build(),
            )
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
                    pb.audioCode()?.let { setPreferredAudioLanguage(it) }
                    if (pb.downmix == io.github.petebutty.viewarr.data.settings.Downmix.Stereo) setMaxAudioChannelCount(2)
                    when (pb.subtitles) {
                        io.github.petebutty.viewarr.data.settings.SubtitleMode.Off -> setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                        io.github.petebutty.viewarr.data.settings.SubtitleMode.On -> setPreferredTextLanguage(pb.subtitleCode()).setSelectUndeterminedTextLanguage(true)
                        io.github.petebutty.viewarr.data.settings.SubtitleMode.Forced -> Unit // only tracks flagged forced/default are picked
                    }
                }.build()
                setMediaItem(mediaItem(stream.url, hls = stream.url.contains(".m3u8")), startAt)
                prepare()
                playWhenReady = true
            }
    }
    // Report where you are every 15 s while playing, and on pause/stop.
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0)
            paused = !player.playWhenReady
            buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(250)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            if (player.isPlaying) onProgress("playing", player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }
    DisposableEffect(player) {
        var retriedAsHls = stream.url.contains(".m3u8")
        var usedFallback = false
        // Audio & subtitles picked on the film's page: applied once the file's tracks are known.
        var picksApplied = stream.audioTrack == null && stream.subtitleTrack == null
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (picksApplied || tracks.groups.isEmpty()) return
                picksApplied = true
                val text = androidx.media3.common.C.TRACK_TYPE_TEXT
                fun nth(type: Int, n: Int) = tracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).map { g to it } }.getOrNull(n)
                val b = player.trackSelectionParameters.buildUpon()
                stream.audioTrack?.let { n -> nth(androidx.media3.common.C.TRACK_TYPE_AUDIO, n)?.let { (g, i) -> b.setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)) } }
                stream.subtitleTrack?.let { n ->
                    if (n < 0) b.setTrackTypeDisabled(text, true)
                    else nth(text, n)?.let { (g, i) -> b.setTrackTypeDisabled(text, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)) }
                }
                player.trackSelectionParameters = b.build()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_ENDED) return
                onProgress(if (isPlaying) "playing" else "paused", player.currentPosition, player.duration.coerceAtLeast(0))
            }

            override fun onRenderedFirstFrame() {
                // Settings → Playback → Match frame rate.
                val fps = player.videoFormat?.frameRate ?: return
                if (pb.frameRate == io.github.petebutty.viewarr.data.settings.FrameRateMatch.On && activity != null && restoreMode == null) {
                    restoreMode = io.github.petebutty.viewarr.playback.FrameRateMatcher.match(activity, fps)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onClose()
                if (state == Player.STATE_ENDED) onProgress("stopped", player.duration.coerceAtLeast(0), player.duration.coerceAtLeast(0))
            }

            override fun onPlayerError(e: PlaybackException) {
                // e.g. Plex: the file itself won't play here, so ask Plex to convert it.
                val fb = stream.fallback
                if (fb != null && !usedFallback) {
                    usedFallback = true
                    val at = player.currentPosition.takeIf { it > 0 } ?: fb.resumeMs
                    player.setMediaItem(mediaItem(fb.url, hls = fb.url.contains(".m3u8")), at)
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
                // Addon links often have no file extension; many are HLS playlists. Try that once before giving up.
                if (!retriedAsHls && (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
                    retriedAsHls = true
                    player.setMediaItem(mediaItem(stream.url, hls = true), stream.resumeMs)
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                        "This file's format isn't supported by the TV. Press Back and choose another source."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when ((e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode) {
                        404 -> "The server lists this title but couldn't find its file (moved or deleted?). Press Back and try another source."
                        401, 403 -> "The server didn't allow this stream (sign-in or permissions). Press Back and try another source."
                        else -> "The server refused this stream. Press Back and try another source."
                    }
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Couldn't connect to the stream. Press Back and try again."
                    else -> "Couldn't play this (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}). Press Back."
                }
            }
        }
        player.addListener(listener)
        onDispose {
            restoreMode?.let { m -> activity?.let { io.github.petebutty.viewarr.playback.FrameRateMatcher.restore(it, m) } }
            if (player.playbackState != Player.STATE_ENDED && player.currentPosition > 0) {
                onProgress("stopped", player.currentPosition, player.duration.coerceAtLeast(0))
            }
            player.removeListener(listener)
            player.release()
        }
    }
    // Back: closes audio & subtitles, then hides the controls, then stops (resume point saved). As Netflix / Plex.
    BackHandler(enabled = !mini) {
        when {
            tracksOpen -> tracksOpen = false
            scrub.active -> { scrub.cancel(); showOsd() }
            detailsOn -> detailsOn = false
            menu.open -> { menu.open = false; osdUntil = 0L }
            System.currentTimeMillis() < osdUntil && player.isPlaying -> osdUntil = 0L
            // Back again: into the corner, still playing (hold Back to bring it back).
            // (Settings → Playback → Back while watching: or stop.)
            else -> if (io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.PLAYER_BACK) == 1) onClose() else VodHost.minimise().also { if (!VodHost.active) onClose() }
        }
    }
    // The tracks in use, as ordinals of each kind (subtitles -1 = off): handed over when switching to mpv.
    fun picks(): Pair<Int?, Int?> {
        fun selected(type: Int): Int? {
            val all = player.currentTracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).map { g to it } }
            if (all.isEmpty()) return null
            return all.indexOfFirst { (g, i) -> g.isTrackSelected(i) }
        }
        return selected(androidx.media3.common.C.TRACK_TYPE_AUDIO)?.takeIf { it >= 0 } to selected(androidx.media3.common.C.TRACK_TYPE_TEXT)
    }
    val buttons = listOfNotNull(PlayerButton.PlayPause, PlayerButton.Stop, PlayerButton.Details, PlayerButton.Audio, PlayerButton.Subtitles, PlayerButton.Info, if (onSwitchPlayer != null) PlayerButton.SwitchPlayer else null)
    fun press(b: PlayerButton) {
        when (b) {
            PlayerButton.PlayPause -> player.playWhenReady = !player.playWhenReady
            PlayerButton.Audio -> { tracksSubs = false; tracksOpen = true }
            PlayerButton.Subtitles -> { tracksSubs = true; tracksOpen = true }
            PlayerButton.Info -> menu.infoOn = !menu.infoOn
            PlayerButton.Details -> { detailsOn = !detailsOn; if (detailsOn) menu.open = false }
            PlayerButton.SwitchPlayer -> picks().let { (a, t) -> onSwitchPlayer?.invoke(player.currentPosition, a, t) }
            PlayerButton.Stop -> { onClose(); return }
        }
        showOsd()
    }
    // The remote: shared with the mpv player (VodKeys).
    val keys = remember { VodKeys(scrub, menu) }
    val remote = object : VodRemote {
        override val positionMs get() = player.currentPosition
        override val durationMs get() = player.duration.coerceAtLeast(0)
        override fun seekTo(ms: Long) = player.seekTo(ms)
        override fun skip(ms: Long) = player.seekTo(maxOf(0, player.currentPosition + ms))
        override fun togglePause() { player.playWhenReady = !player.playWhenReady }
        override fun pause() { player.playWhenReady = false }
    }
    val hooks = VodHooks(
        buttons = buttons, press = ::press, seekMs = seekMs, showOsd = ::showOsd,
        openTracks = { subs -> subs?.let { tracksSubs = it }; tracksOpen = true },
        toggleDetails = { detailsOn = !detailsOn }, showDetails = { detailsOn = true }, stop = onClose,
    )
    // The controls close after a while without a key (not while paused).
    LaunchedEffect(paused) {
        pausedLong = false
        if (paused) { delay(2_500); pausedLong = true } else detailsOn = false
    }
    LaunchedEffect(menu.open, menu.lastKey, paused) {
        if (menu.open && !paused) { kotlinx.coroutines.delay(maxOf(8_000L, 2 * io.github.petebutty.viewarr.ui.theme.AppColors.palette.controlsMs)); menu.open = false }
    }
    // The player must hold focus itself, or the remote keeps driving the screen underneath (the controls never respond).
    LaunchedEffect(mini) {
        if (mini) return@LaunchedEffect
        repeat(3) {
            awaitFrame()
            runCatching { focus.requestFocus() }
        }
    }
    var hasFocus by remember { mutableStateOf(false) }
    // If anything else takes the remote while playing (and no picker is open), take it back.
    val searchOpen = io.github.petebutty.viewarr.ui.common.LocalQuickSearchOpen.current
    LaunchedEffect(hasFocus, tracksOpen, searchOpen, mini) {
        if (!hasFocus && !tracksOpen && !searchOpen && !mini) {
            kotlinx.coroutines.delay(150)
            runCatching { focus.requestFocus() }
        }
    }
    // The remote is handled here, not by the PlayerView's own buttons: inside Compose those never get key focus,
    // which left the remote doing nothing during playback.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable(enabled = !tracksOpen && !mini)
            .onPreviewKeyEvent { e ->
                if (VodHost.eatsBack(e)) return@onPreviewKeyEvent true
                if (tracksOpen) return@onPreviewKeyEvent false
                keys.handle(e, remote, hooks)
            },
    ) {
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(io.github.petebutty.viewarr.R.layout.vod_player_view, null) as PlayerView).apply {
                    this.player = player
                    view = this
                    // Picture and subtitles only: the controls are the app's own (PlayerOsd), keys come from the box above.
                    useController = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    isFocusable = false
                    keepScreenOn = true
                    subtitleView?.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * pb.subtitleSize.scale)
                    val box = pb.subtitleBackground == io.github.petebutty.viewarr.data.settings.SubtitleBackground.Box
                    subtitleView?.setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            pb.subtitleColour.argb.toInt(),
                            if (box) 0xC0000000.toInt() else android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            if (box) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null,
                        ),
                    )
                    resizeMode = when (pb.pictureSize) {
                        io.github.petebutty.viewarr.data.settings.PictureSize.Fit -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        io.github.petebutty.viewarr.data.settings.PictureSize.Zoom -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        io.github.petebutty.viewarr.data.settings.PictureSize.Stretch -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        var started by remember(stream) { mutableStateOf(false) }
        if (!buffering && durationMs > 0) started = true
        if (!mini && started && (detailsOn || (paused && pausedLong && !tracksOpen && !scrub.active && !menu.open))) PlayerDetails(stream.title, art)
        if (!mini && started && (paused || buffering || now < osdUntil || scrub.active || menu.open)) PlayerOsd(
            stream.title, positionMs, durationMs, paused, buffering, scrub.targetMs, thumbs, Modifier.align(Alignment.BottomCenter), logo = art?.logo,
            buttons = if (menu.open) buttons.map { it.osd(paused, menu.infoOn, "mpv") } else null,
            buttonFocus = if (menu.open && !menu.onBar) menu.button else -1,
            barFocused = menu.open && menu.onBar,
        )
        PlayerLoading(stream.title, art, visible = !mini && !started && error == null)
        if (tracksOpen && !mini) TrackPicker(player, subtitlesFirst = tracksSubs, onSwitchPlayer = onSwitchPlayer?.let { f -> { picks().let { (a, t) -> f(player.currentPosition, a, t) } } }, onClose = { tracksOpen = false; runCatching { focus.requestFocus() } }, modifier = Modifier.align(Alignment.CenterEnd))
        if (!mini && (pb.stats == io.github.petebutty.viewarr.data.settings.StatsOverlay.On || menu.infoOn)) {
            var info by remember { mutableStateOf("") }
            LaunchedEffect(player) {
                while (true) {
                    val v = player.videoFormat
                    val a = player.audioFormat
                    val c = player.videoDecoderCounters
                    info = listOf(
                        "Video: ${v?.sampleMimeType?.substringAfter('/') ?: "?"}  ${v?.width ?: "?"}×${v?.height ?: "?"}  ${v?.frameRate?.takeIf { it > 0 }?.let { "%.3f".format(it) } ?: "?"} fps",
                        "Audio: ${a?.sampleMimeType?.substringAfter('/') ?: "?"}  ${a?.channelCount ?: "?"} ch",
                        "Dropped frames: ${c?.droppedBufferCount ?: 0}",
                        "Buffered: ${(player.totalBufferedDuration / 1000)} s",
                    ).joinToString("\n")
                    kotlinx.coroutines.delay(1_000)
                }
            }
            Text(info, fontSize = 13.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(24.dp).background(Color.Black.copy(alpha = 0.6f)).padding(12.dp))
        }
        error?.let {
            Text(it, fontSize = 18.sp, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(20.dp))
        }
    }
}

private fun mediaItem(url: String, hls: Boolean): MediaItem =
    MediaItem.Builder().setUri(url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()

/** Audio and subtitle choice for the film player (hold OK, or the Menu key). */
@OptIn(UnstableApi::class)
@Composable
private fun TrackPicker(player: ExoPlayer, subtitlesFirst: Boolean = false, onSwitchPlayer: (() -> Unit)?, onClose: () -> Unit, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    val groups = player.currentTracks.groups
    data class Choice(val label: String, val apply: () -> Unit)
    val choices = buildList {
        add(Choice("Audio") {})
        groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val label = listOfNotNull(f.label, f.language?.uppercase(), f.sampleMimeType?.substringAfter('/')?.uppercase(), if (f.channelCount > 0) "${f.channelCount}ch" else null)
                    .joinToString(" · ").ifEmpty { "Track ${i + 1}" }
                add(Choice((if (g.isTrackSelected(i)) "✓ " else "   ") + label) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                })
            }
        }
        add(Choice("Subtitles") {})
        val textOff = groups.none { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSelected }
        add(Choice((if (textOff) "✓ " else "   ") + "Off") {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
        })
        groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val label = listOfNotNull(f.label, f.language?.uppercase()).joinToString(" · ").ifEmpty { "Subtitle ${i + 1}" }
                add(Choice((if (g.isTrackSelected(i)) "✓ " else "   ") + label) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                })
            }
        }
        if (onSwitchPlayer != null) {
            add(Choice("Player") {})
            add(Choice("   Switch to mpv (carries on from here)") { onSwitchPlayer() })
        }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.fillMaxHeight().width(380.dp).background(io.github.petebutty.viewarr.ui.theme.AppColors.Panel.copy(alpha = 0.95f)).padding(20.dp).trapFocus().then(Modifier.ignoreHeldOk()),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        // Focus starts on the first audio track, or (Subtitles button) on the subtitles' "Off".
        val isHeader = { c: Choice -> c.label == "Audio" || c.label == "Subtitles" || c.label == "Player" }
        val subsAt = choices.indexOfFirst { it.label == "Subtitles" }
        val firstIdx = (if (subtitlesFirst && subsAt >= 0) subsAt + 1 else choices.indexOfFirst { !isHeader(it) }).coerceAtLeast(0)
        choices.forEachIndexed { idx, c ->
            val header = c.label == "Audio" || c.label == "Subtitles" || c.label == "Player"
            item(key = idx) {
                if (header) Text(c.label, fontSize = 13.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.Accent, modifier = Modifier.padding(top = 10.dp, start = 12.dp))
                else {
                    val m = if (idx == firstIdx) Modifier.focusRequester(first) else Modifier
                    io.github.petebutty.viewarr.ui.common.MenuItem(text = c.label, selected = false, modifier = m, onClick = { c.apply(); onClose() })
                }
            }
        }
    }
}
