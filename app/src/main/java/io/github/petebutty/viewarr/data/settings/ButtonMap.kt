package io.github.petebutty.viewarr.data.settings

import android.view.KeyEvent

/**
 * Settings → Remote buttons, laid out as TiviMate does: each part of the app lists every button (and its long press)
 * with a drop-down of what it does. Only changes are saved (RemoteSettings.buttonMap, "Screen.Press" → action name);
 * everything else is [ButtonMap.default], the way the chosen preset works. A button does one thing per screen.
 */
enum class BtnScreen(val label: String) {
    Guide("TV guide"),
    Live("Live TV player"),
    Film("Films & shows player"),
    Pages("Movies, Shows & pages"),
}

/** A press that can be given an action, in the order Settings lists them (as TiviMate's). */
enum class BtnPress(val label: String) {
    Ok("OK"),
    OkHold("Long OK"),
    Back("Back"),
    BackHold("Long Back"),
    Left("Left"),
    LeftHold("Long Left"),
    Right("Right"),
    RightHold("Long Right"),
    Up("Up"),
    UpHold("Long Up"),
    Down("Down"),
    DownHold("Long Down"),
    ChannelUp("Ch +"),
    ChannelDown("Ch −"),
    Menu("Menu"),
    MenuHold("Long Menu"),
    PlayPause("Play / pause"),
    PlayPauseHold("Long Play / pause"),
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
    Banner("Info banner"),
    ChannelList("Channel list"),
    GroupsMenu("Channel groups (menu)"),
    MainMenu("Main menu"),
    Guide("TV guide"),
    FullScreen("Back to full screen"),
    NextChannel("Next channel"),
    PrevChannel("Previous channel"),
    LastChannel("The channel watched before"),
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
    PlayPause("Play / pause"),
    GoLive("Go live"),
    StartOver("Start over / watch from the start"),
    Description("Programme details"),
    Details("Details"),
    AudioSubs("Audio & subtitles"),
    MediaInfo("Media info"),
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
        BtnScreen.Live to listOf(BtnPress.Ok, BtnPress.OkHold, BtnPress.Back, BtnPress.BackHold, BtnPress.Left, BtnPress.LeftHold, BtnPress.Right, BtnPress.RightHold,
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
            BtnAction.Guide, BtnAction.NextChannel, BtnAction.PrevChannel, BtnAction.LastChannel, BtnAction.SkipBack, BtnAction.SkipForward,
            BtnAction.PlayPause, BtnAction.GoLive, BtnAction.StartOver, BtnAction.Description, BtnAction.AudioSubs, BtnAction.RecordNow,
            BtnAction.RecordOptions, BtnAction.MultiView, BtnAction.Transparency, BtnAction.Stop) + pages,
        BtnScreen.Film to listOf(BtnAction.Nothing, BtnAction.Controls, BtnAction.TimeTitle, BtnAction.SeekBar, BtnAction.SkipBack, BtnAction.SkipForward,
            BtnAction.PlayPause, BtnAction.AudioSubs, BtnAction.Details, BtnAction.MediaInfo, BtnAction.SwitchPlayer, BtnAction.Stop, BtnAction.Guide,
            BtnAction.MainMenu, BtnAction.Search, BtnAction.Settings),
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
    private val defaults: Map<Pair<BtnScreen, BtnPress>, BtnAction> = mapOf(
        (BtnScreen.Guide to BtnPress.BackHold) to BtnAction.GroupsMenu,
        (BtnScreen.Guide to BtnPress.Record) to BtnAction.RecordNow,
        (BtnScreen.Guide to BtnPress.Stop) to BtnAction.Stop,
        (BtnScreen.Guide to BtnPress.Red) to BtnAction.RecordOptions,
        (BtnScreen.Guide to BtnPress.Green) to BtnAction.GroupsMenu,
        (BtnScreen.Guide to BtnPress.Yellow) to BtnAction.DayBack,
        (BtnScreen.Guide to BtnPress.Blue) to BtnAction.DayForward,
        (BtnScreen.Live to BtnPress.BackHold) to BtnAction.GroupsMenu,
        (BtnScreen.Live to BtnPress.Record) to BtnAction.RecordNow,
        (BtnScreen.Live to BtnPress.Stop) to BtnAction.Stop,
        (BtnScreen.Live to BtnPress.Red) to BtnAction.RecordOptions,
        (BtnScreen.Live to BtnPress.Green) to BtnAction.ChannelList,
        (BtnScreen.Live to BtnPress.Yellow) to BtnAction.StartOver,
        (BtnScreen.Live to BtnPress.Blue) to BtnAction.Banner,
        (BtnScreen.Live to BtnPress.Info) to BtnAction.Banner,
        (BtnScreen.Film to BtnPress.Green) to BtnAction.AudioSubs,
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
        return action(s, screen, press).takeIf { it != native && !(it == BtnAction.Nothing && native == null) }
    }

    fun key(screen: BtnScreen, press: BtnPress) = "${screen.name}.${press.name}"

    /** What [press] does on [screen] with these settings. */
    fun action(s: RemoteSettings, screen: BtnScreen, press: BtnPress): BtnAction =
        s.buttonMap[key(screen, press)]?.let { n -> BtnAction.entries.firstOrNull { it.name == n } } ?: default(s, screen, press)

    /**
     * The table press a button is, or null (arrows, OK, Back, numbers… keep their own handling). Record and Info
     * come through RemoteKeys, so a button taught for them (Buttons while watching) counts too.
     */
    fun pressOf(e: androidx.compose.ui.input.key.KeyEvent): BtnPress? {
        when (e.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> return BtnPress.Red
            KeyEvent.KEYCODE_PROG_GREEN -> return BtnPress.Green
            KeyEvent.KEYCODE_PROG_YELLOW -> return BtnPress.Yellow
            KeyEvent.KEYCODE_PROG_BLUE -> return BtnPress.Blue
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> return BtnPress.ChannelUp
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> return BtnPress.ChannelDown
        }
        return when (io.github.petebutty.viewarr.ui.common.RemoteKeys.action(e)) {
            io.github.petebutty.viewarr.ui.common.RemoteAction.Record -> BtnPress.Record
            io.github.petebutty.viewarr.ui.common.RemoteAction.Info -> BtnPress.Info
            io.github.petebutty.viewarr.ui.common.RemoteAction.Stop -> BtnPress.Stop
            io.github.petebutty.viewarr.ui.common.RemoteAction.Ok -> BtnPress.Ok
            io.github.petebutty.viewarr.ui.common.RemoteAction.Back -> BtnPress.Back
            io.github.petebutty.viewarr.ui.common.RemoteAction.Up -> BtnPress.Up
            io.github.petebutty.viewarr.ui.common.RemoteAction.Down -> BtnPress.Down
            io.github.petebutty.viewarr.ui.common.RemoteAction.Left -> BtnPress.Left
            io.github.petebutty.viewarr.ui.common.RemoteAction.Right -> BtnPress.Right
            io.github.petebutty.viewarr.ui.common.RemoteAction.ChannelUp -> BtnPress.ChannelUp
            io.github.petebutty.viewarr.ui.common.RemoteAction.ChannelDown -> BtnPress.ChannelDown
            io.github.petebutty.viewarr.ui.common.RemoteAction.Menu -> BtnPress.Menu
            io.github.petebutty.viewarr.ui.common.RemoteAction.PlayPause -> BtnPress.PlayPause
            io.github.petebutty.viewarr.ui.common.RemoteAction.Rewind -> BtnPress.Rewind
            io.github.petebutty.viewarr.ui.common.RemoteAction.FastForward -> BtnPress.FastForward
            else -> null
        }
    }
}
