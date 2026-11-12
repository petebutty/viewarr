package io.github.petebutty.viewarr.ui.media

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import io.github.petebutty.viewarr.ui.common.Press
import io.github.petebutty.viewarr.ui.common.RemoteAction
import io.github.petebutty.viewarr.ui.common.RemoteKeys
import io.github.petebutty.viewarr.ui.common.TapHold

/** What a film player (the built-in one or mpv) gives the shared remote handling. */
interface VodRemote {
    val positionMs: Long
    val durationMs: Long
    fun seekTo(ms: Long)
    /** Skip from where it is ([ms] negative: back). */
    fun skip(ms: Long)
    fun togglePause()
    fun pause()
}

/** The player screen's parts the remote can open or change. */
class VodHooks(
    val buttons: List<PlayerButton>,
    val press: (PlayerButton) -> Unit,
    /** Settings → Remote → Left / Right. */
    val seekMs: Long,
    val showOsd: () -> Unit,
    /** Audio & subtitles: on the subtitles side, the audio side, or (null) as it was left. */
    val openTracks: (subs: Boolean?) -> Unit,
    val toggleDetails: () -> Unit,
    val showDetails: () -> Unit,
    val stop: () -> Unit,
    /** Settings → Remote → Picture-in-picture: 0 corner, 1 stop, 2 stop (hold Back: corner). */
    val backMode: Int = 0,
    /** Nothing open on the player (no controls, details, seek marker): Back's last step is next. */
    val atTop: () -> Boolean = { false },
    val minimise: () -> Unit = {},
    /** Back's usual step (what the player's BackHandler does): close what's open, else the last step. */
    val stepBack: () -> Unit = {},
    /**
     * Settings → Remote buttons for films: what a button that works anywhere (colours, Info, Play / pause, Stop,
     * Rewind / Fast forward) has been changed to (null: as standard, or not one of those).
     */
    val tableAction: (KeyEvent) -> io.github.petebutty.viewarr.data.settings.BtnAction? = { null },
    /** What OK, hold OK, the arrows or Menu have been changed to, with nothing open on the player (null: as standard). */
    val mapped: (io.github.petebutty.viewarr.data.settings.BtnPress) -> io.github.petebutty.viewarr.data.settings.BtnAction? = { null },
    /** The TV guide (the film stops, its place kept), and search over the page (the film goes to the corner). */
    val openGuide: () -> Unit = {},
    val search: () -> Unit = {},
    /** The live TV shortcuts under the controls: how many, and opening one (the film goes to the corner). */
    val shortcutCount: () -> Int = { 0 },
    val pickShortcut: (Int) -> Unit = {},
    /** The rows under a shortcut (their sizes), and opening a card (row, card). */
    val rowSizes: (Int) -> List<Int> = { emptyList() },
    val pickCard: (Int, Int) -> Unit = { _, _ -> },
    /** Holding OK on a card (row, card): its quicker action, if it has one. */
    val holdCard: (Int, Int) -> Unit = { _, _ -> },
    /** Skip intro / credits showing (or an intro just skipped by itself): OK skips it (or goes back). True if used. */
    val skipOk: () -> Boolean = { false },
    /** The shortcuts' Move (index, -1 / 1), and whether one has a hold menu (Viewarr's own Refresh doesn't). */
    val moveShortcut: (Int, Int) -> Unit = { _, _ -> },
    val canHoldShortcut: (Int) -> Boolean = { true },
)

/**
 * The remote during a film or episode: one copy for both players, so they can't drift apart. Back is handled by
 * each player's BackHandler (it steps out of whatever is open).
 */
class VodKeys(private val scrub: Scrubber, private val menu: PlayerMenu) {
    private val ok = TapHold()
    private val menuKey = TapHold()
    // "Stop · hold Back: corner": a tap takes Back's usual step (on release); a hold shrinks to the corner at once.
    private var backDown = false

    /** True when the event was used. */
    fun handle(e: KeyEvent, p: VodRemote, h: VodHooks): Boolean {
        val a = RemoteKeys.action(e)
        val down = e.type == KeyEventType.KeyDown
        val repeat = e.nativeKeyEvent.repeatCount
        if (a == RemoteAction.Back && h.backMode == 2) {
            // Every Back press is decided here: a hold goes to the corner whatever is open (the corner closes it);
            // a tap takes Back's usual step (controls, details… then stop).
            when {
                down && repeat == 0 -> backDown = true
                down && backDown -> {
                    backDown = false
                    // The rest of this press is swallowed at the root once the player has let go of the remote.
                    VodHost.swallowBack = true
                    h.minimise()
                }
                !down && backDown -> { backDown = false; h.stepBack() }
                // The release of a hold that already sent the film to the corner can still land here (the player
                // hadn't let go of the remote yet): that press is over, so the root mustn't swallow the next one.
                !down -> VodHost.swallowBack = false
            }
            return true
        }
        // The button table (Settings → Remote buttons → Films & shows): buttons that work whatever is open.
        h.tableAction(e)?.let { act ->
            if (down && repeat == 0) perform(act, p, h)
            return true
        }
        // On the seek bar: Left / Right move the marker, OK jumps there, Up leaves.
        if (scrub.active) {
            when (a) {
                RemoteAction.Ok -> {
                    if (down && repeat == 0) { scrub.commit()?.let(p::seekTo); ok.reset(); h.showOsd() }
                    return true
                }
                RemoteAction.Left, RemoteAction.Rewind -> { if (down) { scrub.step(-1, repeat, p.durationMs); h.showOsd() }; return true }
                RemoteAction.Right, RemoteAction.FastForward -> { if (down) { scrub.step(1, repeat, p.durationMs); h.showOsd() }; return true }
                RemoteAction.Up -> { if (down) { scrub.cancel(); h.showOsd() }; return true }
                RemoteAction.Down -> { if (down && menu.open) { scrub.cancel(); menu.onBar = false }; return true }
                else -> Unit
            }
        }
        // The controls (opened with OK): seek bar and buttons.
        if (menu.open && menu.handle(
                e, h.buttons,
                beginScrub = { dir, rep -> scrub.begin(p.positionMs); scrub.step(dir, rep, p.durationMs); h.showOsd() },
                press = h.press,
                // Up from the seek bar: pause, with what's playing (its synopsis) on screen.
                upFromBar = { p.pause(); h.showDetails(); menu.open = false },
                leftFromFirst = { VodHost.openMenu() },
                episodeCount = NextUp.upcoming.size,
                pickEpisode = { i -> NextUp.upcoming.getOrNull(i)?.let { NextUp.playEpisode(it) } },
                shortcutCount = h.shortcutCount(), pickShortcut = h.pickShortcut,
                rowSizes = h.rowSizes, pickCard = h.pickCard, holdCard = h.holdCard,
                moveShortcut = h.moveShortcut, canHold = h.canHoldShortcut,
            )
        ) return true
        // Tap OK: the controls (Play/Pause on the remote pauses). Hold OK: nothing unless set. (Either as changed in
        // Settings → Remote buttons → Films & shows.)
        if (a == RemoteAction.Ok) {
            when (ok.feed(e)) {
                // Long OK: only what it's set to in Settings → Remote buttons (its own audio / subtitles pop-up is gone: the info bar has them).
                Press.Hold -> { h.mapped(io.github.petebutty.viewarr.data.settings.BtnPress.OkHold)?.let { perform(it, p, h) } }
                Press.Tap -> if (!h.skipOk()) h.mapped(io.github.petebutty.viewarr.data.settings.BtnPress.Ok)?.let { perform(it, p, h) } ?: run { menu.show(); h.showOsd() }
                null -> Unit
            }
            return true
        }
        // Menu: audio & subtitles on a tap, when it's let go (holding Menu is search, handled above the player).
        if (a == RemoteAction.Menu) {
            if (menuKey.feed(e) == Press.Tap) h.mapped(io.github.petebutty.viewarr.data.settings.BtnPress.Menu)?.let { perform(it, p, h) } ?: h.openTracks(false)
            return true
        }
        // The arrows, changed (nothing open on the player here): their new action, once per press.
        val arrow = when (a) {
            RemoteAction.Up -> io.github.petebutty.viewarr.data.settings.BtnPress.Up
            RemoteAction.Down -> io.github.petebutty.viewarr.data.settings.BtnPress.Down
            RemoteAction.Left -> io.github.petebutty.viewarr.data.settings.BtnPress.Left
            RemoteAction.Right -> io.github.petebutty.viewarr.data.settings.BtnPress.Right
            else -> null
        }
        arrow?.let(h.mapped)?.let { act ->
            if (down && repeat == 0) perform(act, p, h)
            return true
        }
        if (!down) return false
        when (a) {
            RemoteAction.Left, RemoteAction.Rewind -> { p.skip(-h.seekMs); h.showOsd() }
            RemoteAction.Right, RemoteAction.FastForward -> { p.skip(h.seekMs); h.showOsd() }
            RemoteAction.Down -> { scrub.begin(p.positionMs); h.showOsd() }
            RemoteAction.Up -> h.showOsd()
            RemoteAction.Info -> h.toggleDetails()
            RemoteAction.PlayPause -> { p.togglePause(); h.showOsd() }
            RemoteAction.Stop -> h.stop()
            else -> return false
        }
        return true
    }

    /** A button's action from the table, on the film. */
    private fun perform(act: io.github.petebutty.viewarr.data.settings.BtnAction, p: VodRemote, h: VodHooks) {
        when (act) {
            io.github.petebutty.viewarr.data.settings.BtnAction.Controls -> { menu.show(); h.showOsd() }
            io.github.petebutty.viewarr.data.settings.BtnAction.AudioSubs -> h.openTracks(null)
            io.github.petebutty.viewarr.data.settings.BtnAction.Details -> h.toggleDetails()
            io.github.petebutty.viewarr.data.settings.BtnAction.PlayPause -> { p.togglePause(); h.showOsd() }
            io.github.petebutty.viewarr.data.settings.BtnAction.SkipBack -> { p.skip(-h.seekMs); h.showOsd() }
            io.github.petebutty.viewarr.data.settings.BtnAction.SkipForward -> { p.skip(h.seekMs); h.showOsd() }
            io.github.petebutty.viewarr.data.settings.BtnAction.Stop -> h.stop()
            io.github.petebutty.viewarr.data.settings.BtnAction.Guide -> h.openGuide()
            io.github.petebutty.viewarr.data.settings.BtnAction.Search -> h.search()
            io.github.petebutty.viewarr.data.settings.BtnAction.MainMenu -> VodHost.openMenu()
            io.github.petebutty.viewarr.data.settings.BtnAction.MediaInfo -> h.press(PlayerButton.Info)
            io.github.petebutty.viewarr.data.settings.BtnAction.SwitchPlayer -> h.press(PlayerButton.SwitchPlayer)
            io.github.petebutty.viewarr.data.settings.BtnAction.Settings -> h.press(PlayerButton.Settings)
            io.github.petebutty.viewarr.data.settings.BtnAction.TimeTitle -> h.showOsd()
            io.github.petebutty.viewarr.data.settings.BtnAction.SeekBar -> { scrub.begin(p.positionMs); h.showOsd() }
            // An episode with more after it: the bar with the next episodes; otherwise (a film) the seek bar.
            io.github.petebutty.viewarr.data.settings.BtnAction.Episodes ->
                if (NextUp.upcoming.isNotEmpty()) { menu.show(); menu.onBar = false; menu.onEpisodes = true; menu.episodesOnly = true; menu.episode = 0; h.showOsd() }
                else { scrub.begin(p.positionMs); h.showOsd() }
            else -> Unit
        }
    }

}
