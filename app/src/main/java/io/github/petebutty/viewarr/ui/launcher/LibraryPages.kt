package io.github.petebutty.viewarr.ui.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.FilterGroup
import io.github.petebutty.viewarr.data.media.LibKind
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaLibrary
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.common.AppPill
import io.github.petebutty.viewarr.ui.common.FocusCard
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.home.Page
import io.github.petebutty.viewarr.ui.library.AlphabetRail
import io.github.petebutty.viewarr.ui.library.SidePanel
import io.github.petebutty.viewarr.ui.library.SortPanel
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.media.PlaceholderPoster
import io.github.petebutty.viewarr.ui.media.PosterCard
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.media.firstLetter
import io.github.petebutty.viewarr.ui.media.metaLine
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.CornerPicture
import io.github.petebutty.viewarr.ui.theme.appBackground
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/**
 * Previewarr's Library pages (2026-11-26), over the home screen as its Settings is: the home screen's Library tile
 * opens every library on your servers as a wide tile (LibraryTiles); a tile opens that library as a wall of posters,
 * every title, with what it can be sorted and filtered by across the top and the A–Z strip down the right. They run
 * on the library browsing Viewarr's pages have (MediaViewModel.browse); a title opens Viewarr's own title page, and
 * closing that comes back to the wall where you were.
 */
internal object LauncherLibrary {
    /** The Library pages are up (the list of libraries, or [current]'s wall). */
    var open by mutableStateOf(false)
        private set

    /** The library whose wall is showing (null: the list of them). */
    var current by mutableStateOf<Pair<MediaLibrary, MediaType>?>(null)
        private set

    /** The poster last highlighted on the wall, to come back to after a title's page. */
    var index = 0
    /** The tile last highlighted in the list of libraries. */
    var lastTile: String? = null

    /** Each library's tile picture and how many titles it has, looked up once while the app runs. */
    val facts = HashMap<String, Pair<String?, Int>>()

    /**
     * A server that answers a library with its own folders ("Titles", "Genres", "Years", "Actors"…) rather than its
     * films: [tops] are those folders, [base] the one holding every title (what the wall shows); the others are the
     * buttons across the top.
     */
    class Folders(val tops: List<MediaItem>, val base: MediaLibrary?)
    var folders by mutableStateOf<Folders?>(null)

    /** A folder opened on the wall: from which of [Folders.tops] ([top], its card's key), and its name when it's one inside that. */
    class Step(val top: String?, val lib: MediaLibrary, val label: String?)
    /** The folders gone into, in turn (empty: every title). Back comes out of them one at a time. */
    val trail = androidx.compose.runtime.mutableStateListOf<Step>()

    /** The folder the wall should be showing just now (null: the library itself). */
    val showing: MediaLibrary? get() = trail.lastOrNull()?.lib ?: folders?.base

    fun show() { current = null; open = true }
    fun close() { open = false; current = null }
    /** Another page opened over the home screen: put away, the library kept in case it's asked back ([keep]). */
    fun hide() { open = false }
    fun enter(lib: MediaLibrary, type: MediaType) { index = 0; folders = null; trail.clear(); current = lib to type }
    fun leave() { current = null }
    /** A title opened from the wall: the pages stay as they are behind it. */
    fun keep() { open = true }
}

/** The Library pages: the list of libraries, or the one opened. */
@Composable
internal fun LauncherLibraryPages() {
    val movies: MediaViewModel = hiltViewModel(key = "media-Movie")
    val shows: MediaViewModel = hiltViewModel(key = "media-Show")
    val playback: io.github.petebutty.viewarr.playback.PlaybackViewModel = hiltViewModel()
    val liveCh by playback.channel.collectAsStateWithLifecycle()
    val liveUp = liveCh != null && !VodHost.active
    // Something playing in the corner (top left): the pages' headings start to its right.
    val cornerUp = liveUp || (VodHost.active && VodHost.mini)
    Box(Modifier.fillMaxSize().appBackground(titleArt = false)) {
        val cur = LauncherLibrary.current
        if (cur == null) LibraryList(movies, shows, cornerUp)
        else LibraryWall(if (cur.second == MediaType.Show) shows else movies, cur.first, cur.second, cornerUp, movies, shows)
        if (liveUp) LiveCorner(playback, Modifier.offset { IntOffset(CornerPicture.inset.first.roundToPx(), CornerPicture.inset.second.roundToPx()) })
    }
}

/** Room for the corner picture when something's playing there: the heading sits beside it, the page under it. */
@Composable
private fun PageHead(cornerUp: Boolean, content: @Composable () -> Unit) {
    val (w, h) = CornerPicture.size()
    Box(Modifier.fillMaxWidth()
        .padding(start = if (cornerUp) CornerPicture.inset.first + w + 4.dp else 0.dp, top = 26.dp)
        .heightIn(min = if (cornerUp) CornerPicture.inset.second + h - 18.dp else 0.dp)) { content() }
}

// ---- Every library, as tiles ----

@Composable
private fun LibraryList(movies: MediaViewModel, shows: MediaViewModel, cornerUp: Boolean) {
    LaunchedEffect(Unit) { movies.loadLibraries(MediaType.Movie); shows.loadLibraries(MediaType.Show) }
    // (Every library, whatever Viewarr's menu list has switched off: this page has its own Hide, below.)
    val films by movies.allLibraries.collectAsStateWithLifecycle()
    val series by shows.allLibraries.collectAsStateWithLifecycle()
    // (Your servers' libraries; the lists of collections and playlists the menu also has aren't libraries.)
    val every = remember(films, series) {
        films.orEmpty().map { it.first }.filter { it.kind == LibKind.Library }.map { it to MediaType.Movie } +
            series.orEmpty().map { it.first }.filter { it.kind == LibKind.Library }.map { it to MediaType.Show }
    }
    // Hold OK on a tile: Hide. The hidden ones sit at the foot of the page, where OK brings one back.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("lib_hidden", emptySet()).orEmpty().toSet()) }
    fun setHidden(key: String, on: Boolean) {
        hidden = if (on) hidden + key else hidden - key
        prefs.edit().putStringSet("lib_hidden", hidden).apply()
    }
    var menuFor by remember { mutableStateOf<Pair<MediaLibrary, MediaType>?>(null) }
    val all = every.filter { tileKey(it) !in hidden }
    val away = every.filter { tileKey(it) in hidden }
    val loading = films == null || series == null
    BackHandler(enabled = menuFor == null) { LauncherLibrary.close() }
    val requesters = remember { HashMap<String, FocusRequester>() }
    val listState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(all.size, away.size) {
        // (Every library hidden: the first of the hidden ones, so there's always something highlighted.)
        val want = LauncherLibrary.lastTile?.takeIf { k -> all.any { tileKey(it) == k } } ?: (all.firstOrNull() ?: away.firstOrNull())?.let { tileKey(it) } ?: return@LaunchedEffect
        // (The tile scrolled into view first: one further down the page isn't drawn, and can't take the highlight.)
        val at = all.indexOfFirst { tileKey(it) == want }.let { if (it >= 0) it else all.size + 1 }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == at }) runCatching { listState.scrollToItem(at) }
        awaitFrame(); awaitFrame()
        runCatching { requesters[want]?.requestFocus() }
    }
    Column(Modifier.fillMaxSize()) {
        PageHead(cornerUp) {
            Column(Modifier.padding(start = 48.dp, end = 48.dp)) {
                Text("Library", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Text(
                    when {
                        all.isNotEmpty() -> "Pick a library to go through everything in it. Hold OK on one to hide it."
                        away.isNotEmpty() -> "Every library is hidden. Pick one below to bring it back."
                        loading -> "Loading…"
                        else -> "No libraries found. Add Plex, Jellyfin or Emby in Settings and they'll show here."
                    },
                    fontSize = 13.sp, color = AppColors.TextSecondary,
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 18.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(all, key = { tileKey(it) }) { e ->
                val key = tileKey(e)
                LibraryTile(e, if (e.second == MediaType.Show) shows else movies,
                    Modifier.focusRequester(requesters.getOrPut(key) { FocusRequester() }).onFocusChanged { if (it.isFocused) LauncherLibrary.lastTile = key },
                    onLong = { menuFor = e }) { vm -> vm.openLibrary(e.first, e.second); LauncherLibrary.enter(e.first, e.second) }
            }
            if (away.isNotEmpty()) {
                item(key = "hidden-head", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text("Hidden libraries  ·  OK brings one back", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 14.dp))
                }
                items(away, key = { "h-" + tileKey(it) }) { e ->
                    LibraryTile(e, if (e.second == MediaType.Show) shows else movies, Modifier.focusRequester(requesters.getOrPut(tileKey(e)) { FocusRequester() }).graphicsLayer { alpha = 0.55f },
                        onLong = {}) { LauncherLibrary.lastTile = tileKey(e); setHidden(tileKey(e), false) }
                }
            }
        }
    }
    menuFor?.let { e ->
        SidePanel(e.first.title, onClose = { menuFor = null }) { first ->
            item(key = "hide") {
                MenuItem(text = "Hide this library", selected = false, modifier = Modifier.focusRequester(first), onClick = {
                    setHidden(tileKey(e), true); menuFor = null
                    // (The highlight to a tile that's still there.)
                    LauncherLibrary.lastTile = all.firstOrNull { it != e }?.let { tileKey(it) }
                    LauncherLibrary.lastTile?.let { k -> runCatching { requesters[k]?.requestFocus() } }
                })
            }
            item(key = "cancel") { MenuItem(text = "Cancel", selected = false, onClick = { menuFor = null }) }
        }
    }
}

/** One library's tile: its made-up face (LibraryTiles), with a picture and count looked up once. */
@Composable
private fun LibraryTile(e: Pair<MediaLibrary, MediaType>, vm: MediaViewModel, modifier: Modifier, onLong: () -> Unit, onClick: (MediaViewModel) -> Unit) {
    val (lib, type) = e
    val key = tileKey(e)
    val facts by produceState(LauncherLibrary.facts[key], key) {
        if (value == null) value = vm.libraryFacts(lib, type)?.also { LauncherLibrary.facts[key] = it }
    }
    FocusCard(
        onClick = { onClick(vm) }, onLongClick = onLong, modifier = Modifier.fillMaxWidth().then(modifier),
        shape = RoundedCornerShape(12.dp), aspect = 16f / 9f, containerColor = AppColors.Panel, focusedScale = 1.04f,
    ) { LibraryTiles.Face(lib.title, lib.source, type, facts?.first, facts?.second) }
}

private fun tileKey(e: Pair<MediaLibrary, MediaType>) = (if (e.second == MediaType.Show) "S:" else "M:") + e.first.key

// ---- One library: a wall of posters ----

private const val COLUMNS = 8

/** The highlighted row comes to rest [inset] px from the wall's top, in the same frame (the glide is drawn apart). */
@OptIn(ExperimentalFoundationApi::class)
private fun rowSpec(inset: Float) = object : BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.snap()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val d = offset - inset
        return if (kotlin.math.abs(d) < 1f) 0f else d
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LibraryWall(vm: MediaViewModel, lib: MediaLibrary, type: MediaType, cornerUp: Boolean, movies: MediaViewModel, shows: MediaViewModel) {
    val browse by vm.browse.collectAsStateWithLifecycle()
    // What the wall shows: the library, or (a server of folders, LauncherLibrary.Folders) the folder gone into.
    val want = LauncherLibrary.showing ?: lib
    // (Something else took the page's library meanwhile, e.g. the Movies tile: opened again.)
    LaunchedEffect(browse?.library?.key, want.key) { if (browse?.library?.key != want.key) vm.openLibrary(want, type) }
    var panel by remember { mutableStateOf<String?>(null) }
    // Back: out of a folder gone into (to every title again), then out of the library.
    BackHandler(enabled = panel == null) {
        LauncherLibrary.index = 0
        if (LauncherLibrary.trail.isNotEmpty()) LauncherLibrary.trail.removeAt(LauncherLibrary.trail.lastIndex) else LauncherLibrary.leave()
    }
    val got = browse?.takeIf { it.library.key == want.key }
    // The library answered with its folders, not its titles: they become the buttons across the top, and the wall
    // shows the folder that holds every title ("Titles"), if there's one.
    val first = got?.pages?.get(0)
    val folderRoot = got != null && got.library.key == lib.key && LauncherLibrary.folders == null && got.library.server == null &&
        !first.isNullOrEmpty() && first.all { isFolder(it) }
    LaunchedEffect(folderRoot) {
        if (!folderRoot) return@LaunchedEffect
        val tops = got?.items.orEmpty()
        val base = (tops.firstOrNull { it.title.equals("Titles", ignoreCase = true) } ?: tops.firstOrNull { EVERYTHING.matches(it.title.trim()) })?.let { vm.folderFor(it) }
        LauncherLibrary.index = 0
        LauncherLibrary.folders = LauncherLibrary.Folders(tops, base)
    }
    // (Not drawn for the moment in between: the folders showed as blank posters first.)
    val b = got?.takeIf { !folderRoot } ?: return

    val count = b.total.coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val gridState = remember(b.version) { LazyGridState() }
    val cells = remember(b.version) { HashMap<Int, FocusRequester>() }
    fun cell(i: Int) = cells.getOrPut(i) { FocusRequester() }
    val barFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var barOn by remember { mutableStateOf(false) }
    var railOn by remember { mutableStateOf(false) }
    var railLetter by remember { mutableStateOf('A') }
    // (The strip gone, e.g. into a folder, while the highlight was on it: its big letter with it.)
    LaunchedEffect(b.canLetters) { if (!b.canLetters) railOn = false }
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    val folders = LauncherLibrary.folders
    // (A folder's own cards are folders too, on some servers: counted as folders, opened in place.)
    val ofFolders = b.items.isNotEmpty() && b.items.all { isFolder(it) }

    // Whatever's on screen (and a little either side) is loaded; stretches far away are let go.
    LaunchedEffect(b.version) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.let { v -> (v.firstOrNull()?.index ?: 0) to (v.lastOrNull()?.index ?: 0) } }
            .collect { (f, l) -> vm.ensureRange(f, l) }
    }
    suspend fun focusAt(i: Int) {
        if (count == 0) return
        val idx = i.coerceIn(0, count - 1)
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) runCatching { gridState.scrollToItem(idx - idx % COLUMNS) }
        awaitFrame(); awaitFrame()
        runCatching { cell(idx).requestFocus() }
    }
    // A new list (opened, sorted, filtered) or back from a title: the highlight on the poster you were on. Not while
    // you're on the buttons across the top or the A–Z strip: picking a filter there leaves you there.
    LaunchedEffect(b.version, count > 0) {
        if (count == 0 || barOn || railOn || panel != null) return@LaunchedEffect
        focusAt(LauncherLibrary.index)
    }
    // Nothing to land on (an empty library, filters that match nothing): the buttons across the top.
    LaunchedEffect(b.version, b.total) { if (b.total == 0 && !barOn) { awaitFrame(); runCatching { barFocus.requestFocus() } } }
    // A–Z: the wall moves to the letter; picked with OK on the strip, the highlight goes there too.
    LaunchedEffect(b.jump) {
        val j = b.jump ?: return@LaunchedEffect
        LauncherLibrary.index = j
        if (railOn) runCatching { gridState.scrollToItem(j - j % COLUMNS) } else focusAt(j)
        vm.letterJumpDone()
    }
    val current by rememberUpdatedState(b)
    val shownLetter by remember(b.version) {
        derivedStateOf { current.item(gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0)?.title?.let { firstLetter(it) } }
    }
    // (A change of sort or filter starts the wall from its first title.)
    fun changed(act: () -> Unit) { LauncherLibrary.index = 0; act() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHead(cornerUp) {
                Column {
                    Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.Bottom) {
                        Text(lib.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp))
                        Text(
                            when {
                                b.failed -> "The server didn't answer. Press Back and try again."
                                b.total < 0 -> b.library.source + "  ·  Loading…"
                                else -> listOfNotNull(b.library.source, LauncherLibrary.trail.lastOrNull()?.let { it.label ?: it.lib.title },
                                    "%,d".format(b.total) + if (b.filtered) " of them match" else if (ofFolders) " folders" else if (type == MediaType.Show) " shows" else " films").joinToString("  ·  ")
                            },
                            fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    if (folders != null) FolderBar(b, folders, barFocus, onBar = { barOn = it }, onPanel = { panel = it })
                    else WallBar(b, vm, barFocus, onBar = { barOn = it }, onPanel = { panel = it }, changed = ::changed)
                    Text(
                        when {
                            b.total == 0 && b.filtered -> "Nothing in this library matches these filters."
                            b.total == 0 -> "This library is empty."
                            else -> focused?.let { "${it.title}   ${metaLine(it)}" } ?: " "
                        },
                        fontSize = 14.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 6.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                // Down / Up move the wall a whole row from the key press, in the same instant as the highlight; the
                // posters are then drawn where they were and eased into place (one layer moved, nothing laid out again
                // per frame), as Viewarr's own library grid.
                val rowTop = with(density) { 4.dp.toPx() }
                val spec = remember(rowTop) { rowSpec(rowTop) }
                val glide = remember { mutableFloatStateOf(0f) }
                val glideJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
                val slide: (Int, Int) -> Unit = { from, to ->
                    val info = gridState.layoutInfo.visibleItemsInfo
                    val a = info.firstOrNull { it.index == from }
                    val z = info.firstOrNull { it.index == to }
                    if (a != null && z != null) {
                        val used = gridState.dispatchRawDelta((z.offset.y - a.offset.y).toFloat())
                        if (used != 0f) {
                            val step = kotlin.math.abs(used)
                            glide.floatValue = (glide.floatValue + used).coerceIn(-step, step)
                            glideJob[0]?.cancel()
                            glideJob[0] = scope.launch {
                                androidx.compose.animation.core.animate(glide.floatValue, 0f,
                                    animationSpec = androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { v, _ -> glide.floatValue = v }
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(COLUMNS),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds().graphicsLayer { translationY = glide.floatValue }
                            // Into the wall (Down from the buttons, Left from the strip): a poster that's drawn.
                            .focusProperties {
                                enter = {
                                    val shown = gridState.layoutInfo.visibleItemsInfo.map { it.index }
                                    val want = LauncherLibrary.index
                                    if (shown.isEmpty()) barFocus else cell(if (want in shown) want else shown.first())
                                }
                            }
                            .focusGroup(),
                        contentPadding = PaddingValues(start = 48.dp, end = if (b.canLetters) 12.dp else 48.dp, top = 4.dp, bottom = 60.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(count, key = { it }) { i ->
                            val item = b.item(i)
                            val col = i % COLUMNS
                            val m = Modifier.focusRequester(cell(i))
                                .onFocusChanged { if (it.isFocused) { LauncherLibrary.index = i; focused = item } }
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when {
                                        // Right at the end of a row: onto the A–Z strip.
                                        e.key == Key.DirectionRight && (col == COLUMNS - 1 || i == count - 1) -> { if (b.canLetters) runCatching { railFocus.requestFocus() }; true }
                                        // (Left at the start of a row stays put: there's no menu beside the wall.)
                                        e.key == Key.DirectionLeft && col == 0 -> true
                                        e.key == Key.DirectionDown && i + COLUMNS < count -> { slide(i, i + COLUMNS); false }
                                        // (The last row is short: Down from above its gap goes to its last poster.)
                                        e.key == Key.DirectionDown && i / COLUMNS < (count - 1) / COLUMNS -> { scope.launch { focusAt(count - 1) }; true }
                                        e.key == Key.DirectionUp && i - COLUMNS >= 0 -> { slide(i, i - COLUMNS); false }
                                        // (The first button isn't drawn once the buttons have slid along: then
                                        // Up finds the nearest one itself.)
                                        e.key == Key.DirectionUp -> runCatching { barFocus.requestFocus() }.isSuccess
                                        else -> false
                                    }
                                }
                            if (item != null) PosterCard(item = item, modifier = m, fill = true, onClick = {
                                // A folder: gone into, here on the wall.
                                if (isFolder(item)) vm.folderFor(item)?.let { g ->
                                    LauncherLibrary.index = 0
                                    LauncherLibrary.trail.add(LauncherLibrary.Step(LauncherLibrary.trail.lastOrNull()?.top, g, item.title))
                                }
                                else {
                                    // (A folder can hold films and shows: each opens on its own kind's page.)
                                    val show = item.type == MediaType.Show
                                    movies.closeTitlePages(); shows.closeTitlePages()
                                    (if (show) shows else movies).open(item)
                                    Launcher.openTitle(if (show) Page.Shows else Page.Movies)
                                    LauncherLibrary.keep()
                                }
                            })
                            else PlaceholderPoster(m)
                        }
                    }
                }
                if (b.canLetters) AlphabetRail(
                    // Which letters have titles is only known sorted by title (else every letter is offered).
                    letters = b.letters.takeIf { b.byTitle },
                    shown = shownLetter,
                    focusRequester = railFocus,
                    onFocus = { railOn = it },
                    onMove = { railLetter = it },
                    onLetter = { vm.jumpToLetter(it) },
                    onPick = { vm.jumpToLetter(it); railOn = false },
                    onUp = { if (runCatching { barFocus.requestFocus() }.isFailure) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up) },
                    onLeft = {
                        val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
                        runCatching { cell((first + COLUMNS - 1).coerceAtMost(count - 1)).requestFocus() }
                    },
                )
            }
        }
        // Moving along the A–Z strip: the letter, big, so you can see where you're going.
        if (railOn) Box(
            Modifier.align(Alignment.Center).size(140.dp).background(AppColors.Panel.copy(alpha = 0.92f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(railLetter.toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, maxLines = 1, softWrap = false) }
        WallPanels(b, vm, panel, changed = ::changed) { panel = null }
        if (folders != null) FolderPanel(folders, vm, type, panel) { panel = null }
    }
}

/** A folder's card (a server of folders, CatalogRepository.serverFolderCard). */
private fun isFolder(item: MediaItem) = item.key.startsWith("col:") && item.key.contains("folder:")

/** The folder that holds every title, by its name, where there's no "Titles". */
private val EVERYTHING = Regex("(all|everything|all (titles|movies|films|shows|series)|movies|films|shows|series|tv shows)", RegexOption.IGNORE_CASE)

/**
 * Across the top of the wall on a server of folders: Sort, then each of its folders as a button ("Genres", "Years",
 * "Actors"…). One opens its list to pick from (or, a folder of titles such as "Recently Added", shows them); the
 * one in force is lit and says what's picked. One at a time: the server keeps each as a list of its own.
 */
@Composable
private fun FolderBar(b: MediaViewModel.LibraryBrowse, f: LauncherLibrary.Folders, first: FocusRequester, onBar: (Boolean) -> Unit, onPanel: (String) -> Unit) {
    val sort = b.options?.sorts?.firstOrNull { it.id == b.query.sort }
    val on = LauncherLibrary.trail.firstOrNull()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 10.dp).onFocusChanged { onBar(it.hasFocus) }.focusGroup(),
    ) {
        item(key = "sort") {
            AppPill(text = "Sort" + (sort?.let { ": ${it.label} ${if (b.query.desc) "↓" else "↑"}" } ?: ""), selected = false,
                onClick = { if (b.options != null) onPanel("sort") }, modifier = Modifier.focusRequester(first), height = 34.dp)
        }
        if (on != null) item(key = "clear") {
            AppPill(text = "Clear  ✕", selected = false, outlined = true, onClick = { LauncherLibrary.index = 0; LauncherLibrary.trail.clear() }, height = 34.dp)
        }
        items(f.tops.filter { "col:" + f.base?.key != it.key }, key = { "d-" + it.key }) { top ->
            val lit = on?.top == top.key
            val picked = LauncherLibrary.trail.lastOrNull()?.label?.takeIf { lit }
            AppPill(text = if (picked != null) "${top.title}: $picked" else top.title, selected = lit, onClick = { onPanel("d:" + top.key) }, height = 34.dp)
        }
    }
}

/** One of the folders across the top, opened ([panel]: "d:<its key>"): its folders to pick from; a folder of titles goes straight onto the wall. */
@Composable
private fun FolderPanel(f: LauncherLibrary.Folders, vm: MediaViewModel, type: MediaType, panel: String?, onClose: () -> Unit) {
    val top = panel?.takeIf { it.startsWith("d:") }?.let { k -> f.tops.firstOrNull { it.key == k.removePrefix("d:") } } ?: return
    val g = vm.folderFor(top) ?: return
    val inside by produceState<List<MediaItem>?>(null, top.key) { value = vm.folderChildren(g, type) }
    val list = inside
    val picking = list != null && list.isNotEmpty() && list.all { isFolder(it) }
    LaunchedEffect(list) {
        if (list == null || picking) return@LaunchedEffect
        LauncherLibrary.index = 0
        LauncherLibrary.trail.clear(); LauncherLibrary.trail.add(LauncherLibrary.Step(top.key, g, null))
        onClose()
    }
    SidePanel(top.title, onClose) { first ->
        if (!picking) item(key = "loading") { MenuItem(text = "Loading…", selected = false, onClick = {}, modifier = Modifier.focusRequester(first), dim = true) }
        else items(list.orEmpty(), key = { "c-" + it.key }) { c ->
            val on = LauncherLibrary.trail.any { "col:" + it.lib.key == c.key }
            MenuItem(text = c.title, trailing = if (on) "✓" else null, selected = on, modifier = if (c == list?.first()) Modifier.focusRequester(first) else Modifier, onClick = {
                vm.folderFor(c)?.let { sub ->
                    LauncherLibrary.index = 0
                    LauncherLibrary.trail.clear(); LauncherLibrary.trail.add(LauncherLibrary.Step(top.key, sub, c.title))
                }
                onClose()
            })
        }
    }
}

/**
 * Across the top of the wall: Sort, then everything this library can be filtered by, each a button of its own. An
 * on / off one (Unwatched, 4K) switches with OK; the others (Genre, Year) open their choices. The ones in force are
 * lit and say what's picked; Clear puts them all off.
 */
@Composable
private fun WallBar(
    b: MediaViewModel.LibraryBrowse, vm: MediaViewModel, first: FocusRequester,
    onBar: (Boolean) -> Unit, onPanel: (String) -> Unit, changed: (() -> Unit) -> Unit,
) {
    val o = b.options
    val sort = o?.sorts?.firstOrNull { it.id == b.query.sort }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 10.dp).onFocusChanged { onBar(it.hasFocus) }.focusGroup(),
    ) {
        item(key = "sort") {
            AppPill(text = "Sort" + (sort?.let { ": ${it.label} ${if (b.query.desc) "↓" else "↑"}" } ?: ""), selected = false,
                onClick = { if (o != null) onPanel("sort") }, modifier = Modifier.focusRequester(first), height = 34.dp)
        }
        if (b.query.active > 0) item(key = "clear") {
            AppPill(text = "Clear filters  ✕", selected = false, outlined = true, onClick = { changed { vm.clearFilters() } }, height = 34.dp)
        }
        items(o?.filters.orEmpty(), key = { "f-" + it.id }) { g ->
            if (g.toggle) AppPill(text = g.label, selected = g.id in b.query.toggles, onClick = { changed { vm.toggleFlag(g.id) } }, height = 34.dp)
            else {
                val picked = b.query.filters[g.id].orEmpty()
                val text = if (picked.isEmpty()) g.label else g.label + ": " + picked.first().label + if (picked.size > 1) " +${picked.size - 1}" else ""
                AppPill(text = text, selected = picked.isNotEmpty(), onClick = { vm.loadFilterValues(g.id); onPanel("f:" + g.id) }, height = 34.dp)
            }
        }
    }
}

/** The pop-ups over the wall ([panel]: "sort", or "f:<filter>" for one filter's choices). */
@Composable
private fun WallPanels(b: MediaViewModel.LibraryBrowse, vm: MediaViewModel, panel: String?, changed: (() -> Unit) -> Unit, onClose: () -> Unit) {
    val o = b.options ?: return
    if (panel == "sort") SortPanel(o, b.query,
        onPick = { id -> val same = id == b.query.sort; changed { vm.setLibrarySort(id) }; if (!same) onClose() },
        onDesc = { d -> changed { vm.setLibraryDesc(d) } }, onClose = onClose)
    val g: FilterGroup = panel?.takeIf { it.startsWith("f:") }?.let { id -> o.filters.firstOrNull { it.id == id.removePrefix("f:") } } ?: return
    SidePanel(g.label, onClose) { first ->
        val list = b.values[g.id]
        val picked = b.query.filters[g.id].orEmpty()
        when {
            list == null -> item(key = "loading") { MenuItem(text = "Loading…", selected = false, onClick = {}, modifier = Modifier.focusRequester(first), dim = true) }
            list.isEmpty() -> item(key = "none") { MenuItem(text = "Nothing to pick from in this library", selected = false, onClick = onClose, modifier = Modifier.focusRequester(first), dim = true) }
            else -> items(list, key = { "v-" + it.id }) { v ->
                val on = picked.any { it.id == v.id }
                MenuItem(text = v.label, trailing = if (on) "✓" else null, selected = on, onClick = { changed { vm.toggleFilterValue(g.id, v) } },
                    modifier = if (v == list.first()) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}
