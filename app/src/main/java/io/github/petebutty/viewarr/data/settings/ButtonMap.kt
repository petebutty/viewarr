package io.github.petebutty.viewarr.data.settings

import android.view.KeyEvent

/**
 * Settings → Remote & navigation → Buttons: what each press does on each screen. Only changes are saved
 * (RemoteSettings.buttonMap, "Screen.Press" → action name); everything else is [defaults], the way Viewarr works
 * out of the box. The arrows, OK and Back's taps keep their own settings (Left in the guide, Up / Down on live TV…).
 */
enum class BtnScreen(val label: String) {
    Guide("TV guide"),
    Live("Live TV, full screen"),
    Film("Films & shows"),
    Pages("Movies, Shows & menus"),
}

/** A press that can be given an action, in the order Settings lists them: main buttons, media, colours, extras. */
enum class BtnPress(val label: String, val group: String) {
    BackHold("Hold Back", "Main buttons"),
    Record("Record", "Media buttons"),
    Stop("Stop", "Media buttons"),
    Red("Red", "Colour buttons"),
    Green("Green", "Colour buttons"),
    Yellow("Yellow", "Colour buttons"),
    Blue("Blue", "Colour buttons"),
    Info("Info", "Other buttons"),
}

enum class BtnAction(val label: String) {
    Nothing("Nothing"),
    GroupsMenu("Channel groups (menu)"),
    MainMenu("Main menu"),
    FullScreen("Back to full screen"),
    Guide("TV guide"),
    Now("Jump to now"),
    DayBack("Back a day"),
    DayForward("Forward a day"),
    RecordNow("Record (straight away)"),
    RecordOptions("Record options"),
    ChannelList("Channel list"),
    StartOver("Start over (catch-up)"),
    Banner("Info banner"),
    AudioSubs("Audio & subtitles"),
    Details("Details"),
    PlayPause("Play / pause"),
    Stop("Stop"),
    Search("Search"),
}

object ButtonMap {
    /** Which presses each screen offers (remotes without a button just never use its row). */
    val presses: Map<BtnScreen, List<BtnPress>> = mapOf(
        BtnScreen.Guide to listOf(BtnPress.BackHold, BtnPress.Record, BtnPress.Stop, BtnPress.Red, BtnPress.Green, BtnPress.Yellow, BtnPress.Blue, BtnPress.Info),
        BtnScreen.Live to listOf(BtnPress.BackHold, BtnPress.Record, BtnPress.Stop, BtnPress.Red, BtnPress.Green, BtnPress.Yellow, BtnPress.Blue, BtnPress.Info),
        BtnScreen.Film to listOf(BtnPress.Red, BtnPress.Green, BtnPress.Yellow, BtnPress.Blue, BtnPress.Info),
        BtnScreen.Pages to listOf(BtnPress.BackHold),
    )

    /** The actions that make sense on each screen. */
    val actions: Map<BtnScreen, List<BtnAction>> = mapOf(
        BtnScreen.Guide to listOf(BtnAction.Nothing, BtnAction.GroupsMenu, BtnAction.MainMenu, BtnAction.FullScreen, BtnAction.Now,
            BtnAction.DayBack, BtnAction.DayForward, BtnAction.RecordNow, BtnAction.RecordOptions, BtnAction.Stop, BtnAction.Search),
        BtnScreen.Live to listOf(BtnAction.Nothing, BtnAction.GroupsMenu, BtnAction.MainMenu, BtnAction.Guide, BtnAction.ChannelList,
            BtnAction.RecordNow, BtnAction.RecordOptions, BtnAction.StartOver, BtnAction.Banner, BtnAction.AudioSubs, BtnAction.Stop, BtnAction.Search),
        BtnScreen.Film to listOf(BtnAction.Nothing, BtnAction.AudioSubs, BtnAction.Details, BtnAction.PlayPause, BtnAction.Stop),
        BtnScreen.Pages to listOf(BtnAction.Nothing, BtnAction.MainMenu, BtnAction.Search),
    )

    /** Out of the box: IPTV-box style colour buttons, Record and Info where they help. */
    private val defaults: Map<Pair<BtnScreen, BtnPress>, BtnAction> = mapOf(
        (BtnScreen.Guide to BtnPress.BackHold) to BtnAction.GroupsMenu,
        (BtnScreen.Guide to BtnPress.Record) to BtnAction.RecordNow,
        (BtnScreen.Guide to BtnPress.Stop) to BtnAction.Stop,
        (BtnScreen.Guide to BtnPress.Red) to BtnAction.RecordOptions,
        (BtnScreen.Guide to BtnPress.Green) to BtnAction.GroupsMenu,
        (BtnScreen.Guide to BtnPress.Yellow) to BtnAction.DayBack,
        (BtnScreen.Guide to BtnPress.Blue) to BtnAction.DayForward,
        (BtnScreen.Guide to BtnPress.Info) to BtnAction.Nothing,
        (BtnScreen.Live to BtnPress.BackHold) to BtnAction.GroupsMenu,
        (BtnScreen.Live to BtnPress.Record) to BtnAction.RecordNow,
        (BtnScreen.Live to BtnPress.Stop) to BtnAction.Stop,
        (BtnScreen.Live to BtnPress.Red) to BtnAction.RecordOptions,
        (BtnScreen.Live to BtnPress.Green) to BtnAction.ChannelList,
        (BtnScreen.Live to BtnPress.Yellow) to BtnAction.StartOver,
        (BtnScreen.Live to BtnPress.Blue) to BtnAction.Banner,
        (BtnScreen.Live to BtnPress.Info) to BtnAction.Banner,
        (BtnScreen.Film to BtnPress.Red) to BtnAction.Nothing,
        (BtnScreen.Film to BtnPress.Green) to BtnAction.AudioSubs,
        (BtnScreen.Film to BtnPress.Yellow) to BtnAction.Nothing,
        (BtnScreen.Film to BtnPress.Blue) to BtnAction.Details,
        (BtnScreen.Film to BtnPress.Info) to BtnAction.Details,
        (BtnScreen.Pages to BtnPress.BackHold) to BtnAction.MainMenu,
    )

    fun default(screen: BtnScreen, press: BtnPress): BtnAction = defaults[screen to press] ?: BtnAction.Nothing

    fun key(screen: BtnScreen, press: BtnPress) = "${screen.name}.${press.name}"

    /** What [press] does on [screen] with these settings. */
    fun action(s: RemoteSettings, screen: BtnScreen, press: BtnPress): BtnAction =
        s.buttonMap[key(screen, press)]?.let { n -> BtnAction.entries.firstOrNull { it.name == n } } ?: default(screen, press)

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
        }
        return when (io.github.petebutty.viewarr.ui.common.RemoteKeys.action(e)) {
            io.github.petebutty.viewarr.ui.common.RemoteAction.Record -> BtnPress.Record
            io.github.petebutty.viewarr.ui.common.RemoteAction.Info -> BtnPress.Info
            io.github.petebutty.viewarr.ui.common.RemoteAction.Stop -> BtnPress.Stop
            else -> null
        }
    }
}
