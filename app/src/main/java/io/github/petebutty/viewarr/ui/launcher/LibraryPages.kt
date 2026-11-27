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

    /** Films marked watched / not from a wall's hold-OK menu since they were listed: their posters' ticks follow. */
    val watched = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    /**
     * Surprise me was pressed on a wall and its pick's page is up: how to pick another the same way (the title
     * page's "Another one" button). Null: the title wasn't opened that way.
     */
    var surprise by mutableStateOf<(suspend () -> MediaItem?)?>(null)

    /** A wall's hold-OK menu is up (Back is its own then). */
    var menuUp by mutableStateOf(false)

    /** Titles' details for the wall's info card, kept for a while (LibraryCard.kt). */
    val details = HashMap<String, io.github.petebutty.viewarr.data.media.MediaDetail>()

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

    /** A tile of several libraries shown as one (Shelf), opened: its wall (LibraryMerge.kt); [current] is then one of them picked by itself. */
    var shelf by mutableStateOf<Shelf?>(null)
        private set
    /** That wall's list, kept while the app runs: back from a title, it's as it was. */
    var merged: MergedList? = null
    fun openShelf(s: Shelf) { current = null; shelf = s }
    fun closeShelf() { shelf = null }

    fun show() { current = null; shelf = null; surprise = null; open = true }
    fun close() { open = false; current = null; shelf = null; surprise = null }
    /** Another page opened over the home screen: put away, the library kept in case it's asked back ([keep]). */
    fun hide() { open = false; surprise = null }
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
    // (Back on these pages, e.g. from a title Surprise me picked: that's over.)
    LaunchedEffect(Unit) { LauncherLibrary.surprise = null }
    Box(Modifier.fillMaxSize().appBackground(titleArt = false)) {
        val cur = LauncherLibrary.current
        val shelf = LauncherLibrary.shelf
        if (cur == null && shelf != null) MergedWall(shelf, cornerUp, movies, shows)
        else if (cur == null) LibraryList(movies, shows, cornerUp)
        else LibraryWall(if (cur.second == MediaType.Show) shows else movies, cur.first, cur.second, cornerUp, movies, shows)
        if (liveUp) LiveCorner(playback, Modifier.offset { IntOffset(CornerPicture.inset.first.roundToPx(), CornerPicture.inset.second.roundToPx()) })
    }
}

/** Room for the corner picture when something's playing there: the heading sits beside it, the page under it. */
@Composable
internal fun PageHead(cornerUp: Boolean, content: @Composable () -> Unit) {
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
    // Hold OK on a tile: Move or Hide. The order and the hidden ones are yours, kept on this box.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("lib_hidden", emptySet()).orEmpty().toSet()) }
    fun setHidden(key: String, on: Boolean) {
        hidden = if (on) hidden + key else hidden - key
        prefs.edit().putStringSet("lib_hidden", hidden).apply()
        // (A wall of several libraries is made again from the ones now showing.)
        LauncherLibrary.merged = null
    }
    var order by remember { mutableStateOf(prefs.getString("lib_order", "").orEmpty().split('|').filter { it.isNotEmpty() }) }
    var menuFor by remember { mutableStateOf<Shelf?>(null) }
    // The tile picked up to move (its key): the arrows carry it, OK or Back puts it down.
    var carrying by remember { mutableStateOf<String?>(null) }
    var hiddenOpen by remember { mutableStateOf(false) }
    // (Ones never moved keep their usual places, after the ones that have been.)
    // Libraries you've joined into one tile (hold OK → Merge with…): Shelf.
    var groups by remember { mutableStateOf(LibraryMerges.load(prefs)) }
    fun setGroups(g: List<List<String>>) { groups = g.filter { it.size > 1 }; LibraryMerges.save(prefs, groups); LauncherLibrary.merged = null }
    // The library a merge is being put together around (its tile key): the list of the others, to tick.
    var mergeLead by remember { mutableStateOf<String?>(null) }
    val all = remember(every, hidden, order, groups) {
        shelves(every.filter { tileKey(it) !in hidden }, groups, ::tileKey).sortedBy { e -> order.indexOf(e.key).let { if (it < 0) Int.MAX_VALUE else it } }
    }
    val away = every.filter { tileKey(it) in hidden }
    val loading = films == null || series == null
    BackHandler(enabled = menuFor == null && !hiddenOpen && mergeLead == null) { if (carrying != null) carrying = null else LauncherLibrary.close() }
    val requesters = remember { HashMap<String, FocusRequester>() }
    val hiddenFocus = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(all.size, away.isEmpty(), mergeLead == null) {
        if (mergeLead != null) return@LaunchedEffect
        val want = LauncherLibrary.lastTile?.takeIf { k -> all.any { it.key == k } } ?: all.firstOrNull()?.key
        // (Every library hidden: the Hidden libraries button, so there's always something highlighted.)
        if (want == null) { awaitFrame(); awaitFrame(); runCatching { hiddenFocus.requestFocus() }; return@LaunchedEffect }
        // (The tile scrolled into view first: one further down the page isn't drawn, and can't take the highlight.)
        val at = all.indexOfFirst { it.key == want }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == at }) runCatching { listState.scrollToItem(at) }
        awaitFrame(); awaitFrame()
        runCatching { requesters[want]?.requestFocus() }
    }
    fun move(key: String, by: Int) {
        val keys = all.map { it.key }
        val i = keys.indexOf(key); val j = (i + by).coerceIn(0, keys.lastIndex)
        if (i < 0 || i == j) return
        val next = keys.toMutableList().apply { add(j, removeAt(i)) }
        order = next
        prefs.edit().putString("lib_order", next.joinToString("|")).apply()
        // (Kept on screen as it goes.)
        scope.launch { if (listState.layoutInfo.visibleItemsInfo.none { it.index == j }) runCatching { listState.scrollToItem((j - j % 4).coerceAtLeast(0)) } }
    }
    // The page's corner artwork: a picture from the highlighted library (its tile's).
    var onTile by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(onTile) {
        val k = onTile ?: return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        // (Its picture may still be on its way.)
        val first = all.firstOrNull { it.key == k }?.members?.firstOrNull()?.let { tileKey(it) } ?: k
        repeat(20) { LauncherLibrary.facts[first]?.first?.let { io.github.petebutty.viewarr.ui.theme.Wallpaper.title = it; return@LaunchedEffect }; kotlinx.coroutines.delay(250) }
    }
    // (OK was held to get here, or pressed in the menu: only an OK pressed while carrying puts the tile down.)
    val okDown = remember { booleanArrayOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHead(cornerUp) {
            Column(Modifier.padding(start = 48.dp, end = 48.dp)) {
                Text("Library", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Text(
                    when {
                        carrying != null -> "Moving: the arrows carry it, OK puts it down."
                        all.isNotEmpty() -> "Pick a library to go through everything in it. Hold OK on one to move, merge or hide it."
                        away.isNotEmpty() -> "Every library is hidden. Pick Hidden libraries to bring one back."
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
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                val key = carrying ?: return@onPreviewKeyEvent false
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (e.type == KeyEventType.KeyDown) when {
                    e.key == Key.DirectionLeft -> move(key, -1)
                    e.key == Key.DirectionRight -> move(key, 1)
                    e.key == Key.DirectionUp -> move(key, -4)
                    e.key == Key.DirectionDown -> move(key, 4)
                    ok -> okDown[0] = true
                    else -> return@onPreviewKeyEvent false
                } else if (ok) { if (okDown[0]) { okDown[0] = false; carrying = null } }
                else if (e.key !in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) return@onPreviewKeyEvent false
                true
            },
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 18.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(all, key = { it.key }) { e ->
                val key = e.key
                val lifted = carrying == key
                LibraryTile(e, movies, shows,
                    Modifier.focusRequester(requesters.getOrPut(key) { FocusRequester() }).onFocusChanged { if (it.isFocused) { LauncherLibrary.lastTile = key; onTile = key } }
                        // (Picked up: raised and a little bigger; the rest dimmed.)
                        .graphicsLayer {
                            val s = if (lifted) 1.06f else 1f
                            scaleX = s; scaleY = s
                            translationY = if (lifted) -6.dp.toPx() else 0f
                            alpha = if (carrying != null && !lifted) 0.5f else 1f
                        },
                    onLong = { if (carrying == null) menuFor = e }) {
                        val one = e.members.first()
                        if (e.merged) LauncherLibrary.openShelf(e)
                        else { (if (one.second == MediaType.Show) shows else movies).openLibrary(one.first, one.second); LauncherLibrary.enter(one.first, one.second) }
                    }
            }
            // The hidden ones: one button at the foot of the page (a tile each took the page over).
            if (away.isNotEmpty()) item(key = "hidden", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 10.dp)) {
                    AppPill(text = "Hidden libraries  ·  ${away.size}", selected = false, onClick = { hiddenOpen = true },
                        modifier = Modifier.focusRequester(hiddenFocus), height = 34.dp)
                }
            }
        }
    }
    menuFor?.let { e ->
        fun after() {
            menuFor = null
            // (The highlight to a tile that's still there.)
            LauncherLibrary.lastTile = all.firstOrNull { it.key != e.key }?.key
            LauncherLibrary.lastTile?.let { k -> runCatching { requesters[k]?.requestFocus() } }
        }
        SidePanel(e.name, onClose = { menuFor = null }) { first ->
            item(key = "move") {
                MenuItem(text = "Move", selected = false, modifier = Modifier.focusRequester(first), onClick = {
                    okDown[0] = false; carrying = e.key; menuFor = null
                })
            }
            item(key = "hide") {
                MenuItem(text = if (e.merged) "Hide these libraries" else "Hide this library", selected = false, onClick = {
                    hidden = hidden + e.members.map { tileKey(it) }
                    prefs.edit().putStringSet("lib_hidden", hidden).apply()
                    after()
                })
            }
            // Join it with others into one tile and one wall, or take a joined one apart again.
            item(key = "merge") {
                MenuItem(text = if (e.joined) "Add or remove libraries…" else "Merge with…", selected = false, onClick = {
                    mergeLead = tileKey(e.members.first()); menuFor = null
                })
            }
            if (e.joined) item(key = "split") {
                MenuItem(text = "Split into separate libraries", selected = false, onClick = {
                    val lead = tileKey(e.members.first())
                    setGroups(groups.filterNot { lead in it }); LauncherLibrary.lastTile = lead; menuFor = null
                })
            }
            item(key = "cancel") { MenuItem(text = "Cancel", selected = false, onClick = { menuFor = null }) }
        }
    }
    // Merging: every other library of the same kind, to tick. One that's already joined with others elsewhere says so,
    // and ticking it brings it over here (a library is in one joined tile at a time).
    mergeLead?.let { lead ->
        val visible = every.filter { tileKey(it) !in hidden }
        val me = visible.firstOrNull { tileKey(it) == lead }
        val mine = groups.firstOrNull { lead in it } ?: listOf(lead)
        val others = visible.filter { o -> tileKey(o) != lead && o.second == me?.second }
        fun close() {
            mergeLead = null
            LauncherLibrary.lastTile = if ((groups.firstOrNull { lead in it }?.size ?: 1) > 1) "G:" + (groups.first { lead in it }.first()) else lead
        }
        SidePanel("Merge " + (me?.first?.title ?: "") + " with", onClose = { close() }) { first ->
            item(key = "done") { MenuItem(text = "Done", selected = false, modifier = Modifier.focusRequester(first), onClick = {
                close()
                LauncherLibrary.lastTile?.let { k -> runCatching { requesters[k]?.requestFocus() } }
            }) }
            if (others.isEmpty()) item(key = "none") { MenuItem(text = "No other libraries of this kind", selected = false, onClick = {}, dim = true) }
            items(others, key = { "m-" + tileKey(it) }) { o ->
                val k = tileKey(o)
                val on = k in mine
                val elsewhere = if (on) null else groups.firstOrNull { k in it }?.firstNotNullOfOrNull { g -> visible.firstOrNull { tileKey(it) == g }?.first?.title }
                MenuItem(text = o.first.title, selected = on,
                    trailing = o.first.source + (if (on) "  ✓" else if (elsewhere != null) "  ·  joined with $elsewhere" else ""),
                    onClick = {
                        // (Worked out from the groups as they are now, not as they were when this row was drawn.)
                        val now = groups.firstOrNull { lead in it } ?: listOf(lead)
                        val next = if (k in now) now - k else now + k
                        setGroups(groups.filterNot { lead in it }.map { g -> g - k } + listOf(next))
                    })
            }
        }
    }
    // The hidden libraries, by name: OK on one brings it back (the list stays up for the next).
    if (hiddenOpen) SidePanel("Hidden libraries", onClose = { hiddenOpen = false }) { first ->
        if (away.isEmpty()) item(key = "none") { MenuItem(text = "None hidden now", selected = false, onClick = { hiddenOpen = false }, modifier = Modifier.focusRequester(first), dim = true) }
        items(away, key = { "h-" + tileKey(it) }) { e ->
            MenuItem(text = e.first.title, trailing = e.first.source + "  ·  Show", selected = false,
                modifier = if (e == away.first()) Modifier.focusRequester(first) else Modifier,
                onClick = { LauncherLibrary.lastTile = tileKey(e); setHidden(tileKey(e), false) })
        }
    }
}

/** A tile: its made-up face (LibraryTiles), with a picture and count looked up once for each library in it. */
@Composable
private fun LibraryTile(e: Shelf, movies: MediaViewModel, shows: MediaViewModel, modifier: Modifier, onLong: () -> Unit, onClick: () -> Unit) {
    // (Made afresh when the libraries in it change: merging added one to a list sized for the ones before, and the app
    // closed. 2026-11-26.)
    val keys = e.members.map { tileKey(it) }
    var facts by remember(keys) { mutableStateOf(keys.map { LauncherLibrary.facts[it] }) }
    LaunchedEffect(keys) {
        e.members.forEachIndexed { i, m ->
            if (LauncherLibrary.facts[keys[i]] == null)
                (if (m.second == MediaType.Show) shows else movies).libraryFacts(m.first, m.second)?.let { LauncherLibrary.facts[keys[i]] = it }
            facts = keys.map { LauncherLibrary.facts[it] }
        }
    }
    val art = facts.firstNotNullOfOrNull { it?.first }
    // (Several: their titles added up, once every one has answered.)
    val count = if (facts.all { it != null }) facts.sumOf { it?.second?.coerceAtLeast(0) ?: 0 } else null
    FocusCard(
        onClick = onClick, onLongClick = onLong, modifier = Modifier.fillMaxWidth().then(modifier),
        shape = RoundedCornerShape(12.dp), aspect = 16f / 9f, containerColor = AppColors.Panel, focusedScale = 1.04f,
    ) { LibraryTiles.Face(e.name, e.sources, e.type, art, count) }
}

private fun tileKey(e: Pair<MediaLibrary, MediaType>) = (if (e.second == MediaType.Show) "S:" else "M:") + e.first.key

// ---- One library: a wall of posters ----

internal const val COLUMNS = 8

/** The highlighted row comes to rest [inset] px from the wall's top, in the same frame (the glide is drawn apart). */
@OptIn(ExperimentalFoundationApi::class)
internal fun rowSpec(inset: Float) = object : BringIntoViewSpec {
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
    BackHandler(enabled = panel == null && !LauncherLibrary.menuUp) {
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
    // Holding Down / Up in A to Z order: the letter comes up and the hold runs through letters (holdKey).
    var holdLetter by remember { mutableStateOf<Char?>(null) }
    val holdAt = remember { longArrayOf(0L) }
    // The page's corner artwork: the highlighted title's, once you've rested on it (it stayed on the last title opened).
    LaunchedEffect(focused?.key) { focused?.backdrop?.let { kotlinx.coroutines.delay(300); io.github.petebutty.viewarr.ui.theme.Wallpaper.title = it } }
    val folders = LauncherLibrary.folders
    // (A folder's own cards are folders too, on some servers: counted as folders, opened in place.)
    val ofFolders = b.items.isNotEmpty() && b.items.all { isFolder(it) }

    // Whatever's on screen (and a little either side) is loaded; stretches far away are let go.
    LaunchedEffect(b.version) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.let { v -> (v.firstOrNull()?.index ?: 0) to (v.lastOrNull()?.index ?: 0) } }
            .collect { (f, l) -> vm.ensureRange(f, l) }
    }
    // The highlight has been put on a poster since the wall came up. Until then nothing else here takes it: coming
    // back from a title's page, Android handed it to the first thing that could, the A–Z strip (2026-11-26).
    var landed by remember { mutableStateOf(false) }
    var cellOn by remember { mutableStateOf(-1) }
    suspend fun focusAt(i: Int) {
        if (count == 0) return
        val idx = i.coerceIn(0, count - 1)
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) runCatching { gridState.scrollToItem(idx - idx % COLUMNS) }
        // (Tried for a moment: the poster is there a frame or two after the wall scrolls to it.)
        // (Longer for one far down the wall: its stretch has to arrive first.)
        repeat(120) {
            awaitFrame()
            runCatching { cell(idx).requestFocus() }
            if (cellOn == idx) { landed = true; return }
        }
        landed = true
    }
    // Rested on a poster for a moment: its card (InfoCard). Gone as soon as the highlight moves.
    var cardFor by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(focused?.key, cellOn, panel, holdLetter) {
        cardFor = null
        val f = focused ?: return@LaunchedEffect
        if (cellOn < 0 || panel != null || holdLetter != null || isFolder(f)) return@LaunchedEffect
        kotlinx.coroutines.delay(CARD_AFTER_MS)
        cardFor = f
    }
    // Hold OK on a poster: its menu (WallMenu).
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    // This server's collections, as a list to open on the wall (the Collections button).
    val everyLib by vm.allLibraries.collectAsStateWithLifecycle()
    val collections = everyLib?.map { it.first }?.firstOrNull { it.kind == LibKind.Collections && it.source == lib.source && it.server == lib.server }
    // Surprise me: the poster to go to once the wall has it.
    // (With the list it must wait out, when switching Unwatched on is about to make a new one: going straight away
    // aimed at the old list, which was then replaced, and nothing happened. 2026-11-26.)
    var goTo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(goTo, b.version, b.total) {
        val (g, old) = goTo ?: return@LaunchedEffect
        if (b.version == old || b.total <= 0) return@LaunchedEffect
        goTo = null
        focusAt(g)
    }
    // A new list (opened, sorted, filtered) or back from a title: the highlight on the poster you were on. Not while
    // you're on the buttons across the top or the A–Z strip: picking a filter there leaves you there.
    LaunchedEffect(b.version, count > 0) {
        if (count == 0 || panel != null || (landed && (barOn || railOn))) return@LaunchedEffect
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
                    // Across the top: search (this library only), then sort and its filters.
                    Row(Modifier.padding(top = 10.dp).onFocusChanged { barOn = it.hasFocus }.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.runtime.key(b.library.key) {
                                io.github.petebutty.viewarr.ui.media.LibrarySearchBox(b.query.query, onQuery = { t -> LauncherLibrary.index = 0; vm.setLibraryQuery(t) },
                                    onFocus = {}, onDone = { scope.launch { focusAt(0) } })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            if (folders != null) FolderBar(b, folders, barFocus, onPanel = { panel = it })
                            else WallBar(b, vm, barFocus, onPanel = { panel = it }, changed = ::changed,
                                // (From the library itself, not from inside a collection.)
                                onCollections = collections?.takeIf { LauncherLibrary.trail.isEmpty() }?.let { c -> {
                                    LauncherLibrary.index = 0
                                    LauncherLibrary.trail.add(LauncherLibrary.Step(null, c, "Collections"))
                                } },
                                onSurprise = {
                                // A title picked at random from the ones you haven't watched (any, where the library can't
                                // tell), as the wall is narrowed now: its page opens; Back is the wall again. (It used to
                                // switch Unwatched on and send the highlight far down the wall, which came up empty while
                                // that part was fetched. 2026-11-26.)
                                scope.launch {
                                    val un = b.options?.filters?.firstOrNull { it.toggle && it.label == "Unwatched" }?.id
                                    val q = if (un != null) b.query.copy(toggles = b.query.toggles + un) else b.query
                                    val library = b.library; val kind = b.type
                                    val another: suspend () -> MediaItem? = {
                                        val n = vm.libraryStretch(library, kind, q, 0, 1)?.second ?: 0
                                        if (n > 0) vm.libraryStretch(library, kind, q, kotlin.random.Random.nextInt(n), 1)?.first?.firstOrNull { !isFolder(it) } else null
                                    }
                                    val pick = another()
                                    if (pick == null) { android.widget.Toast.makeText(context, "Nothing unwatched to pick from here", android.widget.Toast.LENGTH_SHORT).show(); return@launch }
                                    val show = pick.type == MediaType.Show
                                    movies.closeTitlePages(); shows.closeTitlePages()
                                    (if (show) shows else movies).open(pick)
                                    Launcher.openTitle(if (show) Page.Shows else Page.Movies)
                                    LauncherLibrary.keep()
                                    LauncherLibrary.surprise = another
                                }
                            })
                        }
                    }
                    Text(
                        when {
                            b.total == 0 && b.query.query.isNotBlank() -> "Nothing in this library matches \"${b.query.query}\"."
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
                                // (Where you are is remembered only once the highlight has been put where you were: before that
                                // Android may rest it on another poster for a moment.)
                                .onFocusChanged { if (it.isFocused) { if (landed) LauncherLibrary.index = i; cellOn = i; focused = item } else if (cellOn == i) cellOn = -1 }
                                .onPreviewKeyEvent { e ->
                                    if (b.canLetters && b.byTitle && holdKey(e, holdLetter, holdAt, item?.title, { holdLetter = it }) { vm.jumpToLetter(it) }) return@onPreviewKeyEvent true
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
                            if (item != null) Box { PosterCard(item = item, modifier = m, fill = true, onLongClick = { if (!isFolder(item)) menuFor = item }, onClick = {
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
                            }); PosterMarks(item) }
                            else PlaceholderPoster(m)
                        }
                    }
                }
                // (Not until the highlight has landed on a poster: see landed.)
                if (b.canLetters) Box(Modifier.focusProperties { canFocus = landed }) { AlphabetRail(
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
                ) }
            }
        }
        // Moving along the A–Z strip: the letter, big, so you can see where you're going.
        if (railOn || holdLetter != null) Box(
            Modifier.align(Alignment.Center).size(140.dp).background(AppColors.Panel.copy(alpha = 0.92f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text((holdLetter ?: railLetter).toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, maxLines = 1, softWrap = false) }
        // (At the side away from the poster it's about.)
        InfoCard(cardFor.takeIf { menuFor == null }, atEnd = cellOn < 0 || cellOn % COLUMNS < COLUMNS / 2, vm = vm)
        androidx.compose.runtime.SideEffect { LauncherLibrary.menuUp = menuFor != null }
        menuFor?.let { WallMenu(it, movies, shows) { menuFor = null } }
        WallPanels(b, vm, panel, changed = ::changed) { panel = null }
        if (folders != null) FolderPanel(folders, vm, type, panel) { panel = null }
    }
}

/** Each server's name for "when it was added" and "its score", among a library's sorts. */
internal val ADDED_SORTS = setOf("addedAt", "DateCreated", "Added")
internal val RATED_SORTS = setOf("audienceRating", "rating", "CommunityRating", "Rating")

/** A card that opens on the wall, not as a title: a folder (a server of folders, CatalogRepository.serverFolderCard), a collection or a playlist. */
internal fun isFolder(item: MediaItem) = item.key.startsWith("col:")

/** The folder that holds every title, by its name, where there's no "Titles". */
private val EVERYTHING = Regex("(all|everything|all (titles|movies|films|shows|series)|movies|films|shows|series|tv shows)", RegexOption.IGNORE_CASE)

/**
 * Across the top of the wall on a server of folders: Sort, then each of its folders as a button ("Genres", "Years",
 * "Actors"…). One opens its list to pick from (or, a folder of titles such as "Recently Added", shows them); the
 * one in force is lit and says what's picked. One at a time: the server keeps each as a list of its own.
 */
@Composable
private fun FolderBar(b: MediaViewModel.LibraryBrowse, f: LauncherLibrary.Folders, first: FocusRequester, onPanel: (String) -> Unit) {
    val sort = b.options?.sorts?.firstOrNull { it.id == b.query.sort }
    val on = LauncherLibrary.trail.firstOrNull()
    LazyRow(
        contentPadding = PaddingValues(end = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    onPanel: (String) -> Unit, changed: (() -> Unit) -> Unit, onCollections: (() -> Unit)?, onSurprise: () -> Unit,
) {
    val o = b.options
    val sort = o?.sorts?.firstOrNull { it.id == b.query.sort }
    // The one-press views: this library's own sort or filter for each, where it has one.
    val added = o?.sorts?.firstOrNull { it.id in ADDED_SORTS }?.id
    val rated = o?.sorts?.firstOrNull { it.id in RATED_SORTS }?.id
    val unwatched = o?.filters?.firstOrNull { it.toggle && it.label == "Unwatched" }?.id
    val going = o?.filters?.firstOrNull { it.toggle && it.label == "In progress" }?.id
    val views = setOfNotNull(unwatched, going)
    fun order(id: String) {
        // (The same again: back to A to Z.)
        val on = b.query.sort == id && b.query.desc
        changed { if (on && o != null) vm.setLibraryView(o.titleSort, false) else vm.setLibraryView(id, true) }
    }
    LazyRow(
        contentPadding = PaddingValues(end = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "sort") {
            AppPill(text = "Sort" + (sort?.let { ": ${it.label} ${if (b.query.desc) "↓" else "↑"}" } ?: ""), selected = false,
                onClick = { if (o != null) onPanel("sort") }, modifier = Modifier.focusRequester(first), height = 34.dp)
        }
        if (added != null) item(key = "v-added") { AppPill(text = "Recently added", selected = b.query.sort == added && b.query.desc, onClick = { order(added) }, height = 34.dp) }
        if (unwatched != null) item(key = "v-unwatched") { AppPill(text = "Unwatched", selected = unwatched in b.query.toggles, onClick = { changed { vm.toggleFlag(unwatched) } }, height = 34.dp) }
        if (going != null) item(key = "v-going") { AppPill(text = "In progress", selected = going in b.query.toggles, onClick = { changed { vm.toggleFlag(going) } }, height = 34.dp) }
        if (rated != null) item(key = "v-rated") { AppPill(text = "Top rated", selected = b.query.sort == rated && b.query.desc, onClick = { order(rated) }, height = 34.dp) }
        if (onCollections != null) item(key = "v-collections") { AppPill(text = "Collections", selected = false, onClick = onCollections, height = 34.dp) }
        if (o != null) item(key = "v-surprise") { AppPill(text = "Surprise me", selected = false, onClick = onSurprise, height = 34.dp) }
        if (b.query.active > 0) item(key = "clear") {
            AppPill(text = "Clear filters  ✕", selected = false, outlined = true, onClick = { changed { vm.clearFilters() } }, height = 34.dp)
        }
        // (The rest of what it can be filtered by; Unwatched and In progress are among the views above.)
        items(o?.filters.orEmpty().filter { it.id !in views }, key = { "f-" + it.id }) { g ->
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
