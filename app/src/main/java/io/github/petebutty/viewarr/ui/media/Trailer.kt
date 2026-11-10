package io.github.petebutty.viewarr.ui.media

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.settings.LocalPlayback
import io.github.petebutty.viewarr.data.settings.TrailerPreview
import io.github.petebutty.viewarr.data.settings.TrailerSound
import io.github.petebutty.viewarr.data.trailers.TrailerRepository
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay

/** Small windows don't need more than 480p; less to download and decode on a weak box. */
private const val PREVIEW_HEIGHT = 480

@OptIn(UnstableApi::class)
private fun trailerPlayer(context: android.content.Context, small: Boolean): ExoPlayer =
    ExoPlayer.Builder(context)
        .setLoadControl(
            if (small) DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 12_000, 1_000, 2_000).build()
            else DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 1_500, 3_000).build(),
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .build()

/**
 * Picture-in-picture trailer for the focused poster. Starts once the poster has been focused for the chosen time
 * (Settings → Playback → Trailers), fades in on its first frame and fades out when it ends or focus moves.
 * [active] is false while the title page, a film or the menu has the screen: the trailer stops at once.
 */
@Composable
fun TrailerPreview(item: MediaItem?, active: Boolean, trailers: TrailerRepository, modifier: Modifier = Modifier) {
    val pb = LocalPlayback.current
    if (pb.trailerPreview == TrailerPreview.Off || !io.github.petebutty.viewarr.ui.theme.AppColors.palette.heavyExtras) return
    val context = LocalContext.current
    val player = remember { trailerPlayer(context, small = true) }
    var showing by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { showing = true }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) showing = false }
            override fun onPlayerError(error: PlaybackException) {
                showing = false
                android.util.Log.w("Trailers", "preview failed: ${error.errorCodeName}")
                currentId?.let(trailers::markDead)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    val sound = pb.trailerSound == TrailerSound.On
    LaunchedEffect(item?.key, active, pb.trailerDelay, sound) {
        showing = false
        player.stop()
        player.clearMediaItems()
        currentId = null
        if (item == null || !active) return@LaunchedEffect
        // The hero itself already waited 250 ms for focus to settle.
        delay((pb.trailerDelay.ms - 250).coerceAtLeast(0))
        val id = trailers.youtubeId(item) ?: return@LaunchedEffect
        val streams = trailers.resolve(id, PREVIEW_HEIGHT) ?: return@LaunchedEffect
        val source = trailers.mediaSource(streams, withAudio = sound) ?: return@LaunchedEffect
        currentId = id
        player.volume = if (sound) 1f else 0f
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }
    val alpha by animateFloatAsState(if (showing && active) 1f else 0f, tween(350), label = "trailer")
    Box(modifier.graphicsLayer { this.alpha = alpha }.background(Color.Black)) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Full-screen trailer with sound. OK pauses, Left / Right skip (Settings → Remote), Back closes. */
@OptIn(UnstableApi::class)
@Composable
fun TrailerPlayer(item: MediaItem, trailers: TrailerRepository, onClose: () -> Unit) {
    val context = LocalContext.current
    val pb = LocalPlayback.current
    val player = remember { trailerPlayer(context, small = false) }
    var message by remember { mutableStateOf<String?>("Loading trailer…") }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var paused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val seekMs = io.github.petebutty.viewarr.data.settings.LocalRemote.current.seek.ms
    BackHandler(onBack = onClose)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { message = null }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { paused = !isPlaying && player.playbackState == Player.STATE_READY }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onClose() }
            override fun onPlayerError(error: PlaybackException) { message = "The trailer couldn't be played (${error.errorCodeName})." }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(item.key) {
        awaitFrame()
        runCatching { focus.requestFocus() }
        val id = trailers.youtubeId(item) ?: run { message = "No trailer found for this title."; return@LaunchedEffect }
        val maxHeight = (pb.maxResolution.height ?: 1080).coerceAtMost(1080)
        val streams = trailers.resolve(id, maxHeight) ?: run { message = "YouTube didn't return this trailer."; return@LaunchedEffect }
        val source = trailers.mediaSource(streams, withAudio = true) ?: run { message = "The trailer couldn't be played."; return@LaunchedEffect }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .trapFocus()
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> { player.playWhenReady = !player.playWhenReady; true }
                    Key.MediaPlay -> { player.playWhenReady = true; true }
                    Key.MediaPause -> { player.playWhenReady = false; true }
                    Key.DirectionRight, Key.MediaFastForward -> { player.seekTo(player.currentPosition + seekMs); true }
                    Key.DirectionLeft, Key.MediaRewind -> { player.seekTo((player.currentPosition - seekMs).coerceAtLeast(0)); true }
                    Key.DirectionUp, Key.DirectionDown -> true
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            modifier = Modifier.aspectRatio(aspect),
        )
        message?.let { Text(it, fontSize = 16.sp, color = AppColors.TextSecondary) }
        if (paused && message == null) {
            Text(
                "Paused · ${item.title} trailer",
                fontSize = 16.sp,
                color = AppColors.TextPrimary,
                modifier = Modifier.align(Alignment.BottomStart).padding(48.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
