package io.github.petebutty.viewarr.ui.home

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.dvr.DvrViewModel
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.petebutty.viewarr.data.dvr.DvrRecording
import io.github.petebutty.viewarr.data.dvr.DvrState
import io.github.petebutty.viewarr.data.media.LibKind
import io.github.petebutty.viewarr.data.media.MediaLibrary
import io.github.petebutty.viewarr.playback.MusicPlayer
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.common.PendingOrder
import io.github.petebutty.viewarr.ui.common.RecentSearches
import io.github.petebutty.viewarr.ui.common.Carry
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.moveOption
import io.github.petebutty.viewarr.ui.common.movingHint
import io.github.petebutty.viewarr.ui.common.rememberCarry
import io.github.petebutty.viewarr.ui.common.reorderable
import io.github.petebutty.viewarr.ui.theme.NoScale
import io.github.petebutty.viewarr.ui.theme.Tokens
import io.github.petebutty.viewarr.ui.music.MusicBrowseKind
import io.github.petebutty.viewarr.ui.music.MusicViewModel
import io.github.petebutty.viewarr.ui.theme.Dates

/**
 * The menu's Movies / Shows panel: the page's home, its catalogues (the rows on the page: OK jumps to one) and your
 * Plex / Jellyfin / Emby libraries (OK opens one as a grid, a page at a time, with sorting).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MediaMenuPanel(
    type: MediaType, focusRequester: FocusRequester, onOpenPage: () -> Unit,
    catsFor: MediaLibrary? = null,
    onCats: (MediaLibrary?) -> Unit = {},
    editing: Boolean = false,
    onEditing: (Boolean) -> Unit = {},
    /** The menu's edit mode (✎ Edit menus): "Edit this list" and hold OK to edit are offered only then. */
    editMode: Boolean = false,
    /** Makes sure the page behind is Movies / Shows (not a service's) before a preview, without closing the menu. */
    onPreviewPage: () -> Unit = {},
    /** An entry of the list being edited, picked up (MenuOverlay's, so its own Left / Back leave it alone meanwhile). */
    carry: Carry = rememberCarry(),
) {
    // Same instance as the page's, so what's picked here shows there straight away.
    val media: MediaViewModel = hiltViewModel(key = "media-${type.name}")
    LaunchedEffect(type) { media.load(type); media.loadLibraries(type) }
    // As you move through the list the page behind follows (home, a library, a row, a category), after a short pause
    // so scrolling past doesn't load each one (an IPTV library asks the server for every category).
    val previewScope = androidx.compose.runtime.rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun preview(show: () -> Unit) {
        previewJob?.cancel()
        previewJob = previewScope.launch { kotlinx.coroutines.delay(450); onPreviewPage(); show() }
    }
    // The menu's own list (Settings → Home page & menu → Menu list), separate from which rows the page shows.
    val rowsState by media.jumpRows.collectAsStateWithLifecycle()
    val libraries by media.libraries.collectAsStateWithLifecycle()
    val browse by media.browse.collectAsStateWithLifecycle()
    val current by media.currentRow.collectAsStateWithLifecycle()
    val rows = rowsState.rows
    val libs = libraries.orEmpty()
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
        MenuListEditor(media, type, startAt = lastLib, onDone = { onEditing(false) }, carry = carry)
        return
    }
    // (The highlight goes onto "home" itself, not the list as a whole: see RecordingsPanel.)
    // Back from a library's categories: the list opens already at that library (it used to start at the top and
    // scroll itself down to it).
    val startAt = lastLib?.let { k -> libs.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }?.let { (it + 2 - 3).coerceAtLeast(0) } ?: 0
    LazyColumn(
        state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = startAt),
        verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "home") {
            val go = { media.openLibrary(null, type); media.jumpTo(null); onOpenPage() }
            MenuItem(text = home, selected = browse == null && current == null, onClick = go,
                modifier = Modifier.focusRequester(focusRequester).onFocusChanged { if (it.isFocused) preview { media.openLibrary(null, type); media.jumpTo(null) } }.rightDoes(go))
        }
        // Your libraries are in My Library now; here only while editing the menu (their order and on / off are
        // what My Library shows).
        if (libs.isNotEmpty() && editMode) item(key = "h-lib") {
            MenuGroupLabel("Libraries")
        }
        items(if (editMode) libs else emptyList(), key = { "l-" + it.key }) { lib ->
            val go = { media.openLibrary(lib, type); onOpenPage() }
            // Right: the library's categories / genres / collections in this column (Settings → Remote → Menu).
            // Right: straight in, as everywhere (Left on it lists all your libraries instead).
            val right = go
            MenuItem(
                text = lib.title,
                trailing = lib.source.removePrefix("Plex · ").takeIf { libs.map { l -> l.source }.distinct().size > 1 },
                selected = browse?.library?.key == lib.key,
                onClick = go,
                // Hold OK: edit this list, on this library.
                onLongClick = if (editMode) ({ lastLib = lib.key; onEditing(true) }) else null,
                modifier = Modifier.focusRequester(libFocus.getOrPut(lib.key) { FocusRequester() })
                    .onFocusChanged { if (it.isFocused) preview { media.openLibrary(lib, type) } }.rightDoes(right),
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
            MenuItem(text = "   " + row.title, trailing = row.items.size.toString(), selected = browse == null && row.id == current, onClick = go,
                modifier = Modifier.onFocusChanged { if (it.isFocused) preview { media.openLibrary(null, type); media.jumpTo(row.id) } }.rightDoes(go))
        }
        if (editMode) item(key = "edit") {
            val go = { lastLib = null; onEditing(true) }
            MenuItem(text = "✎ Edit this list", selected = false, dim = true, onClick = go, modifier = Modifier.padding(top = 8.dp).rightDoes(go))
        }
    }
}

/**
 * The menu's Movies / Shows list, edited in place (the same settings as Settings → Home page & menu → Menu list):
 * every library with On / Off (OK switches), then the rows under "Jump to a row", the same way (moving one moves it on
 * the page too, as the menu follows the page). Hold OK on one: Move / Hide or Show (the shared options pop-up); Move
 * picks it up ([carry]), ▲ ▼ move it, OK, Back or Left put it down (Left left it carried, Right picked up: UI review,
 * Tier 4, 2026-11-17). Done, Back or Left (MenuOverlay) finish.
 */
@Composable
internal fun MenuListEditor(media: MediaViewModel, type: MediaType, startAt: String?, onDone: () -> Unit, carry: Carry) {
    val all = media.allLibraries.collectAsStateWithLifecycle().value.orEmpty()
    // On first (in their order), then off: as the menu shows them.
    val libs = all.filter { !it.second } + all.filter { it.second }
    // The page's rows: "Jump to a row" lists exactly these, so one on / off and one order for both.
    val rows = media.editableRows.collectAsStateWithLifecycle().value.first
    val focus = remember { HashMap<String, FocusRequester>() }
    val doneFocus = remember { FocusRequester() }
    // Opened on a library (hold OK): focus there, else on Done. After a switch or a move: focus follows that library.
    // ("done": Done. Only when asked: it used to run on every change to the lists too, so a move saved a moment later
    // threw the highlight onto Done with the library still picked up, and every press after that was swallowed.)
    var follow by remember { mutableStateOf<String?>(startAt ?: "done") }
    // The library ("key") or row ("row:id") whose hold-OK options are open.
    var menuFor by remember { mutableStateOf<String?>(null) }
    val editorList = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(follow, libs.map { it.first.key to it.second }, rows.map { it.first.id }) {
        val key = follow ?: return@LaunchedEffect
        awaitFrame(); awaitFrame()
        // Moved past the top or bottom: scroll it back into view first (it went off screen, or was dropped from the
        // list and the highlight jumped to Done).
        if (key != "done") {
            val keys = buildList {
                add("done"); add("hint")
                if (libs.isNotEmpty()) add("h-lib"); libs.forEach { add("l-" + it.first.key) }
                if (rows.isNotEmpty()) add("h-rows"); rows.forEach { add("r-" + it.first.id) }
            }
            val idx = keys.indexOf("l-$key").takeIf { it >= 0 } ?: keys.indexOf("r-" + key.removePrefix("row:"))
            val info = editorList.layoutInfo
            if (idx >= 0 && info.visibleItemsInfo.none { it.index == idx && it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }) {
                runCatching { editorList.scrollToItem((idx - 2).coerceAtLeast(0)) }
                awaitFrame()
            }
        }
        if (key == "done" || runCatching { focus[key]?.requestFocus() }.isFailure) runCatching { doneFocus.requestFocus() }
        follow = null
    }
    val on = libs.filter { !it.second }.map { it.first.key }
    val off = libs.filter { it.second }.map { it.first.key }
    val rowIds = rows.map { it.first.id }.toSet()
    fun toggleLib(key: String, hidden: Boolean) {
        // On → the end of the "on" part; off → the top of the "off" part (as in Settings).
        media.setLibraryOrder(if (hidden) on + key + (off - key) else (on - key) + key + off)
        media.setLibraryHidden(key, !hidden)
        follow = key
    }
    menuFor?.let { k ->
        val lib = libs.firstOrNull { it.first.key == k }
        val row = rows.firstOrNull { "row:" + it.first.id == k }
        val hidden = lib?.second ?: row?.second ?: false
        OptionsPopup(
            title = lib?.first?.title ?: row?.first?.title ?: "",
            options = listOfNotNull(
                // (A library that's off can't be moved: it sits with the others that are off.)
                if (lib == null || !hidden) carry.moveOption(k) else null,
                PopupOption(if (hidden) "Show" else "Hide") {
                    if (lib != null) toggleLib(k, hidden) else row?.let { media.setRowHidden(it.first.id, !hidden); follow = k }
                },
            ),
            onClose = { menuFor = null },
        )
    }
    // Opened by holding OK on a library: that held OK, let go, mustn't switch the library off.
    LazyColumn(Modifier.ignoreHeldOk(), state = editorList, verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "done") {
            MenuItem(text = "✓ Done", selected = false, onClick = onDone, modifier = Modifier.focusRequester(doneFocus).rightDoes(onDone))
        }
        item(key = "hint") {
            Text(if (carry.active) movingHint() else "OK: on / off  ·  Hold OK: options", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        if (libs.isNotEmpty()) item(key = "h-lib") { MenuGroupLabel("Libraries") }
        itemsIndexed(libs, key = { _, it -> "l-" + it.first.key }) { _, (lib, hidden) ->
            val carried = carry.isCarried(lib.key)
            MenuItem(
                text = (if (carried) "↕ " else "") + lib.title,
                trailing = if (carried) "Moving" else if (hidden) "Off" else "On",
                dim = hidden,
                selected = carried,
                onClick = { toggleLib(lib.key, hidden) },
                onLongClick = { menuFor = lib.key },
                modifier = Modifier.focusRequester(focus.getOrPut(lib.key) { FocusRequester() }).reorderable(
                    carried = carried,
                    // By its key in the latest order (a held key comes faster than the list redraws).
                    onMove = { by -> PendingOrder.move("libs:$type", on + off, lib.key, by, on.size)?.let { media.setLibraryOrder(it); follow = lib.key } },
                    onDrop = carry::drop,
                ),
            )
        }
        if (rows.isNotEmpty()) item(key = "h-rows") { MenuGroupLabel("Rows (the page and \"Jump to a row\")") }
        items(rows, key = { "r-" + it.first.id }) { (row, hidden) ->
            val id = "row:" + row.id
            val carried = carry.isCarried(id)
            MenuItem(
                text = (if (carried) "↕ " else "") + row.title, trailing = if (carried) "Moving" else if (hidden) "Off" else media.rowOrigin(row),
                dim = hidden && !carried, selected = carried,
                onClick = { media.setRowHidden(row.id, !hidden); follow = id },
                onLongClick = { menuFor = id },
                modifier = Modifier.focusRequester(focus.getOrPut(id) { FocusRequester() }).reorderable(
                    carried = carried,
                    // Same order as the page, so the page's rows move with it.
                    onMove = { by -> media.moveRowAmong(row.id, by) { r -> r.id in rowIds }; follow = id },
                    onDrop = carry::drop,
                ),
            )
        }
    }
}

/**
 * The menu's My Library: every library on all your servers (films and shows together), under each server's name,
 * then your music. Moving onto one shows it behind (after a short pause); OK or Right goes into it. The libraries'
 * order and which are shown follow Settings → Home page & menu (and Edit menus).
 */
@Composable
internal fun LibraryMenuPanel(
    focusRequester: FocusRequester,
    libraries: List<Pair<MediaType, MediaLibrary>>,
    musicSources: List<String>,
    onHover: (MediaType, MediaLibrary) -> Unit,
    onOpen: (MediaType, MediaLibrary) -> Unit,
    onMusic: () -> Unit,
    /** The library open on the page (Left from it lands here, on it); null: "My Library home". */
    current: String? = null,
    /** "My Library home": moving onto it shows My Library's page ([into] false); OK / Right goes into its rows. */
    onHome: (into: Boolean) -> Unit = {},
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var hoverJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val target = current?.takeIf { k -> libraries.any { it.second.key == k } }
    fun hover(t: MediaType, lib: MediaLibrary) {
        hoverJob?.cancel(); hoverJob = scope.launch { kotlinx.coroutines.delay(450); onHover(t, lib) }
    }
    val byServer = libraries.groupBy { it.second.source }
    // The highlight on "Loading your libraries…" when they arrive: onto the first one (the row it was on goes, and the
    // highlight went with it, so the next press only brought it back).
    var onLoading by remember { mutableStateOf(false) }
    val loaded = libraries.isNotEmpty() || musicSources.isNotEmpty()
    // (Read as the list changes, before the row is removed and its focus with it.)
    val refocus = remember(loaded) { loaded && onLoading }
    androidx.compose.runtime.LaunchedEffect(refocus) {
        if (refocus) { androidx.compose.runtime.withFrameNanos { }; runCatching { focusRequester.requestFocus() } }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (!loaded) item(key = "none") {
            MenuItem(text = "Loading your libraries…", selected = false, dim = true, onClick = {}, modifier = Modifier.focusRequester(focusRequester).onFocusChanged { onLoading = it.isFocused })
        }
        // My Library's own page first: your servers' rows, films and shows together (Settings → Home page & menu → My Library).
        if (loaded) item(key = "home") {
            val go = { hoverJob?.cancel(); onHome(true) }
            MenuItem(
                text = "My Library home", selected = false, onClick = go,
                modifier = (if (target == null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { if (it.isFocused) { hoverJob?.cancel(); hoverJob = scope.launch { kotlinx.coroutines.delay(450); onHome(false) } } }.rightDoes(go),
            )
        }
        var firstDone = false
        byServer.entries.forEachIndexed { gi, (server, ls) ->
            item(key = "s-$server") { MenuGroupLabel(server.removePrefix("Plex · ").ifBlank { "Library" }, first = gi == 0) }
            val sorted = ls.sortedBy { if (it.first == MediaType.Movie) 0 else 1 }
            items(sorted.size, key = { "a-" + sorted[it].first.name + sorted[it].second.key }) { i ->
                val (t, lib) = sorted[i]
                val go = { hoverJob?.cancel(); onOpen(t, lib) }
                MenuItem(
                    // (Playlists hold films and shows together.)
                    text = lib.title, trailing = if (lib.kind == LibKind.Playlists) null else if (t == MediaType.Movie) "Films" else "Shows", selected = false, onClick = go,
                    modifier = (if (lib.key == target) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) hover(t, lib) }.rightDoes(go),
                )
            }
            firstDone = true
        }
        if (musicSources.isNotEmpty()) {
            item(key = "s-music") { MenuGroupLabel("Music", first = !firstDone) }
            itemsIndexed(musicSources, key = { i, n -> "m-$i-$n" }) { _, name ->
                MenuItem(text = name, trailing = "Music", selected = false, onClick = onMusic, modifier = Modifier.rightDoes(onMusic))
            }
        }
    }
}

/**
 * The menu's Music panel, like Movies and Shows: the Music home, the whole library by kind, quick play, and each music
 * source with an on/off switch (only Navidrome, only Plex…).
 */
@Composable
internal fun MusicMenuPanel(focusRequester: FocusRequester, onOpenPage: () -> Unit) {
    val music: MusicViewModel = hiltViewModel()
    val connected by music.connected.collectAsStateWithLifecycle()
    val sources by music.sources.collectAsStateWithLifecycle()
    val browse by music.browse.collectAsStateWithLifecycle()
    val detail by music.detail.collectAsStateWithLifecycle()
    // Entering the list lands on the page you're on (Favourites, Artists…), else Music home.
    val onKind = browse?.kind?.takeIf { detail == null }
    // The highlight on "Connect music" while it's still checking: onto Music home once it's connected (the row goes).
    var onSetup by remember { mutableStateOf(false) }
    val refocus = remember(connected) { connected == true && onSetup }
    androidx.compose.runtime.LaunchedEffect(refocus) {
        if (refocus) { androidx.compose.runtime.withFrameNanos { }; runCatching { focusRequester.requestFocus() } }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (connected != true) {
            item(key = "setup") {
                MenuItem(text = "Connect music", selected = false, onClick = onOpenPage, modifier = Modifier.focusRequester(focusRequester).onFocusChanged { onSetup = it.isFocused }.rightDoes(onOpenPage))
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
        items(MusicBrowseKind.entries, key = { "b-" + it.name }) { k ->
            val go = { music.openBrowse(k); onOpenPage() }
            MenuItem(text = k.label, selected = browse?.kind == k && detail == null, onClick = go, modifier = (if (onKind == k) Modifier.focusRequester(focusRequester) else Modifier).rightDoes(go))
        }
        item(key = "h-play") { MenuGroupLabel("Play") }
        item(key = "shuffle") {
            val go = { music.shuffleLibrary(); onOpenPage() }
            MenuItem(text = "Shuffle my library", selected = false, onClick = go, modifier = Modifier.rightDoes(go))
        }
        if (MusicPlayer.active) item(key = "now") {
            val go = { MusicPlayer.nowPlayingOpen = true; onOpenPage() }
            MenuItem(text = "Now playing", trailing = MusicPlayer.current?.title, selected = false, onClick = go, modifier = Modifier.rightDoes(go))
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
internal fun RecordingsPanel(focusRequester: FocusRequester, channelName: (Int) -> String? = { null }, onOpenPage: () -> Unit, onPlay: (VodStream) -> Unit) {
    val dvr: DvrViewModel = hiltViewModel()
    val all by dvr.recordings.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val recorded = all.filter { (it.state == DvrState.Recorded || it.state == DvrState.Recording) && it.hasFile }
        .sortedByDescending { it.start }.take(8)
    val upcoming = all.filter { it.state == DvrState.Scheduled }.sortedBy { it.start }.take(4)
    var picked by remember { mutableStateOf<DvrRecording?>(null) }
    // Each row's focus, and the row whose options were opened: closing them puts the highlight back on it (it jumped
    // to the first recording: UI review, Tier 1, 2026-11-17).
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    var heldRow by remember { mutableStateOf<String?>(null) }
    val day = Dates.day
    val time = java.time.format.DateTimeFormatter.ofPattern("EEE ", java.util.Locale.UK).let { w -> java.time.format.DateTimeFormatterBuilder().append(w).append(Dates.time).toFormatter(java.util.Locale.UK) }
    fun at(ms: Long, f: java.time.format.DateTimeFormatter) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(f)
    if (recorded.isEmpty() && upcoming.isEmpty()) {
        // (Something to land on: Left on the Recordings entry had nowhere to go and did nothing.)
        Column {
            MenuItem(text = "All recordings", selected = false, onClick = onOpenPage, modifier = Modifier.focusRequester(focusRequester).rightDoes(onOpenPage))
            Text("Nothing recorded or scheduled yet. Record from the TV guide or the info bar.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
        }
        return
    }
    // Two lines each: the whole title (no date squeezing it), then when, which channel and how long.
    fun details(r: DvrRecording, withTime: Boolean): String {
        val mins = ((r.end - r.start) / 60_000).toInt().takeIf { it > 0 }
        val length = mins?.let { if (it >= 60) "${it / 60}h ${it % 60}m".removeSuffix(" 0m") else "${it}m" }
        val ep = if (r.season != null && r.episode != null) "S${r.season} · E${r.episode}" else null
        return listOfNotNull(
            if (withTime) Dates.dayTime(r.start) else at(r.start, day),
            channelName(r.channelId), ep, length,
        ).joinToString("  ·  ")
    }
    // "Live" leads most sports titles in the guide; in a list of recordings it says nothing.
    fun shown(t: String) = t.removePrefix("Live ").removePrefix("LIVE ").removePrefix("Live: ").removePrefix("LIVE: ")
    // (The highlight goes onto the first entry itself: asked of the list as a whole, it didn't pass it on while the
    // list was folded away, and Right from the menu did nothing.)
    val firstId = recorded.firstOrNull()?.let { "r${it.id}" } ?: upcoming.firstOrNull()?.let { "u${it.id}" } ?: "all"
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (recorded.isNotEmpty()) item(key = "h-rec") { MenuGroupLabel("Recorded", first = true) }
        items(recorded, key = { "r${it.id}" }) { r ->
            val now = r.state == DvrState.Recording
            RecordingRow(
                title = shown(r.title),
                details = if (now) "● Recording now" + (channelName(r.channelId)?.let { "  ·  $it" } ?: "") else details(r, withTime = false),
                live = now,
                onClick = { scope.launch { dvr.playback(r)?.let(onPlay) } },
                // Hold OK: play, stop or delete.
                onLongClick = { dvr.clearMessage(); heldRow = "r${r.id}"; picked = r },
                modifier = (if (firstId == "r${r.id}") Modifier.focusRequester(focusRequester) else Modifier).focusRequester(rowFocus.getOrPut("r${r.id}") { FocusRequester() }),
            )
        }
        if (upcoming.isNotEmpty()) item(key = "h-up") { MenuGroupLabel("Coming up", first = recorded.isEmpty()) }
        items(upcoming, key = { "u${it.id}" }) { r ->
            RecordingRow(shown(r.title), details(r, withTime = true), live = false, onClick = onOpenPage, onLongClick = { dvr.clearMessage(); heldRow = "u${r.id}"; picked = r },
                modifier = (if (firstId == "u${r.id}") Modifier.focusRequester(focusRequester) else Modifier).focusRequester(rowFocus.getOrPut("u${r.id}") { FocusRequester() }))
        }
        item(key = "all") {
            Spacer(Modifier.height(6.dp))
            MenuItem(text = "All recordings", trailing = "›", selected = false, onClick = onOpenPage, modifier = if (firstId == "all") Modifier.focusRequester(focusRequester) else Modifier)
        }
    }
    picked?.let { r ->
        val message by dvr.message.collectAsStateWithLifecycle()
        val close = {
            picked = null
            // Back on the row held (gone, e.g. deleted: the first entry).
            val back = heldRow?.let { rowFocus[it] }
            scope.launch { awaitFrame(); if (back == null || runCatching { back.requestFocus() }.isFailure) runCatching { focusRequester.requestFocus() } }
            Unit
        }
        // Done (or failed): the message shows a moment, then the options close.
        LaunchedEffect(message) { if (message != null && message != "Working…") { delay(1_400); close() } }
        // The shared options pop-up (it was a ChoicePanel, itself a pop-up, wrapped in a second Dialog; "Close" is now
        // the standard Cancel, last). Delete asks first, "Keep it" highlighted, so a stray OK never deletes anything.
        RecordingOptions(r, at(r.start, time), message, onPlay = { picked = null; scope.launch { dvr.playback(r)?.let(onPlay) } }, dvr = dvr, onClose = close)
    }
}

/** Hold OK on a recording in the menu: Play / Delete (recorded), Stop (recording), Cancel (scheduled); then Cancel. */
@Composable
private fun RecordingOptions(r: DvrRecording, subtitle: String, message: String?, onPlay: () -> Unit, dvr: DvrViewModel, onClose: () -> Unit) {
    // (They stay open after a choice: the server's answer shows under them, then they close.)
    val options = buildList<PopupOption> {
        when (r.state) {
            DvrState.Recorded -> {
                if (r.hasFile) add(PopupOption("Play", keepOpen = true, onClick = onPlay))
                add(PopupOption(
                    "Delete recording…", destructive = true, keepOpen = true,
                    confirmTitle = "Delete \"${r.title}\"?",
                    confirm = "This deletes the recording from your Dispatcharr server. It can't be undone.",
                ) { dvr.cancel(r) })
            }
            DvrState.Recording -> add(PopupOption("Stop recording", keepOpen = true) { dvr.stop(r) })
            DvrState.Scheduled -> add(PopupOption("Cancel recording", keepOpen = true) { dvr.cancel(r) })
            else -> add(PopupOption("Remove from list", keepOpen = true) { dvr.cancel(r) })
        }
    }
    OptionsPopup(title = r.title, subtitle = subtitle, options = options, message = message, onClose = onClose)
}


/** A recording in the menu: its title, and underneath when / channel / length (red dot while recording). */
@Composable
internal fun RecordingRow(title: String, details: String, live: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier,
                          /** How far it's been watched (a thin bar under it), or null. */ progress: Float? = null,
                          /** A small poster on its left (a recording's show or film, from TMDB), or null. */ art: String? = null) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        scale = NoScale,
        shape = ClickableSurfaceDefaults.shape(Tokens.RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
            pressedContainerColor = AppColors.Accent, pressedContentColor = AppColors.OnAccent,
        ),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (art != null) coil.compose.AsyncImage(art, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp).size(width = 32.dp, height = 48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                details, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = when { focused -> AppColors.OnAccent.copy(alpha = 0.8f); live -> Color(0xFFE53935); else -> AppColors.TextSecondary },
                modifier = Modifier.padding(top = 1.dp),
            )
            progress?.takeIf { it > 0.01f && it < io.github.petebutty.viewarr.data.dvr.RecordingProgress.WATCHED_AT }?.let { f ->
                androidx.compose.foundation.layout.Box(Modifier.padding(top = 4.dp).then(Modifier.size(width = 160.dp, height = 3.dp)).background((if (focused) AppColors.OnAccent else AppColors.TextSecondary).copy(alpha = 0.3f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(f).then(Modifier.size(height = 3.dp, width = 160.dp * f)).background(if (focused) AppColors.OnAccent else AppColors.Accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                }
            }
        }
        }
    }
}


/** The menu's Search panel: start a search (typed or by voice), or repeat a recent one. */
@Composable
internal fun SearchMenuPanel(onSearch: (String?) -> Unit, onVoice: () -> Unit, focus: FocusRequester, musicOn: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { RecentSearches.load(ctx) }
    val recent = RecentSearches.items
    // (The highlight's target is the first item, not the column: a request on a group failed while the list was still
    // folded away, so Right on Search opened the search page instead of this list.)
    Column(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MenuItem(text = "Search…", selected = false, onClick = { onSearch(null) }, modifier = Modifier.focusRequester(focus),
            leading = { androidx.compose.material3.Icon(AppIcons.Search, null, modifier = Modifier.size(18.dp), tint = androidx.tv.material3.LocalContentColor.current) })
        MenuItem(text = "Voice search", selected = false, onClick = onVoice,
            leading = { androidx.compose.material3.Icon(AppIcons.Mic, null, modifier = Modifier.size(18.dp), tint = androidx.tv.material3.LocalContentColor.current) })
        Text(
            if (musicOn) "Films, shows, music, your libraries, addons and TV channels." else "Films, shows, your libraries, addons and TV channels.",
            fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (recent.isNotEmpty()) {
            MenuGroupLabel("Recent searches")
            recent.toList().forEach { q -> MenuItem(text = q, selected = false, onClick = { onSearch(q) }) }
            // (Its row goes with the list: the highlight back on Search… rather than lost.)
            MenuItem(text = "Clear recent searches", selected = false, dim = true, onClick = { RecentSearches.clear(ctx); runCatching { focus.requestFocus() } })
        }
    }
}
