package io.github.petebutty.viewarr.ui.home

import io.github.petebutty.viewarr.ui.theme.appBackground

import io.github.petebutty.viewarr.ui.common.slideInFromLeft
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.local.GroupWithCount
import io.github.petebutty.viewarr.data.repository.SyncState
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.guide.GuideScreen
import io.github.petebutty.viewarr.ui.setup.SetupMode
import io.github.petebutty.viewarr.ui.setup.SetupWizard
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.media.MediaScreen
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.media.VodPlayer
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
import io.github.petebutty.viewarr.data.settings.MenuOpensOn
import io.github.petebutty.viewarr.data.settings.PlayerOk
import io.github.petebutty.viewarr.data.settings.ZapDirection
import io.github.petebutty.viewarr.data.settings.StartPage
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEEK_MS = 30_000L

private enum class Section(val label: String, val available: Boolean) {
    Tv("Live TV", true),
    Movies("Movies", true),
    Shows("Shows", true),
    Music("Music", true),
    Recordings("Recordings", true),
    Search("Search", true),
    Settings("Settings", true),
}

private val LogoFont = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(io.github.petebutty.viewarr.R.font.poppins_bold))

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
    var seasonPick by remember { mutableStateOf<io.github.petebutty.viewarr.ui.dvr.GuideMatch?>(null) }
    var recordingPlayback by remember { mutableStateOf<VodStream?>(null) }
    // Recordings play in the same player as films (VodHost, above every page), so they can shrink to a corner too.
    val recordingFlow = remember { kotlinx.coroutines.flow.MutableStateFlow<VodStream?>(null) }
    val recordingSource = remember { io.github.petebutty.viewarr.ui.media.VodSource(recordingFlow, kotlinx.coroutines.flow.MutableStateFlow(null), onClose = { recordingFlow.value = null }) }
    LaunchedEffect(recordingPlayback) {
        recordingPlayback?.let { recordingFlow.value = it; io.github.petebutty.viewarr.ui.media.VodHost.show(recordingSource); recordingPlayback = null }
    }
    var infoTarget by remember { mutableStateOf<Pair<ChannelListItem, GuideProgram>?>(null) }
    // Search popped up over the current screen (Settings → Remote → Search button); Back closes it.
    var quickSearch by remember { mutableStateOf(false) }
    var searchHeld by remember { mutableStateOf(false) }
    // "Remind me": the programme starting now pops up in the corner; OK watches it, Back dismisses.
    var dueReminder by remember { mutableStateOf<io.github.petebutty.viewarr.data.dvr.Reminder?>(null) }
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
                    r.state == io.github.petebutty.viewarr.data.dvr.DvrState.Failed -> "⚠  Recording failed: ${r.title}$ch"
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
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    val remote = LocalRemote.current
    var page by rememberSaveable { mutableStateOf(Page.Tv) }
    // Movies & Shows switched off (live TV only): don't leave a hidden page on screen.
    LaunchedEffect(AppColors.palette.hideMedia) {
        if (AppColors.palette.hideMedia && (page == Page.Movies || page == Page.Shows)) page = Page.Tv
    }
    val mediaFocus = remember { FocusRequester() }
    val musicVm: io.github.petebutty.viewarr.ui.music.MusicViewModel = hiltViewModel()
    // Channel number typed on the remote's number keys.
    var digits by remember { mutableStateOf("") }
    // Up/Down while watching: a see-through channel list over the picture.
    var channelListOpen by remember { mutableStateOf(false) }
    // Long-press Back on the guide returns to the channel that's playing, full screen.
    var backLongPress by remember { mutableStateOf(false) }
    // The timer that turns a Back press into a hold (see the root key handler).
    var backHoldJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pipBackHeld by remember { mutableStateOf(false) }
    // Start page from Settings, applied once when the app opens.
    var startApplied by rememberSaveable { mutableStateOf(false) }
    var digitsToken by remember { mutableLongStateOf(0L) }
    var digitsMessage by remember { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // Live TV hub: the channel carries on in a corner over another page (Settings → Playback → Live TV).
    var liveCorner by remember { mutableStateOf(false) }
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
    val liveMenuKey = remember { io.github.petebutty.viewarr.ui.common.TapHold() }
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
    LaunchedEffect(io.github.petebutty.viewarr.ui.media.VodHost.mini) {
        if (!io.github.petebutty.viewarr.ui.media.VodHost.mini && io.github.petebutty.viewarr.ui.media.VodHost.active) { menuOpen = false; quickSearch = false }
    }
    // Held Back with nothing playing: the menu opens on its main list (not the page's own list).
    var menuOnMain by remember { mutableStateOf(false) }
    val searchMedia: MediaViewModel = hiltViewModel(key = "media-search")
    val fromStartScope = androidx.compose.runtime.rememberCoroutineScope()
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
    val ownPrograms = remember { androidx.compose.runtime.mutableStateMapOf<Int, Pair<Long, List<GuideProgram>>>() }
    val ownScope = androidx.compose.runtime.rememberCoroutineScope()
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

    /** A press from the button table (Settings → Remote → Buttons) on the guide, live TV or a page. */
    fun doButton(screen: io.github.petebutty.viewarr.data.settings.BtnScreen, act: io.github.petebutty.viewarr.data.settings.BtnAction) {
        val ch = playing
        when (act) {
            io.github.petebutty.viewarr.data.settings.BtnAction.Nothing -> Unit
            io.github.petebutty.viewarr.data.settings.BtnAction.GroupsMenu -> { fullscreen = false; channelListOpen = false; menuOnMain = false; menuOpen = true }
            io.github.petebutty.viewarr.data.settings.BtnAction.MainMenu -> { fullscreen = false; channelListOpen = false; menuOnMain = true; menuOpen = true }
            io.github.petebutty.viewarr.data.settings.BtnAction.FullScreen -> if (ch != null) { fullscreen = true; showBanner() }
            io.github.petebutty.viewarr.data.settings.BtnAction.Guide -> { fullscreen = false; channelListOpen = false }
            io.github.petebutty.viewarr.data.settings.BtnAction.Now -> vm.goToNow()
            io.github.petebutty.viewarr.data.settings.BtnAction.DayBack -> vm.jumpBy(-86_400_000L)
            io.github.petebutty.viewarr.data.settings.BtnAction.DayForward -> vm.jumpBy(86_400_000L)
            io.github.petebutty.viewarr.data.settings.BtnAction.RecordOptions, io.github.petebutty.viewarr.data.settings.BtnAction.RecordNow -> {
                // Live TV: what's on now; the guide: the highlighted programme (as holding OK there).
                val target = if (screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Live) ch?.let { c -> GuideViewModel.programAt(programsOf(c), now)?.let { c to it } }
                else {
                    val cur = vm.cursor.value
                    vm.channels.value.getOrNull(cur.row)?.let { c -> GuideViewModel.programAt(programsOf(c), cur.focusTime)?.let { c to it } }
                }
                if (target != null && catchup == null) {
                    val (tc, tp) = target
                    val straight = act == io.github.petebutty.viewarr.data.settings.BtnAction.RecordNow && tp.endTime > now && dvr.recordingFor(tc.id, tp) == null
                    when {
                        // One press: scheduled, with a short note. (Already set: the options, so it can be cancelled.)
                        straight && io.github.petebutty.viewarr.data.profiles.Access.record -> {
                            dvr.record(tc, tp)
                            digitsMessage = (if (tp.startTime <= now) "⏺ Recording from now: " else "⏺ Recording set: ") + tp.title
                            digitsToken = System.nanoTime()
                        }
                        straight -> { digitsMessage = "Recording isn't allowed for this profile"; digitsToken = System.nanoTime() }
                        else -> { osdVisible = false; channelListOpen = false; recordTarget = target }
                    }
                }
            }
            io.github.petebutty.viewarr.data.settings.BtnAction.ChannelList -> if (fullscreen) channelListOpen = true
            io.github.petebutty.viewarr.data.settings.BtnAction.StartOver -> if (ch != null && ch.catchupDays > 0) {
                GuideViewModel.programAt(programsOf(ch), now)?.takeIf { it.startTime < now }?.let { prog ->
                    playback.playCatchup(ch, prog, session)
                    vm.selectChannel(ch.id)
                    showBanner()
                }
            }
            io.github.petebutty.viewarr.data.settings.BtnAction.Banner -> showBanner()
            io.github.petebutty.viewarr.data.settings.BtnAction.AudioSubs -> showOsd(audio = true)
            io.github.petebutty.viewarr.data.settings.BtnAction.Search -> { channelListOpen = false; searchStart = null; quickSearch = true }
            // Live TV stops (catch-up too): the guide with nothing playing, where Back asks to exit.
            io.github.petebutty.viewarr.data.settings.BtnAction.Stop -> if (ch != null || catchup != null) {
                playback.stop()
                osdVisible = false
                channelListOpen = false
                multiView = false
                fullscreen = false
                bannerVisible = false
            }
            // Films only (VodKeys).
            io.github.petebutty.viewarr.data.settings.BtnAction.Details, io.github.petebutty.viewarr.data.settings.BtnAction.PlayPause -> Unit
        }
    }

    /**
     * Back held (timed in the root key handler): the guide's menu on its channel groups, or with nothing playing the
     * menu. False when a hold means nothing here (it's then left to whoever else uses one: music, a film).
     */
    fun holdBack(): Boolean {
        if (menuOpen || showAccounts || quickSearch || recordTarget != null || infoTarget != null) return false
        if (io.github.petebutty.viewarr.ui.media.VodHost.active) return false
        val music = io.github.petebutty.viewarr.playback.MusicPlayer
        if (music.active && !music.nowPlayingOpen) return false
        val screen = when {
            page == Page.Tv && fullscreen -> if (osdVisible || channelListOpen || multiView || transparencyOpen) return false else io.github.petebutty.viewarr.data.settings.BtnScreen.Live
            page == Page.Tv -> io.github.petebutty.viewarr.data.settings.BtnScreen.Guide
            playing == null -> io.github.petebutty.viewarr.data.settings.BtnScreen.Pages
            else -> return false
        }
        // Settings → Remote → Buttons: "Nothing" still takes the hold (it isn't also a tap of Back).
        var act = io.github.petebutty.viewarr.data.settings.ButtonMap.action(remote, screen, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold)
        // The guide must always have a way to the menu: with Left never opening it and Back not either, hold Back does.
        if (screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Guide && remote.guideLeft == io.github.petebutty.viewarr.data.settings.GuideLeft.AlwaysPast && remote.guideBack != GuideBack.Menu &&
            act !in setOf(io.github.petebutty.viewarr.data.settings.BtnAction.GroupsMenu, io.github.petebutty.viewarr.data.settings.BtnAction.MainMenu)
        ) act = io.github.petebutty.viewarr.data.settings.BtnAction.GroupsMenu
        doButton(screen, act)
        return true
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
        val start = if (remote.navStyle == io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub) StartPage.LastChannel else remote.startPage
        when (start) {
            StartPage.Guide -> Unit
            StartPage.Movies -> page = Page.Movies
            StartPage.Shows -> page = Page.Shows
            StartPage.Search -> quickSearch = true
            StartPage.Recordings -> page = Page.Recordings
            StartPage.LastChannel -> {
                val ch = remote.lastChannelId?.let { id -> vm.channelById(id) }
                if (ch != null && session != null) vm.reveal(ch) {
                    playback.play(ch, session)
                    vm.selectChannel(ch.id)
                    fullscreen = true
                    showBanner()
                }
            }
        }
    }
    LaunchedEffect(menuOpen, fullscreen, osdVisible, transparencyOpen, showAccounts, page, recordTarget == null, seasonPick == null, io.github.petebutty.viewarr.ui.media.VodHost.covering, io.github.petebutty.viewarr.ui.media.VodHost.gliding, infoTarget == null, channelListOpen, quickSearch, io.github.petebutty.viewarr.playback.MusicPlayer.nowPlayingOpen) {
        if (channelListOpen || quickSearch || transparencyOpen || io.github.petebutty.viewarr.playback.MusicPlayer.nowPlayingOpen) return@LaunchedEffect
        if (recordTarget != null || seasonPick != null || io.github.petebutty.viewarr.ui.media.VodHost.covering || infoTarget != null) return@LaunchedEffect
        // A film gliding into the corner: the page takes the remote once it's there (its highlight starting mid-glide
        // made the glide stutter).
        if (io.github.petebutty.viewarr.ui.media.VodHost.gliding) return@LaunchedEffect
        // Just landed in the corner: a moment before the page takes the remote, so its highlight and the player
        // letting go aren't drawn in the same frame (that frame was slow).
        if (io.github.petebutty.viewarr.ui.media.VodHost.mini) delay(150)
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
    val vodOver = io.github.petebutty.viewarr.ui.media.VodHost.active
    LaunchedEffect(vodOver) {
        if (vodOver) { fullscreen = false; liveCorner = false; playback.close() }
    }
    // …and a channel started while a film is in the corner ends the film (one stream, one sound).
    LaunchedEffect(playing?.id) { if (playing != null) { io.github.petebutty.viewarr.ui.media.VodHost.close(); io.github.petebutty.viewarr.playback.MusicPlayer.stop() } }
    // Music starting ends Live TV (as a film does).
    val musicOn = io.github.petebutty.viewarr.playback.MusicPlayer.active
    LaunchedEffect(musicOn) { if (musicOn) { fullscreen = false; liveCorner = false; playback.close() } }
    // A full-screen trailer (title page → Trailer) ends live TV too; it was silent with the channel still playing.
    val trailerOn = io.github.petebutty.viewarr.ui.media.FullTrailer.active
    LaunchedEffect(trailerOn) { if (trailerOn) { fullscreen = false; liveCorner = false; playback.close() } }
    // Back on the guide (or the channel stopped): nothing in the corner any more.
    LaunchedEffect(page, playing == null) { if (page == Page.Tv || playing == null) liveCorner = false }
    val cornerNow = liveCorner && playing != null && page != Page.Tv
    androidx.compose.runtime.SideEffect { io.github.petebutty.viewarr.ui.player.LiveCorner.on = cornerNow }
    // Settings → Playback → Still watching?: after hours without a button press on live TV, ask, then stop (a TV left
    // on otherwise holds a provider connection all night).
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var stillAsk by remember { mutableStateOf(false) }
    val stillHours = io.github.petebutty.viewarr.ui.theme.AppColors.palette.stillWatchingHours
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
    LaunchedEffect(menuOpen) { if (!menuOpen) menuFromGuideBack = false }
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
            vm.reveal(ch) {
                page = Page.Tv
                if (playing?.id != ch.id) playback.play(ch, s)
                vm.selectChannel(ch.id)
                fullscreen = true
                showBanner()
            }
        }
        return true
    }

    fun backOnPage() {
        // A film opened a shortcut and is in the corner: Back takes it full screen again (as live TV does).
        if (io.github.petebutty.viewarr.ui.media.VodHost.active && io.github.petebutty.viewarr.ui.media.VodHost.mini) { io.github.petebutty.viewarr.ui.media.VodHost.expand(); return }
        // Live TV hub (Settings → Remote & navigation → Style): Back from a page goes back to the channel.
        // Also whenever the channel is still playing in the corner: Back takes it full screen again.
        if ((remote.navStyle == io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub || (liveCorner && playing != null)) &&
            page != Page.Tv && goLive()) return
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
        fullscreen = false
        bannerVisible = false
        guideFromFullAt = System.currentTimeMillis()
    }

    fun tuneTyped() {
        val n = digits.toIntOrNull() ?: return
        digits = ""
        vm.findByNumber(n) { ch ->
            if (ch == null) {
                digitsMessage = "No channel $n"
                digitsToken = System.nanoTime()
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
    val moviesVm: io.github.petebutty.viewarr.ui.media.MediaViewModel = hiltViewModel(key = "media-Movie")
    val showsVm: io.github.petebutty.viewarr.ui.media.MediaViewModel = hiltViewModel(key = "media-Show")
    LaunchedEffect(Unit) { moviesVm.refreshPlexServers(); delay(20_000); moviesVm.refreshWatchNext() }
    val deepLink by io.github.petebutty.viewarr.data.media.DeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink) {
        val item = deepLink ?: return@LaunchedEffect
        io.github.petebutty.viewarr.data.media.DeepLink.pending.value = null
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
    fun openPage(p: Page, keepLive: Boolean = remote.navStyle == io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub) {
        // Search never replaces what's on screen: it slides over it, and Back returns you there.
        if (p == Page.Search) {
            menuOpen = false
            quickSearch = true
            return
        }
        if (p != Page.Tv && page == Page.Tv) {
            val pal = io.github.petebutty.viewarr.ui.theme.AppColors.palette
            // (Only in the Live TV hub style.)
            if (keepLive && remote.navStyle == io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub && playing != null && !multiView &&
                pal.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.LIVE_CORNER) == 0 && !pal.lowMemory) {
                liveCorner = true
                bannerVisible = false
                osdVisible = false
            } else playback.stop()
            fullscreen = false
        }
        page = p
        menuOpen = false
    }
    // Live TV controls → Down: your shortcuts (Settings → Remote & navigation → Live TV shortcuts).
    val movieLibs by moviesVm.libraries.collectAsStateWithLifecycle()
    val showLibs by showsVm.libraries.collectAsStateWithLifecycle()
    val movieMenuRows by moviesVm.menuRows.collectAsStateWithLifecycle()
    val showMenuRows by showsVm.menuRows.collectAsStateWithLifecycle()
    val musicOnNow by musicVm.enabled.collectAsStateWithLifecycle()
    // Settings → Remote & navigation → Live TV shortcuts → Poster rows (off in Low memory mode: no pictures).
    val postersOn = AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.OSD_POSTERS) == 0 && !AppColors.palette.lowMemory
    LaunchedEffect(osdVisible) {
        if (!osdVisible) return@LaunchedEffect
        moviesVm.loadLibraries(MediaType.Movie); showsVm.loadLibraries(MediaType.Show)
        // Row and network shortcuts come from the pages' rows (Plex rows, AIOMetadata's networks): load them too.
        moviesVm.load(MediaType.Movie); showsVm.load(MediaType.Show)
        if (postersOn) {
            moviesVm.loadContinue(MediaType.Movie); showsVm.loadContinue(MediaType.Show)
            moviesVm.loadRecent(MediaType.Movie); showsVm.loadRecent(MediaType.Show)
        }
    }
    // The music service in use (its logo on the music shortcuts).
    val musicSources by musicVm.sources.collectAsStateWithLifecycle()
    val musicSourceName = musicSources.firstOrNull { it.on }?.name
    // Live now (the hub's shortcut): the event channels' programmes, looked at while the live TV controls are up.
    var liveEvents by remember { mutableStateOf<GuideViewModel.LiveEvents?>(null) }
    LaunchedEffect(osdVisible, remote.navStyle) {
        if (!osdVisible || remote.navStyle != io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub) return@LaunchedEffect
        while (true) {
            liveEvents = runCatching { vm.liveEvents() }.getOrNull() ?: liveEvents
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
     * The Refresh shortcut: every row again from your addons (AIOMetadata, AIOStreams…), Plex, Jellyfin and Emby, with
     * the libraries and Continue watching, so the shortcuts and their previews show what's there now.
     */
    fun refreshShortcuts() {
        digitsMessage = "Refreshing your shortcuts…"
        digitsToken = System.nanoTime()
        listOf(moviesVm to MediaType.Movie, showsVm to MediaType.Show).forEach { (m, t) ->
            m.load(t, force = true); m.loadLibraries(t, force = true); m.loadContinue(t, force = true)
        }
        fromStartScope.launch {
            val done = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                moviesVm.menuRows.first { it.done }; showsVm.menuRows.first { it.done }
            }
            digitsMessage = if (done != null) "Shortcuts up to date" else "Some addons are slow to answer: their rows will appear when they do"
            digitsToken = System.nanoTime()
        }
    }
    val liveShortcuts: List<Pair<io.github.petebutty.viewarr.ui.player.LiveShortcut, () -> Unit>> = run {
        val all = io.github.petebutty.viewarr.ui.player.LiveShortcuts.candidates(
            movieLibs.orEmpty(), showLibs.orEmpty(), movieMenuRows.rows, showMenuRows.rows, musicOnNow, musicSourceName,
        ).associateBy { it.id }
        val rowsForIds = movieMenuRows.rows + showMenuRows.rows
        val saved = (remote.liveShortcuts ?: io.github.petebutty.viewarr.ui.player.LiveShortcuts.defaults)
            .mapNotNull { io.github.petebutty.viewarr.ui.player.LiveShortcuts.resolve(it, all, rowsForIds) }.distinctBy { it.id }
        // Nothing usable saved (everything gone, or not loaded yet): the defaults rather than an empty row.
        val use = saved.ifEmpty { io.github.petebutty.viewarr.ui.player.LiveShortcuts.defaults.mapNotNull { all[it] } }
        use.map { it.id }.mapNotNull { id ->
            val sc = all[id] ?: return@mapNotNull null
            sc to {
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
                            .filter { io.github.petebutty.viewarr.ui.player.RowSets.matches(parts[2], it) }
                            .map { it.copy(title = it.title + if (type == MediaType.Show) " (films)" else " (shows)") }
                        mvm.showRowSet(parts[2], extra)
                        openPage(target, keepLive = true)
                    }
                    // Music: straight into Albums, Artists, Playlists… (the channel carries on in the corner).
                    "music" -> {
                        runCatching { io.github.petebutty.viewarr.ui.music.MusicBrowseKind.valueOf(parts[1]) }.getOrNull()?.let { musicVm.openBrowse(it) }
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
            io.github.petebutty.viewarr.ui.player.LiveShortcut(REFRESH_SHORTCUT, "Refresh", io.github.petebutty.viewarr.ui.common.AppIcons.Refresh,
                colors = io.github.petebutty.viewarr.ui.player.ShortcutColours.settings) to { refreshShortcuts() },
        )
    }

    // Network / service shortcuts: their logos (TMDB), looked up when the controls first open.
    LaunchedEffect(osdVisible, liveShortcuts.map { it.first.id }) {
        if (!osdVisible) return@LaunchedEffect
        val names = liveShortcuts.mapNotNull { (sc, _) -> sc.logoName?.let { n -> n to sc } }
            .filter { (n, _) -> n !in io.github.petebutty.viewarr.ui.player.ShortcutLogos.map }
        if (names.isEmpty()) return@LaunchedEffect
        // A show from its rows tells TMDB which network it is (networks aren't watch providers).
        moviesVm.loadShortcutLogos(names.associate { (n, _) ->
            n to showMenuRows.rows.firstOrNull { io.github.petebutty.viewarr.ui.player.RowSets.name(it) == n }?.items?.firstOrNull()
        })
    }
    // The same shortcuts in the film and show players' controls.
    androidx.compose.runtime.SideEffect {
        io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge.items = liveShortcuts
        io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge.openSettings = { settingsFromFilm = true; menuOnSettings = true; menuOpen = true }
        io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge.openGuide = { menuOpen = false; quickSearch = false; if (page != Page.Tv) openPage(Page.Tv) else fullscreen = false }
    }
    // Settings opened from a film: closing it brings the film back full screen.
    LaunchedEffect(menuOpen) {
        if (!menuOpen && settingsFromFilm) {
            settingsFromFilm = false
            if (io.github.petebutty.viewarr.ui.media.VodHost.active && io.github.petebutty.viewarr.ui.media.VodHost.mini) io.github.petebutty.viewarr.ui.media.VodHost.expand()
        }
    }
    // Hold OK on a shortcut: off the row (saved ids that stand for it too), with a note on getting it back.
    val remoteVm: io.github.petebutty.viewarr.ui.settings.RemoteViewModel = hiltViewModel()
    fun hideShortcut(sc: io.github.petebutty.viewarr.ui.player.LiveShortcut) {
        val all = io.github.petebutty.viewarr.ui.player.LiveShortcuts.candidates(
            movieLibs.orEmpty(), showLibs.orEmpty(), movieMenuRows.rows, showMenuRows.rows, musicOnNow, musicSourceName,
        ).associateBy { it.id }
        val rows = movieMenuRows.rows + showMenuRows.rows
        val shown = liveShortcuts.map { it.first.id }
        val stored = remote.liveShortcuts ?: shown
        remoteVm.setLiveShortcuts(stored.filterNot { id -> id == sc.id || io.github.petebutty.viewarr.ui.player.LiveShortcuts.resolve(id, all, rows)?.id == sc.id })
        digitsMessage = "${sc.label} hidden from your shortcuts"
        digitsToken = System.nanoTime()
    }
    /**
     * Hold OK on a shortcut → Refresh: only its rows again. A network: its rows from the addon (films and shows);
     * a row: that row (an addon's on its own; Plex and the rest with their page); Movies / Shows or a library: that page.
     */
    fun refreshOne(sc: io.github.petebutty.viewarr.ui.player.LiveShortcut) {
        val parts = sc.id.split(":", limit = 3)
        val type = if (parts.getOrNull(1) == "Show") MediaType.Show else MediaType.Movie
        val vm1 = if (type == MediaType.Show) showsVm else moviesVm
        fun note(text: String) { digitsMessage = text; digitsToken = System.nanoTime() }
        note("Refreshing ${sc.label}…")
        when (parts[0]) {
            "set" -> {
                var left = 2
                var got = 0
                listOf(moviesVm to movieMenuRows.rows, showsVm to showMenuRows.rows).forEach { (m, rows) ->
                    m.refreshRows(rows.filter { io.github.petebutty.viewarr.ui.player.RowSets.matches(parts[2], it) }.map { it.id }) { n ->
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
    fun moveShortcut(sc: io.github.petebutty.viewarr.ui.player.LiveShortcut, delta: Int) {
        val shown = liveShortcuts.map { it.first.id }.filter { it != REFRESH_SHORTCUT }
        val i = shown.indexOf(sc.id)
        val j = i + delta
        if (i < 0 || j !in shown.indices) return
        val all = io.github.petebutty.viewarr.ui.player.LiveShortcuts.candidates(
            movieLibs.orEmpty(), showLibs.orEmpty(), movieMenuRows.rows, showMenuRows.rows, musicOnNow, musicSourceName,
        ).associateBy { it.id }
        val rows = movieMenuRows.rows + showMenuRows.rows
        // Chosen ones not showing right now (rows still loading) are kept, after the row.
        val unseen = (remote.liveShortcuts ?: shown).filter { id -> io.github.petebutty.viewarr.ui.player.LiveShortcuts.resolve(id, all, rows)?.id !in shown }
        remoteVm.setLiveShortcuts(shown.toMutableList().apply { add(j, removeAt(i)) } + unseen)
    }
    // The film player's shortcuts use these same three (hold OK on one): one row, changed from either player.
    androidx.compose.runtime.SideEffect {
        io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge.hide = { sc -> hideShortcut(sc) }
        io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge.move = { sc, d -> moveShortcut(sc, d) }
        io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge.refresh = { sc -> refreshOne(sc) }
    }
    // Live TV controls → Down → Down: Continue watching, Recent channels, Recently added (Live TV hub, step 3).
    val continueMovies by moviesVm.continueItems.collectAsStateWithLifecycle()
    val continueShows by showsVm.continueItems.collectAsStateWithLifecycle()
    val recentMovies by moviesVm.recent.collectAsStateWithLifecycle()
    val recentShows by showsVm.recent.collectAsStateWithLifecycle()
    val allChannels by dvr.channels.collectAsStateWithLifecycle()
    // A film or show as a card under the live controls: OK opens its page (the channel carries on in the corner).
    fun titleCard(item: io.github.petebutty.viewarr.data.media.MediaItem) = io.github.petebutty.viewarr.ui.player.OsdPoster(
        key = item.key, title = item.title, subtitle = item.subtitle,
        image = item.backdrop ?: item.poster, progress = item.progress,
    ) {
        osdVisible = false
        val show = item.type == MediaType.Show
        (if (show) showsVm else moviesVm).open(item)
        openPage(if (show) Page.Shows else Page.Movies, keepLive = true)
    }
    // A highlighted shortcut's own content, previewed under it: a network's rows, its row, its page's first rows.
    val movieHomeRows by moviesVm.rows.collectAsStateWithLifecycle()
    val showHomeRows by showsVm.rows.collectAsStateWithLifecycle()
    // Music and Recordings preview their own: your music's home rows, your recordings.
    val musicHome by musicVm.home.collectAsStateWithLifecycle()
    LaunchedEffect(osdVisible, musicOnNow) { if (osdVisible && musicOnNow && musicHome.rows.isEmpty()) musicVm.load() }
    // Live now's rows: On now (the event, its channel, until when, how far through) and Starting soon.
    fun liveEventRows(): List<io.github.petebutty.viewarr.ui.player.OsdRow> {
        val ev = liveEvents ?: return emptyList()
        val t = System.currentTimeMillis()
        val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEE ", java.util.Locale.UK)
        fun at(ms: Long): String {
            val z = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
            val today = java.time.LocalDate.now()
            return (if (z.toLocalDate() == today) "" else z.format(dayFmt)) + z.format(io.github.petebutty.viewarr.ui.theme.Dates.time)
        }
        // The event's own picture (the match's, or the guide's), else its sport's icon on its colours; the channel and
        // time under it. (The channel's logo said nothing: every Sky Sports+ feed has the same one.)
        fun card(e: GuideViewModel.LiveEvent, now: Boolean) = io.github.petebutty.viewarr.ui.player.OsdPoster(
            key = "ev-${e.channel.id}-${e.program.startTime}", title = e.title,
            subtitle = (if (now) "Until " + at(e.program.endTime) else at(e.program.startTime)) + "  ·  " + e.channel.name,
            image = e.picture ?: if (e.sport == null) io.github.petebutty.viewarr.ui.guide.channelLogo(session?.serverUrl.orEmpty(), e.channel) else null,
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
            io.github.petebutty.viewarr.ui.player.OsdRow("On now", ev.onNow.map { card(it, true) }),
            io.github.petebutty.viewarr.ui.player.OsdRow("Starting soon", ev.soon.map { card(it, false) }),
        ).filter { it.items.isNotEmpty() }
    }
    val previewFor: (String) -> List<io.github.petebutty.viewarr.ui.player.OsdRow>? = { id ->
        if (id == "page:Live") liveEventRows() else if (!postersOn) null else if (id == "page:Music" || id.startsWith("music:")) musicHome.rows.take(4).map { r ->
            io.github.petebutty.viewarr.ui.player.OsdRow(r.title, r.cards.take(20).map { card ->
                io.github.petebutty.viewarr.ui.player.OsdPoster(key = "m:" + card.id, title = card.title, subtitle = card.sub, image = card.art?.small) {
                    osdVisible = false
                    musicVm.open(card)
                    openPage(Page.Music, keepLive = true)
                }
            })
        }.filter { it.items.isNotEmpty() } else if (id == "page:Recordings") {
            val chans = dvr.channels.value
            fun card(rec: io.github.petebutty.viewarr.data.dvr.DvrRecording) = io.github.petebutty.viewarr.ui.player.OsdPoster(
                key = "r:${rec.id}", title = rec.title,
                subtitle = listOfNotNull(rec.season?.let { "S$it" }, rec.episode?.let { "E$it" }, io.github.petebutty.viewarr.ui.theme.Dates.dayTime(rec.start)).joinToString(" · "),
                image = chans[rec.channelId]?.let { io.github.petebutty.viewarr.ui.guide.channelLogo(session?.serverUrl.orEmpty(), it) }, logo = true,
            ) { osdVisible = false; openPage(Page.Recordings, keepLive = true) }
            listOf(
                io.github.petebutty.viewarr.ui.player.OsdRow("Recordings", dvrRecordings
                    .filter { it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recorded || it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recording }
                    .sortedByDescending { it.start }.take(20).map(::card)),
                io.github.petebutty.viewarr.ui.player.OsdRow("Coming up", dvrRecordings
                    .filter { it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Scheduled }.sortedBy { it.start }.take(20).map(::card)),
            ).filter { it.items.isNotEmpty() }
        } else {
            val parts = id.split(":", limit = 3)
            val show = parts.getOrNull(1) == "Show"
            val menu = if (show) showMenuRows.rows else movieMenuRows.rows
            when (parts[0]) {
                "set" -> menu.filter { io.github.petebutty.viewarr.ui.player.RowSets.matches(parts[2], it) } +
                    (if (parts[2].startsWith("name:")) (if (show) movieMenuRows.rows else showMenuRows.rows)
                        .filter { io.github.petebutty.viewarr.ui.player.RowSets.matches(parts[2], it) }
                        .map { it.copy(title = it.title + if (show) " (films)" else " (shows)") } else emptyList())
                "row" -> menu.filter { it.id == parts[2] }
                "home" -> (if (show) showHomeRows.rows else movieHomeRows.rows).take(4)
                else -> null
            }?.map { r -> io.github.petebutty.viewarr.ui.player.OsdRow(r.title, r.items.take(20).map(::titleCard)) }?.filter { it.items.isNotEmpty() }
        }
    }
    val posterRows: List<io.github.petebutty.viewarr.ui.player.OsdRow> = if (!postersOn) emptyList() else run {
        // Films and shows taken in turn, so neither crowds out the other.
        fun mix(a: List<io.github.petebutty.viewarr.data.media.MediaItem>, b: List<io.github.petebutty.viewarr.data.media.MediaItem>) =
            (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }.distinctBy { it.key }.take(20)
        val chNow = System.currentTimeMillis()
        listOf(
            io.github.petebutty.viewarr.ui.player.OsdRow("Continue watching", mix(continueMovies.orEmpty(), continueShows.orEmpty()).map(::titleCard)),
            io.github.petebutty.viewarr.ui.player.OsdRow("Recent channels", remote.recentChannels.filter { it != playing?.id }.mapNotNull { allChannels[it] }.map { ch ->
                io.github.petebutty.viewarr.ui.player.OsdPoster(
                    key = "ch-${ch.id}", title = ch.name,
                    subtitle = GuideViewModel.programAt(programsOf(ch), chNow)?.let { io.github.petebutty.viewarr.ui.guide.displayTitle(it) },
                    image = io.github.petebutty.viewarr.ui.guide.channelLogo(session?.serverUrl.orEmpty(), ch), logo = true,
                ) {
                    osdVisible = false
                    playback.play(ch, session)
                    vm.selectChannel(ch.id)
                    showBanner()
                }
            }),
            io.github.petebutty.viewarr.ui.player.OsdRow("Recently added", mix(recentMovies.orEmpty(), recentShows.orEmpty()).map(::titleCard)),
        ).filter { it.items.isNotEmpty() }
    }

    // The film and show players' rows under their shortcuts: the same as live TV's.
    androidx.compose.runtime.SideEffect {
        io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge.previewFor = previewFor
        io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge.defaultRows = posterRows
    }
    Box(
        Modifier
            .fillMaxSize()
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
                                vm.reveal(ch) {
                                    page = Page.Tv
                                    playback.play(ch, session)
                                    vm.selectChannel(ch.id)
                                    fullscreen = true
                                    multiView = false
                                    showBanner()
                                }
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                // The rest of a held Back that already sent the film to the corner: nothing else (VodKeys).
                if (e.key == Key.Back && io.github.petebutty.viewarr.ui.media.VodHost.swallowBack) {
                    if (e.type == KeyEventType.KeyUp) io.github.petebutty.viewarr.ui.media.VodHost.swallowBack = false
                    return@onPreviewKeyEvent true
                }
                // Hold Back with a film in the corner: back to full screen (before the guide's own hold-Back).
                if (e.key == Key.Back && (io.github.petebutty.viewarr.ui.media.VodHost.mini || pipBackHeld)) {
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 && !pipBackHeld -> {
                            pipBackHeld = true
                            // The menu (and any settings page) closes: left open under the film, it took the next Back presses.
                            menuOpen = false
                            quickSearch = false
                            io.github.petebutty.viewarr.ui.media.VodHost.expand()
                            return@onPreviewKeyEvent true
                        }
                        e.type == KeyEventType.KeyDown && pipBackHeld -> return@onPreviewKeyEvent true
                        // The release reached here (the player hadn't the remote yet): that press is over.
                        e.type == KeyEventType.KeyUp && pipBackHeld -> { pipBackHeld = false; io.github.petebutty.viewarr.ui.media.VodHost.eatBackRelease = false; return@onPreviewKeyEvent true }
                    }
                }
                // Music playing in the bar: hold Back for Now Playing; the remote's media buttons control it.
                val music = io.github.petebutty.viewarr.playback.MusicPlayer
                // The rest of the held Back that opened Now Playing (before that screen has the remote).
                if (e.key == Key.Back && music.eatBackRelease) {
                    if (e.type == KeyEventType.KeyUp) music.eatBackRelease = false
                    return@onPreviewKeyEvent true
                }
                if (music.active && !music.nowPlayingOpen && !io.github.petebutty.viewarr.ui.media.VodHost.active && !backLongPress) {
                    if (e.key == Key.Back && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) {
                        if (e.nativeKeyEvent.repeatCount == 1) { music.eatBackRelease = true; music.nowPlayingOpen = true }
                        return@onPreviewKeyEvent true
                    }
                    if (e.type == KeyEventType.KeyDown) {
                        when (io.github.petebutty.viewarr.ui.common.RemoteKeys.action(e)) {
                            io.github.petebutty.viewarr.ui.common.RemoteAction.PlayPause -> { music.toggle(); return@onPreviewKeyEvent true }
                            io.github.petebutty.viewarr.ui.common.RemoteAction.Stop -> { music.stop(); return@onPreviewKeyEvent true }
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
                if (!quickSearch && !showAccounts && !io.github.petebutty.viewarr.ui.media.VodHost.covering && page != Page.Search && e.type == KeyEventType.KeyDown) {
                    val code = e.nativeKeyEvent.keyCode
                    val hit = when (remote.searchButton) {
                        io.github.petebutty.viewarr.data.settings.SearchButton.Auto ->
                            code == android.view.KeyEvent.KEYCODE_SEARCH || (e.key == Key.Menu && e.nativeKeyEvent.repeatCount == 1)
                        io.github.petebutty.viewarr.data.settings.SearchButton.Red -> code == android.view.KeyEvent.KEYCODE_PROG_RED
                        io.github.petebutty.viewarr.data.settings.SearchButton.Green -> code == android.view.KeyEvent.KEYCODE_PROG_GREEN
                        io.github.petebutty.viewarr.data.settings.SearchButton.Yellow -> code == android.view.KeyEvent.KEYCODE_PROG_YELLOW
                        io.github.petebutty.viewarr.data.settings.SearchButton.Blue -> code == android.view.KeyEvent.KEYCODE_PROG_BLUE
                        io.github.petebutty.viewarr.data.settings.SearchButton.Info -> code == android.view.KeyEvent.KEYCODE_INFO
                        io.github.petebutty.viewarr.data.settings.SearchButton.Chosen -> remote.searchKeyCode != null && code == remote.searchKeyCode
                        io.github.petebutty.viewarr.data.settings.SearchButton.Off -> false
                    }
                    if (hit) {
                        menuOpen = false
                        channelListOpen = false
                        searchStart = null
                        quickSearch = true
                        searchHeld = true // swallow the rest of this press
                        return@onPreviewKeyEvent true
                    }
                }
                // Settings → Remote → Buttons: colour buttons, Record and Info on the guide and full-screen live TV.
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && page == Page.Tv && !menuOpen && !showAccounts &&
                    !quickSearch && recordTarget == null && infoTarget == null && !io.github.petebutty.viewarr.ui.media.VodHost.covering &&
                    !transparencyOpen && ((!channelListOpen && !osdVisible && !(fullscreen && multiView)) ||
                        io.github.petebutty.viewarr.data.settings.ButtonMap.pressOf(e) == io.github.petebutty.viewarr.data.settings.BtnPress.Stop)
                ) {
                    // (Stop works with the controls, the channel list or multi-view open too.)
                    val press = io.github.petebutty.viewarr.data.settings.ButtonMap.pressOf(e)
                    val screen = if (fullscreen) io.github.petebutty.viewarr.data.settings.BtnScreen.Live else io.github.petebutty.viewarr.data.settings.BtnScreen.Guide
                    if (press != null && press in io.github.petebutty.viewarr.data.settings.ButtonMap.presses[screen].orEmpty()) {
                        doButton(screen, io.github.petebutty.viewarr.data.settings.ButtonMap.action(remote, screen, press))
                        return@onPreviewKeyEvent true
                    }
                }
                // Remotes with Guide / Record buttons (e.g. Formuler): the TV guide from anywhere, and recording what's on.
                if (!showAccounts && recordTarget == null && infoTarget == null && e.type == KeyEventType.KeyDown) {
                    when (io.github.petebutty.viewarr.ui.common.RemoteKeys.action(e)) {
                        io.github.petebutty.viewarr.ui.common.RemoteAction.Guide -> {
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                quickSearch = false
                                menuOpen = false
                                channelListOpen = false
                                osdVisible = false
                                io.github.petebutty.viewarr.playback.MusicPlayer.nowPlayingOpen = false
                                // A film full screen stops (picture-in-picture for films is off for now).
                                if (io.github.petebutty.viewarr.ui.media.VodHost.covering) io.github.petebutty.viewarr.ui.media.VodHost.close()
                                if (page == Page.Tv) { fullscreen = false; multiView = false } else openPage(Page.Tv)
                            }
                            return@onPreviewKeyEvent true
                        }
                        // Record on live TV: the programme's options (record it, its series, start over…), as holding
                        // OK in the guide. The guide handles Record on the highlighted programme itself.
                        io.github.petebutty.viewarr.ui.common.RemoteAction.Record -> {
                            val ch = playing
                            if (!quickSearch && page == Page.Tv && fullscreen && !multiView && catchup == null && ch != null &&
                                !io.github.petebutty.viewarr.ui.media.VodHost.covering
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
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && backHoldJob == null && !backLongPress) {
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
                        digitsMessage = null
                        digitsToken = System.nanoTime()
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
        if (page == Page.Search) {
            SearchPage(
                guide = vm,
                serverUrl = session?.serverUrl.orEmpty(),
                focusEnabled = !menuOpen && !showAccounts && !quickSearch && infoTarget == null && recordTarget == null && seasonPick == null,
                onProgramme = { ch, prog -> recordTarget = ch to prog },
                onCatchup = { ch, prog ->
                    vm.reveal(ch) {
                        page = Page.Tv
                        playback.playCatchup(ch, prog, session)
                        vm.selectChannel(ch.id)
                        fullscreen = true
                        showBanner()
                    }
                },
                onOpenMenu = { menuOpen = true },
                onExit = ::backOnPage,
                onMusic = { card -> musicVm.openFromSearch(card); openPage(Page.Music) },
                onChannel = { ch ->
                    vm.reveal(ch) {
                        page = Page.Tv
                        playback.play(ch, session)
                        vm.selectChannel(ch.id)
                        fullscreen = true
                        showBanner()
                    }
                },
            )
        } else if (page == Page.Music) {
            io.github.petebutty.viewarr.ui.music.MusicScreen(
                focusRequester = mediaFocus,
                focusEnabled = !menuOpen && !showAccounts && !quickSearch && !io.github.petebutty.viewarr.ui.media.VodHost.covering && !io.github.petebutty.viewarr.playback.MusicPlayer.nowPlayingOpen,
                onOpenMenu = { menuOpen = true },
                onBack = ::backOnPage,
                onSetUp = { showAccounts = true },
            )
        } else if (page == Page.Recordings) {
            RecordingsScreen(
                vm = dvr,
                focusRequester = mediaFocus,
                focusEnabled = !menuOpen && !showAccounts && !quickSearch && !io.github.petebutty.viewarr.ui.media.VodHost.covering,
                onBack = ::backOnPage,
                onPlay = { recordingPlayback = it },
                onOpenMenu = { menuOpen = true },
            )
        } else if (page != Page.Tv) androidx.compose.runtime.CompositionLocalProvider(io.github.petebutty.viewarr.ui.common.LocalQuickSearchOpen provides quickSearch) {
            MediaScreen(
                type = if (page == Page.Movies) MediaType.Movie else MediaType.Show,
                focusRequester = mediaFocus,
                focusEnabled = !menuOpen && !showAccounts && !quickSearch,
                onOpenMenu = { menuOpen = true },
                onBack = {
                    if (io.github.petebutty.viewarr.ui.media.VodHost.active && io.github.petebutty.viewarr.ui.media.VodHost.mini) { io.github.petebutty.viewarr.ui.media.VodHost.expand(); return@MediaScreen }
                    if ((remote.navStyle == io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub || (liveCorner && playing != null)) && goLive()) return@MediaScreen
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
                focusEnabled = !menuOpen && !showAccounts && !quickSearch,
                onSetUp = { showAccounts = true },
                onOpenMenu = { menuOpen = true },
            )
        } else {
            GuideScreen(
                vm = vm,
                recordings = dvrRecordings,
                playingChannelId = playing?.id,
                playingCatchupRowId = catchup?.rowId,
                serverUrl = session?.serverUrl.orEmpty(),
                focusRequester = guideFocus,
                focusEnabled = !menuOpen && !fullscreen && !showAccounts && !quickSearch,
                onOpenMenu = { menuOpen = true },
                onSelect = { channel, program ->
                    val t = System.currentTimeMillis()
                    val live = program == null || (program.startTime <= t && program.endTime > t)
                    val replayable = program != null && program.endTime <= t && channel.catchupDays > 0 &&
                        program.startTime >= t - channel.catchupDays * 86_400_000L
                    when {
                        live -> {
                            val alreadyLive = playing?.id == channel.id && catchup == null && playState !is PlaybackState.Error
                            if (!alreadyLive) playback.play(channel, session)
                            fullscreen = true
                            showBanner()
                        }
                        replayable -> {
                            if (catchup?.rowId != program!!.rowId || playing?.id != channel.id) playback.playCatchup(channel, program, session)
                            fullscreen = true
                            showBanner()
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
                    if (program != null) recordTarget = channel to program
                },
            )
        }

        // Live TV into the guide's preview and back: a glide (the window's real place and size; the picture is a
        // SurfaceView, scaled by the box's display hardware). Settings → Playback → Picture-in-picture → Live TV animation.
        val liveShrink = androidx.compose.animation.core.animateFloatAsState(
            if (fullscreen) 0f else 1f,
            if (AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.ANIM_LIVE) == 0)
                androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            else androidx.compose.animation.core.snap(),
            label = "live",
        )
        // Over another page (a shortcut, the Live TV hub): from the guide's preview place to the chosen corner.
        val inCorner = liveCorner && page != Page.Tv
        val liveSide = androidx.compose.animation.core.animateFloatAsState(
            if (inCorner) 1f else 0f,
            if (AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.ANIM_LIVE) == 0)
                androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            else androidx.compose.animation.core.snap(),
            label = "liveCorner",
        )
        val cornerSize = when (AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.MINI_SIZE)) { 1 -> 320.dp to 180.dp; 2 -> 512.dp to 288.dp; else -> 400.dp to 225.dp }
        val cornerAt = AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.MINI_CORNER) // 0 BR, 1 BL, 2 TR, 3 TL
        val channel = playing.takeIf { page == Page.Tv || inCorner }
        val list = channel?.let { programsOf(it) }.orEmpty()
        val current = catchup ?: GuideViewModel.programAt(list, now)
        if (channel != null && !(multiView && fullscreen)) {
            val next = if (catchup != null) null else current?.let { c -> list.firstOrNull { it.startTime >= c.endTime } }
            PlayerLayer(
                vm = playback,
                fullscreen = fullscreen,
                bannerVisible = bannerVisible && !osdVisible && !inCorner,
                current = current,
                next = next,
                now = now,
                serverUrl = session?.serverUrl.orEmpty(),
                // Read in the layout and draw steps only, so the glide doesn't rebuild the screen every frame.
                modifier = Modifier.fillMaxSize()
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
                        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(cw, ch))
                        layout(fw, fh) { placeable.place((sx * p).toInt(), (sy * p).toInt()) }
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
                    Box(Modifier.fillMaxSize().slideInFromLeft()) {
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
                        val action = io.github.petebutty.viewarr.ui.common.RemoteKeys.action(e)
                        if (action == io.github.petebutty.viewarr.ui.common.RemoteAction.Menu) {
                            if (liveMenuKey.feed(e) == io.github.petebutty.viewarr.ui.common.Press.Tap) {
                                if (playState is PlaybackState.Error) playback.retry() else showOsd(audio = true)
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (e.type != KeyEventType.KeyDown) {
                            if (action == io.github.petebutty.viewarr.ui.common.RemoteAction.Left) rewindHeld = false
                            return@onPreviewKeyEvent false
                        }
                        if (action != io.github.petebutty.viewarr.ui.common.RemoteAction.Left) rewindAsk = 0L
                        // Settings → Remote buttons → Live TV → Left / Right, as TiviMate: Left the channel list, Right
                        // back to the channel watched before (catch-up still moves through the programme).
                        if (remote.liveSides == io.github.petebutty.viewarr.data.settings.LiveSides.ListAndLast && catchup == null && !multiView) {
                            if (action == io.github.petebutty.viewarr.ui.common.RemoteAction.Left) {
                                if (e.nativeKeyEvent.repeatCount == 0) channelListOpen = true
                                return@onPreviewKeyEvent true
                            }
                            if (action == io.github.petebutty.viewarr.ui.common.RemoteAction.Right) {
                                val before = remote.recentChannels.firstOrNull { it != playing?.id }
                                if (e.nativeKeyEvent.repeatCount == 0) {
                                    if (before == null) { digitsMessage = "No channel watched before this one yet"; digitsToken = System.nanoTime() }
                                    else fromStartScope.launch {
                                        val ch = vm.channelById(before) ?: return@launch
                                        playback.play(ch, session)
                                        vm.selectChannel(ch.id)
                                        showBanner()
                                    }
                                }
                                return@onPreviewKeyEvent true
                            }
                        }
                        when (action) {
                            // Live: ask first. In catch-up (already behind live) Left steps back straight away.
                            io.github.petebutty.viewarr.ui.common.RemoteAction.Left -> if (catchup == null) {
                                val ch = playing
                                val again = rewindAsk != 0L && System.currentTimeMillis() - rewindAsk < 4_000
                                when {
                                    rewindHeld -> Unit
                                    ch == null || ch.catchupDays <= 0 -> { digitsMessage = "This channel has no catch-up to rewind"; digitsToken = System.nanoTime() }
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
                            io.github.petebutty.viewarr.ui.common.RemoteAction.Up, io.github.petebutty.viewarr.ui.common.RemoteAction.Down -> when (remote.zap) {
                                ZapDirection.ChannelList -> channelListOpen = true
                                ZapDirection.UpNext -> zap(if (action == io.github.petebutty.viewarr.ui.common.RemoteAction.Up) 1 else -1)
                                ZapDirection.UpPrevious -> zap(if (action == io.github.petebutty.viewarr.ui.common.RemoteAction.Up) -1 else 1)
                            }
                            io.github.petebutty.viewarr.ui.common.RemoteAction.ChannelDown -> zap(-1)
                            io.github.petebutty.viewarr.ui.common.RemoteAction.ChannelUp -> zap(1)
                            io.github.petebutty.viewarr.ui.common.RemoteAction.Ok -> when {
                                playState is PlaybackState.Error -> playback.retry()
                                remote.playerOk == PlayerOk.Banner -> showBanner()
                                remote.playerOk == PlayerOk.ChannelList && !multiView -> channelListOpen = true
                                else -> showOsd()
                            }
                            io.github.petebutty.viewarr.ui.common.RemoteAction.PlayPause -> {
                                playback.togglePause()
                                showBanner()
                            }
                            // The remote's own rewind button means it: no question.
                            io.github.petebutty.viewarr.ui.common.RemoteAction.Rewind -> {
                                rewindAsk = 0L
                                playback.seekBy(-remote.seek.ms)
                                showBanner()
                            }
                            io.github.petebutty.viewarr.ui.common.RemoteAction.Right, io.github.petebutty.viewarr.ui.common.RemoteAction.FastForward -> {
                                playback.seekBy(remote.seek.ms)
                                showBanner()
                            }
                            io.github.petebutty.viewarr.ui.common.RemoteAction.Info -> showBanner()
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
            )
            if (channelListOpen && channel != null) Box(Modifier.fillMaxSize().slideInFromLeft()) {
                ChannelListOverlay(
                    vm = vm,
                    serverUrl = session?.serverUrl.orEmpty(),
                    current = channel,
                    now = now,
                    onPick = { ch ->
                        channelListOpen = false
                        // Another channel, or the same number on another source (after switching source).
                        if (ch.id != channel.id || ch.uuid != channel.uuid) {
                            playback.play(ch, session)
                            vm.selectChannel(ch.id)
                        }
                        showBanner()
                    },
                    onClose = { channelListOpen = false },
                    onCatchup = { ch, prog ->
                        channelListOpen = false
                        playback.playCatchup(ch, prog, session)
                        vm.selectChannel(ch.id)
                        showBanner()
                    },
                    onFuture = { ch, prog -> channelListOpen = false; recordTarget = ch to prog },
                )
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
                    // The shortcut row and its rows are part of the Live TV hub.
                    shortcuts = if (remote.navStyle == io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub) liveShortcuts else emptyList(),
                    posterRows = posterRows,
                    previewFor = previewFor,
                    onHideShortcut = { sc -> hideShortcut(sc) },
                    onMoveShortcut = { sc, d -> moveShortcut(sc, d) },
                    onRefreshShortcut = { sc -> refreshOne(sc) },
                    onMultiView = if (!AppColors.palette.heavyExtras) null else ({
                        osdVisible = false
                        if (catchup != null) playback.goLive()
                        multiView = true
                    }),
                    openAudio = osdAudio,
                    // Recording: only for people allowed to record (Dispatcharr dvr_access; admins always).
                    onRecord = current?.takeIf { catchup == null && io.github.petebutty.viewarr.data.profiles.Access.record }?.let { prog ->
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
        androidx.compose.animation.AnimatedVisibility(
            rewindAsk != 0L && fullscreen && page == Page.Tv,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
        ) {
            Row(
                Modifier.background(AppColors.Panel.copy(alpha = 0.94f), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(io.github.petebutty.viewarr.ui.common.AppIcons.Replay, null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                Text("Rewind?", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 10.dp))
                Text("   Press ◀ again, or hold ◀", fontSize = 13.sp, color = AppColors.TextSecondary)
            }
        }
        // What the buttons do here (Settings → Appearance → Button hints), following the button settings.
        if (!menuOpen && !quickSearch && !showAccounts && recordTarget == null && infoTarget == null && !io.github.petebutty.viewarr.ui.media.VodHost.active) {
            val holdBackGuide = io.github.petebutty.viewarr.data.settings.ButtonMap.action(remote, io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold).label.lowercase()
            when {
                page == Page.Tv && fullscreen && !osdVisible && !channelListOpen && !multiView -> io.github.petebutty.viewarr.ui.common.ButtonHint(
                    "live", "OK: controls  ·  ▲ ▼ channels  ·  Back: guide  ·  hold Back: " + io.github.petebutty.viewarr.data.settings.ButtonMap.action(remote, io.github.petebutty.viewarr.data.settings.BtnScreen.Live, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold).label.lowercase())
                page == Page.Tv && !fullscreen && session != null -> io.github.petebutty.viewarr.ui.common.ButtonHint(
                    "guide", "OK: watch  ·  ◀ ▶ earlier / later  ·  Back: " + (when { guideBack == GuideBack.Menu -> "menu"; guideBack == GuideBack.FullScreen && playing != null -> "full screen"; else -> "exit" }) + "  ·  hold Back: $holdBackGuide")
                page == Page.Movies || page == Page.Shows -> io.github.petebutty.viewarr.ui.common.ButtonHint(
                    "media", "OK: open  ·  hold OK: options  ·  ◀ menu  ·  hold Back: " + io.github.petebutty.viewarr.data.settings.ButtonMap.action(remote, io.github.petebutty.viewarr.data.settings.BtnScreen.Pages, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold).label.lowercase())
                else -> Unit
            }
        }
        // Guide and wallpaper dimming only show on the guide: the panel switches the view as you move to them.
        if (transparencyOpen && page == Page.Tv) io.github.petebutty.viewarr.ui.player.TransparencyPanel(
            onClose = { transparencyOpen = false; if (playing != null) fullscreen = true },
            onRow = { r -> if (playing != null) fullscreen = r == 0 },
        )
        if (menuOpen) Box(Modifier.fillMaxSize().slideInFromLeft()) {
            MenuOverlay(
                onPlayRecording = { menuOpen = false; recordingPlayback = it },
                // See-through only over full-screen video; over the guide and other pages it's solid (tidier).
                seeThrough = fullscreen,
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
            Box(Modifier.fillMaxSize().appBackground().slideInFromLeft(60.dp, 150).then(if (infoTarget == null && recordTarget == null) Modifier.trapFocus() else Modifier)) {
                SearchPage(
                    guide = vm,
                    serverUrl = session?.serverUrl.orEmpty(),
                    focusEnabled = infoTarget == null && recordTarget == null && seasonPick == null,
                    openKeyboard = searchStart == null && !searchVoice,
                    startQuery = searchStart,
                    startVoice = searchVoice,
                    startCatchup = searchCatchup,
                    onProgramme = { ch, prog -> recordTarget = ch to prog },
                    onCatchup = { ch, prog ->
                        quickSearch = false
                        searchStart = null
                        vm.reveal(ch) {
                            page = Page.Tv
                            playback.playCatchup(ch, prog, session)
                            vm.selectChannel(ch.id)
                            fullscreen = true
                            multiView = false
                            showBanner()
                        }
                    },
                    // Left at the edge stays in search (it used to close it and open the menu); Back is the way out.
                    onOpenMenu = {},
                    onExit = { quickSearch = false; searchStart = null; searchCatchup = false },
                    onMusic = { card -> quickSearch = false; searchStart = null; musicVm.openFromSearch(card); openPage(Page.Music) },
                    onChannel = { ch ->
                        quickSearch = false
                        vm.reveal(ch) {
                            page = Page.Tv
                            playback.play(ch, session)
                            vm.selectChannel(ch.id)
                            fullscreen = true
                            multiView = false
                            showBanner()
                        }
                    },
                )
            }
        }

        infoTarget?.let { (ch, prog) ->
            ProgramInfoDialog(ch, prog, dvr, onRecord = { infoTarget = null; recordTarget = ch to prog }, onClose = { infoTarget = null })
        }
        // Recording started / finished / failed: bottom-right, a few seconds, no buttons.
        dvrNote?.let { text ->
            Text(
                text, fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp).width(420.dp)
                    .background(AppColors.Panel, RoundedCornerShape(10.dp))
                    .border(1.dp, if (text.startsWith("⚠")) Color(0xFFE5534B) else AppColors.Accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        // No free provider connection for the channel just tuned: say what's using them.
        if (page == Page.Tv) connectionNote?.let { text ->
            val recordingNow = dvrRecordings.firstOrNull { it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recording }
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 28.dp).width(640.dp)
                    .background(AppColors.Panel.copy(alpha = 0.95f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFFE5A23B), RoundedCornerShape(10.dp))
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
                    .background(AppColors.Panel, RoundedCornerShape(12.dp))
                    .border(2.dp, AppColors.Accent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text("⏰  Starting now", fontSize = 13.sp, color = AppColors.Accent)
                Text(r.title, fontSize = 18.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
        seasonPick?.let { m -> io.github.petebutty.viewarr.ui.dvr.SeasonPicker(m, onClose = { seasonPick = null }) }
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
                Modifier.width(560.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Still watching?", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text("Press any button to carry on. Live TV stops in a minute otherwise.", fontSize = 15.sp, color = AppColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                    .background(AppColors.Panel.copy(alpha = 0.95f), RoundedCornerShape(24.dp))
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
                    .background(AppColors.Panel.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun NoLiveTv(focusRequester: FocusRequester, focusEnabled: Boolean, onSetUp: () -> Unit, onOpenMenu: () -> Unit) {
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

@Composable
private fun MenuOverlay(
    /** Recordings panel: play a recording straight from the menu. */
    onPlayRecording: (VodStream) -> Unit,
    seeThrough: Boolean,
    vm: GuideViewModel,
    session: Session?,
    page: Page,
    onOpenPage: (Page) -> Unit,
    onAccounts: () -> Unit,
    onClose: () -> Unit,
    onSignOut: () -> Unit,
    /** Opened by Back in the guide: Back from the groups (or the main list) leaves it through [onBackOut] instead. */
    backOut: Boolean = false,
    onBackOut: () -> Unit = onClose,
    /** Reopened after Accounts & sources: straight back on Settings, on its first item. */
    onSettings: Boolean = false,
    /** Open on the main list (Live TV, Movies…), whatever "Menu opens on" says. */
    onMain: Boolean = false,
    /** Search panel: search for this (null: an empty search), or by voice. */
    onSearchFor: (String?) -> Unit = {},
    onVoiceSearch: () -> Unit = {},
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val groupId by vm.groupId.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    var section by remember {
        mutableStateOf(if (onSettings) Section.Settings else when (page) { Page.Tv -> Section.Tv; Page.Movies -> Section.Movies; Page.Shows -> Section.Shows; Page.Music -> Section.Music; Page.Recordings -> Section.Recordings; Page.Search -> Section.Search })
    }
    var showAppearance by remember { mutableStateOf(false) }
    var showHomeRows by remember { mutableStateOf(false) }
    var showPlugins by remember { mutableStateOf(false) }
    val pluginsItem = remember { FocusRequester() }
    val homeRowsItem = remember { FocusRequester() }
    var showRemote by remember { mutableStateOf(false) }
    var showNavigation by remember { mutableStateOf(false) }
    val navigationItem = remember { FocusRequester() }
    var showPlayback by remember { mutableStateOf(false) }
    val playbackItem = remember { FocusRequester() }
    var showAddons by remember { mutableStateOf(false) }
    val addonsItem = remember { FocusRequester() }
    val remoteItem = remember { FocusRequester() }
    val appearanceItem = remember { FocusRequester() }
    var showUpdates by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val updatesItem = remember { FocusRequester() }
    val aboutItem = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    // After closing a full settings page, land back on its menu item (not wherever focus happens to fall).
    LaunchedEffect(returnTo) {
        val target = returnTo ?: return@LaunchedEffect
        // When the page goes, focus can fall onto the first menu entry and switch the side panel away from
        // Settings (taking the menu item with it). Put Settings back first, then focus the item.
        section = Section.Settings
        awaitFrame(); awaitFrame()
        runCatching { target.requestFocus() }
        returnTo = null
    }
    // Music: in the menu only once it's turned on (setup, Music).
    val musicMenuOn by hiltViewModel<io.github.petebutty.viewarr.ui.music.MusicViewModel>().enabled.collectAsStateWithLifecycle()
    val remote = LocalRemote.current
    val navFocus = remember { Section.entries.associateWith { FocusRequester() } }
    val settingsFirst = remember { FocusRequester() }
    val groupsFocus = remember { FocusRequester() }
    // Movies / Shows: the newest titles in your libraries, beside the menu.
    val recentFocus = remember { FocusRequester() }
    val recentCount = remember { androidx.compose.runtime.mutableStateMapOf<MediaType, Int>() }
    var recordingsCount by remember { mutableStateOf(0) }
    // From the guide the menu opens on the channel groups alone; Left again slides the main menu out beside them.
    var showMain by remember { mutableStateOf(onMain || !(remote.menuOpensOn == MenuOpensOn.PageList && page == Page.Tv && session != null)) }
    // Back from Users & profiles / the PIN dialog (drawn over the menu): Settings stays the open list, so the row
    // they came from can take focus again (focus otherwise fell to the top of the menu).
    val profilePage = io.github.petebutty.viewarr.ui.profiles.ProfileUi.users || io.github.petebutty.viewarr.ui.profiles.ProfileUi.pin
    var hadProfilePage by remember { mutableStateOf(false) }
    LaunchedEffect(profilePage) {
        if (hadProfilePage && !profilePage) { section = Section.Settings; showMain = true }
        hadProfilePage = profilePage
    }
    val menuScope = androidx.compose.runtime.rememberCoroutineScope()
    fun revealMain() {
        if (showMain) { runCatching { navFocus.getValue(section).requestFocus() }; return }
        showMain = true
        menuScope.launch { awaitFrame(); awaitFrame(); runCatching { navFocus.getValue(section).requestFocus() } }
    }
    // The menu opens on its own list (TV, Movies, …); OK or Right then goes into the highlighted entry.
    LaunchedEffect(Unit) {
        awaitFrame()
        awaitFrame()
        runCatching {
            when {
                onSettings -> { showMain = true; awaitFrame(); settingsFirst.requestFocus() }
                onMain -> navFocus.getValue(section).requestFocus()
                remote.menuOpensOn != MenuOpensOn.PageList -> navFocus.getValue(section).requestFocus()
                // From the guide: the channel groups (on the group being shown); Left again reaches the main list.
                page == Page.Tv && session != null -> groupsFocus.requestFocus()
                // Music, Movies, Shows, Recordings: that page's own list beside the main one (Left again reaches it).
                page == Page.Music || page == Page.Movies || page == Page.Shows || (page == Page.Recordings && recordingsCount > 0) ->
                    if (runCatching { recentFocus.requestFocus() }.isFailure) { awaitFrame(); awaitFrame(); runCatching { recentFocus.requestFocus() }.onFailure { navFocus.getValue(section).requestFocus() } }
                else -> navFocus.getValue(section).requestFocus()
            }
        }
    }
    fun enter(s: Section) {
        when (s) {
            // Into the channel groups: on the guide the main list slides back out (Left brings it again).
            Section.Tv -> runCatching { groupsFocus.requestFocus() }
            Section.Settings -> runCatching { settingsFirst.requestFocus() }
            Section.Movies -> onOpenPage(Page.Movies)
            Section.Shows -> onOpenPage(Page.Shows)
            Section.Recordings -> onOpenPage(Page.Recordings)
            Section.Music -> onOpenPage(Page.Music)
            Section.Search -> onOpenPage(Page.Search)
            else -> Unit
        }
    }
    // Back steps out one level: from the right-hand list to the main list, then closes the menu.
    var subListFocused by remember { mutableStateOf(false) }
    // Movies / Shows: the library whose categories fill the list column (the library list slid away), or null.
    var menuCats by remember { mutableStateOf<io.github.petebutty.viewarr.data.media.MediaLibrary?>(null) }
    // Movies / Shows: editing the list in place (libraries on / off and their order, the rows under "Jump to a row").
    var menuEditing by remember { mutableStateOf(false) }
    LaunchedEffect(section) { menuCats = null; menuEditing = false }
    // Into a section's own list (Movies' rows, Recordings, Music…): the main list slides out to the left, as the
    // guide's channel groups always did, so the menu doesn't cover half the screen. Left brings it back.
    // (Not Settings: its pages open from the Settings entry in the main list.)
    LaunchedEffect(subListFocused) { if (subListFocused && section != Section.Settings) showMain = false }
    BackHandler {
        when {
            // A library's categories: back to the library list. Editing the list: done.
            menuCats != null -> menuCats = null
            menuEditing -> menuEditing = false
            // Opened by Back in the guide (as TiviMate): Back from the groups asks to exit.
            backOut && section == Section.Tv -> onBackOut()
            // A section's list: the main list slides back in.
            subListFocused -> if (showMain) runCatching { navFocus.getValue(section).requestFocus() }.onFailure { onClose() } else revealMain()
            else -> if (backOut) onBackOut() else onClose()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        // Left again: the main list grows out beside the groups, easing them across rather than jumping.
        io.github.petebutty.viewarr.ui.common.GrowFromLeftToggle(showMain, 180.dp) { Column(
            Modifier
                .width(180.dp)
                .fillMaxHeight()
                .background(if (seeThrough) AppColors.MenuPanel else AppColors.palette.panel)
                .padding(horizontal = 10.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The Viewarr logo: aperture mark plus the glossy wordmark (the splash's, tools/brand/wordmark.py; its
            // picture has room round it for the shadow, hence the negative gap).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, bottom = 20.dp)) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(io.github.petebutty.viewarr.R.drawable.logo_mark),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(io.github.petebutty.viewarr.R.drawable.splash_wordmark),
                    contentDescription = "Viewarr",
                    modifier = Modifier.height(40.dp).offset(x = (-2).dp),
                )
            }
            // Settings → Appearance → Movies & Shows off: a live-TV-only menu.
            Section.entries.filter { !(AppColors.palette.hideMedia && (it == Section.Movies || it == Section.Shows)) && (it != Section.Music || musicMenuOn) }.forEach { s ->
                MenuItem(
                    text = s.label,
                    selected = s == section,
                    dim = !s.available,
                    onClick = {
                        section = s
                        enter(s)
                    },
                    modifier = Modifier
                        .focusRequester(navFocus.getValue(s))
                        .onFocusChanged { if (it.isFocused) section = s }
                        .onPreviewKeyEvent { e ->
                            // Right does the same as OK: open what's inside this entry.
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && !(s == Section.Settings && (showAppearance || showRemote || showNavigation || showPlayback || showAddons || showUpdates || showAbout || showHomeRows || showPlugins))) {
                                // Movies / Shows with recently added posters showing: Right goes onto them (OK opens the page).
                                val t = if (s == Section.Movies) MediaType.Movie else if (s == Section.Shows) MediaType.Show else null
                                // (Movies / Shows always have their list: home, libraries, catalogues.)
                                if (t != null || s == Section.Music || s == Section.Search || (s == Section.Recordings && recordingsCount > 0)) {
                                    if (runCatching { recentFocus.requestFocus() }.isFailure) {
                                        menuScope.launch { awaitFrame(); awaitFrame(); runCatching { recentFocus.requestFocus() } }
                                    }
                                } else enter(s)
                                true
                            } else false
                        },
                )
            }
        } }

        Box(
            Modifier
                .width(230.dp)
                .fillMaxHeight()
                .background(if (seeThrough) AppColors.MenuPanelAlt else AppColors.palette.panelAlt)
                .onFocusChanged { subListFocused = it.hasFocus }
                // Any other list (Music, Search): a Left nothing in it used brings the main list back.
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && !showMain && section != Section.Settings) { revealMain(); true } else false
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        // In a library's categories: Left goes back to the library list (it slides back in).
                        e.key == Key.DirectionLeft && menuCats != null -> { menuCats = null; true }
                        // Editing the list: Left finishes (a library being moved is put down where it is).
                        e.key == Key.DirectionLeft && menuEditing -> { menuEditing = false; true }
                        // Left goes back to this section's menu entry, not whichever one is geometrically closest.
                        e.key == Key.DirectionLeft && (section == Section.Tv || section == Section.Movies || section == Section.Shows || section == Section.Recordings ||
                            (section == Section.Settings && !showAppearance && !showRemote && !showNavigation && !showPlayback && !showAddons && !showUpdates && !showAbout && !showHomeRows && !showPlugins)) -> {
                            revealMain()
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 8.dp, vertical = 24.dp),
        ) {
            when (section) {
                Section.Tv -> GroupList(
                    sourcesVm = hiltViewModel(),
                    focusRequester = groupsFocus,
                    groups = groups,
                    allCount = if (groupId == null) channels.size else null,
                    selectedId = groupId,
                    onSelect = {
                        vm.selectGroup(it)
                        onOpenPage(Page.Tv)
                    },
                )
                Section.Settings -> SettingsPanel(
                    playbackItem = playbackItem,
                    onPlayback = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showPlayback = true
                    },
                    addonsItem = addonsItem,
                    onAddons = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showAddons = true
                    },
                    // Park focus on the Settings entry before swapping panels, so it never drops back to Search.
                    updatesItem = updatesItem,
                    onUpdates = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showUpdates = true
                    },
                    aboutItem = aboutItem,
                    onAbout = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showAbout = true
                    },
                    remoteItem = remoteItem,
                    onRemote = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showRemote = true
                    },
                    navigationItem = navigationItem,
                    onNavigation = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showNavigation = true
                    },
                    session = session,
                    sync = sync,
                    firstItem = settingsFirst,
                    appearanceItem = appearanceItem,
                    onAppearance = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showAppearance = true
                    },
                    pluginsItem = pluginsItem,
                    onPlugins = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showPlugins = true
                    },
                    homeRowsItem = homeRowsItem,
                    onHomeRows = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showHomeRows = true
                    },
                    onAccounts = onAccounts,
                    onRefresh = {
                        vm.refresh()
                        onClose()
                    },
                    onSignOut = onSignOut,
                )
                Section.Music -> MusicMenuPanel(
                    focusRequester = recentFocus,
                    onOpenPage = { onOpenPage(Page.Music) },
                )
                Section.Search -> SearchMenuPanel(onSearch = onSearchFor, onVoice = onVoiceSearch, focus = recentFocus, musicOn = musicMenuOn)
                Section.Recordings -> RecordingsPanel(
                    focusRequester = recentFocus,
                    channelName = { id -> channels.firstOrNull { it.id == id }?.name },
                    onCount = { n -> recordingsCount = n },
                    onOpenPage = { onOpenPage(Page.Recordings) },
                    onPlay = onPlayRecording,
                )
                Section.Movies, Section.Shows -> MediaMenuPanel(
                    type = if (section == Section.Movies) MediaType.Movie else MediaType.Show,
                    focusRequester = recentFocus,
                    catsFor = menuCats,
                    onCats = { menuCats = it },
                    editing = menuEditing,
                    onEditing = { menuEditing = it },
                    rightMode = remote.menuLibraryRight,
                    onCount = { t, n -> recentCount[t] = n },
                    onOpenPage = { onOpenPage(if (section == Section.Movies) Page.Movies else Page.Shows) },
                )
                else -> Text(
                    "${section.label} is coming in a later update.",
                    fontSize = 15.sp,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black.copy(alpha = 0.4f * (if (seeThrough) AppColors.palette.menuAlpha else 1f))))
    }
    // Settings → Playback is a full page of its own, over the menu.
    if (showPlayback) {
        io.github.petebutty.viewarr.ui.settings.PlaybackSettingsScreen(onBack = {
            // Move focus to the Playback item first, while the page is still up; otherwise it jumps to Search.
            runCatching { playbackItem.requestFocus() }
            showPlayback = false
            returnTo = playbackItem
        })
    }
    if (showAppearance) {
        io.github.petebutty.viewarr.ui.settings.AppearanceSettingsScreen(onBack = {
            runCatching { appearanceItem.requestFocus() }
            showAppearance = false
            returnTo = appearanceItem
        })
    }
    if (showPlugins) {
        io.github.petebutty.viewarr.ui.settings.PluginsSettingsScreen(onBack = {
            runCatching { pluginsItem.requestFocus() }
            showPlugins = false
            returnTo = pluginsItem
        })
    }
    if (showHomeRows) {
        io.github.petebutty.viewarr.ui.settings.HomeRowsSettingsScreen(onBack = {
            runCatching { homeRowsItem.requestFocus() }
            showHomeRows = false
            returnTo = homeRowsItem
        })
    }
    if (showRemote) {
        io.github.petebutty.viewarr.ui.settings.RemoteSettingsScreen(onBack = {
            runCatching { remoteItem.requestFocus() }
            showRemote = false
            returnTo = remoteItem
        })
    }
    if (showNavigation) {
        io.github.petebutty.viewarr.ui.settings.RemoteSettingsScreen(part = io.github.petebutty.viewarr.ui.settings.RemotePart.Navigation, onBack = {
            runCatching { navigationItem.requestFocus() }
            showNavigation = false
            returnTo = navigationItem
        })
    }
    if (showAbout) {
        io.github.petebutty.viewarr.ui.settings.AboutSettingsScreen(onBack = {
            runCatching { aboutItem.requestFocus() }
            showAbout = false
            returnTo = aboutItem
        })
    }
    if (showUpdates) {
        io.github.petebutty.viewarr.ui.settings.UpdatesSettingsScreen(onBack = {
            runCatching { updatesItem.requestFocus() }
            showUpdates = false
            returnTo = updatesItem
        })
    }
    if (showAddons) {
        io.github.petebutty.viewarr.ui.settings.AddonsSettingsScreen(onBack = {
            runCatching { addonsItem.requestFocus() }
            showAddons = false
            returnTo = addonsItem
        })
    }
    }
}

@Composable
private fun GroupList(sourcesVm: io.github.petebutty.viewarr.ui.settings.LiveSourcesViewModel, focusRequester: FocusRequester, groups: List<GroupWithCount>, allCount: Int?, selectedId: Int?, onSelect: (Int?) -> Unit) {
    // Several Live TV sources (Dispatcharr instances, IPTV logins, playlists): the active one heads the list; OK
    // opens the others to switch to.
    val saved by sourcesVm.saved.collectAsStateWithLifecycle()
    val active by sourcesVm.active.collectAsStateWithLifecycle()
    var sourcesOpen by remember { mutableStateOf(false) }
    // Switching Live TV source: an admin can lock it (another saved source may be someone else's login).
    val showSources = saved.size > 1 && io.github.petebutty.viewarr.data.profiles.Access.can(io.github.petebutty.viewarr.data.profiles.Lock.SOURCES)
    val head = if (showSources) 1 + (if (sourcesOpen) saved.size - 1 else 0) else 0
    val selectedIndex = head + (if (selectedId == null) 0 else groups.indexOfFirst { it.id == selectedId } + 1)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, selectedIndex - 3))
    val selectedFocus = focusRequester
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (showSources) {
            item(key = "source") {
                MenuItem(
                    text = active?.label ?: "Live TV source",
                    selected = false,
                    trailing = if (sourcesOpen) "▴" else "Switch ▾",
                    onClick = { sourcesOpen = !sourcesOpen },
                )
            }
            if (sourcesOpen) items(saved.filter { it.id != active?.id }, key = { "src:" + it.id }) { s ->
                MenuItem(
                    text = "   " + s.label, selected = false, dim = true,
                    onClick = { sourcesOpen = false; sourcesVm.switchTo(s); onSelect(null) },
                )
            }
        }
        item(key = "all") {
            GroupItem(
                name = "All channels",
                count = allCount,
                selected = selectedId == null,
                modifier = if (selectedId == null) Modifier.focusRequester(selectedFocus) else Modifier,
                onClick = { onSelect(null) },
            )
        }
        items(groups, key = { it.id }) { g ->
            GroupItem(
                name = g.name,
                count = g.channelCount,
                selected = g.id == selectedId,
                modifier = if (g.id == selectedId) Modifier.focusRequester(selectedFocus) else Modifier,
                onClick = { onSelect(g.id) },
            )
        }
    }
}

@Composable
private fun GroupItem(name: String, count: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    MenuItem(text = name, selected = selected, onClick = onClick, modifier = modifier.rightDoes(onClick), trailing = count?.toString())
}

/** In the menu's right-hand lists, Right does the same as OK: go to that page. */
private fun Modifier.rightDoes(action: () -> Unit): Modifier = this.composed(factory = {
    // Acts when Right is released, and only if it was also pressed here: the Right that moved focus onto this entry
    // (from the menu, or held down) mustn't open it straight away.
    var pressedHere by remember { mutableStateOf(false) }
    onFocusChanged { if (!it.isFocused) pressedHere = false }.onPreviewKeyEvent { e ->
        if (e.key == Key.DirectionRight) {
            when (e.type) {
                KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) pressedHere = true
                KeyEventType.KeyUp -> if (pressedHere) { pressedHere = false; action() }
            }
            true
        } else false
    }
})

/** Right does nothing on menu actions that aren't pages (refresh, sign out), so a stray press can't trigger them. */
private fun Modifier.rightIgnored(): Modifier = onPreviewKeyEvent { e -> e.key == Key.DirectionRight }

@Composable
private fun SettingsPanel(playbackItem: FocusRequester, onPlayback: () -> Unit, addonsItem: FocusRequester, onAddons: () -> Unit, remoteItem: FocusRequester, onRemote: () -> Unit, navigationItem: FocusRequester, onNavigation: () -> Unit, updatesItem: FocusRequester, onUpdates: () -> Unit, aboutItem: FocusRequester, onAbout: () -> Unit, session: Session?, sync: SyncState, firstItem: FocusRequester, appearanceItem: FocusRequester, onAppearance: () -> Unit, homeRowsItem: FocusRequester, onHomeRows: () -> Unit, pluginsItem: FocusRequester, onPlugins: () -> Unit, onAccounts: () -> Unit, onRefresh: () -> Unit, onSignOut: () -> Unit) {
    // Scrolls: the list is taller than the screen with the Dispatcharr sync items.
    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(session?.let { "Signed in as ${it.username}" } ?: "Live TV not set up", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(horizontal = 12.dp))
        session?.let { Text(it.serverUrl, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        when (sync) {
            is SyncState.Running -> sync.message
            is SyncState.Failed -> sync.message
            SyncState.Idle -> null
        }?.let {
            Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(horizontal = 12.dp))
        }
        // What the highlighted entry covers, so it's clear where things live before opening one.
        var hint by remember { mutableStateOf<String?>(null) }
        fun Modifier.hint(text: String) = onFocusChanged { if (it.isFocused) hint = text }
        Text(hint ?: "", fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 3,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).height(54.dp))
        Spacer(Modifier.height(6.dp))
        // Grouped: accounts, look & feel, remote, watching, live TV, then this TV (backup, updates, sign out).
        // An admin's locks for this person (Settings → Users & profiles → What they can do): locked items don't show.
        val canAccounts = io.github.petebutty.viewarr.data.profiles.Access.can(io.github.petebutty.viewarr.data.profiles.Lock.ACCOUNTS)
        val canSettings = io.github.petebutty.viewarr.data.profiles.Access.can(io.github.petebutty.viewarr.data.profiles.Lock.SETTINGS)
        SettingsGroup("Accounts & people", first = true)
        if (canAccounts) MenuItem(text = "Accounts & sources", selected = false, onClick = onAccounts, modifier = Modifier.hint("Your live TV (Dispatcharr or IPTV), Plex, Jellyfin, Emby, Trakt, addons and music sign-ins.").focusRequester(firstItem).rightDoes(onAccounts))
        io.github.petebutty.viewarr.ui.profiles.ProfileSettingsItems()
        if (canSettings) {
        SettingsGroup("Look & controls")
        // Accounts & sources locked: Appearance is the first row (Right / OK from the menu land here).
        MenuItem(text = "Appearance", selected = false, onClick = onAppearance, modifier = Modifier.hint("How Viewarr looks: theme and wallpaper, the guide, title pages, posters and how the player looks.").focusRequester(appearanceItem).then(if (!canAccounts) Modifier.focusRequester(firstItem) else Modifier).rightDoes(onAppearance))
        MenuItem(text = "Home page & menu", selected = false, onClick = onHomeRows, modifier = Modifier.hint("Which rows show on the Movies and Shows pages, in what order, and what the side menu lists.").focusRequester(homeRowsItem).rightDoes(onHomeRows))
        MenuItem(text = "Remote buttons", selected = false, onClick = onRemote, modifier = Modifier.hint("What each button on your remote does: in the guide, on live TV, in films and on Movies & Shows. The search button and extra buttons.").focusRequester(remoteItem).rightDoes(onRemote))
        MenuItem(text = "Navigation", selected = false, onClick = onNavigation, modifier = Modifier.hint("How you get around: Classic menus or the Live TV hub (and its shortcuts), what the app opens on, the side menu and poster rows.").focusRequester(navigationItem).rightDoes(onNavigation))
        SettingsGroup("Watching")
        MenuItem(text = "Playback", selected = false, onClick = onPlayback, modifier = Modifier.hint("Players and streams: built-in player or mpv, quality, subtitles, audio, skipping, catch-up and the corner player.").focusRequester(playbackItem).rightDoes(onPlayback))
        // Which watchlist Add / Remove and "Your watchlist" use (Trakt, Plex, Jellyfin or Emby favourites).
        io.github.petebutty.viewarr.ui.settings.WatchlistItem(Modifier.rightIgnored())
        }
        SettingsGroup("Live TV")
        // Plugins: Dispatcharr admins only (Standard users have no use for them).
        if (session != null && !session.isDirect && io.github.petebutty.viewarr.ui.profiles.isAdmin()) MenuItem(text = "Dispatcharr plugins", selected = false, onClick = onPlugins, modifier = Modifier.hint("Plugins on your Dispatcharr server (admins only).").focusRequester(pluginsItem).rightDoes(onPlugins))
        MenuItem(text = "Refresh channels & guide", selected = false, onClick = onRefresh, modifier = Modifier.hint("Loads the channel list and TV guide again now.").rightIgnored())
        SettingsGroup("This TV")
        // Viewarr's setup kept on the Dispatcharr server (Dispatcharr sign-ins only).
        val canSignOut = io.github.petebutty.viewarr.data.profiles.Access.can(io.github.petebutty.viewarr.data.profiles.Lock.SIGNOUT)
        if (canSignOut && session != null && !session.isDirect) io.github.petebutty.viewarr.ui.sync.BackupSyncItem()
        // Accounts and settings both locked: Updates is the first row.
        MenuItem(text = "Updates", selected = false, onClick = onUpdates, modifier = Modifier.hint("Check for a new version of Viewarr and install it.").focusRequester(updatesItem).then(if (!canAccounts && !canSettings) Modifier.focusRequester(firstItem) else Modifier).rightDoes(onUpdates))
        // Version, this TV, what Viewarr stores (with clearing), Live TV sources.
        MenuItem(text = "About", selected = false, onClick = onAbout, modifier = Modifier.hint("Version, this TV, what Viewarr stores (and clearing it), and your live TV sources.").focusRequester(aboutItem).rightDoes(onAbout))
        var confirmUntil by remember { mutableLongStateOf(0L) }
        LaunchedEffect(confirmUntil) {
            if (confirmUntil != 0L) {
                delay(3_000)
                confirmUntil = 0L
            }
        }
        if (canSignOut) MenuItem(
            text = if (confirmUntil != 0L) "Press OK again to sign out" else "Sign out of everything",
            selected = false,
            onClick = { if (confirmUntil != 0L) onSignOut() else confirmUntil = System.currentTimeMillis() },
            modifier = Modifier.hint("Signs this TV out of all your accounts and sources.").rightIgnored(),
        )
    }
}

/** A small heading between groups of settings. */
@Composable
private fun SettingsGroup(title: String, first: Boolean = false) = MenuGroupLabel(title, first)

/** The Refresh shortcut at the end of the live TV row (not a saved shortcut). */
internal const val REFRESH_SHORTCUT = "action:refresh"

/** A group's heading in the side menu's lists: small capitals with a rule above, so it can't be mistaken for an item. */
@Composable
internal fun MenuGroupLabel(title: String, first: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (first) 4.dp else 14.dp, bottom = 2.dp)) {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.TextSecondary.copy(alpha = 0.25f)))
        Text(
            title.uppercase(), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 1.4.sp,
            color = AppColors.Accent.copy(alpha = 0.85f), modifier = Modifier.padding(top = if (first) 0.dp else 10.dp),
        )
    }
}

/** Search: a text box (the TV keyboard), then channel matches and poster rows of results. */
@Composable
private fun SearchPage(
    guide: GuideViewModel,
    /** Dispatcharr, for the channels' logos. */
    serverUrl: String,
    focusEnabled: Boolean,
    onOpenMenu: () -> Unit,
    onExit: () -> Unit,
    onChannel: (ChannelListItem) -> Unit,
    /** A programme from the guide that hasn't started: its details (with Record). */
    onProgramme: (ChannelListItem, io.github.petebutty.viewarr.data.local.GuideProgram) -> Unit,
    /** Catch-up: a past programme picked from the Catch-up row (plays it from the start). */
    onCatchup: (ChannelListItem, io.github.petebutty.viewarr.data.local.GuideProgram) -> Unit = { _, _ -> },
    /** Popped up with the search button: the keyboard opens straight away, ready to type. */
    openKeyboard: Boolean = false,
    /** Opened on this search (Watch from the start, a recent search). */
    startQuery: String? = null,
    /** Opened from the menu's Voice search: start listening straight away. */
    startVoice: Boolean = false,
    /** Opened with the Catch-up pill picked (the guide's "Search catch-up"). */
    startCatchup: Boolean = false,
    /** A music artist or album picked: open it on the Music page. */
    onMusic: (io.github.petebutty.viewarr.ui.music.MusicCard) -> Unit = {},
) {
    var fieldFocused by remember { mutableStateOf(false) }
    // Typing in the box (else read-only: no keyboard just for focus passing through).
    var typing by remember { mutableStateOf(openKeyboard) }
    val searchScope = androidx.compose.runtime.rememberCoroutineScope()
    val media: MediaViewModel = hiltViewModel(key = "media-search")
    var query by rememberSaveable { mutableStateOf(startQuery ?: "") }
    val fieldFocus = remember { FocusRequester() }
    val rowsFocus = remember { FocusRequester() }
    // Down from the box: quick picks first, then the filters, then the results.
    val picksFocus = remember { FocusRequester() }
    val filtersFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val detailOpen by media.detail.collectAsStateWithLifecycle()
    val playingOpen by media.playing.collectAsStateWithLifecycle()
    // The box only takes focus on the search page itself, never behind a title's page or the player
    // (focus passing through it made the keyboard flash up).
    val fieldFree = detailOpen == null && (playingOpen == null || io.github.petebutty.viewarr.ui.media.VodHost.mini)
    val channels by androidx.compose.runtime.produceState(emptyList<ChannelListItem>(), query) {
        delay(400)
        value = guide.searchChannels(query)
    }
    // Live TV: programmes in the guide whose title matches, on now and coming up.
    val onTv by androidx.compose.runtime.produceState(emptyList<Pair<ChannelListItem, io.github.petebutty.viewarr.data.local.GuideProgram>>(), query) {
        delay(400)
        value = runCatching { guide.searchPrograms(query) }.getOrDefault(emptyList())
    }
    // Catch-up: programmes that have been on, on channels with catch-up (Dispatcharr's own programme search).
    val catchupHits by androidx.compose.runtime.produceState(emptyList<Pair<ChannelListItem, io.github.petebutty.viewarr.data.local.GuideProgram>>(), query) {
        delay(500)
        value = runCatching { guide.searchCatchup(query) }.getOrDefault(emptyList())
    }
    val filter by media.searchFilter.collectAsStateWithLifecycle()
    // Catch-up: its own pill shows only past programmes; All and Live TV show them alongside the rest.
    val catchupOnly = filter == io.github.petebutty.viewarr.ui.media.SearchFilter.Catchup
    val searchFocus by media.searchFocus.collectAsStateWithLifecycle()
    val catchupFirst = searchFocus == io.github.petebutty.viewarr.ui.media.SearchFilter.Catchup
    val showTv = filter == io.github.petebutty.viewarr.ui.media.SearchFilter.All || filter == io.github.petebutty.viewarr.ui.media.SearchFilter.LiveTv || catchupOnly
    val showMusic = filter == io.github.petebutty.viewarr.ui.media.SearchFilter.All || filter == io.github.petebutty.viewarr.ui.media.SearchFilter.Music
    val musicVm: io.github.petebutty.viewarr.ui.music.MusicViewModel = hiltViewModel()
    val musicOn by musicVm.connected.collectAsStateWithLifecycle()
    LaunchedEffect(query) { media.search(query) }
    // Quick picks under the box while typing: tap one instead of typing the rest.
    val picks by androidx.compose.runtime.produceState(emptyList<String>(), query) {
        if (query.trim().length < 2) { value = emptyList(); return@produceState }
        delay(300)
        value = media.suggestions(query)
    }
    // Voice search: Android's speech recogniser listens, the words become the search.
    val context = androidx.compose.ui.platform.LocalContext.current
    // The mic is always there: listening in the app, or the keyboard's mic on a box without a recogniser.
    val canVoice = true
    val voice = io.github.petebutty.viewarr.ui.common.rememberVoiceSearch(
        onResult = { said ->
            query = said
            searchScope.launch { delay(900); runCatching { rowsFocus.requestFocus() } }
        },
        onKeyboard = {
            typing = true
            runCatching { fieldFocus.requestFocus() }
            searchScope.launch { kotlinx.coroutines.android.awaitFrame(); keyboard?.show() }
        },
    )
    fun listen() = voice.toggle()
    LaunchedEffect(Unit) { if (startVoice) { repeat(3) { awaitFrame() }; listen() } }
    // Remembered for the menu's Recent searches when search closes (what you last searched, not every letter typed).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val latestQuery by androidx.compose.runtime.rememberUpdatedState(query)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { io.github.petebutty.viewarr.ui.common.RecentSearches.add(ctx, latestQuery) } }
    val micFocus = remember { FocusRequester() }
    // Each search starts showing everything.
    LaunchedEffect(Unit) {
        media.setSearchFilter(if (startCatchup) io.github.petebutty.viewarr.ui.media.SearchFilter.Catchup else io.github.petebutty.viewarr.ui.media.SearchFilter.All)
        media.setSearchFocus(null)
    }
    // Focus the box when the page opens (opened with a title already in it, e.g. from the guide when no match was
    // found: the box too, so the remote isn't left on nothing).
    LaunchedEffect(Unit) {
        if (query.isNotEmpty()) {
            repeat(3) { awaitFrame() }
            if (focusEnabled) runCatching { fieldFocus.requestFocus() }
            return@LaunchedEffect
        }
        awaitFrame()
        runCatching { fieldFocus.requestFocus() }
        if (openKeyboard) { awaitFrame(); keyboard?.show() }
    }
    // Live TV results (On TV, Channels): at the top when the search is a channel, else after the films and shows.
    // Catch-up results (Search → Catch-up row): first when its pill is looked at.
    val catchupRow: @Composable () -> Unit = {
                // Catch-up: shown and gone, still in its channel's catch-up window. OK plays it from the start.
                if (catchupHits.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Catch-up", fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.offset(x = (-10).dp)) {
                        items(catchupHits, key = { (c, p) -> "cu:${c.id}:${p.startTime}" }) { (ch, p) ->
                            androidx.tv.material3.Surface(
                                onClick = { onCatchup(ch, p) },
                                modifier = Modifier.width(300.dp).focusProperties { canFocus = fieldFree && focusEnabled },
                                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                    containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
                                    focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
                                ),
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(width = 52.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                                        io.github.petebutty.viewarr.ui.guide.channelLogo(serverUrl, ch)?.let { logo ->
                                            coil.compose.AsyncImage(model = logo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(p.title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        // Which episode: "S2 · E5 · Feel Her Love". The name is the sub-title, else (Sky's
                                        // guides) the start of the description before its colon.
                                        val epName = p.subTitle ?: p.description?.substringBefore(": ", "")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }
                                        val epLine = listOfNotNull(p.season?.let { "S$it" }, p.episode?.let { "E$it" }, epName).joinToString(" · ")
                                        if (epLine.isNotEmpty()) Text(epLine, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text(
                                            "↺ " + io.github.petebutty.viewarr.ui.theme.Dates.dayTime(p.startTime) + " on " + ch.name,
                                            fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
    }
    val tvSections: @Composable () -> Unit = {
            if (query.trim().length >= 2 && showTv) {
                if (catchupFirst) catchupRow()
                // On TV: guide programmes that match. On now → watch; later → details and Record.
                if (onTv.isNotEmpty() && !catchupOnly) {
                    Spacer(Modifier.height(12.dp))
                    Text("On TV", fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
                    val nowMs = System.currentTimeMillis()
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.offset(x = (-10).dp)) {
                        items(onTv, key = { (c, p) -> "${c.id}:${p.rowId}" }) { (ch, p) ->
                            val live = p.startTime <= nowMs
                            androidx.tv.material3.Surface(
                                onClick = { if (live) onChannel(ch) else onProgramme(ch, p) },
                                modifier = Modifier.width(250.dp).focusProperties { canFocus = fieldFree && focusEnabled },
                                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                    containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
                                    focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
                                ),
                            ) {
                                // The channel's logo beside the programme, so it's clear it's a showing on that channel.
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(width = 52.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                                        io.github.petebutty.viewarr.ui.guide.channelLogo(serverUrl, ch)?.let { logo ->
                                            coil.compose.AsyncImage(model = logo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(p.title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text(
                                            (if (live) "● Now on " else whenText(p.startTime, nowMs) + " on ") + ch.name,
                                            fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (!catchupFirst) catchupRow()
                Spacer(Modifier.height(12.dp))
                if (channels.isNotEmpty() && !catchupOnly) {
                    Text("Channels", fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.offset(x = (-10).dp)) {
                        items(channels, key = { it.id }) { ch ->
                            // The channel's logo, name and number, like the guide.
                            androidx.tv.material3.Surface(
                                onClick = { onChannel(ch) },
                                // Not while a title, its episodes or a player is open over search: focus that fell
                                // here (e.g. while an episode's source list opened) started a live channel.
                                modifier = Modifier.width(250.dp).focusProperties { canFocus = fieldFree && focusEnabled },
                                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                    containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
                                    focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
                                ),
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(width = 64.dp, height = 38.dp), contentAlignment = Alignment.Center) {
                                        io.github.petebutty.viewarr.ui.guide.channelLogo(serverUrl, ch)?.let { logo ->
                                            coil.compose.AsyncImage(model = logo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(ch.name, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    ch.number?.let { n -> Text(if (n % 1.0 == 0.0) n.toInt().toString() else n.toString(), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp)) }
                                }
                            }
                        }
                    }
                }
            }
    }
    val mediaRowsNow by media.rows.collectAsStateWithLifecycle()
    val q0 = query.trim()
    val titleHit = mediaRowsNow.rows.any { r -> r.items.any { it.title.equals(q0, ignoreCase = true) } }
    val musicFirst = searchFocus == io.github.petebutty.viewarr.ui.media.SearchFilter.Music
    // Live TV / Catch-up looked at: the TV results move above the rest (music too, when its pill is looked at).
    val tvFirst = musicFirst || searchFocus == io.github.petebutty.viewarr.ui.media.SearchFilter.LiveTv || catchupFirst ||
        (q0.length >= 2 && !titleHit && (channels.any { it.name.startsWith(q0, ignoreCase = true) } || onTv.any { it.second.title.equals(q0, ignoreCase = true) }))
    MediaScreen(
        type = MediaType.Movie,
        focusRequester = rowsFocus,
        focusEnabled = focusEnabled,
        onOpenMenu = onOpenMenu,
        // Back: from the results to the search box; from the box, "press Back again to exit".
        onBack = {
            if (fieldFocused) onExit()
            else {
                runCatching { fieldFocus.requestFocus() }
                // Back to the box without the keyboard popping up (OK on the box opens it).
                searchScope.launch { kotlinx.coroutines.android.awaitFrame(); keyboard?.hide(); kotlinx.coroutines.android.awaitFrame(); keyboard?.hide() }
            }
        },
        searchHeader = {
            // Quick picks only while you're up here typing: down in the results they'd just take room.
            var headerFocused by remember { mutableStateOf(false) }
            Column(Modifier.onFocusChanged { headerFocused = it.hasFocus }.padding(start = 48.dp, end = 48.dp, top = 18.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                // Voice: a round mic button before the bar, as on Android TV's own search.
                if (canVoice) {
                    androidx.tv.material3.Surface(
                        onClick = ::listen,
                        modifier = Modifier.size(44.dp).focusRequester(micFocus).focusProperties { canFocus = fieldFree && focusEnabled },
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                            containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
                            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
                        ),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.tv.material3.Icon(io.github.petebutty.viewarr.ui.common.AppIcons.Mic, contentDescription = "Voice search", modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                // A slim pill-shaped bar (magnifier, then the text), like Android TV's own search.
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Accent),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = AppColors.TextPrimary),
                    // The keyboard only when asked for (OK on the box, or search opened with the search button): focus
                    // passing through the box as pages close (a title opened from the guide, then Back) made it flash.
                    // A read-only box never brings it up, so the box is read-only until OK.
                    readOnly = !typing,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        keyboard?.hide()
                        if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) runCatching { rowsFocus.requestFocus() }
                    }),
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(44.dp)
                        .focusRequester(fieldFocus)
                        .focusProperties { canFocus = fieldFree && focusEnabled }
                        .onFocusChanged { fieldFocused = it.isFocused; if (!it.isFocused) { typing = false; keyboard?.hide() } }
                        // The text box would keep the remote: Down always leaves it for the results.
                        .onPreviewKeyEvent { e ->
                            // Left in an empty box: the mic if there is one, else the menu (with text, Left moves the cursor).
                            if (e.key == Key.DirectionLeft && query.isEmpty()) {
                                // (Asked for directly: a box being typed in keeps Left for its cursor.)
                                if (e.type == KeyEventType.KeyDown) { if (canVoice) runCatching { micFocus.requestFocus() } else onOpenMenu() }
                                return@onPreviewKeyEvent true
                            }
                            if (!typing && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                                if (e.type == KeyEventType.KeyUp) {
                                    typing = true
                                    searchScope.launch { kotlinx.coroutines.android.awaitFrame(); keyboard?.show() }
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                val next = when {
                                    picks.isNotEmpty() -> picksFocus
                                    query.trim().length >= 2 -> filtersFocus
                                    else -> rowsFocus
                                }
                                if (runCatching { next.requestFocus() }.isFailure) runCatching { rowsFocus.requestFocus() }
                                true
                            } else false
                        },
                    decorationBox = { inner ->
                        Row(
                            Modifier.fillMaxSize()
                                .background(AppColors.Panel, androidx.compose.foundation.shape.RoundedCornerShape(50))
                                .border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) AppColors.Accent else AppColors.TextSecondary.copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Icon(io.github.petebutty.viewarr.ui.common.AppIcons.Search, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (voice.listening) Text("Listening… say a film, show, person or channel", fontSize = 15.sp, color = AppColors.Accent, maxLines = 1)
                                else if (query.isEmpty()) Text(if (musicOn == true) "Search films, shows, music, people, channels and the TV guide" else "Search films, shows, people, channels and the TV guide", fontSize = 15.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                inner()
                            }
                        }
                    },
                )
                }
                // Quick picks: the best-known titles and names for what's typed so far.
                if (picks.isNotEmpty() && headerFocused) {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(picks.size, key = { picks[it] }) { i ->
                            val pick = picks[i]
                            androidx.tv.material3.Surface(
                                onClick = {
                                    query = pick
                                    searchScope.launch { delay(900); runCatching { rowsFocus.requestFocus() } }
                                },
                                modifier = (if (i == 0) Modifier.focusRequester(picksFocus) else Modifier).focusProperties { canFocus = fieldFree && focusEnabled },
                                // No growing when highlighted: it was cut off at the edges of the strip.
                                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
                                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                    containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
                                    focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
                                ),
                                border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                                    border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, AppColors.Accent.copy(alpha = 0.5f)), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
                                ),
                            ) { Text("⌕  $pick", fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) }
                        }
                    }
                }
                // Filters: narrow the results to one kind.
                if (query.trim().length >= 2) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        io.github.petebutty.viewarr.ui.media.SearchFilter.entries.filter { it != io.github.petebutty.viewarr.ui.media.SearchFilter.Music || musicOn == true }.forEach { f ->
                            val on = f == filter
                            androidx.tv.material3.Surface(
                                onClick = { media.setSearchFilter(f) },
                                modifier = (if (on) Modifier.focusRequester(filtersFocus) else Modifier).focusProperties { canFocus = fieldFree && focusEnabled }
                                    // Looking at a pill brings its kind of result to the top (OK filters to just that kind).
                                    .onFocusChanged { if (it.isFocused) media.setSearchFocus(f) },
                                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
                                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                    containerColor = if (on) AppColors.TextPrimary else Color.Transparent,
                                    contentColor = if (on) AppColors.Background else AppColors.TextSecondary,
                                    focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
                                ),
                                border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                                    border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, AppColors.TextSecondary.copy(alpha = 0.5f)), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
                                ),
                            ) { Text(f.label, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
                        }
                    }
                }
                if (tvFirst) {
                    if (musicFirst && showMusic && query.trim().length >= 2) io.github.petebutty.viewarr.ui.music.MusicSearchResults(query, fieldFree && focusEnabled, onMusic)
                    tvSections()
                    if (!musicFirst && showMusic && query.trim().length >= 2) io.github.petebutty.viewarr.ui.music.MusicSearchResults(query, fieldFree && focusEnabled, onMusic)
                }
            }
        },
        searchFooter = if (tvFirst) null else ({ Column(Modifier.padding(horizontal = 48.dp)) { tvSections(); if (showMusic && query.trim().length >= 2) io.github.petebutty.viewarr.ui.music.MusicSearchResults(query, fieldFree && focusEnabled, onMusic) } }),
        vm = media,
    )
}

/** See-through channel list over live TV: Up/Down to browse, OK to switch, Back or Left to close. Hides after 8 s idle. */
@Composable
private fun ChannelListOverlay(
    vm: GuideViewModel,
    serverUrl: String,
    current: ChannelListItem,
    now: Long,
    onPick: (ChannelListItem) -> Unit,
    onClose: () -> Unit,
    /** A programme that's been on, from the programme panel: play it from catch-up. */
    onCatchup: (ChannelListItem, GuideProgram) -> Unit = { _, _ -> },
    /** A programme still to come: record / remind options. */
    onFuture: (ChannelListItem, GuideProgram) -> Unit = { _, _ -> },
) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    // Right on a channel: its programmes in a panel beside the list (Left goes back).
    var focusedCh by remember { mutableStateOf<ChannelListItem?>(null) }
    var epgFor by remember { mutableStateOf<ChannelListItem?>(null) }
    val epgFocus = remember { FocusRequester() }
    // Closing the programme panel puts you back on the channel it was for.
    var returnTo by remember { mutableStateOf<Int?>(null) }
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(epgFor) {
        if (epgFor != null) { returnTo = epgFor?.id; return@LaunchedEffect }
        if (returnTo == null) return@LaunchedEffect
        awaitFrame()
        runCatching { returnFocus.requestFocus() }
    }
    val programs by vm.programs.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val groupId by vm.groupId.collectAsStateWithLifecycle()
    var showGroups by remember { mutableStateOf(false) }
    val groupFocus = remember { FocusRequester() }
    // One more Left from the groups: your Live TV sources (several Dispatcharr instances, logins, playlists).
    val sourcesVm: io.github.petebutty.viewarr.ui.settings.LiveSourcesViewModel = hiltViewModel()
    val savedSources by sourcesVm.saved.collectAsStateWithLifecycle()
    val activeSource by sourcesVm.active.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }
    val sourceFocus = remember { FocusRequester() }
    LaunchedEffect(showSources) { if (showSources) { awaitFrame(); runCatching { sourceFocus.requestFocus() } } else if (showGroups) { awaitFrame(); runCatching { groupFocus.requestFocus() } } }
    val start = channels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, start - 4))
    val focus = remember { FocusRequester() }
    var activity by remember { mutableLongStateOf(System.nanoTime()) }
    BackHandler { if (epgFor != null) epgFor = null else if (showSources) showSources = false else if (showGroups) showGroups = false else onClose() }
    // Focus: the current channel (or the first one after a group change), or the selected group.
    var groupSeen by remember { mutableStateOf(groupId) }
    LaunchedEffect(showGroups, groupId) {
        val groupChanged = groupId != groupSeen
        groupSeen = groupId
        awaitFrame()
        if (showGroups) {
            // Opening the groups leaves the channel list where it is (no jump); previewing another group shows its
            // channels from the top (or around the one playing).
            if (groupChanged) listState.scrollToItem(maxOf(0, channels.indexOfFirst { it.id == current.id } - 4))
            runCatching { groupFocus.requestFocus() }
        } else {
            listState.scrollToItem(maxOf(0, channels.indexOfFirst { it.id == current.id } - 4))
            awaitFrame()
            runCatching { focus.requestFocus() }
        }
    }
    LaunchedEffect(activity) { delay(10_000); onClose() }
    val focusTarget = channels.firstOrNull { it.id == current.id }?.id ?: channels.firstOrNull()?.id
    Row(Modifier.fillMaxHeight().onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown) activity = System.nanoTime()
        when {
            e.type != KeyEventType.KeyDown -> false
            // The programme panel: Left closes it; Right on a channel opens it.
            epgFor != null && e.key == Key.DirectionLeft -> { epgFor = null; true }
            epgFor != null -> false
            e.key == Key.DirectionRight && !showGroups -> { focusedCh?.let { epgFor = it }; true }
            // Left from the channels opens the groups; Right (or Back) from the groups returns.
            e.key == Key.DirectionLeft && !showGroups -> { showGroups = true; true }
            e.key == Key.DirectionLeft && showGroups && !showSources && savedSources.size > 1 && io.github.petebutty.viewarr.data.profiles.Access.can(io.github.petebutty.viewarr.data.profiles.Lock.SOURCES) -> { showSources = true; true }
            e.key == Key.DirectionLeft && showSources -> true
            e.key == Key.DirectionRight && showSources -> { showSources = false; true }
            e.key == Key.DirectionRight && showGroups -> { showGroups = false; true }
            else -> false
        }
    }.trapFocus()) {
    // Moving through the groups previews each group's channels on the right (short pause so fast scrolling stays smooth).
    val previewScope = androidx.compose.runtime.rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun preview(id: Int?) {
        previewJob?.cancel()
        previewJob = previewScope.launch { delay(250); if (vm.groupId.value != id) vm.selectGroup(id) }
    }
    if (showSources) io.github.petebutty.viewarr.ui.common.GrowFromLeft(300.dp) {
        Box(Modifier.fillMaxHeight().width(300.dp).background(AppColors.Panel.copy(alpha = AppColors.palette.menuAlpha)).padding(16.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                item { Text("Sources", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)) }
                items(savedSources, key = { "src:" + it.id }) { src ->
                    val on = src.id == activeSource?.id
                    MenuItem(
                        text = src.label, selected = on, trailing = if (on) "✓" else null,
                        modifier = if (on) Modifier.focusRequester(sourceFocus) else Modifier,
                        // The list stays open on the new source's groups, to pick a channel there.
                        onClick = { if (!on) { sourcesVm.switchTo(src); vm.selectGroup(null) }; showSources = false },
                    )
                }
            }
        }
    }
    if (showGroups) io.github.petebutty.viewarr.ui.common.GrowFromLeft(280.dp) {
        Box(Modifier.fillMaxHeight().width(280.dp).background(AppColors.Panel.copy(alpha = AppColors.palette.menuAlpha)).padding(16.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                item(key = "all") {
                    MenuItem(
                        text = "All channels", selected = groupId == null,
                        modifier = (if (groupId == null) Modifier.focusRequester(groupFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) preview(null) },
                        onClick = { previewJob?.cancel(); vm.selectGroup(null); showGroups = false },
                    )
                }
                items(groups, key = { it.id }) { g ->
                    MenuItem(
                        text = g.name, selected = g.id == groupId, trailing = g.channelCount.toString(),
                        modifier = (if (g.id == groupId) Modifier.focusRequester(groupFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) preview(g.id) },
                        onClick = { previewJob?.cancel(); vm.selectGroup(g.id); showGroups = false },
                    )
                }
            }
        }
    }
    Box(
        Modifier
            .fillMaxHeight()
            .width(460.dp)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to AppColors.Panel.copy(alpha = AppColors.palette.menuAlpha), 0.9f to AppColors.Panel.copy(alpha = AppColors.palette.menuAlpha), 1f to Color.Transparent))
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 36.dp),
    ) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(channels, key = { it.id }) { ch ->
                val nowOn = GuideViewModel.programAt(programs[ch.id].orEmpty(), now)?.title
                Surface(
                    onClick = { onPick(ch) },
                    modifier = (if (ch.id == focusTarget) Modifier.focusRequester(focus) else Modifier)
                        .then(if (ch.id == returnTo) Modifier.focusRequester(returnFocus) else Modifier)
                        .focusProperties { canFocus = !showGroups && epgFor == null }
                        .onFocusChanged { if (it.isFocused) focusedCh = ch }
                        .fillMaxWidth(),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (ch.id == current.id) AppColors.CellCurrent.copy(alpha = 0.8f) else Color.Transparent,
                        contentColor = AppColors.TextPrimary,
                        focusedContainerColor = AppColors.Accent,
                        focusedContentColor = AppColors.OnAccent,
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (AppColors.palette.showNumbers) Text(ch.number?.let { n -> if (n % 1.0 == 0.0) n.toInt().toString() else n.toString() }.orEmpty(), fontSize = 13.sp, modifier = Modifier.width(44.dp))
                        Box(Modifier.size(width = 56.dp, height = 34.dp), contentAlignment = Alignment.Center) {
                            io.github.petebutty.viewarr.ui.guide.channelLogo(serverUrl, ch)?.let { logo ->
                                coil.compose.AsyncImage(
                                    model = logo,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            nowOn?.let { Text(it, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        if (ch.id == epgFor?.id) Text("›", fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
    epgFor?.let { ch -> ChannelPrograms(vm, ch, now, epgFocus, onLive = { onPick(ch) }, onCatchup = { onCatchup(ch, it) }, onFuture = { onFuture(ch, it) }) }
    }
}

/**
 * The channel list's programme panel: one channel's earlier programmes (playable from catch-up when the channel has
 * it), what's on now, and what's coming. Opens on now.
 */
@Composable
private fun ChannelPrograms(
    vm: GuideViewModel,
    ch: ChannelListItem,
    now: Long,
    focusRequester: FocusRequester,
    onLive: () -> Unit,
    onCatchup: (GuideProgram) -> Unit,
    onFuture: (GuideProgram) -> Unit,
) {
    val programs by androidx.compose.runtime.produceState<List<GuideProgram>?>(null, ch.id) { value = runCatching { vm.programsFor(ch) }.getOrDefault(emptyList()) }
    val list = programs
    val nowIndex = list?.indexOfFirst { it.endTime > now }?.coerceAtLeast(0) ?: 0
    val state = rememberLazyListState()
    LaunchedEffect(list != null) {
        if (list.isNullOrEmpty()) return@LaunchedEffect
        state.scrollToItem(maxOf(0, nowIndex - 2))
        awaitFrame(); awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }
    val hhmm = io.github.petebutty.viewarr.ui.theme.Dates.time
    val day = java.time.format.DateTimeFormatter.ofPattern("EEE d")
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val catchupFrom = if (ch.catchupDays > 0) now - ch.catchupDays * 86_400_000L else Long.MAX_VALUE
    Box(
        Modifier.fillMaxHeight().width(440.dp)
            .background(AppColors.Panel.copy(alpha = AppColors.palette.menuAlpha))
            .padding(start = 16.dp, top = 24.dp, bottom = 24.dp, end = 20.dp),
    ) {
        Column {
            Text(ch.name, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            when {
                list == null -> Text("Loading…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
                list.isEmpty() -> Text("No programme information for this channel.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
                else -> LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(list.size, key = { list[it].rowId }) { i ->
                        val p = list[i]
                        val live = p.startTime <= now && p.endTime > now
                        val past = p.endTime <= now
                        val canCatchup = past && p.startTime >= catchupFrom
                        val start = java.time.Instant.ofEpochMilli(p.startTime).atZone(zone)
                        val time = (if (start.toLocalDate() != today) start.format(day) + " " else "") + start.format(hhmm)
                        Surface(
                            onClick = { when { live -> onLive(); canCatchup -> onCatchup(p); !past -> onFuture(p) } },
                            // Hold OK: the same menu as holding OK in the guide (record, from the start, go to show…).
                            onLongClick = { onFuture(p) },
                            modifier = (if (i == nowIndex) Modifier.focusRequester(focusRequester) else Modifier).fillMaxWidth(),
                            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (live) AppColors.CellCurrent.copy(alpha = 0.8f) else Color.Transparent,
                                contentColor = if (past && !canCatchup) AppColors.TextSecondary else AppColors.TextPrimary,
                                focusedContainerColor = AppColors.Accent,
                                focusedContentColor = AppColors.OnAccent,
                            ),
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(time, fontSize = 13.sp, modifier = Modifier.width(if (start.toLocalDate() != today) 92.dp else 52.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(io.github.petebutty.viewarr.ui.guide.displayTitle(p), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val tag = when { live -> "● On now"; canCatchup -> "⟲ Catch-up: OK to play"; else -> null }
                                    tag?.let { Text(it, fontSize = 11.sp, maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Today 21:00", "Tomorrow 06:30", "Sat 15:00": when a programme starts, as the guide search shows it. */
private fun whenText(start: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(start).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (d.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE"))
    }
    return day + " " + d.format(io.github.petebutty.viewarr.ui.theme.Dates.time)
}

/**
 * The menu's Movies / Shows panel: the page's home, its catalogues (the rows on the page: OK jumps to one) and your
 * Plex / Jellyfin / Emby libraries (OK opens one as a grid, a page at a time, with sorting).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MediaMenuPanel(
    type: MediaType, focusRequester: FocusRequester, onCount: (MediaType, Int) -> Unit, onOpenPage: () -> Unit,
    catsFor: io.github.petebutty.viewarr.data.media.MediaLibrary? = null,
    onCats: (io.github.petebutty.viewarr.data.media.MediaLibrary?) -> Unit = {},
    rightMode: io.github.petebutty.viewarr.data.settings.MenuLibraryRight = io.github.petebutty.viewarr.data.settings.MenuLibraryRight.Categories,
    editing: Boolean = false,
    onEditing: (Boolean) -> Unit = {},
) {
    // Same instance as the page's, so what's picked here shows there straight away.
    val media: MediaViewModel = hiltViewModel(key = "media-${type.name}")
    LaunchedEffect(type) { media.load(type); media.loadLibraries(type) }
    // The menu's own list (Settings → Home page & menu → Menu list), separate from which rows the page shows.
    val rowsState by media.jumpRows.collectAsStateWithLifecycle()
    val libraries by media.libraries.collectAsStateWithLifecycle()
    val browse by media.browse.collectAsStateWithLifecycle()
    val current by media.currentRow.collectAsStateWithLifecycle()
    val rows = rowsState.rows
    val libs = libraries.orEmpty()
    LaunchedEffect(type) { onCount(type, 1) } // "home" is always there
    val home = if (type == MediaType.Movie) "Movies home" else "Shows home"
    var rowsOpen by remember(type) { mutableStateOf(false) }
    // Back from a library's categories: focus on that library again.
    val libFocus = remember(type) { HashMap<String, FocusRequester>() }
    var lastLib by remember(type) { mutableStateOf<String?>(null) }
    LaunchedEffect(catsFor) {
        if (catsFor != null) return@LaunchedEffect
        val key = lastLib ?: return@LaunchedEffect
        awaitFrame(); awaitFrame()
        runCatching { libFocus[key]?.requestFocus() }
    }
    // Back from editing: focus on the library edited, else the first entry.
    var wasEditing by remember(type) { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) { wasEditing = true; return@LaunchedEffect }
        if (!wasEditing) return@LaunchedEffect
        wasEditing = false
        awaitFrame(); awaitFrame()
        val key = lastLib
        if (key == null || runCatching { libFocus[key]?.requestFocus() }.isFailure) runCatching { focusRequester.requestFocus() }
    }
    if (editing) {
        MenuListEditor(media, type, startAt = lastLib, onDone = { onEditing(false) })
        return
    }
    if (catsFor != null) {
        LibraryCategories(media, type, catsFor, rightMode.groups, browse,
            onPick = { group, v -> media.openLibraryAt(catsFor, type, group, v); onCats(null); onOpenPage() },
            onNone = { media.openLibrary(catsFor, type); onCats(null); onOpenPage() })
        return
    }
    LazyColumn(
        Modifier.focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "home") {
            val go = { media.openLibrary(null, type); media.jumpTo(null); onOpenPage() }
            MenuItem(text = home, selected = browse == null && current == null, onClick = go, modifier = Modifier.rightDoes(go))
        }
        if (libs.isNotEmpty()) item(key = "h-lib") {
            MenuGroupLabel("Libraries")
        }
        items(libs, key = { "l-" + it.key }) { lib ->
            val go = { media.openLibrary(lib, type); onOpenPage() }
            // Right: the library's categories / genres / collections in this column (Settings → Remote → Menu).
            val right = if (rightMode == io.github.petebutty.viewarr.data.settings.MenuLibraryRight.Open) go else { { lastLib = lib.key; onCats(lib) } }
            MenuItem(
                text = lib.title,
                trailing = lib.source.removePrefix("Plex · ").takeIf { libs.map { l -> l.source }.distinct().size > 1 }
                    ?: if (rightMode != io.github.petebutty.viewarr.data.settings.MenuLibraryRight.Open) "›" else null,
                selected = browse?.library?.key == lib.key,
                onClick = go,
                // Hold OK: edit this list, on this library.
                onLongClick = { lastLib = lib.key; onEditing(true) },
                modifier = Modifier.focusRequester(libFocus.getOrPut(lib.key) { FocusRequester() }).rightDoes(right),
            )
        }
        // The page's rows are on the page itself; here they're folded away under one entry (OK / Right opens it),
        // so the menu stays a short list of libraries.
        if (rows.isNotEmpty()) item(key = "jump") {
            MenuItem(
                text = "Jump to a row",
                trailing = if (rowsOpen) "▾" else "›",
                selected = false,
                onClick = { rowsOpen = !rowsOpen },
                modifier = Modifier.padding(top = 8.dp).rightDoes { rowsOpen = true },
            )
        }
        if (rowsOpen) items(rows, key = { "r-" + it.id }) { row ->
            val go = { media.openLibrary(null, type); media.jumpTo(row.id); onOpenPage() }
            MenuItem(text = "   " + row.title, trailing = row.items.size.toString(), selected = browse == null && row.id == current, onClick = go, modifier = Modifier.rightDoes(go))
        }
        item(key = "edit") {
            val go = { lastLib = null; onEditing(true) }
            MenuItem(text = "✎ Edit this list", selected = false, dim = true, onClick = go, modifier = Modifier.padding(top = 8.dp).rightDoes(go))
        }
    }
}

/**
 * The menu's Movies / Shows list, edited in place (the same settings as Settings → Home page & menu → Menu list):
 * every library with On / Off (OK switches; Right picks one up, Up / Down move it, Right or OK puts it down), then
 * the rows under "Jump to a row" with On / Off. Done, Back or Left (MenuOverlay) finish.
 */
@Composable
private fun MenuListEditor(media: MediaViewModel, type: MediaType, startAt: String?, onDone: () -> Unit) {
    val all = media.allLibraries.collectAsStateWithLifecycle().value.orEmpty()
    // On first (in their order), then off: as the menu shows them.
    val libs = all.filter { !it.second } + all.filter { it.second }
    val rows by media.editableMenuRows.collectAsStateWithLifecycle()
    var carrying by remember { mutableStateOf<String?>(null) }
    val focus = remember { HashMap<String, FocusRequester>() }
    val doneFocus = remember { FocusRequester() }
    // Opened on a library (hold OK): focus there, else on Done. After a switch or a move: focus follows that library.
    var follow by remember { mutableStateOf(startAt) }
    LaunchedEffect(follow, libs.map { it.first.key to it.second }) {
        awaitFrame(); awaitFrame()
        val key = follow
        if (key == null || runCatching { focus[key]?.requestFocus() }.isFailure) runCatching { doneFocus.requestFocus() }
        follow = null
    }
    val on = libs.filter { !it.second }.map { it.first.key }
    val off = libs.filter { it.second }.map { it.first.key }
    // Opened by holding OK on a library: that held OK, let go, mustn't switch the library off.
    LazyColumn(Modifier.ignoreHeldOk(), verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "done") {
            MenuItem(text = "✓ Done", selected = false, onClick = onDone, modifier = Modifier.focusRequester(doneFocus).rightDoes(onDone))
        }
        item(key = "hint") {
            Text(if (carrying != null) "Up / Down to move it, then OK to put it down." else "OK: on / off  ·  Right: move", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        if (libs.isNotEmpty()) item(key = "h-lib") { MenuGroupLabel("Libraries") }
        itemsIndexed(libs, key = { _, it -> "l-" + it.first.key }) { i, (lib, hidden) ->
            val carried = carrying == lib.key
            fun move(by: Int) {
                val j = i + by
                if (j !in on.indices) return
                media.setLibraryOrder(on.toMutableList().apply { add(j, removeAt(i)) } + off)
                follow = lib.key
            }
            MenuItem(
                text = (if (carried) "↕ " else "") + lib.title,
                trailing = if (hidden) "Off" else "On",
                dim = hidden,
                selected = carried,
                onClick = {
                    if (carried) carrying = null
                    else if (carrying == null) {
                        // On → the end of the "on" part; off → the top of the "off" part (as in Settings).
                        media.setLibraryOrder(if (hidden) on + lib.key + (off - lib.key) else (on - lib.key) + lib.key + off)
                        media.setLibraryHidden(lib.key, !hidden)
                        follow = lib.key
                    }
                },
                modifier = Modifier.focusRequester(focus.getOrPut(lib.key) { FocusRequester() }).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent carrying != null && e.key != Key.Back && e.key != Key.DirectionLeft
                    when {
                        carrying == null && e.key == Key.DirectionRight -> { if (!hidden) carrying = lib.key; true }
                        carried && e.key == Key.DirectionRight -> { carrying = null; true }
                        carried && e.key == Key.DirectionUp -> { move(-1); true }
                        carried && e.key == Key.DirectionDown -> { move(1); true }
                        carrying != null && e.key != Key.DirectionLeft -> true
                        else -> false
                    }
                },
            )
        }
        if (rows.isNotEmpty()) item(key = "h-rows") { MenuGroupLabel("Under \"Jump to a row\"") }
        items(rows, key = { "r-" + it.first.id }) { (row, hidden) ->
            MenuItem(text = row.title, trailing = if (hidden) "Off" else "On", dim = hidden, selected = false,
                onClick = { if (carrying == null) media.setMenuRowHidden(row.id, !hidden) })
        }
    }
}

/**
 * The menu's third column: one library's categories (IPTV), genres or collections, "All" first. OK or Right opens the
 * library on that one; Left / Back go back to the library list (MenuOverlay). [onNone]: it has none, so it just opens.
 */
@Composable
private fun LibraryCategories(
    media: MediaViewModel, type: MediaType, lib: io.github.petebutty.viewarr.data.media.MediaLibrary, groups: List<String>,
    browse: MediaViewModel.LibraryBrowse?,
    onPick: (String, io.github.petebutty.viewarr.data.media.FilterValue?) -> Unit, onNone: () -> Unit,
) {
    var cats by remember(lib.key, groups) { mutableStateOf<Pair<String, List<io.github.petebutty.viewarr.data.media.FilterValue>>?>(null) }
    var loaded by remember(lib.key, groups) { mutableStateOf(false) }
    LaunchedEffect(lib.key, groups) {
        cats = media.menuCategories(lib, type, groups)
        loaded = true
        if (cats == null) onNone()
    }
    val first = remember { FocusRequester() }
    val c = cats
    // The one showing now (this library, this filter), else All.
    val currentId = c?.let { (g, _) -> browse?.takeIf { it.library.key == lib.key }?.query?.filters?.get(g)?.firstOrNull()?.id }
    LaunchedEffect(c != null) {
        if (c == null) return@LaunchedEffect
        awaitFrame(); awaitFrame()
        runCatching { first.requestFocus() }
    }
    if (c == null) {
        // Holds focus while loading (it would otherwise drop out of the menu).
        Box(Modifier.focusRequester(first).focusable()) {
            Column {
                MenuGroupLabel(lib.title, first = true)
                Text(if (loaded) "Nothing to list here" else "Loading…", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
            }
        }
        LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
        return
    }
    val (group, values) = c
    val startIdx = values.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it + 1 }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (startIdx - 3).coerceAtLeast(0))
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "h") { MenuGroupLabel(lib.title, first = true) }
        item(key = "all") {
            val go = { onPick(group, null) }
            MenuItem(text = "All", selected = currentId == null && browse?.library?.key == lib.key, onClick = go,
                modifier = (if (startIdx == 0) Modifier.focusRequester(first) else Modifier).rightDoes(go))
        }
        itemsIndexed(values, key = { _, v -> "c-" + v.id }) { i, v ->
            val go = { onPick(group, v) }
            MenuItem(text = v.label, selected = v.id == currentId, onClick = go,
                modifier = (if (startIdx == i + 1) Modifier.focusRequester(first) else Modifier).rightDoes(go))
        }
    }
}

/**
 * The menu's Music panel, like Movies and Shows: the Music home, the whole library by kind, quick play, and each music
 * source with an on/off switch (only Navidrome, only Plex…).
 */
@Composable
private fun MusicMenuPanel(focusRequester: FocusRequester, onOpenPage: () -> Unit) {
    val music: io.github.petebutty.viewarr.ui.music.MusicViewModel = hiltViewModel()
    val connected by music.connected.collectAsStateWithLifecycle()
    val sources by music.sources.collectAsStateWithLifecycle()
    val browse by music.browse.collectAsStateWithLifecycle()
    val detail by music.detail.collectAsStateWithLifecycle()
    // Entering the list lands on the page you're on (Favourites, Artists…), else Music home.
    val onKind = browse?.kind?.takeIf { detail == null }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (connected != true) {
            item(key = "setup") {
                MenuItem(text = "Connect music", selected = false, onClick = onOpenPage, modifier = Modifier.focusRequester(focusRequester).rightDoes(onOpenPage))
            }
            item(key = "setup-text") {
                Text("Navidrome, or Plex, Jellyfin or Emby with a music library (Settings → Accounts & sources).", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
            }
            return@LazyColumn
        }
        item(key = "home") {
            val go = { music.showHome(); onOpenPage() }
            MenuItem(text = "Music home", selected = browse == null && detail == null, onClick = go, modifier = (if (onKind == null) Modifier.focusRequester(focusRequester) else Modifier).rightDoes(go))
        }
        item(key = "h-browse") { MenuGroupLabel("Library") }
        items(io.github.petebutty.viewarr.ui.music.MusicBrowseKind.entries, key = { "b-" + it.name }) { k ->
            val go = { music.openBrowse(k); onOpenPage() }
            MenuItem(text = k.label, selected = browse?.kind == k && detail == null, onClick = go, modifier = (if (onKind == k) Modifier.focusRequester(focusRequester) else Modifier).rightDoes(go))
        }
        item(key = "h-play") { MenuGroupLabel("Play") }
        item(key = "shuffle") {
            val go = { music.shuffleLibrary(); onOpenPage() }
            MenuItem(text = "Shuffle my library", selected = false, onClick = go, modifier = Modifier.rightDoes(go))
        }
        if (io.github.petebutty.viewarr.playback.MusicPlayer.active) item(key = "now") {
            val go = { io.github.petebutty.viewarr.playback.MusicPlayer.nowPlayingOpen = true; onOpenPage() }
            MenuItem(text = "Now playing", trailing = io.github.petebutty.viewarr.playback.MusicPlayer.current?.title, selected = false, onClick = go, modifier = Modifier.rightDoes(go))
        }
        // Each source on or off (with more than one): e.g. only Navidrome, or only Plex.
        if (sources.size > 1) {
            item(key = "h-src") { MenuGroupLabel("Music sources") }
            items(sources, key = { "s-" + it.id }) { src ->
                MenuItem(text = src.name, trailing = if (src.on) "On" else "Off", selected = false, dim = !src.on, onClick = { music.setSourceOn(src.id, !src.on) })
            }
        }
    }
}

/**
 * The menu's Recordings panel: your newest Dispatcharr recordings (OK plays one) and what's coming up next
 * (OK opens the Recordings page).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun RecordingsPanel(focusRequester: FocusRequester, channelName: (Int) -> String? = { null }, onCount: (Int) -> Unit, onOpenPage: () -> Unit, onPlay: (VodStream) -> Unit) {
    val dvr: DvrViewModel = hiltViewModel()
    val all by dvr.recordings.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val recorded = all.filter { (it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recorded || it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recording) && it.hasFile }
        .sortedByDescending { it.start }.take(8)
    val upcoming = all.filter { it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Scheduled }.sortedBy { it.start }.take(4)
    LaunchedEffect(recorded.size + upcoming.size) { onCount(recorded.size + upcoming.size) }
    var picked by remember { mutableStateOf<io.github.petebutty.viewarr.data.dvr.DvrRecording?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val day = io.github.petebutty.viewarr.ui.theme.Dates.day
    val time = java.time.format.DateTimeFormatter.ofPattern("EEE ", java.util.Locale.UK).let { w -> java.time.format.DateTimeFormatterBuilder().append(w).append(io.github.petebutty.viewarr.ui.theme.Dates.time).toFormatter(java.util.Locale.UK) }
    fun at(ms: Long, f: java.time.format.DateTimeFormatter) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(f)
    if (recorded.isEmpty() && upcoming.isEmpty()) {
        Text("Press OK to see what's recorded and scheduled on your Dispatcharr server.", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
        return
    }
    // Two lines each: the whole title (no date squeezing it), then when, which channel and how long.
    fun details(r: io.github.petebutty.viewarr.data.dvr.DvrRecording, withTime: Boolean): String {
        val mins = ((r.end - r.start) / 60_000).toInt().takeIf { it > 0 }
        val length = mins?.let { if (it >= 60) "${it / 60}h ${it % 60}m".removeSuffix(" 0m") else "${it}m" }
        val ep = if (r.season != null && r.episode != null) "S${r.season} · E${r.episode}" else null
        return listOfNotNull(
            if (withTime) io.github.petebutty.viewarr.ui.theme.Dates.dayTime(r.start) else at(r.start, day),
            channelName(r.channelId), ep, length,
        ).joinToString("  ·  ")
    }
    // "Live" leads most sports titles in the guide; in a list of recordings it says nothing.
    fun shown(t: String) = t.removePrefix("Live ").removePrefix("LIVE ").removePrefix("Live: ").removePrefix("LIVE: ")
    LazyColumn(Modifier.focusRequester(focusRequester), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (recorded.isNotEmpty()) item(key = "h-rec") { MenuGroupLabel("Recorded", first = true) }
        items(recorded, key = { "r${it.id}" }) { r ->
            val now = r.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recording
            RecordingRow(
                title = shown(r.title),
                details = if (now) "● Recording now" + (channelName(r.channelId)?.let { "  ·  $it" } ?: "") else details(r, withTime = false),
                live = now,
                onClick = { scope.launch { dvr.playback(r)?.let(onPlay) } },
                // Hold OK: play, stop or delete.
                onLongClick = { dvr.clearMessage(); confirmDelete = false; picked = r },
            )
        }
        if (upcoming.isNotEmpty()) item(key = "h-up") { MenuGroupLabel("Coming up", first = recorded.isEmpty()) }
        items(upcoming, key = { "u${it.id}" }) { r ->
            RecordingRow(shown(r.title), details(r, withTime = true), live = false, onClick = onOpenPage, onLongClick = { dvr.clearMessage(); confirmDelete = false; picked = r })
        }
        item(key = "all") {
            Spacer(Modifier.height(6.dp))
            MenuItem(text = "All recordings", trailing = "›", selected = false, onClick = onOpenPage)
        }
    }
    picked?.let { r ->
        val message by dvr.message.collectAsStateWithLifecycle()
        val close = { picked = null; confirmDelete = false; scope.launch { awaitFrame(); runCatching { focusRequester.requestFocus() } }; Unit }
        // Done (or failed): the message shows a moment, then the options close.
        LaunchedEffect(message) { if (message != null && message != "Working…") { delay(1_400); close() } }
        androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            if (confirmDelete) {
                // "Keep it" first, so a stray OK can never delete anything.
                io.github.petebutty.viewarr.ui.dvr.ChoicePanel(
                    "Delete \"${r.title}\"?",
                    "This deletes the recording from your Dispatcharr server. It can't be undone.",
                    listOf("Keep it" to { confirmDelete = false }, "Delete permanently" to { confirmDelete = false; dvr.cancel(r); Unit }),
                    message,
                    { confirmDelete = false },
                )
            } else {
                val choices = buildList<Pair<String, () -> Unit>> {
                    when (r.state) {
                        io.github.petebutty.viewarr.data.dvr.DvrState.Recorded -> {
                            if (r.hasFile) add("Play" to { picked = null; scope.launch { dvr.playback(r)?.let(onPlay) }; Unit })
                            add("Delete recording…" to { confirmDelete = true })
                        }
                        io.github.petebutty.viewarr.data.dvr.DvrState.Recording -> add("Stop recording" to { dvr.stop(r); Unit })
                        io.github.petebutty.viewarr.data.dvr.DvrState.Scheduled -> add("Cancel recording" to { dvr.cancel(r); Unit })
                        else -> add("Remove from list" to { dvr.cancel(r); Unit })
                    }
                    add("Close" to close)
                }
                io.github.petebutty.viewarr.ui.dvr.ChoicePanel(r.title, at(r.start, time), choices, message, close)
            }
        }
    }
}


/** A recording in the menu: its title, and underneath when / channel / length (red dot while recording). */
@Composable
private fun RecordingRow(title: String, details: String, live: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
            pressedContainerColor = AppColors.Accent, pressedContentColor = AppColors.OnAccent,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                details, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = when { focused -> AppColors.OnAccent.copy(alpha = 0.8f); live -> Color(0xFFE53935); else -> AppColors.TextSecondary },
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}


/** The menu's Search panel: start a search (typed or by voice), or repeat a recent one. */
@Composable
private fun SearchMenuPanel(onSearch: (String?) -> Unit, onVoice: () -> Unit, focus: FocusRequester, musicOn: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { io.github.petebutty.viewarr.ui.common.RecentSearches.load(ctx) }
    val recent = io.github.petebutty.viewarr.ui.common.RecentSearches.items
    Column(Modifier.focusRequester(focus).focusGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MenuItem(text = "Search…", selected = false, onClick = { onSearch(null) },
            leading = { androidx.compose.material3.Icon(io.github.petebutty.viewarr.ui.common.AppIcons.Search, null, modifier = Modifier.size(18.dp), tint = androidx.tv.material3.LocalContentColor.current) })
        MenuItem(text = "Voice search", selected = false, onClick = onVoice,
            leading = { androidx.compose.material3.Icon(io.github.petebutty.viewarr.ui.common.AppIcons.Mic, null, modifier = Modifier.size(18.dp), tint = androidx.tv.material3.LocalContentColor.current) })
        Text(
            if (musicOn) "Films, shows, music, your libraries, addons and TV channels." else "Films, shows, your libraries, addons and TV channels.",
            fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (recent.isNotEmpty()) {
            MenuGroupLabel("Recent searches")
            recent.toList().forEach { q -> MenuItem(text = q, selected = false, onClick = { onSearch(q) }) }
            MenuItem(text = "Clear recent searches", selected = false, dim = true, onClick = { io.github.petebutty.viewarr.ui.common.RecentSearches.clear(ctx) })
        }
    }
}
