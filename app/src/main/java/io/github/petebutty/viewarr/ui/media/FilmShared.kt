package io.github.petebutty.viewarr.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.petebutty.viewarr.data.media.PlayerArt
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.playback.SeekThumbs
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import io.github.petebutty.viewarr.ui.common.LocalQuickSearchOpen
import io.github.petebutty.viewarr.ui.player.AvSyncPanel
import io.github.petebutty.viewarr.ui.player.StreamQuality
import io.github.petebutty.viewarr.ui.player.TransparencyPanel
import io.github.petebutty.viewarr.ui.player.TvSettingsPanel
import io.github.petebutty.viewarr.ui.settings.AppearanceViewModel
import io.github.petebutty.viewarr.ui.settings.UiOptsViewModel

/*
 * What the two film players (the built-in one, VodPlayer.kt, and mpv, MpvPlayer.kt) have in common, written once so
 * they can't drift apart: the seek-bar pictures, the info bar's wiring, the timers and focus, the key handling, and
 * everything drawn over the picture. Each player keeps only what's its own (the picture, its tracks, its stats).
 */

/** The seek-bar pictures: the server's own, else the box's (Settings → Playback → Seek-bar previews); closed with the film. */
@Composable
fun rememberFilmThumbs(stream: VodStream): SeekThumbs? {
    val p = AppColors.palette
    val thumbs = remember(stream) { SeekThumbs.forStream(stream.url, stream.headers, stream.thumbs.takeIf { p.serverPreviews }, stream.boxPreviews && p.boxPreviews) }
    DisposableEffect(thumbs) { onDispose { thumbs?.close() } }
    return thumbs
}

/** The info bar's buttons, the remote ([keys]) and what its presses do ([hooks]). */
class FilmWiring(val buttons: List<PlayerButton>, val keys: VodKeys, val hooks: VodHooks)

/**
 * The info bar's buttons as you've arranged them, the remote, and the player as the remote's Play / Pause reaches it
 * with the film in the corner ([VodHost.remote]). [switchPlayer]: carry on in the other player (null: there isn't one).
 */
@Composable
fun rememberFilmWiring(
    remote: VodRemote, panels: FilmPanels, menu: PlayerMenu, scrub: Scrubber, skip: IntroSkip, seekMs: Long,
    switchPlayer: (() -> Unit)?, onClose: () -> Unit, showOsd: () -> Unit, backStep: () -> Unit,
): FilmWiring {
    val barAppearance: AppearanceViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val barOpts: UiOptsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val buttons = filmButtons(menu, canSwitch = switchPlayer != null)
    val keys = remember { VodKeys(scrub, menu) }
    DisposableEffect(Unit) { onDispose { if (VodHost.remote != null) VodHost.remote = null } }
    SideEffect { VodHost.remote = remote }
    val press: (PlayerButton) -> Unit = { b -> if (pressFilmButton(b, remote, panels, menu, onClose, switchPlayer, barOpts::set)) showOsd() }
    val hooks = filmHooks(buttons, remote, panels, menu, skip, seekMs, press = press, showOsd = showOsd,
        backStep = backStep, onClose = onClose, barAppearance = barAppearance)
    return FilmWiring(buttons, keys, hooks)
}

/**
 * The timers and focus: in the corner (or under the menu) everything over the film closes and the page has the
 * remote; full screen the player takes the remote (and takes it back if something else does, no panel being open);
 * pausing shows the film's info, playing hides it; the controls close after a while without a key (not paused).
 */
@Composable
fun FilmEffects(panels: FilmPanels, menu: PlayerMenu, scrub: Scrubber, paused: Boolean, mini: Boolean, hasFocus: Boolean, focus: FocusRequester, hideOsd: () -> Unit) {
    LaunchedEffect(mini) {
        if (mini) {
            menu.open = false; panels.tracksOpen = false; panels.transOpen = false; panels.syncOpen = false; panels.tvOpen = false; panels.detailsOn = false
            if (scrub.active) scrub.cancel()
            hideOsd()
            return@LaunchedEffect
        }
        // The player must hold focus itself, or the remote keeps driving the screen underneath.
        repeat(3) { awaitFrame(); takeFilmFocus(focus) }
    }
    val searchOpen = LocalQuickSearchOpen.current
    val anyOpen = panels.anyOpen
    LaunchedEffect(hasFocus, anyOpen, searchOpen, mini) {
        if (!hasFocus && !anyOpen && !searchOpen && !mini) { delay(150); takeFilmFocus(focus) }
    }
    LaunchedEffect(paused) {
        panels.pausedLong = false
        // Paused (the Play / Pause button or the remote's): the film's info shows straight away; playing hides it.
        if (paused) panels.pausedLong = true else panels.detailsOn = false
    }
    LaunchedEffect(menu.open, menu.lastKey, paused) {
        if (menu.open && !paused) { delay(maxOf(8_000L, 2 * AppColors.palette.controlsMs)); menu.open = false }
    }
}

/**
 * The player's box: black, holding focus, and the remote handled here (not by the video view's own buttons, which
 * never get key focus inside Compose). A panel over the film (audio & subtitles, transparency…) has the keys instead.
 */
fun Modifier.filmKeys(focus: FocusRequester, panels: FilmPanels, mini: Boolean, onFocus: (Boolean) -> Unit, wiring: FilmWiring, remote: VodRemote): Modifier =
    fillMaxSize()
        .background(Color.Black)
        .focusRequester(focus)
        .onFocusChanged { onFocus(it.isFocused) }
        .focusable(enabled = !panels.anyOpen && !(mini && !VodHost.gliding))
        .onPreviewKeyEvent { e ->
            if (VodHost.eatsBack(e)) return@onPreviewKeyEvent true
            if (panels.anyOpen) return@onPreviewKeyEvent false
            wiring.keys.handle(e, remote, wiring.hooks)
        }

/** Where the film is and how it looks, as the player reads it, for the controls. */
class FilmState(
    /**
     * Where it is and how far it has loaded: read only where the controls are drawn (they change four times a second;
     * read by the player itself, its whole body redrew with them).
     */
    val positionMs: () -> Long, val durationMs: Long, val bufferedMs: () -> Long, val paused: Boolean, val buffering: Boolean,
    /** Playing has begun (the first picture): until then, the loading screen. */
    val started: Boolean,
    /** The controls are up (paused, loading, a key a moment ago, seeking, or the buttons open). */
    val osdVisible: Boolean,
    val quality: StreamQuality?,
)

/**
 * Everything over the picture: the film's info, the controls, Skip intro, the loading screen, the
 * panels (audio & subtitles from [tracks], the player's own) and an error. [otherPlayer]: the switch button's name.
 */
@Composable
fun BoxScope.FilmOverlays(
    stream: VodStream, art: PlayerArt?, f: FilmState, mini: Boolean, panels: FilmPanels, menu: PlayerMenu, scrub: Scrubber,
    skip: IntroSkip, buttons: List<PlayerButton>, thumbs: SeekThumbs?, otherPlayer: String, error: String?, focus: FocusRequester,
    holdOsd: (Boolean) -> Unit, tracks: @Composable BoxScope.() -> Unit,
) {
    // Previewarr Mobile: its own controls, made for a finger (ui/mobile), in place of the TV's info bar and details.
    if (io.github.petebutty.viewarr.ui.mobile.Mobile.on) {
        if (!mini) io.github.petebutty.viewarr.ui.mobile.MobileFilmControls(stream, art, f, panels)
        if (!mini && f.started && !panels.anyOpen) IntroSkipPill(skip, Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 120.dp))
        PlayerLoading(stream.title, art, visible = !mini && !f.started && error == null)
        if (panels.tracksOpen && !mini) io.github.petebutty.viewarr.ui.mobile.Mobile.Phone { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { tracks() } }
        error?.let {
            Text(it, fontSize = 18.sp, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(20.dp))
        }
        return
    }
    if (!mini && f.started && (panels.detailsOn || (f.paused && panels.pausedLong && !panels.anyOpen && !scrub.active))) PlayerDetails(stream.title, art)
    if (!mini && f.started && f.osdVisible) PlayerOsd(
        stream.title, f.positionMs(), f.durationMs, f.paused, f.buffering, scrub.targetMs, thumbs, Modifier.align(Alignment.BottomCenter), logo = art?.logo,
        from = stream.from,
        bufferedMs = f.bufferedMs(),
        // The badges: the film's own picture, frame rate and sound, as the player reads them.
        quality = f.quality,
        buttons = filmOsdButtons(buttons, menu, f.paused, otherPlayer),
        editingButtons = menu.open && menu.editing,
        buttonFocus = if (menu.open && !menu.onBar && !menu.onEpisodes) menu.focusIndex(buttons) else -1,
        barFocused = menu.open && menu.onBar,
        downRows = if (menu.open && menu.onEpisodes) FilmShortcuts.downRows(menu.withNext) else null,
        downRow = menu.downRow,
        episodeFocus = menu.episode,
    )
    if (!mini && f.started && !menu.open && !scrub.active && !panels.anyOpen && !f.osdVisible) IntroSkipPill(skip, Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 36.dp))
    FilmShortcutDialogs(menu)
    PlayerLoading(stream.title, art, visible = !mini && !f.started && error == null)
    if (panels.tracksOpen && !mini) tracks()
    if (panels.transOpen && !mini) TransparencyPanel(onClose = { panels.transOpen = false; takeFilmFocus(focus) },
        // On its info bar rows: the film's bar up behind it, while they're being changed.
        onInfoBar = holdOsd)
    if (panels.syncOpen && !mini) AvSyncPanel(onClose = { panels.syncOpen = false; takeFilmFocus(focus) }, modifier = Modifier.align(Alignment.CenterEnd))
    if (panels.tvOpen && !mini) TvSettingsPanel(onClose = { panels.tvOpen = false; takeFilmFocus(focus) }, modifier = Modifier.align(Alignment.CenterEnd))
    error?.let {
        Text(it, fontSize = 18.sp, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(20.dp))
    }
}
