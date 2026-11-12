package io.github.petebutty.viewarr.ui.home

import io.github.petebutty.viewarr.R
import androidx.compose.runtime.mutableStateMapOf
import io.github.petebutty.viewarr.ui.theme.appBackground

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.petebutty.viewarr.ui.common.glass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import kotlinx.coroutines.flow.first
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.petebutty.viewarr.playback.PlaybackState
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.player.PlayerLayer
import io.github.petebutty.viewarr.ui.player.PlayerOsd
import io.github.petebutty.viewarr.ui.player.MultiView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.EdgeBlocked
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.guide.GuideScreen
import io.github.petebutty.viewarr.ui.setup.SetupMode
import io.github.petebutty.viewarr.ui.setup.SetupWizard
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.media.MediaScreen
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.dvr.DvrViewModel
import io.github.petebutty.viewarr.ui.dvr.RecordDialog
import io.github.petebutty.viewarr.ui.dvr.ProgramInfoDialog
import io.github.petebutty.viewarr.ui.dvr.RecordingsScreen
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.settings.GuideBack
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.data.settings.MediaBack
import io.github.petebutty.viewarr.data.settings.PlayerOk
import io.github.petebutty.viewarr.data.settings.ZapDirection
import io.github.petebutty.viewarr.data.settings.StartPage
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.runtime.mutableIntStateOf
import io.github.petebutty.viewarr.data.dvr.DvrRecording
import io.github.petebutty.viewarr.data.dvr.DvrState
import io.github.petebutty.viewarr.data.dvr.Reminder
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.settings.BtnAction
import io.github.petebutty.viewarr.data.settings.BtnPress
import io.github.petebutty.viewarr.data.settings.BtnScreen
import io.github.petebutty.viewarr.data.settings.ButtonMap
import io.github.petebutty.viewarr.data.settings.GuideLeft
import io.github.petebutty.viewarr.data.settings.LiveSides
import io.github.petebutty.viewarr.playback.MusicPlayer
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.common.ButtonHint
import io.github.petebutty.viewarr.ui.common.LocalQuickSearchOpen
import io.github.petebutty.viewarr.ui.common.Press
import io.github.petebutty.viewarr.ui.common.RemoteAction
import io.github.petebutty.viewarr.ui.common.RemoteKeys
import io.github.petebutty.viewarr.ui.common.SearchKey
import io.github.petebutty.viewarr.ui.common.TapHold
import io.github.petebutty.viewarr.ui.dvr.GuideMatch
import io.github.petebutty.viewarr.ui.dvr.SeasonPicker
import io.github.petebutty.viewarr.ui.guide.channelLogo
import io.github.petebutty.viewarr.ui.guide.displayTitle
import io.github.petebutty.viewarr.ui.media.FullTrailer
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.media.VodSource
import io.github.petebutty.viewarr.ui.music.MusicBrowseKind
import io.github.petebutty.viewarr.ui.music.MusicScreen
import io.github.petebutty.viewarr.ui.music.MusicViewModel
import io.github.petebutty.viewarr.ui.player.CardOption
import io.github.petebutty.viewarr.ui.player.LiveCorner
import io.github.petebutty.viewarr.ui.player.LiveShortcut
import io.github.petebutty.viewarr.ui.player.LiveShortcuts
import io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge
import io.github.petebutty.viewarr.ui.player.OsdPoster
import io.github.petebutty.viewarr.ui.player.OsdRow
import io.github.petebutty.viewarr.ui.player.RowSets
import io.github.petebutty.viewarr.ui.player.ShortcutColours
import io.github.petebutty.viewarr.ui.player.ShortcutLogos
import io.github.petebutty.viewarr.ui.player.TransparencyPanel
import io.github.petebutty.viewarr.ui.settings.RemoteViewModel
import io.github.petebutty.viewarr.ui.settings.SportsViewModel
import io.github.petebutty.viewarr.ui.theme.CornerPicture
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.UiOpts

internal const val SEEK_MS = 30_000L

internal enum class Section(val label: String, val available: Boolean) {
    Tv("Live TV", true),
    Movies("Movies", true),
    Shows("Shows", true),
    Music("Music", true),
    Recordings("Recordings", true),
    Search("Search", true),
    Settings("Settings", true),
}

internal val LogoFont = FontFamily(Font(R.font.poppins_bold))

/** What fills the screen behind the menu. */
enum class Page { Tv, Movies, Shows, Music, Recordings, Search }

@Composable
fun HomeScreen(
    session: Session?,
    onSignOut: () -> Unit,
    vm: GuideViewModel = hiltViewModel(),
    playback: PlaybackViewModel = hiltViewModel(),
    dvr: DvrViewModel = hiltViewModel(),
) {
    // Record options for a programme (from the guide or the player's Record button).
    var recordTarget by remember { mutableStateOf<Pair<ChannelListItem, GuideProgram>?>(null) }
    // Seerr: choosing which series of a guide programme's show to request.
    var seasonPick by remember { mutableStateOf<GuideMatch?>(null) }
    var recordingPlayback by remember { mutableStateOf<VodStream?>(null) }
    // Recordings play in the same player as films (VodHost, above every page), so they can shrink to a corner too.
    val recordingFlow = remember { kotlinx.coroutines.flow.MutableStateFlow<VodStream?>(null) }
    val recordingSource = remember { VodSource(recordingFlow, kotlinx.coroutines.flow.MutableStateFlow(null), onClose = { recordingFlow.value = null }) }
    LaunchedEffect(recordingPlayback) {
        recordingPlayback?.let { recordingFlow.value = it; VodHost.show(recordingSource); recordingPlayback = null }
    }
    var infoTarget by remember { mutableStateOf<Pair<ChannelListItem, GuideProgram>?>(null) }
    // Search popped up over the current screen (Settings → Remote → Search button); Back closes it.
    var quickSearch by remember { mutableStateOf(false) }
    var searchHeld by remember { mutableStateOf(false) }
    // A changed button from Settings → Remote buttons: the rest of its press (repeats, release) is swallowed.
    var tableHeld by remember { mutableStateOf<BtnPress?>(null) }
    // A button with a long press as well (Settings → Remote buttons): its tap waits for the release, its hold for a timer.
    var holdTap by remember { mutableStateOf<BtnPress?>(null) }
    val holdJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    // Buttons that open a page or leave the app (done once those are in reach, below).
    var buttonRequest by remember { mutableStateOf<BtnAction?>(null) }
    // "Remind me": the programme starting now pops up in the corner; OK watches it, Back dismisses.
    var dueReminder by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(Unit) {
        dvr.pruneReminders()
        while (true) {
            val now = System.currentTimeMillis()
            val due = dvr.reminders.value.firstOrNull { it.start <= now + 30_000 && it.end > now }
            if (due != null && dueReminder?.id != due.id) {
                dueReminder = due
                dvr.forget(due.id)
            }
            delay(15_000)
        }
    }
    LaunchedEffect(dueReminder) { if (dueReminder != null) { delay(90_000); dueReminder = null } }
    // Recordings: a small card when one starts, finishes or fails (by the schedule, so it's on time).
    var dvrNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val told = HashSet<String>()
        var first = true
        while (true) {
            val now = System.currentTimeMillis()
            val names = dvr.channels.value
            for (r in dvr.recordings.value) {
                val ch = names[r.channelId]?.name?.let { " · $it" }.orEmpty()
                val note = when {
                    r.state == DvrState.Failed -> "⚠  Recording failed: ${r.title}$ch"
                    r.end in (now - 120_000)..now -> "✓  Recorded: ${r.title}$ch"
                    r.start in (now - 120_000)..now && r.end > now -> "●  Recording now: ${r.title}$ch"
                    else -> null
                } ?: continue
                val id = "${r.id}:${note.take(3)}"
                if (told.add(id) && !first) { dvrNote = note; delay(6_000); dvrNote = null }
            }
            first = false // what was already true when the app opened isn't news
            delay(15_000)
        }
    }
    // Live TV: the channel's providers have no free connection (see PlaybackViewModel.checkConnections).
    val connectionNote by playback.connectionNote.collectAsStateWithLifecycle()
    LaunchedEffect(connectionNote) { if (connectionNote != null) { delay(12_000); playback.clearConnectionNote() } }
    val dvrRecordings by dvr.recordings.collectAsStateWithLifecycle()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var menuWidth by remember { mutableIntStateOf(0) }
    var menuFullPage by remember { mutableStateOf(false) }
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    val remote = LocalRemote.current
    var page by rememberSaveable { mutableStateOf(Page.Tv) }
    // Movies & Shows switched off (live TV only): don't leave a hidden page on screen.
    LaunchedEffect(AppColors.palette.hideMedia) {
        if (AppColors.palette.hideMedia && (page == Page.Movies || page == Page.Shows)) page = Page.Tv
    }
    val mediaFocus = remember { FocusRequester() }
    val musicVm: MusicViewModel = hiltViewModel()
    // Channel number typed on the remote's number keys.
    var digits by remember { mutableStateOf("") }
    // Up/Down while watching: a see-through channel list over the picture.
    var channelListOpen by remember { mutableStateOf(false) }
    // Long-press Back on the guide returns to the channel that's playing, full screen.
    var backLongPress by remember { mutableStateOf(false) }
    // The timer that turns a Back press into a hold (see the root key handler).
    var backHoldJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pipBackHeld by remember { mutableStateOf(false) }
    // Hold Back with a picture in the corner (live TV or a film, over another page): Full screen / Close picture.
    var pipMenu by remember { mutableStateOf(false) }
    var pipHoldJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Start page from Settings, applied once when the app opens.
    var startApplied by rememberSaveable { mutableStateOf(false) }
    var digitsToken by remember { mutableLongStateOf(0L) }
    var digitsMessage by remember { mutableStateOf<String?>(null) }
    /** A short message at the top right (replaces the one showing). */
    fun toast(text: String) { digitsMessage = text; digitsToken = System.nanoTime() }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // Live TV hub: the channel carries on in a corner over another page (Settings → Playback → Live TV).
    var liveCorner by remember { mutableStateOf(false) }
    // Movies or Shows opened from the menu over the guide: the channel carries on in the guide's preview place, the
    // page's content starting where the guide's did.
    var livePreview by remember { mutableStateOf(false) }
    SideEffect { io.github.petebutty.viewarr.ui.common.LiveBehind.preview = livePreview }
    // Movies / Shows opened from the menu over full-screen live TV: the channel carries on full screen behind the page.
    var liveBehind by remember { mutableStateOf(false) }
    SideEffect { io.github.petebutty.viewarr.ui.common.LiveBehind.on = liveBehind }
    // The film player's Settings button opened the menu (its film is in the corner meanwhile).
    var settingsFromFilm by remember { mutableStateOf(false) }
    // Full-screen live TV: Left asks "Rewind?" first (a second Left, or holding it, rewinds), so a stray press
    // doesn't drop you out of live. When it was asked; 0: not asking.
    var rewindAsk by remember { mutableLongStateOf(0L) }
    // A hold that already rewound: the rest of that press is ignored (each repeat would rewind again).
    var rewindHeld by remember { mutableStateOf(false) }
    var bannerToken by remember { mutableLongStateOf(0L) }
    var bannerVisible by remember { mutableStateOf(false) }
    var osdVisible by remember { mutableStateOf(false) }
    // Several channels on screen at once (from the player menu).
    var multiView by remember { mutableStateOf(false) }
    // A Menu press that's still a tap (not held for search).
    // Menu on full-screen live TV: a tap (on release) opens audio & subtitles; holding it is search.
    val liveMenuKey = remember { TapHold() }
    // "Watch from the start" from the guide: search opens on the programme's title.
    var searchStart by remember { mutableStateOf<String?>(null) }
    // Search opened from the guide's "Search catch-up": with the Catch-up pill picked.
    var searchCatchup by remember { mutableStateOf(false) }
    LaunchedEffect(quickSearch) { if (!quickSearch) searchCatchup = false }
    var accountsFromMenu by remember { mutableStateOf(false) }
    var searchVoice by remember { mutableStateOf(false) }
    var menuOnSettings by remember { mutableStateOf(false) }
    // Live TV controls → Transparency: the three sliders over the picture.
    var transparencyOpen by remember { mutableStateOf(false) }
    // A film brought back full screen (held Back, handled in MainActivity): the menu or search under it closes,
    // or it would take the next Back presses.
    LaunchedEffect(VodHost.mini) {
        if (!VodHost.mini && VodHost.active) { menuOpen = false; quickSearch = false }
    }
    // Held Back with nothing playing: the menu opens on its main list (not the page's own list).
    var menuOnMain by remember { mutableStateOf(false) }
    val searchMedia: MediaViewModel = hiltViewModel(key = "media-search")
    val fromStartScope = rememberCoroutineScope()
    // Leaving full screen (guide, another page) ends multi-view; it mustn't reappear next time.
    LaunchedEffect(fullscreen) { if (!fullscreen) multiView = false }
    var osdAudio by remember { mutableStateOf(false) }
    // When the live controls were last used: a plain holder, not state. As state, every press inside the controls
    // rebuilt the whole home screen (guide, shortcuts, poster rows), which made them sluggish.
    val osdUsedAt = remember { longArrayOf(0L) }
    val guideFocus = remember { FocusRequester() }
    val playerFocus = remember { FocusRequester() }
    val playing by playback.channel.collectAsStateWithLifecycle()
    val playState by playback.state.collectAsStateWithLifecycle()
    val catchup by playback.catchup.collectAsStateWithLifecycle()
    // The guide group a channel was started from (browsing another group later doesn't change it).
    var playingGroup by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(playing?.id) { if (playing != null) playingGroup = vm.groupId.value }
    // Back from full screen: the guide opens on the channel playing, at now, in its group (not wherever the guide was
    // last scrolled to, nor the group browsed since).
    LaunchedEffect(fullscreen) {
        if (!fullscreen && page == Page.Tv) playing?.let { vm.returnTo(it.id, playingGroup) }
    }
    val programs by vm.programs.collectAsStateWithLifecycle()
    // The guide only keeps programmes for the channels near where it's scrolled to. The channel you're watching
    // (and multi-view's) are looked up on their own, or the banner said "No guide information" once you'd scrolled
    // away from it in the guide.
    val ownPrograms = remember { mutableStateMapOf<Int, Pair<Long, List<GuideProgram>>>() }
    val ownScope = rememberCoroutineScope()
    val ownLoading = remember { HashSet<Int>() }
    fun programsOf(ch: ChannelListItem): List<GuideProgram> {
        programs[ch.id]?.let { return it }
        val cached = ownPrograms[ch.id]
        // Refreshed every 10 minutes, so what's on now moves on.
        if ((cached == null || System.currentTimeMillis() - cached.first > 600_000) && ownLoading.add(ch.id)) ownScope.launch {
            runCatching { vm.programsFor(ch) }.getOrNull()?.let { ownPrograms[ch.id] = System.currentTimeMillis() to it }
            ownLoading.remove(ch.id)
        }
        return cached?.second.orEmpty()
    }
    val now by vm.now.collectAsStateWithLifecycle()

    fun showBanner() {
        bannerVisible = true
        bannerToken = System.nanoTime()
    }
    /**
     * Onto [ch] full screen (the guide moves to it first): live, or [catchup] from catch-up. [endMultiView]: multi-view
     * closes; [keepIfPlaying]: no restart when it's the channel already playing.
     */
    fun watchChannel(ch: ChannelListItem, catchup: GuideProgram? = null, endMultiView: Boolean = false, keepIfPlaying: Boolean = false, s: Session? = session) {
        vm.reveal(ch) {
            page = Page.Tv
            when {
                catchup != null -> playback.playCatchup(ch, catchup, s)
                !keepIfPlaying || playing?.id != ch.id -> playback.play(ch, s)
            }
            vm.selectChannel(ch.id)
            fullscreen = true
            if (endMultiView) multiView = false
            showBanner()
        }
    }

    // Live TV moved to another source: what was playing belongs to the old one (its picture froze), so stop it.
    var lastSource by remember { mutableStateOf(session?.id) }
    LaunchedEffect(session?.id) {
        val now = session?.id
        val previous = lastSource
        lastSource = now
        if (previous == null || now == previous || session == null) return@LaunchedEffect
        // What's playing carries on (full screen, or the guide's preview); the guide and channel list show the new
        // source's channels, and only picking one changes the channel.
    }

    fun showOsd(audio: Boolean = false) {
        osdAudio = audio
        bannerVisible = false
        osdVisible = true
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

    fun zap(delta: Int) {
        val list = vm.channels.value
        val index = list.indexOfFirst { it.id == playing?.id }
        val target = list.getOrNull((if (index < 0) 0 else index + delta).coerceIn(0, maxOf(0, list.size - 1))) ?: return
        if (target.id == playing?.id) return
        playback.play(target, session)
        vm.selectChannel(target.id)
        showBanner()
    }

    /** A press from the button table (Settings → Remote → Buttons) on the guide, live TV or a page. */
    fun doButton(screen: BtnScreen, act: BtnAction) {
        val ch = playing
        when (act) {
            BtnAction.Nothing, BtnAction.TimeTitle, BtnAction.SeekBar, BtnAction.MediaInfo, BtnAction.SwitchPlayer -> Unit
            BtnAction.Movies, BtnAction.Shows, BtnAction.Recordings, BtnAction.Music, BtnAction.Exit -> buttonRequest = act
            BtnAction.Settings -> { fullscreen = false; channelListOpen = false; menuOnSettings = true; menuOpen = true }
            BtnAction.WatchInGuide -> vm.channels.value.getOrNull(vm.cursor.value.row)?.let { c ->
                if (playing?.id == c.id && catchup == null) { fullscreen = true; showBanner() } else { playback.play(c, session); vm.selectChannel(c.id) }
            }
            BtnAction.Watch -> vm.channels.value.getOrNull(vm.cursor.value.row)?.let { c ->
                playback.play(c, session); vm.selectChannel(c.id); fullscreen = true; showBanner()
            }
            BtnAction.GoLive -> if (catchup != null) { playback.goLive(); showBanner() }
            BtnAction.Description -> {
                val target = if (screen == BtnScreen.Live) ch?.let { c -> GuideViewModel.programAt(programsOf(c), now)?.let { c to it } }
                else vm.cursor.value.let { cur -> vm.channels.value.getOrNull(cur.row)?.let { c -> GuideViewModel.programAt(programsOf(c), cur.focusTime)?.let { c to it } } }
                if (target != null) { osdVisible = false; channelListOpen = false; infoTarget = target }
            }
            BtnAction.MultiView -> if (ch != null && AppColors.palette.heavyExtras) {
                osdVisible = false; channelListOpen = false
                if (catchup != null) playback.goLive()
                fullscreen = true; multiView = true
            }
            BtnAction.Transparency -> { osdVisible = false; channelListOpen = false; transparencyOpen = true }
            BtnAction.PageUp, BtnAction.PageDown -> vm.moveVertical(if (act == BtnAction.PageUp) -7 else 7, if (AppColors.palette.opt(UiOpts.LIST_ENDS) == 0) 1 else 2)
            BtnAction.Earlier -> vm.moveLeft()
            BtnAction.Later -> vm.moveRight()
            BtnAction.Controls -> if (ch != null) showOsd()
            BtnAction.NextChannel -> zap(1)
            BtnAction.PrevChannel -> zap(-1)
            BtnAction.LastChannel -> lastChannel()
            BtnAction.SkipBack, BtnAction.SkipForward -> if (ch != null || catchup != null) {
                playback.seekBy(if (act == BtnAction.SkipBack) -remote.seek.ms else remote.seek.ms)
                showBanner()
            }
            BtnAction.PlayPause -> if (ch != null || catchup != null) { playback.togglePause(); showBanner() }
            BtnAction.GroupsMenu -> { fullscreen = false; channelListOpen = false; menuOnMain = false; menuOpen = true }
            BtnAction.MainMenu -> { fullscreen = false; channelListOpen = false; menuOnMain = true; menuOpen = true }
            BtnAction.FullScreen -> if (ch != null) { fullscreen = true; showBanner() }
            BtnAction.Guide -> if (screen == BtnScreen.Pages) buttonRequest = act else { fullscreen = false; channelListOpen = false }
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
                        else -> { osdVisible = false; channelListOpen = false; recordTarget = target }
                    }
                }
            }
            BtnAction.ChannelList -> if (fullscreen) channelListOpen = true
            // The guide: the highlighted programme from its start (catch-up, or on now).
            BtnAction.StartOver -> if (screen == BtnScreen.Guide) {
                val cur = vm.cursor.value
                vm.channels.value.getOrNull(cur.row)?.takeIf { it.catchupDays > 0 }?.let { c ->
                    GuideViewModel.programAt(programsOf(c), cur.focusTime)?.takeIf { it.startTime < now }?.let { prog ->
                        playback.playCatchup(c, prog, session); vm.selectChannel(c.id); fullscreen = true; showBanner()
                    }
                }
            } else if (ch != null && ch.catchupDays > 0) {
                GuideViewModel.programAt(programsOf(ch), now)?.takeIf { it.startTime < now }?.let { prog ->
                    playback.playCatchup(ch, prog, session)
                    vm.selectChannel(ch.id)
                    showBanner()
                }
            }
            BtnAction.Banner -> showBanner()
            BtnAction.AudioSubs -> showOsd(audio = true)
            BtnAction.Search -> { channelListOpen = false; searchStart = null; quickSearch = true }
            // Live TV stops (catch-up too): the guide with nothing playing, where Back asks to exit.
            BtnAction.Stop -> if (ch != null || catchup != null) {
                playback.stop()
                osdVisible = false
                channelListOpen = false
                multiView = false
                fullscreen = false
                bannerVisible = false
            }
            // Films only (VodKeys).
            BtnAction.Details, BtnAction.TimeTitle, BtnAction.SeekBar -> Unit
        }
    }

    /**
     * Back held (timed in the root key handler): the guide's menu on its channel groups, or with nothing playing the
     * menu. False when a hold means nothing here (it's then left to whoever else uses one: music, a film).
     */
    fun holdBack(): Boolean {
        if (menuOpen || showAccounts || quickSearch || recordTarget != null || infoTarget != null) return false
        if (VodHost.active) return false
        val music = MusicPlayer
        if (music.active && !music.nowPlayingOpen) return false
        val screen = when {
            page == Page.Tv && fullscreen -> if (osdVisible || channelListOpen || multiView || transparencyOpen) return false else BtnScreen.Live
            page == Page.Tv -> BtnScreen.Guide
            playing == null -> BtnScreen.Pages
            else -> return false
        }
        // Settings → Remote → Buttons: "Nothing" still takes the hold (it isn't also a tap of Back).
        var act = ButtonMap.action(remote, screen, BtnPress.BackHold)
        // The guide must always have a way to the menu: with Left never opening it and Back not either, hold Back does.
        if (screen == BtnScreen.Guide && remote.guideLeft == GuideLeft.AlwaysPast && remote.guideBack != GuideBack.Menu &&
            act !in setOf(BtnAction.GroupsMenu, BtnAction.MainMenu)
        ) act = BtnAction.GroupsMenu
        doButton(screen, act)
        return true
    }

    /** Which screen of Settings → Remote buttons a press is on now (null: none, e.g. a menu or dialog is open). */
    fun tableScreen(): BtnScreen? {
        if (menuOpen || showAccounts || quickSearch || recordTarget != null || infoTarget != null || VodHost.covering || transparencyOpen) return null
        return when {
            page == Page.Tv -> if (fullscreen) BtnScreen.Live else BtnScreen.Guide
            page != Page.Search && !MusicPlayer.nowPlayingOpen -> BtnScreen.Pages
            else -> null
        }
    }

    LaunchedEffect(bannerToken) {
        if (bannerVisible) {
            delay(5_000)
            bannerVisible = false
        }
    }
    // The live controls close 8 s after they were last used.
    LaunchedEffect(osdVisible) {
        if (!osdVisible) return@LaunchedEffect
        while (true) {
            val left = 8_000 - (System.currentTimeMillis() - osdUsedAt[0])
            if (left <= 0) { osdVisible = false; break }
            delay(left)
        }
    }
    LaunchedEffect(remote.loaded) {
        if (!remote.loaded || startApplied) return@LaunchedEffect
        startApplied = true
        // Live TV hub: always opens on the last channel, full screen.
        val start = remote.startPage
        when (start) {
            StartPage.Guide -> Unit
            StartPage.Movies -> page = Page.Movies
            StartPage.Shows -> page = Page.Shows
            StartPage.Search -> quickSearch = true
            StartPage.Recordings -> page = Page.Recordings
            StartPage.LastChannel -> {
                val ch = remote.lastChannelId?.let { id -> vm.channelById(id) }
                if (ch != null && session != null) watchChannel(ch)
            }
        }
    }
    LaunchedEffect(menuOpen, fullscreen, osdVisible, transparencyOpen, showAccounts, page, recordTarget == null, seasonPick == null, VodHost.covering, VodHost.gliding, infoTarget == null, channelListOpen, quickSearch, MusicPlayer.nowPlayingOpen) {
        if (channelListOpen || quickSearch || transparencyOpen || MusicPlayer.nowPlayingOpen) return@LaunchedEffect
        if (recordTarget != null || seasonPick != null || VodHost.covering || infoTarget != null) return@LaunchedEffect
        // A film gliding into the corner: the page takes the remote once it's there (its highlight starting mid-glide
        // made the glide stutter).
        if (VodHost.gliding) return@LaunchedEffect
        // Just landed in the corner: a moment before the page takes the remote, so its highlight and the player
        // letting go aren't drawn in the same frame (that frame was slow).
        if (VodHost.mini) delay(150)
        awaitFrame()
        runCatching {
            when {
                fullscreen && !osdVisible -> playerFocus.requestFocus()
                !fullscreen && !menuOpen && !showAccounts && !quickSearch -> if (page == Page.Tv) guideFocus.requestFocus() else mediaFocus.requestFocus()
            }
        }
    }
    // A film or episode playing over Live TV (Watch from the start, search, a recording): the channel is stopped and
    // its player let go (it carried on underneath, sound and all). Picking a channel afterwards starts it afresh.
    val vodOver = VodHost.active
    LaunchedEffect(vodOver) {
        if (vodOver) { fullscreen = false; liveCorner = false; livePreview = false; playback.close() }
    }
    // …and a channel started while a film is in the corner ends the film (one stream, one sound).
    LaunchedEffect(playing?.id) { if (playing != null) { VodHost.close(); MusicPlayer.stop() } }
    // Music starting ends Live TV (as a film does).
    val musicOn = MusicPlayer.active
    LaunchedEffect(musicOn) { if (musicOn) { fullscreen = false; liveCorner = false; livePreview = false; playback.close() } }
    // A full-screen trailer (title page → Trailer) ends live TV too; it was silent with the channel still playing.
    val trailerOn = FullTrailer.active
    LaunchedEffect(trailerOn) { if (trailerOn) { fullscreen = false; liveCorner = false; livePreview = false; playback.close() } }
    // Back on the guide (or the channel stopped): nothing in the corner any more.
    LaunchedEffect(page, playing == null) { if (page == Page.Tv || playing == null) { liveCorner = false; livePreview = false } }
    val cornerNow = liveCorner && playing != null && page != Page.Tv
    // A picture in the corner (live TV over another page, or a film).
    val pictureInCorner = cornerNow || (VodHost.active && VodHost.mini)
    SideEffect { LiveCorner.on = cornerNow }
    // Settings → Playback → Still watching?: after hours without a button press on live TV, ask, then stop (a TV left
    // on otherwise holds a provider connection all night).
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var stillAsk by remember { mutableStateOf(false) }
    val stillHours = AppColors.palette.stillWatchingHours
    LaunchedEffect(stillHours, playing?.id) {
        if (stillHours == 0) return@LaunchedEffect
        while (true) {
            delay(60_000)
            if (playing != null && !stillAsk && System.currentTimeMillis() - lastKeyAt > stillHours * 3_600_000L) stillAsk = true
        }
    }
    LaunchedEffect(stillAsk) {
        if (!stillAsk) return@LaunchedEffect
        delay(60_000)
        if (stillAsk) { stillAsk = false; fullscreen = false; playback.stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { playback.resume() }

    // Back on a page (guide, Movies, Shows, Recordings) asks before leaving: a second Back within a few
    // seconds exits. The menu handles its own Back (list → main menu → closed).
    val activity = LocalContext.current as? Activity
    var exitArmed by remember { mutableLongStateOf(0L) }
    /**
     * The menu was opened by Back in the guide (TV guide → Back: Open the menu, as TiviMate): Back out of it asks to
     * exit, and Back once more exits (it used to close the menu, and Back opened it again: no way out).
     */
    var menuFromGuideBack by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) { if (!menuOpen) { menuFromGuideBack = false; menuFullPage = false; VodHost.behind = false } }
    // Left from the film controls' first button: the menu over the film, which plays on full screen underneath.
    SideEffect { VodHost.openMenu = { VodHost.behind = true; menuOnMain = true; menuOpen = true } }
    /** When Back last took full screen down to the guide (a quick second Back then asks to exit). */
    var guideFromFullAt by remember { mutableLongStateOf(0L) }
    /** The guide's Back (Settings → Remote & navigation → TV guide). */
    val guideBack = remote.guideBack
    /** Live TV hub: back to the channel watched last, full screen. False when there's none to go back to. */
    fun goLive(): Boolean {
        val s = session ?: return false
        val id = playing?.id ?: remote.lastChannelId ?: return false
        menuOpen = false
        quickSearch = false
        fromStartScope.launch {
            val ch = vm.channelById(id) ?: return@launch
            watchChannel(ch, keepIfPlaying = true, s = s)
        }
        return true
    }

    fun backOnPage() {
        // A film opened a shortcut and is in the corner: Back takes it full screen again (as live TV does).
        if (VodHost.active && VodHost.mini) { VodHost.expand(); return }
        // A library over live TV: Back is the channel full screen again.
        if (liveBehind && playing != null) { liveBehind = false; page = Page.Tv; fullscreen = true; showBanner(); return }
        // The channel in the guide's preview place: Back is the guide again.
        if (livePreview && playing != null && page != Page.Tv) { livePreview = false; page = Page.Tv; return }
        // The channel still playing in the corner: Back takes it full screen again. (Otherwise Back is the page's own.)
        if (liveCorner && playing != null && page != Page.Tv && goLive()) return
        if (exitArmed != 0L) activity?.finish() else exitArmed = System.currentTimeMillis()
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed != 0L) { delay(2_500); exitArmed = 0L }
    }
    // Back returns to live TV first when browsing the past/future, then asks to exit (or opens the menu).
    BackHandler(enabled = page == Page.Tv && !menuOpen && !fullscreen && !showAccounts && !quickSearch) {
        val toFull = guideBack == GuideBack.FullScreen && playing != null
        when {
            !vm.isAtNow() -> vm.goToNow()
            // "Press Back again to exit" is up: this Back exits.
            exitArmed != 0L -> backOnPage()
            // Back twice quickly from full screen asks to exit (as before 0.23), rather than swapping straight back.
            toFull && System.currentTimeMillis() - guideFromFullAt < 1_500 -> backOnPage()
            // Back to the channel playing in the preview, full screen (Back there brings you here again).
            toFull -> { fullscreen = true; showBanner() }
            guideBack == GuideBack.Menu -> { menuOpen = true; menuFromGuideBack = true }
            else -> backOnPage()
        }
    }
    BackHandler(enabled = fullscreen && !osdVisible) {
        ButtonMap.changed(remote, BtnScreen.Live, BtnPress.Back)?.let { doButton(BtnScreen.Live, it); return@BackHandler }
        fullscreen = false
        bannerVisible = false
        guideFromFullAt = System.currentTimeMillis()
    }

    fun tuneTyped() {
        val n = digits.toIntOrNull() ?: return
        digits = ""
        vm.findByNumber(n) { ch ->
            if (ch == null) {
                toast("No channel $n")
            } else {
                playback.play(ch, session)
                vm.selectChannel(ch.id)
                fullscreen = true
                multiView = false
                showBanner()
            }
        }
    }

    LaunchedEffect(digitsToken) {
        if (digits.isEmpty() && digitsMessage == null) return@LaunchedEffect
        delay(if (digitsMessage != null) 3_000 else 1_800)
        if (digitsMessage != null) digitsMessage = null else tuneTyped()
    }

    // Watch Next cards on the TV home screen: brought up to date a little after start-up (servers answer slowly
    // while everything else loads), and a card picked there opens its title's page here.
    val moviesVm: MediaViewModel = hiltViewModel(key = "media-Movie")
    val showsVm: MediaViewModel = hiltViewModel(key = "media-Show")
    // Into a title (a poster on a library or catalogue page) while the channel's in the corner: it stops. Browsing a
    // shortcut's posters keeps it; choosing one is watching something else.
    val titleOpen = moviesVm.detail.collectAsStateWithLifecycle().value != null || showsVm.detail.collectAsStateWithLifecycle().value != null
    LaunchedEffect(titleOpen) {
        // (The channel in the preview place stays: the title page makes room for it.)
        if (titleOpen && liveCorner) { liveCorner = false; playback.stop() }
        if (titleOpen && liveBehind) { liveBehind = false; playback.stop() }
    }
    // Over live TV only on Movies / Shows; nothing playing, nothing behind.
    LaunchedEffect(page, playing == null) { if ((page != Page.Movies && page != Page.Shows) || playing == null) liveBehind = false }
    LaunchedEffect(Unit) { moviesVm.refreshPlexServers(); delay(20_000); moviesVm.refreshWatchNext() }
    // The splash can go once the guide has its channels (the splash's own limit covers a guide that doesn't come).
    LaunchedEffect(Unit) {
        vm.channels.first { it.isNotEmpty() }
        io.github.petebutty.viewarr.ui.common.StartupGate.ready = true
    }
    val deepLink by DeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink) {
        val item = deepLink ?: return@LaunchedEffect
        DeepLink.pending.value = null
        quickSearch = false
        menuOpen = false
        val show = item.type == MediaType.Show
        if (page == Page.Tv) { playback.stop(); fullscreen = false }
        page = if (show) Page.Shows else Page.Movies
        (if (show) showsVm else moviesVm).open(item)
    }

    /**
     * [keepLive]: leaving live TV for this page leaves the channel playing in a corner (a shortcut under the live
     * controls, or any page in the Live TV hub style), unless that's switched off or the box is short of memory.
     */
    fun openPage(p: Page, keepLive: Boolean = false, closeMenu: Boolean = true) {
        // From the menu over a full-screen film: the film carries on in the corner, the page opens.
        if (VodHost.behind) { VodHost.behind = false; VodHost.minimise() }
        // Search never replaces what's on screen: it slides over it, and Back returns you there.
        if (p == Page.Search) {
            menuOpen = false
            quickSearch = true
            return
        }
        // Over live TV already: another of Movies / Shows keeps it behind; anything else stops it (the TV guide: full screen).
        if (liveBehind && p != Page.Movies && p != Page.Shows) {
            liveBehind = false
            if (p == Page.Tv) { fullscreen = true; showBanner() } else playback.stop()
        }
        // From Movies / Shows with the channel in the preview place: the guide keeps it; anything else stops it.
        if (livePreview && p == Page.Tv) livePreview = false
        if (p != Page.Tv && page == Page.Tv) {
            val pal = AppColors.palette
            // From the menu over full-screen live TV, a library or catalogue: the channel stays full screen behind it.
            if (!keepLive && fullscreen && playing != null && !multiView && (p == Page.Movies || p == Page.Shows) && !pal.lowMemory) {
                liveBehind = true
                bannerVisible = false
                osdVisible = false
                channelListOpen = false
            } else if ((keepLive || !fullscreen) && playing != null && !multiView && p != Page.Search && !pal.lowMemory) {
                // From the guide: the channel stays where the guide's preview was.
                livePreview = true
                bannerVisible = false
                osdVisible = false
            } else playback.stop()
            fullscreen = false
        }
        page = p
        if (closeMenu) menuOpen = false
    }

    // A button's page (Movies, Recordings…), the TV guide from a page, or leaving the app.
    LaunchedEffect(buttonRequest) {
        val r = buttonRequest ?: return@LaunchedEffect
        buttonRequest = null
        when (r) {
            BtnAction.Movies -> openPage(Page.Movies)
            BtnAction.Shows -> openPage(Page.Shows)
            BtnAction.Recordings -> openPage(Page.Recordings)
            BtnAction.Music -> openPage(Page.Music)
            BtnAction.Guide -> { menuOpen = false; openPage(Page.Tv) }
            BtnAction.Exit -> activity?.finish()
            else -> Unit
        }
    }
    // Live TV controls → Down: your shortcuts (Settings → Remote & navigation → Live TV shortcuts).
    val movieLibs by moviesVm.libraries.collectAsStateWithLifecycle()
    val showLibs by showsVm.libraries.collectAsStateWithLifecycle()
    val movieMenuRows by moviesVm.menuRows.collectAsStateWithLifecycle()
    val showMenuRows by showsVm.menuRows.collectAsStateWithLifecycle()
    // Streaming services down the main menu (AIOMetadata's "Netflix · Popular", "Netflix · New"… rows), each with its
    // rows' names: its show rows then its film rows on one page.
    val menuServices = remember(movieMenuRows.rows, showMenuRows.rows) {
        val all = showMenuRows.rows + movieMenuRows.rows
        (RowSets.sets(showMenuRows.rows) + RowSets.sets(movieMenuRows.rows)).filter { it.startsWith("name:") }.distinct().map { key ->
            key.removePrefix("name:") to all.filter { RowSets.matches(key, it) }.map { it.id to RowSets.rowTitle(it) }.distinctBy { it.first }
        }
    }
    fun openService(name: String, closeMenu: Boolean, rowId: String? = null) {
        val key = "name:$name"
        val type = if (showMenuRows.rows.any { RowSets.matches(key, it) }) MediaType.Show else MediaType.Movie
        val mvm = if (type == MediaType.Show) showsVm else moviesVm
        if (mvm.currentRowSet.value != key) { mvm.openLibrary(null, type); mvm.jumpTo(null) }
        // Its rows from the other page follow, in that page's order without the ones hidden (the menu's rows list
        // can change both, so this is redone each time).
        val (other, otherHidden) = (if (type == MediaType.Show) moviesVm else showsVm).orderedRows.value
        val extra = other.filter { RowSets.matches(key, it) && it.id !in otherHidden }
            .map { it.copy(title = it.title + if (type == MediaType.Show) " (films)" else " (shows)") }
        mvm.showRowSet(key, extra)
        // A row picked in the menu (Popular, New…): the page opens on it.
        rowId?.let { mvm.jumpWithin(it) }
        openPage(if (type == MediaType.Show) Page.Shows else Page.Movies, closeMenu = closeMenu)
    }
    val musicOnNow by musicVm.enabled.collectAsStateWithLifecycle()
    // Settings → Remote & navigation → Live TV shortcuts → Poster rows (off in Low memory mode: no pictures).
    val postersOn = AppColors.palette.opt(UiOpts.OSD_POSTERS) == 0 && !AppColors.palette.lowMemory
    // The shortcuts' rows, logos, Live now and Sports: loaded after the app starts (once connected and the start page is
    // up), not the first time the controls open, so they're ready then. One at a time, a few seconds apart, so a slow
    // box isn't asked for everything at once while you start using it. (Refreshed again as the controls open.)
    var preloadStage by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val ctxForPreload = LocalContext.current
    LaunchedEffect(session?.id) {
        if (session == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { io.github.petebutty.viewarr.ui.common.StartupGate.ready }.first { it }
        val am = ctxForPreload.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val gap = if (am?.isLowRamDevice == true || AppColors.palette.lowMemory) 5_000L else 2_500L
        delay(3_000)
        for (stage in 1..5) { preloadStage = stage; delay(gap) }
        // On for long enough to load, then off, so the controls closing later doesn't load it all again.
        delay(60_000)
        preloadStage = 0
    }
    // 1: libraries and the pages' rows (row and network shortcuts come from them: Plex rows, AIOMetadata's networks).
    val preloadRows = preloadStage >= 1
    LaunchedEffect(osdVisible, preloadRows) {
        if (!osdVisible && !preloadRows) return@LaunchedEffect
        moviesVm.loadLibraries(MediaType.Movie); showsVm.loadLibraries(MediaType.Show)
        moviesVm.load(MediaType.Movie); showsVm.load(MediaType.Show)
    }
    // 2: Continue watching and Recently added (the poster rows under the shortcuts).
    val preloadPosters = preloadStage >= 2
    LaunchedEffect(osdVisible, preloadPosters) {
        if ((!osdVisible && !preloadPosters) || !postersOn) return@LaunchedEffect
        moviesVm.loadContinue(MediaType.Movie); showsVm.loadContinue(MediaType.Show)
        moviesVm.loadRecent(MediaType.Movie); showsVm.loadRecent(MediaType.Show)
    }
    val preloadEvents = preloadStage >= 3
    val preloadExtras = preloadStage >= 4
    val preloadMusic = preloadStage >= 5
    // The music service in use (its logo on the music shortcuts).
    val musicSources by musicVm.sources.collectAsStateWithLifecycle()
    val musicSourceName = musicSources.firstOrNull { it.on }?.name
    // Live now (the hub's shortcut): the event channels' programmes, looked at while the live TV controls are up.
    var liveEvents by remember { mutableStateOf<GuideViewModel.LiveEvents?>(null) }
    // (Also while the film player's controls are up: its shortcuts show the same rows.)
    val rowsWanted = osdVisible || LiveShortcutsBridge.filmMenuOpen
    // (Once at the start; then every minute while the controls are up.)
    // (3)
    LaunchedEffect(rowsWanted, preloadEvents) {
        if (!rowsWanted && !preloadEvents) return@LaunchedEffect
        while (true) {
            liveEvents = runCatching { vm.liveEvents() }.getOrNull() ?: liveEvents
            if (!rowsWanted) break
            delay(60_000)
        }
    }
    /** OK on Live now: the TV guide, on the events group most of what's on now is in. */
    fun openLiveEvents() {
        osdVisible = false
        liveEvents?.mainGroup?.let { name -> vm.groups.value.firstOrNull { it.name == name }?.let { g -> vm.selectGroup(g.id) } }
        fullscreen = false
    }
    /**
     * The menu's Live now and Sports: the TV guide on the event channels, or on your sports channels (the first group
     * named Sports). Moving onto one shows it behind the menu; Right / OK goes into the guide.
     */
    fun openLiveEntry(label: String, closeMenu: Boolean) {
        if (page != Page.Tv) openPage(Page.Tv, closeMenu = closeMenu)
        val sports = if (label == "Sports") vm.groups.value.firstOrNull { it.name.equals("Sports", true) } ?: vm.groups.value.firstOrNull { it.name.contains("Sport", true) } else null
        if (sports != null) { osdVisible = false; vm.selectGroup(sports.id); fullscreen = false } else openLiveEvents()
        if (closeMenu) menuOpen = false
    }

    /**
     * The Refresh shortcut: every row again from your addons (AIOMetadata, AIOStreams…), Plex, Jellyfin and Emby, with
     * the libraries and Continue watching, so the shortcuts and their previews show what's there now.
     */
    fun refreshShortcuts() {
        toast("Refreshing your shortcuts…")
        listOf(moviesVm to MediaType.Movie, showsVm to MediaType.Show).forEach { (m, t) ->
            m.load(t, force = true); m.loadLibraries(t, force = true); m.loadContinue(t, force = true)
        }
        fromStartScope.launch {
            val done = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                moviesVm.menuRows.first { it.done }; showsVm.menuRows.first { it.done }
            }
            toast(if (done != null) "Shortcuts up to date" else "Some addons are slow to answer: their rows will appear when they do")
        }
    }
    // ---- Sports hub (Settings → Sports): your teams and leagues, each a shortcut with its games.
    val sportsVm: SportsViewModel = hiltViewModel()
    val sportFollows by sportsVm.follows.collectAsStateWithLifecycle()
    // (SportsHub.kt: the games, where they're on, and the cards, rows and pinned shortcuts made from them.)
    val sessionRef = rememberUpdatedState(session)
    val sportsHub = remember {
        SportsHub(sportsVm, vm, dvr,
            watch = { ch -> osdVisible = false; playback.play(ch, sessionRef.value); vm.selectChannel(ch.id); fullscreen = true; showBanner() },
            toast = { toast(it) })
    }
    // (4)
    LaunchedEffect(rowsWanted, preloadExtras, sportFollows) {
        if (!rowsWanted && !preloadExtras) return@LaunchedEffect
        sportsHub.load(sportFollows)
    }

    // (Built again only when what it's made from changes: it was rebuilt on every redraw of this screen. What each
    // shortcut does reads the rest when it's pressed.)
    val mainShortcuts: List<Pair<LiveShortcut, () -> Unit>> = remember(movieLibs, showLibs, movieMenuRows, showMenuRows, musicOnNow, musicSourceName, remote.liveShortcuts) {
        val all = LiveShortcuts.candidates(
            movieLibs.orEmpty(), showLibs.orEmpty(), movieMenuRows.rows, showMenuRows.rows, musicOnNow, musicSourceName,
        ).associateBy { it.id }
        val rowsForIds = movieMenuRows.rows + showMenuRows.rows
        val saved = (remote.liveShortcuts ?: LiveShortcuts.defaults)
            .mapNotNull { LiveShortcuts.resolve(it, all, rowsForIds) }.distinctBy { it.id }
        // Nothing usable saved (everything gone, or not loaded yet): the defaults rather than an empty row.
        val use = saved.ifEmpty { LiveShortcuts.defaults.mapNotNull { all[it] } }
        use.map { it.id }.mapNotNull { id ->
            val sc = all[id] ?: return@mapNotNull null
            sc to sc@{
                // Sports: like Live now, OK opens the guide on the sports channels (its rows, Down, are your teams).
                if (id == "page:Sports") { openLiveEvents(); return@sc }
                osdVisible = false
                val parts = id.split(":", limit = 3)
                val type = if (parts.getOrNull(1) == "Show") MediaType.Show else MediaType.Movie
                val mvm = if (type == MediaType.Show) showsVm else moviesVm
                val target = if (type == MediaType.Show) Page.Shows else Page.Movies
                when (parts[0]) {
                    "home" -> { mvm.openLibrary(null, type); mvm.jumpTo(null); openPage(target, keepLive = true) }
                    "lib" -> { (if (type == MediaType.Show) showLibs else movieLibs).orEmpty().firstOrNull { it.key == parts[2] }?.let { mvm.openLibrary(it, type) }; openPage(target, keepLive = true) }
                    "row" -> { mvm.openLibrary(null, type); mvm.jumpTo(parts[2]); openPage(target, keepLive = true) }
                    "set" -> {
                        mvm.openLibrary(null, type); mvm.jumpTo(null)
                        // A network: its film rows follow its show rows on the same page (named "… (films)").
                        val other = if (type == MediaType.Show) movieMenuRows.rows else showMenuRows.rows
                        val extra = if (!parts[2].startsWith("name:")) emptyList() else other
                            .filter { RowSets.matches(parts[2], it) }
                            .map { it.copy(title = it.title + if (type == MediaType.Show) " (films)" else " (shows)") }
                        mvm.showRowSet(parts[2], extra)
                        openPage(target, keepLive = true)
                    }
                    // Music: straight into Albums, Artists, Playlists… (the channel carries on in the corner).
                    "music" -> {
                        runCatching { MusicBrowseKind.valueOf(parts[1]) }.getOrNull()?.let { musicVm.openBrowse(it) }
                        openPage(Page.Music, keepLive = true)
                    }
                    "page" -> when (parts[1]) {
                        "Guide" -> fullscreen = false
                        "Live" -> openLiveEvents()
                        "Music" -> openPage(Page.Music, keepLive = true)
                        "Recordings" -> openPage(Page.Recordings, keepLive = true)
                        "Settings" -> { menuOnSettings = true; menuOpen = true }
                        else -> { searchStart = null; quickSearch = true }
                    }
                }
            }
        } + listOf(
            // Last in the row: fetch every row again from your addons (AIOMetadata, AIOStreams…) and servers, so new
            // networks and fresh catalogues turn up without restarting Viewarr. (Not movable or hideable.)
            LiveShortcut(REFRESH_SHORTCUT, "Refresh", AppIcons.Refresh,
                colors = ShortcutColours.settings) to { refreshShortcuts() },
        )
    }
    // The row on live TV: the main row with the teams / leagues you pinned (Settings → Sports) before Refresh.
    val liveShortcuts: List<Pair<LiveShortcut, () -> Unit>> = remember(mainShortcuts, sportFollows) {
        mainShortcuts.dropLast(1) + sportFollows.filter { it.pinned && mainShortcuts.none { m -> m.first.id == it.shortcutId } }.map { sportsHub.shortcut(it) } + mainShortcuts.last()
    }

    // Network / service shortcuts: their logos (TMDB), looked up at the start (and for any shortcut added since).
    LaunchedEffect(osdVisible, preloadExtras, liveShortcuts.map { it.first.id }) {
        if (!osdVisible && !preloadExtras) return@LaunchedEffect
        val names = liveShortcuts.mapNotNull { (sc, _) -> sc.logoName?.takeIf { !it.startsWith("sport:") }?.let { n -> n to sc } }
            .filter { (n, _) -> n !in ShortcutLogos.map }
        if (names.isEmpty()) return@LaunchedEffect
        // A show from its rows tells TMDB which network it is (networks aren't watch providers).
        moviesVm.loadShortcutLogos(names.associate { (n, _) ->
            n to showMenuRows.rows.firstOrNull { RowSets.name(it) == n }?.items?.firstOrNull()
        })
    }
    // The same shortcuts in the film and show players' controls.
    SideEffect {
        LiveShortcutsBridge.items = liveShortcuts
        LiveShortcutsBridge.openSettings = { settingsFromFilm = true; menuOnSettings = true; menuOpen = true }
        LiveShortcutsBridge.openGuide = { menuOpen = false; quickSearch = false; if (page != Page.Tv) openPage(Page.Tv) else fullscreen = false }
    }
    // Settings opened from a film: closing it brings the film back full screen.
    LaunchedEffect(menuOpen) {
        if (!menuOpen && settingsFromFilm) {
            settingsFromFilm = false
            if (VodHost.active && VodHost.mini) VodHost.expand()
        }
    }
    // Hold OK on a shortcut: off the row (saved ids that stand for it too), with a note on getting it back.
    val remoteVm: RemoteViewModel = hiltViewModel()
    fun hideShortcut(sc: LiveShortcut) {
        // A pinned team / league: Unpin (it stays under the Sports shortcut).
        if (sc.id.startsWith("sport:")) {
            sportFollows.firstOrNull { it.shortcutId == sc.id }?.let { sportsVm.setPinned(it, false) }
            toast("${sc.label}: back under Sports")
            return
        }
        val all = LiveShortcuts.candidates(
            movieLibs.orEmpty(), showLibs.orEmpty(), movieMenuRows.rows, showMenuRows.rows, musicOnNow, musicSourceName,
        ).associateBy { it.id }
        val rows = movieMenuRows.rows + showMenuRows.rows
        val shown = liveShortcuts.map { it.first.id }
        val stored = remote.liveShortcuts ?: shown
        remoteVm.setLiveShortcuts(stored.filterNot { id -> id == sc.id || LiveShortcuts.resolve(id, all, rows)?.id == sc.id })
        toast("${sc.label} hidden from your shortcuts")
    }
    /**
     * Hold OK on a shortcut → Refresh: only its rows again. A network: its rows from the addon (films and shows);
     * a row: that row (an addon's on its own; Plex and the rest with their page); Movies / Shows or a library: that page.
     */
    fun refreshOne(sc: LiveShortcut) {
        val parts = sc.id.split(":", limit = 3)
        val type = if (parts.getOrNull(1) == "Show") MediaType.Show else MediaType.Movie
        val vm1 = if (type == MediaType.Show) showsVm else moviesVm
        fun note(text: String) = toast(text)
        note("Refreshing ${sc.label}…")
        when (parts[0]) {
            "set" -> {
                var left = 2
                var got = 0
                listOf(moviesVm to movieMenuRows.rows, showsVm to showMenuRows.rows).forEach { (m, rows) ->
                    m.refreshRows(rows.filter { RowSets.matches(parts[2], it) }.map { it.id }) { n ->
                        got += n; left--
                        if (left == 0) note(if (got > 0) "${sc.label} is up to date" else "${sc.label}: its addon didn't answer, try again in a moment")
                    }
                }
            }
            "row" -> if (parts[2].startsWith("stremio-")) vm1.refreshRows(listOf(parts[2])) { n -> note(if (n > 0) "${sc.label} is up to date" else "${sc.label}: its addon didn't answer, try again in a moment") }
                else { vm1.load(type, force = true); note("${sc.label}: refreshing with the ${if (type == MediaType.Show) "Shows" else "Movies"} page") }
            "home" -> { vm1.load(type, force = true); vm1.loadContinue(type, force = true) }
            "lib" -> vm1.loadLibraries(type, force = true)
            else -> refreshShortcuts()
        }
    }
    /** Hold OK on a shortcut → Move: one place left (-1) or right (1) in the row (saved as the row's new order). */
    fun moveShortcut(sc: LiveShortcut, delta: Int) {
        val shown = liveShortcuts.map { it.first.id }.filter { it != REFRESH_SHORTCUT && !it.startsWith("sport:") }
        val i = shown.indexOf(sc.id)
        val j = i + delta
        if (i < 0 || j !in shown.indices) return
        val all = LiveShortcuts.candidates(
            movieLibs.orEmpty(), showLibs.orEmpty(), movieMenuRows.rows, showMenuRows.rows, musicOnNow, musicSourceName,
        ).associateBy { it.id }
        val rows = movieMenuRows.rows + showMenuRows.rows
        // Chosen ones not showing right now (rows still loading) are kept, after the row.
        val unseen = (remote.liveShortcuts ?: shown).filter { id -> LiveShortcuts.resolve(id, all, rows)?.id !in shown }
        remoteVm.setLiveShortcuts(shown.toMutableList().apply { add(j, removeAt(i)) } + unseen)
    }
    // Choose rows: only when the saved choice changes (each redraw would put back the saved one over a choice just made
    // and not yet written).
    LaunchedEffect(remote.shortcutRows) { LiveShortcutsBridge.chosen = remote.shortcutRows }
    // The film player's shortcuts use these same three (hold OK on one): one row, changed from either player.
    SideEffect {
        LiveShortcutsBridge.hide = { sc -> hideShortcut(sc) }
        // The search button in the film player: search over the page (the film goes to the corner first).
        VodHost.openSearch = { menuOpen = false; channelListOpen = false; searchStart = null; quickSearch = true }
        // Settings → Playback → After a film or episode → Back to live TV: the channel you were watching, full screen.
        VodHost.afterStop = { if (AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.AFTER_FILM) == 1) goLive() }
        LiveShortcutsBridge.setRows = { id, rows -> remoteVm.setShortcutRows(id, rows) }
        LiveShortcutsBridge.move = { sc, d -> moveShortcut(sc, d) }
        LiveShortcutsBridge.refresh = { sc -> refreshOne(sc) }
    }
    // Live TV controls → Down → Down: Continue watching, Recent channels, Recently added (Live TV hub, step 3).
    val continueMovies by moviesVm.continueItems.collectAsStateWithLifecycle()
    val continueShows by showsVm.continueItems.collectAsStateWithLifecycle()
    val recentMovies by moviesVm.recent.collectAsStateWithLifecycle()
    val recentShows by showsVm.recent.collectAsStateWithLifecycle()
    val allChannels by dvr.channels.collectAsStateWithLifecycle()
    // A film or show as a card under the live controls: OK opens its page (the channel carries on in the corner).
    // Continue watching ([resume]): OK carries on (the episode you're up to, or the film where you left it); hold OK
    // for Go to show / Go to season / Play from the start. Elsewhere OK opens its page.
    fun titleCard(item: MediaItem, resume: Boolean = false): OsdPoster {
        val show = item.type == MediaType.Show
        val mvm = if (show) showsVm else moviesVm
        val page = if (show) Page.Shows else Page.Movies
        // (A card on a row: into the title itself, so the channel stops. The shortcuts keep it in the corner.)
        fun open() { osdVisible = false; mvm.open(item); openPage(page, keepLive = false) }
        fun play(how: (MediaItem) -> Unit) { osdVisible = false; how(item); openPage(page, keepLive = false) }
        val carryOn = resume && (item.progress != null || item.nextSeason != null)
        val options = if (!carryOn) emptyList() else listOfNotNull(
            CardOption(if (show) "Go to show" else "Go to film") { open() },
            if (show) CardOption("Go to season" + (item.nextSeason?.let { " $it" } ?: "")) {
                osdVisible = false; showsVm.quickEpisodes(item); openPage(page, keepLive = false)
            } else null,
            CardOption(if (show) "Play this episode from the start" else "Play from the start", plays = true) { play(mvm::quickFromStart) },
        )
        return OsdPoster(
            key = item.key, title = item.title, subtitle = item.subtitle,
            image = item.backdrop ?: item.poster, progress = item.progress,
            options = options, plays = carryOn,
        ) { if (carryOn) play(if (show) showsVm::quickNextUp else moviesVm::quickPlay) else open() }
    }
    // A highlighted shortcut's own content, previewed under it: a network's rows, its row, its page's first rows.
    val movieHomeRows by moviesVm.homeRows.collectAsStateWithLifecycle()
    val showHomeRows by showsVm.homeRows.collectAsStateWithLifecycle()
    // Music and Recordings preview their own: your music's home rows, your recordings.
    val musicHome by musicVm.home.collectAsStateWithLifecycle()
    LaunchedEffect(osdVisible, preloadMusic, musicOnNow) { if ((osdVisible || preloadMusic) && musicOnNow && musicHome.rows.isEmpty()) musicVm.load() }
    // Live now's rows: On now (the event, its channel, until when, how far through) and Starting soon.
    fun liveEventRows(): List<OsdRow> {
        val ev = liveEvents ?: return emptyList()
        val t = System.currentTimeMillis()
        val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEE ", java.util.Locale.UK)
        fun at(ms: Long): String {
            val z = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
            val today = java.time.LocalDate.now()
            return (if (z.toLocalDate() == today) "" else z.format(dayFmt)) + z.format(Dates.time)
        }
        // The event's own picture (the match's, or the guide's), else its sport's icon on its colours; the channel and
        // time under it. (The channel's logo said nothing: every Sky Sports+ feed has the same one.)
        fun card(e: GuideViewModel.LiveEvent, now: Boolean) = OsdPoster(
            key = "ev-${e.channel.id}-${e.program.startTime}", title = e.title,
            subtitle = (if (now) "Until " + at(e.program.endTime) else at(e.program.startTime)) + "  ·  " + e.channel.name,
            image = e.picture ?: if (e.sport == null) channelLogo(session?.serverUrl.orEmpty(), e.channel) else null,
            logo = e.picture == null || e.picture.contains("/badge/"),
            progress = if (now) ((t - e.program.startTime).toFloat() / (e.program.endTime - e.program.startTime).coerceAtLeast(1)).coerceIn(0f, 1f) else null,
            emoji = e.sport?.emoji, tint = e.sport?.colors,
        ) {
            val ch = e.channel
            osdVisible = false
            playback.play(ch, session)
            vm.selectChannel(ch.id)
            fullscreen = true
            showBanner()
        }
        return listOf(
            OsdRow("On now", ev.onNow.map { card(it, true) }),
            OsdRow("Starting soon", ev.soon.map { card(it, false) }),
        ).filter { it.items.isNotEmpty() }
    }
    val computePreview: (String) -> List<OsdRow>? = { id ->
        if (id == "page:Live") liveEventRows() else if (id == "page:Sports") sportsHub.rows(sportFollows) else if (id.startsWith("sport:team:") || id.startsWith("sport:league:")) sportsHub.gamesRows(id, sportFollows) else if (!postersOn) null else if (id == "page:Music" || id.startsWith("music:")) musicHome.rows.map { r ->
            OsdRow(r.title, r.cards.take(20).map { card ->
                OsdPoster(key = "m:" + card.id, title = card.title, subtitle = card.sub, image = card.art?.small) {
                    osdVisible = false
                    musicVm.open(card)
                    openPage(Page.Music, keepLive = false)
                }
            })
        }.filter { it.items.isNotEmpty() } else if (id == "page:Recordings") {
            val chans = dvr.channels.value
            fun card(rec: DvrRecording) = OsdPoster(
                key = "r:${rec.id}", title = rec.title,
                subtitle = listOfNotNull(rec.season?.let { "S$it" }, rec.episode?.let { "E$it" }, Dates.dayTime(rec.start)).joinToString(" · "),
                image = chans[rec.channelId]?.let { channelLogo(session?.serverUrl.orEmpty(), it) }, logo = true,
            ) { osdVisible = false; openPage(Page.Recordings, keepLive = false) }
            listOf(
                OsdRow("Recordings", dvrRecordings
                    .filter { it.state == DvrState.Recorded || it.state == DvrState.Recording }
                    .sortedByDescending { it.start }.take(20).map(::card)),
                OsdRow("Coming up", dvrRecordings
                    .filter { it.state == DvrState.Scheduled }.sortedBy { it.start }.take(20).map(::card)),
            ).filter { it.items.isNotEmpty() }
        } else {
            val parts = id.split(":", limit = 3)
            val show = parts.getOrNull(1) == "Show"
            val menu = if (show) showMenuRows.rows else movieMenuRows.rows
            when (parts[0]) {
                "set" -> menu.filter { RowSets.matches(parts[2], it) } +
                    (if (parts[2].startsWith("name:")) (if (show) movieMenuRows.rows else showMenuRows.rows)
                        .filter { RowSets.matches(parts[2], it) }
                        .map { it.copy(title = it.title + if (show) " (films)" else " (shows)") } else emptyList())
                "row" -> menu.filter { it.id == parts[2] }
                "home" -> (if (show) showHomeRows.rows else movieHomeRows.rows)
                else -> null
            }?.map { r -> OsdRow(r.title, r.items.take(20).map { titleCard(it, resume = r.group == "deck") }) }?.filter { it.items.isNotEmpty() }
        }
    }
    // Each shortcut's rows, built once and kept until something they show changes (they were built again on every
    // redraw of the controls, and in the film player on every key press: hundreds of cards for Sports or Music).
    val previewCache = remember(
        liveEvents, sportFollows, sportsHub.games.toMap(), sportsHub.tv.toMap(), musicHome, dvrRecordings, movieMenuRows, showMenuRows,
        movieHomeRows, showHomeRows, postersOn, session?.serverUrl, now, allChannels,
    ) { HashMap<String, List<OsdRow>?>() }
    val previewFor: (String) -> List<OsdRow>? = { id ->
        if (previewCache.containsKey(id)) previewCache[id] else computePreview(id).also { previewCache[id] = it }
    }

    // Built again only when what's in them changes, not on every redraw of the live TV screen. (What the cards' OK does
    // reads the session as it is when pressed.)
    val sessionNow by sessionRef
    val ownStamp = ownPrograms.entries.sumOf { it.value.first }
    val posterRows: List<OsdRow> = remember(
        postersOn, continueMovies, continueShows, recentMovies, recentShows, remote.recentChannels, playing?.id, allChannels, programs, now, ownStamp, session?.serverUrl,
    ) { if (!postersOn) emptyList() else run {
        // Films and shows taken in turn, so neither crowds out the other.
        fun mix(a: List<MediaItem>, b: List<MediaItem>) =
            (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }.distinctBy { it.key }.take(20)
        val chNow = System.currentTimeMillis()
        listOf(
            OsdRow("Continue watching", mix(continueMovies.orEmpty(), continueShows.orEmpty()).map { titleCard(it, resume = true) }),
            OsdRow("Recent channels", remote.recentChannels.filter { it != playing?.id }.mapNotNull { allChannels[it] }.map { ch ->
                OsdPoster(
                    key = "ch-${ch.id}", title = ch.name,
                    subtitle = GuideViewModel.programAt(programsOf(ch), chNow)?.let { displayTitle(it) },
                    image = channelLogo(session?.serverUrl.orEmpty(), ch), logo = true,
                ) {
                    osdVisible = false
                    playback.play(ch, sessionNow)
                    vm.selectChannel(ch.id)
                    showBanner()
                }
            }),
            OsdRow("Recently added", mix(recentMovies.orEmpty(), recentShows.orEmpty()).map { titleCard(it) }),
        ).filter { it.items.isNotEmpty() }
    } }

    // ---- The corner picture, highlighted (Settings → Playback → Highlight the corner picture): at the page's edge,
    // pressing towards it moves onto it; then Full screen or Close (Left / Right, OK).
    val cornerFocus = remember { FocusRequester() }
    var cornerFocused by remember { mutableStateOf(false) }
    // Set just before moving onto the picture on purpose: otherwise it can't take the highlight (when a page briefly
    // has nothing to highlight, e.g. opening a title, Android handed it to the picture, and it stuck there). A plain
    // holder, read when the focus system asks, not a state (it must count in the same moment it's set).
    val cornerArm = remember { booleanArrayOf(false) }
    val rootFocus = LocalFocusManager.current
    // A film's picture is drawn over everything (search and the TV guide too); live TV's only over another page, and
    // search covers it.
    val filmInCorner = VodHost.active && VodHost.mini
    val cornerReachable = pictureInCorner && (page != Page.Tv || filmInCorner) &&
        !menuOpen && !showAccounts && !pipMenu &&
        AppColors.palette.opt(UiOpts.CORNER_FOCUS) == 0
    LaunchedEffect(pictureInCorner) { if (!pictureInCorner) cornerFocused = false }

    // The film and show players' rows under their shortcuts: the same as live TV's.
    SideEffect {
        LiveShortcutsBridge.previewFor = previewFor
        LiveShortcutsBridge.defaultRows = posterRows
    }
    Box(
        Modifier
            .fillMaxSize()
            // Towards the corner picture with nowhere further to go on the page (the end of a row, the bottom row):
            // onto the picture. Every other move is the page's own, as before.
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !cornerReachable || cornerFocused) return@onKeyEvent false
                val at = AppColors.palette.opt(UiOpts.MINI_CORNER) // 0 BR, 1 BL, 2 TR, 3 TL
                val dir = when (e.key) {
                    Key.DirectionRight -> if (at == 0 || at == 2) FocusDirection.Right else null
                    Key.DirectionLeft -> if (at == 1 || at == 3) FocusDirection.Left else null
                    Key.DirectionDown -> if (at < 2) FocusDirection.Down else null
                    Key.DirectionUp -> if (at >= 2) FocusDirection.Up else null
                    else -> null
                } ?: return@onKeyEvent false
                // (A page that keeps the highlight inside itself, a title page or search, blocks the move at its edge;
                // that counts as handled, so it's asked about too.)
                EdgeBlocked.hit = false
                if (rootFocus.moveFocus(dir) && !EdgeBlocked.hit) return@onKeyEvent true
                cornerArm[0] = true
                EdgeBlocked.lettingOut { runCatching { cornerFocus.requestFocus() }.isSuccess }.also { if (!it) cornerArm[0] = false }
            }
            .onPreviewKeyEvent { e ->
                lastKeyAt = System.currentTimeMillis()
                // "Still watching?" up: any button answers yes.
                if (stillAsk) { if (e.type == KeyEventType.KeyUp) stillAsk = false; return@onPreviewKeyEvent true }
                // A reminder on screen: OK switches to it, Back dismisses it; other buttons work as usual.
                dueReminder?.let { r ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (ok || e.key == Key.Back) {
                        if (e.type == KeyEventType.KeyUp) {
                            dueReminder = null
                            if (ok) dvr.channels.value[r.channelId]?.let { ch ->
                                quickSearch = false
                                menuOpen = false
                                watchChannel(ch, endMultiView = true)
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                // The rest of a held Back that already sent the film to the corner: nothing else (VodKeys).
                if (e.key == Key.Back && VodHost.swallowBack) {
                    if (e.type == KeyEventType.KeyUp) VodHost.swallowBack = false
                    return@onPreviewKeyEvent true
                }
                // Hold Back with a picture in the corner (a film, or live TV over another page): its menu, Full screen /
                // Close picture (before the guide's own hold-Back). A hold is timed here too (some remotes send no
                // repeats); a tap is an ordinary Back.
                val cornerUp = (VodHost.active && VodHost.mini) ||
                    (liveCorner && playing != null && page != Page.Tv)
                if (e.key == Key.Back && (pipBackHeld || (cornerUp && !pipMenu && !showAccounts))) {
                    fun openPipMenu() {
                        pipHoldJob?.cancel(); pipHoldJob = null
                        pipBackHeld = true
                        // The menu (and any settings page) closes: left open under it, it took the next Back presses.
                        menuOpen = false
                        quickSearch = false
                        pipMenu = true
                    }
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && !pipBackHeld -> {
                            pipHoldJob?.cancel()
                            pipHoldJob = fromStartScope.launch { delay(300); pipHoldJob = null; openPipMenu() }
                            return@onPreviewKeyEvent false
                        }
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 && !pipBackHeld -> { openPipMenu(); return@onPreviewKeyEvent true }
                        e.type == KeyEventType.KeyDown && pipBackHeld -> return@onPreviewKeyEvent true
                        // The release of the hold: that press is over (not a Back as well).
                        e.type == KeyEventType.KeyUp && pipBackHeld -> { pipBackHeld = false; VodHost.eatBackRelease = false; return@onPreviewKeyEvent true }
                        // A tap: an ordinary Back.
                        e.type == KeyEventType.KeyUp -> { pipHoldJob?.cancel(); pipHoldJob = null }
                    }
                }
                // Music playing in the bar: hold Back for Now Playing; the remote's media buttons control it.
                val music = MusicPlayer
                // The rest of the held Back that opened Now Playing (before that screen has the remote).
                if (e.key == Key.Back && music.eatBackRelease) {
                    if (e.type == KeyEventType.KeyUp) music.eatBackRelease = false
                    return@onPreviewKeyEvent true
                }
                if (music.active && !music.nowPlayingOpen && !VodHost.active && !backLongPress) {
                    if (e.key == Key.Back && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) {
                        if (e.nativeKeyEvent.repeatCount == 1) { music.eatBackRelease = true; music.nowPlayingOpen = true }
                        return@onPreviewKeyEvent true
                    }
                    if (e.type == KeyEventType.KeyDown) {
                        when (RemoteKeys.action(e)) {
                            RemoteAction.PlayPause -> { music.toggle(); return@onPreviewKeyEvent true }
                            RemoteAction.Stop -> { music.stop(); return@onPreviewKeyEvent true }
                            else -> Unit
                        }
                        when (e.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { music.next(); return@onPreviewKeyEvent true }
                            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { music.previous(); return@onPreviewKeyEvent true }
                        }
                    }
                }
                // The search button (Settings → Remote): pops search up over whatever's on screen.
                if (searchHeld) {
                    if (e.type == KeyEventType.KeyUp) searchHeld = false
                    return@onPreviewKeyEvent true
                }
                // Long Menu (Settings → Remote buttons), when it isn't search there.
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu && e.nativeKeyEvent.repeatCount == 1 && !quickSearch) {
                    tableScreen()?.let { sc -> ButtonMap.changed(remote, sc, BtnPress.MenuHold) }?.let { a ->
                        tableHeld = null
                        searchHeld = true // swallow the rest of this press
                        doButton(tableScreen()!!, a)
                        return@onPreviewKeyEvent true
                    }
                }
                // (Watching a film or show too: it goes to the corner and carries on while you search.)
                if (!quickSearch && !showAccounts && page != Page.Search && e.type == KeyEventType.KeyDown) {
                    val hit = SearchKey.hit(e, remote)
                    if (hit) {
                        VodHost.minimise()
                        menuOpen = false
                        channelListOpen = false
                        searchStart = null
                        quickSearch = true
                        searchHeld = true // swallow the rest of this press
                        tableHeld = null
                        return@onPreviewKeyEvent true
                    }
                }
                // Settings → Remote buttons: the rest of a changed button's press.
                tableHeld?.let { held ->
                    if (ButtonMap.pressOf(e) == held) {
                        // Held: channels and skips keep going, as their own buttons do.
                        if (e.type == KeyEventType.KeyDown && holdTap == null) tableScreen()?.let { sc -> ButtonMap.changed(remote, sc, held) }
                            ?.takeIf { it in setOf(BtnAction.NextChannel, BtnAction.PrevChannel, BtnAction.SkipBack, BtnAction.SkipForward, BtnAction.PageUp, BtnAction.PageDown, BtnAction.Earlier, BtnAction.Later) }
                            ?.let { doButton(tableScreen()!!, it) }
                        if (e.type == KeyEventType.KeyUp) {
                            tableHeld = null
                            // Let go before the long press: the tap (its usual job too, as it was held back).
                            if (holdTap == held) {
                                holdJob[0]?.cancel()
                                holdTap = null
                                tableScreen()?.let { sc -> doButton(sc, ButtonMap.action(remote, sc, held)) }
                            }
                            // Menu acts when it's let go (holding it is search, above).
                            if (held == BtnPress.Menu) tableScreen()?.let { sc -> ButtonMap.changed(remote, sc, held)?.let { doButton(sc, it) } }
                        }
                        return@onPreviewKeyEvent true
                    }
                    tableHeld = null
                }
                // Settings → Remote buttons on the guide, full-screen live TV and the pages: buttons changed from standard
                // (colours, Record, Info, the media and channel buttons, Menu). OK, Back and their holds are taken
                // where they're handled (the guide's onSelect / onLongSelect, the Back handlers, holdBack()).
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    val press = ButtonMap.pressOf(e)
                    val screen = tableScreen()
                    // (Stop works with the controls, the channel list or multi-view open too.)
                    val free = !channelListOpen && !osdVisible && !(fullscreen && multiView)
                    if (press != null && screen != null && press !in setOf(BtnPress.OkHold, BtnPress.Back, BtnPress.BackHold) &&
                        (free || press == BtnPress.Stop)
                    ) {
                        // With a long press too: wait to see which (OK in the guide times its own hold).
                        val long = ButtonMap.longOf[press]?.takeIf { !(screen == BtnScreen.Guide && press == BtnPress.Ok) }?.let { ButtonMap.changed(remote, screen, it) }
                        if (long != null) {
                            tableHeld = press
                            holdTap = press
                            holdJob[0]?.cancel()
                            holdJob[0] = fromStartScope.launch {
                                delay(450)
                                holdTap = null
                                tableScreen()?.let { doButton(it, long) }
                            }
                            return@onPreviewKeyEvent true
                        }
                        val act = ButtonMap.changed(remote, screen, press)
                        if (act != null) {
                            tableHeld = press
                            if (press != BtnPress.Menu) doButton(screen, act)
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                // Remotes with Guide / Record buttons (e.g. Formuler): the TV guide from anywhere, and recording what's on.
                if (!showAccounts && recordTarget == null && infoTarget == null && e.type == KeyEventType.KeyDown) {
                    when (RemoteKeys.action(e)) {
                        RemoteAction.Guide -> {
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                quickSearch = false
                                menuOpen = false
                                channelListOpen = false
                                osdVisible = false
                                MusicPlayer.nowPlayingOpen = false
                                // A film full screen stops (picture-in-picture for films is off for now).
                                if (VodHost.covering) VodHost.close()
                                if (page == Page.Tv) { fullscreen = false; multiView = false } else openPage(Page.Tv)
                            }
                            return@onPreviewKeyEvent true
                        }
                        // Record on live TV: the programme's options (record it, its series, start over…), as holding
                        // OK in the guide. The guide handles Record on the highlighted programme itself.
                        RemoteAction.Record -> {
                            val ch = playing
                            if (!quickSearch && page == Page.Tv && fullscreen && !multiView && catchup == null && ch != null &&
                                !VodHost.covering
                            ) {
                                if (e.nativeKeyEvent.repeatCount == 0) {
                                    GuideViewModel.programAt(programsOf(ch), now)?.let { prog ->
                                        osdVisible = false
                                        channelListOpen = false
                                        recordTarget = ch to prog
                                    }
                                }
                                return@onPreviewKeyEvent true
                            }
                        }
                        else -> Unit
                    }
                }
                if (quickSearch) return@onPreviewKeyEvent false
                if (e.key == Key.Back) {
                    // A hold is timed here (0.25 s) rather than waiting for the remote's repeats, which can take 0.6 s
                    // to arrive (Projectivy holds Back back first). Safe for taps: a tap's press and release arrive
                    // together (Projectivy passes it on once it's let go). The repeats below are the fallback.
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && backHoldJob == null && !backLongPress && !cornerUp) {
                        backHoldJob = fromStartScope.launch {
                            delay(250)
                            backHoldJob = null
                            if (holdBack()) backLongPress = true
                        }
                    }
                    if (e.type == KeyEventType.KeyUp) { backHoldJob?.cancel(); backHoldJob = null }
                    when {
                        // (Holds are decided by the timer above, through the button table: holdBack().)
                        e.type == KeyEventType.KeyDown && backLongPress -> return@onPreviewKeyEvent true
                        // Swallow the release too, or it would also count as a normal Back.
                        e.type == KeyEventType.KeyUp && backLongPress -> { backLongPress = false; return@onPreviewKeyEvent true }
                    }
                }
                // Number keys type a channel number on the TV page (guide or full screen); OK tunes straight away.
                if (page != Page.Tv || menuOpen || showAccounts || quickSearch || recordTarget != null || infoTarget != null || session == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                val digit = when (code) {
                    in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> code - android.view.KeyEvent.KEYCODE_0
                    in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> code - android.view.KeyEvent.KEYCODE_NUMPAD_0
                    else -> null
                }
                when {
                    digit != null -> {
                        if (digits.length < 5) digits += digit
                        digitsMessage = null; digitsToken = System.nanoTime()
                        true
                    }
                    digits.isNotEmpty() && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                        tuneTyped()
                        true
                    }
                    digits.isNotEmpty() && e.key == Key.Back -> {
                        digits = ""
                        true
                    }
                    else -> false
                }
            },
    ) {
        // The menu over a page (as TiviMate): the page and the preview pushed right beside the see-through strip,
        // sliding along together as it opens and grows (Left: the main list too).
        val guidePush = animateIntAsState(
            if (menuOpen && !fullscreen && !liveBehind && !menuFullPage && !VodHost.behind) menuWidth else 0,
            tween(160, easing = FastOutSlowInEasing),
            label = "guidePush",
        )
        // (The menu over a full-screen film: only the menu shows, the page stays out of sight underneath.)
        Box(Modifier.fillMaxSize().offset { IntOffset(guidePush.value, 0) }.graphicsLayer { alpha = if (VodHost.behind) 0f else 1f }) {
        if (page == Page.Search) {
            SearchPage(
                guide = vm,
                serverUrl = session?.serverUrl.orEmpty(),
                focusEnabled = !menuOpen && !cornerFocused && !showAccounts && !quickSearch && infoTarget == null && recordTarget == null && seasonPick == null,
                onProgramme = { ch, prog -> recordTarget = ch to prog },
                onCatchup = { ch, prog ->
                    watchChannel(ch, catchup = prog)
                },
                onOpenMenu = { menuOpen = true },
                onExit = ::backOnPage,
                onMusic = { card -> musicVm.openFromSearch(card); openPage(Page.Music) },
                onChannel = { ch ->
                    watchChannel(ch)
                },
            )
        } else if (page == Page.Music) {
            MusicScreen(
                focusRequester = mediaFocus,
                focusEnabled = !menuOpen && !cornerFocused && !showAccounts && !quickSearch && !VodHost.covering && !MusicPlayer.nowPlayingOpen,
                onOpenMenu = { menuOpen = true },
                onBack = ::backOnPage,
                onSetUp = { showAccounts = true },
            )
        } else if (page == Page.Recordings) {
            RecordingsScreen(
                vm = dvr,
                focusRequester = mediaFocus,
                focusEnabled = !menuOpen && !cornerFocused && !showAccounts && !quickSearch && !VodHost.covering,
                onBack = ::backOnPage,
                onPlay = { recordingPlayback = it },
                onOpenMenu = { menuOpen = true },
            )
        } else if (page != Page.Tv) CompositionLocalProvider(LocalQuickSearchOpen provides quickSearch) {
            MediaScreen(
                type = if (page == Page.Movies) MediaType.Movie else MediaType.Show,
                cornerHasFocus = cornerFocused,
                focusRequester = mediaFocus,
                focusEnabled = !menuOpen && !cornerFocused && !showAccounts && !quickSearch,
                onOpenMenu = { menuOpen = true },
                onBack = {
                    if (VodHost.active && VodHost.mini) { VodHost.expand(); return@MediaScreen }
                    // The channel in the corner: back to it. Otherwise your Back setting (not always back to live TV).
                    if (liveCorner && playing != null && goLive()) return@MediaScreen
                    // Over live TV: back to the channel full screen.
                    if (liveBehind && playing != null) { backOnPage(); return@MediaScreen }
                    // The channel in the preview place: back to the guide.
                    if (livePreview && playing != null) { backOnPage(); return@MediaScreen }
                    when (remote.mediaBack) {
                        MediaBack.Exit -> backOnPage()
                        MediaBack.Menu -> menuOpen = true
                        MediaBack.Guide -> openPage(Page.Tv)
                    }
                },
            )
        } else if (session == null) {
            NoLiveTv(
                focusRequester = guideFocus,
                focusEnabled = !menuOpen && !cornerFocused && !showAccounts && !quickSearch,
                onSetUp = { showAccounts = true },
                onOpenMenu = { menuOpen = true },
            )
        } else {
            GuideScreen(
                // A film in the corner: past the last (or first) channel, onto it (as at a page's edge).
                onPastEnd = { up ->
                    val at = AppColors.palette.opt(UiOpts.MINI_CORNER)
                    if (!cornerReachable || up != (at >= 2)) false else {
                        cornerArm[0] = true
                        EdgeBlocked.lettingOut { runCatching { cornerFocus.requestFocus() }.isSuccess }.also { if (!it) cornerArm[0] = false }
                    }
                },
                vm = vm,
                recordings = dvrRecordings,
                playingChannelId = playing?.id,
                playingCatchupRowId = catchup?.rowId,
                serverUrl = session?.serverUrl.orEmpty(),
                focusRequester = guideFocus,
                focusEnabled = !menuOpen && !cornerFocused && !fullscreen && !showAccounts && !quickSearch,
                onOpenMenu = { menuOpen = true },
                onSelect = { channel, program ->
                    // Settings → Remote buttons → TV guide → OK: "Watch in the preview" stays in the guide (OK again on
                    // what's playing: full screen).
                    val inGuide = ButtonMap.action(remote, BtnScreen.Guide, BtnPress.Ok) == BtnAction.WatchInGuide
                    if (!inGuide) ButtonMap.changed(remote, BtnScreen.Guide, BtnPress.Ok)?.let { doButton(BtnScreen.Guide, it); return@GuideScreen }
                    val t = System.currentTimeMillis()
                    val live = program == null || (program.startTime <= t && program.endTime > t)
                    val replayable = program != null && program.endTime <= t && channel.catchupDays > 0 &&
                        program.startTime >= t - channel.catchupDays * 86_400_000L
                    when {
                        live -> {
                            val alreadyLive = playing?.id == channel.id && catchup == null && playState !is PlaybackState.Error
                            if (!alreadyLive) playback.play(channel, session)
                            if (!inGuide || alreadyLive) { fullscreen = true; showBanner() }
                        }
                        replayable -> {
                            val already = catchup?.rowId == program!!.rowId && playing?.id == channel.id
                            if (!already) playback.playCatchup(channel, program, session)
                            if (!inGuide || already) { fullscreen = true; showBanner() }
                        }
                        // A programme still to come: show its details (Record options from there).
                        program != null && program.startTime > t ->
                            // Already set to record: straight to cancelling it. Otherwise the details, with record / remind.
                            if (dvr.recordingFor(channel.id, program) != null) recordTarget = channel to program
                            else infoTarget = channel to program
                    }
                },
                // Holding OK: the programme's options (record / remind, watch it from your library or addons), whether
                // it's on now, still to come or already aired.
                onLongSelect = { channel, program ->
                    ButtonMap.changed(remote, BtnScreen.Guide, BtnPress.OkHold)?.let { doButton(BtnScreen.Guide, it); return@GuideScreen }
                    if (program != null) recordTarget = channel to program
                },
            )
        }
        }

        // Live TV into the guide's preview and back: a glide (the window's real place and size; the picture is a
        // SurfaceView, scaled by the box's display hardware). Settings → Playback → Picture-in-picture → Live TV animation.
        val liveShrink = animateFloatAsState(
            if (fullscreen || liveBehind) 0f else 1f,
            tween(300, easing = FastOutSlowInEasing),
            label = "live",
        )
        // Over another page (a shortcut, the Live TV hub): from the guide's preview place to the chosen corner.
        val inCorner = liveCorner && page != Page.Tv
        val liveSide = animateFloatAsState(
            if (inCorner) 1f else 0f,
            tween(300, easing = FastOutSlowInEasing),
            label = "liveCorner",
        )
        val cornerSize = CornerPicture.size()
        val cornerAt = AppColors.palette.opt(UiOpts.MINI_CORNER) // 0 BR, 1 BL, 2 TR, 3 TL
        val channel = playing.takeIf { page == Page.Tv || inCorner || liveBehind || livePreview }
        // The live channel list: on its own over full-screen live TV, or as the menu strip's last column.
        val channelPanel: @Composable (Boolean, () -> Unit) -> Unit = { embedded, onLeft ->
            channel?.let { ch0 ->
                ChannelListOverlay(
                    vm = vm,
                    serverUrl = session?.serverUrl.orEmpty(),
                    current = ch0,
                    now = now,
                    onPick = { ch ->
                        channelListOpen = false; menuOpen = false
                        // Another channel, or the same number on another source (after switching source).
                        if (ch.id != ch0.id || ch.uuid != ch0.uuid) {
                            playback.play(ch, session)
                            vm.selectChannel(ch.id)
                        }
                        showBanner()
                    },
                    onClose = { channelListOpen = false },
                    onCatchup = { ch, prog ->
                        channelListOpen = false; menuOpen = false
                        playback.playCatchup(ch, prog, session)
                        vm.selectChannel(ch.id)
                        showBanner()
                    },
                    onFuture = { ch, prog -> channelListOpen = false; menuOpen = false; recordTarget = ch to prog },
                    onLeft = onLeft,
                    embedded = embedded,
                )
            }
        }
        val list = channel?.let { programsOf(it) }.orEmpty()
        val current = catchup ?: GuideViewModel.programAt(list, now)
        if (channel != null && !(multiView && fullscreen)) {
            val next = if (catchup != null) null else current?.let { c -> list.firstOrNull { it.startTime >= c.endTime } }
            PlayerLayer(
                vm = playback,
                fullscreen = fullscreen,
                bannerVisible = bannerVisible && !osdVisible && !inCorner && !liveBehind && !livePreview,
                current = current,
                next = next,
                now = now,
                serverUrl = session?.serverUrl.orEmpty(),
                // Read in the layout and draw steps only, so the glide doesn't rebuild the screen every frame.
                // (A library over live TV: the picture drawn first, under the page, so the see-through page is on top.)
                modifier = Modifier.zIndex(if (liveBehind) -1f else 0f).fillMaxSize()
                    .layout { measurable, constraints ->
                        val p = liveShrink.value
                        val q = liveSide.value
                        val fw = constraints.maxWidth
                        val fh = constraints.maxHeight
                        // The small window: the guide's preview (q 0) or the corner (q 1), and in between on the way.
                        val sw = 320.dp.roundToPx() + (cornerSize.first.roundToPx() - 320.dp.roundToPx()) * q
                        val sh = 180.dp.roundToPx() + (cornerSize.second.roundToPx() - 180.dp.roundToPx()) * q
                        val margin = 32.dp.roundToPx()
                        val cx = if (cornerAt == 1 || cornerAt == 3) margin.toFloat() else fw - margin - sw
                        val cy = if (cornerAt >= 2) margin.toFloat() else fh - margin - sh
                        val sx = 12.dp.roundToPx() + (cx - 12.dp.roundToPx()) * q
                        val sy = 20.dp.roundToPx() + (cy - 20.dp.roundToPx()) * q
                        val cw = (fw + (sw - fw) * p).toInt().coerceAtLeast(1)
                        val ch = (fh + (sh - fh) * p).toInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(cw, ch))
                        layout(fw, fh) { placeable.place((sx * p).toInt() + (guidePush.value * p * (1 - q)).toInt(), (sy * p).toInt()) }
                    }
                    .graphicsLayer { clip = liveShrink.value > 0.99f; shape = RoundedCornerShape(8.dp) },
            )
        }

        if (fullscreen && multiView && channel != null) {
            MultiView(
                vm = playback,
                programFor = { ch -> GuideViewModel.programAt(programsOf(ch), now) },
                now = now,
                onExit = { ch ->
                    multiView = false
                    if (ch.id != playback.channel.value?.id) {
                        playback.play(ch, session)
                        vm.selectChannel(ch.id)
                    }
                    showBanner()
                },
                channelPicker = { cur, onPick, onClose ->
                    Box(Modifier.fillMaxSize()) {
                        ChannelListOverlay(
                            vm = vm,
                            serverUrl = session?.serverUrl.orEmpty(),
                            current = cur,
                            now = now,
                            onPick = onPick,
                            onClose = onClose,
                            // A programme from the list's programme panel: that channel, live.
                            onCatchup = { ch, _ -> onPick(ch) },
                        )
                    }
                },
            )
        } else if (fullscreen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(playerFocus)
                    .focusable(enabled = !osdVisible && !channelListOpen && !transparencyOpen)
                    .onPreviewKeyEvent { e ->
                        if (osdVisible || channelListOpen) return@onPreviewKeyEvent false
                        // Menu: a tap opens audio & subtitles when it's let go. Holding it is search (handled above),
                        // so acting on the press opened audio as well.
                        val action = RemoteKeys.action(e)
                        if (action == RemoteAction.Menu) {
                            if (liveMenuKey.feed(e) == Press.Tap) {
                                if (playState is PlaybackState.Error) playback.retry() else showOsd(audio = true)
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (e.type != KeyEventType.KeyDown) {
                            if (action == RemoteAction.Left) rewindHeld = false
                            return@onPreviewKeyEvent false
                        }
                        if (action != RemoteAction.Left) rewindAsk = 0L
                        // Settings → Remote buttons → Live TV → Left / Right, as TiviMate: Left the channel list, Right
                        // back to the channel watched before (catch-up still moves through the programme).
                        if (remote.liveSides == LiveSides.ListAndLast && catchup == null && !multiView) {
                            if (action == RemoteAction.Left) {
                                if (e.nativeKeyEvent.repeatCount == 0) channelListOpen = true
                                return@onPreviewKeyEvent true
                            }
                            if (action == RemoteAction.Right) {
                                if (e.nativeKeyEvent.repeatCount == 0) lastChannel()
                                return@onPreviewKeyEvent true
                            }
                        }
                        when (action) {
                            // Live: ask first. In catch-up (already behind live) Left steps back straight away.
                            RemoteAction.Left -> if (catchup == null) {
                                val ch = playing
                                val again = rewindAsk != 0L && System.currentTimeMillis() - rewindAsk < 4_000
                                when {
                                    rewindHeld -> Unit
                                    ch == null || ch.catchupDays <= 0 -> { toast("This channel has no catch-up to rewind") }
                                    again || e.nativeKeyEvent.repeatCount > 0 -> {
                                        rewindAsk = 0L
                                        rewindHeld = e.nativeKeyEvent.repeatCount > 0
                                        playback.seekBy(-remote.seek.ms)
                                        showBanner()
                                    }
                                    else -> rewindAsk = System.currentTimeMillis()
                                }
                            } else {
                                playback.seekBy(-remote.seek.ms)
                                showBanner()
                            }
                            RemoteAction.Up, RemoteAction.Down -> when (remote.zap) {
                                ZapDirection.ChannelList -> channelListOpen = true
                                ZapDirection.UpNext -> zap(if (action == RemoteAction.Up) 1 else -1)
                                ZapDirection.UpPrevious -> zap(if (action == RemoteAction.Up) -1 else 1)
                            }
                            RemoteAction.ChannelDown -> zap(-1)
                            RemoteAction.ChannelUp -> zap(1)
                            RemoteAction.Ok -> when {
                                playState is PlaybackState.Error -> playback.retry()
                                remote.playerOk == PlayerOk.Banner -> showBanner()
                                remote.playerOk == PlayerOk.ChannelList && !multiView -> channelListOpen = true
                                else -> showOsd()
                            }
                            RemoteAction.PlayPause -> {
                                playback.togglePause()
                                showBanner()
                            }
                            // The remote's own rewind button means it: no question.
                            RemoteAction.Rewind -> {
                                rewindAsk = 0L
                                playback.seekBy(-remote.seek.ms)
                                showBanner()
                            }
                            RemoteAction.Right, RemoteAction.FastForward -> {
                                playback.seekBy(remote.seek.ms)
                                showBanner()
                            }
                            RemoteAction.Info -> showBanner()
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
            )
            // The channel list on its own; Left opens the menu strip beside it (groups, then the main list).
            if (channelListOpen && channel != null && !menuOpen) Box(Modifier.fillMaxSize()) {
                channelPanel(false) { menuOpen = true; menuOnMain = false }
            }
            if (osdVisible && channel != null) {
                PlayerOsd(
                    vm = playback,
                    channel = channel,
                    current = current,
                    now = now,
                    serverUrl = session?.serverUrl.orEmpty(),
                    onGuide = {
                        osdVisible = false
                        fullscreen = false
                    },
                    onClose = { osdVisible = false },
                    onActivity = { osdUsedAt[0] = System.currentTimeMillis() },
                    // Low-memory mode: no multi-view (up to four players at once).
                    // Settings over live TV: the menu opens on Settings; Back closes it, back to the channel.
                    onSettings = { osdVisible = false; menuOnSettings = true; menuOpen = true },
                    onTransparency = { osdVisible = false; transparencyOpen = true },
                    // No shortcut row under the buttons any more: every library and service is in the menu on the left.
                    shortcuts = emptyList(),
                    posterRows = emptyList(),
                    previewFor = previewFor,
                    onHideShortcut = { sc -> hideShortcut(sc) },
                    onMoveShortcut = { sc, d -> moveShortcut(sc, d) },
                    onRefreshShortcut = { sc -> refreshOne(sc) },
                    onStop = { doButton(BtnScreen.Live, BtnAction.Stop) },
                    onMultiView = if (!AppColors.palette.heavyExtras) null else ({
                        osdVisible = false
                        if (catchup != null) playback.goLive()
                        multiView = true
                    }),
                    openAudio = osdAudio,
                    // Recording: only for people allowed to record (Dispatcharr dvr_access; admins always).
                    onRecord = current?.takeIf { catchup == null && Access.record }?.let { prog ->
                        {
                            osdVisible = false
                            recordTarget = channel to prog
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // "Rewind?" after Left on full-screen live TV: gone after 4 s, or as soon as anything else happens.
        LaunchedEffect(rewindAsk) { if (rewindAsk != 0L) { delay(4_000); rewindAsk = 0L } }
        LaunchedEffect(fullscreen, osdVisible, playing?.id, page) { rewindAsk = 0L }
        AnimatedVisibility(
            rewindAsk != 0L && fullscreen && page == Page.Tv,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
            enter = androidx.compose.animation.EnterTransition.None, exit = androidx.compose.animation.ExitTransition.None,
        ) {
            Row(
                Modifier.background(AppColors.Panel.copy(alpha = 0.94f), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Replay, null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                Text("Rewind?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 10.dp))
                Text("   Press ◀ again, or hold ◀", fontSize = 13.sp, color = AppColors.TextSecondary)
            }
        }
        // What the buttons do here (Settings → Appearance → Button hints), following the button settings.
        if (!menuOpen && !quickSearch && !showAccounts && recordTarget == null && infoTarget == null && !VodHost.active) {
            val holdBackGuide = ButtonMap.action(remote, BtnScreen.Guide, BtnPress.BackHold).label.lowercase()
            when {
                page == Page.Tv && fullscreen && !osdVisible && !channelListOpen && !multiView -> ButtonHint(
                    "live", "OK: controls  ·  ▲ ▼ channels  ·  Back: guide  ·  hold Back: " + ButtonMap.action(remote, BtnScreen.Live, BtnPress.BackHold).label.lowercase())
                page == Page.Tv && !fullscreen && session != null -> ButtonHint(
                    "guide", "OK: watch  ·  ◀ ▶ earlier / later  ·  Back: " + (when { guideBack == GuideBack.Menu -> "menu"; guideBack == GuideBack.FullScreen && playing != null -> "full screen"; else -> "exit" }) + "  ·  hold Back: $holdBackGuide")
                page == Page.Movies || page == Page.Shows -> ButtonHint(
                    "media", "OK: open  ·  hold OK: options  ·  ◀ menu  ·  hold Back: " + ButtonMap.action(remote, BtnScreen.Pages, BtnPress.BackHold).label.lowercase())
                else -> Unit
            }
        }
        // Guide and wallpaper dimming only show on the guide: the panel switches the view as you move to them.
        if (transparencyOpen && page == Page.Tv) TransparencyPanel(
            onClose = { transparencyOpen = false; if (playing != null) fullscreen = true },
            onRow = { r -> if (playing != null) fullscreen = r == 0 },
        )
        // Going right into a page (a library, a service, a channel group's guide): the menu's glass slides back out to the
        // left with the page and the preview moving back, rather than vanishing while they move.
        if (!menuOpen && guidePush.value > 0 && !fullscreen && !liveBehind) Box(
            Modifier.fillMaxHeight()
                .offset { IntOffset(guidePush.value - menuWidth, 0) }
                .layout { m, c -> val pl = m.measure(Constraints.fixed(menuWidth, c.maxHeight)); layout(menuWidth, c.maxHeight) { pl.place(0, 0) } }
                .background(AppColors.MenuPanel),
        )
        if (menuOpen) Box(Modifier.fillMaxSize().offset { IntOffset(if (!fullscreen && !liveBehind && !menuFullPage && !VodHost.behind) guidePush.value - menuWidth else 0, 0) }) {
            MenuOverlay(
                onPlayRecording = { menuOpen = false; recordingPlayback = it },
                // See-through everywhere: the page is pushed aside beside it (or video shows through).
                seeThrough = true,
                vm = vm,
                session = session,
                page = page,
                onOpenPage = ::openPage,
                onAccounts = {
                    menuOpen = false
                    showAccounts = true
                    accountsFromMenu = true
                },
                onSettings = menuOnSettings,
                onMain = menuOnMain,
                onSearchFor = { q -> searchStart = q; searchVoice = false; openPage(Page.Search) },
                onVoiceSearch = { searchStart = null; searchVoice = true; openPage(Page.Search) },
                onClose = { menuOpen = false; menuOnSettings = false; menuOnMain = false },
                backOut = menuFromGuideBack,
                onBackOut = {
                    menuOpen = false; menuOnSettings = false; menuOnMain = false
                    if (page == Page.Tv && !fullscreen) exitArmed = System.currentTimeMillis()
                },
                onSignOut = {
                    playback.stop()
                    onSignOut()
                },
                channelsPanel = if (fullscreen && channelListOpen && channel != null) ({ onLeft -> channelPanel(true, onLeft) }) else null,
                // A group picked: back to its channels, the channel list on its own.
                onGroupPicked = { menuOpen = false; menuOnMain = false },
                onWidth = { menuWidth = it },
                previewOn = playing != null && !fullscreen && !liveBehind && (page == Page.Tv || livePreview),
                onFullPage = { menuFullPage = it },
                // Live TV, Movies, Shows, Music, Recordings: the page behind changes as you move onto it (not from over
                // full-screen video).
                onHoverPage = { p ->
                    if (!fullscreen && !liveBehind && !VodHost.active) {
                        // Back on Movies / Shows from a service: the page's own rows again, not the service's.
                        if (p == Page.Movies && moviesVm.currentRowSet.value != null) { moviesVm.openLibrary(null, MediaType.Movie); moviesVm.jumpTo(null) }
                        if (p == Page.Shows && showsVm.currentRowSet.value != null) { showsVm.openLibrary(null, MediaType.Show); showsVm.jumpTo(null) }
                        if (p != page) openPage(p, closeMenu = false)
                    }
                },
                services = menuServices,
                liveEntries = listOf("Live now", "Sports"),
                onLiveEntry = { label, into -> if (into || (!fullscreen && !liveBehind && !VodHost.active)) openLiveEntry(label, closeMenu = into) },
                // Moving onto a service: its page behind (not over full-screen video); Right / OK: into it.
                onService = { name, rowId, into -> if (into || (!fullscreen && !liveBehind && !VodHost.active)) openService(name, closeMenu = into, rowId = rowId) },
            )
        }

        if (showAccounts) {
            // Back from Accounts & sources (opened from Settings): back on Settings in the menu, like the other settings pages.
            SetupWizard(mode = SetupMode.Manage, onClose = {
                showAccounts = false
                if (accountsFromMenu) { accountsFromMenu = false; menuOnSettings = true; menuOpen = true }
            })
        }

        // Search popped up from any screen: the same search page, on top; Back closes it.
        if (quickSearch) {
            BackHandler { quickSearch = false; searchStart = null }
            // Solid: the screen underneath showing through between results (guide text, times) looked like a glitch.
            // The remote stays inside search until it closes.
            // Live TV in the corner: a window cut in search where its picture is, so it shows (the video's drawn
            // behind the app; a film's picture is drawn over everything anyway).
            val liveHole = cornerNow && !filmInCorner
            Box(Modifier.fillMaxSize().then(if (liveHole) Modifier.cornerWindow() else Modifier).appBackground().then(if (infoTarget == null && recordTarget == null) Modifier.trapFocus() else Modifier)) {
                SearchPage(
                    guide = vm,
                    serverUrl = session?.serverUrl.orEmpty(),
                    // (Not switched off while the corner picture has the highlight: search's box, results and buttons
                    // would all stop being reachable, and coming back off the picture had nowhere to land. It never
                    // pulls the highlight back on its own, so it needn't stand aside.)
                    focusEnabled = infoTarget == null && recordTarget == null && seasonPick == null,
                    openKeyboard = searchStart == null && !searchVoice,
                    startQuery = searchStart,
                    startVoice = searchVoice,
                    startCatchup = searchCatchup,
                    onProgramme = { ch, prog -> recordTarget = ch to prog },
                    onCatchup = { ch, prog ->
                        quickSearch = false
                        searchStart = null
                        watchChannel(ch, catchup = prog, endMultiView = true)
                    },
                    // Left at the edge stays in search (it used to close it and open the menu); Back is the way out.
                    onOpenMenu = {},
                    onExit = { quickSearch = false; searchStart = null; searchCatchup = false },
                    onMusic = { card -> quickSearch = false; searchStart = null; musicVm.openFromSearch(card); openPage(Page.Music) },
                    onChannel = { ch ->
                        quickSearch = false
                        watchChannel(ch, endMultiView = true)
                    },
                )
            }
        }

        // The picture in the corner: its highlight (Full screen / Close) and, holding Back, its menu (CornerPicture.kt).
        fun cornerFull() { if (filmInCorner) VodHost.expand() else goLive() }
        fun cornerClose() { if (filmInCorner) VodHost.close() else { liveCorner = false; playback.stop() } }
        if (pictureInCorner && (page != Page.Tv || filmInCorner)) CornerHighlight(
            film = filmInCorner, focusRequester = cornerFocus, arm = cornerArm, reachable = cornerReachable, focused = cornerFocused,
            onFocused = { cornerFocused = it }, onFullScreen = ::cornerFull, onClose = ::cornerClose,
            // (Nothing above it to land on: the page's own starting point, or search's.)
            onLeave = { runCatching { if (page == Page.Tv) guideFocus.requestFocus() else mediaFocus.requestFocus() } },
        )
        if (pipMenu) CornerPictureMenu(
            film = filmInCorner, onFullScreen = { pipMenu = false; cornerFull() }, onClosePicture = { pipMenu = false; cornerClose() },
            onDismiss = { pipMenu = false },
        )

        infoTarget?.let { (ch, prog) ->
            ProgramInfoDialog(ch, prog, dvr, onRecord = { infoTarget = null; recordTarget = ch to prog }, onClose = { infoTarget = null })
        }
        // Recording started / finished / failed: bottom-right, a few seconds, no buttons.
        dvrNote?.let { text ->
            Text(
                text, fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp).width(420.dp)
                    .glass(10.dp, if (text.startsWith("⚠")) Color(0xFFE5534B) else null)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        // No free provider connection for the channel just tuned: say what's using them.
        if (page == Page.Tv) connectionNote?.let { text ->
            val recordingNow = dvrRecordings.firstOrNull { it.state == DvrState.Recording }
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 28.dp).width(640.dp)
                    .glass(10.dp, Color(0xFFE5A23B))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text("All connections in use", fontSize = 13.sp, color = Color(0xFFE5A23B))
                Text(text, fontSize = 15.sp, color = AppColors.TextPrimary)
                recordingNow?.let { Text("Recording now: ${it.title}", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        dueReminder?.let { r ->
            Column(
                Modifier.align(Alignment.TopEnd).padding(28.dp).width(380.dp)
                    .glass(12.dp)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text("⏰  Starting now", fontSize = 13.sp, color = AppColors.Accent)
                Text(r.title, fontSize = 18.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(r.channelName, fontSize = 14.sp, color = AppColors.TextSecondary)
                Text("OK: watch  ·  Back: dismiss", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // Search opens on the title (so there's something to pick if it isn't matched), then the title's page opens:
        // playing it from the start, or (Go to show) on its episodes where you're up to.
        fun openTitle(ch: ChannelListItem, prog: GuideProgram, play: Boolean) {
            searchStart = prog.title.removePrefix("New: ")
            fullscreen = false
            quickSearch = true
            // Films in the guide usually end their description with the year, e.g. "(2014)".
            val year = prog.description?.let { Regex("\\((19|20)\\d{2}\\)").findAll(it).lastOrNull()?.value?.trim('(', ')')?.toIntOrNull() }
            fromStartScope.launch {
                if (play) searchMedia.watchFromStart(prog.title, prog.season, prog.episode, year)
                else searchMedia.goToTitle(prog.title, prog.season, prog.episode, year)
            }
        }
        seasonPick?.let { m -> SeasonPicker(m, onClose = { seasonPick = null }) }
        if (seasonPick == null) recordTarget?.let { (ch, prog) ->
            RecordDialog(ch, prog, dvr, onDetails = { recordTarget = null; infoTarget = ch to prog },
                onChooseSeasons = { m -> recordTarget = null; seasonPick = m },
                onWatchFromStart = {
                    recordTarget = null
                    val t = System.currentTimeMillis()
                    // Catch-up first: the channel's own replay of this very showing.
                    if (ch.catchupDays > 0 && prog.startTime < t && prog.startTime >= t - ch.catchupDays * 86_400_000L) {
                        playback.playCatchup(ch, prog, session)
                        vm.selectChannel(ch.id)
                    } else openTitle(ch, prog, play = true)
                },
                onGoTo = { recordTarget = null; openTitle(ch, prog, play = false) },
                // Only when some channel has catch-up (nothing to find otherwise).
                onSearchCatchup = if (vm.channels.value.none { it.catchupDays > 0 }) null else ({
                    recordTarget = null
                    searchStart = prog.title
                    searchVoice = false
                    searchCatchup = true
                    quickSearch = true
                }),
            ) { recordTarget = null }
        }

        if (stillAsk) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(560.dp).glass(14.dp).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Still watching?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text("Press any button to carry on. Live TV stops in a minute otherwise.", fontSize = 15.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
            }
        }

        if (exitArmed != 0L && !menuOpen) {
            Text(
                "Press Back again to exit",
                fontSize = 18.sp,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .glass(24.dp)
                    .padding(horizontal = 26.dp, vertical = 12.dp),
            )
        }

        (digits.takeIf { it.isNotEmpty() } ?: digitsMessage)?.let { text ->
            Text(
                text,
                fontSize = if (digits.isNotEmpty()) 44.sp else 22.sp,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 40.dp)
                    .glass(10.dp)
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun NoLiveTv(focusRequester: FocusRequester, focusEnabled: Boolean, onSetUp: () -> Unit, onOpenMenu: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Live TV isn't set up", fontSize = 26.sp, color = AppColors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text("Connect your Dispatcharr server to watch channels and use the TV guide.", fontSize = 15.sp, color = AppColors.TextSecondary)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(220.dp).focusRequester(focusRequester)) {
                if (focusEnabled) MenuItem(text = "Set up Live TV", selected = false, onClick = onSetUp)
            }
            Box(Modifier.width(160.dp)) { MenuItem(text = "Menu", selected = false, onClick = onOpenMenu) }
        }
    }
}
