package io.github.petebutty.viewarr.ui.home

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import io.github.petebutty.viewarr.ui.settings.AppearancePanel
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
import io.github.petebutty.viewarr.data.settings.MenuBack
import io.github.petebutty.viewarr.data.settings.MenuOpensOn
import io.github.petebutty.viewarr.data.settings.PlayerOk
import io.github.petebutty.viewarr.data.settings.ZapDirection
import io.github.petebutty.viewarr.data.settings.StartPage
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay

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
    val dvrRecordings by dvr.recordings.collectAsStateWithLifecycle()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    val remote = LocalRemote.current
    var page by rememberSaveable { mutableStateOf(Page.Tv) }
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
    var osdToken by remember { mutableLongStateOf(0L) }
    val guideFocus = remember { FocusRequester() }
    val playerFocus = remember { FocusRequester() }
    val playing by playback.channel.collectAsStateWithLifecycle()
    val playState by playback.state.collectAsStateWithLifecycle()
    val catchup by playback.catchup.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()

    fun showBanner() {
        bannerVisible = true
        bannerToken = System.nanoTime()
    }

    fun showOsd() {
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
            StartPage.Search -> page = Page.Search
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
    LaunchedEffect(menuOpen, fullscreen, osdVisible, showAccounts, page, recordTarget == null, recordingPlayback == null, infoTarget == null, channelListOpen) {
        if (channelListOpen) return@LaunchedEffect
        if (recordTarget != null || recordingPlayback != null || infoTarget != null) return@LaunchedEffect
        awaitFrame()
        runCatching {
            when {
                fullscreen && !osdVisible -> playerFocus.requestFocus()
                !fullscreen && !menuOpen && !showAccounts -> if (page == Page.Tv) guideFocus.requestFocus() else mediaFocus.requestFocus()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { playback.resume() }

    // Back returns to live TV first when browsing the past/future, then opens the menu.
    BackHandler(enabled = page == Page.Tv && !menuOpen && !fullscreen && !showAccounts) {
        if (remote.guideBack == GuideBack.Menu || vm.isAtNow()) menuOpen = true else vm.goToNow()
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
                showBanner()
            }
        }
    }

    LaunchedEffect(digitsToken) {
        if (digits.isEmpty() && digitsMessage == null) return@LaunchedEffect
        delay(if (digitsMessage != null) 2_000 else 1_800)
        if (digitsMessage != null) digitsMessage = null else tuneTyped()
    }

    fun openPage(p: Page) {
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
                if (e.key == Key.Back) {
                    val canReturn = page == Page.Tv && !fullscreen && !menuOpen && !showAccounts && playing != null &&
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
                if (page != Page.Tv || menuOpen || showAccounts || recordTarget != null || infoTarget != null || session == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
                focusEnabled = !menuOpen && !showAccounts,
                onOpenMenu = { menuOpen = true },
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
                focusEnabled = !menuOpen && !showAccounts && recordingPlayback == null,
                onBack = { menuOpen = true },
                onPlay = { recordingPlayback = it },
            )
        } else if (page != Page.Tv) {
            MediaScreen(
                type = if (page == Page.Movies) MediaType.Movie else MediaType.Show,
                focusRequester = mediaFocus,
                focusEnabled = !menuOpen && !showAccounts,
                onOpenMenu = { menuOpen = true },
                onBack = { if (remote.mediaBack == MediaBack.Guide) openPage(Page.Tv) else menuOpen = true },
            )
        } else if (session == null) {
            NoLiveTv(
                focusRequester = guideFocus,
                focusEnabled = !menuOpen && !showAccounts,
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
                focusEnabled = !menuOpen && !fullscreen && !showAccounts,
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
                        program != null && program.startTime > t -> infoTarget = channel to program
                    }
                },
                // Holding OK: record options for anything live or still to come.
                onLongSelect = { channel, program ->
                    if (program != null && program.endTime > System.currentTimeMillis()) recordTarget = channel to program
                },
            )
        }

        val channel = playing.takeIf { page == Page.Tv }
        val list = channel?.let { programs[it.id] }.orEmpty()
        val current = catchup ?: GuideViewModel.programAt(list, now)
        if (channel != null) {
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
                else Modifier.padding(start = 32.dp, top = 20.dp).size(width = 320.dp, height = 180.dp).clip(RoundedCornerShape(8.dp)),
            )
        }

        if (fullscreen) {
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
                            Key.Menu -> if (playState is PlaybackState.Error) playback.retry() else showOsd()
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
            if (channelListOpen && channel != null) {
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

        if (menuOpen) {
            MenuOverlay(
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

        infoTarget?.let { (ch, prog) ->
            ProgramInfoDialog(ch, prog, dvr, onRecord = { infoTarget = null; recordTarget = ch to prog }, onClose = { infoTarget = null })
        }
        recordTarget?.let { (ch, prog) -> RecordDialog(ch, prog, dvr) { recordTarget = null } }
        recordingPlayback?.let { io.github.petebutty.viewarr.ui.media.PlayerHost(stream = it, onClose = { recordingPlayback = null }) }

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
    var showRemote by remember { mutableStateOf(false) }
    var showPlayback by remember { mutableStateOf(false) }
    val playbackItem = remember { FocusRequester() }
    var showAddons by remember { mutableStateOf(false) }
    val addonsItem = remember { FocusRequester() }
    val remoteItem = remember { FocusRequester() }
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
    var lastBack by remember { mutableLongStateOf(0L) }
    val navFocus = remember { Section.entries.associateWith { FocusRequester() } }
    val settingsFirst = remember { FocusRequester() }
    val groupsFocus = remember { FocusRequester() }
    val catalogueFocus = remember { FocusRequester() }
    // The menu opens on its own list (TV, Movies, …); OK or Right then goes into the highlighted entry.
    LaunchedEffect(Unit) {
        awaitFrame()
        awaitFrame()
        // From Movies/Shows the menu opens on that page's catalogue list; Left from there reaches the main list.
        runCatching {
            if ((page == Page.Movies || page == Page.Shows) && remote.menuOpensOn == MenuOpensOn.PageList) catalogueFocus.requestFocus()
            else navFocus.getValue(section).requestFocus()
        }
    }
    fun enter(s: Section) {
        when (s) {
            Section.Tv -> runCatching { groupsFocus.requestFocus() }
            Section.Settings -> runCatching { settingsFirst.requestFocus() }
            Section.Movies, Section.Shows -> runCatching { catalogueFocus.requestFocus() }
            Section.Recordings -> onOpenPage(Page.Recordings)
            Section.Search -> onOpenPage(Page.Search)
            else -> Unit
        }
    }
    val activity = LocalContext.current as? Activity

    BackHandler {
        when {
            remote.menuBack == MenuBack.Close -> onClose()
            lastBack != 0L -> activity?.finish()
            else -> lastBack = System.currentTimeMillis()
        }
    }
    LaunchedEffect(lastBack) {
        if (lastBack != 0L) {
            delay(2_000)
            lastBack = 0L
        }
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(180.dp)
                .fillMaxHeight()
                .background(AppColors.MenuPanel)
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
            Section.entries.forEach { s ->
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
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && !(s == Section.Settings && (showAppearance || showRemote || showPlayback || showAddons))) {
                                enter(s)
                                true
                            } else false
                        },
                )
            }
            Spacer(Modifier.weight(1f))
            if (lastBack != 0L) {
                Text("Press Back again to exit", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp))
            }
        }

        Box(
            Modifier
                .width(230.dp)
                .fillMaxHeight()
                .background(AppColors.MenuPanelAlt)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        // Right from the category list returns to the guide, like TiviMate.
                        section == Section.Tv && e.key == Key.DirectionRight -> {
                            onOpenPage(Page.Tv)
                            true
                        }
                        // Left goes back to this section's menu entry, not whichever one is geometrically closest.
                        e.key == Key.DirectionLeft && (section == Section.Tv || section == Section.Movies || section == Section.Shows ||
                            (section == Section.Settings && !showAppearance && !showRemote && !showPlayback && !showAddons)) -> {
                            runCatching { navFocus.getValue(section).requestFocus() }
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
                Section.Settings -> if (showAppearance) {
                    AppearancePanel(onBack = { showAppearance = false })
                } else SettingsPanel(
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
                    remoteItem = remoteItem,
                    onRemote = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showRemote = true
                    },
                    session = session,
                    sync = sync,
                    firstItem = settingsFirst,
                    onAppearance = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showAppearance = true
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
                Section.Recordings -> Text(
                    "Press OK to see what's recorded and scheduled on your Dispatcharr server.",
                    fontSize = 15.sp,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(12.dp),
                )
                Section.Movies, Section.Shows -> CatalogueList(
                    type = if (section == Section.Movies) MediaType.Movie else MediaType.Show,
                    focusRequester = catalogueFocus,
                    onPick = { onOpenPage(if (section == Section.Movies) Page.Movies else Page.Shows) },
                )
                else -> Text(
                    "${section.label} is coming in a later update.",
                    fontSize = 15.sp,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black.copy(alpha = 0.4f * AppColors.palette.menuAlpha)))
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
    if (showRemote) {
        io.github.petebutty.viewarr.ui.settings.RemoteSettingsScreen(onBack = {
            runCatching { remoteItem.requestFocus() }
            showRemote = false
            returnTo = remoteItem
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
    MenuItem(text = name, selected = selected, onClick = onClick, modifier = modifier, trailing = count?.toString())
}

@Composable
private fun SettingsPanel(playbackItem: FocusRequester, onPlayback: () -> Unit, addonsItem: FocusRequester, onAddons: () -> Unit, remoteItem: FocusRequester, onRemote: () -> Unit, session: Session?, sync: SyncState, firstItem: FocusRequester, onAppearance: () -> Unit, onAccounts: () -> Unit, onRefresh: () -> Unit, onSignOut: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        MenuItem(text = "Accounts & sources", selected = false, onClick = onAccounts, modifier = Modifier.focusRequester(firstItem))
        MenuItem(text = "Appearance", selected = false, onClick = onAppearance)
        MenuItem(text = "Playback", selected = false, onClick = onPlayback, modifier = Modifier.focusRequester(playbackItem))
        MenuItem(text = "Addons", selected = false, onClick = onAddons, modifier = Modifier.focusRequester(addonsItem))
        MenuItem(text = "Remote & navigation", selected = false, onClick = onRemote, modifier = Modifier.focusRequester(remoteItem))
        MenuItem(text = "Refresh channels & guide", selected = false, onClick = onRefresh)
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
        )
    }
}

/** The Movies / Shows catalogues (the rows on that page), so you can jump straight to one from the menu. */
@Composable
private fun CatalogueList(type: MediaType, focusRequester: FocusRequester, onPick: () -> Unit) {
    // Same instance as the page's (same key, same owner), so the rows are shared and load once.
    val media: MediaViewModel = hiltViewModel(key = "media-${type.name}")
    LaunchedEffect(type) { media.load(type) }
    val state by media.rows.collectAsStateWithLifecycle()
    val current by media.currentRow.collectAsStateWithLifecycle()
    // Focus lands on the row you were last on (highlighted), or on Home.
    val currentIndex = state.rows.indexOfFirst { it.id == current }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, currentIndex + 1 - 3))
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "home") {
            MenuItem(
                text = if (type == MediaType.Movie) "Movies home" else "Shows home",
                selected = false,
                modifier = if (currentIndex < 0) Modifier.focusRequester(focusRequester) else Modifier,
                onClick = {
                    media.jumpTo(null)
                    onPick()
                },
            )
        }
        items(state.rows, key = { it.id }) { row ->
            MenuItem(
                text = row.title,
                selected = row.id == current,
                modifier = if (row.id == current) Modifier.focusRequester(focusRequester) else Modifier,
                trailing = row.items.size.toString(),
                onClick = {
                    media.jumpTo(row.id)
                    onPick()
                },
            )
        }
        if (!state.done) item(key = "loading") {
            Text("Loading…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
        }
    }
}

/** Search: a text box (the TV keyboard), then channel matches and poster rows of results. */
@Composable
private fun SearchPage(guide: GuideViewModel, focusEnabled: Boolean, onOpenMenu: () -> Unit, onChannel: (ChannelListItem) -> Unit) {
    val media: MediaViewModel = hiltViewModel(key = "media-search")
    var query by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    val rowsFocus = remember { FocusRequester() }
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
    LaunchedEffect(query) { media.search(query) }
    // Focus the box only when the page first opens with nothing typed yet.
    LaunchedEffect(Unit) {
        if (query.isNotEmpty()) return@LaunchedEffect
        awaitFrame()
        runCatching { fieldFocus.requestFocus() }
    }
    MediaScreen(
        type = MediaType.Movie,
        focusRequester = rowsFocus,
        focusEnabled = focusEnabled,
        onOpenMenu = onOpenMenu,
        searchHeader = {
            Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 12.dp)) {
                Text("Search", fontSize = 14.sp, color = AppColors.Accent)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { androidx.compose.material3.Text("Films, shows or channels…") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        keyboard?.hide()
                        if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) runCatching { rowsFocus.requestFocus() }
                    }),
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .focusRequester(fieldFocus)
                        .focusProperties { canFocus = fieldFree && focusEnabled }
                        .onFocusChanged { if (!it.isFocused) keyboard?.hide() }
                        // The text box would keep the remote: Down always leaves it for the results.
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) runCatching { rowsFocus.requestFocus() }
                                true
                            } else false
                        },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, color = AppColors.TextPrimary),
                )
                if (query.trim().length >= 2) {
                    Spacer(Modifier.height(12.dp))
                    if (channels.isNotEmpty()) {
                        Text("Channels", fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(channels, key = { it.id }) { ch ->
                                Box(Modifier.width(220.dp)) {
                                    MenuItem(
                                        text = ch.name,
                                        selected = false,
                                        trailing = ch.number?.let { n -> if (n % 1.0 == 0.0) n.toInt().toString() else n.toString() },
                                        onClick = { onChannel(ch) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
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
) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val groupId by vm.groupId.collectAsStateWithLifecycle()
    var showGroups by remember { mutableStateOf(false) }
    val groupFocus = remember { FocusRequester() }
    val start = channels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, start - 4))
    val focus = remember { FocusRequester() }
    var activity by remember { mutableLongStateOf(System.nanoTime()) }
    BackHandler { if (showGroups) showGroups = false else onClose() }
    // Focus: the current channel (or the first one after a group change), or the selected group.
    LaunchedEffect(showGroups, groupId) {
        awaitFrame()
        if (showGroups) runCatching { groupFocus.requestFocus() }
        else {
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
            // Left from the channels opens the groups; Right (or Back) from the groups returns.
            e.key == Key.DirectionLeft && !showGroups -> { showGroups = true; true }
            e.key == Key.DirectionRight && showGroups -> { showGroups = false; true }
            else -> false
        }
    }.trapFocus()) {
    if (showGroups) {
        Box(Modifier.fillMaxHeight().width(280.dp).background(AppColors.Panel.copy(alpha = 0.92f)).padding(16.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                item(key = "all") {
                    MenuItem(
                        text = "All channels", selected = groupId == null,
                        modifier = if (groupId == null) Modifier.focusRequester(groupFocus) else Modifier,
                        onClick = { vm.selectGroup(null); showGroups = false },
                    )
                }
                items(groups, key = { it.id }) { g ->
                    MenuItem(
                        text = g.name, selected = g.id == groupId, trailing = g.channelCount.toString(),
                        modifier = if (g.id == groupId) Modifier.focusRequester(groupFocus) else Modifier,
                        onClick = { vm.selectGroup(g.id); showGroups = false },
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
                        .focusProperties { canFocus = !showGroups }
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
                        Text(ch.number?.let { n -> if (n % 1.0 == 0.0) n.toInt().toString() else n.toString() }.orEmpty(), fontSize = 13.sp, modifier = Modifier.width(44.dp))
                        Box(Modifier.size(width = 56.dp, height = 34.dp), contentAlignment = Alignment.Center) {
                            ch.logoId?.let { id ->
                                coil.compose.AsyncImage(
                                    model = io.github.petebutty.viewarr.ui.guide.logoUrl(serverUrl, id),
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
                    }
                }
            }
        }
    }
    }
}
