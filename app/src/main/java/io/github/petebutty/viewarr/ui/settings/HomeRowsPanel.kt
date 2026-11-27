package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.theme.appBackground

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import io.github.petebutty.viewarr.ui.common.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import io.github.petebutty.viewarr.ui.common.PendingOrder
import io.github.petebutty.viewarr.ui.setup.WizardField
import io.github.petebutty.viewarr.ui.common.Carry
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.PopupPanel
import io.github.petebutty.viewarr.ui.common.PopupTitle
import io.github.petebutty.viewarr.ui.common.moveOption
import io.github.petebutty.viewarr.ui.common.movingHint
import io.github.petebutty.viewarr.ui.common.rememberCarry
import io.github.petebutty.viewarr.ui.common.reorderable
import io.github.petebutty.viewarr.ui.theme.NoScale
import io.github.petebutty.viewarr.ui.theme.Tokens

/**
 * Settings → Home page & menu: one list each for Movies and Shows: the libraries the menu lists (on / off, order, names), then the rows on the
 * page, which are also what "Jump to a row" lists (on / off and order).
 * OK turns an entry on or off; hold OK: options (Move, Rename… for a library, Hide / Show). Move (or Right, the
 * Settings shortcut) picks it up so Up / Down move it, and OK, Back, Left or Right puts it down.
 */
@Composable
fun HomeRowsSettingsScreen(onBack: () -> Unit, startMine: Boolean = false) {
    var type by remember { mutableStateOf(MediaType.Movie) }
    // The third list: My Library's page (your servers' rows from both). (2026-11-17.) [startMine]: opened on it.
    var mine by remember { mutableStateOf(startMine) }
    val tabFocus = remember { List(3) { FocusRequester() } }
    // The entry being moved (shared ui/common/Reorder.kt; was hold OK = rename, Right only to move. 2026-11-17).
    val carry = rememberCarry()
    // (Back while carrying puts it down: Modifier.reorderable takes that press, so this never leaves the page then.)
    BackHandler { if (carry.active) carry.drop() else onBack() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { tabFocus[if (startMine) 2 else 0].requestFocus() } }
    Box(Modifier.fillMaxSize().appBackground(readable = true).trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = Tokens.Gutter, vertical = 40.dp)) {
            // (Below the live TV window when one is up, as SettingsPage.)
            // (Scrolls: the Continue watching from list can be long with several servers.)
            Column(Modifier.width(300.dp).fillMaxHeight().padding(top = if (SettingsLayout.belowPreview) 170.dp else 0.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Settings", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Home page & menu", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 20.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOf(MediaType.Movie to "Movies", MediaType.Show to "Shows").forEachIndexed { i, (t, label) ->
                    MenuItem(
                        text = label,
                        selected = t == type && !mine,
                        onClick = { type = t; mine = false },
                        modifier = Modifier.focusRequester(tabFocus[i]).onFocusChanged { if (it.isFocused) { type = t; mine = false; carry.drop() } }.leftGoesBack(onBack),
                    )
                }
                MenuItem(
                    text = "My Library",
                    selected = mine,
                    onClick = { mine = true },
                    modifier = Modifier.focusRequester(tabFocus[2]).onFocusChanged { if (it.isFocused) { mine = true; carry.drop() } }.leftGoesBack(onBack),
                )
                // Both pages: rows that repeat across servers shown once.
                val mediaVm: MediaViewModel = hiltViewModel(key = "media-${MediaType.Movie.name}")
                val merge by mediaVm.mergeRows.collectAsStateWithLifecycle()
                Spacer(Modifier.height(18.dp))
                OnOffRow("Combine rows from different servers", merge, carry, onBack) { mediaVm.setMergeRows(!merge) }
                Text(
                    "One Continue watching, and one Recently added per library, instead of a row for each server.",
                    fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp),
                )
                // "<service> · Just added" (the Viewarr plugin's, from JustWatch) in place of each "<service> · New".
                // (Just added switched off in the Viewarr plugin: there are no such rows, so no option.)
                if (io.github.petebutty.viewarr.data.sync.ServerSports.available(io.github.petebutty.viewarr.data.sync.ServerSports.F_JUST)) {
                val showsVmJ: MediaViewModel = hiltViewModel(key = "media-${MediaType.Show.name}")
                Spacer(Modifier.height(10.dp))
                io.github.petebutty.viewarr.ui.common.MenuItem(text = "Use Just added instead of New", selected = false,
                    onClick = { mediaVm.useJustAddedForNewWhenReady(MediaType.Movie); showsVmJ.useJustAddedForNewWhenReady(MediaType.Show) },
                    modifier = Modifier.leftGoesBack(onBack))
                Text(
                    "Each service's Just added row (what really arrived on it lately, new seasons too) takes the place of its New row, on Movies and Shows. The New rows are hidden, not removed.",
                    fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp),
                )
                }
                // Network rows (an addon's "Netflix · Popular"…) on Movies / Shows too, or only on each network's own
                // page in the menu. (Its switch was in the old live TV shortcut lists, 2026-11-18.)
                val optsNet: UiOptsViewModel = hiltViewModel()
                val netOn = AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.SET_ROWS_HOME) == 1
                // Movies / Shows: the 8 main rows only (Discover), or every row (2026-11-23).
                val mainOnly = AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.CURATED_HOME) == 0
                Spacer(Modifier.height(18.dp))
                OnOffRow("Main rows only on Movies / Shows", mainOnly, carry, onBack) { optsNet.set(io.github.petebutty.viewarr.ui.theme.UiOpts.CURATED_HOME, if (mainOnly) 1 else 0) }
                Text(
                    "Movies and Shows keep their 8 main rows: Continue watching, Recently added (every library as one), your watchlist, Recommended and the like. Trending and popular rows are on the Trending page, Just added on What's new and each service's page, every library in My Library. Off: every row, as before.",
                    fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(18.dp))
                OnOffRow("Network rows on Movies / Shows", netOn, carry, onBack) { optsNet.set(io.github.petebutty.viewarr.ui.theme.UiOpts.SET_ROWS_HOME, if (netOn) 0 else 1) }
                Text(
                    "Netflix, Prime Video and the other networks' rows from your addons, on the Movies and Shows pages too. Off: only on each network's own page in the menu.",
                    fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp),
                )
                // Continue watching, server by server (Movies, Shows, My Library and the info bar's row).
                val showsVm: MediaViewModel = hiltViewModel(key = "media-${MediaType.Show.name}")
                val appearance: AppearanceViewModel = hiltViewModel()
                val cf = io.github.petebutty.viewarr.ui.media.ContinueFrom
                val fromServers by mediaVm.continueSources.collectAsStateWithLifecycle()
                val fromShows by showsVm.continueSources.collectAsStateWithLifecycle()
                val sources = (fromServers + fromShows + cf.IPTV + cf.TRAKT).distinct()
                Spacer(Modifier.height(18.dp))
                Text("Continue watching from", fontSize = 14.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                sources.forEach { src ->
                    val on = cf.on(src)
                    OnOffRow(src, on, carry, onBack, compact = true) { appearance.setTitlePart(cf.key(src), !on) }
                }
                Text(
                    "Off: that server's (or IPTV's, or Trakt's) films and episodes you're part way through leave Continue watching everywhere.",
                    fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.width(40.dp))
            // Same view model as the Films / Shows page, so the rows are shared and changes show there at once.
            // Left from the rows: back to the page selected on the left (not the nearest entry).
            Box(Modifier.weight(1f).fillMaxHeight().leftReturnsTo { tabFocus[if (mine) 2 else if (type == MediaType.Show) 1 else 0] }) {
                if (mine) MyLibraryEditor(carry)
                else androidx.compose.runtime.key(type) {
                    RowsEditor(type, carry)
                }
            }
        }
    }
}

/** An On / Off row in the left column: moving onto it puts down a carried row; Left goes back to the Settings list. */
@Composable
private fun OnOffRow(text: String, on: Boolean, carry: Carry, onBack: () -> Unit, compact: Boolean = false, onClick: () -> Unit) {
    MenuItem(
        text = text, trailing = if (on) "On" else "Off", selected = false, compact = compact,
        onClick = onClick,
        modifier = Modifier.onFocusChanged { if (it.isFocused) carry.drop() }.leftGoesBack(onBack),
    )
}

@Composable
private fun RowsEditor(type: MediaType, carry: Carry) {
    val moving = carry.id
    val media: MediaViewModel = hiltViewModel(key = "media-${type.name}")
    LaunchedEffect(type) { media.load(type); media.loadLibraries(type) }
    // Turned-on entries are listed first (in their order), then the ones turned off: switching one on moves it to the
    // end of the "on" part, switching it off to the top of the "off" part.
    val libs = media.allLibraries.collectAsStateWithLifecycle().value.orEmpty().let { l -> l.filter { !it.second } + l.filter { it.second } }
    val state by media.editableRows.collectAsStateWithLifecycle()
    val (pageRows, done) = state
    // One list of rows: what's on the page is what "Jump to a row" lists, in the same order.
    val rows = pageRows.let { r -> r.filter { !it.second } + r.filter { it.second } }
    val rowsOn = rows.count { !it.second }
    val libsOn = libs.count { !it.second }
    // After a switch: focus follows the switched entry to its new place.
    var refocus by remember { mutableStateOf<String?>(null) }
    val what = if (type == MediaType.Movie) "Movies" else "Shows"
    val focus = remember { HashMap<String, FocusRequester>() }
    val listState = rememberLazyListState()
    // Where an entry sits in the list: the libraries' heading + libraries, then the rows' heading + rows.
    val libItems = if (libs.isNotEmpty()) 1 + libs.size else 0
    fun itemIndexOf(id: String): Int =
        if (id.startsWith("lib:")) libs.indexOfFirst { "lib:" + it.first.key == id }.let { if (it < 0) -1 else 1 + it }
        else rows.indexOfFirst { it.first.id == id }.let { if (it < 0) -1 else libItems + 1 + it }
    // After a move, keep focus on the entry being carried.
    LaunchedEffect(moving, rows.map { it.first.id }, libs.map { it.first.key }) {
        val id = moving ?: return@LaunchedEffect
        val idx = itemIndexOf(id)
        if (idx >= 0) {
            listState.animateScrollToItem(maxOf(0, idx - 3))
            awaitFrame()
            runCatching { focus[id]?.requestFocus() }
        }
    }
    LaunchedEffect(refocus, rows.map { it.first.id to it.second }, libs.map { it.first.key to it.second }) {
        val id = refocus ?: return@LaunchedEffect
        val idx = itemIndexOf(id)
        if (idx < 0) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) listState.scrollToItem(maxOf(0, idx - 3))
        awaitFrame()
        runCatching { focus[id]?.requestFocus() }
        refocus = null
    }
    fun toggleRow(i: Int) {
        val (row, hidden) = rows[i]
        val on = rows.filter { !it.second }.map { it.first.id }
        val off = rows.filter { it.second }.map { it.first.id }
        media.setRowOrder(if (hidden) on + row.id + (off - row.id) else (on - row.id) + row.id + off)
        media.setRowHidden(row.id, !hidden)
        refocus = row.id
    }
    fun toggleLib(i: Int) {
        val (lib, hidden) = libs[i]
        val on = libs.filter { !it.second }.map { it.first.key }
        val off = libs.filter { it.second }.map { it.first.key }
        media.setLibraryOrder(if (hidden) on + lib.key + (off - lib.key) else (on - lib.key) + lib.key + off)
        media.setLibraryHidden(lib.key, !hidden)
        refocus = "lib:" + lib.key
    }
    // Hold OK on an entry: its options (by id).
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxHeight()) {
        Text(
            if (moving != null) movingHint()
            else "Your $what libraries in the menu, then the rows on the $what page (and under \"Jump to a row\"), top to bottom. " +
                "OK: on / off  ·  Hold OK: move, rename or hide.",
            fontSize = 14.sp, color = if (moving != null) AppColors.Accent else AppColors.TextSecondary,
            modifier = Modifier.padding(top = 26.dp, bottom = 14.dp, start = 4.dp),
        )
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            // The menu's library list (Movies / Shows → Right): on / off, order and names.
            if (libs.isNotEmpty()) item(key = "libs-head") { SectionHead("Libraries", first = true) }
            itemsIndexed(libs, key = { _, it -> "lib:" + it.first.key }) { i, (lib, hidden) ->
                val id = "lib:" + lib.key
                val fr = focus.getOrPut(id) { FocusRequester() }
                val carried = id == moving
                fun move(by: Int) {
                    // By its key in the latest order (a held key comes faster than the list redraws).
                    val keys = PendingOrder.move("libs:$type", libs.map { it.first.key }, lib.key, by, libsOn) ?: return
                    media.setLibraryOrder(keys)
                }
                OrderedToggle(
                    marker = if (carried) "↕" else "▤",
                    title = lib.title, subtitle = lib.source + "  ·  " + if (hidden) "Not in the menu" else "In the menu",
                    on = !hidden, carried = carried, focusRequester = fr,
                    onPickUp = { if (!hidden) carry.pickUp(id) }, onPutDown = carry::drop, onMove = ::move,
                    onClick = { if (moving != null) carry.drop() else toggleLib(i) },
                    onLongClick = { if (moving == null) menuFor = id },
                )
            }
            item(key = "rows-head") { SectionHead("Rows", first = libs.isEmpty()) }
            itemsIndexed(rows, key = { _, it -> it.first.id }) { i, (row, hidden) ->
                val fr = focus.getOrPut(row.id) { FocusRequester() }
                val carried = row.id == moving
                fun move(by: Int) {
                    val ids = PendingOrder.move("rows:$type", rows.map { it.first.id }, row.id, by, rowsOn) ?: return
                    media.setRowOrder(ids)
                }
                OrderedToggle(
                    marker = if (carried) "↕" else if (hidden) "" else "${i + 1}",
                    title = row.title,
                    // Where the row comes from (Plex, Jellyfin, an add-on…) instead of where it shows.
                    subtitle = media.rowOrigin(row) + "  ·  " + if (hidden) "Off" else "${row.items.size} titles",
                    on = !hidden, carried = carried, focusRequester = fr,
                    onPickUp = { if (!hidden) carry.pickUp(row.id) }, onPutDown = carry::drop, onMove = ::move,
                    onClick = { if (moving != null) carry.drop() else toggleRow(i) },
                    onLongClick = { if (moving == null) menuFor = row.id },
                )
            }
            if (!done) item(key = "loading") {
                Text("Loading rows…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
            }
            if (done) item(key = "reset") {
                var confirm by remember { mutableStateOf(false) }
                MenuItem(
                    text = if (confirm) "Press OK again to reset" else "Reset to the original order",
                    selected = false,
                    modifier = Modifier.padding(top = 10.dp).onFocusChanged { if (!it.isFocused) confirm = false },
                    onClick = { if (confirm) { media.resetRows(); confirm = false } else confirm = true },
                )
            }
        }
    }
    menuFor?.let { id -> EntryOptions(id, libs, rows, carry, onRename = { renaming = it }, onToggle = { lib, i -> if (lib) toggleLib(i) else toggleRow(i) }, onClose = { menuFor = null }) }
    renaming?.let { key -> libs.firstOrNull { it.first.key == key }?.first }?.let { lib ->
        RenameLibraryDialog(lib.title, onDone = { name -> media.setLibraryName(lib.key, name); renaming = null; refocus = "lib:" + lib.key }, onCancel = { renaming = null })
    }
}

/**
 * Settings → Home page & menu → My Library: the rows on My Library's page (the menu's My Library), your own servers'
 * rows from Movies and Shows in one list. OK: on / off; hold OK: move or hide (as the other two lists).
 */
@Composable
private fun MyLibraryEditor(carry: Carry) {
    val moving = carry.id
    val movies: MediaViewModel = hiltViewModel(key = "media-${MediaType.Movie.name}")
    val shows: MediaViewModel = hiltViewModel(key = "media-${MediaType.Show.name}")
    LaunchedEffect(Unit) { movies.load(MediaType.Movie); shows.load(MediaType.Show) }
    val state by io.github.petebutty.viewarr.ui.media.MyLibrary.state.collectAsStateWithLifecycle()
    val rows = state.entries.let { e -> e.filter { !it.off } + e.filter { it.off } }
    val rowsOn = rows.count { !it.off }
    var refocus by remember { mutableStateOf<String?>(null) }
    val focus = remember { HashMap<String, FocusRequester>() }
    val listState = rememberLazyListState()
    LaunchedEffect(moving, rows.map { it.key }) {
        val k = moving ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it.key == k }
        if (idx >= 0) { listState.animateScrollToItem(maxOf(0, idx - 3)); awaitFrame(); runCatching { focus[k]?.requestFocus() } }
    }
    LaunchedEffect(refocus, rows.map { it.key to it.off }) {
        val k = refocus ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it.key == k }
        if (idx < 0) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) listState.scrollToItem(maxOf(0, idx - 3))
        awaitFrame(); runCatching { focus[k]?.requestFocus() }
        refocus = null
    }
    fun toggle(i: Int) {
        val e = rows[i]
        val on = rows.filter { !it.off }.map { it.key }
        val off = rows.filter { it.off }.map { it.key }
        movies.setMyLibraryOrder(if (e.off) on + e.key + (off - e.key) else (on - e.key) + e.key + off)
        movies.setMyLibraryHidden(e.key, !e.off)
        refocus = e.key
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxHeight()) {
        Text(
            if (moving != null) movingHint()
            else "The rows on the My Library page (the menu's My Library): your own servers' films and shows, top to bottom. " +
                "OK: on / off  ·  Hold OK: move or hide.",
            fontSize = 14.sp, color = if (moving != null) AppColors.Accent else AppColors.TextSecondary,
            modifier = Modifier.padding(top = 26.dp, bottom = 14.dp, start = 4.dp),
        )
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(rows, key = { _, it -> it.key }) { i, e ->
                val fr = focus.getOrPut(e.key) { FocusRequester() }
                val carried = e.key == moving
                fun move(by: Int) {
                    val keys = PendingOrder.move("rows:mine", rows.map { it.key }, e.key, by, rowsOn) ?: return
                    movies.setMyLibraryOrder(keys)
                }
                OrderedToggle(
                    marker = if (carried) "↕" else if (e.off) "" else "${i + 1}",
                    title = e.row.title,
                    subtitle = (if (e.type == MediaType.Show) "Shows" else "Films") + "  ·  " + movies.rowOrigin(e.row) + "  ·  " +
                        if (e.off) "Off" else "${e.row.items.size} titles",
                    on = !e.off, carried = carried, focusRequester = fr,
                    onPickUp = { if (!e.off) carry.pickUp(e.key) }, onPutDown = carry::drop, onMove = ::move,
                    onClick = { if (moving != null) carry.drop() else toggle(i) },
                    onLongClick = { if (moving == null) menuFor = e.key },
                )
            }
            if (rows.isEmpty()) item(key = "loading") {
                Text("Loading your servers' rows…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
            }
            if (rows.isNotEmpty()) item(key = "reset") {
                var confirm by remember { mutableStateOf(false) }
                MenuItem(
                    text = if (confirm) "Press OK again to reset" else "Reset to the original order",
                    selected = false,
                    modifier = Modifier.padding(top = 10.dp).onFocusChanged { if (!it.isFocused) confirm = false },
                    onClick = { if (confirm) { movies.resetMyLibrary(); confirm = false } else confirm = true },
                )
            }
        }
    }
    menuFor?.let { k ->
        val i = rows.indexOfFirst { it.key == k }
        if (i < 0) { LaunchedEffect(k) { menuFor = null }; return@let }
        val e = rows[i]
        OptionsPopup(
            title = e.row.title,
            options = buildList {
                if (!e.off) add(carry.moveOption(k))
                add(PopupOption(if (e.off) "Show" else "Hide") { toggle(i) })
            },
            onClose = { menuFor = null },
        )
    }
}

/**
 * Hold OK on a library or row: Move (only while it's on: the ones off sit below, unordered), Rename… (libraries),
 * then Hide / Show, and Cancel (the standard options pop-up).
 */
@Composable
private fun EntryOptions(
    id: String,
    libs: List<Pair<io.github.petebutty.viewarr.data.media.MediaLibrary, Boolean>>,
    rows: List<Pair<io.github.petebutty.viewarr.data.media.MediaRow, Boolean>>,
    carry: Carry,
    onRename: (String) -> Unit,
    onToggle: (lib: Boolean, index: Int) -> Unit,
    onClose: () -> Unit,
) {
    val isLib = id.startsWith("lib:")
    val i = if (isLib) libs.indexOfFirst { "lib:" + it.first.key == id } else rows.indexOfFirst { it.first.id == id }
    if (i < 0) { LaunchedEffect(id) { onClose() }; return }
    val title = if (isLib) libs[i].first.title else rows[i].first.title
    val hidden = if (isLib) libs[i].second else rows[i].second
    val options = buildList {
        if (!hidden) add(carry.moveOption(id))
        if (isLib) add(PopupOption("Rename…") { onRename(id.removePrefix("lib:")) })
        add(PopupOption(if (hidden) (if (isLib) "Show in the menu" else "Show") else (if (isLib) "Hide from the menu" else "Hide")) { onToggle(isLib, i) })
    }
    OptionsPopup(title = title, options = options, onClose = onClose)
}

@Composable
private fun SectionHead(text: String, first: Boolean) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.Accent, modifier = Modifier.padding(start = 4.dp, top = if (first) 0.dp else 18.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Hold OK on a library: give it your own name (empty puts the server's name back). */
@Composable
private fun RenameLibraryDialog(current: String, onDone: (String?) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    // (The shared pop-up: was a raw Dialog. 2026-11-17.)
    PopupPanel(onClose = onCancel, width = Tokens.PopupWide) {
        PopupTitle("Rename library", "Leave it empty to use the server's own name.")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WizardField(
                name, { name = it }, "Name", first,
                androidx.compose.ui.text.input.KeyboardType.Text, androidx.compose.ui.text.input.ImeAction.Done,
                onIme = { onDone(name.ifBlank { null }) },
            )
            MenuItem(text = "Save", selected = false, onClick = { onDone(name.ifBlank { null }) })
            MenuItem(text = "Cancel", selected = false, onClick = onCancel)
        }
    }
}

/**
 * A row or library in these lists, looking like a settings row (SettingsPage's SettingRow: row shape, no growing, its
 * switch): its place (or ↕ while carried), name and what it is, and an on / off switch. OK turns it on / off; hold OK:
 * options; moving is the shared Modifier.reorderable (Right picks up as the Settings shortcut).
 */
@Composable
private fun OrderedToggle(
    marker: String, title: String, subtitle: String, on: Boolean, carried: Boolean,
    focusRequester: FocusRequester, onPickUp: () -> Unit, onPutDown: () -> Unit, onMove: (Int) -> Unit,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            .reorderable(carried = carried, onMove = onMove, onDrop = onPutDown, rightToggles = true, onPickUp = onPickUp),
        scale = NoScale,
        shape = ClickableSurfaceDefaults.shape(Tokens.RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel,
            contentColor = if (on) AppColors.TextPrimary else AppColors.TextSecondary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(marker, fontSize = 14.sp, modifier = Modifier.width(36.dp), maxLines = 1, softWrap = false)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SettingSwitch(on)
        }
    }
}
