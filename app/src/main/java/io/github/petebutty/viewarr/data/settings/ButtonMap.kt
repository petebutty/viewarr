package io.github.petebutty.viewarr.data.settings

import android.view.KeyEvent
import io.github.petebutty.viewarr.ui.common.RemoteAction
import io.github.petebutty.viewarr.ui.common.RemoteKeys

/**
 * Settings → Remote buttons, laid out as classic IPTV apps does: each part of the app lists every button (and its long press)
 * with a drop-down of what it does. Only changes are saved (RemoteSettings.buttonMap, "Screen.Press" → action name);
 * everything else is [ButtonMap.default], the way the chosen preset works. A button does one thing per screen.
 */
enum class BtnScreen(val label: String) {
    Guide("TV guide"),
    // (Labels only, the names Settings → Remote buttons uses: the constants are stored. 2026-11-17.)
    Live("On live TV"),
    Film("In films & shows"),
    Pages("In menus & pages"),
}

/** A press that can be given an action, in the order Settings lists them (as classic IPTV apps'). */
enum class BtnPress(val label: String) {
    Ok("OK"),
    OkHold("Hold OK"),
    Back("Back"),
    BackHold("Hold Back"),
    Left("Left"),
    LeftHold("Hold Left"),
    Right("Right"),
    RightHold("Hold Right"),
    Up("Up"),
    UpHold("Hold Up"),
    Down("Down"),
    DownHold("Hold Down"),
    ChannelUp("Ch +"),
    ChannelDown("Ch −"),
    Menu("Menu"),
    MenuHold("Hold Menu"),
    PlayPause("Play / Pause"),
    PlayPauseHold("Hold Play / Pause"),
    Stop("Stop"),
    Rewind("Rewind"),
    FastForward("Fast forward"),
    Record("Record"),
    Info("Info"),
    Red("Red"),
    Green("Green"),
    Yellow("Yellow"),
    Blue("Blue"),
}

enum class BtnAction(val label: String) {
    Nothing("Nothing"),
    Watch("Watch, or details for later"),
    WatchInGuide("Watch in the preview (OK again: full screen)"),
    Controls("Open the controls"),
    Banner("Info bar"),
    ChannelList("Channel list"),
    GroupsMenu("Channel groups (menu)"),
    MainMenu("Main menu"),
    Guide("TV guide"),
    FullScreen("Back to full screen"),
    NextChannel("Next channel"),
    PrevChannel("Previous channel"),
    BrowseNext("Next channel in the info bar (changes when you stop)"),
    BrowsePrev("Previous channel in the info bar (changes when you stop)"),
    LastChannel("Last channel"),
    Now("Jump to now"),
    PageUp("Page up the channels"),
    PageDown("Page down the channels"),
    Earlier("Earlier programmes"),
    Later("Later programmes"),
    DayBack("Back a day"),
    DayForward("Forward a day"),
    SkipBack("Skip back"),
    SkipForward("Skip forward"),
    TimeTitle("Time and title"),
    SeekBar("The seek bar"),
    Episodes("Next episodes row (shows; films: the seek bar)"),
    PlayPause("Play / Pause"),
    GoLive("Go live"),
    StartOver("Start over / watch from the start"),
    Description("Programme details"),
    Details("Details"),
    AudioSubs("Audio & subtitles"),
    MediaInfo("Stream info"),
    SwitchPlayer("Switch player"),
    RecordNow("Record (straight away)"),
    RecordOptions("Record options"),
    MultiView("Multi-view"),
    Transparency("Transparency"),
    Stop("Stop"),
    Search("Search"),
    Movies("Movies"),
    Shows("Shows"),
    Recordings("Recordings"),
    Music("Music"),
    Settings("Settings"),
    Exit("Exit Viewarr"),
}

object ButtonMap {
    private val colours = listOf(BtnPress.Info, BtnPress.Red, BtnPress.Green, BtnPress.Yellow, BtnPress.Blue)
    private val media = listOf(BtnPress.PlayPause, BtnPress.PlayPauseHold, BtnPress.Stop, BtnPress.Rewind, BtnPress.FastForward, BtnPress.Record)

    /**
     * The presses each screen has. The guide's Left and Back, and the pages' Left on the first poster and Back, have
     * their own drop-downs (they step: jump to now first, the menu at the start…); OK and the arrows in the guide and on
     * pages always move around.
     */
    val presses: Map<BtnScreen, List<BtnPress>> = mapOf(
        BtnScreen.Guide to listOf(BtnPress.Ok, BtnPress.OkHold, BtnPress.BackHold, BtnPress.ChannelUp, BtnPress.ChannelDown, BtnPress.Menu, BtnPress.MenuHold) + media + colours,
        // (No Back or Long Back: on live TV Back always goes to the guide / mini player and Long Back stops the
        // channel, as for films, since 0.28.3.)
        BtnScreen.Live to listOf(BtnPress.Ok, BtnPress.OkHold, BtnPress.Left, BtnPress.LeftHold, BtnPress.Right, BtnPress.RightHold,
            BtnPress.Up, BtnPress.UpHold, BtnPress.Down, BtnPress.DownHold, BtnPress.ChannelUp, BtnPress.ChannelDown, BtnPress.Menu, BtnPress.MenuHold) + media + colours,
        BtnScreen.Film to listOf(BtnPress.Ok, BtnPress.OkHold, BtnPress.Left, BtnPress.Right, BtnPress.Up, BtnPress.Down, BtnPress.Menu,
            BtnPress.PlayPause, BtnPress.Stop, BtnPress.Rewind, BtnPress.FastForward, BtnPress.Info) + colours,
        BtnScreen.Pages to listOf(BtnPress.BackHold, BtnPress.Menu, BtnPress.MenuHold) + colours,
    )

    /** The long press of a button (timed above the screens), for the ones that have one besides OK, Back and Menu. */
    val longOf = mapOf(BtnPress.Ok to BtnPress.OkHold, BtnPress.Left to BtnPress.LeftHold, BtnPress.Right to BtnPress.RightHold,
        BtnPress.Up to BtnPress.UpHold, BtnPress.Down to BtnPress.DownHold, BtnPress.PlayPause to BtnPress.PlayPauseHold)

    private val pages = listOf(BtnAction.Search, BtnAction.Movies, BtnAction.Shows, BtnAction.Recordings, BtnAction.Music, BtnAction.Settings, BtnAction.Exit)

    /** What each screen's drop-downs offer: everything Viewarr can do there. */
    val actions: Map<BtnScreen, List<BtnAction>> = mapOf(
        BtnScreen.Guide to listOf(BtnAction.Nothing, BtnAction.Watch, BtnAction.WatchInGuide, BtnAction.RecordOptions, BtnAction.RecordNow, BtnAction.Description, BtnAction.StartOver,
            BtnAction.GroupsMenu, BtnAction.MainMenu, BtnAction.FullScreen, BtnAction.Now, BtnAction.PageUp, BtnAction.PageDown, BtnAction.Earlier,
            BtnAction.Later, BtnAction.DayBack, BtnAction.DayForward, BtnAction.Stop) + pages,
        BtnScreen.Live to listOf(BtnAction.Nothing, BtnAction.Controls, BtnAction.Banner, BtnAction.ChannelList, BtnAction.GroupsMenu, BtnAction.MainMenu,
            BtnAction.Guide, BtnAction.NextChannel, BtnAction.PrevChannel, BtnAction.BrowseNext, BtnAction.BrowsePrev, BtnAction.LastChannel, BtnAction.SkipBack, BtnAction.SkipForward,
            BtnAction.PlayPause, BtnAction.GoLive, BtnAction.StartOver, BtnAction.Description, BtnAction.AudioSubs, BtnAction.RecordNow,
            BtnAction.RecordOptions, BtnAction.MultiView, BtnAction.Transparency, BtnAction.Stop) + pages,
        BtnScreen.Film to listOf(BtnAction.Nothing, BtnAction.Controls, BtnAction.TimeTitle, BtnAction.SeekBar, BtnAction.SkipBack, BtnAction.SkipForward,
            BtnAction.PlayPause, BtnAction.AudioSubs, BtnAction.Details, BtnAction.MediaInfo, BtnAction.SwitchPlayer, BtnAction.Stop, BtnAction.Guide,
            BtnAction.MainMenu, BtnAction.Search, BtnAction.Settings, BtnAction.Transparency, BtnAction.Episodes),
        BtnScreen.Pages to listOf(BtnAction.Nothing, BtnAction.MainMenu, BtnAction.Guide) + pages,
    )

    /** The film player's buttons that move around its controls when they're open: changed ones act only when nothing is. */
    val filmNavigation = setOf(BtnPress.Ok, BtnPress.OkHold, BtnPress.Up, BtnPress.Down, BtnPress.Left, BtnPress.Right, BtnPress.Menu)

    /**
     * What the app's own handling of a press does there (null: it has none, the press is free). A press set to this
     * runs that handling as it always has (holds, repeats, "Rewind?"…); set to anything else it runs the new action.
     */
    fun native(s: RemoteSettings, screen: BtnScreen, press: BtnPress): BtnAction? = when (screen) {
        BtnScreen.Guide -> when (press) {
            BtnPress.Ok -> BtnAction.Watch
            BtnPress.OkHold -> BtnAction.RecordOptions
            BtnPress.ChannelUp -> if (s.channelKeys == ChannelKeys.Reversed) BtnAction.PageDown else BtnAction.PageUp
            BtnPress.ChannelDown -> if (s.channelKeys == ChannelKeys.Reversed) BtnAction.PageUp else BtnAction.PageDown
            BtnPress.Rewind -> BtnAction.Earlier
            BtnPress.FastForward -> BtnAction.Later
            BtnPress.Menu, BtnPress.PlayPause -> BtnAction.Nothing
            BtnPress.MenuHold -> menuHold(s)
            else -> null
        }
        BtnScreen.Live -> when (press) {
            BtnPress.Ok -> when (s.playerOk) { PlayerOk.Controls -> BtnAction.Controls; PlayerOk.Banner -> BtnAction.Banner; PlayerOk.ChannelList -> BtnAction.ChannelList }
            BtnPress.Up, BtnPress.Down -> when (s.zap) {
                ZapDirection.ChannelList -> BtnAction.ChannelList
                ZapDirection.UpNext -> if (press == BtnPress.Up) BtnAction.NextChannel else BtnAction.PrevChannel
                ZapDirection.UpPrevious -> if (press == BtnPress.Up) BtnAction.PrevChannel else BtnAction.NextChannel
            }
            BtnPress.Left -> if (s.liveSides == LiveSides.ListAndLast) BtnAction.ChannelList else BtnAction.SkipBack
            BtnPress.Right -> if (s.liveSides == LiveSides.ListAndLast) BtnAction.LastChannel else BtnAction.SkipForward
            BtnPress.Back -> BtnAction.Guide
            BtnPress.ChannelUp -> BtnAction.NextChannel
            BtnPress.ChannelDown -> BtnAction.PrevChannel
            BtnPress.Menu -> BtnAction.AudioSubs
            BtnPress.PlayPause -> BtnAction.PlayPause
            BtnPress.Rewind -> BtnAction.SkipBack
            BtnPress.FastForward -> BtnAction.SkipForward
            BtnPress.MenuHold -> menuHold(s)
            else -> null
        }
        BtnScreen.Film -> when (press) {
            BtnPress.Ok -> BtnAction.Controls
            // (Long OK's own pop-up of soundtracks and subtitles is gone: they're in the info bar's buttons.)
            BtnPress.Menu -> BtnAction.AudioSubs
            BtnPress.Up -> BtnAction.TimeTitle
            BtnPress.Down -> BtnAction.SeekBar
            BtnPress.Left, BtnPress.Rewind -> BtnAction.SkipBack
            BtnPress.Right, BtnPress.FastForward -> BtnAction.SkipForward
            BtnPress.PlayPause -> BtnAction.PlayPause
            BtnPress.Stop -> BtnAction.Stop
            else -> null
        }
        BtnScreen.Pages -> when (press) {
            BtnPress.Menu -> BtnAction.Nothing
            BtnPress.MenuHold -> menuHold(s)
            else -> null
        }
    }

    /** Holding Menu is search out of the box (Settings → Remote buttons → Search button: Automatic). */
    private fun menuHold(s: RemoteSettings) = if (s.searchButton == SearchButton.Auto) BtnAction.Search else BtnAction.Nothing

    /** Out of the box, beyond each press's own handling: IPTV-box style colour buttons, Record and Info. */
    // (The Viewarr preset, 2026-11-15: classic IPTV apps' buttons, keeping Viewarr's Long OK record options in the guide,
    // Left / Right rewind and skip on live TV, Record = record now and the guide's colour buttons.)
    private val defaults: Map<Pair<BtnScreen, BtnPress>, BtnAction> = mapOf(
        (BtnScreen.Guide to BtnPress.BackHold) to BtnAction.GroupsMenu,
        (BtnScreen.Guide to BtnPress.Menu) to BtnAction.MainMenu,
        (BtnScreen.Guide to BtnPress.PlayPause) to BtnAction.Description,
        (BtnScreen.Guide to BtnPress.Info) to BtnAction.Description,
        (BtnScreen.Guide to BtnPress.Record) to BtnAction.RecordNow,
        (BtnScreen.Guide to BtnPress.Stop) to BtnAction.Stop,
        (BtnScreen.Guide to BtnPress.Red) to BtnAction.RecordOptions,
        (BtnScreen.Guide to BtnPress.Green) to BtnAction.GroupsMenu,
        (BtnScreen.Guide to BtnPress.Yellow) to BtnAction.DayBack,
        (BtnScreen.Guide to BtnPress.Blue) to BtnAction.DayForward,
        (BtnScreen.Live to BtnPress.OkHold) to BtnAction.MainMenu,
        (BtnScreen.Live to BtnPress.Menu) to BtnAction.MainMenu,
        (BtnScreen.Live to BtnPress.PlayPauseHold) to BtnAction.GoLive,
        (BtnScreen.Live to BtnPress.Record) to BtnAction.RecordNow,
        (BtnScreen.Live to BtnPress.Stop) to BtnAction.Stop,
        (BtnScreen.Live to BtnPress.Info) to BtnAction.Banner,
        (BtnScreen.Live to BtnPress.Red) to BtnAction.Guide,
        (BtnScreen.Live to BtnPress.Green) to BtnAction.GroupsMenu,
        (BtnScreen.Live to BtnPress.Yellow) to BtnAction.Controls,
        (BtnScreen.Live to BtnPress.Blue) to BtnAction.Settings,
        (BtnScreen.Film to BtnPress.Green) to BtnAction.AudioSubs,
        // Down while an episode plays: the next episodes (a film: the seek bar, as before).
        (BtnScreen.Film to BtnPress.Down) to BtnAction.Episodes,
        (BtnScreen.Film to BtnPress.Blue) to BtnAction.Details,
        (BtnScreen.Film to BtnPress.Info) to BtnAction.Details,
        (BtnScreen.Pages to BtnPress.BackHold) to BtnAction.MainMenu,
    )

    fun default(s: RemoteSettings, screen: BtnScreen, press: BtnPress): BtnAction =
        defaults[screen to press] ?: native(s, screen, press) ?: BtnAction.Nothing

    /**
     * What [press] does on [screen], when it isn't the app's own handling of it (null: let that run). A free press
     * left unused passes on too (to whatever else uses it there); one whose own handling was taken away does nothing.
     */
    fun changed(s: RemoteSettings, screen: BtnScreen, press: BtnPress): BtnAction? {
        if (press !in presses[screen].orEmpty()) return null
        val native = native(s, screen, press)
        val a = action(s, screen, press)
        // Set to Nothing on purpose (Record or Info, say): it does nothing, rather than passing on to their usual job.
        if (a == BtnAction.Nothing && s.buttonMap[key(screen, press)] == BtnAction.Nothing.name) return BtnAction.Nothing
        return a.takeIf { it != native && !(it == BtnAction.Nothing && native == null) }
    }

    fun key(screen: BtnScreen, press: BtnPress) = "${screen.name}.${press.name}"

    /** What [press] does on [screen] with these settings. */
    fun action(s: RemoteSettings, screen: BtnScreen, press: BtnPress): BtnAction {
        val a = chosen(s, screen, press)
        // OK keeps the screen's core job unless another button has it, so no setting can lock you out of it.
        val need = core[screen] ?: return a
        if (press == BtnPress.Ok && a !in need && !coreElsewhere(s, screen, need))
            return default(s, screen, press).takeIf { it in need } ?: need.first()
        return a
    }

    /**
     * The guide's Back on what's on now with nothing playing (Settings → Remote buttons → Back). "Ask to exit" needs
     * another way to the main menu (Left on now, or a button every remote has set to Main menu); without one it opens
     * the menu, so no setting leaves the guide with no way to the menu.
     */
    fun guideBack(s: RemoteSettings): GuideBack {
        if (s.guideBack != GuideBack.NowThenExit || s.guideLeft == GuideLeft.NowMenu) return s.guideBack
        val menuElsewhere = presses[BtnScreen.Guide].orEmpty().any {
            it in everyRemote && claimedBy(s, it) == null && action(s, BtnScreen.Guide, it) == BtnAction.MainMenu
        }
        return if (menuElsewhere) GuideBack.NowThenExit else GuideBack.Menu
    }

    /**
     * Presses every remote has (Shield and Fire TV remotes have no colour, media or Info buttons), so only these can
     * take over a screen's core job from OK.
     */
    private val everyRemote = setOf(BtnPress.OkHold, BtnPress.Up, BtnPress.UpHold, BtnPress.Down, BtnPress.DownHold,
        BtnPress.Left, BtnPress.LeftHold, BtnPress.Right, BtnPress.RightHold, BtnPress.Menu, BtnPress.MenuHold)

    /** Whether a press besides OK, on every remote and not taken by Search or an extra button, does the core job. */
    private fun coreElsewhere(s: RemoteSettings, screen: BtnScreen, need: Set<BtnAction>) = presses[screen].orEmpty().any {
        it != BtnPress.Ok && it in everyRemote && claimedBy(s, it) == null && chosen(s, screen, it) in need
    }

    private fun chosen(s: RemoteSettings, screen: BtnScreen, press: BtnPress): BtnAction =
        s.buttonMap[key(screen, press)]?.let { n -> BtnAction.entries.firstOrNull { it.name == n } }
            ?.takeIf { allowed(screen, press, it) } ?: default(s, screen, press)

    /**
     * Each screen's core job, which some button must always do: watching from the guide, and opening the controls on
     * live TV and in films (everything else is reached from there). If OK is changed and nothing else does it, OK
     * keeps doing it.
     */
    private val core: Map<BtnScreen, Set<BtnAction>> = mapOf(
        BtnScreen.Guide to setOf(BtnAction.Watch, BtnAction.WatchInGuide),
        BtnScreen.Live to setOf(BtnAction.Controls),
        BtnScreen.Film to setOf(BtnAction.Controls),
    )

    /** Whether OK on [screen] has to keep its core job with these settings (no other button does it). */
    fun okLocked(s: RemoteSettings, screen: BtnScreen): String? {
        val need = core[screen] ?: return null
        if (coreElsewhere(s, screen, need)) return null
        return if (screen == BtnScreen.Guide) "watching" else "opening the controls"
    }

    /**
     * Whether [a] can go on [press] here. OK can't do nothing (it's how you pick anything), and only the screen's own
     * actions count, so an old or hand-edited setting falls back to standard instead of clashing.
     */
    fun allowed(screen: BtnScreen, press: BtnPress, a: BtnAction) =
        press in presses[screen].orEmpty() && a in actions[screen].orEmpty() && !(press == BtnPress.Ok && a == BtnAction.Nothing)

    /** The key code that opens search now (Auto: the remote's own search key; holding Menu is in the table). */
    fun searchCode(s: RemoteSettings): Int? = when (s.searchButton) {
        SearchButton.Auto -> KeyEvent.KEYCODE_SEARCH
        SearchButton.Red -> KeyEvent.KEYCODE_PROG_RED
        SearchButton.Green -> KeyEvent.KEYCODE_PROG_GREEN
        SearchButton.Yellow -> KeyEvent.KEYCODE_PROG_YELLOW
        SearchButton.Blue -> KeyEvent.KEYCODE_PROG_BLUE
        SearchButton.Info -> KeyEvent.KEYCODE_INFO
        SearchButton.Chosen -> s.searchKeyCode
        SearchButton.Off -> null
    }

    /** The table press a remote's own key code is (before any extra buttons taught in Settings), or null. */
    fun pressOfCode(code: Int?): BtnPress? = when (code) {
        KeyEvent.KEYCODE_PROG_RED -> BtnPress.Red
        KeyEvent.KEYCODE_PROG_GREEN -> BtnPress.Green
        KeyEvent.KEYCODE_PROG_YELLOW -> BtnPress.Yellow
        KeyEvent.KEYCODE_PROG_BLUE -> BtnPress.Blue
        KeyEvent.KEYCODE_INFO -> BtnPress.Info
        KeyEvent.KEYCODE_MENU -> BtnPress.Menu
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> BtnPress.ChannelUp
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> BtnPress.ChannelDown
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> BtnPress.PlayPause
        KeyEvent.KEYCODE_MEDIA_STOP -> BtnPress.Stop
        KeyEvent.KEYCODE_MEDIA_REWIND -> BtnPress.Rewind
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> BtnPress.FastForward
        KeyEvent.KEYCODE_MEDIA_RECORD -> BtnPress.Record
        else -> null
    }

    /**
     * What has taken [press]'s button away from the table, or null. The Search button is checked before the table, so
     * its press always opens search; a button taught as an extra button for something else (Buttons while watching)
     * does that instead. Their table rows show this rather than choices that would never run.
     */
    fun claimedBy(s: RemoteSettings, press: BtnPress): String? {
        // A long press goes with its button: the key is taken on its first press, so its hold never reaches the table.
        val tap = tapOf[press] ?: press
        if (pressOfCode(searchCode(s)) == tap) return "Search"
        return s.buttons.entries.firstOrNull { (action, code) -> pressOfCode(code) == tap && pressOfAction(action) != tap }
            ?.key?.let { n -> RemoteKeys.teachable.firstOrNull { it.first.name == n }?.second }
    }

    private val tapOf: Map<BtnPress, BtnPress> by lazy { longOf.entries.associate { (tap, long) -> long to tap } + (BtnPress.MenuHold to BtnPress.Menu) }

    private fun pressOfAction(name: String): BtnPress? = when (name) {
        "PlayPause" -> BtnPress.PlayPause; "Rewind" -> BtnPress.Rewind; "FastForward" -> BtnPress.FastForward
        "Stop" -> BtnPress.Stop; "Info" -> BtnPress.Info; "Menu" -> BtnPress.Menu; "ChannelUp" -> BtnPress.ChannelUp
        "ChannelDown" -> BtnPress.ChannelDown; "Record" -> BtnPress.Record
        else -> null
    }

    /**
     * The table press a button is, or null (arrows, OK, Back, numbers… keep their own handling). Record and Info
     * come through RemoteKeys, so a button taught for them (Buttons while watching) counts too.
     */
    fun pressOf(e: androidx.compose.ui.input.key.KeyEvent): BtnPress? {
        // A button taught as an extra button for something (a colour button for Record, say) is that, not its colour.
        if (!RemoteKeys.isTaught(e.nativeKeyEvent.keyCode)) when (e.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> return BtnPress.Red
            KeyEvent.KEYCODE_PROG_GREEN -> return BtnPress.Green
            KeyEvent.KEYCODE_PROG_YELLOW -> return BtnPress.Yellow
            KeyEvent.KEYCODE_PROG_BLUE -> return BtnPress.Blue
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> return BtnPress.ChannelUp
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> return BtnPress.ChannelDown
        }
        return when (RemoteKeys.action(e)) {
            RemoteAction.Record -> BtnPress.Record
            RemoteAction.Info -> BtnPress.Info
            RemoteAction.Stop -> BtnPress.Stop
            RemoteAction.Ok -> BtnPress.Ok
            RemoteAction.Back -> BtnPress.Back
            RemoteAction.Up -> BtnPress.Up
            RemoteAction.Down -> BtnPress.Down
            RemoteAction.Left -> BtnPress.Left
            RemoteAction.Right -> BtnPress.Right
            RemoteAction.ChannelUp -> BtnPress.ChannelUp
            RemoteAction.ChannelDown -> BtnPress.ChannelDown
            RemoteAction.Menu -> BtnPress.Menu
            RemoteAction.PlayPause -> BtnPress.PlayPause
            RemoteAction.Rewind -> BtnPress.Rewind
            RemoteAction.FastForward -> BtnPress.FastForward
            else -> null
        }
    }
}
