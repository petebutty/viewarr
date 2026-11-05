package io.github.petebutty.viewarr.ui.media

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
) {
    val context = LocalContext.current
    var error by remember(stream) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val seekMs = io.github.petebutty.viewarr.data.settings.LocalRemote.current.seek.ms
    var view by remember { mutableStateOf<PlayerView?>(null) }
    var tracksOpen by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
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
                DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build().apply {
                setMediaItem(mediaItem(stream.url, hls = stream.url.contains(".m3u8")), stream.resumeMs)
                prepare()
                playWhenReady = true
            }
    }
    // Report where you are every 15 s while playing, and on pause/stop.
    LaunchedEffect(player) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            if (player.isPlaying) onProgress("playing", player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }
    DisposableEffect(player) {
        var retriedAsHls = stream.url.contains(".m3u8")
        var usedFallback = false
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_ENDED) return
                onProgress(if (isPlaying) "playing" else "paused", player.currentPosition, player.duration.coerceAtLeast(0))
            }

            override fun onPlaybackStateChanged(state: Int) {
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
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "The server refused this stream. Press Back and try another source."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Couldn't connect to the stream. Press Back and try again."
                    else -> "Couldn't play this (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}). Press Back."
                }
            }
        }
        player.addListener(listener)
        onDispose {
            if (player.playbackState != Player.STATE_ENDED && player.currentPosition > 0) {
                onProgress("stopped", player.currentPosition, player.duration.coerceAtLeast(0))
            }
            player.removeListener(listener)
            player.release()
        }
    }
    BackHandler { if (tracksOpen) tracksOpen = false else onClose() }
    // The player must hold focus itself, or the remote keeps driving the screen underneath (the controls never respond).
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { focus.requestFocus() }
    }
    // The remote is handled here, not by the PlayerView's own buttons: inside Compose those never get key focus,
    // which left the remote doing nothing during playback.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable(enabled = !tracksOpen)
            .onPreviewKeyEvent { e ->
                if (tracksOpen) return@onPreviewKeyEvent false
                val v = view
                val center = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (center) {
                    // Tap OK: pause/play. Hold OK: audio & subtitles.
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> okDown = true
                        e.type == KeyEventType.KeyDown && okDown -> { okDown = false; tracksOpen = true }
                        e.type == KeyEventType.KeyUp && okDown -> {
                            okDown = false
                            player.playWhenReady = !player.playWhenReady
                            v?.showController()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft, Key.MediaRewind -> { player.seekTo(maxOf(0, player.currentPosition - seekMs)); v?.showController() }
                    Key.DirectionRight, Key.MediaFastForward -> { player.seekTo(player.currentPosition + seekMs); v?.showController() }
                    Key.DirectionUp, Key.DirectionDown, Key.Info -> v?.showController()
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { player.playWhenReady = !player.playWhenReady; v?.showController() }
                    Key.Menu -> tracksOpen = true
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(io.github.petebutty.viewarr.R.layout.vod_player_view, null) as PlayerView).apply {
                    this.player = player
                    view = this
                    useController = true
                    // Display only: keys come from the Compose box above.
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    isFocusable = false
                    controllerShowTimeoutMs = 4_000
                    controllerAutoShow = true
                    setShowSubtitleButton(true)
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (tracksOpen) TrackPicker(player, onClose = { tracksOpen = false; runCatching { focus.requestFocus() } }, modifier = Modifier.align(Alignment.CenterEnd))
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
private fun TrackPicker(player: ExoPlayer, onClose: () -> Unit, modifier: Modifier) {
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
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.fillMaxHeight().width(380.dp).background(io.github.petebutty.viewarr.ui.theme.AppColors.Panel.copy(alpha = 0.95f)).padding(20.dp).trapFocus(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        var firstSet = false
        choices.forEachIndexed { idx, c ->
            val header = c.label == "Audio" || c.label == "Subtitles"
            item(key = idx) {
                if (header) Text(c.label, fontSize = 13.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.Accent, modifier = Modifier.padding(top = 10.dp, start = 12.dp))
                else {
                    val m = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                    io.github.petebutty.viewarr.ui.common.MenuItem(text = c.label, selected = false, modifier = m, onClick = { c.apply(); onClose() })
                }
            }
        }
    }
}
