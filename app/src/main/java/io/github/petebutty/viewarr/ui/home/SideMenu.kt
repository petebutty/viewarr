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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
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
    /**
     * From full-screen live TV (as TiviMate): the channel list as the strip's last column, beside the groups. Its
     * Left comes back to the groups ([onLeft]); moving through the groups previews theirs; picking one ([onGroupPicked])
     * goes back to the channel list on its own.
     */
    channelsPanel: (@Composable (onLeft: () -> Unit) -> Unit)? = null,
    onGroupPicked: (Int?) -> Unit = {},
    /** The strip's width as it changes (Left adding the main list…): the guide is pushed along beside it. */
    onWidth: (Int) -> Unit = {},
    /** Moving onto Live TV, Movies or Shows in the main list: that page behind the menu straight away (after a short rest). */
    onHoverPage: (Page) -> Unit = {},
    /** Live TV in the preview place (top left): a full settings page opens beside it rather than over it. */
    previewOn: Boolean = false,
    /** A full settings page open (Appearance, Remote buttons…): the page behind goes back to its place. */
    onFullPage: (Boolean) -> Unit = {},
    /** Live now, Sports: after Live TV in the main list (each the guide on its channels). */
    liveEntries: List<String> = emptyList(),
    /** One of them highlighted (into false: shown behind the menu) or gone into (true). */
    onLiveEntry: (String, Boolean) -> Unit = { _, _ -> },
    /** Streaming services for the main list (name, its rows: id and name), after Shows. */
    services: List<Pair<String, List<Pair<String, String>>>> = emptyList(),
    /** A service highlighted (into false: its page behind) or gone into (true), on one of its rows (or its top). */
    onService: (name: String, rowId: String?, into: Boolean) -> Unit = { _, _, _ -> },
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
    // (Movies, Shows, Music too: opening on their own list, the main list starts hidden rather than showing for a
    // moment and then sliding away, which jolted the page beside it.)
    // Left back to the main list: shown at once in place of the list beside it, before focus gets there (no frame
    // with both up, which flashed).
    var forceMain by remember { mutableStateOf(false) }
    var showMain by remember {
        mutableStateOf(onMain || onSettings || !(remote.menuOpensOn == MenuOpensOn.PageList &&
            ((page == Page.Tv && session != null) || page == Page.Movies || page == Page.Shows || page == Page.Music)))
    }
    // Back from Users & profiles / the PIN dialog (drawn over the menu): Settings stays the open list, so the row
    // they came from can take focus again (focus otherwise fell to the top of the menu).
    val profilePage = io.github.petebutty.viewarr.ui.profiles.ProfileUi.users || io.github.petebutty.viewarr.ui.profiles.ProfileUi.pin
    var hadProfilePage by remember { mutableStateOf(false) }
    LaunchedEffect(profilePage) {
        if (hadProfilePage && !profilePage) { section = Section.Settings; showMain = true }
        hadProfilePage = profilePage
    }
    val menuScope = androidx.compose.runtime.rememberCoroutineScope()
    // The service highlighted in the main list (null: one of the main entries).
    var serviceOn by remember { mutableStateOf<String?>(null) }
    val serviceFocus = remember { HashMap<String, FocusRequester>() }
    // Editing the main list in place (✎ Edit menus, or hold OK on an entry): OK shows / hides, hold OK picks up.
    var editingMain by remember { mutableStateOf(false) }
    var carryingMain by remember { mutableStateOf<String?>(null) }
    var mainFollow by remember { mutableStateOf<String?>(null) }
    // The main list's entries while editing (by id: "Movies", "svc:Netflix"…).
    val mainFocus = remember { HashMap<String, FocusRequester>() }
    fun revealMain() {
        // Back from a service's rows: onto the service itself.
        val target = serviceOn?.let { if (editingMain) mainFocus["svc:$it"] else serviceFocus[it] }
            ?: (if (editingMain && (section == Section.Movies || section == Section.Shows)) mainFocus[section.name] else null)
            ?: navFocus.getValue(section)
        forceMain = true
        if (showMain) { runCatching { target.requestFocus() }.onFailure { menuScope.launch { awaitFrame(); awaitFrame(); runCatching { target.requestFocus() } } }; return }
        showMain = true
        menuScope.launch { awaitFrame(); awaitFrame(); runCatching { target.requestFocus() } }
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
                    if (runCatching { recentFocus.requestFocus() }.isFailure) { awaitFrame(); awaitFrame(); runCatching { recentFocus.requestFocus() }.onFailure { revealMain() } }
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
    LaunchedEffect(subListFocused) { forceMain = false; if (subListFocused && section != Section.Settings) showMain = false }
    val moviesMedia: io.github.petebutty.viewarr.ui.media.MediaViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = "media-Movie")
    val showsMedia: io.github.petebutty.viewarr.ui.media.MediaViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = "media-Show")
    // A service's rows being moved (its rows list beside it).
    var carryingRow by remember { mutableStateOf<String?>(null) }
    fun endEditMain() {
        editingMain = false; carryingMain = null; mainFollow = null; menuEditing = false; carryingRow = null
        menuScope.launch { awaitFrame(); awaitFrame(); runCatching { navFocus.getValue(Section.Tv).requestFocus() } }
    }
    BackHandler {
        when {
            carryingRow != null -> carryingRow = null
            editingMain -> endEditMain()
            // A library's categories: back to the library list. Editing the list: done.
            menuCats != null -> menuCats = null
            menuEditing -> menuEditing = false
            // Opened from the live channel list: Back goes back to the channels.
            channelsPanel != null -> onClose()
            // Opened by Back in the guide (as TiviMate): Back from the groups asks to exit.
            backOut && section == Section.Tv -> onBackOut()
            // A section's list: the main list slides back in.
            subListFocused -> revealMain()
            else -> if (backOut) onBackOut() else onClose()
        }
    }

    // One strip, as TiviMate: the main list, the section's list (channel groups, libraries…) and from live TV the
    // channels, side by side on one background (drawn once, the same colour and see-through for every column), with
    // a thin line between them. Each Left pushes the next column in from the left; Right / OK goes into it.
    val stripColour = if (seeThrough) AppColors.MenuPanel else AppColors.palette.panel
    val divider = AppColors.TextSecondary.copy(alpha = 0.15f)
    // Channel groups previewed in the channel column while you move through them (a short pause keeps it smooth).
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var hoverJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val serviceRowsFocus = remember { FocusRequester() }
    fun hoverService(name: String) {
        hoverJob?.cancel()
        hoverJob = menuScope.launch { delay(300); onService(name, null, false) }
    }
    fun hoverPage(s: Section) {
        val p = when (s) { Section.Tv -> Page.Tv; Section.Movies -> Page.Movies; Section.Shows -> Page.Shows; Section.Music -> Page.Music; Section.Recordings -> Page.Recordings; else -> null }
        hoverJob?.cancel()
        if (p != null) hoverJob = menuScope.launch { delay(300); onHoverPage(p) }
    }
    fun previewGroup(id: Int?) {
        if (channelsPanel == null) return
        previewJob?.cancel()
        previewJob = menuScope.launch { delay(250); if (vm.groupId.value != id) vm.selectGroup(id) }
    }
    val fullPage = showAppearance || showRemote || showNavigation || showSports || showPlayback || showAddons || showUpdates || showAbout || showHomeRows || showPlugins
    LaunchedEffect(fullPage) { onFullPage(fullPage) }
    // Beside the preview: the strip steps out of the way (still there underneath for focus to come back to).
    val besidePreview = fullPage && previewOn
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().graphicsLayer { alpha = if (besidePreview) 0f else 1f }) {
    Row(Modifier.fillMaxHeight().onSizeChanged { onWidth(it.width) }.background(stripColour)) {
        // Left again: the main list appears beside the groups.
        // (As wide as the list beside it: the strip stays one width whichever of the two shows, so the page doesn't move.)
        // One panel at a time (as TiviMate): the main list, or the list of the entry you went into, swapped in the
        // same frame. Both only while editing, with the live channel list, or in Settings.
        val single = !editingMain && channelsPanel == null && section != Section.Settings
        val mainShown = showMain && (forceMain || !(subListFocused && single))
        io.github.petebutty.viewarr.ui.common.GrowFromLeftToggle(mainShown, 230.dp) { Column(
            Modifier
                .width(230.dp)
                .fillMaxHeight()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
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
            // The main list: Live TV, then the rest in your order (hold OK on one to edit the menu here), then Settings.
            // (Settings → Appearance → Main menu switches entries on and off too.)
            val appearanceVm: io.github.petebutty.viewarr.ui.settings.AppearanceViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val movable = buildList {
                addAll(liveEntries)
                if (!AppColors.palette.hideMedia) { add(Section.Movies.name); add(Section.Shows.name) }
                services.forEach { add("svc:" + it.first) }
                if (musicMenuOn) add(Section.Music.name)
                add(Section.Recordings.name); add(Section.Search.name)
            }
            val order = AppColors.palette.menuOrder
            val ordered = movable.withIndex().sortedBy { (i, id) -> order.indexOf(id).let { if (it < 0) order.size + i else it } }.map { it.value }
            fun visible(id: String) = AppColors.palette.shows("menu:$id")
            fun labelOf(id: String) = when { id in liveEntries -> id; id.startsWith("svc:") -> id.removePrefix("svc:"); else -> Section.valueOf(id).label }
            LaunchedEffect(mainFollow, editingMain) {
                val id = mainFollow ?: return@LaunchedEffect
                awaitFrame(); awaitFrame()
                runCatching { (mainFocus[id] ?: runCatching { navFocus.getValue(Section.valueOf(id)) }.getOrNull())?.requestFocus() }
            }
            val sectionItem: @Composable (Section) -> Unit = { s ->
                    MenuItem(
                        text = s.label,
                        selected = s == section,
                        dim = !s.available,
                        onClick = {
                            section = s
                            enter(s)
                        },
                        // Hold OK: edit the menu here (not Live TV or Settings, which always stay).
                        onLongClick = if (s == Section.Tv || s == Section.Settings) null else ({ editingMain = true; carryingMain = null; mainFollow = s.name }),
                        modifier = Modifier
                            .focusRequester(navFocus.getValue(s))
                            .onFocusChanged { if (it.isFocused) { section = s; serviceOn = null; hoverPage(s) } }
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
            // Edit mode: every list in the menu can be changed (the main list here; a service's rows, Movies' and
            // Shows' lists beside it). Off: what's switched off is hidden and the editing panels go.
            MenuItem(
                text = if (editingMain) "✓  Done editing" else "✎  Edit menus",
                selected = editingMain, dim = !editingMain,
                onClick = { if (editingMain) endEditMain() else { editingMain = true; carryingMain = null; mainFollow = null } },
                modifier = Modifier.padding(bottom = 6.dp),
            )
            sectionItem(Section.Tv)
            (if (editingMain) ordered else ordered.filter(::visible)).forEach { id ->
                androidx.compose.runtime.key(id) {
                    when {
                        // Editing: every entry, On / Off; the one picked up says so.
                        editingMain -> MenuItem(
                            text = (if (carryingMain == id) "↕  " else "") + labelOf(id),
                            trailing = if (carryingMain == id) "Moving" else if (visible(id)) "On" else "Off",
                            dim = !visible(id) && carryingMain != id,
                            selected = carryingMain == id,
                            onClick = { if (carryingMain == id) carryingMain = null else appearanceVm.setTitlePart("menu:$id", !visible(id)) },
                            onLongClick = { carryingMain = if (carryingMain == id) null else id },
                            modifier = Modifier.focusRequester(mainFocus.getOrPut(id) { FocusRequester() })
                                .onFocusChanged {
                                    if (!it.isFocused) return@onFocusChanged
                                    // Its own list beside it: a service's rows, Movies' or Shows' list.
                                    if (id.startsWith("svc:")) serviceOn = id.removePrefix("svc:")
                                    else { serviceOn = null; if (id == Section.Movies.name || id == Section.Shows.name) section = Section.valueOf(id) }
                                }
                                .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    carryingMain == id && e.key == Key.DirectionRight -> { carryingMain = null; true }
                                    // Right: into the list beside it, to edit that.
                                    e.key == Key.DirectionRight -> {
                                        when {
                                            id.startsWith("svc:") -> runCatching { serviceRowsFocus.requestFocus() }
                                            id == Section.Movies.name || id == Section.Shows.name -> { menuEditing = true }
                                            else -> Unit
                                        }
                                        true
                                    }
                                    carryingMain == id && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> {
                                        val list = ordered.toMutableList()
                                        val i = list.indexOf(id); val j = i + if (e.key == Key.DirectionUp) -1 else 1
                                        if (j in list.indices) { list[i] = list[j]; list[j] = id; appearanceVm.setMenuOrder(list); mainFollow = null; mainFollow = id }
                                        true
                                    }
                                    else -> false
                                }
                            },
                        )
                        // Live now, Sports: the guide on their channels (moving onto one shows it behind; OK / Right: into it).
                        id in liveEntries -> MenuItem(
                            text = id,
                            selected = serviceOn == id,
                            onClick = { hoverJob?.cancel(); onLiveEntry(id, true) },
                            onLongClick = { editingMain = true; carryingMain = null; mainFollow = id },
                            modifier = Modifier
                                .focusRequester(mainFocus.getOrPut(id) { FocusRequester() })
                                .onFocusChanged { if (it.isFocused) { serviceOn = id; hoverJob?.cancel(); hoverJob = menuScope.launch { delay(300); onLiveEntry(id, false) } } }
                                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { hoverJob?.cancel(); onLiveEntry(id, true); true } else false },
                        )
                        // A service: its page behind as you move onto it; OK straight in; Right onto its rows (to reorder them).
                        id.startsWith("svc:") -> {
                            val name = id.removePrefix("svc:")
                            val rows = services.firstOrNull { it.first == name }?.second.orEmpty()
                            MenuItem(
                                text = name,
                                selected = serviceOn == name,
                                onClick = { hoverJob?.cancel(); onService(name, null, true) },
                                onLongClick = { editingMain = true; carryingMain = null; mainFollow = id },
                                modifier = Modifier
                                    .focusRequester(serviceFocus.getOrPut(name) { FocusRequester() })
                                    .onFocusChanged { if (it.isFocused) { serviceOn = name; hoverService(name) } }
                                    .onPreviewKeyEvent { e ->
                                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                            if (rows.isEmpty() || runCatching { serviceRowsFocus.requestFocus() }.isFailure) { hoverJob?.cancel(); onService(name, null, true) }
                                            true
                                        } else false
                                    },
                            )
                        }
                        else -> sectionItem(Section.valueOf(id))
                    }
                }
            }
            sectionItem(Section.Settings)
            if (editingMain) MenuItem(text = "✓  Done", selected = false, onClick = { endEditMain() }, modifier = Modifier.padding(top = 8.dp))
        } }

        // One panel at a time (as TiviMate): moving down the main list shows only the main list; Right / OK on an entry
        // opens its list (groups, libraries…) in place of it. Both show only while editing, or with the live channel
        // list beside them.
        val subHidden = single && mainShown && (!subListFocused || forceMain)
        if (mainShown && !subHidden) Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
        Box(
            Modifier
                .width(if (subHidden) 0.dp else 230.dp)
                .fillMaxHeight()
                .onFocusChanged { subListFocused = it.hasFocus }
                // Any other list (Music, Search): a Left nothing in it used brings the main list back.
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && !mainShown && section != Section.Settings) { revealMain(); true } else false
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
            val svc = serviceOn?.let { n -> services.firstOrNull { it.first == n } }
            // Editing the menu: what the buttons do.
            if (editingMain && svc == null && !menuEditing) Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("EDITING THE MENU", fontSize = 12.sp, color = AppColors.Accent)
                Text("OK: show or hide", fontSize = 15.sp, color = AppColors.TextSecondary)
                Text("Hold OK: pick up, then ▲ ▼ to move it, OK to put it down", fontSize = 15.sp, color = AppColors.TextSecondary)
                Text("Right on a service, Movies or Shows: edit its own list", fontSize = 15.sp, color = AppColors.TextSecondary)
                Text("Back or Done editing: finish (what's off is hidden)", fontSize = 15.sp, color = AppColors.TextSecondary)
            } else
            // A service highlighted: its rows, in the page's order, to reorder or hide (OK hides / shows, Right picks
            // one up, Up / Down move it). The page behind follows straight away.
            if (svc != null) {
                // (Out of edit mode a service has no panel: the column is folded away.)
                if (editingMain) {
                val key = "name:" + svc.first
                val (sOrdered, sHidden) = showsMedia.orderedRows.collectAsStateWithLifecycle().value
                val (mOrdered, mHidden) = moviesMedia.orderedRows.collectAsStateWithLifecycle().value
                val sRows = sOrdered.filter { io.github.petebutty.viewarr.ui.player.RowSets.matches(key, it) }
                val mRows = mOrdered.filter { io.github.petebutty.viewarr.ui.player.RowSets.matches(key, it) }
                val both = sRows.isNotEmpty() && mRows.isNotEmpty()
                val all = sRows.map { Triple(it, showsMedia, sHidden) } + mRows.map { Triple(it, moviesMedia, mHidden) }
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(svc.first.uppercase() + "  ·  ROWS", fontSize = 12.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                    all.forEachIndexed { i, (row, media, hidden) ->
                        val off = row.id in hidden
                        val films = media === moviesMedia
                        androidx.compose.runtime.key(row.id) {
                            MenuItem(
                                text = (if (carryingRow == row.id) "↕  " else "") + io.github.petebutty.viewarr.ui.player.RowSets.rowTitle(row) + if (both && films) " (films)" else "",
                                trailing = if (carryingRow == row.id) "Moving" else if (off) "Off" else null,
                                dim = off && carryingRow != row.id,
                                selected = carryingRow == row.id,
                                onClick = {
                                    if (carryingRow == row.id) carryingRow = null
                                    else { media.setRowHidden(row.id, !off); menuScope.launch { delay(150); onService(svc.first, null, false) } }
                                },
                                modifier = (if (i == 0) Modifier.focusRequester(serviceRowsFocus) else Modifier)
                                    .onPreviewKeyEvent { e ->
                                        when {
                                            e.type != KeyEventType.KeyDown -> false
                                            e.key == Key.DirectionRight -> { carryingRow = if (carryingRow == row.id) null else row.id; true }
                                            carryingRow == row.id && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> {
                                                media.moveRowAmong(row.id, if (e.key == Key.DirectionUp) -1 else 1) { io.github.petebutty.viewarr.ui.player.RowSets.matches(key, it) }
                                                menuScope.launch { delay(150); onService(svc.first, null, false) }
                                                true
                                            }
                                            e.key == Key.DirectionLeft -> { carryingRow = null; revealMain(); true }
                                            else -> false
                                        }
                                    },
                            )
                        }
                    }
                    Text("OK: show or hide  ·  Right: move", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 10.dp))
                }
                }
            } else when (section) {
                Section.Tv -> GroupList(
                    sourcesVm = hiltViewModel(),
                    focusRequester = groupsFocus,
                    groups = groups,
                    allCount = if (groupId == null) channels.size else null,
                    selectedId = groupId,
                    onSelect = {
                        previewJob?.cancel()
                        vm.selectGroup(it)
                        if (channelsPanel != null) onGroupPicked(it) else onOpenPage(Page.Tv)
                    },
                    onHover = { previewGroup(it) },
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
                    editMode = editingMain,
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

        if (channelsPanel != null && section == Section.Tv) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
            channelsPanel { runCatching { groupsFocus.requestFocus() } }
        }
    }
        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black.copy(alpha = 0.4f * (if (seeThrough) AppColors.palette.menuAlpha else 1f))))
    }
    // Beside the preview: glass under it, the page to its right.
    if (besidePreview) Column(Modifier.width(344.dp).fillMaxHeight()) {
        Spacer(Modifier.height(212.dp))
        Box(Modifier.fillMaxSize().background(AppColors.MenuPanel))
    }
    Box(Modifier.fillMaxSize().padding(start = if (besidePreview) 344.dp else 0.dp)) {
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
}

@Composable
internal fun GroupList(sourcesVm: io.github.petebutty.viewarr.ui.settings.LiveSourcesViewModel, focusRequester: FocusRequester, groups: List<GroupWithCount>, allCount: Int?, selectedId: Int?, onSelect: (Int?) -> Unit, onHover: (Int?) -> Unit = {}) {
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
                modifier = (if (selectedId == null) Modifier.focusRequester(selectedFocus) else Modifier).onFocusChanged { if (it.isFocused) onHover(null) },
                onClick = { onSelect(null) },
            )
        }
        items(groups, key = { it.id }) { g ->
            GroupItem(
                name = g.name,
                count = g.channelCount,
                selected = g.id == selectedId,
                modifier = (if (g.id == selectedId) Modifier.focusRequester(selectedFocus) else Modifier).onFocusChanged { if (it.isFocused) onHover(g.id) },
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
