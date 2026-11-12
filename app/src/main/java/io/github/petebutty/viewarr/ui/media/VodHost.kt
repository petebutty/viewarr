package io.github.petebutty.viewarr.ui.media

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.github.petebutty.viewarr.data.media.PlayerArt
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.flow.StateFlow

/** Where a film, episode or recording comes from: its page's state (what's playing, its artwork) and callbacks. */
class VodSource(
    val playing: StateFlow<VodStream?>,
    val art: StateFlow<PlayerArt?>,
    val onClose: () -> Unit,
    val onProgress: (state: String, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
)

/**
 * The one film / episode / recording player, above every page, so it can shrink to a corner (picture-in-picture)
 * and keep playing while you browse. Back in the player shrinks it; holding Back brings it back full screen; Stop
 * (the controls, or the remote's Stop) ends it. Starting a Live TV channel ends it too.
 */
object VodHost {
    var source by mutableStateOf<VodSource?>(null)
        private set
    /** Shrunk to the corner: pages have the remote, the player doesn't. */
    var mini by mutableStateOf(false)
    /** Something is playing (full screen or in the corner). */
    var active by mutableStateOf(false)
        internal set

    /** Full screen over the page. */
    val covering: Boolean get() = active && !mini

    /** A page starts playing: it takes the player (whatever was playing from another page stops). */
    fun show(src: VodSource) {
        val old = source
        // The same page's state (its screen was rebuilt, e.g. on coming back to Movies with the film in the
        // corner): nothing new is playing, so neither stop it nor bring it full screen.
        val samePage = old?.playing === src.playing
        val now = src.playing.value
        source = src
        if (samePage && now != null && now === shown) return
        if (!samePage) old?.onClose()
        // A film or episode ends the music.
        io.github.petebutty.viewarr.playback.MusicPlayer.stop()
        shown = now
        mini = false
    }
    /** What [show] last brought up. */
    private var shown: VodStream? = null

    fun minimise() { if (active) mini = true }
    /** Search (the home screen sets it): the film goes to the corner and search opens over the page. */
    var openSearch: () -> Unit = {}
    /**
     * After a film or episode ended or was stopped by you (Stop, Back): Settings → Playback → After a film or episode
     * (the home screen sets it). Not when it's left for a shortcut (that goes where you chose).
     */
    var afterStop: () -> Unit = {}
    /**
     * The rest of a held Back that already did its job (the film went to the corner while it was held): swallowed at
     * the root until it's let go, so it doesn't also bring the film back full screen.
     */
    var swallowBack = false
    /** Gliding into the corner or back: the page behind is held still and keeps its hands off the remote meanwhile. */
    var gliding by mutableStateOf(false)
    /** Held Back brought it full screen: the player ignores the rest of that press (its release would shrink it again). */
    var eatBackRelease = false
    fun expand() { mini = false; eatBackRelease = true }

    /** For the players: true for the remaining events of the Back press that brought them full screen. */
    fun eatsBack(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!eatBackRelease || e.key != androidx.compose.ui.input.key.Key.Back) return false
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) eatBackRelease = false
        return true
    }
    fun close() { if (active) source?.onClose(); mini = false }
}

/** At the root, over everything: the player, full screen or in the corner. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun VodHostLayer() {
    val src = VodHost.source ?: return
    val stream by src.playing.collectAsState()
    val art by src.art.collectAsState()
    LaunchedEffect(stream == null) {
        VodHost.active = stream != null
        // Stopped or ended (even mid-glide): nothing is gliding any more, or the page would wait for it for ever.
        if (stream == null) { VodHost.mini = false; VodHost.gliding = false }
    }
    val s = stream ?: return
    val mini = VodHost.mini
    // Settings → Playback → Picture-in-picture: size and corner.
    val p = AppColors.palette
    val (w, h) = io.github.petebutty.viewarr.ui.theme.CornerPicture.size()
    val corner = when (p.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.MINI_CORNER)) { 1 -> Alignment.BottomStart; 2 -> Alignment.TopEnd; 3 -> Alignment.TopStart; else -> Alignment.BottomEnd }
    val top = corner == Alignment.TopEnd || corner == Alignment.TopStart
    val left = corner == Alignment.BottomStart || corner == Alignment.TopStart
    // Into the corner and back as a short glide, not a jump. Standard video view (SurfaceView): the window's real
    // place and size move and the display hardware scales the picture. Compatible (TextureView): the full-screen
    // frame is scaled as it's drawn.
    // Read only where it's drawn (graphicsLayer) and through derivedStateOf: read here, every frame of the glide
    // rebuilt the player's whole screen, which is what made it stutter.
    // Growing back starts a moment late: the player takes the remote back and rebuilds its screen first, which
    // stuttered the first frames when it happened during the glide. (Shrinking starts at once: it looked fine.)
    // Settings → Playback → Picture-in-picture → Film animation (Off: straight there).
    val animate = p.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.ANIM_FILM) == 0
    var target by androidx.compose.runtime.remember { mutableStateOf(if (mini) 1f else 0f) }
    val shrinkAnim = androidx.compose.animation.core.animateFloatAsState(
        target,
        if (animate) androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing) else androidx.compose.animation.core.snap(),
        label = "pip",
        finishedListener = { VodHost.gliding = false },
    )
    LaunchedEffect(mini) {
        val next = if (mini) 1f else 0f
        if (next == target) {
            // Changed back before the last change began (Back held twice quickly): already where it should be, so no
            // glide will finish to clear the flag.
            if (shrinkAnim.value == next) VodHost.gliding = false
            return@LaunchedEffect
        }
        VodHost.gliding = true
        if (!mini && animate) kotlinx.coroutines.delay(60)
        target = next
    }
    // Standard video view (a SurfaceView): the display hardware scales the picture, so the window's real place and
    // size are moved (only re-laid out each frame). A scaled graphics layer doesn't apply to a SurfaceView.
    val surfaceView = io.github.petebutty.viewarr.data.settings.LocalPlayback.current.videoView == io.github.petebutty.viewarr.data.settings.VideoView.Standard
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullW = maxWidth
        val fullH = maxHeight
        val tx = if (left) 40.dp else fullW - 40.dp - w
        val ty = if (top) 56.dp else fullH - 32.dp - h
        // The same player either way, so it carries on without a pause. Compatible: the frame is only ever scaled,
        // in the corner too (resizing a TextureView made the players resize their video surface, which jolted).
        // No border: one less thing to draw while it moves.
        val scale = w / fullW
        val density = androidx.compose.ui.platform.LocalDensity.current
        val txPx = with(density) { tx.roundToPx() }
        val tyPx = with(density) { ty.roundToPx() }
        val wPx = with(density) { w.roundToPx() }
        val hPx = with(density) { h.roundToPx() }
        Box(
            if (surfaceView) Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val p = shrinkAnim.value
                    val fw = constraints.maxWidth
                    val fh = constraints.maxHeight
                    val cw = (fw + (wPx - fw) * p).toInt().coerceAtLeast(1)
                    val ch = (fh + (hPx - fh) * p).toInt().coerceAtLeast(1)
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(cw, ch))
                    layout(fw, fh) { placeable.place((txPx * p).toInt(), (tyPx * p).toInt()) }
                }
                .background(Color.Black)
            else Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val shrink = shrinkAnim.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    val k = 1f + (scale - 1f) * shrink
                    scaleX = k
                    scaleY = k
                    translationX = with(density) { tx.toPx() } * shrink
                    translationY = with(density) { ty.toPx() } * shrink
                }
                .background(Color.Black),
        ) {
          // In the corner: nothing inside the player takes the highlight by moving onto it (the page's Right / Down
          // went in there, out of sight); the corner picture's own highlight (Full screen / Close) is drawn by the page.
          // The search button while the film's full screen: it goes to the corner and search opens (search first,
          // whatever's playing). The rest of that press is swallowed (a tap of Menu would also open audio & subtitles).
          val remote = io.github.petebutty.viewarr.data.settings.LocalRemote.current
          val searchHeld = androidx.compose.runtime.remember { booleanArrayOf(false) }
          val searchKeys = Modifier.onPreviewKeyEvent { e ->
              if (searchHeld[0]) { if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) searchHeld[0] = false; return@onPreviewKeyEvent true }
              if (mini || e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || !io.github.petebutty.viewarr.ui.common.SearchKey.hit(e, remote)) return@onPreviewKeyEvent false
              searchHeld[0] = true
              VodHost.minimise()
              VodHost.openSearch()
              true
          }
          Box(if (mini && !VodHost.gliding) Modifier.fillMaxSize()
                  .focusProperties { enter = { androidx.compose.ui.focus.FocusRequester.Cancel } }.focusGroup()
              else Modifier.fillMaxSize().then(searchKeys)) {
            // A new film or episode gets a fresh player and picture surface. Without this the old screen's
            // surface stayed attached to the old player: the picture froze and only the new one's sound played.
            androidx.compose.runtime.key(s) {
                PlayerHost(stream = s, onClose = { src.onClose(); VodHost.afterStop() }, art = art, onProgress = src.onProgress)
            }
          }
        }
    }
}
