package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.ui.common.videoBleed
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.ui.common.BackLayer

/** Small windows don't need more than 480p; less to download and decode on a weak box. */
private const val PREVIEW_HEIGHT = 480

/** A full-screen trailer is open (the Trailer button): live TV stops for it, as for a film (one stream, one sound). */
object FullTrailer {
    private val state = mutableStateOf(false)
    var active: Boolean
        get() = state.value
        set(v) { state.value = v }
}

private fun trailerPlayer(context: android.content.Context, small: Boolean): ExoPlayer =
    ExoPlayer.Builder(context)
        .setLoadControl(
            if (small) DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 12_000, 1_000, 2_000).build()
            else DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 1_500, 3_000).build(),
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .build()
        // Never the TV's refresh rate for a trailer (a preview on a poster or title page, or the full-screen one): the
        // player's own frame-rate hint switched some TVs' mode, a blank screen and a flicker each time. Frame rate
        // matching is for films and live TV only (Settings → Video). (2026-11-17.)
        .also { it.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF }

/**
 * Picture-in-picture trailer for the focused poster. Starts once the poster has been focused for the chosen time
 * (Settings → Playback → Trailers), fades in on its first frame and fades out when it ends or focus moves.
 * [active] is false while the title page, a film or the menu has the screen: the trailer stops at once.
 */
@Composable
fun TrailerPreview(item: MediaItem?, active: Boolean, trailers: TrailerRepository, modifier: Modifier = Modifier, onShowing: (Boolean) -> Unit = {},
                   /** Sound on / off now, without restarting (the poster's trailer: muted in the card, sound in the panel); null: the setting. */
                   audio: Boolean? = null) {
    val pb = LocalPlayback.current
    if (pb.trailerPreview == TrailerPreview.Off || !AppColors.palette.heavyExtras) return
    val context = LocalContext.current
    var showing by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    val listener = remember {
        object : Player.Listener {
            override fun onRenderedFirstFrame() { showing = true }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) showing = false }
            override fun onPlayerError(error: PlaybackException) {
                showing = false
                android.util.Log.w("Trailers", "preview failed: ${error.errorCodeName}")
                currentId?.let(trailers::markDead)
            }
        }
    }
    // Made for the first trailer that plays, not before: the hero is on several pages at once, and each held an idle
    // ExoPlayer (its threads, buffers and picture) whether a trailer ever played or not.
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.let { it.removeListener(listener); it.release() } } }
    val sound = if (audio != null) true else pb.trailerSound == TrailerSound.On
    // (Its volume follows [audio] as it changes; the trailer carries on.)
    SideEffect { player?.volume = if (audio ?: sound) 1f else 0f }
    LaunchedEffect(item?.key, active, pb.trailerDelay, sound) {
        showing = false
        player?.run { stop(); clearMediaItems() }
        currentId = null
        if (item == null || !active) return@LaunchedEffect
        // The hero itself already waited 250 ms for focus to settle.
        delay((pb.trailerDelay.ms - 250).coerceAtLeast(0))
        val id = trailers.youtubeId(item) ?: return@LaunchedEffect
        val streams = trailers.resolve(id, PREVIEW_HEIGHT) ?: return@LaunchedEffect
        val source = trailers.mediaSource(streams, withAudio = sound) ?: return@LaunchedEffect
        currentId = id
        val p = player ?: trailerPlayer(context, small = true).also { it.addListener(listener); player = it }
        p.volume = if (audio ?: sound) 1f else 0f
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }
    // (In the poster: the card widens once the trailer's first frame is in, and back when it stops.)
    LaunchedEffect(showing && active) { onShowing(showing && active) }
    // Gone (the page left while it showed): not showing any more, or the card stayed wide.
    DisposableEffect(Unit) { onDispose { onShowing(false) } }
    val alpha by animateFloatAsState(if (showing && active) 1f else 0f, tween(350), label = "trailer")
    Box(modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; this.alpha = alpha }.background(Color.Black)) {
        player?.let { p ->
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { p.setVideoTextureView(it) } },
                modifier = Modifier.fillMaxSize().videoBleed(),
            )
        }
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
    val seekMs = LocalRemote.current.seek.ms
    BackHandler(onBack = onClose)
    BackLayer()
    DisposableEffect(Unit) {
        FullTrailer.active = true
        onDispose { FullTrailer.active = false }
    }
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
                    // (Once per press: held, OK paused and played again with every repeat.)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> { if (e.nativeKeyEvent.repeatCount == 0) player.playWhenReady = !player.playWhenReady; true }
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
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The hero's corner-window trailer (Viewarr style): whether one is showing, for the "full screen" key and hint. */
object HeroTrailer {
    var showing by mutableStateOf(false)
}

/**
 * Trailer previews in the poster (Settings → Playback → Trailers → Trailer previews play: In the poster; ideas from
 * Nuvio KE, 2026-11-18). One trailer for the whole app, drawn over everything ([PosterTrailerHost]): it sits on
 * the highlighted poster (which widens to 16:9, stage 1, muted), then grows from there into a big panel in the middle
 * with sound (stage 2), the same trailer playing on throughout. OK in the panel opens the title page, Back / a D-pad
 * key closes it (not again until you move off that poster).
 */
object PosterTrailer {
    var repo: TrailerRepository? = null
    /** The page may play one now (it has focus, nothing over it). */
    var active by mutableStateOf(false)
    /** The poster with a preview under way, and where it is on screen (its card's bounds, root coordinates). */
    var item by mutableStateOf<MediaItem?>(null)
    var cardRect by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** Its trailer's first frame is in. */
    var showing by mutableStateOf(false)
    /** Stage 2: the big panel. */
    var panel by mutableStateOf(false)
    /** The poster's OK (its title page), for OK in the panel. */
    var onOpen: (() -> Unit)? = null
    /** Closed with Back / a key: that poster doesn't start again until it loses the highlight. */
    var dismissed: String? = null
    /** Bumped when the panel closes, so the poster takes the highlight back. */
    var closedAt by mutableStateOf(0L)
    val on get() = AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.TRAILER_STYLE) == 1 && AppColors.palette.heavyExtras

    fun start(i: MediaItem, open: () -> Unit) {
        if (item?.key == i.key) return
        item = i; onOpen = open; showing = false; panel = false; cardRect = null
    }
    fun stop(key: String) { if (item?.key == key && !panel) { item = null; showing = false; cardRect = null; onOpen = null } }
    /** A key while the panel is open (the poster passes them on): OK opens the title page, Back / arrows close. */
    fun panelKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val k = e.key
        val ok = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
        val closes = k == Key.Back || k == Key.Escape || k == Key.DirectionLeft || k == Key.DirectionRight || k == Key.DirectionUp || k == Key.DirectionDown
        if (!ok && !closes) return false // (other keys, Menu and the media keys, go through: UI review, 2026-11-18)
        // OK opens the title when it's let go: on the press, the release landed on the new page's Play and started
        // the film. Back and the arrows close on the press.
        if (ok) {
            if (e.type == KeyEventType.KeyUp) { val open = onOpen; close(); open?.invoke() }
            return true
        }
        if (e.type == KeyEventType.KeyDown) close()
        return true
    }
    /** [quiet]: the poster went away (a row refreshed, the page closed), not a Back: it may start again later. */
    fun close(quiet: Boolean = false) {
        if (!quiet) dismissed = item?.key
        item = null; showing = false; panel = false; cardRect = null; onOpen = null
        closedAt = System.currentTimeMillis()
    }
}

/** Over everything (the app's root): the poster's trailer, on its card, then grown into the big panel. */
@Composable
fun PosterTrailerHost() {
    // (Composed whether or not a poster has a trailer going: the one player is kept between posters instead of
    // being made again for each. UI review, 2026-11-18.)
    val item = PosterTrailer.item
    val repo = PosterTrailer.repo ?: return
    // Stage 2 after it has played in the card a while (Settings → Playback → Trailers → Then the big panel).
    LaunchedEffect(item?.key, PosterTrailer.showing) {
        if (item == null) return@LaunchedEffect
        val after = listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 0L)[AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.TRAILER_GROW).coerceIn(0, 5)]
        if (!PosterTrailer.showing || PosterTrailer.panel || after == 0L) return@LaunchedEffect
        delay(after)
        if (PosterTrailer.item?.key == item.key && PosterTrailer.showing && PosterTrailer.active) PosterTrailer.panel = true
    }
    // The page lost the screen (the menu, quick search, a link): the panel closes rather than playing on over it.
    LaunchedEffect(PosterTrailer.active) { if (!PosterTrailer.active && PosterTrailer.panel) PosterTrailer.close(quiet = true) }
    // The panel is a pop-up for Back: with a film in the corner, one Back closes the panel (the poster takes the key),
    // not the panel and the corner together.
    BackLayer(PosterTrailer.panel)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val grow by animateFloatAsState(if (PosterTrailer.panel) 1f else 0f, tween(480, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "trailerPanel")
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        // (Most of the screen, 2026-11-18: 78% wide, its title and hints in the room left under it.)
        val pw = w * 0.78f; val ph = pw * 9f / 16f
        // (Up a little more: its title and hints under it were on the very edge of the screen.)
        val panelRect = androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset((w - pw) / 2f, (h - ph) / 2f - h * 0.08f), androidx.compose.ui.geometry.Size(pw, ph))
        val r = if (item == null) androidx.compose.ui.geometry.Rect.Zero else androidx.compose.ui.geometry.lerp(PosterTrailer.cardRect ?: panelRect, panelRect, grow)
        // The corners: the poster's radius growing to the panel's.
        val radius = androidx.compose.ui.unit.lerp(io.github.petebutty.viewarr.ui.theme.Tokens.CardRadius, 18.dp, grow)
        // The rest of the screen darkens as it grows.
        if (grow > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f * grow)))
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(r.left.toInt(), r.top.toInt()) }
                .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(radius))

        ) {
            TrailerPreview(
                item = item, active = PosterTrailer.active || PosterTrailer.panel, trailers = repo, modifier = Modifier.fillMaxSize(),
                // Sound from the start when Preview sound is on (2026-11-18: it used to stay muted in the poster).
                onShowing = { PosterTrailer.showing = it }, audio = PosterTrailer.panel || LocalPlayback.current.trailerSound == TrailerSound.On,
            )
            // The highlight ring, on the trailer as it grows (the poster's own goes while the panel is open).
            if (PosterTrailer.showing) Box(Modifier.fillMaxSize().border(3.dp, AppColors.Accent, androidx.compose.foundation.shape.RoundedCornerShape(radius)))
            if (item != null && PosterTrailer.showing && grow < 1f) {
                // Stage 1: its title bottom-left, muted bottom-right, over a soft shade (fading as it grows).
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = 1f - grow }
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                androidx.tv.material3.Text(item.title, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 34.dp, bottom = 8.dp).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = 1f - grow })
                if (LocalPlayback.current.trailerSound != TrailerSound.On)
                    androidx.tv.material3.Text("🔇", fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 7.dp).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = 1f - grow })
            }
        }
        // Stage 2: the title, a line about it and what the buttons do, under the panel.
        if (item != null && grow > 0.5f) androidx.compose.foundation.layout.Column(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(panelRect.left.toInt(), (panelRect.bottom + with(density) { 14.dp.toPx() }).toInt()) }
                .width(with(density) { panelRect.width.toDp() }).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = (grow - 0.5f) * 2f },
        ) {
            androidx.tv.material3.Text(item.title, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color.White, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            listOfNotNull(item.year?.toString(), item.origin.takeIf { it.isNotBlank() }).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                androidx.tv.material3.Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            // What the buttons do, as key prompts (2026-11-18: the faint line went unseen). OK opens the title page,
            // films and shows alike: that's where Play, Up next and the episodes are.
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyPrompt("OK", "Details and play")
                Spacer(Modifier.width(18.dp))
                KeyPrompt("BACK", "Close")
            }
        }
    }
}

/**
 * A page saying whether its posters may play trailers now (B5, UI review 2026-11-18: two pages, one over the other,
 * both wrote it and the one underneath kept switching it off). A page writes while it's the one with the screen, and
 * once more (false) when it stops being that; otherwise it keeps quiet.
 */
@Composable
fun PublishPosterTrailerActive(active: Boolean) {
    var wrote by remember { mutableStateOf(false) }
    SideEffect { if (active || wrote) { PosterTrailer.active = active; wrote = active } }
    // The page went while it said yes: off, or the next page's posters played on as if it were still there.
    DisposableEffect(Unit) { onDispose { if (wrote) PosterTrailer.active = false } }
}

/** A remote button and what it does: "[OK] Details and play". */
@Composable
private fun KeyPrompt(key: String, does: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.tv.material3.Text(key, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.Black, maxLines = 1, softWrap = false,
            modifier = Modifier.background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        Spacer(Modifier.width(8.dp))
        androidx.tv.material3.Text(does, fontSize = 15.sp, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
