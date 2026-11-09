package io.github.petebutty.viewarr.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        shown = now
        mini = false
    }
    /** What [show] last brought up. */
    private var shown: VodStream? = null

    fun minimise() { if (active) mini = true }
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
@Composable
fun VodHostLayer() {
    val src = VodHost.source ?: return
    val stream by src.playing.collectAsState()
    val art by src.art.collectAsState()
    LaunchedEffect(stream == null) {
        VodHost.active = stream != null
        if (stream == null) VodHost.mini = false
    }
    val s = stream ?: return
    val mini = VodHost.mini
    // Settings → Playback → Picture-in-picture: size and corner.
    val p = AppColors.palette
    val (w, h) = when (p.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.MINI_SIZE)) { 1 -> 320.dp to 180.dp; 2 -> 512.dp to 288.dp; else -> 400.dp to 225.dp }
    val corner = when (p.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.MINI_CORNER)) { 1 -> Alignment.BottomStart; 2 -> Alignment.TopEnd; 3 -> Alignment.TopStart; else -> Alignment.BottomEnd }
    val top = corner == Alignment.TopEnd || corner == Alignment.TopStart
    val left = corner == Alignment.BottomStart || corner == Alignment.TopStart
    Box(Modifier.fillMaxSize()) {
        // The same player either way (only its frame changes), so it carries on without a pause.
        Box(
            if (mini) Modifier
                .align(corner)
                .padding(start = if (left) 40.dp else 0.dp, end = if (left) 0.dp else 40.dp, top = if (top) 56.dp else 0.dp, bottom = if (top) 0.dp else 32.dp)
                .size(w, h)
                .border(2.dp, AppColors.Accent.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                .padding(2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
            else Modifier.fillMaxSize(),
        ) {
            // A new film or episode gets a fresh player and picture surface. Without this the old screen's
            // surface stayed attached to the old player: the picture froze and only the new one's sound played.
            androidx.compose.runtime.key(s) {
                PlayerHost(stream = s, onClose = src.onClose, art = art, onProgress = src.onProgress)
            }
        }
        if (mini) androidx.tv.material3.Text(
            "Hold Back: full screen",
            fontSize = 12.sp, color = AppColors.TextPrimary,
            modifier = Modifier.align(corner).padding(
                start = if (left) 48.dp else 0.dp, end = if (left) 0.dp else 48.dp,
                top = if (top) 56.dp + h + 8.dp else 0.dp, bottom = if (top) 0.dp else 32.dp + h + 8.dp,
            )
                .background(AppColors.Panel.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}
