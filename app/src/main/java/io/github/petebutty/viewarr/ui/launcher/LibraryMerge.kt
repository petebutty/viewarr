package io.github.petebutty.viewarr.ui.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.FilterGroup
import io.github.petebutty.viewarr.data.media.FilterValue
import io.github.petebutty.viewarr.data.media.LibraryOptions
import io.github.petebutty.viewarr.data.media.LibraryQuery
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaLibrary
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.common.AppPill
import io.github.petebutty.viewarr.ui.home.Page
import io.github.petebutty.viewarr.ui.library.AlphabetRail
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.media.PlaceholderPoster
import io.github.petebutty.viewarr.ui.media.PosterCard
import io.github.petebutty.viewarr.ui.media.firstLetter
import io.github.petebutty.viewarr.ui.media.metaLine
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One tile on the Library page: a library, or several you've joined into one (hold OK on a tile → Merge with…,
 * 2026-11-26). [key]: a single library's own tile key, or "G:<its first library's key>" for a joined one.
 */
internal class Shelf(val key: String, val name: String, val type: MediaType, val members: List<Pair<MediaLibrary, MediaType>>) {
    /** Several libraries showing as one. */
    val merged get() = members.size > 1
    /** One you've joined (even if only one of its libraries is showing just now). */
    val joined get() = key.startsWith("G:")
    /** Where it comes from, for the tile and the wall's heading: "Plex", or "Plex + Plex · MINIPC". */
    val sources get() = members.map { it.first.source }.distinct().joinToString(" + ")
}

/** The libraries you've joined, kept on this box: each a list of tile keys, the first the one it's named after. */
internal object LibraryMerges {
    fun load(prefs: android.content.SharedPreferences): List<List<String>> =
        prefs.getString("lib_merges", "").orEmpty().split('\n').map { g -> g.split('\t').filter { it.isNotEmpty() } }.filter { it.size > 1 }
    fun save(prefs: android.content.SharedPreferences, groups: List<List<String>>) {
        prefs.edit().putString("lib_merges", groups.filter { it.size > 1 }.joinToString("\n") { it.joinToString("\t") }).apply()
    }
}

/** [libs] as tiles, the ones in [groups] joined; [keyOf] is a library's own tile key. */
internal fun shelves(libs: List<Pair<MediaLibrary, MediaType>>, groups: List<List<String>>, keyOf: (Pair<MediaLibrary, MediaType>) -> String): List<Shelf> {
    val byKey = libs.associateBy(keyOf)
    val used = HashSet<String>()
    val out = ArrayList<Shelf>()
    for (e in libs) {
        val k = keyOf(e)
        if (k in used) continue
        val g = groups.firstOrNull { k in it }
        if (g == null) out += Shelf(k, e.first.title, e.second, listOf(e))
        else {
            used += g
            val ms = g.mapNotNull { byKey[it] }
            out += Shelf("G:" + g.first(), ms.first().first.title, ms.first().second, ms)
        }
    }
    return out
}

/** How a joined wall is ordered: by title, newest in the libraries first, or highest score first. */
internal enum class MergedOrder { AZ, Added, Rated }

/** What a joined wall is narrowed to: words in the title, and filters by their names (each library has its own ids for them). */
internal data class MergedQuery(val text: String = "", val toggles: Set<String> = emptySet(), val picks: Map<String, Set<String>> = emptyMap()) {
    val active get() = toggles.size + picks.values.sumOf { it.size }
}

/**
 * Several libraries as one list in A to Z order, each title once: every member is read in its own A to Z order, a
 * stretch at a time, and the next title is always the earliest waiting in any of them. Only as much as has been
 * scrolled to is held. A letter (the A–Z strip) starts the list again from where that letter begins in each member.
 */
internal class MergedList(val key: String, private val members: List<Pair<MediaLibrary, MediaType>>, private val vm: MediaViewModel) {
    val items = mutableStateListOf<MediaItem>()
    /** Every member read to its end. */
    var done by mutableStateOf(false)
        private set
    /** The members' titles added up (one that's in two of them counts twice), once each has answered. */
    var total by mutableIntStateOf(-1)
        private set
    /** Changes when the list starts again (a letter picked). */
    var version by mutableIntStateOf(0)
        private set

    /** The words and filters in force. */
    var query by mutableStateOf(MergedQuery())
        private set
    /** The order it's in (a library without that order has nothing in it meanwhile). */
    var order by mutableStateOf(MergedOrder.AZ)
        private set
    /** The filters every library here offers under the same name (null until they've answered). */
    var groups by mutableStateOf<List<FilterGroup>?>(null)
        private set
    /** A filter's choices, by its name, from every library here (null while they're looked up). */
    val choices = androidx.compose.runtime.mutableStateMapOf<String, List<String>?>()

    private class Cursor(val lib: MediaLibrary, val type: MediaType) {
        var options: LibraryOptions? = null
        /** This library's own choices for a filter (by the filter's id here). */
        val values = HashMap<String, List<FilterValue>>()
        /** How it's asked, with the words and filters in force; null: it has nothing for them (a filter it can't do). */
        var q: LibraryQuery? = null
        var ready = false
        var next = 0
        var total = -1
        var ended = false
        val waiting = ArrayDeque<Entry>()
    }
    private val cursors = members.map { Cursor(it.first, it.second) }
    private val lock = Mutex()
    /** The last titles added, to spot the same one coming from another member just after. */
    // (By TMDB id; by name and year where a copy has none. Two with different ids are different titles.)
    private val seenIds = HashSet<String>()
    private val seenNames = HashSet<String>()
    private val seenNamesNoId = HashSet<String>()

    /** A title waiting its turn: [name] as it's ordered by title, [rank] for the other orders (lower comes first). */
    private class Entry(val name: String, val rank: Double, val item: MediaItem)

    /** More of the list, until it holds [upTo] titles or there are no more. */
    suspend fun more(upTo: Int) = lock.withLock {
        while (items.size < upTo && !done) {
            coroutineScope { cursors.filter { it.waiting.isEmpty() && !it.ended }.map { c -> async { ready(c); fill(c) } }.awaitAll() }
            if (groups == null) groups = shared()
            total = if (cursors.all { it.total >= 0 }) cursors.sumOf { it.total } else -1
            if (cursors.none { it.waiting.isNotEmpty() }) { done = true; break }
            // (While every member still going has a title waiting: the earliest of them is the next one for sure.)
            while (cursors.all { it.ended || it.waiting.isNotEmpty() } && cursors.any { it.waiting.isNotEmpty() }) {
                val live = cursors.filter { it.waiting.isNotEmpty() }
                val c = if (order == MergedOrder.AZ) live.minBy { it.waiting.first().name } else live.minBy { it.waiting.first().rank }
                add(c.waiting.removeFirst())
            }
        }
    }

    /** From [letter]'s first titles ('#': the very start). */
    suspend fun startAt(letter: Char) = lock.withLock {
        coroutineScope {
            cursors.map { c -> async {
                ready(c)
                val q = c.q
                c.next = if (letter == '#' || q == null) 0 else vm.libraryLetterStart(c.lib, c.type, q, letter) ?: search(c, q, letter)
            } }.awaitAll()
        }
        restart()
    }

    /** Another order: the list from its start again. */
    suspend fun reorder(o: MergedOrder) = lock.withLock {
        if (o == order) return@withLock
        order = o
        cursors.forEach { it.ready = false; it.next = 0; it.total = -1 }
        restart()
    }

    /** Other words or filters: the list from its start again. */
    suspend fun narrow(q: MergedQuery) = lock.withLock {
        if (q == query) return@withLock
        query = q
        cursors.forEach { it.ready = false; it.next = 0; it.total = -1 }
        restart()
    }

    private fun restart() {
        cursors.forEach { it.waiting.clear(); it.ended = false }
        items.clear(); seenIds.clear(); seenNames.clear(); seenNamesNoId.clear(); done = false
        version++
    }

    /**
     * One title at random from the ones you haven't watched (any, in a library that can't tell), with the words and
     * filters in force: a library picked by how many it has, then one of its titles. Null: there are none.
     */
    suspend fun random(): MediaItem? {
        val pools = coroutineScope {
            cursors.map { c -> async {
                if (c.options == null) c.options = vm.libraryOptionsOf(c.lib, c.type)
                ready(c)
                val base = c.q ?: return@async null
                val un = c.options?.filters?.firstOrNull { it.toggle && it.label == "Unwatched" }?.id
                val q = if (un != null) base.copy(toggles = base.toggles + un) else base
                val n = vm.libraryStretch(c.lib, c.type, q, 0, 1)?.second ?: 0
                if (n > 0) Triple(c, q, n) else null
            } }.awaitAll().filterNotNull()
        }
        if (pools.isEmpty()) return null
        var at = kotlin.random.Random.nextInt(pools.sumOf { it.third })
        val (c, q, n) = pools.first { p -> if (at < p.third) true else { at -= p.third; false } }
        return vm.libraryStretch(c.lib, c.type, q, at.coerceIn(0, n - 1), 1)?.first?.firstOrNull { !isFolder(it) }
    }

    /** A filter's choices looked up in every library (for its list, and so each library knows its own ids for them). */
    suspend fun lookUp(label: String) {
        if (choices[label] != null) return
        choices[label] = null
        val all = coroutineScope {
            cursors.map { c -> async {
                if (c.options == null) c.options = vm.libraryOptionsOf(c.lib, c.type)
                val g = c.options?.filters?.firstOrNull { !it.toggle && it.label == label } ?: return@async emptyList<FilterValue>()
                c.values.getOrPut(g.id) { vm.libraryFilterValues(c.lib, c.type, g.id) }
            } }.awaitAll()
        }
        choices[label] = all.flatten().map { it.label }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    /** The filters every library has, by name (on / off ones and lists apart), in the first library's order. */
    private fun shared(): List<FilterGroup> {
        val first = cursors.firstOrNull()?.options?.filters.orEmpty()
        return first.filter { g -> cursors.all { c -> c.options?.filters.orEmpty().any { it.label == g.label && it.toggle == g.toggle } } }.distinctBy { it.label }
    }

    /** [c]'s own way of asking for the words and filters in force. */
    private suspend fun ready(c: Cursor) {
        if (c.ready) return
        if (c.options == null) c.options = vm.libraryOptionsOf(c.lib, c.type)
        val o = c.options
        val q = query
        c.q = run {
            val filters = o?.filters.orEmpty()
            val toggles = q.toggles.map { label -> filters.firstOrNull { it.toggle && it.label == label }?.id ?: return@run null }.toSet()
            val picks = q.picks.filter { it.value.isNotEmpty() }.map { (label, want) ->
                val g = filters.firstOrNull { !it.toggle && it.label == label } ?: return@run null
                val mine = c.values.getOrPut(g.id) { vm.libraryFilterValues(c.lib, c.type, g.id) }.filter { v -> want.any { it.equals(v.label, ignoreCase = true) } }
                if (mine.isEmpty()) return@run null
                g.id to mine
            }.toMap()
            // (Each library's own name for the order asked for.)
            val sort = when (order) {
                MergedOrder.AZ -> o?.titleSort.orEmpty()
                MergedOrder.Added -> o?.sorts?.firstOrNull { it.id in ADDED_SORTS }?.id ?: return@run null
                MergedOrder.Rated -> o?.sorts?.firstOrNull { it.id in RATED_SORTS }?.id ?: return@run null
            }
            LibraryQuery(sort, order != MergedOrder.AZ, picks, toggles, q.text)
        }
        c.ready = true
    }

    /** Where [letter] starts in a member that can't say: found by halving (it is in A to Z order). */
    private suspend fun search(c: Cursor, q: LibraryQuery, letter: Char): Int {
        if (c.total < 0) c.total = vm.libraryStretch(c.lib, c.type, q, 0, 1)?.second ?: return 0
        var lo = 0; var hi = c.total
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val t = vm.libraryStretch(c.lib, c.type, q, mid, 1)?.first?.firstOrNull()?.title ?: return lo
            if (firstLetter(t) < letter) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private suspend fun fill(c: Cursor) {
        // (A filter this library can't do: nothing of its while that's on.)
        val q = c.q ?: run { c.ended = true; c.total = 0; return }
        val got = vm.libraryStretch(c.lib, c.type, q, c.next, STRETCH)
        // (No answer: this member is left out from here on; the others carry on.)
        if (got == null) { c.ended = true; if (c.total < 0) c.total = 0; return }
        c.total = got.second
        c.next += got.first.size
        // (A server that answers with its folders, not titles: nothing of its to list.)
        got.first.filterNot { isFolder(it) }.forEach {
            val rank = when (order) {
                MergedOrder.AZ -> 0.0
                MergedOrder.Added -> -(it.marks?.addedAt ?: 0L).toDouble()
                MergedOrder.Rated -> -(it.rating ?: 0.0)
            }
            c.waiting += Entry(order(it.title), rank, it)
        }
        if (got.first.isEmpty() || c.next >= got.second) c.ended = true
    }

    private fun add(e: Entry) {
        val item = e.item
        val name = item.type.name + ":" + e.name + ":" + (item.year ?: "")
        val id = item.tmdbId?.let { item.type.name + ":" + it }
        val twin = if (id != null) id in seenIds || name in seenNamesNoId else name in seenNames
        if (twin) return
        if (id != null) seenIds += id else seenNamesNoId += name
        seenNames += name
        items += item
    }

    companion object {
        private const val STRETCH = 60
        private val ARTICLE = Regex("^(the|a|an)\\s+")
        /** A title as it's ordered: lower case, no leading "The" / "A" / "An", letters and digits only. */
        fun order(title: String) = title.trim().lowercase().replace(ARTICLE, "").filter { it.isLetterOrDigit() }
    }
}

/** A shelf of several libraries, opened: one wall of posters in A to Z order, each title once, with the A–Z strip. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MergedWall(shelf: Shelf, cornerUp: Boolean, movies: MediaViewModel, shows: MediaViewModel) {
    val m = remember(shelf.key) {
        LauncherLibrary.merged?.takeIf { it.key == shelf.key } ?: MergedList(shelf.key, shelf.members, movies).also { LauncherLibrary.merged = it; LauncherLibrary.index = 0 }
    }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val gridState = remember(m.version) { LazyGridState() }
    val cells = remember(m.version) { HashMap<Int, FocusRequester>() }
    fun cell(i: Int) = cells.getOrPut(i) { FocusRequester() }
    val barFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var barOn by remember { mutableStateOf(false) }
    var railOn by remember { mutableStateOf(false) }
    var railLetter by remember { mutableStateOf('A') }
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    // (As a library's own wall: nothing else takes the highlight until it has been put on a poster.)
    var landed by remember { mutableStateOf(false) }
    var cellOn by remember { mutableIntStateOf(-1) }
    val count = m.items.size
    var panel by remember { mutableStateOf<String?>(null) }
    // Holding Down / Up: after a moment the letter comes up and the hold runs through letters; letting go jumps there.
    var holdLetter by remember { mutableStateOf<Char?>(null) }
    val holdAt = remember { longArrayOf(0L) }
    val typing = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    fun narrow(q: MergedQuery) { scope.launch { LauncherLibrary.index = 0; m.narrow(q); m.more(120) } }
    fun reorder(o: MergedOrder) { scope.launch { LauncherLibrary.index = 0; m.reorder(o); m.more(120) } }
    val letters = m.order == MergedOrder.AZ
    // Hold OK on a poster: its menu (WallMenu).
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    // Rested on a poster for a moment: its card (InfoCard). Gone as soon as the highlight moves.
    var cardFor by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(focused?.key, cellOn, panel, holdLetter) {
        cardFor = null
        val f = focused ?: return@LaunchedEffect
        if (cellOn < 0 || panel != null || holdLetter != null) return@LaunchedEffect
        kotlinx.coroutines.delay(CARD_AFTER_MS)
        cardFor = f
    }
    // Surprise me: the poster to go to once the wall has it.
    var goTo by remember { mutableStateOf<Int?>(null) }

    BackHandler(enabled = panel == null && menuFor == null) { LauncherLibrary.index = 0; LauncherLibrary.closeShelf() }
    LaunchedEffect(m.version) { m.more(maxOf(120, LauncherLibrary.index + 60)) }
    // Near the end of what's held: the next stretch.
    LaunchedEffect(m.version) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (!m.done && last >= m.items.size - 4 * COLUMNS) m.more(m.items.size + 120) }
    }
    suspend fun focusAt(i: Int) {
        if (m.items.isEmpty()) return
        val idx = i.coerceIn(0, m.items.size - 1)
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) runCatching { gridState.scrollToItem(idx - idx % COLUMNS) }
        repeat(120) {
            awaitFrame()
            runCatching { cell(idx).requestFocus() }
            if (cellOn == idx) { landed = true; return }
        }
        landed = true
    }
    LaunchedEffect(m.version, count > 0) {
        if (count == 0 || (landed && (barOn || railOn))) return@LaunchedEffect
        focusAt(LauncherLibrary.index)
    }
    LaunchedEffect(focused?.key) { focused?.backdrop?.let { kotlinx.coroutines.delay(300); io.github.petebutty.viewarr.ui.theme.Wallpaper.title = it } }
    LaunchedEffect(goTo, m.version, count > 0) { val g = goTo ?: return@LaunchedEffect; if (count > 0) { goTo = null; focusAt(g.coerceAtMost(count - 1)) } }
    val shownLetter by remember(m.version) {
        derivedStateOf { m.items.getOrNull(gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0)?.title?.let { firstLetter(it) } }
    }
    fun jump(letter: Char, focus: Boolean) {
        scope.launch {
            LauncherLibrary.index = 0
            m.startAt(letter)
            m.more(120)
            if (focus) focusAt(0)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHead(cornerUp) {
                Column {
                    Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.Bottom) {
                        Text(shelf.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp))
                        Text(
                            shelf.sources + "  ·  " + when {
                                m.total < 0 && !m.done -> "Loading…"
                                m.total < 0 -> "Nothing here in this order"
                                else -> "%,d".format(m.total) + (if (shelf.type == MediaType.Show) " shows" else " films") + ", each shown once"
                            },
                            fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    // Across the top: search (these libraries only), the filters they share, then each library by itself
                    // (with its own sort and every filter it has).
                    Row(Modifier.padding(top = 10.dp).onFocusChanged { barOn = it.hasFocus }.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            io.github.petebutty.viewarr.ui.media.LibrarySearchBox(m.query.text, onQuery = { t ->
                                typing[0]?.cancel()
                                typing[0] = scope.launch { kotlinx.coroutines.delay(450); LauncherLibrary.index = 0; m.narrow(m.query.copy(text = t.trim())); m.more(120) }
                            }, onFocus = {}, onDone = { scope.launch { focusAt(0) } })
                        }
                        LazyRow(contentPadding = PaddingValues(end = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            // The order: by title, or the newest / the best of all of them together.
                            item(key = "all") {
                                AppPill(text = "A to Z", selected = m.order == MergedOrder.AZ, onClick = { reorder(MergedOrder.AZ) },
                                    modifier = Modifier.focusRequester(barFocus), height = 34.dp)
                            }
                            item(key = "added") { AppPill(text = "Recently added", selected = m.order == MergedOrder.Added, onClick = { reorder(MergedOrder.Added) }, height = 34.dp) }
                            item(key = "rated") { AppPill(text = "Top rated", selected = m.order == MergedOrder.Rated, onClick = { reorder(MergedOrder.Rated) }, height = 34.dp) }
                            if (m.query.active > 0) item(key = "clear") {
                                AppPill(text = "Clear filters  ✕", selected = false, outlined = true, height = 34.dp,
                                    onClick = { narrow(m.query.copy(toggles = emptySet(), picks = emptyMap())) })
                            }
                            item(key = "surprise") {
                                // A title picked at random from the ones you haven't watched, across these libraries: its
                                // page opens; Back is the wall again.
                                AppPill(text = "Surprise me", selected = false, height = 34.dp, onClick = {
                                    scope.launch {
                                        val pick = m.random()
                                        if (pick == null) { android.widget.Toast.makeText(context, "Nothing unwatched to pick from here", android.widget.Toast.LENGTH_SHORT).show(); return@launch }
                                        val show = pick.type == MediaType.Show
                                        movies.closeTitlePages(); shows.closeTitlePages()
                                        (if (show) shows else movies).open(pick)
                                        Launcher.openTitle(if (show) Page.Shows else Page.Movies)
                                        LauncherLibrary.keep()
                                        LauncherLibrary.surprise = { m.random() }
                                    }
                                })
                            }
                            items(m.groups.orEmpty(), key = { "f-" + it.label }) { g ->
                                if (g.toggle) AppPill(text = g.label, selected = g.label in m.query.toggles, height = 34.dp, onClick = {
                                    narrow(m.query.copy(toggles = if (g.label in m.query.toggles) m.query.toggles - g.label else m.query.toggles + g.label))
                                })
                                else {
                                    val picked = m.query.picks[g.label].orEmpty()
                                    AppPill(text = if (picked.isEmpty()) g.label else g.label + ": " + picked.first() + if (picked.size > 1) " +${picked.size - 1}" else "",
                                        selected = picked.isNotEmpty(), height = 34.dp, onClick = { scope.launch { m.lookUp(g.label) }; panel = g.label })
                                }
                            }
                            items(shelf.members, key = { "m-" + it.first.key }) { e ->
                                AppPill(text = "Only " + e.first.source, selected = false, height = 34.dp, onClick = {
                                    (if (e.second == MediaType.Show) shows else movies).openLibrary(e.first, e.second)
                                    LauncherLibrary.enter(e.first, e.second)
                                })
                            }
                        }
                    }
                    Text(
                        when {
                            m.done && count == 0 && (m.query.active > 0 || m.query.text.isNotEmpty()) -> "Nothing in these libraries matches."
                            m.done && count == 0 -> "Nothing in these libraries."
                            else -> focused?.let { "${it.title}   ${metaLine(it)}" } ?: " "
                        },
                        fontSize = 14.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 6.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
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
                // (A row of blanks at the foot while there's more to come: scrolling onto it brings the next stretch.)
                val shown = count + if (m.done) 0 else COLUMNS
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(COLUMNS),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds().graphicsLayer { translationY = glide.floatValue }
                            .focusProperties {
                                enter = {
                                    val on = gridState.layoutInfo.visibleItemsInfo.map { it.index }.filter { it < count }
                                    val want = LauncherLibrary.index
                                    if (on.isEmpty()) barFocus else cell(if (want in on) want else on.first())
                                }
                            }
                            .focusGroup(),
                        contentPadding = PaddingValues(start = 48.dp, end = if (letters) 12.dp else 48.dp, top = 4.dp, bottom = 60.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(shown, key = { it }) { i ->
                            val item = m.items.getOrNull(i)
                            val col = i % COLUMNS
                            val mod = Modifier.focusRequester(cell(i))
                                .onFocusChanged { if (it.isFocused) { if (landed) LauncherLibrary.index = i; cellOn = i; focused = item } else if (cellOn == i) cellOn = -1 }
                                .onPreviewKeyEvent { e ->
                                    if (letters && holdKey(e, holdLetter, holdAt, item?.title, { holdLetter = it }) { jump(it, focus = true) }) return@onPreviewKeyEvent true
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when {
                                        e.key == Key.DirectionRight && (col == COLUMNS - 1 || i == shown - 1) -> { if (letters) runCatching { railFocus.requestFocus() }; true }
                                        e.key == Key.DirectionLeft && col == 0 -> true
                                        e.key == Key.DirectionDown && i + COLUMNS < shown -> { slide(i, i + COLUMNS); false }
                                        e.key == Key.DirectionDown && i / COLUMNS < (shown - 1) / COLUMNS -> { scope.launch { focusAt(count - 1) }; true }
                                        e.key == Key.DirectionUp && i - COLUMNS >= 0 -> { slide(i, i - COLUMNS); false }
                                        e.key == Key.DirectionUp -> runCatching { barFocus.requestFocus() }.isSuccess
                                        else -> false
                                    }
                                }
                            if (item != null) Box { PosterCard(item = item, modifier = mod, fill = true, onLongClick = { menuFor = item }, onClick = {
                                val show = item.type == MediaType.Show
                                movies.closeTitlePages(); shows.closeTitlePages()
                                (if (show) shows else movies).open(item)
                                Launcher.openTitle(if (show) Page.Shows else Page.Movies)
                                LauncherLibrary.keep()
                            }); PosterMarks(item) }
                            else PlaceholderPoster(mod)
                        }
                    }
                }
                // (Not until the highlight has landed on a poster.)
                if (letters) Box(Modifier.focusProperties { canFocus = landed }) {
                    AlphabetRail(
                        letters = null,
                        shown = shownLetter,
                        focusRequester = railFocus,
                        onFocus = { railOn = it },
                        onMove = { railLetter = it },
                        onLetter = { jump(it, focus = false) },
                        onPick = { railOn = false; jump(it, focus = true) },
                        onUp = { if (runCatching { barFocus.requestFocus() }.isFailure) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up) },
                        onLeft = {
                            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
                            runCatching { cell((first + COLUMNS - 1).coerceAtMost((count - 1).coerceAtLeast(0))).requestFocus() }
                        },
                    )
                }
            }
        }
        // Moving along the A–Z strip: the letter, big, so you can see where you're going.
        if (railOn || holdLetter != null) Box(
            Modifier.align(Alignment.Center).size(140.dp).background(AppColors.Panel.copy(alpha = 0.92f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text((holdLetter ?: railLetter).toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, maxLines = 1, softWrap = false) }
        InfoCard(cardFor.takeIf { menuFor == null }, atEnd = cellOn < 0 || cellOn % COLUMNS < COLUMNS / 2, vm = movies)
        menuFor?.let { WallMenu(it, movies, shows) { menuFor = null } }
        // A filter's choices, from every library here.
        panel?.let { label ->
            io.github.petebutty.viewarr.ui.library.SidePanel(label, onClose = { panel = null }) { first ->
                val list = m.choices[label]
                val picked = m.query.picks[label].orEmpty()
                when {
                    list == null -> item(key = "loading") { io.github.petebutty.viewarr.ui.common.MenuItem(text = "Loading…", selected = false, onClick = {}, modifier = Modifier.focusRequester(first), dim = true) }
                    list.isEmpty() -> item(key = "none") { io.github.petebutty.viewarr.ui.common.MenuItem(text = "Nothing to pick from", selected = false, onClick = { panel = null }, modifier = Modifier.focusRequester(first), dim = true) }
                    else -> items(list, key = { "v-$it" }) { v ->
                        val on = picked.any { it.equals(v, ignoreCase = true) }
                        io.github.petebutty.viewarr.ui.common.MenuItem(text = v, trailing = if (on) "✓" else null, selected = on,
                            modifier = if (v == list.first()) Modifier.focusRequester(first) else Modifier, onClick = {
                                val next = if (on) picked.filterNot { it.equals(v, ignoreCase = true) }.toSet() else picked + v
                                narrow(m.query.copy(picks = if (next.isEmpty()) m.query.picks - label else m.query.picks + (label to next)))
                            })
                    }
                }
            }
        }
    }
}

private val LETTERS = listOf('#') + ('A'..'Z')

/**
 * Holding Down / Up on a wall in A to Z order: after about a second the letter comes up ([set]) and the hold runs
 * through the letters instead of the rows; letting go goes to that letter ([go]). True: the press was used here.
 */
internal fun holdKey(e: androidx.compose.ui.input.key.KeyEvent, letter: Char?, at: LongArray, title: String?, set: (Char?) -> Unit, go: (Char) -> Unit): Boolean {
    val down = e.key == Key.DirectionDown
    if (!down && e.key != Key.DirectionUp) return false
    if (e.type == KeyEventType.KeyUp) {
        val l = letter ?: return false
        set(null); go(l)
        return true
    }
    if (e.type != KeyEventType.KeyDown) return false
    val now = android.os.SystemClock.uptimeMillis()
    if (letter == null) {
        if (e.nativeKeyEvent.repeatCount < 8) return false
        at[0] = now
        set(title?.let { firstLetter(it) } ?: 'A')
        return true
    }
    if (now - at[0] >= 170) {
        at[0] = now
        val i = LETTERS.indexOf(letter)
        set(LETTERS[(i + if (down) 1 else -1).coerceIn(0, LETTERS.lastIndex)])
    }
    return true
}
