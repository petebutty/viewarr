@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.petebutty.viewarr.ui.home

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.local.GroupWithCount
import io.github.petebutty.viewarr.data.repository.SyncState
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.GroupNames
import io.github.petebutty.viewarr.ui.common.HeldOk
import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.Carry
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.SearchField
import io.github.petebutty.viewarr.ui.common.VoiceButton
import io.github.petebutty.viewarr.ui.common.moveOption
import io.github.petebutty.viewarr.ui.common.movingHint
import io.github.petebutty.viewarr.ui.common.rememberCarry
import io.github.petebutty.viewarr.ui.common.reorderable
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.petebutty.viewarr.data.media.MediaLibrary
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.profiles.Lock
import io.github.petebutty.viewarr.ui.common.GrowFromLeftToggle
import io.github.petebutty.viewarr.ui.common.PendingOrder
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.music.MusicViewModel
import io.github.petebutty.viewarr.ui.player.RowSets
import io.github.petebutty.viewarr.ui.profiles.ProfileSettingsItems
import io.github.petebutty.viewarr.ui.profiles.ProfileUi
import io.github.petebutty.viewarr.ui.settings.AboutSettingsScreen
import io.github.petebutty.viewarr.ui.settings.AddonsSettingsScreen
import io.github.petebutty.viewarr.ui.settings.AppearanceViewModel
import io.github.petebutty.viewarr.ui.settings.HomeRowsSettingsScreen
import io.github.petebutty.viewarr.ui.settings.LiveSourcesViewModel
import io.github.petebutty.viewarr.ui.settings.MappedSettingsScreen
import io.github.petebutty.viewarr.ui.settings.OtherSettingsPage
import io.github.petebutty.viewarr.ui.settings.PluginsSettingsScreen
import io.github.petebutty.viewarr.ui.settings.RemoteSettingsScreen
import io.github.petebutty.viewarr.ui.settings.SettingsLayout
import io.github.petebutty.viewarr.ui.settings.SettingsSearchScreen
import io.github.petebutty.viewarr.ui.settings.SportsSettingsScreen
import io.github.petebutty.viewarr.ui.settings.UiOptsViewModel
import io.github.petebutty.viewarr.ui.settings.UpdatesSettingsScreen
import io.github.petebutty.viewarr.ui.settings.WatchlistItem
import io.github.petebutty.viewarr.ui.sync.BackupSyncItem
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.ui.settings.SettingsMap

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    /** Open on the main list (Live TV, Movies…). (It always does now, unless [onGroups].) */
    onMain: Boolean = false,
    /** Open on Live TV's channel groups (the remote's "Channel groups menu" button). */
    onGroups: Boolean = false,
    /** Open with My Library highlighted (Left from a library's page). */
    onLibrary: Boolean = false,
    /** Search panel: search for this (null: an empty search), or by voice. */
    onSearchFor: (String?) -> Unit = {},
    onVoiceSearch: () -> Unit = {},
    /**
     * From full-screen live TV (as classic IPTV apps): the channel list as the strip's last column, beside the groups. Its
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
    /** Sports: after Live TV in the main list (its own page of games). */
    liveEntries: List<String> = emptyList(),
    /** The service or live entry whose page is open behind ("svc:NOW", "Sports"…): the menu opens on it, not on Shows. */
    openEntry: String? = null,
    /** One of them highlighted (into false: shown behind the menu) or gone into (true). */
    onLiveEntry: (String, Boolean) -> Unit = { _, _ -> },
    /** Streaming services for the main list (name, its rows: id and name), after Shows. */
    services: List<Pair<String, List<Pair<String, String>>>> = emptyList(),
    /** A service highlighted (into false: its page behind) or gone into (true), on one of its rows (or its top). */
    onService: (name: String, rowId: String?, into: Boolean) -> Unit = { _, _, _ -> },
    /** My Library highlighted ([into] false: its page behind) or its "My Library home" picked (true: into its rows). */
    onMyLibrary: (into: Boolean) -> Unit = {},
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val groupId by vm.groupId.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    // (Its focus requesters and plain flags in one remembered holder: MenuOverlay is at the method-size limit.)
    val fi = remember { MenuHolder() }
    var section by remember {
        mutableStateOf(if (onSettings) Section.Settings else if (onLibrary) Section.Library else if (channelsPanel != null) Section.Tv else when (page) { Page.Tv -> Section.Tv; Page.Movies -> Section.Movies; Page.Shows -> Section.Shows; Page.Music -> Section.Music; Page.Recordings -> Section.Recordings; Page.Search -> Section.Search })
    }
    var showAppearance by fi.showAppearance
    // Settings → Live TV, Troubleshooting and Search settings (the pages SettingsMap lays out, and the search over them).
    var showLive by fi.showLive
    val liveItem = fi.liveItem
    var showTrouble by fi.showTrouble
    val troubleItem = fi.troubleItem
    var showSearchSettings by fi.showSearchSettings
    val searchSettingsItem = fi.searchSettingsItem
    // From Search settings: the section and setting the page opens on.
    var mappedStart by fi.mappedStart
    var showHomeRows by fi.showHomeRows
    /** Home page & menu opened on My Library (Right on it while editing the menu). */
    var homeRowsMine by fi.homeRowsMine
    var showPlugins by fi.showPlugins
    val pluginsItem = fi.pluginsItem
    val homeRowsItem = fi.homeRowsItem
    var showRemote by fi.showRemote
    var showNavigation by fi.showNavigation
    val navigationItem = fi.navigationItem
    var showSports by fi.showSports
    // (Opened from another settings page: Back goes back to that page, not to Sports in the settings list.)
    var sportsJumped by fi.sportsJumped
    // "Follow a team" from elsewhere (the Sports page) opens Settings → Sports.
    LaunchedEffect(io.github.petebutty.viewarr.ui.settings.SettingsJump.sports) {
        if (io.github.petebutty.viewarr.ui.settings.SettingsJump.sports) { showSports = true; sportsJumped = true; io.github.petebutty.viewarr.ui.settings.SettingsJump.sports = false }
    }
    val sportsItem = fi.sportsItem
    var showPlayback by fi.showPlayback
    val playbackItem = fi.playbackItem
    var showAddons by fi.showAddons
    val addonsItem = fi.addonsItem
    val remoteItem = fi.remoteItem
    val appearanceItem = fi.appearanceItem
    var showUpdates by fi.showUpdates
    var showAbout by fi.showAbout
    val updatesItem = fi.updatesItem
    val aboutItem = fi.aboutItem
    var returnTo by fi.returnTo
    // A hovered main entry's page switch, after a short pause (cancelled when focus moves on).
    var hoverJob by fi.hoverJob
    // The service highlighted in the main list (null: one of the main entries).
    var serviceOn by fi.serviceOn
    // After closing a full settings page, land back on its menu item (not wherever focus happens to fall). While this
    // is pending, a main-list entry the highlight passes through on the way doesn't open (see the entries' onFocusChanged).
    LaunchedEffect(returnTo) {
        val target = returnTo ?: return@LaunchedEffect
        // When the page goes, focus can fall onto the first menu entry and switch the side panel away from
        // Settings (taking the menu item with it). Put Settings back first, then focus the item.
        // Focus dropping onto a main-list entry meanwhile (a service such as Sky Atlantic) made that the open entry:
        // its panel replaced Settings' list (so the item wasn't there to focus) and its hover opened its page.
        // Undo that each frame until the item has focus.
        for (i in 0 until 12) {
            section = Section.Settings; serviceOn = null; hoverJob?.cancel()
            awaitFrame()
            if (i >= 1 && runCatching { target.requestFocus() }.isSuccess) break
        }
        section = Section.Settings; serviceOn = null
        hoverJob?.cancel()
        returnTo = null
    }
    // Music: in the menu only once it's turned on (setup, Music).
    val musicMenuOn by hiltViewModel<MusicViewModel>().enabled.collectAsStateWithLifecycle()
    val remote = LocalRemote.current
    val navFocus = remember { Section.entries.associateWith { FocusRequester() } }
    // The top of the main list's entries (under logo, hints and search), for the side lists to line up with.
    var mainListTop by fi.mainListTop
    // The highlight is on the menu's top drawn over a folded list (its keys are its own then).
    var foldedHeaderFocused by fi.foldedHeaderFocused
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Someone without Live TV (Profiles → What they can do): no Live TV entry; Movies is the menu's home instead.
    val liveOn = Access.liveTv
    val homeSection = if (liveOn) Section.Tv else Section.Movies
    val editMenusFocus = fi.editMenusFocus
    // The ⚙ beside Edit menus (Settings without going to the bottom of the list); Back from Settings returns to it.
    val gearFocus = fi.gearFocus
    // Where focus goes when the entry it wanted isn't there: Live TV, else Movies, else the ⚙ (or Done editing),
    // so the menu is never left with nothing highlighted (Movies can be switched off).
    fun focusHome() {
        if (runCatching { navFocus.getValue(homeSection).requestFocus() }.isSuccess) return
        if (runCatching { gearFocus.requestFocus() }.isSuccess) return
        runCatching { editMenusFocus.requestFocus() }
    }
    var settingsFromGear by fi.settingsFromGear
    val settingsFirst = fi.settingsFirst
    val groupsFocus = fi.groupsFocus
    // Movies / Shows: the newest titles in your libraries, beside the menu.
    val recentFocus = fi.recentFocus
    // From the guide the menu opens on the channel groups alone; Left again slides the main menu out beside them.
    // (Movies, Shows, Music too: opening on their own list, the main list starts hidden rather than showing for a
    // moment and then sliding away, which jolted the page beside it.)
    // Left back to the main list: shown at once in place of the list beside it, before focus gets there (no frame
    // with both up, which flashed).
    var forceMain by fi.forceMain
    var showMain by remember {
        // (From the full-screen channel list: the channel groups alone beside it, whichever page is behind; Left again
        // brings the main list.)
        // (From the guide: always the channel groups first, whatever "Menu opens on" says; Left again: the main list.)
        // (Since 0.28.3 every page opens the menu on the main list, on that page's entry, except the guide: its channel
        // groups first, Left again the main list. Left on an entry opens its own list. "Menu opens on" is gone.)
        // (Left from a library opened from My Library: My Library's list, on that library.)
        mutableStateOf(!onLibrary && (onMain || onSettings || channelsPanel == null && !((onGroups || page == Page.Tv) && session != null)))
    }
    // Back from Users & profiles / the PIN dialog (drawn over the menu): Settings stays the open list, so the row
    // they came from can take focus again (focus otherwise fell to the top of the menu).
    val profilePage = ProfileUi.users || ProfileUi.pin
    var hadProfilePage by fi.hadProfilePage
    LaunchedEffect(profilePage) {
        if (hadProfilePage && !profilePage) { section = Section.Settings; showMain = true }
        hadProfilePage = profilePage
    }
    val menuScope = androidx.compose.runtime.rememberCoroutineScope()
    val serviceFocus = remember { HashMap<String, FocusRequester>() }
    // Editing the main list in place (hold OK on an entry → Edit the menu, or Move): OK shows / hides, hold OK its
    // options. (UI review, Tier 4, 2026-11-17: hold OK opens Move / Hide / Edit the menu, the shared options pop-up;
    // Move picks up with the shared Carry, ▲ ▼ move, OK / Back / Left put down.)
    var editingMain by fi.editingMain
    val mainCarry = rememberCarry()
    // Edit mode was opened only to move one entry (Move from the options): putting it down leaves edit mode again.
    var moveOnly by fi.moveOnly
    // The entry whose hold-OK options are open (by id: "Movies", "svc:Netflix", "Sports"…).
    var entryMenu by fi.entryMenu
    // A channel group being moved (GroupList), kept here so the column's own Left / Back leave it alone meanwhile.
    val groupCarry = rememberCarry()
    var mainFollow by fi.mainFollow
    // The main list's entries while editing (by id: "Movies", "svc:Netflix"…).
    val mainFocus = remember { HashMap<String, FocusRequester>() }
    // The list beside the main list was gone into from the main list (OK / Right / Left on an entry, the ⚙): Back is
    // the main list again, one level at a time. Opened straight onto a list (the guide's groups), Back closes the menu.
    var subFromMain by fi.subFromMain
    // The guide as the menu opened (its group, and where its highlight was): moving through the groups shows each in
    // the guide behind, and Back out of the menu puts it back (it stayed on the last group looked at, at row 0).
    // (One remembered holder, not three: MenuOverlay is at the method-size limit.)
    val guideReturn = remember { GuideReturn(vm) }
    fun restoreGuide() = guideReturn.restore()
    fun revealMain() {
        // Viewarr launcher: no main list; from the guide's channel groups, home; from Settings, nothing.
        if (io.github.petebutty.viewarr.ui.launcher.Launcher.enabled) { if (!onSettings) io.github.petebutty.viewarr.ui.launcher.Launcher.goHome(); return }
        subFromMain = false
        // Back from a service's rows: onto the service itself.
        val target = serviceOn?.let { if (editingMain) mainFocus["svc:$it"] else serviceFocus[it] }
            ?: (if (editingMain && (section == Section.Movies || section == Section.Shows)) mainFocus[section.name] else null)
            ?: (if (settingsFromGear && section == Section.Settings) gearFocus else null)
            ?: navFocus.getValue(section)
        settingsFromGear = false
        forceMain = true
        if (showMain) { runCatching { target.requestFocus() }.onFailure { menuScope.launch { awaitFrame(); awaitFrame(); runCatching { target.requestFocus() } } }; return }
        showMain = true
        menuScope.launch { awaitFrame(); awaitFrame(); runCatching { target.requestFocus() } }
    }
    // The menu opens on its own list (TV, Movies, …); OK or Right then goes into the highlighted entry.
    LaunchedEffect(Unit) {
        awaitFrame()
        awaitFrame()
        // A service's page (NOW, Netflix…) or Sports open behind: back on that entry in the main list. (Not when the
        // channel groups were asked for: the "Channel groups menu" button, or Left from the live channel list.)
        val entry = openEntry?.takeIf { !onSettings && !onGroups && !onLibrary && channelsPanel == null }
        if (entry != null) {
            // (The main list first, so its entries exist to be focused.)
            showMain = true; forceMain = true
            awaitFrame(); awaitFrame()
            val f = if (entry.startsWith("svc:")) serviceFocus[entry.removePrefix("svc:")] else if (entry == "section:Library") navFocus[Section.Library] else mainFocus[entry]
            // (Only a service or Sports / Trending / What's new counts as "on a service": My Library set as one had no
            // list, so Left on it opened nothing.)
            if (f != null && runCatching { f.requestFocus() }.isSuccess) {
                serviceOn = if (entry.startsWith("svc:")) entry.removePrefix("svc:") else entry.takeIf { it in liveEntries }
                return@LaunchedEffect
            }
        }
        runCatching {
            when {
                onSettings -> { showMain = true; awaitFrame(); settingsFirst.requestFocus() }
                // Left from a library opened from My Library: My Library's list, on that library (it went to the main list).
                onLibrary -> if (runCatching { recentFocus.requestFocus() }.isFailure) {
                    awaitFrame(); awaitFrame()
                    if (runCatching { recentFocus.requestFocus() }.isFailure) { showMain = true; awaitFrame(); navFocus.getValue(Section.Library).requestFocus() }
                }
                // (The page's own entry switched off in the menu: Live TV, never nothing highlighted.)
                onMain -> if (runCatching { navFocus.getValue(section).requestFocus() }.isFailure) focusHome()
                channelsPanel != null -> groupsFocus.requestFocus()
                // The guide (and the remote's "Channel groups menu"): the channel groups; Left again, the main list.
                liveOn && (onGroups || page == Page.Tv) && session != null -> if (runCatching { groupsFocus.requestFocus() }.isFailure) revealMain()
                // Every other page: the main list, on its entry (Left there opens that entry's own list).
                else -> if (runCatching { navFocus.getValue(section).requestFocus() }.isFailure) focusHome()
            }
        }
    }
    val moviesMedia: MediaViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = "media-Movie")
    val showsMedia: MediaViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = "media-Show")
    fun enter(s: Section) {
        when (s) {
            // Into the channel groups: on the guide the main list slides back out (Left brings it again).
            Section.Tv -> { subFromMain = true; runCatching { groupsFocus.requestFocus() } }
            Section.Settings -> { subFromMain = true; runCatching { settingsFirst.requestFocus() } }
            // Movies / Shows themselves: their own rows, not a library left open from My Library (or a service).
            Section.Movies -> { moviesMedia.openLibrary(null, MediaType.Movie); moviesMedia.jumpTo(null); onOpenPage(Page.Movies) }
            Section.Shows -> { showsMedia.openLibrary(null, MediaType.Show); showsMedia.jumpTo(null); onOpenPage(Page.Shows) }
            // My Library: its page, into its rows, as Movies / Shows (it tried its side list, which isn't on screen with
            // the one-panel menu, so OK did nothing. 2026-11-18). Left still opens its list of libraries.
            Section.Library -> onMyLibrary(true)
            Section.Recordings -> onOpenPage(Page.Recordings)
            Section.Music -> onOpenPage(Page.Music)
            Section.Search -> onOpenPage(Page.Search)
            else -> Unit
        }
    }
    /** Right / OK on a main entry: into its page's rows (Live TV: the guide). My Library and Settings, which have no
     *  page of their own: their list. */
    fun openEntryPage(s: Section) {
        hoverJob?.cancel()
        when (s) {
            Section.Tv -> onOpenPage(Page.Tv)
            Section.Music -> onOpenPage(Page.Music)
            else -> enter(s)
        }
    }
    /** Left on a main entry: its own list beside the menu. */
    fun intoList(s: Section) {
        val target = when (s) {
            Section.Tv -> groupsFocus
            Section.Settings -> settingsFirst
            Section.Movies, Section.Shows, Section.Library, Section.Music, Section.Recordings, Section.Search -> recentFocus
            else -> return
        }
        subFromMain = true
        if (runCatching { target.requestFocus() }.isFailure) {
            // (The list not drawn yet, e.g. just switched to: a frame or two later.)
            menuScope.launch { awaitFrame(); awaitFrame(); runCatching { target.requestFocus() } }
        }
    }
    // Back steps out one level: from the right-hand list to the main list, then closes the menu.
    var subListFocused by fi.subListFocused
    // Movies / Shows: the library whose categories fill the list column (the library list slid away), or null.
    var menuCats by fi.menuCats
    // Movies / Shows: editing the list in place (libraries on / off and their order, the rows under "Jump to a row").
    var menuEditing by fi.menuEditing
    LaunchedEffect(section) { menuCats = null; menuEditing = false }
    // Into a section's own list (Movies' rows, Recordings, Music…): the main list slides out to the left, as the
    // guide's channel groups always did, so the menu doesn't cover half the screen. Left brings it back.
    // (Not Settings: its pages open from the Settings entry in the main list.)
    LaunchedEffect(subListFocused) { forceMain = false; if (subListFocused && section != Section.Settings) showMain = false }
    // Every library on your servers, films and shows (Left on a library in Movies' / Shows' list lists them all).
    LaunchedEffect(Unit) { moviesMedia.loadLibraries(MediaType.Movie); showsMedia.loadLibraries(MediaType.Show) }
    val movieLibs by moviesMedia.libraries.collectAsStateWithLifecycle()
    val showLibs by showsMedia.libraries.collectAsStateWithLifecycle()
    val allLibs = movieLibs.orEmpty().map { MediaType.Movie to it } + showLibs.orEmpty().map { MediaType.Show to it }
    val libMusicVm: MusicViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val libMusicAll by libMusicVm.sources.collectAsStateWithLifecycle()
    val libMusicSources = libMusicAll.filter { it.on }.map { it.name }
    // A service's rows being moved (its rows list beside it), and the row whose hold-OK options are open.
    val rowCarry = rememberCarry()
    var rowMenu by fi.rowMenu
    fun endEditMain() {
        editingMain = false; mainCarry.drop(); moveOnly = false; mainFollow = null; menuEditing = false; rowCarry.drop()
        menuScope.launch { awaitFrame(); awaitFrame(); runCatching { focusHome() } }
    }
    val fullPage = showAppearance || showRemote || showNavigation || showSports || showPlayback || showAddons || showUpdates || showAbout || showHomeRows || showPlugins || showLive || showTrouble || showSearchSettings
    BackHandler {
        // (A page switch still waiting from the highlight resting on an entry mustn't land after Back.)
        hoverJob?.cancel()
        when {
            // (Something carried: Back puts it down first. The row's own reorderable normally takes Back itself.)
            rowCarry.active -> rowCarry.drop()
            mainCarry.active -> mainCarry.drop()
            groupCarry.active -> groupCarry.drop()
            editingMain -> endEditMain()
            // A library's categories: back to the library list. Editing the list: done.
            menuCats != null -> menuCats = null
            menuEditing -> menuEditing = false
            // A list gone into from the main list (Settings, Movies' libraries…): the main list again, as Left (Back
            // closed the whole menu). One level per Back.
            subListFocused && subFromMain && !fullPage -> revealMain()
            // From the live channel list: Back from the main list is the groups again; from the groups, the channels.
            // (Beside the channel list both lists can show: the main list is up whenever showMain is.)
            channelsPanel != null -> if (showMain && !subListFocused) {
                showMain = false; forceMain = false; subFromMain = false
                runCatching { groupsFocus.requestFocus() }
            } else { restoreGuide(); onClose() }
            // Opened by Back in the guide (as classic IPTV apps): Back from the groups asks to exit.
            backOut && section == Section.Tv -> { restoreGuide(); onBackOut() }
            // The main list: Back closes the menu, on the page you're on. (It used to go to Live TV first, which also
            // switched the page behind to the guide: two presses, and the wrong page.)
            else -> { restoreGuide(); if (backOut) onBackOut() else onClose() }
        }
    }

    // One strip, as classic IPTV apps: the main list, the section's list (channel groups, libraries…) and from live TV the
    // channels, side by side on one background (drawn once, the same colour and see-through for every column), with
    // a thin line between them. Each Left pushes the next column in from the left; Right / OK goes into it.
    // Over a page: no panel of its own, the page's background carrying on under it (the wallpaper, the film's picture
    // on Movies / Shows), so the menu is the same colour as the rest of the page (2026-11-17; it was a lighter
    // panel). Over full-screen video: the see-through glass, so its text stays readable.
    val stripColour = if (seeThrough) AppColors.MenuPanel else Color.Transparent
    val divider = AppColors.TextSecondary.copy(alpha = 0.15f)
    // Channel groups previewed in the channel column while you move through them (a short pause keeps it smooth).
    var previewJob by fi.previewJob
    val serviceRowsFocus = remember { FocusRequester() }
    // Only once you've stopped on an entry (2026-11-18: flying through the menu loaded every page's rows as it
    // passed, which stuttered): no Up / Down for 0.6 s, then its page behind.
    suspend fun settled() {
        delay(600)
        while (System.currentTimeMillis() - MenuNav.lastMove < 600) delay(120)
    }
    fun hoverService(name: String) {
        hoverJob?.cancel()
        hoverJob = menuScope.launch { settled(); onService(name, null, false) }
    }
    fun hoverPage(s: Section, fresh: Boolean = false) {
        val p = when (s) { Section.Tv -> Page.Tv; Section.Movies -> Page.Movies; Section.Shows -> Page.Shows; Section.Music -> Page.Music; Section.Recordings -> Page.Recordings; else -> null }
        hoverJob?.cancel()
        // My Library: its own page behind (your servers' rows from Movies and Shows). (2026-11-17: the guide stayed.)
        if (s == Section.Library) hoverJob = menuScope.launch { settled(); onMyLibrary(false) }
        if (p != null) hoverJob = menuScope.launch {
            settled()
            // Arriving on Movies / Shows from another entry (e.g. My Library): their own rows, not a library left
            // open there. (Coming back Left from their own list keeps the library you were in.)
            if (fresh && s == Section.Movies && moviesMedia.browse.value != null) { moviesMedia.openLibrary(null, MediaType.Movie); moviesMedia.jumpTo(null) }
            if (fresh && s == Section.Shows && showsMedia.browse.value != null) { showsMedia.openLibrary(null, MediaType.Show); showsMedia.jumpTo(null) }
            onHoverPage(p)
        }
    }
    // (The guide behind follows too, as classic IPTV apps: moving through the groups shows each one's channels there.)
    // Only once you've stopped on a group: each switch loads that group's channels and programmes, and doing it for
    // groups passed on the way (250 ms was shorter than the gap between steady presses) made moving through them
    // jerky. A press within the wait starts it again; a held button never switches until it's let go.
    fun previewGroup(id: Int?) {
        previewJob?.cancel()
        previewJob = menuScope.launch { delay(550); if (vm.groupId.value != id) vm.selectGroup(id) }
    }
    // The Live TV heading's source name: only with two or more sources (and the switch on).
    val liveSources: LiveSourcesViewModel = hiltViewModel()
    val savedSources by liveSources.saved.collectAsStateWithLifecycle()
    val activeSource by liveSources.active.collectAsStateWithLifecycle()
    val headSource = activeSource?.label?.takeIf { savedSources.size > 1 && AppColors.palette.opt(UiOpts.MENU_HEAD_SOURCE) == 0 }
    LaunchedEffect(fullPage) { onFullPage(fullPage) }
    // Beside the preview: the strip steps out of the way (still there underneath for focus to come back to).
    val besidePreview = fullPage && previewOn
    // Opened by holding OK (a button set to the menu): the rest of that press, its repeats and release, mustn't press
    // the entry the highlight lands on.
    Box(Modifier.fillMaxSize().ignoreHeldOk()) {
    Row(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = if (besidePreview) 0f else 1f }) {
    // Settings → Appearance → Main menu → Colour tint: the whole menu's glass (both panels, so they always match) in the
    // colour of the section you're on.
    val tintOn = AppColors.palette.opt(UiOpts.MENU_TINT) == 1 && !fullPage
    Row(Modifier.fillMaxHeight().onSizeChanged { onWidth(it.width) }.background(stripColour)
        // (Every Up / Down in the menu: when it last moved, so pages load only once it has stopped.)
        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown)) MenuNav.lastMove = System.currentTimeMillis(); false }
        // (Not Settings: a grey tint just looked washed out; it keeps the plain glass.)
        .then(if (tintOn && section != Section.Settings) Modifier.background(sectionColour(section).copy(alpha = 0.10f)) else Modifier)) {
        // Left again: the main list appears beside the groups.
        // (As wide as the list beside it: the strip stays one width whichever of the two shows, so the page doesn't move.)
        // One panel at a time (as classic IPTV apps): the main list, or the list of the entry you went into, swapped in the
        // same frame. Both only while editing, with the live channel list, or in Settings.
        // (Settings too: highlighting it no longer pulls its list out; OK or Right opens it.)
        val single = !editingMain && channelsPanel == null
        val mainShown = showMain && (forceMain || !(subListFocused && single))
        // The menu's top: the Viewarr logo, the hint, and ⚙ / 🎤 / Search. Drawn over an entry's own list too when the
        // main list folds away (channel groups…), so they stay put and only the list under them changes.
        // (2026-11-17: they went with the main list.) [folded]: the hint is the way back to the main list.
        val menuHeader: @Composable androidx.compose.foundation.layout.ColumnScope.(Boolean, String?) -> Unit = { folded, foldedHeading ->
            // The Viewarr logo: aperture mark plus the glossy wordmark (the splash's, tools/brand/wordmark.py; its
            // picture has room round it for the shadow, hence the negative gap).
            // The Viewarr logo ("Play Stack", ui/common/Logo.kt; it was the aperture and the glossy wordmark picture).
            // (Previewarr's name is longer: smaller, so it fits the menu's width. 2026-11-25.)
            io.github.petebutty.viewarr.ui.common.ViewarrLogo(Modifier.padding(start = 10.dp, top = 5.dp, bottom = 25.dp),
                markSize = if (io.github.petebutty.viewarr.BuildConfig.LAUNCHER) 24.dp else 30.dp, textSize = if (io.github.petebutty.viewarr.BuildConfig.LAUNCHER) 21.sp else 30.sp)
            // How to get around, for anyone new to it: what Right does on the highlighted entry (not every one has a
            // list: a service or Sports just goes into its page).
            val rightHint = when {
                serviceOn != null -> "Right ›  go into " + serviceOn
                else -> when (section) {
                    Section.Tv -> "Right › guide  ·  Left › groups"
                    Section.Movies -> "Right ›  go into Movies"
                    Section.Shows -> "Right ›  go into Shows"
                    Section.Library -> "Right ›  My Library  ·  ‹ Left: your libraries"
                    Section.Music -> "Right ›  your music"
                    Section.Recordings -> "Right ›  go into Recordings"
                    Section.Search -> "Right ›  search"
                    Section.Settings -> "Right ›  settings"
                }
            }

            // Hints sit at the top of each panel, under the logo here (fixed: the list scrolls under it, not the hint).
            if (!editingMain && AppColors.palette.opt(UiOpts.MENU_HINTS) == 0)
                Column(Modifier.padding(start = 12.dp, bottom = 10.dp).offset(y = (-10).dp)) {
                    // (Short, and "…" if a long service name still doesn't fit: they were cut mid-word. UI review, 2026-11-17.)
                    Text(if (folded) "‹ Left: main menu" else rightHint, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    // (The ✎ Edit menus button went: hold OK on an entry opens its options: Move, Hide, Edit the menu.)
                    // Folded: the list's heading here ("Live TV · Channel groups"), not over the search bar.
                    if (folded && foldedHeading != null) Text(foldedHeading, fontSize = 12.sp, color = AppColors.Accent, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    else Text("Hold OK: move or hide", fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            // ⚙ / 🎤 / Search stay at the top, fixed: the list scrolls under them (they scrolled away with it, cut off at
            // the top. 2026-11-17).
            // Search from the menu: typed or said here, the search page opens on it straight away (results fill in there).
            fun runMenuSearch(raw: String) = onSearchFor(raw.trim().ifEmpty { null })
            val menuVoice = io.github.petebutty.viewarr.ui.common.rememberVoiceSearch(
                onResult = { said -> runMenuSearch(said) },
                // No speech recogniser on this box: the search page and its keyboard's mic, as before.
                onKeyboard = { onVoiceSearch() },
            )
            Row(
                Modifier
                    // Where the main list's entries start (under this row): an entry's own list starts there too.
                    .onGloballyPositioned { c -> mainListTop = with(density) { (c.positionInRoot().y + c.size.height).toDp() } + 8.dp }
                    // Up from the list lands on the gear first, then Right to the mic and Search (it landed on the
                    // nearest, Search). (2026-11-17.)
                    .focusProperties { @Suppress("DEPRECATION") enter = { if (!editingMain) gearFocus else FocusRequester.Default } }
                    .focusGroup()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                // Settings: only here, at the top (it used to be last in the list too), left of Edit menus,
                // which takes the rest of the row.
                // (A round button like the mic beside it: it was a small dim "⚙". 2026-11-17.)
                if (!editingMain) io.github.petebutty.viewarr.ui.common.RoundBarButton(
                    icon = io.github.petebutty.viewarr.ui.common.AppIcons.Settings, label = "Settings", size = 30.dp,
                    onClick = {
                        // (A page switch still waiting from the entry the highlight left mustn't land under Settings.)
                        hoverJob?.cancel()
                        section = Section.Settings; serviceOn = null; settingsFromGear = true; subFromMain = true
                        menuScope.launch { awaitFrame(); awaitFrame(); runCatching { settingsFirst.requestFocus() } }
                    },
                    // (Also Settings' own place in the menu: everything returning to "Settings" lands here.)
                    modifier = Modifier.padding(start = 8.dp).focusRequester(gearFocus).focusRequester(navFocus.getValue(Section.Settings)).onPreviewKeyEvent { e -> e.key == Key.DirectionUp },
                )
                // Voice and search, beside Settings, as the search page's own bar: a round mic, then a slim pill that
                // opens search (the ✎ Edit menus button they replace: hold OK on an entry instead). Menu-row height.
                if (!editingMain) {
                    val micFocus = remember { FocusRequester() }
                    val searchKeys = Modifier.onPreviewKeyEvent { e ->
                        if (e.key == Key.DirectionUp) return@onPreviewKeyEvent true
                        false
                    }
                    // The shared round mic (as the search page's, smaller, no pulsing rings in the narrow menu).
                    VoiceButton(
                        onClick = { menuVoice.toggle() },
                        // Left: the gear, always (over a folded list Left otherwise brought the main list back, so the
                        // gear couldn't be reached. 2026-11-17).
                        modifier = Modifier.padding(start = 2.dp).focusRequester(micFocus).then(searchKeys).onPreviewKeyEvent { e ->
                            if (e.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                            if (e.type == KeyEventType.KeyDown) runCatching { gearFocus.requestFocus() }
                            true
                        },
                        listening = menuVoice.listening, size = 30.dp, pulse = false,
                    )
                    // The search box itself: OK starts typing here (the keyboard only then: passing through doesn't
                    // bring it up); the keyboard's search key opens the search page with what was typed.
                    var menuQuery by remember { mutableStateOf("") }
                    var menuTyping by remember { mutableStateOf(false) }
                    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    fun startSearch() {
                        keyboard?.hide(); menuTyping = false
                        val q = menuQuery.trim(); menuQuery = ""
                        runMenuSearch(q)
                    }
                    // The shared slim search pill (lit with the accent while highlighted, a ring while typing).
                    SearchField(
                        value = menuQuery,
                        onValueChange = { menuQuery = it },
                        // (One word: anything longer is cut short in the slim pill.)
                        placeholder = "Search",
                        readOnly = !menuTyping,
                        compact = true,
                        listening = menuVoice.listening,
                        litWhenFocused = true,
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { startSearch() }, onDone = { startSearch() }),
                        modifier = Modifier.weight(1f).height(30.dp)
                            .onFocusChanged { if (!it.isFocused) { menuTyping = false; keyboard?.hide() } }
                            .onPreviewKeyEvent { e ->
                                if (e.key == Key.DirectionUp) return@onPreviewKeyEvent true
                                if (!menuTyping && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                                    // (On the release, but not the release of a held OK that opened something else.)
                                    if (e.type == KeyEventType.KeyUp && !HeldOk.releasePending) { menuTyping = true; menuScope.launch { awaitFrame(); keyboard?.show() } }
                                    return@onPreviewKeyEvent true
                                }
                                if (!menuTyping && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { openEntryPage(section); return@onPreviewKeyEvent true }
                                // Left: the mic (not the main list coming back over a folded list).
                                if (!menuTyping && e.key == Key.DirectionLeft) { if (e.type == KeyEventType.KeyDown) runCatching { micFocus.requestFocus() }; return@onPreviewKeyEvent true }
                                false
                            },
                    )
                }
                // Editing the menu: the way out.
                if (editingMain) MenuItem(
                    text = "✓  Done editing",
                    selected = true,
                    onClick = { endEditMain() },
                    modifier = Modifier.weight(1f).focusRequester(editMenusFocus).onPreviewKeyEvent { e ->
                        if (e.key == Key.DirectionUp) return@onPreviewKeyEvent true
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                            runCatching { focusHome() }
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
                )
            }
        }
        GrowFromLeftToggle(mainShown, MenuW) { Box(Modifier.width(MenuW).fillMaxHeight()) { Column(
            Modifier
                .width(MenuW)
                .fillMaxHeight()
                .padding(horizontal = 10.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            menuHeader(false, null)
            // (Faded at its top / bottom edge while there's more list that way: a row scrolled half under the edge left a
            // sliver of itself under the Search row. 2026-11-18.)
            val clearOfFade = with(androidx.compose.ui.platform.LocalDensity.current) { remember(this) { clearOfEdges(32.dp.toPx()) } }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides clearOfFade) {
            Column(Modifier.weight(1f).scrollEdgeFade(MenuScrolls.main).verticalScroll(MenuScrolls.main), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Settings → Appearance → Movies & Shows off: a live-TV-only menu.
            // The main list: Live TV, then the rest in your order (hold OK on one to edit the menu here), then Settings.
            // (Settings → Appearance → Main menu switches entries on and off too.)
            val appearanceVm: AppearanceViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val movable = buildList {
                // (Trending / What's new are films and shows: there without live TV too.)
                addAll(liveEntries.filter { liveOn || it != "Sports" })
                if (!AppColors.palette.hideMedia || !liveOn) { add(Section.Movies.name); add(Section.Shows.name); add(Section.Library.name) }
                services.forEach { add("svc:" + it.first) }
                if (musicMenuOn) add(Section.Music.name)
                add(Section.Recordings.name); add(Section.Search.name)
            }
            val order = PendingOrder.get("menu", AppColors.palette.menuOrder)
            val ordered = movable.withIndex().sortedBy { (i, id) -> order.indexOf(id).let { if (it < 0) order.size + i else it } }.map { it.value }
            fun visible(id: String) = AppColors.palette.shows("menu:$id")
            fun labelOf(id: String) = when { id in liveEntries -> id; id.startsWith("svc:") -> id.removePrefix("svc:"); else -> Section.valueOf(id).label }
            LaunchedEffect(mainFollow, editingMain) {
                val id = mainFollow ?: return@LaunchedEffect
                awaitFrame(); awaitFrame()
                // (Out of edit mode each kind of entry has its own focus: a service's, Sports', a section's.)
                val f = when {
                    editingMain -> mainFocus[id]
                    id.startsWith("svc:") -> serviceFocus[id.removePrefix("svc:")]
                    id in liveEntries -> mainFocus[id]
                    else -> runCatching { navFocus.getValue(Section.valueOf(id)) }.getOrNull()
                }
                runCatching { f?.requestFocus() }
            }
            // A picked-up entry one place up or down (from the latest order, not the one last drawn: a held ▲ / ▼
            // outruns the redraw), the list following it.
            fun moveMain(id: String, by: Int, bring: androidx.compose.foundation.relocation.BringIntoViewRequester) {
                val fresh = PendingOrder.get("menu", AppColors.palette.menuOrder)
                val list = movable.withIndex().sortedBy { (k, m) -> fresh.indexOf(m).let { if (it < 0) fresh.size + k else it } }.map { it.value }.toMutableList()
                val i = list.indexOf(id); val j = i + by
                if (i < 0 || j !in list.indices) return
                list[i] = list[j]; list[j] = id; PendingOrder.set("menu", list); appearanceVm.setMenuOrder(list); mainFollow = null; mainFollow = id
                menuScope.launch { awaitFrame(); awaitFrame(); runCatching { bring.bringIntoView() } }
            }
            // Hold OK on an entry: Move / Hide (Show while editing) / Edit the menu, the shared options pop-up.
            entryMenu?.let { id ->
                MainEntryOptions(
                    label = labelOf(id), shown = visible(id), editing = editingMain,
                    onMove = { if (!editingMain) moveOnly = true; editingMain = true; mainCarry.pickUp(id); mainFollow = null; mainFollow = id },
                    onHide = { h ->
                        appearanceVm.setTitlePart("menu:$id", !h)
                        // (Hidden out of edit mode it goes from the list: the highlight to Live TV, not nowhere.)
                        if (h && !editingMain) menuScope.launch { awaitFrame(); awaitFrame(); focusHome() }
                    },
                    onEdit = { editingMain = true; mainFollow = null; mainFollow = id },
                    onClose = { entryMenu = null },
                )
            }
            val sectionItem: @Composable (Section) -> Unit = { s ->
                    MenuItem(
                        text = s.label,
                        leading = { MenuIcons.Entry(s.name, moviesMedia) },
                        selected = s == section,
                        onClick = {
                            section = s
                            openEntryPage(s)
                        },
                        // Hold OK: its options, Move / Hide / Edit the menu (not Live TV or Settings, which always stay).
                        onLongClick = if (s == Section.Tv || s == Section.Settings) null else ({ entryMenu = s.name }),
                        modifier = Modifier
                            .focusRequester(navFocus.getValue(s))
                            // Up from Live TV: the gear (Settings) beside Edit menus (the folded-away channel groups were
                            // nearer and took it); Edit menus when the gear isn't there (while editing).
                            .onPreviewKeyEvent { e ->
                                if (s == Section.Tv && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp)
                                    runCatching { gearFocus.requestFocus() }.isSuccess || runCatching { editMenusFocus.requestFocus() }.isSuccess
                                else false
                            }
                            .onFocusChanged { if (it.isFocused && returnTo == null) { val fresh = section != s; section = s; serviceOn = null; hoverPage(s, fresh) } }
                            .onPreviewKeyEvent { e ->
                                // Right does the same as OK: open what's inside this entry.
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && !(s == Section.Settings && (showAppearance || showRemote || showNavigation || showSports || showPlayback || showAddons || showUpdates || showAbout || showHomeRows || showPlugins || showLive || showTrouble || showSearchSettings))) {
                                    // Movies, Shows, Recordings: straight into the page, as a service does (their lists are where
                                    // Left from that page lands). Music and Search: into their list.
                                    openEntryPage(s)
                                    true
                                } else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && !editingMain) {
                                    // Left: this entry's own list (channel groups, Movies' rows and libraries, Music's
                                    // albums and artists, recordings, Settings' pages).
                                    intoList(s)
                                    true
                                } else false
                            },
                    )
            }
            // Edit mode: every list in the menu can be changed (the main list here; a service's rows, Movies' and
            // Shows' lists beside it). Off: what's switched off is hidden and the editing panels go.
            if (liveOn) sectionItem(Section.Tv)
            (if (editingMain) ordered else ordered.filter(::visible)).forEach { id ->
                androidx.compose.runtime.key(id) {
                    // A picked-up entry moving past the top or bottom: the list follows it (focus staying on the same
                    // entry as it moves doesn't scroll by itself, so it went off screen).
                    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                    when {
                        // Editing: every entry, On / Off; the one picked up says so.
                        editingMain -> MenuItem(
                            text = (if (mainCarry.isCarried(id)) "↕  " else "") + labelOf(id),
                            leading = { MenuIcons.Entry(id, moviesMedia) },
                            trailing = if (mainCarry.isCarried(id)) "Moving" else if (visible(id)) "On" else "Off",
                            dim = !visible(id) && !mainCarry.isCarried(id),
                            selected = mainCarry.isCarried(id),
                            onClick = { appearanceVm.setTitlePart("menu:$id", !visible(id)) },
                            onLongClick = { entryMenu = id },
                            modifier = Modifier.focusRequester(mainFocus.getOrPut(id) { FocusRequester() })
                                // Carried: ▲ ▼ move it, OK / Back / Left put it down (Move from the options only: and
                                // out of edit mode again).
                                .reorderable(
                                    carried = mainCarry.isCarried(id),
                                    onMove = { by -> moveMain(id, by, bring) },
                                    onDrop = {
                                        mainCarry.drop()
                                        if (moveOnly) { moveOnly = false; editingMain = false; menuEditing = false; mainFollow = null; mainFollow = id }
                                    },
                                )
                                .bringIntoViewRequester(bring)
                                .onFocusChanged {
                                    if (!it.isFocused) return@onFocusChanged
                                    // Its own list beside it: a service's rows, Movies' or Shows' list.
                                    if (id.startsWith("svc:")) serviceOn = id.removePrefix("svc:")
                                    else { serviceOn = null; if (id == Section.Movies.name || id == Section.Shows.name) section = Section.valueOf(id) }
                                }
                                .onPreviewKeyEvent { e ->
                                    // Right: into the list beside it, to edit that.
                                    if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                                    when {
                                        id.startsWith("svc:") -> runCatching { serviceRowsFocus.requestFocus() }
                                        id == Section.Movies.name || id == Section.Shows.name -> { menuEditing = true }
                                        // My Library: which of your servers' rows it shows, and their order.
                                        id == Section.Library.name -> { homeRowsMine = true; showHomeRows = true }
                                        else -> Unit
                                    }
                                    true
                                },
                        )
                        // Sports: its page of games (moving onto it shows it behind; OK / Right: into it).
                        id in liveEntries -> MenuItem(
                            text = id,
                            leading = { MenuIcons.Entry(id, moviesMedia) },
                            selected = serviceOn == id,
                            onClick = { hoverJob?.cancel(); onLiveEntry(id, true) },
                            onLongClick = { entryMenu = id },
                            modifier = Modifier
                                .focusRequester(mainFocus.getOrPut(id) { FocusRequester() })
                                .onFocusChanged { if (it.isFocused && returnTo == null) { serviceOn = id; hoverJob?.cancel(); hoverJob = menuScope.launch { settled(); onLiveEntry(id, false) } } }
                                .onPreviewKeyEvent { e ->
                                    when {
                                        e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> { hoverJob?.cancel(); onLiveEntry(id, true); true }
                                        // Left: nothing (they have no list of their own; Android's own move went on to
                                        // whichever list was nearest: the channel groups, Music's).
                                        e.key == Key.DirectionLeft -> true
                                        else -> false
                                    }
                                },
                        )
                        // A service: its page behind as you move onto it; OK straight in; Right onto its rows (to reorder them).
                        id.startsWith("svc:") -> {
                            val name = id.removePrefix("svc:")
                            val rows = services.firstOrNull { it.first == name }?.second.orEmpty()
                            MenuItem(
                                text = name,
                                leading = { MenuIcons.Entry(id, moviesMedia) },
                                selected = serviceOn == name,
                                onClick = { hoverJob?.cancel(); onService(name, null, true) },
                                onLongClick = { entryMenu = id },
                                modifier = Modifier
                                    .focusRequester(serviceFocus.getOrPut(name) { FocusRequester() })
                                    .onFocusChanged { if (it.isFocused && returnTo == null) { serviceOn = name; hoverService(name) } }
                                    .onPreviewKeyEvent { e ->
                                        // Left: nothing (no list of its own; Android's own move went on to another list).
                                        if (e.key == Key.DirectionLeft) return@onPreviewKeyEvent true
                                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                            // Out of edit mode a service has no list beside it: Right goes straight into its page.
                                            if (!editingMain || rows.isEmpty() || runCatching { serviceRowsFocus.requestFocus() }.isFailure) { hoverJob?.cancel(); onService(name, null, true) }
                                            true
                                        } else false
                                    },
                            )
                        }
                        else -> sectionItem(Section.valueOf(id))
                    }
                }
            }
            // (No Settings entry at the bottom: the ⚙ at the top opens it.)
            if (editingMain) MenuItem(text = "✓  Done", selected = false, onClick = { endEditMain() }, modifier = Modifier.padding(top = 8.dp))
            if (mainCarry.active) Text(movingHint(), fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
                    } } // (LocalBringIntoViewSpec: the highlighted row kept clear of the faded edges)
        } 
        } }

        // One panel at a time (as classic IPTV apps): moving down the main list shows only the main list; Right / OK on an entry
        // opens its list (groups, libraries…) in place of it. Both show only while editing, or with the live channel
        // list beside them.
        val subHidden = single && mainShown && (!subListFocused || forceMain)
        if (mainShown && !subHidden) Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
        val subHint = single && !mainShown && !fullPage && AppColors.palette.opt(UiOpts.MENU_HINTS) == 0
        // Room at the top for the hint (and the heading under it, in the padding as before). On its own (the main list
        // folded away): its first entry level with the main list's first, so stepping between them (channel groups ⇄
        // Live TV) doesn't jump up and down. (2026-11-17: the groups started well above the main list.)
        // (Beside the live channel list too: the groups had no header there, so Up from the first group went nowhere.
        // Not while that list's programme panel has folded the groups away to nothing.)
        val aligned = (single || (channelsPanel != null && !ChannelPanelState.programmesOpen)) && !mainShown && !fullPage
        // (Measured from the header's ⚙ / Search row; before its first drawing, about where it lands.)
        val subTop = if (aligned) maxOf(mainListTop.takeIf { it > 0.dp } ?: 170.dp, if (subHint) 40.dp else 24.dp)
            else if (subHint) 40.dp else 24.dp
        // The right-hand list, as a block of its own: compiled as a separate function, which keeps MenuOverlay
        // under Android's size limit for optimising a function (it reads and changes the menu's state as before).
        val subListPanel: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
            val svc = serviceOn?.let { n -> services.firstOrNull { it.first == n } }
            // Colour edge: a thin bar down the list's left side in the highlight colour.
            if (AppColors.palette.opt(UiOpts.MENU_EDGE) == 1 && !fullPage && !subHidden) Box(
                Modifier.align(Alignment.CenterStart).offset(x = (-8).dp).width(3.dp).fillMaxHeight(0.92f)
                    // (Your highlight colour, to match the rest of the menu; the tint and icon column keep the section colours.)
                    .background(AppColors.Accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
            )
            // Which list this is (Settings → Appearance → Main menu → List headings): the guide's channel group "Movies"
            // is never taken for the Movies libraries. In the top padding, above the list.
            val heading = when {
                fullPage || AppColors.palette.opt(UiOpts.MENU_HEADINGS) == 1 -> null
                svc != null || (editingMain && !menuEditing) -> null
                // The path: "Live TV › Channel groups" (and the source); else the heading in capitals.
                AppColors.palette.opt(UiOpts.MENU_HEADINGS) == 2 -> sectionIcon(section) + "  " + section.label + when (section) {
                    Section.Tv -> " › Channel groups" + (headSource?.let { " · $it" } ?: "")
                    Section.Movies, Section.Shows -> menuCats?.let { " › " + it.title } ?: ""
                    else -> ""
                }
                else -> sectionIcon(section) + "  " + when (section) {
                    Section.Tv -> "LIVE TV · CHANNEL GROUPS" + (headSource?.let { " · " + it.uppercase() } ?: "")
                    else -> section.label.uppercase()
                }
            }
            if (heading != null && !aligned) Text(
                heading, fontSize = 11.sp, color = AppColors.Accent, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp).offset(y = (-18).dp),
            )
            // Folded (an entry's own list on its own): the menu's top stays where it was (logo, hint, ⚙ / 🎤 / Search), the
            // list starting under it as the main list did.
            if (aligned) Column(
                Modifier.align(Alignment.TopStart).offset(x = (-8).dp, y = -subTop).width(MenuW)
                    .onFocusChanged { foldedHeaderFocused = it.hasFocus }.padding(horizontal = 10.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { menuHeader(true, heading) }
            // In an entry's own list (the main list folded away): how to get back to it, at the top as the main list's
            // hint is (above the heading when there is one); under the logo when that's shown.
            if (subHint && !aligned) Text(
                "‹ Left: main menu", fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp).offset(y = (-(subTop.value - if (aligned) 74f else 6f)).dp),
            )
            // Editing the menu: what the buttons do.
            if (editingMain && svc == null && !menuEditing) Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("EDITING THE MENU", fontSize = 12.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("OK: show or hide", fontSize = 15.sp, color = AppColors.TextSecondary)
                Text(if (mainCarry.active) movingHint() else "Hold OK: options (move, hide)", fontSize = 15.sp, color = AppColors.TextSecondary)
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
                val sRows = sOrdered.filter { RowSets.matches(key, it) }
                val mRows = mOrdered.filter { RowSets.matches(key, it) }
                val both = sRows.isNotEmpty() && mRows.isNotEmpty()
                // In the page's fixed order (New, Originals, Popular, Top rated; shows before films): on / off only.
                val all = (sRows.map { Triple(it, showsMedia, sHidden) } + mRows.map { Triple(it, moviesMedia, mHidden) })
                    .sortedWith(compareBy({ RowSets.kindRank(it.first) }, { it.second === moviesMedia }))
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(svc.first.uppercase() + "  ·  ROWS", fontSize = 12.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    all.forEachIndexed { i, (row, media, hidden) ->
                        val off = row.id in hidden
                        val films = media === moviesMedia
                        val carried = rowCarry.isCarried(row.id)
                        val title = RowSets.rowTitle(row) + if (both && films) " (films)" else ""
                        androidx.compose.runtime.key(row.id) {
                            MenuItem(
                                text = (if (carried) "↕  " else "") + title,
                                trailing = if (carried) "Moving" else if (off) "Off" else null,
                                dim = off && !carried,
                                selected = carried,
                                onClick = { media.setRowHidden(row.id, !off); menuScope.launch { delay(150); onService(svc.first, null, false) } },
                                // Hold OK: Move / Hide or Show (the shared options pop-up; rows could never be moved).
                                onLongClick = { rowMenu = row.id },
                                modifier = (if (i == 0) Modifier.focusRequester(serviceRowsFocus) else Modifier)
                                    // Carried: ▲ ▼ move it, OK / Back / Left put it down (Left only puts it down now; it
                                    // also left the list).
                                    .reorderable(
                                        carried = carried,
                                        onMove = { by ->
                                            media.moveRowAmong(row.id, by) { RowSets.matches(key, it) }
                                            menuScope.launch { delay(150); onService(svc.first, null, false) }
                                        },
                                        onDrop = rowCarry::drop,
                                    )
                                    .onPreviewKeyEvent { e ->
                                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { revealMain(); true } else false
                                    },
                            )
                            if (rowMenu == row.id) OptionsPopup(
                                title = title,
                                options = listOf(
                                    rowCarry.moveOption(row.id),
                                    PopupOption(if (off) "Show" else "Hide") { media.setRowHidden(row.id, !off); menuScope.launch { delay(150); onService(svc.first, null, false) } },
                                ),
                                onClose = { rowMenu = null },
                            )
                        }
                    }
                    Text(if (rowCarry.active) movingHint() else "OK: show or hide  ·  Hold OK: options  ·  Always: New, Originals, Popular, Top rated", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 10.dp))
                }
                }
            } else when (section) {
                Section.Tv -> GroupList(
                    carry = groupCarry,
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
                    favouriteCount = vm.favourites.collectAsStateWithLifecycle().value.size,
                    onMove = { id, dir -> vm.moveGroup(id, dir) },
                    hidden = vm.menuHiddenGroups.collectAsStateWithLifecycle().value,
                    onHide = { name, h ->
                        vm.setGroupMenuHidden(name, h)
                        // ★ Favourites hidden while on it: back to all channels.
                        if (h && name == FAV_HIDDEN && vm.groupId.value == GuideViewModel.FAVOURITES) vm.selectGroup(null)
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
                    liveItem = liveItem,
                    onLive = { runCatching { navFocus.getValue(Section.Settings).requestFocus() }; showLive = true },
                    troubleItem = troubleItem,
                    onTrouble = { runCatching { navFocus.getValue(Section.Settings).requestFocus() }; showTrouble = true },
                    searchItem = searchSettingsItem,
                    onSearchSettings = { runCatching { navFocus.getValue(Section.Settings).requestFocus() }; showSearchSettings = true },
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
                Section.Library -> LibraryMenuPanel(
                    focusRequester = recentFocus,
                    libraries = allLibs,
                    musicSources = libMusicSources,
                    current = (moviesMedia.browse.value ?: showsMedia.browse.value)?.let { b ->
                        (if (b.type == MediaType.Movie) moviesMedia else showsMedia).browseParentKey ?: b.library.key
                    },
                    onHome = { into -> onMyLibrary(into) },
                    onHover = { t, lib ->
                        // (My Library's own page off first: the page's hover closes a library while a row set is up.)
                        if (moviesMedia.currentRowSet.value == io.github.petebutty.viewarr.ui.media.MyLibrary.SET) moviesMedia.showRowSet(null)
                        // (The library already open: left as it is, scrolled where you were, rather than reloaded.)
                        val m = if (t == MediaType.Movie) moviesMedia else showsMedia
                        if (m.browse.value?.library?.key != lib.key) m.openLibrary(lib, t)
                        onHoverPage(if (t == MediaType.Movie) Page.Movies else Page.Shows)
                    },
                    onOpen = { t, lib ->
                        if (moviesMedia.currentRowSet.value == io.github.petebutty.viewarr.ui.media.MyLibrary.SET) moviesMedia.showRowSet(null)
                        (if (t == MediaType.Movie) moviesMedia else showsMedia).openLibrary(lib, t)
                        onOpenPage(if (t == MediaType.Movie) Page.Movies else Page.Shows)
                    },
                    onMusic = { onOpenPage(Page.Music) },
                )
                Section.Search -> SearchMenuPanel(onSearch = onSearchFor, onVoice = onVoiceSearch, focus = recentFocus, musicOn = musicMenuOn)
                Section.Recordings -> RecordingsPanel(
                    focusRequester = recentFocus,
                    channelName = { id -> channels.firstOrNull { it.id == id }?.name },
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
                    onOpenPage = { onOpenPage(if (section == Section.Movies) Page.Movies else Page.Shows) },
                    onPreviewPage = { onHoverPage(if (section == Section.Movies) Page.Movies else Page.Shows) },
                    // (Never on screen with a service's rows: one carry for both, which the column's Left / Back check.)
                    carry = rowCarry,
                )
            }
        }
        Box(
            Modifier
                // (With the live channel list beside it: folded away while that list's programme panel is open, which had
                // no room otherwise. UI review, Tier 1, 2026-11-17.)
                .width(if (subHidden || (channelsPanel != null && section == Section.Tv && ChannelPanelState.programmesOpen)) 0.dp else MenuW)
                .fillMaxHeight()
                .onFocusChanged { subListFocused = it.hasFocus }
                // Any other list (Music, Search): a Left nothing in it used brings the main list back.
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && !mainShown && section != Section.Settings) { revealMain(); true } else false
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // On the menu's top drawn over this list (⚙ / 🎤 / Search): its own keys (Left along the row to the
                    // mic and the gear); this caught Left first and brought the main list back. (2026-11-17.)
                    if (foldedHeaderFocused) return@onPreviewKeyEvent false
                    // A row or group being moved: Left puts it down there (its reorderable), nothing here.
                    if (rowCarry.active || groupCarry.active) return@onPreviewKeyEvent false
                    when {
                        // (Left from any list: the main menu, below.)
                        // Editing the list: Left finishes (a library being moved is put down where it is).
                        e.key == Key.DirectionLeft && menuEditing -> { menuEditing = false; true }
                        // Left goes back to this section's menu entry, not whichever one is geometrically closest.
                        e.key == Key.DirectionLeft && (section == Section.Tv || section == Section.Movies || section == Section.Shows || section == Section.Library || section == Section.Recordings ||
                            (section == Section.Settings && !showAppearance && !showRemote && !showNavigation && !showSports && !showPlayback && !showAddons && !showUpdates && !showAbout && !showHomeRows && !showPlugins && !showLive && !showTrouble && !showSearchSettings)) -> {
                            revealMain()
                            true
                        }
                        else -> false
                    }
                }
                .padding(start = 8.dp, end = 8.dp, top = subTop, bottom = 24.dp),
        ) { subListPanel() }

        if (channelsPanel != null && section == Section.Tv) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
            channelsPanel { runCatching { groupsFocus.requestFocus() } }
        }
    }
        // The rest of the screen: dimmed only over full-screen video (the menu sits over the picture there). Over a
        // page (the guide) the page is pushed along beside the menu, not under it: no shade (2026-11-17: a dark
        // band slid over the guide as the menu opened, its edge showing mid-slide).
        if (seeThrough) Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black.copy(alpha = 0.4f * AppColors.palette.menuAlpha)))
        else Spacer(Modifier.weight(1f))
    }
    // With the preview up: the page full width, a clear window where the picture is, and its list of sections
    // starting below it (SettingsPage).
    SideEffect { SettingsLayout.belowPreview = besidePreview }
    // (The menu closing with a page open beside the picture: settings pages elsewhere go back to their own layout.)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { SettingsLayout.belowPreview = false } }
    Box(Modifier.fillMaxSize().then(if (besidePreview) Modifier.cornerWindow() else Modifier)) {
    // Settings → Playback is a full page of its own, over the menu.
    // The pages SettingsMap lays out (opened from the list, or on a setting from Search settings).
    @Composable fun mapped(id: String, item: FocusRequester, close: () -> Unit) {
        MappedSettingsScreen(id, startSection = mappedStart?.first, startItem = mappedStart?.second, onBack = {
            // Move focus to its item first, while the page is still up; otherwise it jumps to Search.
            runCatching { item.requestFocus() }
            close(); mappedStart = null
            returnTo = item
        })
    }
    if (showPlayback) mapped(SettingsMap.PLAYBACK, playbackItem) { showPlayback = false }
    if (showAppearance) mapped(SettingsMap.APPEARANCE, appearanceItem) { showAppearance = false }
    if (showLive) mapped(SettingsMap.LIVE, liveItem) { showLive = false }
    val advanced = io.github.petebutty.viewarr.ui.settings.AdvancedPage
    if (showTrouble) mapped(if (advanced.open) SettingsMap.ADVANCED else SettingsMap.SYSTEM, if (advanced.open) advanced.item else troubleItem) { showTrouble = false; advanced.open = false }
    if (showNavigation) mapped(SettingsMap.NAVIGATION, navigationItem) { showNavigation = false }
    if (showSearchSettings) {
        fun other(title: String, words: String, open: () -> Unit) = OtherSettingsPage(title, words) { showSearchSettings = false; open() }
        SettingsSearchScreen(
            onBack = { runCatching { searchSettingsItem.requestFocus() }; showSearchSettings = false; returnTo = searchSettingsItem },
            onOpen = { page, sec, title ->
                showSearchSettings = false
                mappedStart = sec to title
                when (page) {
                    SettingsMap.APPEARANCE -> showAppearance = true
                    SettingsMap.LIVE -> showLive = true
                    SettingsMap.PLAYBACK -> showPlayback = true
                    SettingsMap.NAVIGATION -> showNavigation = true
                    SettingsMap.ADVANCED -> { io.github.petebutty.viewarr.ui.settings.AdvancedPage.open = true; showTrouble = true }
                    else -> showTrouble = true
                }
            },
            others = listOf(
                other("Remote buttons", "buttons remote presets classic skip size channel change delay zapping search button colour extra spare learn") { showRemote = true },
                other("Home page & menu", "home page rows libraries order menu lists recently added continue watching") { showHomeRows = true },
                other("Sports", "teams leagues games football basketball nfl follow") { showSports = true },
                other("Accounts & sources", "accounts sources sign in plex jellyfin emby trakt seerr dispatcharr iptv playlist addons music navidrome server") { onAccounts() },
                other("Dispatcharr plugins", "plugins dispatcharr admin") { showPlugins = true },
                other("Updates", "update version install new release") { showUpdates = true },
                other("Home Assistant", "home assistant smart home automation lights scene doorbell camera pop-up notify token") { io.github.petebutty.viewarr.ui.settings.HaSettings.open = true },
                other("About", "about version storage clear data tour live tv sources this tv") { showAbout = true },
            ),
        )
    }
    if (showPlugins) {
        PluginsSettingsScreen(onBack = {
            runCatching { pluginsItem.requestFocus() }
            showPlugins = false
            returnTo = pluginsItem
        })
    }
    if (showHomeRows) {
        HomeRowsSettingsScreen(startMine = homeRowsMine, onBack = {
            showHomeRows = false
            // (From the menu's edit mode, My Library: back to it there, not to Settings.)
            if (homeRowsMine) { homeRowsMine = false; mainFollow = null; mainFollow = Section.Library.name }
            else { runCatching { homeRowsItem.requestFocus() }; returnTo = homeRowsItem }
        })
    }
    if (showRemote) {
        RemoteSettingsScreen(onBack = {
            runCatching { remoteItem.requestFocus() }
            showRemote = false
            returnTo = remoteItem
        })
    }
    if (showSports) {
        SportsSettingsScreen(onBack = {
            showSports = false
            if (sportsJumped) sportsJumped = false
            else { runCatching { sportsItem.requestFocus() }; returnTo = sportsItem }
        })
    }
    if (showAbout) {
        AboutSettingsScreen(onBack = {
            runCatching { aboutItem.requestFocus() }
            showAbout = false
            returnTo = aboutItem
        })
    }
    if (showUpdates) {
        UpdatesSettingsScreen(onBack = {
            runCatching { updatesItem.requestFocus() }
            showUpdates = false
            returnTo = updatesItem
        })
    }
    if (showAddons) {
        AddonsSettingsScreen(onBack = {
            runCatching { addonsItem.requestFocus() }
            showAddons = false
            returnTo = addonsItem
        })
    }
    }
    }
}

@Composable
internal fun GroupList(carry: Carry, sourcesVm: LiveSourcesViewModel, focusRequester: FocusRequester, groups: List<GroupWithCount>, allCount: Int?, selectedId: Int?, onSelect: (Int?) -> Unit, onHover: (Int?) -> Unit = {}, favouriteCount: Int = 0,
    /** Hold OK on a group → Move: picked up ([carry]); ▲ ▼ move it ([Int] id, -1 / 1), OK, Back or Left put it down. */
    onMove: ((Int, Int) -> Unit)? = null,
    /** Groups hidden from this list (names), and hiding / showing one. */
    hidden: Set<String> = emptySet(),
    onHide: ((String, Boolean) -> Unit)? = null) {
    // Several Live TV sources (Dispatcharr instances, IPTV logins, playlists): the active one heads the list; OK
    // opens the others to switch to.
    val saved by sourcesVm.saved.collectAsStateWithLifecycle()
    val active by sourcesVm.active.collectAsStateWithLifecycle()
    var sourcesOpen by remember { mutableStateOf(false) }
    // Switching Live TV source: an admin can lock it (another saved source may be someone else's login).
    val showSources = saved.size > 1 && Access.can(Lock.SOURCES)
    val head = if (showSources) 1 + (if (sourcesOpen) saved.size - 1 else 0) else 0
    val hideAll = AppColors.palette.hideAllChannels
    // Hidden from this list (hold OK → Hide): left out here, gathered at the end.
    val shownGroups = groups.filter { it.name !in hidden }
    val hiddenGroups = groups.filter { it.name in hidden }
    // ★ Favourites heads the groups once there are any (hold OK on a channel: Add to favourites).
    // ★ Favourites and All channels can be hidden from the list too (hold OK → Hide).
    val favHidden = FAV_HIDDEN in hidden
    val favRow = if (favouriteCount > 0 && !favHidden) 1 else 0
    val uiOpts: UiOptsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    fun hideAllChannels(h: Boolean) = uiOpts.set(UiOpts.GROUP_ALL, if (h) 1 else 0)
    val selectedIndex = head + when (selectedId) {
        null -> favRow
        GuideViewModel.FAVOURITES -> 0
        else -> favRow + shownGroups.indexOfFirst { it.id == selectedId } + if (hideAll) 0 else 1
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, selectedIndex - 3))
    val selectedFocus = focusRequester
    // The group shown may have no row here (hidden, Favourites emptied, All channels hidden): the highlight then lands
    // on the first group instead of nowhere (OK / Right on Live TV did nothing).
    val selectedShown = when (selectedId) {
        null -> !hideAll
        GuideViewModel.FAVOURITES -> favRow == 1
        else -> shownGroups.any { it.id == selectedId }
    }
    val firstRow = when { selectedShown -> null; favRow == 1 -> "fav"; !hideAll -> "all"; else -> shownGroups.firstOrNull()?.id?.toString() }
    // The group picked up (Move), by id.
    val moving = carry.id?.toIntOrNull()
    // Hold OK on a group: its options (Move, Hide from this list) in the shared options pop-up (they opened under it,
    // with no Cancel: UI review, Tier 4, 2026-11-17); the hidden ones gather at the end, folded.
    var optionsFor by remember { mutableStateOf<Int?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    val hiddenTotal = hiddenGroups.size + (if (hideAll) 1 else 0) + (if (favHidden && favouriteCount > 0) 1 else 0)
    // After Move / Hide / Show / a source switch the row that had the highlight changes or goes: the highlight is put
    // back (on that group, else the group shown) instead of being lost.
    val rowFocus = remember { HashMap<Int, FocusRequester>() }
    fun rf(id: Int) = rowFocus.getOrPut(id) { FocusRequester() }
    var refocus by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(refocus) {
        val id = refocus ?: return@LaunchedEffect
        awaitFrame(); awaitFrame()
        if (id == REFOCUS_SHOWN || runCatching { rf(id).requestFocus() }.isFailure) runCatching { selectedFocus.requestFocus() }
        refocus = null
    }
    optionsFor?.let { id ->
        val g = groups.firstOrNull { it.id == id }
        val hideIt: (() -> Unit)? = when {
            onHide == null -> null
            id == GuideViewModel.FAVOURITES -> ({ onHide(FAV_HIDDEN, true) })
            id == ALL_ID -> ({ hideAllChannels(true) })
            g != null -> ({ onHide(g.name, true) })
            else -> null
        }
        OptionsPopup(
            title = when (id) { GuideViewModel.FAVOURITES -> "★  Favourites"; ALL_ID -> "All channels"; else -> g?.let { GroupNames.display(it.name) } ?: "" },
            options = listOfNotNull(
                // (★ Favourites and All channels can't be moved.)
                if (onMove != null && g != null) PopupOption("Move") { carry.pickUp(id.toString()); refocus = id } else null,
                hideIt?.let { h -> PopupOption("Hide from this list") { h(); refocus = REFOCUS_SHOWN } },
                // With the "Hidden groups" row switched off (Settings → Appearance → Menu, the default), this is the
                // way back to them: it lists them at the end until the menu closes.
                if (hiddenTotal > 0 && onHide != null && AppColors.palette.opt(UiOpts.MENU_HIDDEN) == 0 && !showHidden)
                    PopupOption("Show hidden groups ($hiddenTotal)") { showHidden = true } else null,
            ),
            onClose = { optionsFor = null },
        )
    }
    // A picked-up group moving past the top or bottom: the list follows it (the highlight stays on the same group as
    // it moves, which doesn't scroll by itself, so it went off screen).
    LaunchedEffect(shownGroups.map { it.id }, moving) {
        val id = moving ?: return@LaunchedEffect
        val idx = head + favRow + (if (hideAll) 0 else 1) + shownGroups.indexOfFirst { it.id == id }.coerceAtLeast(0)
        awaitFrame()
        val info = state.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == idx }
        if (seen == null || seen.offset < info.viewportStartOffset || seen.offset + seen.size > info.viewportEndOffset) {
            runCatching { state.scrollToItem((idx - 3).coerceAtLeast(0)) }
        }
    }
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
                    onClick = { sourcesOpen = false; sourcesVm.switchTo(s); onSelect(null); refocus = REFOCUS_SHOWN },
                )
            }
        }
        if (favouriteCount > 0 && !favHidden) item(key = "fav") {
            Column {
            GroupItem(
                name = "★  Favourites",
                count = favouriteCount,
                selected = selectedId == GuideViewModel.FAVOURITES,
                modifier = (if (selectedId == GuideViewModel.FAVOURITES || firstRow == "fav") Modifier.focusRequester(selectedFocus) else Modifier).onFocusChanged { if (it.isFocused) onHover(GuideViewModel.FAVOURITES) },
                onLongClick = if (onHide != null) ({ optionsFor = GuideViewModel.FAVOURITES }) else null,
                onClick = { onSelect(GuideViewModel.FAVOURITES) },
            )
            }
        }
        // Settings → Live TV → "All channels" group: hidden leaves just your groups.
        if (!hideAll) item(key = "all") {
            Column {
            GroupItem(
                name = "All channels" + if (AppColors.palette.opt(UiOpts.MENU_LIVE_MARK) == 1) "  🔴 LIVE" else "",
                count = allCount,
                selected = selectedId == null,
                modifier = (if (selectedId == null || firstRow == "all") Modifier.focusRequester(selectedFocus) else Modifier).onFocusChanged { if (it.isFocused) onHover(null) },
                onLongClick = if (onHide != null) ({ optionsFor = ALL_ID }) else null,
                onClick = { onSelect(null) },
            )
            }
        }
        items(shownGroups, key = { it.id }) { g ->
            val carried = moving == g.id
            Column {
            GroupItem(
                name = GroupNames.display(g.name).let { if (carried) "↕  $it" else it },
                count = g.channelCount,
                selected = g.id == selectedId || carried,
                trailing = if (carried) "Moving" else null,
                modifier = (if (g.id == selectedId || firstRow == g.id.toString()) Modifier.focusRequester(selectedFocus) else Modifier)
                    .focusRequester(rf(g.id))
                    // Picked up: ▲ ▼ move it, OK / Back / Left put it down; Right does nothing (no leaving the list with it).
                    .reorderable(carried = carried, onMove = { by -> onMove?.invoke(g.id, by) }, onDrop = { carry.drop(); refocus = g.id })
                    .onFocusChanged { if (it.isFocused && moving == null) onHover(g.id) },
                // Hold OK: Move or Hide from this list.
                onLongClick = if (onMove != null || onHide != null) ({ carry.drop(); optionsFor = g.id }) else null,
                onClick = { onSelect(g.id) },
            )
            }
        }
        // Hidden groups, folded at the end: OK on one shows it in the list again. The row is optional (abbey, 2026-11-17:
        // clutter): off, it only appears after "Show hidden groups" in a group's hold-OK menu.
        val hiddenCount = hiddenTotal
        if (hiddenCount > 0 && onHide != null && (showHidden || AppColors.palette.opt(UiOpts.MENU_HIDDEN) == 1)) {
            item(key = "hidden") {
                MenuItem(text = "Hidden groups ($hiddenCount)", trailing = if (showHidden) "▴" else "▾", selected = false, dim = true,
                    onClick = { showHidden = !showHidden }, modifier = Modifier.rightIgnored())
            }
            if (showHidden && favHidden && favouriteCount > 0) item(key = "hid-fav") {
                MenuItem(text = "      ★  Favourites", trailing = "Show", selected = false, dim = true, compact = true, onClick = { onHide(FAV_HIDDEN, false) }, modifier = Modifier.rightIgnored())
            }
            if (showHidden && hideAll) item(key = "hid-all") {
                MenuItem(text = "      All channels", trailing = "Show", selected = false, dim = true, compact = true, onClick = { hideAllChannels(false) }, modifier = Modifier.rightIgnored())
            }
            if (showHidden) items(hiddenGroups, key = { "hid-" + it.id }) { g ->
                MenuItem(text = "      " + GroupNames.display(g.name), trailing = "Show", selected = false, dim = true, compact = true,
                    onClick = { onHide(g.name, false); refocus = g.id }, modifier = Modifier.rightIgnored())
            }
        }
    }
}

/** [GroupList]'s refocus: the group being shown (or the first), not one particular group. */
private const val REFOCUS_SHOWN = Int.MIN_VALUE

@Composable
internal fun GroupItem(name: String, count: Int?, selected: Boolean, modifier: Modifier, trailing: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    MenuItem(text = (if (AppColors.palette.opt(UiOpts.MENU_GROUP_ICON) == 1) "📺  " else "") + name, selected = selected, onClick = onClick, onLongClick = onLongClick, modifier = modifier.rightDoes(onClick), trailing = trailing ?: count?.toString())
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
internal fun SettingsPanel(playbackItem: FocusRequester, onPlayback: () -> Unit, addonsItem: FocusRequester, onAddons: () -> Unit, remoteItem: FocusRequester, onRemote: () -> Unit, navigationItem: FocusRequester, onNavigation: () -> Unit, sportsItem: FocusRequester, onSports: () -> Unit, updatesItem: FocusRequester, onUpdates: () -> Unit, aboutItem: FocusRequester, onAbout: () -> Unit, session: Session?, sync: SyncState, firstItem: FocusRequester, appearanceItem: FocusRequester, onAppearance: () -> Unit, homeRowsItem: FocusRequester, onHomeRows: () -> Unit, pluginsItem: FocusRequester, onPlugins: () -> Unit, onAccounts: () -> Unit, onRefresh: () -> Unit, onSignOut: () -> Unit,
    liveItem: FocusRequester, onLive: () -> Unit, troubleItem: FocusRequester, onTrouble: () -> Unit, searchItem: FocusRequester, onSearchSettings: () -> Unit) {
    // Scrolls: the list is taller than the screen with the Dispatcharr sync items.
    Column(Modifier.verticalScroll(MenuScrolls.settings), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(session?.let { "Signed in as ${it.username}" } ?: "Live TV not set up", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        session?.let { Text(it.serverUrl, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
        val canAccounts = Access.can(Lock.ACCOUNTS)
        val canSettings = Access.can(Lock.SETTINGS)
        // Search every setting by name (the pages SettingsMap lays out, and the pages with their own screens).
        if (canSettings) MenuItem(text = "🔍  Search settings", selected = false, onClick = onSearchSettings, modifier = Modifier.hint("Find any setting by name: type a word (frame rate, wallpaper, subtitles…) and OK opens it.").focusRequester(searchItem).rightDoes(onSearchSettings))
        SettingsGroup("Accounts & people", first = !canSettings)
        if (canAccounts) MenuItem(text = "Accounts & sources", selected = false, onClick = onAccounts, modifier = Modifier.hint("Your live TV (Dispatcharr or IPTV), Plex, Jellyfin, Emby, Trakt, addons and music sign-ins.").focusRequester(firstItem).rightDoes(onAccounts))
        ProfileSettingsItems()
        if (canSettings) {
        SettingsGroup("Look & controls")
        // Accounts & sources locked: Appearance is the first row (Right / OK from the menu land here).
        MenuItem(text = "Appearance", selected = false, onClick = onAppearance, modifier = Modifier.hint("How everything looks: theme and colours, wallpaper, glass, the TV guide, the info bar, the menu, posters, title pages, subtitles and music.").focusRequester(appearanceItem).then(if (!canAccounts) Modifier.focusRequester(firstItem) else Modifier).rightDoes(onAppearance))
        MenuItem(text = "Home page & menu", selected = false, onClick = onHomeRows, modifier = Modifier.hint("Which rows show on the Movies and Shows pages, in what order, and what the side menu lists.").focusRequester(homeRowsItem).rightDoes(onHomeRows))
        MenuItem(text = "Remote buttons", selected = false, onClick = onRemote, modifier = Modifier.hint("Every button on your remote, and what it does in the guide, on live TV, in films and shows and in the menus. Presets, the search button and extra buttons.").focusRequester(remoteItem).rightDoes(onRemote))
        MenuItem(text = "Menu & navigation", selected = false, onClick = onNavigation, modifier = Modifier.hint("Where things are: what the app opens on, the side menu and what it lists, and moving around poster rows.").focusRequester(navigationItem).rightDoes(onNavigation))
        SettingsGroup("Watching")
        MenuItem(text = "Live TV", selected = false, onClick = onLive, modifier = Modifier.hint("Watching live TV: Up / Down and browsing channels, the TV guide (days ahead, refresh), channel groups and Still watching?").focusRequester(liveItem).rightDoes(onLive))
        MenuItem(text = "Playback", selected = false, onClick = onPlayback, modifier = Modifier.hint("Films, shows and players: built-in player or mpv, frame rate matching, audio, subtitles, streams, episodes and trailers.").focusRequester(playbackItem).rightDoes(onPlayback))
        MenuItem(text = "Sports", selected = false, onClick = onSports, modifier = Modifier.hint("Your teams and leagues: their games on the Sports page in the menu, and the channel showing each.").focusRequester(sportsItem).rightDoes(onSports))
        // Which watchlist Add / Remove and "Your watchlist" use (Trakt, Plex, Jellyfin or Emby favourites).
        WatchlistItem(Modifier.rightIgnored())
        }
        SettingsGroup("Live TV server")
        // Plugins: Dispatcharr admins only (Standard users have no use for them).
        if (session != null && !session.isDirect && io.github.petebutty.viewarr.ui.profiles.isAdmin()) MenuItem(text = "Dispatcharr plugins", selected = false, onClick = onPlugins, modifier = Modifier.hint("Plugins on your Dispatcharr server (admins only).").focusRequester(pluginsItem).rightDoes(onPlugins))
        MenuItem(text = "Refresh channels & guide", selected = false, onClick = onRefresh, modifier = Modifier.hint("Loads the channel list and TV guide again now.").rightIgnored())
        SettingsGroup("This TV")
        // Viewarr's setup kept on the Dispatcharr server (Dispatcharr sign-ins only).
        val canSignOut = Access.can(Lock.SIGNOUT)
        if (canSignOut && session != null && !session.isDirect) BackupSyncItem()
        // Optional: what's playing to Home Assistant, and its pop-ups on this TV.
        if (canSettings) io.github.petebutty.viewarr.ui.settings.HomeAssistantItem(Modifier.hint("Optional: tell Home Assistant what's playing (for lights and scenes), and show its pop-ups, like a doorbell camera.").rightIgnored())
        if (canSettings) MenuItem(text = "Troubleshooting", selected = false, onClick = onTrouble, modifier = Modifier.hint("Playback info on screen, and Low-memory mode for boxes that struggle.").focusRequester(troubleItem).rightDoes(onTrouble))
        // Settings most people never need, behind a warning (the page itself says so at the top of each section).
        if (canSettings) {
            val openAdvanced = { io.github.petebutty.viewarr.ui.settings.AdvancedPage.open = true; onTrouble() }
            MenuItem(text = "Advanced", selected = false, onClick = openAdvanced, modifier = Modifier.hint("For when you've been asked to change something: player internals, screen and sizes, guide data. Best left alone.").focusRequester(io.github.petebutty.viewarr.ui.settings.AdvancedPage.item).rightDoes(openAdvanced))
        }
        // Accounts and settings both locked: Updates is the first row.
        MenuItem(text = "Updates", selected = false, onClick = onUpdates, modifier = Modifier.hint("Check for a new version of Viewarr and install it.").focusRequester(updatesItem).then(if (!canAccounts && !canSettings) Modifier.focusRequester(firstItem) else Modifier).rightDoes(onUpdates))
        // Version, this TV, what Viewarr stores (with clearing), Live TV sources.
        MenuItem(text = "About", selected = false, onClick = onAbout, modifier = Modifier.hint("Version, this TV, what Viewarr stores (and clearing it), and your live TV sources.").focusRequester(aboutItem).rightDoes(onAbout))
        // (Asks "are you sure?" before anything happens: the home screen's confirmation.)
        if (canSignOut) MenuItem(
            text = "Sign out of everything",
            selected = false,
            onClick = onSignOut,
            modifier = Modifier.hint("Signs this TV out of all your accounts and sources.").rightIgnored(),
        )
    }
}

/** A small heading between groups of settings. */
@Composable
internal fun SettingsGroup(title: String, first: Boolean = false) = MenuGroupLabel(title, first)

/** A group's heading in the side menu's lists: small capitals with a rule above, so it can't be mistaken for an item. */
@Composable
internal fun MenuGroupLabel(title: String, first: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (first) 4.dp else 14.dp, bottom = 2.dp)) {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.TextSecondary.copy(alpha = 0.25f)))
        Text(
            title.uppercase(), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 1.4.sp,
            color = AppColors.Accent.copy(alpha = 0.85f), modifier = Modifier.padding(top = if (first) 0.dp else 10.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Each menu section's own colour (Settings → Appearance → Main menu → Colour edge / Colour tint). */
internal fun sectionColour(s: Section): Color = when (s) {
    Section.Tv -> Color(0xFFEF4444)
    Section.Movies -> Color(0xFF3B82F6)
    Section.Shows -> Color(0xFFA855F7)
    Section.Library -> Color(0xFF14B8A6)
    Section.Music -> Color(0xFF22C55E)
    Section.Recordings -> Color(0xFFF59E0B)
    Section.Search -> Color(0xFF94A3B8)
    // Settings: the highlight colour (its edge bar); it has no tint of its own.
    Section.Settings -> AppColors.Accent
}

/** Each section's mark (headings and the icon column). */
internal fun sectionIcon(s: Section): String = when (s) {
    Section.Tv -> "📺"; Section.Movies -> "🎬"; Section.Shows -> "📼"; Section.Library -> "📚"; Section.Music -> "♪"
    Section.Recordings -> "⏺"; Section.Search -> "🔍"; Section.Settings -> "⚙"
}

/**
 * Where the main list and the Settings list were scrolled to, kept while Viewarr is open: the menu opens already in
 * place, rather than starting at the top and scrolling itself to the highlighted entry each time.
 */
/** A soft fade at the top / bottom edge of a scrolling list, only on the side where more of it is hidden. */
internal fun Modifier.scrollEdgeFade(state: androidx.compose.foundation.ScrollState, edge: androidx.compose.ui.unit.Dp = 28.dp): Modifier =
    // (No fade any more, just the clip: the off-screen buffer it needed made the renderer give every menu entry a
    // buffer of its own, kept afterwards; ~15 MB at 4K each time the menu had been open. 2026-11-19.)
    this

/** The menu columns' width: 200 (was 230; 2026-11-19: "the menus can be shortened a bit"), its top icons smaller to fit. */
internal val MenuW = 200.dp

internal object MenuScrolls {
    val main = androidx.compose.foundation.ScrollState(0)
    val settings = androidx.compose.foundation.ScrollState(0)
}

/** The hidden-groups entry standing for ★ Favourites (not a real group name). */
// (A name no real group has. It was "\u0000favourites", but a NUL can't be written into the settings file safely.)
internal const val FAV_HIDDEN = "★ favourites (hidden)"
/** optionsFor's stand-in for All channels (real groups have positive ids; Favourites is GuideViewModel.FAVOURITES). */
/** The "All channels" entry in the groups list (its own number: -3 was Live now's too, so hiding Live now hid All
 * channels. 2026-11-17). */
private const val ALL_ID = -10

/**
 * Hold OK on a main-menu entry: Move (picks it up: edit mode for the move, so hidden entries show where it lands),
 * Hide / Show, and Edit the menu (every entry with On / Off, and a service's or Movies' / Shows' own list); Cancel
 * last (OptionsPopup adds it). [label]: the entry's name; [shown]: switched on; [editing]: edit mode already on.
 */
@Composable
private fun MainEntryOptions(label: String, shown: Boolean, editing: Boolean, onMove: () -> Unit, onHide: (Boolean) -> Unit, onEdit: () -> Unit, onClose: () -> Unit) {
    OptionsPopup(
        title = label,
        options = listOfNotNull(
            PopupOption("Move", onClick = onMove),
            PopupOption(if (shown) "Hide" else "Show") { onHide(shown) },
            if (!editing) PopupOption("Edit the menu", onClick = onEdit) else null,
        ),
        onClose = onClose,
    )
}

/** When the menu's highlight last moved Up / Down (its pages load only once it has stopped). */
internal object MenuNav {
    @Volatile var lastMove = 0L
}

/** MenuOverlay's focus requesters and plain flags (one remembered holder, not one slot each: the method-size limit). */
private class MenuHolder {
    val showAppearance = mutableStateOf(false)
    val showLive = mutableStateOf(false)
    val showTrouble = mutableStateOf(false)
    val showSearchSettings = mutableStateOf(false)
    val mappedStart = mutableStateOf<Pair<String, String>?>(null)
    val showHomeRows = mutableStateOf(false)
    val homeRowsMine = mutableStateOf(false)
    val showPlugins = mutableStateOf(false)
    val showRemote = mutableStateOf(false)
    val showNavigation = mutableStateOf(false)
    val showSports = mutableStateOf(false)
    val sportsJumped = mutableStateOf(false)
    val showPlayback = mutableStateOf(false)
    val showAddons = mutableStateOf(false)
    val showUpdates = mutableStateOf(false)
    val showAbout = mutableStateOf(false)
    val returnTo = mutableStateOf<FocusRequester?>(null)
    val hoverJob = mutableStateOf<kotlinx.coroutines.Job?>(null)
    val serviceOn = mutableStateOf<String?>(null)
    val mainListTop = mutableStateOf(0.dp)
    val foldedHeaderFocused = mutableStateOf(false)
    val settingsFromGear = mutableStateOf(false)
    val forceMain = mutableStateOf(false)
    val hadProfilePage = mutableStateOf(false)
    val editingMain = mutableStateOf(false)
    val moveOnly = mutableStateOf(false)
    val entryMenu = mutableStateOf<String?>(null)
    val mainFollow = mutableStateOf<String?>(null)
    val subFromMain = mutableStateOf(false)
    val subListFocused = mutableStateOf(false)
    val menuCats = mutableStateOf<MediaLibrary?>(null)
    val menuEditing = mutableStateOf(false)
    val rowMenu = mutableStateOf<String?>(null)
    val previewJob = mutableStateOf<kotlinx.coroutines.Job?>(null)
    val liveItem = FocusRequester()
    val troubleItem = FocusRequester()
    val searchSettingsItem = FocusRequester()
    val pluginsItem = FocusRequester()
    val homeRowsItem = FocusRequester()
    val navigationItem = FocusRequester()
    val sportsItem = FocusRequester()
    val playbackItem = FocusRequester()
    val addonsItem = FocusRequester()
    val remoteItem = FocusRequester()
    val appearanceItem = FocusRequester()
    val updatesItem = FocusRequester()
    val aboutItem = FocusRequester()
    val editMenusFocus = FocusRequester()
    val gearFocus = FocusRequester()
    val settingsFirst = FocusRequester()
    val groupsFocus = FocusRequester()
    val recentFocus = FocusRequester()
}

/** The guide as the menu opened (its group and highlight), put back when the menu is cancelled with Back. */
private class GuideReturn(private val vm: GuideViewModel) {
    private val group = vm.groupId.value
    private val cursor = vm.cursor.value
    private val channel = vm.channels.value.getOrNull(vm.cursor.value.row)?.id
    fun restore() { if (vm.groupId.value != group) vm.restoreView(group, cursor, channel) }
}

/** Scrolls a list just enough to keep the highlighted row [margin] px clear of both edges (out of the faded part). */
internal fun clearOfEdges(margin: Float) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val m = margin.coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
        val end = offset + size
        return when {
            offset < m -> offset - m
            end > containerSize - m -> end - (containerSize - m)
            else -> 0f
        }
    }
}
