package io.github.petebutty.viewarr.ui.home

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * The menu's Movies / Shows panel: the page's home, its catalogues (the rows on the page: OK jumps to one) and your
 * Plex / Jellyfin / Emby libraries (OK opens one as a grid, a page at a time, with sorting).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MediaMenuPanel(
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
internal fun MenuListEditor(media: MediaViewModel, type: MediaType, startAt: String?, onDone: () -> Unit) {
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
internal fun LibraryCategories(
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
internal fun MusicMenuPanel(focusRequester: FocusRequester, onOpenPage: () -> Unit) {
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
internal fun RecordingsPanel(focusRequester: FocusRequester, channelName: (Int) -> String? = { null }, onCount: (Int) -> Unit, onOpenPage: () -> Unit, onPlay: (VodStream) -> Unit) {
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
internal fun RecordingRow(title: String, details: String, live: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
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
internal fun SearchMenuPanel(onSearch: (String?) -> Unit, onVoice: () -> Unit, focus: FocusRequester, musicOn: Boolean) {
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
