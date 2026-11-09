package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.ui.theme.appBackground

import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import io.github.petebutty.viewarr.ui.common.trapFocus
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import io.github.petebutty.viewarr.ui.library.AlphabetRail
import io.github.petebutty.viewarr.ui.library.FilterChip
import io.github.petebutty.viewarr.ui.library.FilterPanel
import io.github.petebutty.viewarr.ui.library.SortPanel
import io.github.petebutty.viewarr.ui.library.ToolButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.platform.LocalDensity
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.data.settings.RowAlign
import io.github.petebutty.viewarr.data.settings.RowLeft
import io.github.petebutty.viewarr.data.settings.RowMemory
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.StreamOption
import io.github.petebutty.viewarr.ui.theme.AppColors
import coil.compose.AsyncImage
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay

/** Movies or Shows: a hero for the focused title over Netflix-style poster rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaScreen(
    type: MediaType,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    onOpenMenu: () -> Unit,
    onBack: () -> Unit = onOpenMenu,
    /** Search: shown instead of the hero, and the rows are search results (no automatic loading or focus grab). */
    searchHeader: (@Composable () -> Unit)? = null,
    /** Search: shown after the result rows (live TV results, when the search isn't for a channel). */
    searchFooter: (@Composable () -> Unit)? = null,
    vm: MediaViewModel = hiltViewModel(key = if (searchHeader != null) "media-search" else "media-${type.name}"),
) {
    val remote = LocalRemote.current
    val state by vm.rows.collectAsStateWithLifecycle()
    val jump by vm.jump.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val playingStream by vm.playing.collectAsStateWithLifecycle()
    // The player itself is above every page (VodHost), so it can shrink to a corner. Shrunk, this page is in use again.
    val vodSource = remember(vm) { VodSource(vm.playing, vm.playerArt, vm::stopPlaying, vm::reportProgress) }
    LaunchedEffect(playingStream) { if (playingStream != null) VodHost.show(vodSource) }
    val playing = playingStream.takeIf { !VodHost.mini }
    // A whole library being browsed (menu → Movies / Shows → a library), shown instead of the rows.
    val browse by vm.browse.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    var lastCard by remember { mutableStateOf<String?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    // Waiting a moment before swapping the hero keeps fast scrolling smooth on the weak box.
    val hero by produceState<MediaItem?>(null, focused) {
        delay(250)
        value = focused
    }

    LaunchedEffect(type) { if (searchHeader == null) vm.load(type) }
    // Typing in the library's search box: results arriving mustn't take focus (and the keyboard) away.
    var librarySearching by remember { mutableStateOf(false) }
    // The library grid looks after its own focus (first title, the one you came back to, A–Z jumps).
    // Rows arrive after the screen opens: grab focus once there's something to focus.
    LaunchedEffect(state.rows.isNotEmpty(), focusEnabled, detail == null) {
        if (searchHeader != null || !focusEnabled || detail != null || state.rows.isEmpty() || hasFocus) return@LaunchedEffect
        awaitFrame()
        awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }

    // One scroll per move: the list itself puts the focused row (title included) at the top. Previously a second,
    // manual scroll ran after the built-in one and the rows jumped up and down.
    // Sideways: keep the focused poster at the row's left edge (no half-cut poster on the left).
    val leftPad = with(LocalDensity.current) { 48.dp.toPx() }
    val defaultSpec = LocalBringIntoViewSpec.current
    val leftSpec = remember(leftPad) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val d = offset - leftPad
                return if (kotlin.math.abs(d) < 1f) 0f else d
            }
        }
    }
    val rowSpec = if (remote.rowAlign == RowAlign.Free) defaultSpec else leftSpec
    // Each row remembers its scroll and focused poster, even after it scrolls out of view and is disposed.
    val rowStates = remember(type) { HashMap<String, LazyListState>() }
    val rowPos = remember(type) { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    val titleSpace = with(LocalDensity.current) { 28.dp.toPx() }
    val topAligned = remember(titleSpace) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val d = offset - titleSpace
                return if (kotlin.math.abs(d) < 1f) 0f else d
            }
        }
    }

    val rows = state.rows
    // Where focus is in the rows, for Back (Netflix): first back to the top row's first poster, then leave.
    var focusedRow by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var focusedIndex by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var quick by remember { mutableStateOf<MediaItem?>(null) }
    var quickTrailer by remember { mutableStateOf<MediaItem?>(null) }
    val personShown by vm.person.collectAsStateWithLifecycle()
    BackHandler(enabled = focusEnabled && detail == null && quick == null && quickTrailer == null) {
        // In a library: Back returns to the page's rows.
        if (browse != null) {
            vm.openLibrary(null, type)
            scope.launch { awaitFrame(); awaitFrame(); runCatching { focusRequester.requestFocus() } }
            return@BackHandler
        }
        // Search, a person's films and shows: Back to the results.
        if (personShown != null) {
            vm.closePerson()
            lastCard = null
            scope.launch { listState.scrollToItem(0); awaitFrame(); awaitFrame(); runCatching { focusRequester.requestFocus() } }
            return@BackHandler
        }
        val top = rows.firstOrNull()
        if (searchHeader == null && hasFocus && top != null && (focusedRow > 0 || focusedIndex > 0)) {
            rowPos[top.id] = 0
            top.items.firstOrNull()?.let { lastCard = cardId(top, it) }
            scope.launch {
                listState.scrollToItem(0)
                rowStates[top.id]?.scrollToItem(0)
                awaitFrame(); awaitFrame()
                runCatching { focusRequester.requestFocus() }
            }
        } else onBack()
    }
    // A person's page: their first film is selected as soon as the rows arrive (focus was nowhere until a key).
    LaunchedEffect(personShown, rows.isNotEmpty()) {
        if (personShown == null || rows.isEmpty() || detail != null) return@LaunchedEffect
        lastCard = rows.first().items.firstOrNull()?.let { cardId(rows.first(), it) }
        runCatching { listState.scrollToItem(0) }
        awaitFrame(); awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(jump, rows.size) {
        val (rowId, _) = jump ?: return@LaunchedEffect
        val index = if (rowId == null) 0 else rows.indexOfFirst { it.id == rowId }
        val row = rows.getOrNull(index) ?: return@LaunchedEffect
        listState.scrollToItem(index)
        row.items.firstOrNull()?.let { lastCard = cardId(row, it) }
        awaitFrame()
        awaitFrame()
        runCatching { focusRequester.requestFocus() }
        vm.jumpDone()
    }
    // Back from a title opened from the rows: straight back to that poster. Without this, focus fell to whichever
    // poster grabbed it first as the title page went (the first card of the row above) and stayed there.
    var openedCard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(detail == null) {
        if (detail != null || browse != null) return@LaunchedEffect
        val key = openedCard ?: return@LaunchedEffect
        openedCard = null
        val rowIndex = rows.indexOfFirst { r -> r.items.any { cardId(r, it) == key } }
        if (rowIndex < 0) return@LaunchedEffect
        lastCard = key
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == rowIndex }) runCatching { listState.scrollToItem(rowIndex) }
        awaitFrame(); awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }
    val entryCard = lastCard?.takeIf { key -> rows.any { r -> r.items.any { cardId(r, it) == key } } }
        ?: rows.firstOrNull()?.let { r -> r.items.firstOrNull()?.let { cardId(r, it) } }
    // Rows keep arriving after the page opens (slow servers, many libraries) and can be put in above the one you're
    // on, which drops focus: the remote then does nothing until a direction is pressed. Take it back by itself.
    // A row that arrives late can land above the top of the list (out of sight): while you're not in the rows yet,
    // keep the list at its top so the first row (e.g. search's "In your library") is the one you see.
    LaunchedEffect(rows.firstOrNull()?.id) { if (!hasFocus && rows.isNotEmpty()) runCatching { listState.scrollToItem(0) } }
    val upNextShown by vm.upNext.collectAsStateWithLifecycle()
    LaunchedEffect(hasFocus, focusEnabled, detail == null, quick == null, quickTrailer == null, playing == null, rows.size, upNextShown == null) {
        if (searchHeader != null || upNextShown != null || hasFocus || !focusEnabled || detail != null || quick != null || quickTrailer != null || playing != null) return@LaunchedEffect
        if (rows.isEmpty() || browse != null) return@LaunchedEffect
        delay(400)
        if (hasFocus) return@LaunchedEffect
        // The poster that takes focus has to be on screen: bring its row in first.
        val rowIndex = rows.indexOfFirst { r -> r.items.any { cardId(r, it) == entryCard } }.coerceAtLeast(0)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == rowIndex }) runCatching { listState.scrollToItem(rowIndex) }
        awaitFrame(); awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }

    // Movies and Shows: the highlighted title's artwork fills the page, whatever the wallpaper setting.
    if (searchHeader == null) androidx.compose.runtime.DisposableEffect(Unit) {
        io.github.petebutty.viewarr.ui.theme.Wallpaper.titlePages++
        onDispose { io.github.petebutty.viewarr.ui.theme.Wallpaper.titlePages-- }
    }
    Box(Modifier.fillMaxSize().then(if (searchHeader != null) Modifier else Modifier.appBackground(titleArt = true))) {
        // A library's grid has no hero: the background follows the highlighted title here too (it kept the last
        // page's artwork, e.g. a show's on the films' grid).
        val gridArt = focused?.takeIf { browse != null }?.backdrop
        LaunchedEffect(gridArt) { gridArt?.let { io.github.petebutty.viewarr.ui.theme.Wallpaper.title = it } }
        browse?.let { b ->
            LibraryGrid(
                b = b,
                vm = vm,
                focusRequester = focusRequester,
                focusEnabled = focusEnabled && detail == null && playing == null && quick == null,
                focused = focused,
                onFocus = { item -> focused = item },
                onHasFocus = { hasFocus = it },
                searching = librarySearching,
                onSearchFocus = { librarySearching = it },
                onOpen = { vm.open(it) },
                onLong = { quick = it },
                onLeftEdge = onOpenMenu,
            )
        }
        if (browse == null) Column(Modifier.fillMaxSize()) {
            val personInfo by vm.personInfo.collectAsStateWithLifecycle()
            val pi = personInfo?.takeIf { personShown != null }
            if (pi != null) PersonHeader(pi) else if (searchHeader != null) searchHeader() else Hero(
                item = hero ?: rows.firstOrNull()?.items?.firstOrNull(),
                type = type,
                // Only the poster you've stopped on plays: not the first-row fallback, nor while anything covers the rows.
                trailerItem = hero,
                trailerActive = focusEnabled && hasFocus && detail == null && playing == null,
                trailers = vm.trailers,
                librarySourcesOf = vm::librarySources,
                logoOf = vm::logoFor,
                ratingsOf = vm::ratingsFor,
            )
            when {
                searchHeader != null && rows.isEmpty() && state.done -> searchFooter?.invoke()
                rows.isEmpty() && !state.done -> Text(
                    if (searchHeader != null) "Searching…" else "Loading ${if (type == MediaType.Movie) "films" else "shows"}…",
                    color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp),
                )
                rows.isEmpty() -> Text(
                    "Nothing to show yet. Connect Plex, TMDB, Trakt or a Stremio addon in Settings → Accounts & sources.",
                    color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp, end = 48.dp),
                )
                else -> CompositionLocalProvider(LocalBringIntoViewSpec provides topAligned, LocalButtonsFree provides (detail == null && playing == null)) { LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).onFocusChanged { hasFocus = it.hasFocus },
                    // Room below the last row so it can also scroll up to the top (no half row left above it).
                    contentPadding = PaddingValues(bottom = 150.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(rows, key = { _, it -> it.id }) { index, row ->
                        PosterRow(
                            row = row,
                            rowSpec = rowSpec,
                            state = rowStates.getOrPut(row.id) { LazyListState() },
                            entryIndex = if (remote.rowMemory == RowMemory.First) 0 else (rowPos[row.id] ?: 0).coerceAtMost(row.items.lastIndex),
                            resetOnLeave = remote.rowMemory == RowMemory.First,
                            onFocusIndex = {
                                rowPos[row.id] = it
                                focusedRow = index
                                focusedIndex = it
                                vm.setCurrentRow(row.id)
                            },
                            onLongOpen = { if (!it.key.startsWith("person:")) quick = it },
                            onLeftEdge = if (remote.rowLeft == RowLeft.Menu) onOpenMenu else null,
                            entryCard = entryCard,
                            entryFocus = focusRequester,
                            onFocus = { item, id ->
                                focused = item
                                lastCard = id
                            },
                            onOpen = { openedCard = lastCard; vm.open(it) },
                        )
                    }
                    searchFooter?.let { footer -> item(key = "search-footer") { footer() } }
                } }
            }
        }
        // While a film plays, the title page (and its stream list) is set aside so it can't hold the remote.
        if (playing == null) detail?.let { d ->
            DetailScreen(
                ui = d,
                vm = vm,
                active = playing == null,
                onPlayPlex = vm::playPlex,
                onSearch = vm::searchAddons,
                onPickStream = vm::playStream,
                onCloseStreams = vm::closeStreams,
                onWatchlist = vm::toggleWatchlist,
                onClose = {
                    vm.close()
                    // Back to the title it was opened from (More like this) keeps focus on that page.
                    if (vm.detail.value == null) runCatching { focusRequester.requestFocus() }
                },
            )
        }
        if (playing == null && detail == null) quick?.let { item ->
            QuickOptions(
                item = item,
                vm = vm,
                onTrailer = { quick = null; quickTrailer = item },
                onClose = {
                    quick = null
                    scope.launch { awaitFrame(); runCatching { focusRequester.requestFocus() } }
                },
            )
        }
        quickTrailer?.let { item ->
            TrailerPlayer(item = item, trailers = vm.trailers, onClose = {
                quickTrailer = null
                scope.launch { awaitFrame(); runCatching { focusRequester.requestFocus() } }
            })
        }
        val notice by vm.notice.collectAsStateWithLifecycle()
        notice?.let { text ->
            LaunchedEffect(text) { delay(3_000); vm.clearNotice() }
            Text(
                text, fontSize = 16.sp, color = AppColors.TextPrimary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .background(AppColors.Panel.copy(alpha = 0.95f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
        val upNext by vm.upNext.collectAsStateWithLifecycle()
        if (playing == null) upNext?.let { UpNextOverlay(it.episode, onPlay = vm::playUpNext, onCancel = vm::cancelUpNext) }
    }
}

private fun cardId(row: MediaRow, item: MediaItem) = row.id + "|" + item.key

@Composable
private fun Hero(
    item: MediaItem?,
    type: MediaType,
    trailerItem: MediaItem? = null,
    trailerActive: Boolean = false,
    trailers: io.github.petebutty.viewarr.data.trailers.TrailerRepository? = null,
    /** Where a title from an addon row really is, e.g. "Plex" (AIOStreams can pass on your own library). */
    librarySourcesOf: (suspend (MediaItem) -> String?)? = null,
    logoOf: (suspend (MediaItem) -> String?)? = null,
    ratingsOf: (suspend (MediaItem) -> List<io.github.petebutty.viewarr.data.media.RatingBadge>)? = null,
) {
    val inLibrary by produceState<String?>(null, item?.key) {
        value = null
        val i = item ?: return@produceState
        if (librarySourcesOf != null && i.origin !in LIBRARY_ORIGINS && !i.origin.startsWith("Plex")) value = librarySourcesOf(i)
    }
    // The Movies / Shows background: the highlighted title's backdrop.
    LaunchedEffect(item?.backdrop) { item?.backdrop?.let { io.github.petebutty.viewarr.ui.theme.Wallpaper.title = it } }
    // On the Movies and Shows pages the artwork fills the whole page (drawn by the page's background), so the
    // hero doesn't draw its own copy (it left a seam where it ended).
    val pageArt = io.github.petebutty.viewarr.ui.theme.Wallpaper.titlePages > 0
    Box(Modifier.fillMaxWidth().height(232.dp)) {
        if (!pageArt) item?.backdrop?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.68f),
            )
        }
        // Over a wallpaper the hero blends into it (see-through) rather than into a solid colour.
        val bg = AppColors.Scrim
        if (!pageArt) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.3f to bg, 0.55f to bg.copy(alpha = bg.alpha * 0.55f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to bg)))
        }
        // Picture-in-picture trailer over the right of the backdrop, clear of the title and overview.
        if (trailers != null) TrailerPreview(
            item = trailerItem,
            active = trailerActive,
            trailers = trailers,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 48.dp)
                .height(200.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.padding(start = 48.dp, top = 26.dp, end = 48.dp).fillMaxWidth(0.5f)) {
            Text(if (type == MediaType.Movie) "Movies" else "Shows", fontSize = 14.sp, color = AppColors.Accent)
            Spacer(Modifier.height(8.dp))
            if (item != null) {
                TitleLogo(item, logoOf, height = 64.dp, maxWidth = 360.dp, fontSize = 26, waitMs = 300)
                Spacer(Modifier.height(6.dp))
                Text(
                    inLibrary?.let { where -> metaLine(item.copy(origin = "$where · via ${item.origin}")) } ?: metaLine(item),
                    fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1,
                )
                // IMDb / Rotten Tomatoes / Trakt for the highlighted title (after a short rest on it).
                val heroRatings by produceState<List<io.github.petebutty.viewarr.data.media.RatingBadge>>(emptyList(), item.key) {
                    value = emptyList()
                    if (ratingsOf == null) return@produceState
                    // All but TMDB's switched off (Settings → Appearance → Ratings): nothing to fetch.
                    val tp = io.github.petebutty.viewarr.ui.theme.TitleParts
                    if (AppColors.palette.lowMemory || tp.ratings.none { (src, _) -> src != "TMDB" && AppColors.palette.shows(tp.rating(src)) }) return@produceState
                    delay(600)
                    value = ratingsOf(item)
                }
                if (heroRatings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    RatingsLine(null, null, heroRatings)
                }
                item.subtitle?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 14.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                item.overview?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private const val GRID_COLUMNS = 7

/**
 * A whole library as a poster grid, one page at a time (so a big library never weighs on the box), with the sort
 * choices along the top. The last card goes to the next page, the first (after page 1) to the previous one.
 */
@Composable
private fun LibraryGrid(
    b: MediaViewModel.LibraryBrowse,
    vm: MediaViewModel,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    focused: MediaItem?,
    onFocus: (MediaItem) -> Unit,
    onHasFocus: (Boolean) -> Unit,
    searching: Boolean,
    onSearchFocus: (Boolean) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onLong: (MediaItem) -> Unit,
    onLeftEdge: () -> Unit,
) {
    val gridState = remember(b.library.key, b.version) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    val cells = remember(b.library.key, b.version) { HashMap<Int, FocusRequester>() }
    fun cell(i: Int) = cells.getOrPut(i) { FocusRequester() }
    var panel by remember { mutableStateOf<String?>(null) }
    val railFocus = remember { FocusRequester() }
    var railOn by remember { mutableStateOf(false) }
    var railLetter by remember { mutableStateOf('A') }
    val count = b.total.coerceAtLeast(0)

    // Whatever's on screen (and a little either side) is loaded; stretches far away are let go.
    LaunchedEffect(b.version) {
        androidx.compose.runtime.snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.let { v -> (v.firstOrNull()?.index ?: 0) to (v.lastOrNull()?.index ?: 0) }
        }.collect { (f, l) -> vm.ensureRange(f, l) }
    }
    suspend fun focusAt(i: Int) {
        if (count == 0) return
        val idx = i.coerceIn(0, count - 1)
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) runCatching { gridState.scrollToItem(idx - idx % GRID_COLUMNS) }
        awaitFrame(); awaitFrame()
        runCatching { cell(idx).requestFocus() }
    }
    // A new list (opened, re-sorted, filtered): its first title. Back from a title, a panel or the menu: the one you were on.
    LaunchedEffect(b.version, count > 0, focusEnabled, panel == null) {
        if (!focusEnabled || panel != null || count == 0 || searching || railOn) return@LaunchedEffect
        focusAt(vm.lastIndexFor(b.version) ?: 0)
    }
    // A–Z: the grid moves to the letter; picked with OK on the strip, focus goes there too.
    LaunchedEffect(b.jump) {
        val j = b.jump ?: return@LaunchedEffect
        if (railOn) runCatching { gridState.scrollToItem(j - j % GRID_COLUMNS) } else focusAt(j)
        vm.setLastIndex(b.version, j)
        vm.letterJumpDone()
    }
    // The letter of the first title on screen (lit on the strip while you scroll).
    // (Read through the latest state: the list's stretches arrive after the grid is first drawn.)
    val current by androidx.compose.runtime.rememberUpdatedState(b)
    val shownLetter by androidx.compose.runtime.remember(b.version) {
        androidx.compose.runtime.derivedStateOf {
            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            current.item(first)?.title?.let { firstLetter(it) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.library.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sortLabel = b.options?.sorts?.firstOrNull { it.id == b.query.sort }?.label
                    Text(
                        when {
                            b.failed -> "The server didn't answer. Press Back and try again."
                            b.total < 0 -> "Loading…"
                            else -> listOfNotNull(
                                b.library.source,
                                "${"%,d".format(b.total)} ${if (b.filtered) "matches" else "titles"}",
                                sortLabel?.let { "$it ${if (b.query.desc) "↓" else "↑"}" },
                            ).joinToString("  ·  ")
                        },
                        fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1,
                    )
                }
                // Keyed by library: another library starts with an empty box.
                androidx.compose.runtime.key(b.library.key) {
                    LibrarySearchBox(b.query.query, vm::setLibraryQuery, onSearchFocus, onDone = { runCatching { cell(vm.lastIndexFor(b.version) ?: 0).requestFocus() } })
                }
                ToolButton(
                    "Sort" + (b.options?.sorts?.firstOrNull { it.id == b.query.sort }?.let { ": ${it.label}" } ?: ""),
                    active = false, modifier = Modifier.focusRequester(focusRequester),
                ) { if (b.options != null) panel = "sort" }
                if (b.options?.filters?.isNotEmpty() == true) ToolButton(
                    if (b.query.active > 0) "Filter · ${b.query.active}" else "Filter",
                    active = b.query.active > 0, modifier = Modifier.padding(start = 8.dp),
                ) { panel = "filter" }
            }
            // What's filtered, each removable; then the highlighted title.
            if (b.query.active > 0) androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                val groups = b.options?.filters.orEmpty()
                items(b.query.toggles.toList(), key = { "t-$it" }) { t ->
                    FilterChip(groups.firstOrNull { it.id == t }?.label ?: t) { vm.toggleFlag(t) }
                }
                b.query.filters.forEach { (g, vs) ->
                    items(vs, key = { "v-$g-${it.id}" }) { v -> FilterChip(v.label) { vm.toggleFilterValue(g, v) } }
                }
                item(key = "clear") { FilterChip("Clear all", clear = true) { vm.clearFilters() } }
            }
            Text(
                focused?.takeIf { f -> b.items.any { it.key == f.key } }?.let { "${it.title}   ${metaLine(it)}" } ?: " ",
                fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 6.dp),
            )
            when {
                b.total == 0 && b.query.query.isNotBlank() -> Text("Nothing in this library matches \"${b.query.query}\"", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp))
                b.total == 0 && b.filtered -> Text("Nothing in this library matches these filters", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp))
                b.total == 0 -> Text("This library is empty", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp))
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(GRID_COLUMNS),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxHeight().onFocusChanged { onHasFocus(it.hasFocus) },
                    contentPadding = PaddingValues(start = 48.dp, end = if (b.canLetters) 12.dp else 48.dp, top = 4.dp, bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(count, key = { it }) { i ->
                        val item = b.item(i)
                        val col = i % GRID_COLUMNS
                        val m = Modifier.focusRequester(cell(i))
                            .onFocusChanged { if (it.isFocused) { vm.setLastIndex(b.version, i); item?.let(onFocus) } }
                            .onPreviewKeyEvent { e ->
                                // Back in the grid: up to the toolbar (sort, filter, search), keeping your place; Back
                                // again from there leaves the library.
                                if (e.key == Key.Back) {
                                    if (e.type == KeyEventType.KeyUp) runCatching { focusRequester.requestFocus() }
                                    return@onPreviewKeyEvent true
                                }
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    // Left on the first column: the menu (pick another library).
                                    e.key == Key.DirectionLeft && col == 0 -> { onLeftEdge(); true }
                                    // Right at the end of a row: onto the A–Z strip.
                                    e.key == Key.DirectionRight && b.canLetters && (col == GRID_COLUMNS - 1 || i == count - 1) -> { runCatching { railFocus.requestFocus() }; true }
                                    else -> false
                                }
                            }
                        if (item != null) PosterCard(item = item, modifier = m, onClick = { onOpen(item) }, onLongClick = { onLong(item) }, fill = true)
                        else PlaceholderPoster(m)
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
                    onUp = { runCatching { focusRequester.requestFocus() } },
                    onLeft = {
                        val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
                        val target = (first + GRID_COLUMNS - 1).coerceAtMost(count - 1)
                        runCatching { cell(target).requestFocus() }
                    },
                )
            }
        }
        // Moving along the A–Z strip: the letter, big, so you can see where you're going.
        if (railOn) Box(
            Modifier.align(Alignment.Center).size(140.dp).background(AppColors.Panel.copy(alpha = 0.92f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(railLetter.toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent) }
        if (panel == "sort") b.options?.let { o -> SortPanel(o, b.query, onPick = { id -> val same = id == b.query.sort; vm.setLibrarySort(id); if (!same) panel = null }, onDesc = vm::setLibraryDesc, onClose = { panel = null }) }
        if (panel == "filter") b.options?.let { o ->
            FilterPanel(o, b.query, b.values, onOpenGroup = vm::loadFilterValues, onToggle = vm::toggleFlag, onValue = vm::toggleFilterValue, onClear = vm::clearFilters, onClose = { panel = null })
        }
    }
}

/** '#' for titles starting with a number or symbol, else the first letter (ignoring "The "). */
private fun firstLetter(title: String): Char {
    val t = title.trim().removePrefix("The ").removePrefix("the ").uppercase()
    val c = t.firstOrNull { !it.isWhitespace() } ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

/** A poster still on its way (the grid holds every position so you can scroll or jump anywhere). */
@Composable
private fun PlaceholderPoster(modifier: Modifier) {
    Surface(
        onClick = {},
        modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = RoundedCornerShape(8.dp))),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Panel.copy(alpha = 0.6f), focusedContainerColor = AppColors.Panel),
    ) {}
}








@Composable
private fun FilterPill(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) AppColors.TextPrimary else AppColors.Panel,
            contentColor = if (active) AppColors.Background else AppColors.TextSecondary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) { Text(text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) }
}

/**
 * Search within a library: a slim box. The keyboard opens only on OK (moving through doesn't pop it up); Search /
 * Done or Down returns to the titles.
 */
@Composable
private fun LibrarySearchBox(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit, onDone: () -> Unit) {
    var text by remember { mutableStateOf(query) }
    var editing by remember { mutableStateOf(false) }
    var focusedBox by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Voice: the mic before the box, as in the main search.
    val context = androidx.compose.ui.platform.LocalContext.current
    val boxFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val voiceScope = androidx.compose.runtime.rememberCoroutineScope()
    val voice = io.github.petebutty.viewarr.ui.common.rememberVoiceSearch(
        onResult = { said -> text = said; onQuery(said) },
        onKeyboard = {
            runCatching { boxFocus.requestFocus() }
            editing = true
            voiceScope.launch { kotlinx.coroutines.android.awaitFrame(); keyboard?.show() }
        },
    )
    run {
        Surface(
            onClick = { voice.toggle() },
            modifier = Modifier.padding(end = 8.dp).size(36.dp),
            shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.08f, pressedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
                focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.tv.material3.Icon(io.github.petebutty.viewarr.ui.common.AppIcons.Mic, contentDescription = "Voice search", modifier = Modifier.size(18.dp))
            }
        }
    }
    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = { text = it; onQuery(it) },
        singleLine = true,
        readOnly = !editing,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Accent),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = AppColors.TextPrimary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { editing = false; keyboard?.hide(); onDone() }),
        modifier = Modifier.padding(end = 8.dp).width(260.dp).height(36.dp)
            .focusRequester(boxFocus)
            .onFocusChanged { focusedBox = it.isFocused; onFocus(it.isFocused); if (!it.isFocused) { editing = false; keyboard?.hide() } }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                when {
                    ok && !editing -> { if (e.type == KeyEventType.KeyDown) { editing = true; keyboard?.show() }; true }
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> { editing = false; keyboard?.hide(); onDone(); true }
                    // Not typing: Left / Right move on (to the sort buttons) instead of through the text.
                    !editing && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right); true }
                    !editing && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft -> { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left); true }
                    else -> false
                }
            },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize()
                    .background(AppColors.Panel, RoundedCornerShape(50))
                    .border(if (focusedBox) 2.dp else 1.dp, if (focusedBox) AppColors.Accent else AppColors.TextSecondary.copy(alpha = 0.4f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(io.github.petebutty.viewarr.ui.common.AppIcons.Search, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (voice.listening) Text("Listening…", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1)
                    else if (text.isEmpty()) Text("Search this library", fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1)
                    inner()
                }
            }
        },
    )
}


/** Rows that already are your library (no need to look the title up). */
private val LIBRARY_ORIGINS = setOf("Plex", "Jellyfin", "Emby", "IPTV")

private fun metaLine(item: MediaItem) = listOfNotNull(
    item.year?.toString(),
    item.rating?.let { "★ %.1f".format(it) },
    item.origin,
).joinToString("  ·  ")

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PosterRow(
    row: MediaRow,
    rowSpec: BringIntoViewSpec,
    state: LazyListState,
    entryIndex: Int,
    onFocusIndex: (Int) -> Unit,
    onLeftEdge: (() -> Unit)?,
    resetOnLeave: Boolean,
    entryCard: String?,
    entryFocus: FocusRequester,
    onFocus: (MediaItem, String) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onLongOpen: (MediaItem) -> Unit = {},
) {
    Column {
        Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 48.dp, bottom = 6.dp))
        val rowEntry = remember { FocusRequester() }
        // True only while the remembered poster is actually composed. A poster can still be listed as visible while
        // its row is being torn down (e.g. leaving Movies for Shows); aiming focus at it then crashed the app
        // ("FocusRequester is not initialized").
        val entryAttached = remember { booleanArrayOf(false) }
        val scope = rememberCoroutineScope()
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) { LazyRow(
            state = state,
            // Moving onto this row from above or below lands on its remembered poster, not the nearest one on screen.
            modifier = Modifier
                .focusProperties {
                    // Only aim at the remembered poster if it's on screen; otherwise let focus pick normally.
                    enter = {
                        if (entryAttached[0] && state.layoutInfo.visibleItemsInfo.any { it.index == entryIndex }) rowEntry else FocusRequester.Default
                    }
                }
                .onFocusChanged { if (resetOnLeave && !it.hasFocus && state.firstVisibleItemIndex != 0) scope.launch { state.scrollToItem(0) } },
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(row.items, key = { _, it -> it.key }) { index, item ->
                val id = cardId(row, item)
                if (index == entryIndex) DisposableEffect(entryIndex) {
                    entryAttached[0] = true
                    onDispose { entryAttached[0] = false }
                }
                PosterCard(
                    item = item,
                    modifier = (if (id == entryCard) Modifier.focusRequester(entryFocus) else Modifier)
                        .then(if (index == entryIndex) Modifier.focusRequester(rowEntry) else Modifier)
                        // Left on a row's first poster opens the catalogue menu.
                        .then(if (index == 0 && onLeftEdge != null) Modifier.onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onLeftEdge(); true } else false
                        } else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                onFocus(item, id)
                                onFocusIndex(index)
                            }
                        },
                    onClick = { onOpen(item) },
                    onLongClick = { onLongOpen(item) },
                )
            }
        } }
    }
}

@Composable
private fun PosterCard(item: MediaItem, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit = {}, fill: Boolean = false) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        // In a grid ([fill]): as wide as its column, always 2:3 (a fixed size was squeezed to the column's width
        // but kept its height, so posters came out too wide).
        modifier = modifier.free().then(if (fill) Modifier.fillMaxWidth().aspectRatio(2f / 3f) else Modifier.size(width = 100.dp, height = 150.dp)),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = RoundedCornerShape(8.dp))),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Panel, focusedContainerColor = AppColors.Panel),
    ) {
        Box(Modifier.fillMaxSize()) {
            Text(
                item.title, fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(8.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            item.poster?.let { io.github.petebutty.viewarr.ui.common.PosterImage(it, item.title, Modifier.fillMaxSize()) }
            // Search: this one's in your library, and where ("Plex", "Jellyfin · IPTV").
            if (item.owned && AppColors.palette.sourceBadges) Text(
                item.ownedFrom ?: "In library",
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppColors.OnAccent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(AppColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            // A person (search): their name under the photo.
            if (item.key.startsWith("person:")) Text(
                item.title, fontSize = 11.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 4.dp, vertical = 3.dp),
            )
            item.progress?.takeIf { AppColors.palette.progressBadges }?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(AppColors.Accent))
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(
    ui: DetailUi,
    vm: MediaViewModel,
    active: Boolean,
    onPlayPlex: () -> Unit,
    onSearch: () -> Unit,
    onPickStream: (StreamOption) -> Unit,
    onCloseStreams: () -> Unit,
    onWatchlist: () -> Unit,
    onClose: () -> Unit,
) {
    val d = ui.detail
    val item = d.item
    val playFocus = remember { FocusRequester() }
    val onPlex = ui.copies?.any { it.source == "Plex" && it.ref != null } == true
    val onProvider = ui.copies?.any { it.source == io.github.petebutty.viewarr.data.media.PROVIDER } == true
    val serverName = ui.copies?.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }?.source
    val onServer = serverName != null
    val show = item.type == MediaType.Show
    val parts = AppColors.palette
    val trailerId by produceState<String?>(null, item.key, parts.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.TRAILER)) { value = if (parts.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.TRAILER)) vm.trailers.youtubeId(item) else null }
    var trailerOpen by remember { mutableStateOf(false) }
    // Other sources: every copy (each Plex server, Jellyfin / Emby, IPTV) and the addon search, to pick one by hand.
    var sourcesOpen by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    // Closed: back to Play (once the buttons can take focus again, a frame later).
    var tracksWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tracksOpen) {
        if (tracksOpen) { tracksWasOpen = true; return@LaunchedEffect }
        if (!tracksWasOpen) return@LaunchedEffect
        tracksWasOpen = false
        awaitFrame(); awaitFrame()
        runCatching { playFocus.requestFocus() }
    }
    if (sourcesOpen) SourcesDialog(ui, vm, onSearch = { sourcesOpen = false; onSearch() }, onDone = { sourcesOpen = false })
    val trailerFocus = remember { FocusRequester() }
    BackHandler(enabled = !trailerOpen) {
        when {
            ui.streams != null -> if (ui.episode != null) vm.closeEpisodeStreams() else onCloseStreams()
            ui.episode != null -> vm.pickEpisode(null)
            ui.episodesOpen -> vm.closeEpisodes()
            else -> onClose()
        }
    }
    // The first button changes once the library check finishes (Play on Plex appears), so focus again then.
    // ...but only if you're still up there: on a slow server you may already have moved down to the cast or tabs,
    // and the check finishing mustn't throw you back to Play.
    var pageFocused by remember { mutableStateOf(false) }
    var buttonsFocused by remember { mutableStateOf(false) }
    var lastMode by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(ui.copies != null, ui.hasAddons, ui.streams == null, active, ui.episodesOpen) {
        val mode = listOf(ui.streams == null, active, ui.episodesOpen)
        val onlyLoaded = mode == lastMode
        lastMode = mode
        if (ui.streams != null || !active || ui.episodesOpen || trailerOpen) return@LaunchedEffect
        if (onlyLoaded && pageFocused && !buttonsFocused) return@LaunchedEffect
        awaitFrame()
        runCatching { playFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(AppColors.Background).onFocusChanged { pageFocused = it.hasFocus }.trapFocus()) {
        item.backdrop?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.75f))
        }
        val bg = AppColors.Background
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.25f to bg, 0.6f to bg.copy(alpha = 0.6f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to bg)))
        // One screen: a compact header (poster, logo, details, ratings, description, buttons), then tabs with one
        // row under them (cast & crew, reviews, more like this, details), as on other TV media apps.
        var reading by remember { mutableStateOf<io.github.petebutty.viewarr.data.media.Review?>(null) }
        val tabFocus = remember { FocusRequester() }
        val sectionsFree = !ui.episodesOpen && ui.streams == null && !trailerOpen
        // Shows: the seasons and episodes come between the header and the tabs, so the page scrolls (focus leads it).
        val episodesEntry = remember { FocusRequester() }
        val inlineEpisodes = show && ui.seasons.isNotEmpty() && parts.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.EPISODES)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 56.dp, end = 40.dp, top = 24.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            item.poster?.let {
                io.github.petebutty.viewarr.ui.common.PosterImage(it, null, Modifier.size(width = 120.dp, height = 180.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(24.dp))
            }
            Column(Modifier.fillMaxWidth(0.68f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                TitleLogo(item, vm::logoFor, height = 54.dp, maxWidth = 320.dp, fontSize = 28)
                val meta = listOfNotNull(
                    item.year?.toString(),
                    d.certification,
                    d.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                    d.seasons.takeIf { it.isNotEmpty() }?.let { s -> "${s.size} season${if (s.size == 1) "" else "s"} · ${s.sumOf { it.episodes }} episodes" },
                    d.genres.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", "),
                ).joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RatingsLine(item.rating, d.voteCount, ui.ratings)
                item.subtitle?.takeIf { parts.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.UP_NEXT) }?.let { Text("Up next: $it", fontSize = 13.sp, color = AppColors.Accent, maxLines = 1) }
                item.overview?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary, maxLines = if (show) 2 else 3, overflow = TextOverflow.Ellipsis) }
                Text(
                    listOfNotNull(
                        d.directors.takeIf { it.isNotEmpty() }?.let { (if (show) "Created by " else "Directed by ") + it.take(2).joinToString(", ") },
                        when (val copies = ui.copies) {
                            null -> "Checking your library…"
                            else -> if (copies.isEmpty()) "Not in your library" else "In your library: " + copies.joinToString(" · ") { c -> c.detail?.let { "${c.source} ($it)" } ?: c.source }
                        },
                    ).joinToString("   ·   "),
                    fontSize = 13.sp,
                    color = if (ui.copies.isNullOrEmpty()) AppColors.TextSecondary else AppColors.Accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                // Pop-ups and the episodes panel sit on top: the buttons below must not take focus then.
                val buttonsFree = !ui.episodesOpen && ui.streams == null && !trailerOpen && !tracksOpen && !sourcesOpen
                androidx.compose.runtime.CompositionLocalProvider(LocalButtonsFree provides buttonsFree) {
                // The buttons may run past the text column into the free space on the right, rather than squash.
                // Down from any of them goes to the selected tab below (not just the nearest one).
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).onFocusChanged { buttonsFocused = it.hasFocus }.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && buttonsFree) runCatching { (if (inlineEpisodes && ui.episodes != null) episodesEntry else tabFocus).requestFocus() }.isSuccess else false
                }) {
                    if (show) {
                        // Play / Resume: continue watching's episode, else where Plex / Jellyfin / Trakt say you're up to,
                        // else S1 E1; from the best source (as a film's Play). Episodes for picking one.
                        val canPlay = onPlex || onServer || onProvider || ui.hasAddons
                        val next = item.nextSeason?.let { sn -> item.nextEpisode?.let { en -> Triple(sn, en, item.progress != null) } } ?: ui.nextUp
                        if (canPlay) {
                            val label = when {
                                next == null -> "Play S1 · E1"
                                next.third -> "Resume S${next.first} · E${next.second}"
                                else -> "Play S${next.first} · E${next.second}"
                            }
                            // Holding OK: pick the episode (and its source) yourself.
                            // The seasons and episodes are on the page itself now (below); holding OK still opens the full list.
                            Button(onClick = vm::playNextUp, onLongClick = vm::openEpisodes, modifier = (Modifier.focusRequester(playFocus)).free()) { Text(if (ui.searching) "Looking…" else label) }
                            // Part-way through that episode: or start it again.
                            if (next?.third == true) OutlinedButton(onClick = vm::playFromStart, modifier = Modifier.free()) { Text("From the start") }
                        } else {
                            Button(onClick = vm::openEpisodes, modifier = (Modifier.focusRequester(playFocus)).free()) { Text("Episodes") }
                        }
                    } else if (onPlex || onServer || onProvider || ui.hasAddons) {
                        // One Play: home servers, then away, then addons, then IPTV (MediaViewModel.playBest).
                        // OK plays from the best source; holding OK lists every source to pick from.
                        Button(onClick = vm::playBest, onLongClick = { sourcesOpen = true }, modifier = (Modifier.focusRequester(playFocus)).free()) {
                            Text(if (ui.searching) "Looking…" else if (item.progress != null) "Resume" else "Play")
                        }
                        // Part-way through: or watch it again from the beginning.
                        if (item.progress != null) OutlinedButton(onClick = vm::playFromStart, modifier = Modifier.free()) { Text("From the start") }
                    }
                    // Films on your own server: pick the soundtrack and subtitles before Play (this play only).
                    if (!show && ui.tracks != null) {
                        val picked = ui.pickedAudio != null || ui.pickedSubtitle != null
                        OutlinedButton(onClick = { tracksOpen = true }, modifier = Modifier.free()) { Text(if (picked) "Audio & subtitles ✓" else "Audio & subtitles") }
                    }
                    // Films: watched / not, on your servers and Trakt.
                    if (!show && ui.watched != null && (!ui.copies.isNullOrEmpty() || ui.hasTrakt)) {
                        OutlinedButton(onClick = vm::toggleWatched, modifier = Modifier.free()) { Text(if (ui.watched) "Watched ✓" else "Mark watched") }
                    }
                    if (!show && !onPlex && !onProvider && !onServer && !ui.hasAddons && ui.copies != null) {
                        OutlinedButton(onClick = onClose, modifier = (Modifier.focusRequester(playFocus)).free()) { Text("Back") }
                    }
                    if (trailerId != null) {
                        OutlinedButton(onClick = { trailerOpen = true }, modifier = Modifier.focusRequester(trailerFocus).free()) { Text("Trailer") }
                    }
                    if (ui.hasTrakt) {
                        OutlinedButton(onClick = onWatchlist, modifier = Modifier.free()) { Text(if (ui.watchlisted) "Remove from watchlist" else "Add to watchlist") }
                    }
                    // Seerr: ask for it to be kept (Radarr / Sonarr fetch it), or show it's already on the way.
                    val seerrState by produceState<io.github.petebutty.viewarr.data.remote.services.SeerrService.Status?>(null, ui.detail.item.key, ui.message) { value = vm.seerrStatus(ui.detail.item) }
                    // Not for something you already have.
                    if (ui.copies.isNullOrEmpty()) seerrState?.takeIf { it != io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.Available }?.let { st ->
                        val canAsk = st == io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.None ||
                            st == io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.Partial
                        OutlinedButton(onClick = { if (canAsk) vm.requestOnSeerr(ui.detail.item) }, modifier = Modifier.free()) {
                            Text(if (canAsk) "Request" else if (st == io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.Available) "On Seerr: available" else "Requested")
                        }
                    }
                }
                }
                if (!ui.episodesOpen) ui.message?.let { Text(it, fontSize = 14.sp, color = AppColors.Accent) }
                if (!onPlex && !onProvider && !onServer && !ui.hasAddons && ui.copies != null) {
                    Text("Connect Plex or a Stremio addon in Settings → Accounts & sources to play this.", fontSize = 13.sp, color = AppColors.TextSecondary)
                }
            }
        }
        if (inlineEpisodes) InlineEpisodes(ui, vm, free = sectionsFree && ui.episode == null, entry = episodesEntry, up = playFocus, down = tabFocus)
        Spacer(Modifier.height(16.dp))
        DetailTabs(d, show, sectionsFree && ui.episode == null, tabFocus, onPerson = { c -> vm.openCastMember(c) }, onReview = { reading = it }, onSimilar = { vm.open(it) })
        }
        reading?.let { r -> ReviewDialog(r) { reading = null } }
        if (ui.episodesOpen) EpisodesPanel(ui, vm, onPlex)
        else if (ui.streams.isNullOrEmpty()) EpisodeActionsFor(ui, vm)
        ui.streams?.takeIf { it.isNotEmpty() }?.let { StreamPicker(it, onPickStream, Modifier.align(Alignment.CenterEnd)) }
        ui.tracks?.takeIf { tracksOpen }?.let { t -> TracksDialog(t, ui.pickedAudio, ui.pickedSubtitle, onPick = { a, sub -> vm.pickTracks(a, sub) }, onDone = { tracksOpen = false }) }
        if (trailerOpen) {
            TrailerPlayer(item = item, trailers = vm.trailers, onClose = { trailerOpen = false })
            DisposableEffect(Unit) {
                onDispose { trailerFocus.let { f -> android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { f.requestFocus() } } } }
            }
        }
    }
}

/** Seasons along the top (Left/Right), episodes below. OK on an episode → Play on Plex / Search addons. */
/**
 * Episodes: the show's artwork fills the screen. On the left, the title and the highlighted episode's details;
 * on the right, seasons and a compact episode list. OK on an episode → Play on Plex / Search addons.
 */
@Composable
private fun EpisodesPanel(ui: DetailUi, vm: MediaViewModel, onPlex: Boolean) {
    val item = ui.detail.item
    // Watched on Plex, Jellyfin / Emby, or anywhere Trakt knows about.
    fun watched(s: Int, e: Int) = ui.plexEpisodes[s to e]?.watched == true || ui.serverEpisodes[s to e]?.watched == true || (s to e) in ui.traktEpisodes
    val episodeFocus = remember { FocusRequester() }
    val target = ui.episodes?.let { eps ->
        val next = if (item.nextSeason == ui.season) eps.indexOfFirst { it.number == item.nextEpisode } else -1
        if (next >= 0) next else eps.indexOfFirst { e -> !watched(e.season, e.number) }.coerceAtLeast(0)
    } ?: -1
    var highlighted by remember(ui.season) { mutableStateOf<io.github.petebutty.viewarr.data.media.EpisodeInfo?>(null) }
    val shown = highlighted ?: ui.episodes?.getOrNull(target)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Nothing behind an open pop-up or stream list may take focus.
    val panelFree = ui.episode == null && ui.streams == null
    // True while you're moving along the seasons: a season's episodes arriving mustn't pull you down into them.
    var onSeasons by remember { mutableStateOf(false) }
    // The season you're viewing: Up from the top episode goes to its pill (not whichever pill is nearest).
    val seasonFocus = remember { FocusRequester() }
    val seasonRow = androidx.compose.foundation.lazy.rememberLazyListState()
    // Moving along the pills: a season loads once you pause on it, not every pill passed on the way.
    var pendingSeason by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingSeason) {
        val n = pendingSeason ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        vm.selectSeason(n)
    }
    // Keep the viewed season's pill in view when you're not on the row (so Up can reach it).
    LaunchedEffect(ui.season, ui.seasons.size) {
        if (onSeasons) return@LaunchedEffect
        val idx = ui.seasons.indexOfFirst { it.number == ui.season }
        if (idx >= 0) runCatching { seasonRow.scrollToItem(maxOf(0, idx - 1)) }
    }
    // While the next season loads, the last list stays (faded) instead of flashing "Loading episodes…".
    var lastEpisodes by remember { mutableStateOf<List<io.github.petebutty.viewarr.data.media.EpisodeInfo>?>(null) }
    LaunchedEffect(ui.episodes) { ui.episodes?.let { lastEpisodes = it } }
    LaunchedEffect(ui.season, ui.episodes != null, ui.episode == null, ui.streams == null) {
        if (ui.episodes.isNullOrEmpty() || ui.episode != null || ui.streams != null || target < 0) return@LaunchedEffect
        listState.scrollToItem(maxOf(0, target - 1))
        if (onSeasons) return@LaunchedEffect
        awaitFrame()
        runCatching { episodeFocus.requestFocus() }
    }
    val bg = AppColors.Background
    Box(Modifier.fillMaxSize().background(bg).trapFocus()) {
        (shown?.still?.takeIf { false } ?: item.backdrop)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Artwork stays clear on the left; the list side darkens for readability.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = 0.55f), 0.38f to bg.copy(alpha = 0.35f), 0.55f to bg.copy(alpha = 0.85f), 1f to bg.copy(alpha = 0.95f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to bg.copy(alpha = 0.8f))))
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 40.dp, top = 40.dp, bottom = 24.dp)) {
            // Left: show title + the highlighted episode, at the top where it's read first.
            Column(Modifier.weight(0.42f).fillMaxHeight().padding(end = 32.dp), verticalArrangement = Arrangement.Top) {
                TitleLogo(item, vm::logoFor, height = 80.dp, maxWidth = 400.dp, fontSize = 30)
                shown?.let { ep ->
                    val plex = ui.plexEpisodes[ep.season to ep.number]
                    Spacer(Modifier.height(10.dp))
                    Text("S${ep.season} · E${ep.number}  ${ep.name}", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = AppColors.Accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val tags = listOfNotNull(
                        ep.airDate?.takeIf { it.isNotBlank() },
                        ep.runtimeMin?.takeIf { it > 0 }?.let { "${it}m" },
                        if (plex != null) "In Plex" else null,
                        if (watched(ep.season, ep.number)) "Watched" else null,
                    ).joinToString("  ·  ")
                    if (tags.isNotEmpty()) Text(tags, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    ep.overview?.let { Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = AppColors.TextPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
                }
                Spacer(Modifier.height(24.dp))
            }
            // Right: seasons + compact episodes.
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                LazyRow(Modifier.onFocusChanged { onSeasons = it.hasFocus }, state = seasonRow, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.seasons, key = { it.number }) { season ->
                        val selected = season.number == ui.season
                        Surface(
                            onClick = { pendingSeason = null; vm.selectSeason(season.number) },
                            modifier = (if (selected) Modifier.focusRequester(seasonFocus) else Modifier)
                                .focusProperties { canFocus = panelFree }
                                // Only a real move onto a season changes it, never focus passing through behind a pop-up.
                                .onFocusChanged { if (it.isFocused && panelFree) pendingSeason = season.number },
                            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                // The season you're viewing stands out even when you're down in its episodes.
                                containerColor = if (selected) AppColors.Accent.copy(alpha = 0.35f) else AppColors.Panel.copy(alpha = 0.8f),
                                contentColor = AppColors.TextPrimary,
                                focusedContainerColor = AppColors.Accent,
                                focusedContentColor = AppColors.OnAccent,
                            ),
                        ) {
                            Text(season.name, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val loading = ui.episodes == null
                val eps = ui.episodes ?: lastEpisodes
                when {
                    eps == null -> Text("Loading episodes…", color = AppColors.TextSecondary)
                    eps.isEmpty() -> Text("No episodes listed for this season.", color = AppColors.TextSecondary)
                    else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.graphicsLayer { alpha = if (loading) 0.4f else 1f }) {
                        itemsIndexed(eps, key = { _, e -> "${e.season}-${e.number}" }) { i, ep ->
                            val plex = ui.plexEpisodes[ep.season to ep.number]
                            Surface(
                                // OK plays it from the best source; holding OK lists the sources.
                                onClick = { vm.playBestEpisode(ep.season, ep.number) },
                                onLongClick = { vm.pickEpisode(ep) },
                                modifier = (if (i == target) Modifier.focusRequester(episodeFocus) else Modifier)
                                    // Not while the next season loads (that's the last season's list); Up from the top
                                    // episode goes to the season being viewed.
                                    .focusProperties { canFocus = panelFree && !loading; if (i == 0) up = seasonFocus }
                                    .onFocusChanged { if (it.isFocused) highlighted = ep }
                                    .fillMaxWidth(),
                                scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = AppColors.Panel.copy(alpha = 0.75f),
                                    contentColor = AppColors.TextPrimary,
                                    focusedContainerColor = AppColors.Accent,
                                    focusedContentColor = AppColors.OnAccent,
                                ),
                            ) {
                                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(5.dp)).background(AppColors.PanelAlt)) {
                                        ep.still?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                        plex?.takeIf { it.viewOffsetMs > 0 && !it.watched }?.let {
                                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f).height(3.dp).background(AppColors.Accent))
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("${ep.number}. ${ep.name}", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val tags = listOfNotNull(
                                            ep.runtimeMin?.takeIf { it > 0 }?.let { "${it}m" },
                                            if (plex != null) "Plex" else null,
                                        ).joinToString("  ·  ")
                                        if (tags.isNotEmpty()) Text(tags, fontSize = 12.sp, maxLines = 1)
                                        // A taste of the story, right in the list (the full synopsis is on the left).
                                        ep.overview?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp).graphicsLayer { alpha = 0.85f })
                                        }
                                    }
                                    if (watched(ep.season, ep.number)) Text("✓", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 10.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (ui.streams.isNullOrEmpty()) EpisodeActionsFor(ui, vm)
    }
}

/** A film's soundtrack and subtitles, picked before Play. */
@Composable
private fun TracksDialog(
    t: io.github.petebutty.viewarr.data.media.TitleTracks,
    audio: Int?, subtitle: Int?,
    onPick: (audio: Int?, subtitle: Int?) -> Unit,
    onDone: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    BackHandler { onDone() }
    // What the server would pick by itself, when you haven't picked.
    val a = audio ?: t.audio.firstOrNull { it.default }?.ordinal ?: 0
    val sub = subtitle ?: t.subtitles.firstOrNull { it.default }?.ordinal
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        LazyColumn(
            Modifier.width(560.dp).fillMaxHeight(0.85f).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(24.dp).trapFocus().ignoreHeldOk(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { Text("Audio & subtitles", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary) }
            item { Text("For this play, from ${t.source}.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp)) }
            if (t.audio.isNotEmpty()) item { Text("Audio", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 6.dp, start = 12.dp)) }
            itemsIndexed(t.audio) { i, c ->
                io.github.petebutty.viewarr.ui.common.MenuItem(
                    text = (if (c.ordinal == a) "✓  " else "     ") + c.label, selected = false,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { onPick(c.ordinal, subtitle) },
                )
            }
            item { Text("Subtitles", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 10.dp, start = 12.dp)) }
            item {
                io.github.petebutty.viewarr.ui.common.MenuItem(
                    text = (if (sub == null || sub < 0) "✓  " else "     ") + "Off", selected = false,
                    modifier = if (t.audio.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    onClick = { onPick(audio, -1) },
                )
            }
            items(t.subtitles) { c ->
                io.github.petebutty.viewarr.ui.common.MenuItem(text = (if (c.ordinal == sub) "✓  " else "     ") + c.label, selected = false, onClick = { onPick(audio, c.ordinal) })
            }
            item { io.github.petebutty.viewarr.ui.common.MenuItem(text = "Done", selected = false, onClick = onDone, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

/** Holding OK on an episode: where to play it from. */
@Composable
private fun EpisodeActionsFor(ui: DetailUi, vm: MediaViewModel) {
    val ep = ui.episode ?: return
    val plex = ui.plexEpisodes[ep.season to ep.number]
    EpisodeActions(
        title = "S${ep.season} E${ep.number} · ${ep.name}",
        plexLabel = plex?.let { if (it.viewOffsetMs > 0 && !it.watched) "Resume on Plex" else "Play on Plex" },
        onProvider = if ((ep.season to ep.number) in ui.providerEpisodes) ({ vm.playProviderEpisode(ep.season, ep.number) }) else null,
        serverLabel = ui.serverEpisodes[ep.season to ep.number]?.let { se ->
            val name = ui.copies?.firstOrNull { it.source == "Jellyfin" || it.source == "Emby" }?.source ?: "server"
            if (se.viewOffsetMs > 0 && !se.watched) "Resume on $name" else "Play on $name"
        },
        onServer = { vm.playServerEpisode(ep.season, ep.number) },
        hasAddons = ui.hasAddons,
        searching = ui.searching,
        message = ui.message,
        onPlex = { vm.playPlexEpisode(ep.season, ep.number) },
        onSearch = { vm.searchEpisode(ep) },
        onClose = { vm.pickEpisode(null) },
    )
}

/**
 * A show's page: its seasons and that season's episodes in a row, right under the buttons (opens on the season you're
 * up to). OK plays an episode from the best source; holding OK lists the sources.
 */
@Composable
private fun InlineEpisodes(ui: DetailUi, vm: MediaViewModel, free: Boolean, entry: FocusRequester, up: FocusRequester, down: FocusRequester) {
    val item = ui.detail.item
    fun watched(s: Int, e: Int) = ui.plexEpisodes[s to e]?.watched == true || ui.serverEpisodes[s to e]?.watched == true || (s to e) in ui.traktEpisodes
    // Opens on the season you're up to (once that's known; a few seconds at most), else the first.
    LaunchedEffect(ui.seasons.size, ui.nextUpChecked) {
        if (ui.seasons.isEmpty() || ui.season != null) return@LaunchedEffect
        if (!ui.nextUpChecked && item.nextSeason == null) { kotlinx.coroutines.delay(4_000); if (ui.season != null) return@LaunchedEffect }
        val want = item.nextSeason ?: ui.nextUp?.first
        vm.selectSeason(want?.takeIf { n -> ui.seasons.any { it.number == n } } ?: ui.seasons.first().number)
    }
    val seasonFocus = remember { FocusRequester() }
    val seasonRow = androidx.compose.foundation.lazy.rememberLazyListState()
    val episodeRow = androidx.compose.foundation.lazy.rememberLazyListState()
    var onSeasons by remember { mutableStateOf(false) }
    var pendingSeason by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingSeason) {
        val n = pendingSeason ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        vm.selectSeason(n)
    }
    var lastEpisodes by remember { mutableStateOf<List<io.github.petebutty.viewarr.data.media.EpisodeInfo>?>(null) }
    LaunchedEffect(ui.episodes) { ui.episodes?.let { lastEpisodes = it } }
    val target = ui.episodes?.let { eps ->
        val next = if (item.nextSeason == ui.season) eps.indexOfFirst { it.number == item.nextEpisode }
            else if (ui.nextUp?.first == ui.season) eps.indexOfFirst { it.number == ui.nextUp?.second } else -1
        if (next >= 0) next else eps.indexOfFirst { e -> !watched(e.season, e.number) }.coerceAtLeast(0)
    } ?: -1
    // The row starts at the episode you're up to.
    LaunchedEffect(ui.season, ui.episodes != null) {
        if (target > 0) runCatching { episodeRow.scrollToItem(maxOf(0, target - 1)) }
    }
    LaunchedEffect(ui.season, ui.seasons.size) {
        if (onSeasons) return@LaunchedEffect
        val idx = ui.seasons.indexOfFirst { it.number == ui.season }
        if (idx >= 0) runCatching { seasonRow.scrollToItem(maxOf(0, idx - 1)) }
    }
    if (ui.seasons.isEmpty()) return
    Column(Modifier.padding(top = 10.dp)) {
        LazyRow(
            Modifier.onFocusChanged { onSeasons = it.hasFocus },
            state = seasonRow, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.seasons, key = { it.number }) { season ->
                val selected = season.number == ui.season
                Surface(
                    onClick = { pendingSeason = null; vm.selectSeason(season.number) },
                    modifier = (if (selected) Modifier.focusRequester(seasonFocus) else Modifier)
                        .focusProperties { canFocus = free; this.up = up }
                        .onFocusChanged { if (it.isFocused && free) pendingSeason = season.number },
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) AppColors.Accent.copy(alpha = 0.35f) else AppColors.Panel.copy(alpha = 0.8f),
                        contentColor = AppColors.TextPrimary,
                        focusedContainerColor = AppColors.Accent,
                        focusedContentColor = AppColors.OnAccent,
                    ),
                ) {
                    Text(season.name, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val loading = ui.episodes == null
        val eps = ui.episodes ?: lastEpisodes
        when {
            eps == null -> Text("Loading episodes…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.height(150.dp))
            eps.isEmpty() -> Text("No episodes listed for this season.", fontSize = 13.sp, color = AppColors.TextSecondary)
            // Room round the cards for the focused one's frame (it was clipped at the row's edge); pulled back by the
            // same amount so the row still lines up with the page.
            else -> LazyRow(
                state = episodeRow, horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.offset(x = (-8).dp).graphicsLayer { alpha = if (loading) 0.4f else 1f },
            ) {
                itemsIndexed(eps, key = { _, e -> "${e.season}-${e.number}" }) { i, ep ->
                    val plex = ui.plexEpisodes[ep.season to ep.number] ?: ui.serverEpisodes[ep.season to ep.number]
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.width(192.dp)) {
                        Surface(
                            onClick = { vm.playBestEpisode(ep.season, ep.number) },
                            onLongClick = { vm.pickEpisode(ep) },
                            modifier = (if (i == target.coerceAtLeast(0)) Modifier.focusRequester(entry) else Modifier)
                                .focusProperties { canFocus = free && !loading; this.up = seasonFocus; this.down = down }
                                .onFocusChanged { focused = it.isFocused }
                                .size(width = 192.dp, height = 108.dp),
                            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = RoundedCornerShape(8.dp))),
                            colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.PanelAlt, focusedContainerColor = AppColors.PanelAlt),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                (ep.still ?: item.backdrop)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                if (watched(ep.season, ep.number)) Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(AppColors.Accent, androidx.compose.foundation.shape.CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { androidx.compose.material3.Icon(io.github.petebutty.viewarr.ui.common.AppIcons.Check, contentDescription = "Watched", tint = AppColors.OnAccent, modifier = Modifier.size(16.dp)) }
                                plex?.takeIf { it.viewOffsetMs > 0 && !it.watched && AppColors.palette.progressBadges }?.let {
                                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f).height(4.dp).background(AppColors.Accent))
                                }
                            }
                        }
                        Text(
                            "${ep.number}. ${ep.name}", fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (focused) AppColors.Accent else AppColors.TextPrimary, modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            listOfNotNull(ep.runtimeMin?.takeIf { it > 0 }?.let { "${it}m" }, ep.airDate?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                            fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeActions(
    title: String,
    plexLabel: String?,
    onProvider: (() -> Unit)? = null,
    serverLabel: String? = null,
    onServer: () -> Unit = {},
    hasAddons: Boolean,
    searching: Boolean,
    message: String?,
    onPlex: () -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        // Opened by holding OK on an episode: letting go of that OK mustn't pick the first source.
        Column(Modifier.width(520.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(24.dp).trapFocus().ignoreHeldOk(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
            var firstUsed = false
            fun m(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
            plexLabel?.let { io.github.petebutty.viewarr.ui.common.MenuItem(text = it, selected = false, onClick = onPlex, modifier = m()) }
            serverLabel?.let { io.github.petebutty.viewarr.ui.common.MenuItem(text = it, selected = false, onClick = onServer, modifier = m()) }
            onProvider?.let { io.github.petebutty.viewarr.ui.common.MenuItem(text = "Play from IPTV", selected = false, onClick = it, modifier = m()) }
            if (hasAddons) io.github.petebutty.viewarr.ui.common.MenuItem(text = if (searching) "Searching…" else "Search addons", selected = false, onClick = onSearch, modifier = m())
            if (plexLabel == null && serverLabel == null && onProvider == null && !hasAddons) Text("This episode isn't in Plex or on IPTV, and no Stremio addon is connected.", fontSize = 13.sp, color = AppColors.TextSecondary)
            io.github.petebutty.viewarr.ui.common.MenuItem(text = "Close", selected = false, onClick = onClose, modifier = m())
            message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun StreamPicker(streams: List<StreamOption>, onPick: (StreamOption) -> Unit, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { first.requestFocus() }
    }
    Column(
        modifier.fillMaxHeight().fillMaxWidth(0.46f).background(AppColors.Panel.copy(alpha = 0.97f)).padding(horizontal = 20.dp, vertical = 28.dp).trapFocus(),
    ) {
        Text("${streams.size} stream${if (streams.size == 1) "" else "s"} found", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
        Text("Back to close", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(streams) { i, s ->
                Surface(
                    onClick = { onPick(s) },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = AppColors.PanelAlt,
                        contentColor = AppColors.TextPrimary,
                        focusedContainerColor = AppColors.Accent,
                        focusedContentColor = AppColors.OnAccent,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.description.isNotBlank()) Text(s.description, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private val LocalButtonsFree = androidx.compose.runtime.compositionLocalOf { true }

/** Detail page buttons that step aside (unfocusable) while a pop-up is open. */
@Composable
private fun Modifier.free(): Modifier {
    val free = LocalButtonsFree.current
    return this.focusProperties { canFocus = free }
}

/** "Next episode in 10 s": OK plays now, Back cancels. */
@Composable
private fun UpNextOverlay(ep: io.github.petebutty.viewarr.data.media.EpisodeInfo, onPlay: () -> Unit, onCancel: () -> Unit) {
    // Settings → Playback → Next episode countdown.
    var left by remember(ep) { androidx.compose.runtime.mutableIntStateOf(AppColors.palette.upNextSeconds) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(ep) {
        awaitFrame(); runCatching { focus.requestFocus() }
        while (left > 0) { delay(1_000); left-- }
        onPlay()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).trapFocus(), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.padding(56.dp).width(520.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Next episode in $left s", fontSize = 14.sp, color = AppColors.Accent)
            Text("S${ep.season} · E${ep.number}  ${ep.name}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ep.overview?.let { Text(it, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onPlay, modifier = Modifier.focusRequester(focus)) { Text("Play now") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** Hold OK on a poster: play, details, trailer and watchlist without opening the title page first. */
@Composable
private fun QuickOptions(item: MediaItem, vm: MediaViewModel, onTrailer: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    val trailerId by produceState<String?>(null, item.key) { value = vm.trailers.youtubeId(item) }
    // null = no Trakt (no watchlist option); loading shows nothing until known.
    val watchlisted by produceState<Boolean?>(null, item.key) { value = vm.watchlistState(item) }
    // Seerr set up: request the title to keep (or see that it's already requested / available).
    val seerrStatus by produceState<io.github.petebutty.viewarr.data.remote.services.SeerrService.Status?>(null, item.key) { value = vm.seerrStatus(item) }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    val show = item.type == MediaType.Show
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(20.dp).trapFocus()
                .then(Modifier.ignoreHeldOk()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            listOfNotNull(item.year?.toString(), item.origin).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                Text(it, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
            }
            io.github.petebutty.viewarr.ui.common.MenuItem(
                text = if (show) "Episodes" else "Play",
                selected = false,
                modifier = Modifier.focusRequester(first),
                onClick = { onClose(); if (show) vm.quickEpisodes(item) else vm.quickPlay(item) },
            )
            io.github.petebutty.viewarr.ui.common.MenuItem(text = "Details", selected = false, onClick = { onClose(); vm.open(item) })
            if (trailerId != null) io.github.petebutty.viewarr.ui.common.MenuItem(text = "Watch trailer", selected = false, onClick = onTrailer)
            watchlisted?.let { on ->
                io.github.petebutty.viewarr.ui.common.MenuItem(
                    text = if (on) "Remove from watchlist" else "Add to watchlist",
                    selected = false,
                    onClick = { onClose(); vm.quickWatchlist(item, add = !on) },
                )
            }
            seerrStatus?.let { st ->
                val canAsk = st == io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.None ||
                    st == io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.Partial
                io.github.petebutty.viewarr.ui.common.MenuItem(
                    text = st.label,
                    selected = false,
                    dim = !canAsk,
                    onClick = { if (canAsk) { onClose(); vm.requestOnSeerr(item) } },
                )
            }
            io.github.petebutty.viewarr.ui.common.MenuItem(text = "Close", selected = false, onClick = onClose)
        }
    }
}


/**
 * A title's name as its logo artwork (TMDB), left-aligned; the name as text until the logo arrives, or when there's
 * none. [waitMs]: a short pause first, so moving quickly along a row doesn't fetch every logo.
 */
@Composable
private fun TitleLogo(item: MediaItem, logoOf: (suspend (MediaItem) -> String?)?, height: androidx.compose.ui.unit.Dp, maxWidth: androidx.compose.ui.unit.Dp, fontSize: Int, waitMs: Long = 0) {
    // Settings → Appearance → Title logo off: the name as text, and no logo lookup.
    val logosOn = AppColors.palette.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.LOGO) && !AppColors.palette.lowMemory
    val logo by produceState<String?>(null, item.key, logosOn) {
        value = null
        if (logoOf == null || !logosOn) return@produceState
        if (waitMs > 0) delay(waitMs)
        value = logoOf(item)
    }
    var loaded by remember(logo) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Box {
        logo?.let { url ->
            AsyncImage(
                // Loaded at its own size: the space stays 0 high until it arrives.
                model = remember(url) { coil.request.ImageRequest.Builder(context).data(url).size(coil.size.Size.ORIGINAL).build() },
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onSuccess = { loaded = true },
                modifier = Modifier.height(if (loaded) height else 0.dp).width(maxWidth),
            )
        }
        if (!loaded) Text(item.title, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}


/** "TMDB 8.6 (1.2k) · IMDb 7.9 · Rotten Tomatoes 87% · RT audience 91% · Trakt 82%". */
@Composable
private fun RatingsLine(tmdb: Double?, votes: Int?, more: List<io.github.petebutty.viewarr.data.media.RatingBadge>) {
    val all = buildList {
        // Scores only (no vote counts), so up to seven sources fit on one line.
        // Settings → Appearance → Ratings: only the ones switched on.
        val p = AppColors.palette
        tmdb?.takeIf { it > 0 && p.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.rating("TMDB")) }?.let { add("TMDB" to "%.1f".format(it)) }
        more.filter { p.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.rating(it.source)) }.forEach { add(it.source to it.value) }
    }
    if (all.isEmpty()) return
    // Wraps onto a second line rather than running past its column (e.g. under the trailer on the Movies page).
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        all.forEach { (src, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(src, fontSize = 11.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = Color.Black,
                    modifier = Modifier.background(ratingColour(src), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(v, fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 1, softWrap = false)
            }
        }
    }
}

private fun ratingColour(source: String) = when (source) {
    "IMDb" -> Color(0xFFF5C518)
    "Rotten Tomatoes", "RT" -> Color(0xFFFA6C4F)
    "RT audience", "Audience" -> Color(0xFFF9D361)
    "Trakt" -> Color(0xFFED6E83)
    "Metacritic" -> Color(0xFF66CC33)
    "Letterboxd" -> Color(0xFF40BCF4)
    else -> Color(0xFF90CEA1)
}

private enum class DetailTab(val label: String) { Cast("Cast & crew"), Reviews("Reviews"), Collection("Collection"), Similar("More like this"), Details("Details") }

/**
 * Below the header: a row of tabs; moving onto one shows its row underneath (cast & crew, reviews, more like this,
 * details). Down goes from a tab into its row.
 */
@Composable
private fun DetailTabs(
    d: io.github.petebutty.viewarr.data.media.MediaDetail,
    show: Boolean,
    free: Boolean,
    /** On the selected tab: where Down from the buttons above goes. */
    tabFocus: FocusRequester,
    onPerson: (io.github.petebutty.viewarr.data.media.CastMember) -> Unit,
    onReview: (io.github.petebutty.viewarr.data.media.Review) -> Unit,
    onSimilar: (MediaItem) -> Unit,
) {
    val facts = listOfNotNull(
        d.released?.let { (if (show) "First aired" else "Released") to it },
        d.runtimeMin?.takeIf { it > 0 }?.let { "Runtime" to (if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m") },
        d.studio?.let { (if (show) "Network" else "Studio") to it },
        d.language?.let { "Language" to it },
        d.status?.let { "Status" to it },
        d.budget?.let { "Budget" to money(it) },
        d.revenue?.let { "Box office" to money(it) },
        d.writers.takeIf { it.isNotEmpty() }?.let { "Written by" to it.take(3).joinToString(", ") },
    )
    val tabs = buildList {
        // Settings → Appearance → Title pages: only the tabs switched on.
        val p = AppColors.palette
        if (d.cast.isNotEmpty() && p.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.CAST)) add(DetailTab.Cast)
        if (d.reviews.isNotEmpty() && p.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.REVIEWS)) add(DetailTab.Reviews)
        if (d.collection.isNotEmpty() && p.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.COLLECTION)) add(DetailTab.Collection)
        if (d.similar.isNotEmpty() && p.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.SIMILAR)) add(DetailTab.Similar)
        if (facts.isNotEmpty() && p.shows(io.github.petebutty.viewarr.ui.theme.TitleParts.DETAILS)) add(DetailTab.Details)
    }
    if (tabs.isEmpty()) return
    var tab by remember(d.item.key) { mutableStateOf(tabs.first()) }
    if (tab !in tabs) tab = tabs.first()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEach { t ->
            Surface(
                onClick = { tab = t },
                modifier = (if (t == tab) Modifier.focusRequester(tabFocus) else Modifier).onFocusChanged { if (it.isFocused) tab = t }.focusProperties { canFocus = free },
                scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (t == tab) AppColors.TextPrimary.copy(alpha = 0.15f) else Color.Transparent,
                    contentColor = if (t == tab) AppColors.TextPrimary else AppColors.TextSecondary,
                    focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
                ),
            ) { Text((if (t == DetailTab.Collection) d.collectionName ?: t.label else t.label) + if (t == DetailTab.Reviews) " (${d.reviews.size})" else "", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
        }
    }
    Spacer(Modifier.height(12.dp))
    val rowPadding = PaddingValues(end = 40.dp)
    when (tab) {
        DetailTab.Cast -> androidx.compose.foundation.lazy.LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(d.cast, key = { "${it.id}-${it.name}" }) { c -> CastCard(c, free) { onPerson(c) } }
        }
        DetailTab.Reviews -> androidx.compose.foundation.lazy.LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(d.reviews.size) { i -> ReviewCard(d.reviews[i], free) { onReview(d.reviews[i]) } }
        }
        DetailTab.Similar, DetailTab.Collection -> androidx.compose.foundation.lazy.LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The film you're on stays out of its own collection row.
            items(if (tab == DetailTab.Collection) d.collection.filter { it.tmdbId != d.item.tmdbId } else d.similar, key = { it.key }) { m ->
                Column(Modifier.width(86.dp)) {
                    PosterCard(m, Modifier.focusProperties { canFocus = free }, onClick = { onSimilar(m) })
                    Text(m.title, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        DetailTab.Details -> Surface(
            onClick = {}, modifier = Modifier.focusProperties { canFocus = free },
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Panel.copy(alpha = 0.6f), focusedContainerColor = AppColors.Panel),
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                facts.forEach { (k, v) ->
                    Column {
                        Text(k, fontSize = 11.sp, color = AppColors.TextSecondary)
                        Text(v, fontSize = 14.sp, color = AppColors.TextPrimary, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun money(n: Long): String = when {
    n >= 1_000_000_000 -> "$%.1fbn".format(n / 1e9)
    n >= 1_000_000 -> "$%.0fm".format(n / 1e6)
    else -> "$%,d".format(n)
}

/** A cast member: round photo (initials when TMDB has none), name and character. OK opens their films and shows. */
@Composable
private fun CastCard(c: io.github.petebutty.viewarr.data.media.CastMember, free: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(76.dp).onFocusChanged { focused = it.isFocused }.focusProperties { canFocus = free },
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f, pressedScale = 1f),
            shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = androidx.compose.foundation.shape.CircleShape)),
            colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Panel, focusedContainerColor = AppColors.Panel),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(c.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), fontSize = 20.sp, color = AppColors.TextSecondary)
                c.photo?.let { AsyncImage(model = it, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Text(c.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (focused) AppColors.TextPrimary else AppColors.TextPrimary.copy(alpha = 0.85f),
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        c.character?.let { Text(it, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun ReviewCard(r: io.github.petebutty.viewarr.data.media.Review, free: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 380.dp, height = 140.dp).focusProperties { canFocus = free },
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, AppColors.Accent), shape = RoundedCornerShape(10.dp))),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Panel.copy(alpha = 0.7f), focusedContainerColor = AppColors.Panel),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.author, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                r.rating?.let { Text("★ %.0f/10".format(it), fontSize = 13.sp, color = AppColors.Accent) }
            }
            Text(listOfNotNull(r.source, r.date, r.likes.takeIf { it > 0 }?.let { "♥ $it" }).joinToString("  ·  "), fontSize = 11.sp, color = AppColors.TextSecondary)
            if (r.spoiler) Text("Contains spoilers: OK to read", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp))
            else Text(r.text.replace(Regex("\\s+"), " "), fontSize = 12.sp, lineHeight = 17.sp, color = AppColors.TextPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** A whole review, scrolled with Up/Down; Back or OK closes it. */
@Composable
private fun ReviewDialog(r: io.github.petebutty.viewarr.data.media.Review, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val scroll = androidx.compose.foundation.rememberScrollState()
        val f = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { awaitFrame(); runCatching { f.requestFocus() } }
        Column(
            Modifier.width(820.dp).heightIn(max = 600.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(28.dp)
                .focusRequester(f).then(Modifier.focusable())
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(240f) }; true }
                        Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-240f) }; true }
                        Key.DirectionCenter, Key.Enter -> { onClose(); true }
                        else -> false
                    }
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.author, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                r.rating?.let { Text("★ %.0f/10".format(it), fontSize = 16.sp, color = AppColors.Accent) }
            }
            r.date?.let { Text(it, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 12.dp)) }
            Column(Modifier.verticalScroll(scroll)) {
                Text(r.text, fontSize = 16.sp, lineHeight = 24.sp, color = AppColors.TextPrimary)
            }
        }
    }
}

/** Other sources: play from one particular place instead of the automatic choice. */
@Composable
private fun SourcesDialog(ui: DetailUi, vm: MediaViewModel, onSearch: () -> Unit, onDone: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone) {
        Column(
            // Opened by holding OK: letting go of that OK mustn't pick the first source.
            Modifier.width(560.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(22.dp).ignoreHeldOk(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Play from", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 8.dp))
            var firstUsed = false
            fun mod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
            ui.copies.orEmpty().filter { it.ref != null || it.source == io.github.petebutty.viewarr.data.media.PROVIDER }.forEach { c ->
                io.github.petebutty.viewarr.ui.common.MenuItem(text = vm.copyLabel(c), selected = false, onClick = { onDone(); vm.playCopy(c) }, modifier = mod())
            }
            if (ui.hasAddons) io.github.petebutty.viewarr.ui.common.MenuItem(text = "Search addons", selected = false, onClick = onSearch, modifier = mod())
            io.github.petebutty.viewarr.ui.common.MenuItem(text = "Cancel", selected = false, onClick = onDone, modifier = mod())
        }
    }
}

/** A person's films and shows: who they are at the top (photo, born / died, where, biography). */
@Composable
private fun PersonHeader(p: io.github.petebutty.viewarr.data.media.PersonInfo) {
    Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 36.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(Modifier.size(width = 150.dp, height = 210.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.Panel)) {
            p.photo?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            val life = listOfNotNull(
                p.knownFor,
                p.born?.let { b -> if (p.died != null) "$b – ${p.died}" else "Born $b" },
                p.place,
            ).joinToString("  ·  ")
            if (life.isNotEmpty()) Text(life, fontSize = 15.sp, color = AppColors.TextSecondary)
            (p.bio ?: if (p.loading) "Loading…" else null)?.let { bio -> Text(
                bio,
                fontSize = 15.sp, lineHeight = 21.sp, color = AppColors.TextPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            ) }
        }
    }
}
