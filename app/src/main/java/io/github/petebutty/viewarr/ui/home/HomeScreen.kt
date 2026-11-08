package io.github.petebutty.viewarr.ui.home

import io.github.petebutty.viewarr.ui.theme.appBackground

import io.github.petebutty.viewarr.ui.common.slideInFromLeft
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
    Recordings("Recordings", true),
    Search("Search", true),
    Settings("Settings", true),
}

private val LogoFont = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(io.github.petebutty.viewarr.R.font.poppins_bold))

/** What fills the screen behind the menu. */
enum class Page { Tv, Movies, Shows, Recordings, Search }

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
    var recordingPlayback by remember { mutableStateOf<VodStream?>(null) }
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
    // Channel number typed on the remote's number keys.
    var digits by remember { mutableStateOf("") }
    // Up/Down while watching: a see-through channel list over the picture.
    var channelListOpen by remember { mutableStateOf(false) }
    // Long-press Back on the guide returns to the channel that's playing, full screen.
    var backLongPress by remember { mutableStateOf(false) }
    // Start page from Settings, applied once when the app opens.
    var startApplied by rememberSaveable { mutableStateOf(false) }
    var digitsToken by remember { mutableLongStateOf(0L) }
    var digitsMessage by remember { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var bannerToken by remember { mutableLongStateOf(0L) }
    var bannerVisible by remember { mutableStateOf(false) }
    var osdVisible by remember { mutableStateOf(false) }
    // Several channels on screen at once (from the player menu).
    var multiView by remember { mutableStateOf(false) }
    // "Watch from the start" from the guide: search opens on the programme's title.
    var searchStart by remember { mutableStateOf<String?>(null) }
    val searchMedia: MediaViewModel = hiltViewModel(key = "media-search")
    val fromStartScope = androidx.compose.runtime.rememberCoroutineScope()
    // Leaving full screen (guide, another page) ends multi-view; it mustn't reappear next time.
    LaunchedEffect(fullscreen) { if (!fullscreen) multiView = false }
    var osdAudio by remember { mutableStateOf(false) }
    var osdToken by remember { mutableLongStateOf(0L) }
    val guideFocus = remember { FocusRequester() }
    val playerFocus = remember { FocusRequester() }
    val playing by playback.channel.collectAsStateWithLifecycle()
    val playState by playback.state.collectAsStateWithLifecycle()
    val catchup by playback.catchup.collectAsStateWithLifecycle()
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

    fun showOsd(audio: Boolean = false) {
        osdAudio = audio
        bannerVisible = false
        osdVisible = true
        osdToken = System.nanoTime()
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
    LaunchedEffect(osdToken) {
        if (osdVisible) {
            delay(8_000)
            osdVisible = false
        }
    }
    LaunchedEffect(remote.loaded) {
        if (!remote.loaded || startApplied) return@LaunchedEffect
        startApplied = true
        when (remote.startPage) {
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
    LaunchedEffect(menuOpen, fullscreen, osdVisible, showAccounts, page, recordTarget == null, recordingPlayback == null, infoTarget == null, channelListOpen, quickSearch) {
        if (channelListOpen || quickSearch) return@LaunchedEffect
        if (recordTarget != null || recordingPlayback != null || infoTarget != null) return@LaunchedEffect
        awaitFrame()
        runCatching {
            when {
                fullscreen && !osdVisible -> playerFocus.requestFocus()
                !fullscreen && !menuOpen && !showAccounts && !quickSearch -> if (page == Page.Tv) guideFocus.requestFocus() else mediaFocus.requestFocus()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { playback.resume() }

    // Back on a page (guide, Movies, Shows, Recordings) asks before leaving: a second Back within a few
    // seconds exits. The menu handles its own Back (list → main menu → closed).
    val activity = LocalContext.current as? Activity
    var exitArmed by remember { mutableLongStateOf(0L) }
    fun backOnPage() {
        if (exitArmed != 0L) activity?.finish() else exitArmed = System.currentTimeMillis()
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed != 0L) { delay(2_500); exitArmed = 0L }
    }
    // Back returns to live TV first when browsing the past/future, then asks to exit (or opens the menu).
    BackHandler(enabled = page == Page.Tv && !menuOpen && !fullscreen && !showAccounts && !quickSearch) {
        when {
            !vm.isAtNow() -> vm.goToNow()
            remote.guideBack == GuideBack.Menu -> menuOpen = true
            else -> backOnPage()
        }
    }
    BackHandler(enabled = fullscreen && !osdVisible) {
        fullscreen = false
        bannerVisible = false
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
        delay(if (digitsMessage != null) 2_000 else 1_800)
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

    fun openPage(p: Page) {
        // Search never replaces what's on screen: it slides over it, and Back returns you there.
        if (p == Page.Search) {
            menuOpen = false
            quickSearch = true
            return
        }
        if (p != Page.Tv && page == Page.Tv) {
            playback.stop()
            fullscreen = false
        }
        page = p
        menuOpen = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
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
                // The search button (Settings → Remote): pops search up over whatever's on screen.
                if (searchHeld) {
                    if (e.type == KeyEventType.KeyUp) searchHeld = false
                    return@onPreviewKeyEvent true
                }
                if (!quickSearch && !showAccounts && recordingPlayback == null && page != Page.Search && e.type == KeyEventType.KeyDown) {
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
                if (quickSearch) return@onPreviewKeyEvent false
                if (e.key == Key.Back) {
                    val canReturn = page == Page.Tv && !fullscreen && !menuOpen && !showAccounts && !quickSearch && playing != null &&
                        recordTarget == null && infoTarget == null
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 && canReturn && !backLongPress -> {
                            backLongPress = true
                            fullscreen = true
                            showBanner()
                            return@onPreviewKeyEvent true
                        }
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
                focusEnabled = !menuOpen && !showAccounts && !quickSearch && infoTarget == null && recordTarget == null,
                onProgramme = { ch, prog -> recordTarget = ch to prog },
                onOpenMenu = { menuOpen = true },
                onExit = ::backOnPage,
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
        } else if (page == Page.Recordings) {
            RecordingsScreen(
                vm = dvr,
                focusRequester = mediaFocus,
                focusEnabled = !menuOpen && !showAccounts && !quickSearch && recordingPlayback == null,
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
                // Holding OK: record options for anything live or still to come.
                onLongSelect = { channel, program ->
                    if (program != null && program.endTime > System.currentTimeMillis()) recordTarget = channel to program
                },
            )
        }

        val channel = playing.takeIf { page == Page.Tv }
        val list = channel?.let { programsOf(it) }.orEmpty()
        val current = catchup ?: GuideViewModel.programAt(list, now)
        if (channel != null && !(multiView && fullscreen)) {
            val next = if (catchup != null) null else current?.let { c -> list.firstOrNull { it.startTime >= c.endTime } }
            PlayerLayer(
                vm = playback,
                fullscreen = fullscreen,
                bannerVisible = bannerVisible && !osdVisible,
                current = current,
                next = next,
                now = now,
                serverUrl = session?.serverUrl.orEmpty(),
                modifier = if (fullscreen) Modifier.fillMaxSize()
                else Modifier.padding(start = 12.dp, top = 20.dp).size(width = 320.dp, height = 180.dp).clip(RoundedCornerShape(8.dp)),
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
                    .focusable(enabled = !osdVisible && !channelListOpen)
                    .onPreviewKeyEvent { e ->
                        if (osdVisible || channelListOpen || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp, Key.DirectionDown -> when (remote.zap) {
                                ZapDirection.ChannelList -> channelListOpen = true
                                ZapDirection.UpNext -> zap(if (e.key == Key.DirectionUp) 1 else -1)
                                ZapDirection.UpPrevious -> zap(if (e.key == Key.DirectionUp) -1 else 1)
                            }
                            Key.ChannelDown -> zap(-1)
                            Key.ChannelUp -> zap(1)
                            // Menu = audio & subtitles, as in the film players.
                            Key.Menu -> if (playState is PlaybackState.Error) playback.retry() else showOsd(audio = true)
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
                                playState is PlaybackState.Error -> playback.retry()
                                remote.playerOk == PlayerOk.Banner -> showBanner()
                                else -> showOsd()
                            }
                            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                                playback.togglePause()
                                showBanner()
                            }
                            Key.DirectionLeft, Key.MediaRewind -> {
                                playback.seekBy(-remote.seek.ms)
                                showBanner()
                            }
                            Key.DirectionRight, Key.MediaFastForward -> {
                                playback.seekBy(remote.seek.ms)
                                showBanner()
                            }
                            Key.Info -> showBanner()
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
                        if (ch.id != channel.id) {
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
                    onActivity = { osdToken = System.nanoTime() },
                    onMultiView = {
                        osdVisible = false
                        if (catchup != null) playback.goLive()
                        multiView = true
                    },
                    openAudio = osdAudio,
                    onRecord = current?.takeIf { catchup == null }?.let { prog ->
                        {
                            osdVisible = false
                            recordTarget = channel to prog
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

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
                },
                onClose = { menuOpen = false },
                onSignOut = {
                    playback.stop()
                    onSignOut()
                },
            )
        }

        if (showAccounts) {
            SetupWizard(mode = SetupMode.Manage, onClose = { showAccounts = false })
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
                    focusEnabled = infoTarget == null && recordTarget == null,
                    openKeyboard = searchStart == null,
                    startQuery = searchStart,
                    onProgramme = { ch, prog -> recordTarget = ch to prog },
                    // Left at the edge stays in search (it used to close it and open the menu); Back is the way out.
                    onOpenMenu = {},
                    onExit = { quickSearch = false; searchStart = null },
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
        recordTarget?.let { (ch, prog) ->
            RecordDialog(ch, prog, dvr, onDetails = { recordTarget = null; infoTarget = ch to prog }, onWatchFromStart = {
                recordTarget = null
                // Search opens on the title (so there's something to pick if it isn't matched), then the title's page
                // opens and it plays from the best source.
                searchStart = prog.title.removePrefix("New: ")
                fullscreen = false
                quickSearch = true
                // Films in the guide usually end their description with the year, e.g. "(2014)".
                val year = prog.description?.let { Regex("\\((19|20)\\d{2}\\)").findAll(it).lastOrNull()?.value?.trim('(', ')')?.toIntOrNull() }
                fromStartScope.launch { searchMedia.watchFromStart(prog.title, prog.season, prog.episode, year) }
            }) { recordTarget = null }
        }
        recordingPlayback?.let { io.github.petebutty.viewarr.ui.media.PlayerHost(stream = it, onClose = { recordingPlayback = null }) }

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
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val groupId by vm.groupId.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    var section by remember {
        mutableStateOf(when (page) { Page.Tv -> Section.Tv; Page.Movies -> Section.Movies; Page.Shows -> Section.Shows; Page.Recordings -> Section.Recordings; Page.Search -> Section.Search })
    }
    var showAppearance by remember { mutableStateOf(false) }
    var showHomeRows by remember { mutableStateOf(false) }
    var showPlugins by remember { mutableStateOf(false) }
    val pluginsItem = remember { FocusRequester() }
    val homeRowsItem = remember { FocusRequester() }
    var showRemote by remember { mutableStateOf(false) }
    var showPlayback by remember { mutableStateOf(false) }
    val playbackItem = remember { FocusRequester() }
    var showAddons by remember { mutableStateOf(false) }
    val addonsItem = remember { FocusRequester() }
    val remoteItem = remember { FocusRequester() }
    val appearanceItem = remember { FocusRequester() }
    var showUpdates by remember { mutableStateOf(false) }
    val updatesItem = remember { FocusRequester() }
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
    val remote = LocalRemote.current
    val navFocus = remember { Section.entries.associateWith { FocusRequester() } }
    val settingsFirst = remember { FocusRequester() }
    val groupsFocus = remember { FocusRequester() }
    // Movies / Shows: the newest titles in your libraries, beside the menu.
    val recentFocus = remember { FocusRequester() }
    val recentCount = remember { androidx.compose.runtime.mutableStateMapOf<MediaType, Int>() }
    var recordingsCount by remember { mutableStateOf(0) }
    // From the guide the menu opens on the channel groups alone; Left again slides the main menu out beside them.
    var showMain by remember { mutableStateOf(!(remote.menuOpensOn == MenuOpensOn.PageList && page == Page.Tv && session != null)) }
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
                remote.menuOpensOn != MenuOpensOn.PageList -> navFocus.getValue(section).requestFocus()
                // From the guide: the channel groups (on the group being shown); Left again reaches the main list.
                page == Page.Tv && session != null -> groupsFocus.requestFocus()
                else -> navFocus.getValue(section).requestFocus()
            }
        }
    }
    fun enter(s: Section) {
        when (s) {
            Section.Tv -> runCatching { groupsFocus.requestFocus() }
            Section.Settings -> runCatching { settingsFirst.requestFocus() }
            Section.Movies -> onOpenPage(Page.Movies)
            Section.Shows -> onOpenPage(Page.Shows)
            Section.Recordings -> onOpenPage(Page.Recordings)
            Section.Search -> onOpenPage(Page.Search)
            else -> Unit
        }
    }
    // Back steps out one level: from the right-hand list to the main list, then closes the menu.
    var subListFocused by remember { mutableStateOf(false) }
    BackHandler {
        if (subListFocused && showMain) runCatching { navFocus.getValue(section).requestFocus() }.onFailure { onClose() }
        else onClose()
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        // Left again: the main list grows out beside the groups, easing them across rather than jumping.
        if (showMain) io.github.petebutty.viewarr.ui.common.GrowFromLeft(180.dp) { Column(
            Modifier
                .width(180.dp)
                .fillMaxHeight()
                .background(if (seeThrough) AppColors.MenuPanel else AppColors.palette.panel)
                .padding(horizontal = 10.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The Viewarr logo: aperture mark plus the wordmark in the banner's font.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, bottom = 20.dp)) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(io.github.petebutty.viewarr.R.drawable.logo_mark),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "viewarr",
                    fontSize = 22.sp,
                    fontFamily = LogoFont,
                    color = Color.White,
                    // Poppins sits high in its line box; trim the extra space so it centres on the mark.
                    style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                )
            }
            // Settings → Appearance → Movies & Shows off: a live-TV-only menu.
            Section.entries.filter { !(AppColors.palette.hideMedia && (it == Section.Movies || it == Section.Shows)) }.forEach { s ->
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
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && !(s == Section.Settings && (showAppearance || showRemote || showPlayback || showAddons || showUpdates || showHomeRows || showPlugins))) {
                                // Movies / Shows with recently added posters showing: Right goes onto them (OK opens the page).
                                val t = if (s == Section.Movies) MediaType.Movie else if (s == Section.Shows) MediaType.Show else null
                                // (Movies / Shows always have their list: home, libraries, catalogues.)
                                if (t != null || (s == Section.Recordings && recordingsCount > 0)) {
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
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        // Left goes back to this section's menu entry, not whichever one is geometrically closest.
                        e.key == Key.DirectionLeft && (section == Section.Tv || section == Section.Movies || section == Section.Shows || section == Section.Recordings ||
                            (section == Section.Settings && !showAppearance && !showRemote && !showPlayback && !showAddons && !showUpdates && !showHomeRows && !showPlugins)) -> {
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
                    remoteItem = remoteItem,
                    onRemote = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showRemote = true
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
                Section.Search -> Text(
                    "Press OK to search films, shows, your Plex library, addons and TV channels.",
                    fontSize = 15.sp,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(12.dp),
                )
                Section.Recordings -> RecordingsPanel(
                    focusRequester = recentFocus,
                    onCount = { n -> recordingsCount = n },
                    onOpenPage = { onOpenPage(Page.Recordings) },
                    onPlay = onPlayRecording,
                )
                Section.Movies, Section.Shows -> MediaMenuPanel(
                    type = if (section == Section.Movies) MediaType.Movie else MediaType.Show,
                    focusRequester = recentFocus,
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
private fun GroupList(focusRequester: FocusRequester, groups: List<GroupWithCount>, allCount: Int?, selectedId: Int?, onSelect: (Int?) -> Unit) {
    val selectedIndex = if (selectedId == null) 0 else groups.indexOfFirst { it.id == selectedId } + 1
    val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, selectedIndex - 3))
    val selectedFocus = focusRequester
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
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
private fun SettingsPanel(playbackItem: FocusRequester, onPlayback: () -> Unit, addonsItem: FocusRequester, onAddons: () -> Unit, remoteItem: FocusRequester, onRemote: () -> Unit, updatesItem: FocusRequester, onUpdates: () -> Unit, session: Session?, sync: SyncState, firstItem: FocusRequester, appearanceItem: FocusRequester, onAppearance: () -> Unit, homeRowsItem: FocusRequester, onHomeRows: () -> Unit, pluginsItem: FocusRequester, onPlugins: () -> Unit, onAccounts: () -> Unit, onRefresh: () -> Unit, onSignOut: () -> Unit) {
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
        Spacer(Modifier.height(12.dp))
        // Grouped: accounts, look & feel, remote, watching, live TV, then this TV (backup, updates, sign out).
        SettingsGroup("Accounts")
        MenuItem(text = "Accounts & sources", selected = false, onClick = onAccounts, modifier = Modifier.focusRequester(firstItem).rightDoes(onAccounts))
        SettingsGroup("Look & feel")
        MenuItem(text = "Appearance", selected = false, onClick = onAppearance, modifier = Modifier.focusRequester(appearanceItem).rightDoes(onAppearance))
        MenuItem(text = "Home page & menu", selected = false, onClick = onHomeRows, modifier = Modifier.focusRequester(homeRowsItem).rightDoes(onHomeRows))
        SettingsGroup("Remote")
        MenuItem(text = "Remote & navigation", selected = false, onClick = onRemote, modifier = Modifier.focusRequester(remoteItem).rightDoes(onRemote))
        SettingsGroup("Watching")
        MenuItem(text = "Playback", selected = false, onClick = onPlayback, modifier = Modifier.focusRequester(playbackItem).rightDoes(onPlayback))
        SettingsGroup("Live TV")
        if (session != null && !session.isXc) MenuItem(text = "Dispatcharr plugins", selected = false, onClick = onPlugins, modifier = Modifier.focusRequester(pluginsItem).rightDoes(onPlugins))
        MenuItem(text = "Refresh channels & guide", selected = false, onClick = onRefresh, modifier = Modifier.rightIgnored())
        SettingsGroup("This TV")
        // Viewarr's setup kept on the Dispatcharr server (Dispatcharr sign-ins only).
        if (session != null && !session.isXc) io.github.petebutty.viewarr.ui.sync.BackupSyncItem()
        MenuItem(text = "Updates", selected = false, onClick = onUpdates, modifier = Modifier.focusRequester(updatesItem).rightDoes(onUpdates))
        var confirmUntil by remember { mutableLongStateOf(0L) }
        LaunchedEffect(confirmUntil) {
            if (confirmUntil != 0L) {
                delay(3_000)
                confirmUntil = 0L
            }
        }
        MenuItem(
            text = if (confirmUntil != 0L) "Press OK again to sign out" else "Sign out of everything",
            selected = false,
            onClick = { if (confirmUntil != 0L) onSignOut() else confirmUntil = System.currentTimeMillis() },
            modifier = Modifier.rightIgnored(),
        )
    }
}

/** A small heading between groups of settings. */
@Composable
private fun SettingsGroup(title: String) {
    Text(title, fontSize = 12.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 10.dp))
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
    /** Popped up with the search button: the keyboard opens straight away, ready to type. */
    openKeyboard: Boolean = false,
    /** Opened on this search (Watch from the start). */
    startQuery: String? = null,
) {
    var fieldFocused by remember { mutableStateOf(false) }
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
    val fieldFree = detailOpen == null && playingOpen == null
    val channels by androidx.compose.runtime.produceState(emptyList<ChannelListItem>(), query) {
        delay(400)
        value = guide.searchChannels(query)
    }
    // Live TV: programmes in the guide whose title matches, on now and coming up.
    val onTv by androidx.compose.runtime.produceState(emptyList<Pair<ChannelListItem, io.github.petebutty.viewarr.data.local.GuideProgram>>(), query) {
        delay(400)
        value = runCatching { guide.searchPrograms(query) }.getOrDefault(emptyList())
    }
    val filter by media.searchFilter.collectAsStateWithLifecycle()
    val showTv = filter == io.github.petebutty.viewarr.ui.media.SearchFilter.All || filter == io.github.petebutty.viewarr.ui.media.SearchFilter.LiveTv
    LaunchedEffect(query) { media.search(query) }
    // Quick picks under the box while typing: tap one instead of typing the rest.
    val picks by androidx.compose.runtime.produceState(emptyList<String>(), query) {
        if (query.trim().length < 2) { value = emptyList(); return@produceState }
        delay(300)
        value = media.suggestions(query)
    }
    // Voice search: Android's speech recogniser listens, the words become the search.
    val context = androidx.compose.ui.platform.LocalContext.current
    val voiceIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a film, show, person or channel")
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val canVoice = remember { runCatching { context.packageManager.queryIntentActivities(voiceIntent, 0).isNotEmpty() }.getOrDefault(false) }
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!said.isNullOrEmpty()) {
            query = said
            searchScope.launch { delay(900); runCatching { rowsFocus.requestFocus() } }
        }
    }
    fun listen() { if (canVoice) runCatching { voice.launch(voiceIntent) } }
    // Each search starts showing everything.
    LaunchedEffect(Unit) { media.setSearchFilter(io.github.petebutty.viewarr.ui.media.SearchFilter.All) }
    // Focus the box only when the page first opens with nothing typed yet.
    LaunchedEffect(Unit) {
        if (query.isNotEmpty()) return@LaunchedEffect
        awaitFrame()
        runCatching { fieldFocus.requestFocus() }
        if (openKeyboard) { awaitFrame(); keyboard?.show() }
    }
    // Live TV results (On TV, Channels): at the top when the search is a channel, else after the films and shows.
    val tvSections: @Composable () -> Unit = {
            if (query.trim().length >= 2 && showTv) {
                // On TV: guide programmes that match. On now → watch; later → details and Record.
                if (onTv.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("On TV", fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
                    val nowMs = System.currentTimeMillis()
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Spacer(Modifier.height(12.dp))
                if (channels.isNotEmpty()) {
                    Text("Channels", fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val tvFirst = q0.length >= 2 && !titleHit && (channels.any { it.name.startsWith(q0, ignoreCase = true) } || onTv.any { it.second.title.equals(q0, ignoreCase = true) })
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
                        modifier = Modifier.size(44.dp).focusProperties { canFocus = fieldFree && focusEnabled },
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
                        .onFocusChanged { fieldFocused = it.isFocused; if (!it.isFocused) keyboard?.hide() }
                        // The text box would keep the remote: Down always leaves it for the results.
                        .onPreviewKeyEvent { e ->
                            // Left in an empty box: the mic if there is one, else the menu (with text, Left moves the cursor).
                            if (e.key == Key.DirectionLeft && query.isEmpty()) {
                                if (canVoice) return@onPreviewKeyEvent false
                                if (e.type == KeyEventType.KeyDown) onOpenMenu()
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
                                if (query.isEmpty()) Text("Search films, shows, people, channels and the TV guide", fontSize = 15.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
                        io.github.petebutty.viewarr.ui.media.SearchFilter.entries.forEach { f ->
                            val on = f == filter
                            androidx.tv.material3.Surface(
                                onClick = { media.setSearchFilter(f) },
                                modifier = (if (on) Modifier.focusRequester(filtersFocus) else Modifier).focusProperties { canFocus = fieldFree && focusEnabled },
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
                if (tvFirst) tvSections()
            }
        },
        searchFooter = if (tvFirst) null else ({ Column(Modifier.padding(horizontal = 48.dp)) { tvSections() } }),
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
    val start = channels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, start - 4))
    val focus = remember { FocusRequester() }
    var activity by remember { mutableLongStateOf(System.nanoTime()) }
    BackHandler { if (epgFor != null) epgFor = null else if (showGroups) showGroups = false else onClose() }
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
    if (showGroups) io.github.petebutty.viewarr.ui.common.GrowFromLeft(280.dp) {
        Box(Modifier.fillMaxHeight().width(280.dp).background(AppColors.Panel.copy(alpha = 0.92f)).padding(16.dp)) {
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
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to AppColors.Panel.copy(alpha = 0.88f), 0.85f to AppColors.Panel.copy(alpha = 0.7f), 1f to Color.Transparent))
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
    val hhmm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val day = java.time.format.DateTimeFormatter.ofPattern("EEE d")
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val catchupFrom = if (ch.catchupDays > 0) now - ch.catchupDays * 86_400_000L else Long.MAX_VALUE
    Box(
        Modifier.fillMaxHeight().width(440.dp)
            .background(AppColors.Panel.copy(alpha = 0.94f))
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
    return day + " " + d.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}

/**
 * The menu's Movies / Shows panel: the page's home, its catalogues (the rows on the page: OK jumps to one) and your
 * Plex / Jellyfin / Emby libraries (OK opens one as a grid, a page at a time, with sorting).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MediaMenuPanel(type: MediaType, focusRequester: FocusRequester, onCount: (MediaType, Int) -> Unit, onOpenPage: () -> Unit) {
    // Same instance as the page's, so what's picked here shows there straight away.
    val media: MediaViewModel = hiltViewModel(key = "media-${type.name}")
    LaunchedEffect(type) { media.load(type); media.loadLibraries(type) }
    // The menu's own list (Settings → Home page & menu → Menu list), separate from which rows the page shows.
    val rowsState by media.menuRows.collectAsStateWithLifecycle()
    val libraries by media.libraries.collectAsStateWithLifecycle()
    val browse by media.browse.collectAsStateWithLifecycle()
    val current by media.currentRow.collectAsStateWithLifecycle()
    val rows = rowsState.rows
    val libs = libraries.orEmpty()
    LaunchedEffect(type) { onCount(type, 1) } // "home" is always there
    val home = if (type == MediaType.Movie) "Movies home" else "Shows home"
    var rowsOpen by remember(type) { mutableStateOf(false) }
    LazyColumn(
        Modifier.focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "home") {
            val go = { media.openLibrary(null, type); media.jumpTo(null); onOpenPage() }
            MenuItem(text = home, selected = browse == null && current == null, onClick = go, modifier = Modifier.rightDoes(go))
        }
        if (libs.isNotEmpty()) item(key = "h-lib") {
            Text("Libraries", fontSize = 12.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
        }
        items(libs, key = { "l-" + it.key }) { lib ->
            val go = { media.openLibrary(lib, type); onOpenPage() }
            MenuItem(
                text = lib.title,
                trailing = lib.source.removePrefix("Plex · ").takeIf { libs.map { l -> l.source }.distinct().size > 1 },
                selected = browse?.library?.key == lib.key,
                onClick = go,
                modifier = Modifier.rightDoes(go),
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
    }
}

/**
 * The menu's Recordings panel: your newest Dispatcharr recordings (OK plays one) and what's coming up next
 * (OK opens the Recordings page).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun RecordingsPanel(focusRequester: FocusRequester, onCount: (Int) -> Unit, onOpenPage: () -> Unit, onPlay: (VodStream) -> Unit) {
    val dvr: DvrViewModel = hiltViewModel()
    val all by dvr.recordings.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val recorded = all.filter { (it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recorded || it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recording) && it.hasFile }
        .sortedByDescending { it.start }.take(8)
    val upcoming = all.filter { it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Scheduled }.sortedBy { it.start }.take(4)
    LaunchedEffect(recorded.size + upcoming.size) { onCount(recorded.size + upcoming.size) }
    var picked by remember { mutableStateOf<io.github.petebutty.viewarr.data.dvr.DvrRecording?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val day = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
    val time = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm")
    fun at(ms: Long, f: java.time.format.DateTimeFormatter) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(f)
    if (recorded.isEmpty() && upcoming.isEmpty()) {
        Text("Press OK to see what's recorded and scheduled on your Dispatcharr server.", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
        return
    }
    LazyColumn(Modifier.focusRequester(focusRequester), verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (recorded.isNotEmpty()) item(key = "h-rec") {
            Text("Recorded", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
        }
        items(recorded, key = { "r${it.id}" }) { r ->
            MenuItem(
                text = (if (r.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recording) "● " else "") + r.title,
                trailing = at(r.start, day),
                selected = false,
                onClick = { scope.launch { dvr.playback(r)?.let(onPlay) } },
                // Hold OK: play, stop or delete.
                onLongClick = { dvr.clearMessage(); confirmDelete = false; picked = r },
            )
        }
        if (upcoming.isNotEmpty()) item(key = "h-up") {
            Text("Coming up", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
        }
        items(upcoming, key = { "u${it.id}" }) { r ->
            MenuItem(text = r.title, trailing = at(r.start, time), selected = false, onClick = onOpenPage, onLongClick = { dvr.clearMessage(); confirmDelete = false; picked = r })
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
