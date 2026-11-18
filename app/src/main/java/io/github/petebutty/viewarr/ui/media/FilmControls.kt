package io.github.petebutty.viewarr.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import io.github.petebutty.viewarr.ui.player.BarButtonsEditor
import io.github.petebutty.viewarr.ui.player.BarItem
import kotlinx.coroutines.delay
import io.github.petebutty.viewarr.data.settings.BtnScreen
import io.github.petebutty.viewarr.data.settings.ButtonMap
import io.github.petebutty.viewarr.ui.common.BarLayout
import io.github.petebutty.viewarr.ui.player.PlayerBridge
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.playback.SleepTimer
import io.github.petebutty.viewarr.ui.common.EdgeBlocked
import io.github.petebutty.viewarr.ui.settings.AppearanceViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts

/*
 * The film controls both players share (the built-in player, VodPlayer, and mpv, MpvPlayer): what's open over the
 * film, the info bar's buttons and what they do, Back's steps and the remote's hooks. Each player keeps only what
 * its engine does differently, behind VodRemote (position, seeking, pause, the tracks in use). A fix made here is
 * made for both players (they used to carry two copies, and fixes reached one and not the other).
 */

/** What's open over the film. One per player, remembered by it. */
class FilmPanels {
    /** Audio & subtitles. */
    var tracksOpen by mutableStateOf(false)
    /** Audio & subtitles opened from the Subtitles button: start on the subtitles. */
    var tracksSubs by mutableStateOf(false)
    /** The Transparency panel (the same as live TV's), from its button. */
    var transOpen by mutableStateOf(false)
    /** The Audio sync choices, from its button. */
    var syncOpen by mutableStateOf(false)
    /** The TV settings panel (frame rate matching, picture size…), from its button. */
    var tvOpen by mutableStateOf(false)
    /** The Info bar buttons list (hold OK on a button of the bar; FilmBarEditor). */
    var barOpen by mutableStateOf(false)
    /** What's playing (poster, synopsis): the Info / Details buttons, or a moment after pausing. */
    var detailsOn by mutableStateOf(false)
    /** Paused for a moment: the film's info shows. */
    var pausedLong by mutableStateOf(false)
    /** A panel over the film has the remote. */
    val anyOpen get() = tracksOpen || transOpen || syncOpen || tvOpen || barOpen
}

/** Every button the film bar can have just now (BarLayout arranges them and leaves out the hidden ones). */
private fun filmAll(canSwitch: Boolean): List<PlayerButton> =
    listOfNotNull(
        // The TV guide first (live TV comes first in Viewarr).
        PlayerButton.Guide, PlayerButton.PlayPause, PlayerButton.Stop, PlayerButton.Details, PlayerButton.Audio, PlayerButton.Subtitles, PlayerButton.Info,
        if (canSwitch) PlayerButton.SwitchPlayer else null,
        // (Previous / Next episode: extras for shows, each only when there is one. Episodes: only with more to come.)
        if (NextUp.previous != null) PlayerButton.PrevEpisode else null, if (NextUp.episode != null) PlayerButton.NextEpisode else null,
        if (NextUp.upcoming.isNotEmpty()) PlayerButton.Episodes else null,
        PlayerButton.Sync, PlayerButton.Sleep, PlayerButton.Movies, PlayerButton.Shows, PlayerButton.Recordings,
        PlayerButton.Search, PlayerButton.Transparency, PlayerButton.BarStyle, PlayerButton.TvSettings, PlayerButton.Settings,
    )

/**
 * The info bar's buttons, as you've arranged them (hold OK on one: the Info bar buttons list, FilmBarEditor; see
 * BarLayout). [canSwitch]: the other player is available. Also tells [menu] where Play / Pause is.
 */
fun filmButtons(menu: PlayerMenu, canSwitch: Boolean): List<PlayerButton> {
    val buttons = BarLayout.arrange(filmAll(canSwitch), { BarLayout.filmId(it.name) }, editing = false, bar = BarLayout.Bar.Film)
    menu.home = buttons.indexOf(PlayerButton.PlayPause).coerceAtLeast(0)
    return buttons
}

/** Sends the film to the mini player before something opens over it (Search, a page, Settings, the guide). */
private fun toCorner(menu: PlayerMenu) { menu.open = false; VodHost.minimise() }

/**
 * What an info bar button does. [setOpt] saves a look setting (the Style button). [switchPlayer]: hands the film to
 * the other player at this moment with its tracks. Returns whether the controls should stay up afterwards.
 */
fun pressFilmButton(
    b: PlayerButton,
    p: VodRemote,
    panels: FilmPanels,
    menu: PlayerMenu,
    onClose: () -> Unit,
    switchPlayer: (() -> Unit)?,
    setOpt: (String, Int) -> Unit,
): Boolean {
    when (b) {
        PlayerButton.PlayPause -> p.togglePause()
        PlayerButton.Audio -> { panels.tracksSubs = false; panels.tracksOpen = true }
        PlayerButton.Subtitles -> { panels.tracksSubs = true; panels.tracksOpen = true }
        PlayerButton.Transparency -> { menu.open = false; panels.transOpen = true; return false }
        PlayerButton.TvSettings -> { menu.open = false; panels.tvOpen = true; return false }
        // The next info bar style, shown at once on this bar.
        PlayerButton.BarStyle -> BarLayout.nextStyle(setOpt)
        // Search over the film: it carries on in the corner (as the remote's Search).
        PlayerButton.Search -> { toCorner(menu); VodHost.openSearch(); return false }
        PlayerButton.Episodes -> FilmShortcuts.openEpisodes(menu, only = false)
        PlayerButton.PrevEpisode -> { menu.open = false; NextUp.playPrevious() }
        PlayerButton.NextEpisode -> { menu.open = false; NextUp.play() }
        PlayerButton.Sync -> { menu.open = false; panels.syncOpen = true; return false }
        PlayerButton.Sleep -> SleepTimer.cycle(
            (p.durationMs - p.positionMs).takeIf { it > 0 }?.let { System.currentTimeMillis() + it },
        )
        // A page over the film (it carries on in the corner).
        PlayerButton.Movies, PlayerButton.Shows, PlayerButton.Recordings -> { toCorner(menu); PlayerBridge.openPage(b.name); return false }
        PlayerButton.Info -> menu.infoOn = !menu.infoOn
        PlayerButton.Details -> { panels.detailsOn = !panels.detailsOn; if (panels.detailsOn) menu.open = false }
        PlayerButton.SwitchPlayer -> switchPlayer?.invoke()
        PlayerButton.Stop -> { onClose(); return false }
        // Settings over the film, which carries on full screen behind it (it went to the corner, showing whatever page
        // was underneath, the TV guide if it was started from live TV; Peter, 2026-11-16). Closing Settings: the film.
        PlayerButton.Settings -> { menu.open = false; VodHost.behind = true; PlayerBridge.openSettings(); return false }
        // TV guide: the film carries on in the mini player while the guide opens (Back on it: full screen again).
        PlayerButton.Guide -> { openGuideOverFilm(menu); return false }
    }
    return true
}

/**
 * Full screen to the mini player (Back's last step, or the remote's minimise): over the page it was started from (its
 * title page, usually), as standard since 0.28.5; or, set so, the menu open on Live TV with the TV guide behind. Buttons that open something over the film (a page, Settings, Search, the guide) go there instead.
 */
fun minimiseToLiveTv(onClose: () -> Unit) {
    VodHost.minimise()
    if (!VodHost.active) { onClose(); return }
    // Settings → Playback → Back from a film: its own page (standard since 0.28.5, Gtunney / Peter) leaves the mini
    // player over the page it was started from (its title page, usually); "The TV guide and menu" opens those.
    if (AppColors.palette.opt(UiOpts.FILM_BACK) != 1) return
    PlayerBridge.openMenuOnLiveTv()
}

/** The TV guide over a film (its button, or a remote button set to TV guide): the film carries on in the mini player. */
fun openGuideOverFilm(menu: PlayerMenu) { toCorner(menu); PlayerBridge.openGuide() }

/**
 * Back, one step: closes what's open (audio & subtitles, the seek preview, details, the rows, the
 * controls), then the next episode's countdown; the last step makes the film the mini player (as live TV's Back
 * into the guide's preview). Stop ends it, its place kept. [hideOsd] closes the controls; [showOsd] shows them.
 */
fun filmBackStep(panels: FilmPanels, menu: PlayerMenu, scrub: Scrubber, skip: IntroSkip, onClose: () -> Unit, hideOsd: () -> Unit, showOsd: () -> Unit) {
    when {
        panels.tracksOpen -> panels.tracksOpen = false
        scrub.active -> { scrub.cancel(); showOsd() }
        panels.detailsOn -> panels.detailsOn = false
        // In the rows under the controls: back up to the buttons (the Episodes button's own row: closes the bar).
        // (Back reaches here before the controls see the key, so it's this step, not PlayerControls, that does it.)
        menu.open && menu.onEpisodes -> { menu.onEpisodes = false; if (menu.episodesOnly) menu.open = false }
        menu.open -> { menu.open = false; hideOsd() }
        // The next episode's countdown: Back keeps watching this one (after anything open over it).
        skip.cancelNext() -> Unit
        // (The info bar showing by itself, after the start or a skip, doesn't take a Back: Back shrinks the film.)
        else -> minimiseToLiveTv(onClose)
    }
}

/**
 * The remote's hooks into the film controls (VodKeys reads them), the same for both players.
 */
@Composable
fun filmHooks(
    buttons: List<PlayerButton>,
    p: VodRemote,
    panels: FilmPanels,
    menu: PlayerMenu,
    skip: IntroSkip,
    seekMs: Long,
    press: (PlayerButton) -> Unit,
    showOsd: () -> Unit,
    backStep: () -> Unit,
    onClose: () -> Unit,
    barAppearance: AppearanceViewModel,
): VodHooks {
    val remoteSettings = LocalRemote.current
    // The Episodes row opens on the episode playing; the other rows on their first card.
    menu.rowStart = { r -> if (r == FilmShortcuts.nextIndex(menu.withNext)) NextUp.startAt else 0 }
    // Hold OK on a button: the Info bar buttons list (FilmBarEditor), as on live TV. (It replaced editing the bar in
    // place, (VodHooks' old toggleButton / moveButton went with it): Peter, 2026-11-17 UI review. [barAppearance] is kept
    // for the callers; the list saves through its own.)
    menu.editBar = { panels.barOpen = true }
    return VodHooks(
        buttons = buttons, press = press,
        seekMs = seekMs, showOsd = showOsd,
        openTracks = { subs -> subs?.let { panels.tracksSubs = it }; panels.tracksOpen = true },
        toggleDetails = { panels.detailsOn = !panels.detailsOn }, showDetails = { panels.detailsOn = true }, stop = onClose,
        // Back: the mini player (its last step); hold Back: close the player (as live TV).
        backMode = 3,
        stepBack = backStep,
        skipOk = { skip.ok({ p.seekTo(it) }, onNext = { NextUp.play() }) },
        // Settings → Remote buttons → Films & shows: buttons changed from standard.
        tableAction = { e -> ButtonMap.pressOf(e)?.takeIf { it !in ButtonMap.filmNavigation }?.let { ButtonMap.changed(remoteSettings, BtnScreen.Film, it) } },
        mapped = { pr -> ButtonMap.changed(remoteSettings, BtnScreen.Film, pr) },
        // A remote button set to TV guide: the film carries on in the mini player (it used to stop it).
        openGuide = { openGuideOverFilm(menu) },
        search = { VodHost.minimise(); VodHost.openSearch() },
    )
}

/** The info bar's buttons as drawn. */
fun filmOsdButtons(buttons: List<PlayerButton>, menu: PlayerMenu, paused: Boolean, otherPlayer: String): List<OsdButton>? {
    if (!menu.open) return null
    val ids = buttons.map { BarLayout.filmId(it.name) }
    // The divider after the locked buttons (BarLayout.arrange puts them first), not after Stop and the guide.
    val dividerAfter = BarLayout.lastLocked(ids, BarLayout.Bar.Film)
    return buttons.mapIndexed { i, b ->
        val o = b.osd(paused, menu.infoOn, otherPlayer)
        OsdButton(o.icon, o.label, groupEnd = ids[i] == dividerAfter && i != buttons.lastIndex)
    }
}

/**
 * Hold OK on a film bar button: the Info bar buttons list for the film bar (BarButtonsEditor with Bar.Film, the same
 * list live TV's hold OK opens and Settings → Appearance → Info bar buttons shows). Its own window has the remote, so
 * the controls are kept up meanwhile (they close a while after the last key, FilmEffects). The film going to the
 * corner closes it. Both players call this, beside FilmOverlays.
 */
@Composable
fun FilmBarEditor(panels: FilmPanels, menu: PlayerMenu, canSwitch: Boolean, mini: Boolean, focus: FocusRequester) {
    LaunchedEffect(mini) { if (mini) panels.barOpen = false }
    if (!panels.barOpen || mini) return
    val appearance: AppearanceViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    LaunchedEffect(Unit) { while (true) { menu.touch(); delay(2_000) } }
    DisposableEffect(Unit) { menu.editing = true; onDispose { menu.editing = false } }
    val items = filmAll(canSwitch).map { b ->
        val id = BarLayout.filmId(b.name)
        // (Its plain name, as Settings lists it: not "Pause" / "Switch to mpv" as the bar says just now.)
        BarItem(id, b.osd(paused = false, infoOn = false, otherPlayer = "").icon, BarLayout.labels[id] ?: b.name)
    }
    BarButtonsEditor(
        items, appearance::setTitlePart, { appearance.setBarOrder("all", it) }, onActivity = menu::touch,
        onClose = { panels.barOpen = false; menu.touch(); takeFilmFocus(focus) },
        bar = BarLayout.Bar.Film,
    )
}

/**
 * The film player takes the remote (back to full screen, a panel closed, focus wandered): through the pages' focus
 * fences (trapFocus on the guide, menus and title pages), which otherwise refuse a request from outside them too, so
 * the film came back full screen with the remote still on the page underneath.
 */
fun takeFilmFocus(focus: FocusRequester) {
    EdgeBlocked.lettingOut { runCatching { focus.requestFocus() } }
}
