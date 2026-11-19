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
import io.github.petebutty.viewarr.ui.common.glass
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.key.key
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
import io.github.petebutty.viewarr.data.media.FoundMarkers
import io.github.petebutty.viewarr.data.media.PlayerArt
import io.github.petebutty.viewarr.data.settings.AutoNext
import io.github.petebutty.viewarr.data.settings.Downmix
import io.github.petebutty.viewarr.data.settings.LocalPlayback
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.data.settings.PictureSize
import io.github.petebutty.viewarr.data.settings.ResumeMode
import io.github.petebutty.viewarr.data.settings.StatsOverlay
import io.github.petebutty.viewarr.data.settings.SubtitleBackground
import io.github.petebutty.viewarr.data.settings.SubtitleMode
import io.github.petebutty.viewarr.data.settings.VideoView
import io.github.petebutty.viewarr.playback.AvSync
import io.github.petebutty.viewarr.playback.DecoderQuirks
import io.github.petebutty.viewarr.playback.FrameRateMatcher
import io.github.petebutty.viewarr.playback.HdmiSoundGuard
import io.github.petebutty.viewarr.playback.SarFixVideoRenderer
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.player.StreamQuality
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.data.settings.Passthrough
import io.github.petebutty.viewarr.ui.player.FpsCounter

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
    /**
     * Hold-OK menu → "Switch to mpv": carry on there from this position, with the audio / subtitle tracks in use
     * (ordinals; subtitles -1 = off).
     */
    onSwitchPlayer: ((positionMs: Long, audio: Int?, subtitles: Int?) -> Unit)? = null,
    art: PlayerArt? = null,
) {
    val context = LocalContext.current
    var error by remember(stream) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val seekMs = LocalRemote.current.seek.ms
    val pb = LocalPlayback.current
    val activity = context as? android.app.Activity
    var restoreMode by remember { mutableStateOf<Int?>(null) }
    // Match frame rate before it starts, as live TV does (FilmRateStart.kt): the switch can begin right here.
    val rateStart = remember(stream) { FilmRateStart(activity, pb.frameRate.films, stream) }
    // The frame rate counted from the decoder, for files that don't say it (Media info, the badge, Match frame rate).
    var countedFps by remember(stream) { mutableStateOf<Float?>(null) }
    val startAt = stream.startMs ?: if (pb.resume == ResumeMode.Start) 0L else stream.resumeMs
    // What's open over the film: shared with the other player (FilmControls.kt), kept under the old names here.
    val panels = remember { FilmPanels() }
    var tracksOpen by panels::tracksOpen
    // Audio & subtitles opened from the Subtitles button: start on the subtitles.
    var tracksSubs by panels::tracksSubs
    val menu = remember { PlayerMenu() }
    val scrub = remember { Scrubber() }
    val thumbs = rememberFilmThumbs(stream)
    // The app's own controls (as mpv's): state read from the player a few times a second.
    var positionMs by remember { mutableStateOf(0L) }
    var bufferedMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var osdUntil by remember { mutableStateOf(System.currentTimeMillis() + AppColors.palette.controlsMs) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // The controls are up: the clock's ticks redraw nothing unless this changes.
    val osdVisible by remember { androidx.compose.runtime.derivedStateOf { paused || buffering || now < osdUntil || scrub.active || menu.open } }
    fun showOsd() { osdUntil = System.currentTimeMillis() + AppColors.palette.controlsMs }
    // Shrunk to the corner (picture-in-picture): just the picture; the page has the remote.
    // (The menu over it counts too: the player lets go of the remote and its controls.)
    val mini = VodHost.mini || VodHost.behind
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
                    // (The picture's shape from the file when the decoder says square pixels wrongly: playback/SarFix.kt.)
                    override fun buildVideoRenderers(
                        ctx: android.content.Context, mode: Int, selector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector, fallback: Boolean,
                        handler: android.os.Handler, listener: androidx.media3.exoplayer.video.VideoRendererEventListener, joiningMs: Long,
                        out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
                    ) {
                        super.buildVideoRenderers(ctx, mode, selector, fallback, handler, listener, joiningMs, out)
                        SarFixVideoRenderer.replaceIn(out, ctx, codecAdapterFactory, selector, fallback, handler, listener, joiningMs)
                    }
                    override fun buildAudioSink(ctx: android.content.Context, floatOutput: Boolean, audioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? =
                        androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(ctx)
                            // Passthrough off: always decode to PCM in the box (no Dolby/DTS bitstream to a receiver).
                            .apply { if (pb.passthrough == Passthrough.Off) setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES) }
                            .setEnableFloatOutput(floatOutput)
                            .setEnableAudioTrackPlaybackParams(audioTrackPlaybackParams)
                            .build()
                            // The info bar's Audio sync.
                            .let(AvSync::wrap)
                }
                    .setEnableDecoderFallback(true)
                    .apply { if (DecoderQuirks.amlogic) forceDisableMediaCodecAsynchronousQueueing() }
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(io.github.petebutty.viewarr.data.net.reachOf(http)))
            // Settings → Playback → Buffer. Capped in bytes too, by the app's memory: with no cap ExoPlayer saves up
            // to ~140 MB of a film in the app's own memory, and a high-bitrate Plex file filled the AM9's 256 MB in
            // three seconds and crashed it (out of memory, 2026-11-18).
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(minOf(15_000, pb.buffer.exoMs), pb.buffer.exoMs, 2_500, 5_000)
                    .setTargetBufferBytes(filmBufferBytes(pb.buffer))
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
                    pb.audioCode()?.let { setPreferredAudioLanguage(it) }
                    if (pb.downmix == Downmix.Stereo) setMaxAudioChannelCount(2)
                    when (pb.subtitles) {
                        SubtitleMode.Off -> setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                        SubtitleMode.On -> setPreferredTextLanguage(pb.subtitleCode()).setSelectUndeterminedTextLanguage(true)
                        SubtitleMode.Forced -> Unit // only tracks flagged forced/default are picked
                    }
                    // Match frame rate before it starts: no sound at all until the TV has switched (FilmRateStart),
                    // or the sound opened early on the old HDMI link and stayed silent.
                    if (rateStart.holding) setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                }.build()
                setMediaItem(mediaItem(stream.url, hls = stream.url.contains(".m3u8")), startAt)
                prepare()
                playWhenReady = !rateStart.holding
            }
    }
    // Skip intro / credits (Settings → Playback → Episodes), from the server's markers.
    val skip = remember(stream) { IntroSkip() }
    val skipModes by androidx.compose.runtime.rememberUpdatedState(pb.skipIntro to pb.skipCredits)
    // The next episode's countdown over the end of this one (Settings → Playback → Episodes); 0: off.
    val nextCountdown by androidx.compose.runtime.rememberUpdatedState(
        if (pb.autoNext == AutoNext.On) maxOf(AppColors.palette.upNextSeconds * 1_000L, 1L) else 0L)
    // Its server's markers, else ones the community intro databases had (they can arrive a moment after it starts).
    val skipMarkers by androidx.compose.runtime.rememberUpdatedState(stream.markers.ifEmpty { FoundMarkers.byUrl[stream.url].orEmpty() })
    // Report where you are every 15 s while playing, and on pause/stop.
    LaunchedEffect(player) {
        while (true) {
            if (player.isPlaying) skip.update(skipMarkers, player.currentPosition, player.duration.coerceAtLeast(0), skipModes.first, skipModes.second, { player.seekTo(it) },
                hasNext = NextUp.episode != null, countdownMs = nextCountdown, onNext = { NextUp.play() })
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0)
            bufferedMs = player.bufferedPosition
            paused = !player.playWhenReady && !rateStart.holding
            buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE || rateStart.holding
            // (In the corner the controls can't show: the clock rests.)
            if (!VodHost.mini && !VodHost.behind) now = System.currentTimeMillis()
            kotlinx.coroutines.delay(250)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            if (player.isPlaying) onProgress("playing", player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }
    // Whenever the picture is cut (a frame-rate switch, the TV re-syncing): the sound restarted on the new link.
    val guard = remember(player) { HdmiSoundGuard(context) }
    DisposableEffect(guard) {
        guard.start()
        onDispose { guard.stop() }
    }
    // The start: the TV switched first (if it needs to), then it plays.
    LaunchedEffect(rateStart) {
        rateStart.run(player, guard)
        if (rateStart.restoreMode != null && restoreMode == null) restoreMode = rateStart.restoreMode
    }
    // What's playing and what's behind it: Plex's conversion, or the next addon streams (Try the next stream).
    val chain = remember(stream) { SourceChain(stream) }
    // An addon stream that hasn't started in 20 seconds (a debrid link still fetching, a dead server): the next one.
    // (Counted from each stream's own start, and not while it's paused: a slow link the viewer paused is left alone.)
    LaunchedEffect(chain, chain.switches) {
        var waited = 0L
        while (waited < 20_000) {
            kotlinx.coroutines.delay(500)
            if (player.playWhenReady) waited += 500
        }
        if (chain.ready || !chain.nextIsSource || error != null) return@LaunchedEffect
        playNext(player, chain, startAt, context, "didn't start")
    }
    // Any stream that hasn't shown a picture after 25 seconds of trying (and has no next stream to fall back to):
    // give up and say so, letting the connection go. (Peter, 2026-11-19: a 4K file on a slow line sat on the loading
    // screen for good, holding the provider's only connection.) Not while paused, and not a stream that has started.
    // (Only when nothing at all has come: a slow provider that takes a minute to start sending is left to it, since
    // some IPTV files only start once the player has read their index from the end of the file.)
    LaunchedEffect(chain, chain.switches) {
        var waited = 0L
        while (waited < 30_000) {
            kotlinx.coroutines.delay(500)
            if (player.playbackState == Player.STATE_READY && player.duration > 0) return@LaunchedEffect
            if (player.totalBufferedDuration > 0 || player.bufferedPosition > startAt) return@LaunchedEffect
            if (player.playWhenReady) waited += 500
        }
        if (chain.ready || chain.nextIsSource || error != null || player.playbackState == Player.STATE_READY) return@LaunchedEffect
        android.util.Log.w("VodPlayer", "nothing after 30 s: giving up on ${stream.title}")
        runCatching { player.stop() }
        error = "Nothing has arrived after 30 seconds. The provider's line may already be in use (most allow one stream at a time), or the server isn't answering. Press Back to close, then try again in a minute."
    }
    DisposableEffect(player) {
        var retriedAsHls = stream.url.contains(".m3u8")
        // Audio & subtitles picked on the film's page: applied once the file's tracks are known.
        var picksApplied = stream.audioTrack == null && stream.subtitleTrack == null
        // A file this player can't manage goes to mpv at the same moment (once; mpv never hands back), so
        // nobody sees an error for something mpv plays. False when there's no mpv to hand to.
        var handedOver = false
        fun toMpv(why: String): Boolean {
            val swap = onSwitchPlayer ?: return false
            if (!handedOver) {
                handedOver = true
                android.util.Log.w("VodPlayer", "handing over to mpv: $why")
                swap(player.currentPosition.takeIf { it > 0 } ?: startAt, null, null)
            }
            return true
        }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // The file opened but its picture or sound can't be played here (this player would carry on
                // silently, or with a black screen).
                if (!tracks.groups.isEmpty()) {
                    val video = androidx.media3.common.C.TRACK_TYPE_VIDEO
                    val audio = androidx.media3.common.C.TRACK_TYPE_AUDIO
                    val badVideo = tracks.containsType(video) && !tracks.isTypeSupported(video, true)
                    val badAudio = tracks.containsType(audio) && !tracks.isTypeSupported(audio, true)
                    if ((badVideo || badAudio) && toMpv(if (badVideo) "picture format" else "sound format")) return
                }
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
                // Stopped only to load (a skip, a slow stream) isn't a pause: Trakt got a pause and a start for each.
                if (!isPlaying && player.playWhenReady) return
                onProgress(if (isPlaying) "playing" else "paused", player.currentPosition, player.duration.coerceAtLeast(0))
            }

            override fun onRenderedFirstFrame() {
                // Settings → Playback → Match frame rate.
                // (Match frame rate is done before it starts, only when the server gave the rate: FilmRateStart. Not mid-film.)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) chain.ready = true
                // The end with the next episode known (and its countdown not turned down): on to it, not closing.
                if (state == Player.STATE_ENDED && skip.nextAtEnd(NextUp.episode != null, nextCountdown > 0)) {
                    onProgress("stopped", player.duration.coerceAtLeast(0), player.duration.coerceAtLeast(0))
                    NextUp.play(); return
                }
                // The end reported first: closing clears what's playing, and the report then went nowhere (no 100% to
                // Plex / Jellyfin / Trakt, and no next-up countdown after).
                if (state == Player.STATE_ENDED) {
                    onProgress("stopped", player.duration.coerceAtLeast(0), player.duration.coerceAtLeast(0))
                    onClose()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val parsing = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                // e.g. Plex: the file itself won't play here, so ask Plex to convert it. (Or the next addon stream:
                // Settings → Playback → Try the next stream; but an addon link that may be an HLS playlist without
                // saying so gets its HLS try first, as it always did.)
                val hlsFirst = parsing && !retriedAsHls && chain.nextIsSource
                if (!hlsFirst && playNext(player, chain, startAt, context, "didn't play")) { retriedAsHls = chain.current.url.contains(".m3u8"); return }
                // Addon links often have no file extension; many are HLS playlists. Try that once before giving up.
                if (!retriedAsHls && parsing) {
                    retriedAsHls = true
                    // (The address that just failed: the fallback's, once that's in use.)
                    val failed = chain.current.url
                    player.setMediaItem(mediaItem(failed, hls = true), player.currentPosition.takeIf { it > 0 } ?: startAt)
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
                // Formats and decoding: mpv may well play it. (Network and server errors stay errors: mpv wouldn't help.)
                val unplayable = e.errorCode in setOf(
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                )
                if (unplayable && toMpv(e.errorCodeName)) return
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
            // The TV switches as soon as FilmRateStart is made, so closing during that wait still has a mode to go back to.
            (restoreMode ?: rateStart.restoreMode)?.let { m -> activity?.let { FrameRateMatcher.restore(it, m) } }
            if (player.playbackState != Player.STATE_ENDED && player.currentPosition > 0) {
                onProgress("stopped", player.currentPosition, player.duration.coerceAtLeast(0))
            }
            player.removeListener(listener)
            player.release()
        }
    }
    // Back, one step at a time (FilmControls.kt filmBackStep): what's open closes, then the mini player. A film that
    // failed closes outright (it went to the corner, broken).
    fun backStep() = if (error != null) onClose() else filmBackStep(panels, menu, scrub, skip, onClose, hideOsd = { osdUntil = 0L }, showOsd = ::showOsd)
    BackHandler(enabled = !mini) { backStep() }
    // A file that doesn't say its frame rate: counted (FpsCounter); then Match frame rate can switch for it too.
    LaunchedEffect(player) {
        val counter = FpsCounter()
        repeat(120) {
            kotlinx.coroutines.delay(500)
            if ((player.videoFormat?.frameRate ?: 0f) > 0f) return@LaunchedEffect
            if (!player.isPlaying) { counter.reset(); return@repeat }
            val frames = FpsCounter.frames(player) ?: return@repeat
            counter.feed(frames, android.os.SystemClock.elapsedRealtime())?.let { fps ->
                countedFps = fps

                return@LaunchedEffect
            }
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
    // The remote's Play / Pause with the film in the corner (HomeState): this player while it's the one playing.
    val remote = object : VodRemote {
        override val positionMs get() = player.currentPosition
        override val durationMs get() = player.duration.coerceAtLeast(0)
        override fun seekTo(ms: Long) = player.seekTo(ms)
        override fun skip(ms: Long) = player.seekTo(maxOf(0, player.currentPosition + ms))
        override fun togglePause() { player.playWhenReady = !player.playWhenReady }
        override fun pause() { player.playWhenReady = false }
        override val playing get() = player.isPlaying
    }
    // Carry on in mpv, with the tracks in use.
    val switchPlayer = onSwitchPlayer?.let { f -> { picks().let { (a, t) -> f(player.currentPosition, a, t) } } }
    // The info bar, the remote, the timers and focus: shared with the mpv player (FilmShared.kt).
    val wiring = rememberFilmWiring(remote, panels, menu, scrub, skip, seekMs, switchPlayer, onClose, showOsd = ::showOsd,
        backStep = ::backStep)
    val buttons = wiring.buttons
    var hasFocus by remember { mutableStateOf(false) }
    FilmEffects(panels, menu, scrub, paused, mini, hasFocus, focus, hideOsd = { osdUntil = 0L })
    // The remote is handled here, not by the PlayerView's own buttons: inside Compose those never get key focus,
    // which left the remote doing nothing during playback.
    Box(Modifier.filmKeys(focus, panels, mini, { hasFocus = it }, wiring, remote)) {
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(
                    // Settings → Playback → Video view: Standard (SurfaceView) or Compatible (TextureView).
                    if (pb.videoView == VideoView.Standard) io.github.petebutty.viewarr.R.layout.vod_player_view_surface
                    else io.github.petebutty.viewarr.R.layout.vod_player_view, null) as PlayerView).apply {
                    this.player = player
                    // Picture and subtitles only: the controls are the app's own (PlayerOsd), keys come from the box above.
                    useController = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    isFocusable = false
                    keepScreenOn = true
                    subtitleView?.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * pb.subtitleSize.scale)
                    val box = pb.subtitleBackground == SubtitleBackground.Box
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
                        PictureSize.Fit -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        PictureSize.Zoom -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        PictureSize.Stretch -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        var started by remember(stream) { mutableStateOf(false) }
        if (!buffering && durationMs > 0) started = true
        val state = FilmState({ positionMs }, durationMs, { bufferedMs }, paused, buffering, started,
            osdVisible = osdVisible,
            quality = player.videoFormat?.let { v -> StreamQuality(v.height, v.frameRate.takeIf { it > 0 } ?: countedFps ?: 0f, player.audioFormat?.channelCount ?: 0) })
        FilmOverlays(stream, art, state, mini, panels, menu, scrub, skip, buttons, thumbs, "mpv", error, focus,
            holdOsd = { show -> osdUntil = if (show) Long.MAX_VALUE else 0L }) {
            TrackPicker(player, subtitlesFirst = tracksSubs, onSwitchPlayer = switchPlayer, onClose = { tracksOpen = false; takeFilmFocus(focus) }, modifier = Modifier.align(Alignment.CenterEnd))
        }
        // Hold OK on a bar button: the Info bar buttons list (FilmControls.kt).
        FilmBarEditor(panels, menu, canSwitch = switchPlayer != null, mini = mini, focus = focus)
        if (!mini && (pb.stats == StatsOverlay.On || menu.infoOn)) {
            var info by remember { mutableStateOf("") }
            LaunchedEffect(player) {
                while (true) {
                    val v = player.videoFormat
                    val a = player.audioFormat
                    val c = player.videoDecoderCounters
                    info = listOf(
                        "Video: ${v?.sampleMimeType?.substringAfter('/') ?: "?"}  ${v?.width ?: "?"}×${v?.height ?: "?"}  ${(v?.frameRate?.takeIf { it > 0 } ?: countedFps)?.let { "%.3f".format(it) } ?: "?"} fps",
                        "Audio: ${a?.sampleMimeType?.substringAfter('/') ?: "?"}  ${a?.channelCount ?: "?"} ch",
                        "Dropped frames: ${c?.droppedBufferCount ?: 0}",
                        "Buffered: ${(player.totalBufferedDuration / 1000)} s",
                    ).joinToString("\n")
                    kotlinx.coroutines.delay(1_000)
                }
            }
            Text(info, fontSize = 13.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(24.dp).background(Color.Black.copy(alpha = 0.6f)).padding(12.dp))
        }
    }
}

/**
 * On to the stream behind the one playing: Plex's conversion, or the next addon stream (said, with where it's from).
 * From where it had got to, else where it was meant to start ("From the start" and Resume count). False: none left.
 */
private fun playNext(player: ExoPlayer, chain: SourceChain, startAt: Long, context: android.content.Context, why: String): Boolean {
    val source = chain.nextIsSource
    val fb = chain.next() ?: return false
    if (source) android.widget.Toast.makeText(context, "That stream $why. Trying the next one" + (fb.from?.let { " ($it)" } ?: "") + "…", android.widget.Toast.LENGTH_SHORT).show()
    val at = player.currentPosition.takeIf { it > 0 } ?: startAt
    player.setMediaItem(mediaItem(fb.url, hls = fb.url.contains(".m3u8")), at)
    player.prepare()
    player.playWhenReady = true
    return true
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
        modifier.fillMaxHeight().padding(vertical = 28.dp).padding(end = 28.dp).width(380.dp).glass().padding(20.dp).trapFocus().then(Modifier.ignoreHeldOk()),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        // Focus starts on the first audio track, or (Subtitles button) on the subtitles' "Off".
        val isHeader = { c: Choice -> c.label == "Audio" || c.label == "Subtitles" || c.label == "Player" }
        val subsAt = choices.indexOfFirst { it.label == "Subtitles" }
        val firstIdx = (if (subtitlesFirst && subsAt >= 0) subsAt + 1 else choices.indexOfFirst { !isHeader(it) }).coerceAtLeast(0)
        choices.forEachIndexed { idx, c ->
            val header = c.label == "Audio" || c.label == "Subtitles" || c.label == "Player"
            item(key = idx) {
                if (header) Text(c.label, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 10.dp, start = 12.dp))
                else {
                    val m = if (idx == firstIdx) Modifier.focusRequester(first) else Modifier
                    MenuItem(text = c.label, selected = false, modifier = m, onClick = { c.apply(); onClose() })
                }
            }
        }
    }
}

/** The film buffer's byte cap: a share of the box's normal app allowance (Small 12%, Normal 20%, Large 30%), 16–160 MB. */
private fun filmBufferBytes(size: io.github.petebutty.viewarr.data.settings.BufferSize): Int {
    val share = when (size) {
        io.github.petebutty.viewarr.data.settings.BufferSize.Small -> 0.12
        io.github.petebutty.viewarr.data.settings.BufferSize.Normal -> 0.20
        io.github.petebutty.viewarr.data.settings.BufferSize.Large -> 0.30
    }
    return (io.github.petebutty.viewarr.MemoryBudget.normalBytes * share).toLong().coerceIn(16L shl 20, 160L shl 20).toInt()
}
