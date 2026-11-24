package io.github.petebutty.viewarr.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateMapOf
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.flow.first
import androidx.compose.ui.input.key.type
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.size
import io.github.petebutty.viewarr.playback.PlaybackState
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.player.MultiView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.dvr.DvrViewModel
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.settings.StartPage
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import io.github.petebutty.viewarr.data.dvr.DvrState
import io.github.petebutty.viewarr.data.dvr.Reminder
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.settings.BtnAction
import io.github.petebutty.viewarr.data.settings.BtnPress
import io.github.petebutty.viewarr.data.settings.BtnScreen
import io.github.petebutty.viewarr.data.settings.ButtonMap
import io.github.petebutty.viewarr.playback.MusicPlayer
import io.github.petebutty.viewarr.ui.common.RemoteAction
import io.github.petebutty.viewarr.ui.common.RemoteKeys
import io.github.petebutty.viewarr.ui.common.SearchKey
import io.github.petebutty.viewarr.ui.dvr.GuideMatch
import io.github.petebutty.viewarr.ui.guide.channelLogo
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.music.MusicViewModel
import io.github.petebutty.viewarr.ui.player.RowSets
import io.github.petebutty.viewarr.ui.settings.RemoteViewModel
import io.github.petebutty.viewarr.ui.settings.SportsViewModel
import io.github.petebutty.viewarr.ui.theme.UiOpts
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.ui.common.BackLayers
import io.github.petebutty.viewarr.ui.common.NewsTicker
import io.github.petebutty.viewarr.ui.common.Ticker
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.input.key.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * What the home screen keeps track of (menus, dialogs, full screen, holds…), in one place. HomeScreen() itself had
 * grown past the size Android's code checker accepts (a VerifyError on launch): one holder instead of ~60 remembered
 * values keeps it well under. The values are snapshot state, so reading them while composing still recomposes.
 */
@Stable
class HomeState(
    menuOpenState: MutableState<Boolean>,
    showAccountsState: MutableState<Boolean>,
    private val pageState: MutableState<Page>,
    startAppliedState: MutableState<Boolean>,
    private val fullscreenState: MutableState<Boolean>,
) {
    /** (The moved code still says hs.x, as it did inside HomeScreen.) */
    private val hs: HomeState get() = this

    // What HomeScreen hands over each time it's drawn (its view models, the remote's settings, its scopes…).
    lateinit var vm: GuideViewModel
    lateinit var playback: PlaybackViewModel
    lateinit var dvr: DvrViewModel
    var session: Session? = null
    var remote: RemoteSettings = RemoteSettings()
    lateinit var fromStartScope: CoroutineScope
    lateinit var ownScope: CoroutineScope
    var activity: Activity? = null
    lateinit var moviesVm: MediaViewModel
    lateinit var showsVm: MediaViewModel
    lateinit var musicVm: MusicViewModel
    lateinit var sportsVm: SportsViewModel
    lateinit var remoteVm: RemoteViewModel
    // Read fresh from the view models (HomeScreen's collected copies of the same flows).
    val showMenuRows get() = showsVm.menuRows.value
    val playing get() = playback.channel.value
    val catchup get() = playback.catchup.value
    val programs get() = vm.programs.value
    val now get() = vm.now.value
    // Kept here (were remembered in HomeScreen).
    /**
     * When the live controls were last used: a plain holder, not state. As state, every press inside the controls
     * rebuilt the whole home screen (guide, poster rows), which made them sluggish.
     */
    val osdUsedAt = longArrayOf(0L)
    val holdJob = arrayOfNulls<Job>(1)
    /** When the Back press being timed went down (the corner picture's hold). */
    private var backDownAt = 0L
    /** When the channel last changed by zap(): a held button changes one every ZAP_GAP_MS, not one per repeat. */
    private var lastZapAt = 0L
    /** Buttons that would open the controls, info bar or channel list: on a failed stream they try it again instead. */
    private val retryActions = setOf(BtnAction.Controls, BtnAction.Banner, BtnAction.ChannelList)
    val ownPrograms = mutableStateMapOf<Int, Pair<Long, List<GuideProgram>>>()
    val ownLoading = HashSet<Int>()
    /**
     * Two guide lookups at a time for [programsOf]: the info bar's rows ask for up to ~95 channels at once (Recent,
     * Favourites, On now), which all went off together and stalled a small box.
     */
    private val ownLimit = kotlinx.coroutines.sync.Semaphore(2)
    var menuOpen by menuOpenState
    var showAccounts by showAccountsState
    /** The page shown: Live TV's guide only for someone who has Live TV (Films otherwise). */
    var page: Page
        get() = pageState.value
        set(v) {
            pageState.value = if (v == Page.Tv && !Access.liveTv) Page.Movies else v
            io.github.petebutty.viewarr.ui.theme.Wallpaper.titleBehind = pageState.value == Page.Movies || pageState.value == Page.Shows
            io.github.petebutty.viewarr.ui.theme.Wallpaper.guideOnScreen = pageState.value == Page.Tv
        }
    /** Where Viewarr opens: a guide or channel start becomes Movies for someone without Live TV. */
    fun startFor(start: StartPage) =
        if (Access.liveTv) start
        else if (start == StartPage.Guide || start == StartPage.LastChannel) StartPage.Movies else start
    /** The start page from Settings, applied once when the app opens. */
    var startApplied by startAppliedState
    var fullscreen: Boolean
        get() = fullscreenState.value
        set(v) { fullscreenState.value = v; io.github.petebutty.viewarr.ui.common.LiveBehind.fullScreen = v }
    /** Record options for a programme (from the guide or the player's Record button). */
    var recordTarget by mutableStateOf<Pair<ChannelListItem, GuideProgram>?>(null)
    /** Hold OK in the guide → Guide times: the channel whose guide offset is being set. */
    var guideShiftFor by mutableStateOf<ChannelListItem?>(null)
    /** Hold OK in the guide → Timeline: choosing how many hours the guide shows. */
    var guideTimelineOpen by mutableStateOf(false)
    /** Seerr: choosing which series of a guide programme's show to request. */
    var seasonPick by mutableStateOf<GuideMatch?>(null)
    var recordingPlayback by mutableStateOf<VodStream?>(null)
    var infoTarget by mutableStateOf<Pair<ChannelListItem, GuideProgram>?>(null)
    /** Search popped up over the current screen (Settings → Remote → Search button); Back closes it. */
    var quickSearch by mutableStateOf(false)
    var searchHeld by mutableStateOf(false)
    /** A changed button from Settings → Remote buttons: the rest of its press (repeats, release) is swallowed. */
    var tableHeld by mutableStateOf<BtnPress?>(null)
    /** A button with a long press as well (Settings → Remote buttons): its tap waits for the release, its hold for a timer. */
    var holdTap by mutableStateOf<BtnPress?>(null)
    /** Buttons that open a page or leave the app (done in HomeLinkEffects, once those are in reach). */
    var buttonRequest by mutableStateOf<BtnAction?>(null)
    var dueReminder by mutableStateOf<Reminder?>(null)
    var dvrNote by mutableStateOf<String?>(null)
    var menuWidth by mutableIntStateOf(0)
    var menuFullPage by mutableStateOf(false)
    /** A channel number typed on the remote's number keys. */
    var digits by mutableStateOf("")
    private var channelListOpenState by mutableStateOf(false)
    /** The channel list over full-screen live TV. Opening it closes the info bar and the controls (2026-11-16). */
    var channelListOpen: Boolean
        get() = channelListOpenState
        set(v) {
            if (v && !channelListOpenState) { bannerVisible = false; osdVisible = false }
            channelListOpenState = v
        }
    /** Back held on the TV page: the rest of that press is swallowed (see holdBack). */
    var backLongPress by mutableStateOf(false)
    /** The timer that turns a Back press into a hold (see the root key handler). */
    var backHoldJob by mutableStateOf<Job?>(null)
    /** Hold Back with a picture in the corner (live TV or a film, over another page): closes it. */
    var pipBackHeld by mutableStateOf(false)
    var pipHoldJob by mutableStateOf<Job?>(null)
    var digitsToken by mutableLongStateOf(0L)
    var digitsMessage by mutableStateOf<String?>(null)
    /**
     * Movies or Shows opened from the menu over the guide: the channel carries on in
     * the guide's preview place, the page's content starting where the guide's did.
     */
    var livePreview by mutableStateOf(false)
    /** The Sports page (Sports in the menu): over whatever page is open, live TV staying in the top-left window. */
    var sportsOpen by mutableStateOf(false)
    var liveBehind by mutableStateOf(false)
    /** The film player's Settings button opened the menu (its film is in the corner meanwhile). */
    var settingsFromFilm by mutableStateOf(false)
    /**
     * Full-screen live TV: Left asks "Rewind?" first (a second Left, or holding it, rewinds), so a stray press doesn't
     * drop you out of live. When it was asked; 0: not asking.
     */
    var rewindAsk by mutableLongStateOf(0L)
    /** A hold that already rewound: the rest of that press is ignored (each repeat would rewind again). */
    var rewindHeld by mutableStateOf(false)
    var bannerToken by mutableLongStateOf(0L)
    var bannerVisible by mutableStateOf(false)
    /** A channel browsed to in the info bar, not changed to yet (browse()). */
    var browseChannel by mutableStateOf<ChannelListItem?>(null)
    var browseJob by mutableStateOf<Job?>(null)
    var osdVisible by mutableStateOf(false)
    /** Several channels on screen at once (from the player menu). */
    var multiView by mutableStateOf(false)
    /** "Watch from the start" from the guide: search opens on the programme's title. */
    var searchStart by mutableStateOf<String?>(null)
    /** Search opened from the guide's "Search catch-up": with the Catch-up pill picked. */
    var searchCatchup by mutableStateOf(false)
    var accountsFromMenu by mutableStateOf(false)
    var searchVoice by mutableStateOf(false)
    var menuOnSettings by mutableStateOf(false)
    /** The menu opens on Live TV's channel groups (the "Channel groups menu" button), not the main list. */
    var menuOnGroups by mutableStateOf(false)
    /** Sign out of everything: asked first. */
    var askSignOut by mutableStateOf(false)
    /** Live TV controls → Transparency: the three sliders over the picture. */
    var transparencyOpen by mutableStateOf(false)
    /** Held Back with nothing playing: the menu opens on its main list (not the page's own list). */
    var menuOnMain by mutableStateOf(false)
    /** A library (from My Library) on screen: Left opens the menu on My Library. */
    var menuOnLibrary by mutableStateOf(false)
    var osdAudio by mutableStateOf(false)
    var playingGroup by mutableStateOf<Int?>(null)
    var bannerHeld by mutableStateOf(false)
    var lastKeyAt by mutableLongStateOf(System.currentTimeMillis())
    var stillAsk by mutableStateOf(false)
    var exitArmed by mutableLongStateOf(0L)
    /**
     * The menu was opened by Back in the guide (TV guide → Back: Open the menu, as classic IPTV apps): Back out of it asks to
     * exit, and Back once more exits (it used to close the menu, and Back opened it again: no way out).
     */
    var menuFromGuideBack by mutableStateOf(false)
    /**
     * The menu was opened by a button from full-screen live TV (Settings → Remote buttons: the main menu, the channel
     * groups, Settings): Back out of it goes back to full screen, not the guide it left showing (menuClosed()).
     */
    var menuFromFullscreen by mutableStateOf(false)
    var preloadStage by mutableIntStateOf(0)
    var cornerFocused by mutableStateOf(false)

    /** A short message at the top right (replaces the one showing). */
    fun toast(text: String) { hs.digitsMessage = text; hs.digitsToken = System.nanoTime() }

    fun programsOf(ch: ChannelListItem): List<GuideProgram> {
        programs[ch.id]?.let { return it }
        val cached = ownPrograms[ch.id]
        // Refreshed every 10 minutes, so what's on now moves on.
        if ((cached == null || System.currentTimeMillis() - cached.first > 600_000) && ownLoading.add(ch.id)) ownScope.launch {
            // (Removed in finally: a lookup cancelled part-way left the channel marked as loading, never asked again.)
            try {
                val got = ownLimit.withPermit { runCatching { vm.programsFor(ch) }.getOrNull() }
                // Not found this time: known as "nothing" for now (the bar can say so), asked again in a minute.
                ownPrograms[ch.id] = if (got != null) System.currentTimeMillis() to got else (System.currentTimeMillis() - 540_000) to (cached?.second.orEmpty())
            } finally {
                ownLoading.remove(ch.id)
            }
        }
        return cached?.second.orEmpty()
    }

    /** Whether [ch]'s programmes have been looked up yet (until then the info bar waits instead of "No guide information"). */
    fun guideKnown(ch: ChannelListItem) = programs[ch.id] != null || ownPrograms.containsKey(ch.id)

    fun showBanner() {
        hs.bannerVisible = true
        hs.bannerToken = System.nanoTime()
    }

    /**
     * Onto [ch] full screen (the guide moves to it first): live, or [catchup] from catch-up. [endMultiView]: multi-view
     * closes; [keepIfPlaying]: no restart when it's the channel already playing. [full]: full screen (false: in the
     * guide's window, as reopening where you left it can ask).
     */
    fun watchChannel(ch: ChannelListItem, catchup: GuideProgram? = null, endMultiView: Boolean = false, keepIfPlaying: Boolean = false, s: Session? = session, full: Boolean = true) {
        if (!Access.liveTv) return
        vm.reveal(ch) {
            hs.page = Page.Tv
            when {
                catchup != null -> playback.playCatchup(ch, catchup, s)
                !keepIfPlaying || playing?.id != ch.id -> playback.play(ch, s)
            }
            vm.selectChannel(ch.id)
            hs.fullscreen = full
            if (endMultiView) hs.multiView = false
            if (full) showBanner()
        }
    }

    fun showOsd(audio: Boolean = false) {
        hs.osdAudio = audio
        hs.bannerVisible = false
        hs.osdVisible = true
        osdUsedAt[0] = System.currentTimeMillis()
    }

    /** Back to the channel watched before this one. */
    fun lastChannel() {
        val before = remote.recentChannels.firstOrNull { it != playing?.id }
        if (before == null) { toast("No channel watched before this one yet"); return }
        fromStartScope.launch {
            val ch = vm.channelById(before) ?: return@launch
            playback.play(ch, session)
            vm.selectChannel(ch.id)
            showBanner()
        }
    }

    /**
     * The channel [delta] along from [index] in a list of [size]. Past either end it follows Settings → Live TV → TV
     * guide → End of a list (as the guide and the channel list do): round to the other end, unless that says Stop.
     * (Down on the first channel used to do nothing, which looked as if Down didn't work.)
     */
    fun stepIndex(index: Int, delta: Int, size: Int): Int {
        if (size <= 0) return 0
        if (index < 0) return 0
        val next = index + delta
        val stop = AppColors.palette.opt(UiOpts.LIST_ENDS) == 1
        return when {
            next in 0 until size -> next
            stop -> next.coerceIn(0, size - 1)
            else -> ((next % size) + size) % size
        }
    }

    fun zap(delta: Int) {
        val list = vm.channels.value
        val index = list.indexOfFirst { it.id == playing?.id }
        val target = list.getOrNull(stepIndex(index, delta, list.size)) ?: return
        if (target.id == playing?.id) return
        // A held button (Up / Down, Channel + / −, or one set to change channel) repeats every 50 ms or so: one
        // channel every 0.4 s, not one per repeat.
        val t = System.currentTimeMillis()
        if (t - lastZapAt < ZAP_GAP_MS) return
        lastZapAt = t
        playback.play(target, session)
        vm.selectChannel(target.id)
        showBanner()
    }

    /**
     * Browsing channels in the info bar (Remote buttons: "Next channel in the info bar"): the bar shows the next or
     * previous channel and what's on it while the picture stays put; it changes once you rest on one for a moment
     * (Settings → Playback → Browsing channels), or straight away on OK. Back stays where you were.
     */
    fun commitBrowse() {
        hs.browseJob?.cancel()
        val target = hs.browseChannel ?: return
        hs.browseChannel = null
        if (target.id != playing?.id) { playback.play(target, session); vm.selectChannel(target.id) }
        showBanner()
    }

    fun cancelBrowse() { hs.browseJob?.cancel(); hs.browseChannel = null }

    fun browse(delta: Int) {
        val list = vm.channels.value
        if (list.isEmpty()) return
        // The first press with no info bar up: the channel you're watching in it first (what's on now and next); the
        // next press browses from there. (2026-11-17: the first press jumped straight to the next channel.)
        if (hs.browseChannel == null && !hs.bannerVisible) { showBanner(); return }
        val from = hs.browseChannel ?: playing
        val index = list.indexOfFirst { it.id == from?.id }
        val target = list.getOrNull(stepIndex(index, delta, list.size)) ?: return
        hs.browseChannel = target.takeIf { it.id != playing?.id }
        showBanner()
        prefetchAround(list, list.indexOf(target), delta)
        hs.browseJob?.cancel()
        if (hs.browseChannel == null) return
        // (Up / Down set to "Browse, OK changes": only OK changes.)
        val wait = (if (AppColors.palette.opt(UiOpts.LIVE_UPDOWN) == 1) null else AppColors.palette.browseDelayMs) ?: return
        hs.browseJob = fromStartScope.launch { delay(wait); commitBrowse() }
    }

    /**
     * The next couple of channels in the direction you're browsing: their programmes and logos fetched now, so the
     * info bar has them the moment you land there (it used to show the channel bare for a frame, then fill in).
     */
    private fun prefetchAround(list: List<ChannelListItem>, at: Int, delta: Int) {
        val dir = if (delta >= 0) 1 else -1
        val ctx = activity ?: return
        val server = session?.serverUrl.orEmpty()
        for (k in 1..2) {
            val ch = list.getOrNull(stepIndex(at, dir * k, list.size)) ?: continue
            programsOf(ch)
            channelLogo(server, ch)?.let { url ->
                runCatching { coil.Coil.imageLoader(ctx).enqueue(coil.request.ImageRequest.Builder(ctx).data(url).build()) }
            }
        }
    }

    /** Up / Down and Channel + / − while watching (Settings → Live TV → Up / Down while watching): change, or browse. */
    fun upDown(delta: Int) { if (AppColors.palette.opt(UiOpts.LIVE_UPDOWN) != 0) browse(delta) else zap(delta) }

    /**
     * OK on a programme in the guide (and Watch / Watch in the preview from a changed button there): a recording plays
     * the recording; on now, the channel; aired on a catch-up channel, its catch-up straight away (never a library
     * search first); still to come, its details.
     */
    fun pickInGuide(channel: ChannelListItem, program: GuideProgram?, inGuide: Boolean) {
        // Recorded on Dispatcharr (the guide shows it as recorded): OK plays the recording.
        val rec = program?.let { dvr.recordingFor(channel.id, it) }
        if (rec != null && rec.state == DvrState.Recorded && rec.hasFile) {
            fromStartScope.launch { dvr.playback(rec)?.let { hs.recordingPlayback = it } ?: hs.toast("That recording couldn't be played") }
            return
        }
        val t = System.currentTimeMillis()
        val live = program == null || (program.startTime <= t && program.endTime > t)
        val replayable = program != null && program.endTime <= t && channel.catchupDays > 0 &&
            program.startTime >= t - channel.catchupDays * 86_400_000L
        when {
            live -> {
                val alreadyLive = playing?.id == channel.id && catchup == null && playback.state.value !is PlaybackState.Error
                if (!alreadyLive) playback.play(channel, session)
                if (!inGuide || alreadyLive) { hs.fullscreen = true; showBanner() }
            }
            replayable -> {
                val already = catchup?.rowId == program!!.rowId && playing?.id == channel.id
                if (!already) playback.playCatchup(channel, program, session)
                if (!inGuide || already) { hs.fullscreen = true; showBanner() }
            }
            // A programme still to come: show its details (Record options from there).
            program != null && program.startTime > t ->
                // Already set to record: straight to cancelling it. Otherwise the details, with record / remind.
                if (dvr.recordingFor(channel.id, program) != null) hs.recordTarget = channel to program
                else hs.infoTarget = channel to program
            // Aired, with no catch-up for it: its details, rather than nothing happening.
            program != null -> hs.infoTarget = channel to program
        }
    }

    /**
     * A press from the button table (Settings → Remote → Buttons) on the guide, live TV or a page. [fresh]: a press,
     * not a repeat of a held button (a page up / down then never jumps round the end of the list).
     */
    fun doButton(screen: BtnScreen, act: BtnAction, fresh: Boolean = true) {
        val ch = playing
        // The stream failed ("OK: try again" over the picture): the buttons that would open its controls, info bar or
        // channel list try it again instead. (OK on live TV comes through here, so the player's own retry never ran.)
        if (ch != null && act in retryActions && playback.state.value is PlaybackState.Error) { playback.retry(); return }
        when (act) {
            BtnAction.Nothing, BtnAction.TimeTitle, BtnAction.SeekBar, BtnAction.Episodes, BtnAction.MediaInfo, BtnAction.SwitchPlayer -> Unit
            BtnAction.Movies, BtnAction.Shows, BtnAction.Recordings, BtnAction.Music, BtnAction.Exit -> hs.buttonRequest = act
            BtnAction.Settings -> { leaveForMenu(screen); hs.menuOnSettings = true; hs.menuOpen = true }
            // In the guide: the highlighted programme, as OK does (an aired one on a catch-up channel plays its catch-up).
            BtnAction.WatchInGuide, BtnAction.Watch -> vm.channels.value.getOrNull(vm.cursor.value.row)?.let { c ->
                if (screen == BtnScreen.Guide) { pickInGuide(c, GuideViewModel.programAt(programsOf(c), vm.cursor.value.focusTime), inGuide = act == BtnAction.WatchInGuide); vm.selectChannel(c.id) }
                else if (act == BtnAction.WatchInGuide && playing?.id == c.id && catchup == null) { hs.fullscreen = true; showBanner() }
                else { playback.play(c, session); vm.selectChannel(c.id); if (act == BtnAction.Watch) { hs.fullscreen = true; showBanner() } }
            }
            BtnAction.GoLive -> if (catchup != null) { playback.goLive(); showBanner() }
            BtnAction.Description -> {
                val target = if (screen == BtnScreen.Live) ch?.let { c -> GuideViewModel.programAt(programsOf(c), now)?.let { c to it } }
                else vm.cursor.value.let { cur -> vm.channels.value.getOrNull(cur.row)?.let { c -> GuideViewModel.programAt(programsOf(c), cur.focusTime)?.let { c to it } } }
                if (target != null) { hs.osdVisible = false; hs.channelListOpen = false; hs.infoTarget = target }
            }
            BtnAction.MultiView -> if (ch != null && AppColors.palette.heavyExtras) {
                hs.osdVisible = false; hs.channelListOpen = false
                if (catchup != null) playback.goLive()
                hs.fullscreen = true; hs.multiView = true
            }
            BtnAction.Transparency -> { hs.osdVisible = false; hs.channelListOpen = false; hs.transparencyOpen = true }
            BtnAction.PageUp, BtnAction.PageDown -> vm.moveVertical(if (act == BtnAction.PageUp) -7 else 7, if (AppColors.palette.opt(UiOpts.LIST_ENDS) == 0) 1 else 2, fresh)
            BtnAction.Earlier -> vm.moveLeft()
            BtnAction.Later -> vm.moveRight()
            BtnAction.Controls -> if (ch != null) showOsd()
            // (Settings → Live TV → Up / Down while watching set to browse: buttons set to change channel browse too.)
            BtnAction.NextChannel -> upDown(1)
            BtnAction.PrevChannel -> upDown(-1)
            BtnAction.BrowseNext -> browse(1)
            BtnAction.BrowsePrev -> browse(-1)
            BtnAction.LastChannel -> lastChannel()
            BtnAction.SkipBack, BtnAction.SkipForward -> if (ch != null || catchup != null) {
                playback.seekBy(if (act == BtnAction.SkipBack) -remote.seek.ms else remote.seek.ms)
                showBanner()
            }
            BtnAction.PlayPause -> if (ch != null || catchup != null) { playback.togglePause(); showBanner() }
            BtnAction.GroupsMenu -> { leaveForMenu(screen); hs.menuOnMain = false; hs.menuOnGroups = true; hs.menuOpen = true }
            BtnAction.MainMenu -> { leaveForMenu(screen); hs.menuOnMain = true; hs.menuOpen = true }
            BtnAction.FullScreen -> if (ch != null) { hs.fullscreen = true; showBanner() }
            BtnAction.Guide -> if (screen == BtnScreen.Pages) hs.buttonRequest = act else { hs.fullscreen = false; hs.channelListOpen = false }
            BtnAction.Now -> vm.goToNow()
            BtnAction.DayBack -> vm.jumpBy(-86_400_000L)
            BtnAction.DayForward -> vm.jumpBy(86_400_000L)
            BtnAction.RecordOptions, BtnAction.RecordNow -> {
                // Live TV: what's on now; the guide: the highlighted programme (as holding OK there).
                val target = if (screen == BtnScreen.Live) ch?.let { c -> GuideViewModel.programAt(programsOf(c), now)?.let { c to it } }
                else {
                    val cur = vm.cursor.value
                    vm.channels.value.getOrNull(cur.row)?.let { c -> GuideViewModel.programAt(programsOf(c), cur.focusTime)?.let { c to it } }
                }
                if (target != null && catchup == null) {
                    val (tc, tp) = target
                    val straight = act == BtnAction.RecordNow && tp.endTime > now && dvr.recordingFor(tc.id, tp) == null
                    when {
                        // One press: scheduled, with a short note. (Already set: the options, so it can be cancelled.)
                        straight && Access.record -> {
                            dvr.record(tc, tp)
                            toast((if (tp.startTime <= now) "⏺ Recording from now: " else "⏺ Recording set: ") + tp.title)
                        }
                        straight -> { toast("Recording isn't allowed for this profile") }
                        else -> { hs.osdVisible = false; hs.channelListOpen = false; hs.recordTarget = target }
                    }
                }
            }
            BtnAction.ChannelList -> if (hs.fullscreen) hs.channelListOpen = true
            // The guide: the highlighted programme from its start (catch-up, or on now).
            BtnAction.StartOver -> if (screen == BtnScreen.Guide) {
                val cur = vm.cursor.value
                vm.channels.value.getOrNull(cur.row)?.takeIf { it.catchupDays > 0 }?.let { c ->
                    GuideViewModel.programAt(programsOf(c), cur.focusTime)?.takeIf { it.startTime < now }?.let { prog ->
                        playback.playCatchup(c, prog, session); vm.selectChannel(c.id); hs.fullscreen = true; showBanner()
                    }
                }
            } else if (ch != null && playback.canRewind(ch)) {
                GuideViewModel.programAt(programsOf(ch), now)?.takeIf { it.startTime < now }?.let { prog ->
                    playback.playCatchup(ch, prog, session)
                    vm.selectChannel(ch.id)
                    showBanner()
                }
            }
            BtnAction.Banner -> showBanner()
            BtnAction.AudioSubs -> showOsd(audio = true)
            BtnAction.Search -> { hs.channelListOpen = false; hs.searchStart = null; hs.quickSearch = true }
            // Live TV stops (catch-up too): the guide with nothing playing, where Back asks to exit.
            BtnAction.Stop -> if (ch != null || catchup != null) {
                playback.stop()
                hs.osdVisible = false
                hs.channelListOpen = false
                hs.multiView = false
                hs.fullscreen = false
                hs.bannerVisible = false
            }
            // Films only (VodKeys).
            BtnAction.Details -> Unit
        }
    }

    /** A button opens the menu: full screen (and the channel list) close, remembering to come back (menuClosed()). */
    private fun leaveForMenu(screen: BtnScreen) {
        hs.menuFromFullscreen = screen == BtnScreen.Live && hs.fullscreen && playing != null
        hs.fullscreen = false
        hs.channelListOpen = false
    }

    /**
     * The menu closed by Back (MenuOverlay's onClose). Opened by a button from full-screen live TV, that's where Back
     * goes back to (it used to leave the guide showing). Wired in HomeScreen's MenuOverlay(onClose = ...).
     */
    fun menuClosed() {
        hs.menuOpen = false; hs.menuOnSettings = false; hs.menuOnMain = false
        val back = hs.menuFromFullscreen
        hs.menuFromFullscreen = false
        if (back && playing != null && hs.page == Page.Tv && !hs.livePreview && !hs.liveBehind) { hs.fullscreen = true; showBanner() }
    }

    /** Whether [act] has anything to act on now (a hold that can't act stays an ordinary press). */
    private fun canAct(act: BtnAction): Boolean = when (act) {
        BtnAction.Nothing -> false
        BtnAction.FullScreen, BtnAction.Controls, BtnAction.MultiView -> playing != null
        BtnAction.ChannelList -> playing != null && hs.fullscreen
        BtnAction.GoLive -> catchup != null
        BtnAction.Stop, BtnAction.PlayPause, BtnAction.SkipBack, BtnAction.SkipForward -> playing != null || catchup != null
        else -> true
    }

    /**
     * Back held (timed in the root key handler): what Settings → Remote buttons → Hold Back says for the screen (the
     * guide's channel groups, on a page the menu…), else with a channel playing it stops. False when a hold means
     * nothing here (it's then an ordinary Back, or left to whoever else uses one: music, a film).
     */
    fun holdBack(): Boolean {
        if (hs.menuOpen || hs.showAccounts || hs.quickSearch || hs.recordTarget != null || hs.infoTarget != null || hs.transparencyOpen) return false
        if (VodHost.active) return false
        val music = MusicPlayer
        if (music.active && !music.nowPlayingOpen) return false
        val screen = when {
            hs.page == Page.Tv && hs.fullscreen -> if (hs.osdVisible || hs.channelListOpen || hs.multiView) return false else BtnScreen.Live
            hs.page == Page.Tv -> BtnScreen.Guide
            playing == null -> BtnScreen.Pages
            // The channel in the preview place over another page, or full screen behind it: hold Back closes it too.
            hs.livePreview -> { hs.livePreview = false; playback.stop(); return true }
            hs.liveBehind -> { hs.liveBehind = false; playback.stop(); return true }
            else -> return false
        }
        // The button table first (it used to stop the channel before looking, so the Classic preset's "Back to full
        // screen" for Hold Back in the guide never ran).
        val act = ButtonMap.action(remote, screen, BtnPress.BackHold)
        // Hold Back with a channel playing (full screen, or in the guide's window) and nothing else set for it (or
        // full screen asked for when it's that already): it stops (since 0.28.3, as for a film: Back shrinks /
        // enlarges it, hold Back closes it).
        val watching = playing != null && (screen == BtnScreen.Live || screen == BtnScreen.Guide)
        if (watching && (act == BtnAction.Nothing || (act == BtnAction.FullScreen && hs.fullscreen))) {
            playback.stop(); hs.fullscreen = false; hs.osdVisible = false
            return true
        }
        // (The guide always has a way to the menu now: a tap of Left on what's on now.)
        // Set to "Nothing", or to something with nothing to act on (full screen with no channel playing): the press is
        // an ordinary Back after all (a slow tap used to do nothing at all).
        if (!canAct(act)) return false
        doButton(screen, act)
        return true
    }

    /** Which screen of Settings → Remote buttons a press is on now (null: none, e.g. a menu or dialog is open). */
    fun tableScreen(): BtnScreen? {
        if (hs.menuOpen || hs.showAccounts || hs.quickSearch || hs.recordTarget != null || hs.infoTarget != null || VodHost.covering || hs.transparencyOpen ||
            hs.seasonPick != null || (hs.sportsOpen && !hs.fullscreen)) return null
        return when {
            hs.page == Page.Tv -> if (hs.fullscreen) BtnScreen.Live else BtnScreen.Guide
            hs.page != Page.Search && !MusicPlayer.nowPlayingOpen -> BtnScreen.Pages
            else -> null
        }
    }

    /** Back to the channel watched last, full screen. False when there's none to go back to. */
    fun goLive(): Boolean {
        val s = session ?: return false
        val id = playing?.id ?: remote.lastChannelId ?: return false
        hs.menuOpen = false
        hs.quickSearch = false
        fromStartScope.launch {
            val ch = vm.channelById(id) ?: return@launch
            watchChannel(ch, keepIfPlaying = true, s = s)
        }
        return true
    }

    fun backOnPage() {
        if (backToVideo()) return
        if (hs.exitArmed != 0L) activity?.finish() else hs.exitArmed = System.currentTimeMillis()
    }

    /** Back on a page goes to the video it came from first (true: it did). */
    private fun backToVideo(): Boolean {
        // A film in the corner (opened over live TV): Back takes it full screen again (as live TV does).
        if (VodHost.active && VodHost.mini) { VodHost.expand(); return true }
        // A library over live TV: Back is the channel full screen again.
        if (hs.liveBehind && playing != null) { hs.liveBehind = false; hs.page = Page.Tv; hs.fullscreen = true; showBanner(); return true }
        // The channel in the guide's preview place: Back is the channel full screen (as everywhere: Back maximises).
        if (hs.livePreview && playing != null && hs.page != Page.Tv) { hs.livePreview = false; hs.page = Page.Tv; hs.fullscreen = true; showBanner(); return true }
        return false
    }

    /**
     * Back on Music, Recordings or Search (once the page itself has nothing to step back from): the menu, on the main
     * list, in one press. (It used to ask to exit, and Left opened the page's own list first, so reaching the main
     * menu took two presses.) Back in the menu then closes it and asks to exit; Back again exits.
     */
    fun backFromPage() {
        if (backToVideo()) return
        if (hs.exitArmed != 0L) { activity?.finish(); return }
        hs.menuFromFullscreen = false
        hs.menuOnMain = true; hs.menuFromGuideBack = true; hs.menuOpen = true
    }

    fun tuneTyped() {
        val n = hs.digits.toIntOrNull() ?: return
        hs.digits = ""
        vm.findByNumber(n) { ch ->
            if (ch == null) {
                toast("No channel $n")
            } else {
                playback.play(ch, session)
                vm.selectChannel(ch.id)
                hs.fullscreen = true
                hs.multiView = false
                showBanner()
            }
        }
    }

    /**
     * [keepLive]: leaving live TV for this page leaves the channel playing in a corner (a page opened from the
     * live controls or the menu), unless that's switched off or the box is short of memory.
     */
    fun openPage(p: Page, keepLive: Boolean = false, closeMenu: Boolean = true) {
        hs.sportsOpen = false
        // From the menu over a full-screen film: the film carries on in the corner, the page opens.
        if (VodHost.behind) { VodHost.behind = false; VodHost.minimise() }
        // Search never replaces what's on screen: it slides over it, and Back returns you there.
        if (p == Page.Search) {
            hs.menuOpen = false
            hs.quickSearch = true
            return
        }
        // Over live TV already: another of Movies / Shows keeps it behind; anything else stops it (the TV guide: full screen).
        if (hs.liveBehind && p != Page.Movies && p != Page.Shows) {
            hs.liveBehind = false
            // (Opened from the menu, Live TV is the guide, with the channel in its window; a hover keeps it full screen.)
            if (p == Page.Tv) { if (closeMenu) hs.fullscreen = false else { hs.fullscreen = true; showBanner() } } else playback.stop()
        }
        // Live TV from the menu over full-screen live TV (the channel list's menu): the guide, as from anywhere else.
        if (p == Page.Tv && closeMenu && hs.page == Page.Tv && hs.fullscreen) { hs.fullscreen = false; hs.channelListOpen = false; hs.osdVisible = false }
        // From Movies / Shows with the channel in the preview place: the guide keeps it; anything else stops it.
        if (hs.livePreview && p == Page.Tv) hs.livePreview = false
        if (p != Page.Tv && hs.page == Page.Tv) {
            val pal = AppColors.palette
            // From the menu over full-screen live TV, a library or catalogue: the channel stays full screen behind it.
            if (!keepLive && hs.fullscreen && playing != null && !hs.multiView && (p == Page.Movies || p == Page.Shows) && !pal.lowMemory) {
                hs.liveBehind = true
                hs.bannerVisible = false
                hs.osdVisible = false
                hs.channelListOpen = false
            } else if ((keepLive || !hs.fullscreen) && playing != null && !hs.multiView && p != Page.Search && !pal.lowMemory) {
                // From the guide: the channel stays where the guide's preview was.
                hs.livePreview = true
                hs.bannerVisible = false
                hs.osdVisible = false
            } else playback.stop()
            hs.fullscreen = false
        }
        hs.page = p
        if (closeMenu) hs.menuOpen = false
    }

    fun openService(name: String, closeMenu: Boolean, rowId: String? = null, keepLive: Boolean = false) {
        val key = "name:$name"
        val type = if (showMenuRows.rows.any { RowSets.matches(key, it) }) MediaType.Show else MediaType.Movie
        val mvm = if (type == MediaType.Show) showsVm else moviesVm
        if (mvm.currentRowSet.value != key) { mvm.openLibrary(null, type); mvm.jumpTo(null) }
        // A title page open on that page (e.g. the one whose film is in the mini player) would cover the service's rows:
        // close it (the film carries on; the menu's other entries change page instead, so they were fine).
        mvm.closeTitlePages()
        // Its rows from the other page follow, in that page's order without the ones hidden (the menu's rows list
        // can change both, so this is redone each time).
        val (other, otherHidden) = (if (type == MediaType.Show) moviesVm else showsVm).orderedRows.value
        val extra = other.filter { RowSets.matches(key, it) && it.id !in otherHidden }
            .map { it.copy(title = it.title + if (type == MediaType.Show) " (films)" else " (shows)") }
        mvm.showRowSet(key, extra)
        // A row picked in the menu (Popular, New…): the page opens on it.
        rowId?.let { mvm.jumpWithin(it) }
        hs.openPage(if (type == MediaType.Show) Page.Shows else Page.Movies, keepLive = keepLive, closeMenu = closeMenu)
    }

    /**
     * The menu's My Library, highlighted (or its "My Library home"): your own servers' rows, films and shows, on the
     * Movies page (MyLibrary). It used to leave the TV guide behind. (2026-11-17.)
     */
    fun openMyLibrary(closeMenu: Boolean, keepLive: Boolean = false) {
        val mine = io.github.petebutty.viewarr.ui.media.MyLibrary.SET
        if (moviesVm.currentRowSet.value != mine) { moviesVm.openLibrary(null, MediaType.Movie); moviesVm.jumpTo(null) }
        moviesVm.closeTitlePages()
        // (Both pages' rows feed it: Shows may not have been opened yet.)
        moviesVm.load(MediaType.Movie); showsVm.load(MediaType.Show)
        moviesVm.showRowSet(mine)
        hs.openPage(Page.Movies, keepLive = keepLive, closeMenu = closeMenu)
    }

    /** The Sports page's ＋ Follow a team: Settings → Sports, over the menu's settings. */
    fun openFollowTeam() {
        io.github.petebutty.viewarr.ui.settings.SettingsJump.sports = true
        hs.menuFromFullscreen = false
        hs.menuOnSettings = true
        hs.menuOpen = true
    }

    /**
     * The menu's Sports: its own page of game panels (live TV carries on in the top-left window). Moving onto it shows
     * it behind the menu; Right / OK goes into it. (Live now is a group in the TV guide now, not a menu entry.)
     */
    /**
     * A main-menu entry after Live TV: Sports (its page), or Trending / What's new (Discover's pages, films and shows
     * together on the Movies page, like My Library). [into] false: shown behind the menu.
     */
    fun openLiveEntry(id: String, into: Boolean, keepLive: Boolean) {
        val set = io.github.petebutty.viewarr.ui.media.Discover.setOf(id) ?: return openSportsPage(closeMenu = into)
        hs.sportsOpen = false
        if (moviesVm.currentRowSet.value != set) { moviesVm.openLibrary(null, MediaType.Movie); moviesVm.jumpTo(null) }
        moviesVm.closeTitlePages()
        moviesVm.load(MediaType.Movie); showsVm.load(MediaType.Show)
        moviesVm.showRowSet(set)
        hs.openPage(Page.Movies, keepLive = keepLive, closeMenu = into)
    }

    fun openSportsPage(closeMenu: Boolean) {
        if (hs.fullscreen) { hs.fullscreen = false; hs.osdVisible = false }
        hs.sportsOpen = true
        if (closeMenu) hs.menuOpen = false
    }

    /** The screen's first look at every button press (was HomeScreen's root onPreviewKeyEvent). True: handled. */
    fun onRootKey(e: KeyEvent): Boolean = run root@{
        hs.lastKeyAt = System.currentTimeMillis()
        // A fresh Back press is a new press: whatever the last one left set to swallow its rest goes. Its release can be
        // dropped (as for searchHeld / tableHeld below) or land in another window (a pop-up, a dialog), and the flag then
        // ate the next Back.
        if (e.key == Key.Back && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
            hs.pipBackHeld = false; hs.backLongPress = false
            VodHost.swallowBack = false; VodHost.eatBackRelease = false; MusicPlayer.eatBackRelease = false
        }
        // "Still watching?" up: any button answers yes.
        if (hs.stillAsk) { if (e.type == KeyEventType.KeyUp) hs.stillAsk = false; return@root true }
        // A reminder on screen: OK switches to it, Back dismisses it; other buttons work as usual.
        hs.dueReminder?.let { r ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (ok || e.key == Key.Back) {
                // (Not the release of an OK held before the reminder appeared.)
                if (e.type == KeyEventType.KeyUp && !io.github.petebutty.viewarr.ui.common.HeldOk.isHeldRelease(e)) {
                    hs.dueReminder = null
                    if (ok) dvr.channels.value[r.channelId]?.let { ch ->
                        hs.quickSearch = false
                        hs.menuOpen = false
                        hs.watchChannel(ch, endMultiView = true)
                    }
                }
                return@root true
            }
        }
        // A card from a report (the plugin changed a channel; a guide to pick): OK acts on it, Back dismisses it.
        if (io.github.petebutty.viewarr.ui.report.HouseNotice.onKey(e)) return@root true
        // The rest of a press whose table action left the film (search, the guide, the menu…): not for the page now open.
        VodHost.eatKey?.let { k ->
            if (e.nativeKeyEvent.keyCode != k || (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0)) VodHost.eatKey = null
            else { if (e.type == KeyEventType.KeyUp) VodHost.eatKey = null; return@root true }
        }
        // Hold Back on a full-screen film or show stops it, whatever is open over it (Audio sync, TV settings, Transparency
        // took the press before the player saw it, so the hold only closed the panel).
        if (e.key == Key.Back && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 && !VodHost.swallowBack &&
            VodHost.active && !VodHost.mini && !VodHost.gliding && !VodHost.behind) {
            VodHost.stopHeld()
            return@root true
        }
        // The rest of a held Back that already sent the film to the corner: nothing else (VodKeys).
        if (e.key == Key.Back && VodHost.swallowBack) {
            if (e.type == KeyEventType.KeyUp) VodHost.swallowBack = false
            return@root true
        }
        // Hold Back with a picture in the corner (a film, or live TV over another page): its menu, Full screen /
        // Close picture (before the guide's own hold-Back). A hold is timed here too (some remotes send no
        // repeats); a tap is an ordinary Back.
        val cornerUp = VodHost.active && VodHost.mini
        // A picture in the corner (a film, or live TV over another page): Back brings it full screen, hold Back closes
        // it, whatever page is open (as on the players themselves: Back shrinks, hold Back stops). The menu, search
        // and dialogs over the page take their own Back first.
        if (e.key == Key.Back && (hs.pipBackHeld || (cornerUp && !hs.showAccounts && !hs.menuOpen && !hs.quickSearch &&
                hs.recordTarget == null && hs.infoTarget == null && hs.seasonPick == null &&
                BackLayers.open == 0))) {
            fun closeCorner() {
                hs.pipHoldJob?.cancel(); hs.pipHoldJob = null
                hs.pipBackHeld = true
                if (VodHost.active) VodHost.close() else playback.stop()
            }
            when {
                e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && !hs.pipBackHeld -> {
                    hs.pipHoldJob?.cancel()
                    backDownAt = System.currentTimeMillis()
                    hs.pipHoldJob = fromStartScope.launch { delay(BACK_HOLD_MS); hs.pipHoldJob = null; closeCorner() }
                    return@root true
                }
                // (The remote's repeats only count once it's really a hold: some start before half a second.)
                e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 && !hs.pipBackHeld -> {
                    if (System.currentTimeMillis() - backDownAt >= BACK_HOLD_MS || heldFor(e) >= BACK_HOLD_MS) closeCorner()
                    return@root true
                }
                e.type == KeyEventType.KeyDown && hs.pipBackHeld -> return@root true
                // The release of the hold: that press is over (not a Back as well).
                e.type == KeyEventType.KeyUp && hs.pipBackHeld -> { hs.pipBackHeld = false; VodHost.eatBackRelease = false; return@root true }
                // A tap: full screen.
                e.type == KeyEventType.KeyUp -> {
                    hs.pipHoldJob?.cancel(); hs.pipHoldJob = null
                    if (VodHost.active && VodHost.mini) VodHost.expand() else goLive()
                    return@root true
                }
            }
        }
        // The film in the corner: the remote's Play / Pause and Stop work it, whatever page is open.
        if (VodHost.active && VodHost.mini && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
            when (RemoteKeys.action(e)) {
                RemoteAction.PlayPause -> { VodHost.remote?.togglePause(); return@root true }
                RemoteAction.Stop -> { VodHost.close(); return@root true }
                else -> Unit
            }
        }
        // Music playing in the bar: hold Back for Now Playing; the remote's media buttons control it.
        val music = MusicPlayer
        // The rest of the held Back that opened Now Playing (before that screen has the remote).
        if (e.key == Key.Back && music.eatBackRelease) {
            if (e.type == KeyEventType.KeyUp) music.eatBackRelease = false
            return@root true
        }
        if (music.active && !music.nowPlayingOpen && !VodHost.active && !hs.backLongPress) {
            if (e.key == Key.Back && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) {
                if (e.nativeKeyEvent.repeatCount == 1) { hs.backHoldJob?.cancel(); hs.backHoldJob = null; music.eatBackRelease = true; music.nowPlayingOpen = true }
                return@root true
            }
            if (e.type == KeyEventType.KeyDown) {
                // (Once per press: holding Play / pause flipped it back and forth.)
                val first = e.nativeKeyEvent.repeatCount == 0
                when (RemoteKeys.action(e)) {
                    RemoteAction.PlayPause -> { if (first) music.toggle(); return@root true }
                    RemoteAction.Stop -> { if (first) music.stop(); return@root true }
                    else -> Unit
                }
                when (e.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { music.next(); return@root true }
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { music.previous(); return@root true }
                }
            }
        }
        // The search button (Settings → Remote): pops search up over whatever's on screen.
        // (A new press ends it: after a hold the release can go astray, to a pop-up's own window, and the next button
        // pressed was swallowed.)
        if (hs.searchHeld && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) hs.searchHeld = false
        if (hs.searchHeld) {
            if (e.type == KeyEventType.KeyUp) hs.searchHeld = false
            return@root true
        }
        // Long Menu (Settings → Remote buttons), when it isn't search there.
        if (e.type == KeyEventType.KeyDown && e.key == Key.Menu && e.nativeKeyEvent.repeatCount == 1 && !hs.quickSearch) {
            hs.tableScreen()?.let { sc -> ButtonMap.changed(remote, sc, BtnPress.MenuHold) }?.let { a ->
                hs.tableHeld = null
                hs.searchHeld = true // swallow the rest of this press
                hs.doButton(hs.tableScreen()!!, a)
                return@root true
            }
        }
        // (Watching a film or show too: it goes to the corner and carries on while you search.)
        if (!hs.quickSearch && !hs.showAccounts && hs.page != Page.Search && e.type == KeyEventType.KeyDown) {
            val hit = SearchKey.hit(e, remote)
            if (hit) {
                VodHost.minimise()
                hs.menuOpen = false
                hs.channelListOpen = false
                hs.searchStart = null
                hs.quickSearch = true
                hs.searchHeld = true // swallow the rest of this press
                hs.tableHeld = null
                return@root true
            }
        }
        // Settings → Remote buttons: the rest of a changed button's press.
        // (A new press of it is a new press: the last one's release can have gone astray after a hold, to a pop-up's
        // own window, which left this waiting and swallowed the next OK.)
        if (hs.tableHeld != null && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
            hs.tableHeld = null
            if (hs.holdTap != null) { holdJob[0]?.cancel(); hs.holdTap = null }
        }
        hs.tableHeld?.let { held ->
            if (ButtonMap.pressOf(e) == held) {
                // Held: channels and skips keep going, as their own buttons do (a channel change paces itself: zap()).
                if (e.type == KeyEventType.KeyDown && hs.holdTap == null) hs.tableScreen()?.let { sc -> ButtonMap.changed(remote, sc, held) }
                    ?.takeIf { it in setOf(BtnAction.NextChannel, BtnAction.PrevChannel, BtnAction.BrowseNext, BtnAction.BrowsePrev, BtnAction.SkipBack, BtnAction.SkipForward, BtnAction.PageUp, BtnAction.PageDown, BtnAction.Earlier, BtnAction.Later) }
                    ?.let { hs.doButton(hs.tableScreen()!!, it, fresh = false) }
                if (e.type == KeyEventType.KeyUp) {
                    hs.tableHeld = null
                    // Let go before the long press: the tap (its usual job too, as it was held back).
                    if (hs.holdTap == held) {
                        holdJob[0]?.cancel()
                        hs.holdTap = null
                        hs.tableScreen()?.let { sc -> hs.doButton(sc, ButtonMap.action(remote, sc, held)) }
                    }
                    // Menu acts when it's let go (holding it is search, above).
                    if (held == BtnPress.Menu) hs.tableScreen()?.let { sc -> ButtonMap.changed(remote, sc, held)?.let { hs.doButton(sc, it) } }
                }
                return@root true
            }
            hs.tableHeld = null
        }
        // Browsing channels in the info bar: OK changes to the one shown now, Back stays where you were.
        if (hs.browseChannel != null && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
            when (e.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { hs.commitBrowse(); hs.searchHeld = true; return@root true }
                Key.Back -> { hs.cancelBrowse(); hs.showBanner(); hs.searchHeld = true; return@root true }
                else -> Unit
            }
        }
        // Settings → Remote buttons on the guide, full-screen live TV and the pages: buttons changed from standard
        // (colours, Record, Info, the media and channel buttons, Menu). OK, Back and their holds are taken
        // where they're handled (the guide's onSelect / onLongSelect, the Back handlers, holdBack()).
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
            val press = ButtonMap.pressOf(e)
            val screen = hs.tableScreen()
            // (Stop works with the controls, the channel list or multi-view open too; the channel and play buttons with
            // the controls or the channel list open: they did nothing there.)
            val free = !hs.channelListOpen && !hs.osdVisible && !(hs.fullscreen && hs.multiView)
            val alwaysOn = press == BtnPress.Stop || (!(hs.fullscreen && hs.multiView) && press in setOf(
                BtnPress.ChannelUp, BtnPress.ChannelDown, BtnPress.PlayPause, BtnPress.Rewind, BtnPress.FastForward))
            // (OK in the guide is never taken here: the guide's own handler times tap and hold, and does a changed OK and
            // Long OK itself. Taking it here when OK had been changed fired OK at once and swallowed the hold, so holding
            // OK in the guide just tuned the channel and the record options never opened.)
            val guideOk = screen == BtnScreen.Guide && press == BtnPress.Ok
            if (press != null && screen != null && !guideOk && press !in setOf(BtnPress.OkHold, BtnPress.Back, BtnPress.BackHold) &&
                (free || alwaysOn)
            ) {
                // With a long press too: wait to see which (OK in the guide times its own hold).
                val long = ButtonMap.longOf[press]?.takeIf { !(screen == BtnScreen.Guide && press == BtnPress.Ok) }?.let { ButtonMap.changed(remote, screen, it) }
                if (long != null) {
                    hs.tableHeld = press
                    hs.holdTap = press
                    holdJob[0]?.cancel()
                    holdJob[0] = fromStartScope.launch {
                        delay(450)
                        hs.holdTap = null
                        hs.tableScreen()?.let { hs.doButton(it, long) }
                    }
                    return@root true
                }
                val act = ButtonMap.changed(remote, screen, press)
                if (act != null) {
                    hs.tableHeld = press
                    if (press != BtnPress.Menu) hs.doButton(screen, act)
                    return@root true
                }
            }
        }
        // Remotes with Guide / Record buttons (e.g. Formuler): the TV guide from anywhere, and recording what's on.
        if (!hs.showAccounts && hs.recordTarget == null && hs.infoTarget == null && e.type == KeyEventType.KeyDown) {
            when (RemoteKeys.action(e)) {
                RemoteAction.Guide -> {
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        hs.quickSearch = false
                        hs.menuOpen = false
                        hs.channelListOpen = false
                        hs.osdVisible = false
                        MusicPlayer.nowPlayingOpen = false
                        // A film full screen stops (picture-in-picture for films is off for now).
                        if (VodHost.covering) VodHost.close()
                        if (hs.page == Page.Tv) { hs.fullscreen = false; hs.multiView = false } else hs.openPage(Page.Tv)
                    }
                    return@root true
                }
                // Record on live TV: the programme's options (record it, its series, start over…), as holding
                // OK in the guide. The guide handles Record on the highlighted programme itself.
                RemoteAction.Record -> {
                    val ch = playing
                    if (!hs.quickSearch && hs.page == Page.Tv && hs.fullscreen && !hs.multiView && catchup == null && ch != null &&
                        !VodHost.covering
                    ) {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            GuideViewModel.programAt(hs.programsOf(ch), now)?.let { prog ->
                                hs.osdVisible = false
                                hs.channelListOpen = false
                                hs.recordTarget = ch to prog
                            }
                        }
                        return@root true
                    }
                }
                else -> Unit
            }
        }
        if (hs.quickSearch) return@root false
        if (e.key == Key.Back) {
            // A hold is timed here (half a second) rather than waiting for the remote's repeats, which can take 0.6 s
            // to arrive (Projectivy holds Back back first). (It was 0.25 s: an unhurried tap on an ordinary remote
            // counted as a hold, and did nothing when the hold was set to Nothing.)
            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && hs.backHoldJob == null && !hs.backLongPress && !cornerUp) {
                hs.backHoldJob = fromStartScope.launch {
                    delay(BACK_HOLD_MS)
                    hs.backHoldJob = null
                    if (hs.holdBack()) hs.backLongPress = true
                }
            }
            // A repeat that says the button has been down long enough (the key's own times): the hold now, rather than
            // waiting for the timer. (The same for any remote; and a test's long press, sent all at once, counts too.)
            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 && hs.backHoldJob != null && heldFor(e) >= BACK_HOLD_MS) {
                hs.backHoldJob?.cancel(); hs.backHoldJob = null
                if (hs.holdBack()) hs.backLongPress = true
            }
            if (e.type == KeyEventType.KeyUp) { hs.backHoldJob?.cancel(); hs.backHoldJob = null }
            when {
                // (Holds are decided by the timer above, through the button table: holdBack().)
                e.type == KeyEventType.KeyDown && hs.backLongPress -> return@root true
                // Swallow the release too, or it would also count as a normal Back.
                e.type == KeyEventType.KeyUp && hs.backLongPress -> { hs.backLongPress = false; return@root true }
            }
        }
        // Number keys type a channel number on the TV page (guide or full screen); OK tunes straight away.
        if (hs.page != Page.Tv || hs.menuOpen || hs.showAccounts || hs.quickSearch || hs.recordTarget != null || hs.infoTarget != null || session == null || e.type != KeyEventType.KeyDown) return@root false
        val code = e.nativeKeyEvent.keyCode
        val digit = when (code) {
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> code - android.view.KeyEvent.KEYCODE_0
            in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> code - android.view.KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
        when {
            digit != null -> {
                if (hs.digits.length < 5) hs.digits += digit
                hs.digitsMessage = null; hs.digitsToken = System.nanoTime()
                true
            }
            hs.digits.isNotEmpty() && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                hs.tuneTyped()
                true
            }
            hs.digits.isNotEmpty() && e.key == Key.Back -> {
                hs.digits = ""
                true
            }
            else -> false
        }
    }
}

/**
 * How long the key [e] has been held, by its own times (down and this event). A repeat Android marks as a long press
 * (its own long-press time, 0.4 s on most boxes) counts as held long enough.
 */
private fun heldFor(e: KeyEvent): Long =
    if (e.nativeKeyEvent.isLongPress) Long.MAX_VALUE else e.nativeKeyEvent.eventTime - e.nativeKeyEvent.downTime

/** How long Back must be held to count as a hold (the corner picture's menu, Settings → Remote → Hold Back). */
private const val BACK_HOLD_MS = 500L

/** The least time between channel changes from a held button (zap()). */
private const val ZAP_GAP_MS = 400L

/** HomeScreen's state holder; the values that must survive the app being recreated are saved. */
@Composable
internal fun rememberHomeState(): HomeState {
    val menuOpen = rememberSaveable { mutableStateOf(false) }
    val showAccounts = rememberSaveable { mutableStateOf(false) }
    val page = rememberSaveable { mutableStateOf(Page.Tv) }
    val startApplied = rememberSaveable { mutableStateOf(false) }
    val fullscreen = rememberSaveable { mutableStateOf(false) }
    return remember { HomeState(menuOpen, showAccounts, page, startApplied, fullscreen) }
}

/**
 * Live TV switched off for whoever is here (or found to be off once Dispatcharr answers): the channel stops, the
 * guide goes and Movies shows. Switched back on (an admin's profile, say): the guide is fetched again.
 */
@Composable
internal fun LiveTvLockEffect(hs: HomeState) {
    val on = Access.liveTv
    // (The guide is fetched again only when Live TV comes back on, not on every start: that's syncIfStale's job.)
    val was = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(on) {
        val before = was.value; was.value = on
        if (!on) {
            hs.playback.close()
            hs.fullscreen = false
            hs.channelListOpen = false
            if (hs.page == Page.Tv) hs.page = Page.Movies
        } else if (before == false) {
            runCatching { hs.vm.refreshGuide() }
        }
    }
}

/**
 * Search closes once you've chosen something: after a film or show opened from the results (watched or not),
 * leaving its page closes search too, instead of going back to the old results. (Channels, catch-up and music
 * already close search as they're picked.) A person's page is still searching, so it doesn't count.
 */
@Composable
internal fun SearchClosesAfterTitle(onClose: () -> Unit) {
    val searchVm: MediaViewModel = hiltViewModel(key = "media-search")
    val detail by searchVm.detail.collectAsStateWithLifecycle()
    var chose by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(detail != null) {
        if (detail != null) chose = true
        else if (chose) { chose = false; onClose() }
    }
}

/** Settings → Appearance → News ticker → On Movies, Shows and Sports: the ticker along the bottom of those pages. */
@Composable
internal fun BoxScope.PagesTicker(hs: HomeState) {
    val onPage = (hs.page == Page.Movies || hs.page == Page.Shows || hs.sportsOpen) && !hs.fullscreen && !hs.quickSearch && !hs.menuOpen
    if (onPage && Ticker.onPages)
        NewsTicker(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
}
