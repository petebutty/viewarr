package io.github.petebutty.viewarr.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import kotlinx.coroutines.flow.first
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.local.GroupWithCount
import io.github.petebutty.viewarr.data.repository.SyncState
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.data.settings.MenuOpensOn
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MenuOverlay(
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
    var showSports by remember { mutableStateOf(false) }
    val sportsItem = remember { FocusRequester() }
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
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && !(s == Section.Settings && (showAppearance || showRemote || showNavigation || showSports || showPlayback || showAddons || showUpdates || showAbout || showHomeRows || showPlugins))) {
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
                            (section == Section.Settings && !showAppearance && !showRemote && !showNavigation && !showSports && !showPlayback && !showAddons && !showUpdates && !showAbout && !showHomeRows && !showPlugins)) -> {
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
                    sportsItem = sportsItem,
                    onSports = {
                        runCatching { navFocus.getValue(Section.Settings).requestFocus() }
                        showSports = true
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
    if (showSports) {
        io.github.petebutty.viewarr.ui.settings.SportsSettingsScreen(onBack = {
            runCatching { sportsItem.requestFocus() }
            showSports = false
            returnTo = sportsItem
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
internal fun GroupList(sourcesVm: io.github.petebutty.viewarr.ui.settings.LiveSourcesViewModel, focusRequester: FocusRequester, groups: List<GroupWithCount>, allCount: Int?, selectedId: Int?, onSelect: (Int?) -> Unit) {
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
internal fun GroupItem(name: String, count: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    MenuItem(text = name, selected = selected, onClick = onClick, modifier = modifier.rightDoes(onClick), trailing = count?.toString())
}

/** In the menu's right-hand lists, Right does the same as OK: go to that page. */
internal fun Modifier.rightDoes(action: () -> Unit): Modifier = this.composed(factory = {
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
internal fun Modifier.rightIgnored(): Modifier = onPreviewKeyEvent { e -> e.key == Key.DirectionRight }

@Composable
internal fun SettingsPanel(playbackItem: FocusRequester, onPlayback: () -> Unit, addonsItem: FocusRequester, onAddons: () -> Unit, remoteItem: FocusRequester, onRemote: () -> Unit, navigationItem: FocusRequester, onNavigation: () -> Unit, sportsItem: FocusRequester, onSports: () -> Unit, updatesItem: FocusRequester, onUpdates: () -> Unit, aboutItem: FocusRequester, onAbout: () -> Unit, session: Session?, sync: SyncState, firstItem: FocusRequester, appearanceItem: FocusRequester, onAppearance: () -> Unit, homeRowsItem: FocusRequester, onHomeRows: () -> Unit, pluginsItem: FocusRequester, onPlugins: () -> Unit, onAccounts: () -> Unit, onRefresh: () -> Unit, onSignOut: () -> Unit) {
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
        MenuItem(text = "Sports", selected = false, onClick = onSports, modifier = Modifier.hint("Your teams and leagues: their games under the Live TV hub's Sports shortcut (Down), and where to watch them. Pin one for its own shortcut.").focusRequester(sportsItem).rightDoes(onSports))
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
internal fun SettingsGroup(title: String, first: Boolean = false) = MenuGroupLabel(title, first)

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
