package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import io.github.petebutty.viewarr.ui.common.trapFocus
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusable
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
    vm: MediaViewModel = hiltViewModel(key = if (searchHeader != null) "media-search" else "media-${type.name}"),
) {
    val remote = LocalRemote.current
    val state by vm.rows.collectAsStateWithLifecycle()
    val jump by vm.jump.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    // A whole library being browsed (menu → Movies / Shows → a library), shown instead of the rows.
    val browse by vm.browse.collectAsStateWithLifecycle()
    val libGenres by vm.genres.collectAsStateWithLifecycle()
    val gridState = remember(browse?.library?.key, browse?.sort, browse?.page, browse?.genre, browse?.unwatched) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    var gridFocusKey by remember(browse?.library?.key, browse?.sort, browse?.page, browse?.genre, browse?.unwatched) { mutableStateOf<String?>(null) }
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
    // A library page arrived (opened, re-sorted, next / previous page): focus its first title.
    LaunchedEffect(browse?.library?.key, browse?.sort, browse?.page, browse?.genre, browse?.unwatched, browse?.items?.isNotEmpty()) {
        val b = browse ?: return@LaunchedEffect
        if (!focusEnabled || detail != null || b.items.isEmpty() || librarySearching) return@LaunchedEffect
        // A new page: its first title, from the top. Set explicitly: a poster the old page's focus fell onto while
        // the page changed could otherwise become the entry (it landed a row down).
        gridFocusKey = b.items.first().key
        runCatching { gridState.scrollToItem(0) }
        awaitFrame(); awaitFrame()
        runCatching { focusRequester.requestFocus() }
        awaitFrame()
        if (gridFocusKey != b.items.first().key) { gridFocusKey = b.items.first().key; awaitFrame(); awaitFrame(); runCatching { focusRequester.requestFocus() } }
    }
    // Back from a title opened in a library: straight back to that poster (scrolled into view first).
    var openedFromGrid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(detail == null) {
        val key = openedFromGrid ?: return@LaunchedEffect
        val b = browse ?: return@LaunchedEffect
        if (detail != null) return@LaunchedEffect
        openedFromGrid = null
        val idx = b.items.indexOfFirst { it.key == key }
        if (idx < 0) return@LaunchedEffect
        gridFocusKey = key
        runCatching { gridState.scrollToItem(idx + if (b.page > 0) 1 else 0) }
        awaitFrame(); awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }
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

    Box(Modifier.fillMaxSize().background(if (searchHeader != null) Color.Transparent else AppColors.Background)) {
        browse?.let { b ->
            LibraryGrid(
                b = b,
                gridState = gridState,
                focusKey = gridFocusKey,
                focusRequester = focusRequester,
                focused = focused,
                onFocus = { item -> focused = item; gridFocusKey = item.key },
                onHasFocus = { hasFocus = it },
                onSort = vm::setLibrarySort,
                onQuery = vm::setLibraryQuery,
                onSearchFocus = { librarySearching = it },
                genres = libGenres,
                onLoadGenres = vm::loadGenres,
                onGenre = vm::setLibraryGenre,
                onUnwatched = vm::toggleUnwatched,
                onPage = vm::libraryPage,
                onOpen = { openedFromGrid = it.key; vm.open(it) },
                onLong = { quick = it },
                onLeftEdge = onOpenMenu,
            )
        }
        if (browse == null) Column(Modifier.fillMaxSize()) {
            if (searchHeader != null) searchHeader() else Hero(
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
                searchHeader != null && rows.isEmpty() && state.done -> Unit
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
        val playerArt by vm.playerArt.collectAsStateWithLifecycle()
        playing?.let { PlayerHost(stream = it, onClose = vm::stopPlaying, art = playerArt, onProgress = vm::reportProgress) }
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
    Box(Modifier.fillMaxWidth().height(232.dp)) {
        item?.backdrop?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.68f),
            )
        }
        val bg = AppColors.Background
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.3f to bg, 0.55f to bg.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to bg)))
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
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    focusKey: String?,
    focusRequester: FocusRequester,
    focused: MediaItem?,
    onFocus: (MediaItem) -> Unit,
    onHasFocus: (Boolean) -> Unit,
    onSort: (io.github.petebutty.viewarr.data.media.LibrarySort) -> Unit,
    onQuery: (String) -> Unit,
    onSearchFocus: (Boolean) -> Unit,
    genres: List<Pair<String, String>>?,
    onLoadGenres: () -> Unit,
    onGenre: (Pair<String, String>?) -> Unit,
    onUnwatched: () -> Unit,
    onPage: (Int) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onLong: (MediaItem) -> Unit,
    onLeftEdge: () -> Unit,
) {
    val entryKey = focusKey?.takeIf { k -> b.items.any { it.key == k } } ?: b.items.firstOrNull()?.key
    val prev = b.page > 0
    val showNext = b.hasNext && !b.loading
    val nextFocus = remember { FocusRequester() }
    // Down turns the page only for a press that started at the bottom (on the bottom row or Next page). The release
    // of a press that just moved focus down into the bottom row mustn't count, or the last row could never be reached.
    var pageDownArmed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.library.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        b.failed -> "The server didn't answer. Press Back and try again."
                        b.total == 0 && b.query.isNotBlank() -> "Nothing in this library matches \"${b.query}\""
                        b.total == 0 && b.filtered -> "Nothing in this library matches these filters"
                        b.total >= 0 -> "${b.library.source}  ·  ${b.total} ${if (b.filtered) "matches" else "titles"}  ·  page ${b.page + 1} of ${b.pages}"
                        else -> "Loading…"
                    },
                    fontSize = 13.sp, color = AppColors.TextSecondary,
                )
            }
            // Keyed by library: another library starts with an empty box.
            androidx.compose.runtime.key(b.library.key) {
                LibrarySearchBox(b.query, onQuery, onSearchFocus, onDone = { runCatching { focusRequester.requestFocus() } })
            }
            io.github.petebutty.viewarr.data.media.LibrarySort.entries.forEach { s ->
                Surface(
                    onClick = { onSort(s) },
                    modifier = Modifier.padding(start = 8.dp),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (s == b.sort) AppColors.TextPrimary else AppColors.Panel,
                        contentColor = if (s == b.sort) AppColors.Background else AppColors.TextSecondary,
                        focusedContainerColor = AppColors.Accent,
                        focusedContentColor = AppColors.OnAccent,
                    ),
                ) { Text(s.label, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) }
            }
        }
        // Filters (the server's own genres; unwatched only), then the highlighted title so you know what you're on.
        var pickGenre by remember { mutableStateOf(false) }
        Row(Modifier.padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterPill("Genre: " + (b.genre?.second ?: "All") + "  ▾", active = b.genre != null) { onLoadGenres(); pickGenre = true }
            FilterPill("Unwatched", active = b.unwatched, modifier = Modifier.padding(start = 8.dp)) { onUnwatched() }
            if (b.genre != null || b.unwatched) FilterPill("Clear filters", active = false, modifier = Modifier.padding(start = 8.dp)) {
                if (b.genre != null) onGenre(null)
                if (b.unwatched) onUnwatched()
            }
            Text(
                focused?.takeIf { f -> b.items.any { it.key == f.key } }?.let { "${it.title}   ${metaLine(it)}" } ?: " ",
                fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 20.dp),
            )
        }
        if (pickGenre) GenrePicker(genres, b.genre, onPick = { onGenre(it); pickGenre = false }, onDismiss = { pickGenre = false })
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(GRID_COLUMNS),
            state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f).onFocusChanged { onHasFocus(it.hasFocus) },
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (prev) item(key = "prev") { PageCard("‹", "Previous page", "Page ${b.page}") { onPage(-1) } }
            gridItemsIndexed(b.items, key = { _, it -> it.key }) { index, item ->
                val pos = index + if (prev) 1 else 0
                val col = pos % GRID_COLUMNS
                val cells = b.items.size + (if (prev) 1 else 0) + (if (showNext) 1 else 0)
                PosterCard(
                    item = item,
                    modifier = (if (item.key == entryKey) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) onFocus(item) }
                        .onPreviewKeyEvent { e ->
                            // Left on the first column: the menu (pick another library).
                            val bottomRow = showNext && pos / GRID_COLUMNS == (cells - 1) / GRID_COLUMNS
                            when {
                                // Bottom row: Down turns the page when the key is let go (turning it on the press let
                                // the rest of the press move focus a row down on the new page).
                                e.key == Key.DirectionDown && bottomRow -> {
                                    // Pressed here: go via the Next page card, which turns the page on release.
                                    if (e.type == KeyEventType.KeyDown) { pageDownArmed = true; runCatching { nextFocus.requestFocus() } }
                                    true
                                }
                                e.type != KeyEventType.KeyDown -> false
                                e.key == Key.DirectionLeft && col == 0 -> { onLeftEdge(); true }
                                // Nothing directly below on the row before last: Down goes to Next page.
                                e.key == Key.DirectionDown && showNext && pos + GRID_COLUMNS >= cells - 1 && pos / GRID_COLUMNS < (cells - 1) / GRID_COLUMNS ->
                                    { pageDownArmed = false; runCatching { nextFocus.requestFocus() }; true }
                                else -> false
                            }
                        },
                    onClick = { onOpen(item) },
                    onLongClick = { onLong(item) },
                )
            }
            if (showNext) item(key = "next") {
                PageCard(
                    "›", "Next page", "Page ${b.page + 2} of ${b.pages}",
                    // Down on Next page turns the page too (nothing is below it).
                    Modifier.focusRequester(nextFocus).onPreviewKeyEvent { e ->
                        when {
                            e.key != Key.DirectionDown -> false
                            e.type == KeyEventType.KeyDown -> { pageDownArmed = true; true }
                            else -> { if (pageDownArmed) { pageDownArmed = false; onPage(1) }; true }
                        }
                    },
                ) { onPage(1) }
            }
            if (b.loading) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(GRID_COLUMNS) }) {
                Text("Loading…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
            }
        }
    }
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

/** The library's genres as a list to pick from ("All genres" first). */
@Composable
private fun GenrePicker(genres: List<Pair<String, String>>?, selected: Pair<String, String>?, onPick: (Pair<String, String>?) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        Column(Modifier.width(360.dp).then(Modifier.heightIn(max = 520.dp)).background(AppColors.Panel, RoundedCornerShape(12.dp)).padding(16.dp)) {
            Text("Genre", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            when {
                genres == null -> Text("Loading…", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
                else -> {
                    val all = listOf<Pair<String, String>?>(null) + genres
                    val start = all.indexOf(selected).coerceAtLeast(0)
                    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, start - 3))
                    LaunchedEffect(Unit) { awaitFrame(); awaitFrame(); runCatching { first.requestFocus() } }
                    androidx.compose.foundation.lazy.LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        items(all.size) { i ->
                            val g = all[i]
                            io.github.petebutty.viewarr.ui.common.MenuItem(
                                text = g?.second ?: "All genres",
                                selected = g == selected,
                                onClick = { onPick(g) },
                                modifier = if (i == start) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
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
    val voiceIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a title")
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val canVoice = remember { runCatching { context.packageManager.queryIntentActivities(voiceIntent, 0).isNotEmpty() }.getOrDefault(false) }
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!said.isNullOrEmpty()) { text = said; onQuery(said) }
    }
    if (canVoice) {
        Surface(
            onClick = { runCatching { voice.launch(voiceIntent) } },
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
                    if (text.isEmpty()) Text("Search this library", fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** "Next page" / "Previous page" in a library grid: a poster-sized card. */
@Composable
private fun PageCard(arrow: String, label: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 100.dp, height = 150.dp),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = RoundedCornerShape(8.dp))),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Panel, focusedContainerColor = AppColors.Panel),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(arrow, fontSize = 30.sp, color = AppColors.Accent)
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, textAlign = TextAlign.Center)
            Text(sub, fontSize = 11.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        }
    }
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
private fun PosterCard(item: MediaItem, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.free().size(width = 100.dp, height = 150.dp),
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
            item.poster?.let { AsyncImage(model = it, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            // Search: this one's in your library.
            if (item.owned) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(AppColors.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.OnAccent) }
            // A person (search): their name under the photo.
            if (item.key.startsWith("person:")) Text(
                item.title, fontSize = 11.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 4.dp, vertical = 3.dp),
            )
            item.progress?.let { p ->
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
    val trailerId by produceState<String?>(null, item.key) { value = vm.trailers.youtubeId(item) }
    var trailerOpen by remember { mutableStateOf(false) }
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
    LaunchedEffect(ui.copies != null, ui.hasAddons, ui.streams == null, active, ui.episodesOpen) {
        if (ui.streams != null || !active || ui.episodesOpen || trailerOpen) return@LaunchedEffect
        awaitFrame()
        runCatching { playFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(AppColors.Background).trapFocus()) {
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
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 40.dp, top = 30.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            item.poster?.let {
                AsyncImage(
                    model = it, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 140.dp, height = 210.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(28.dp))
            }
            Column(Modifier.fillMaxWidth(0.68f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                TitleLogo(item, vm::logoFor, height = 64.dp, maxWidth = 360.dp, fontSize = 30)
                val meta = listOfNotNull(
                    item.year?.toString(),
                    d.certification,
                    d.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                    d.seasons.takeIf { it.isNotEmpty() }?.let { s -> "${s.size} season${if (s.size == 1) "" else "s"} · ${s.sumOf { it.episodes }} episodes" },
                    d.genres.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", "),
                ).joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RatingsLine(item.rating, d.voteCount, ui.ratings)
                item.subtitle?.let { Text("Up next: $it", fontSize = 13.sp, color = AppColors.Accent, maxLines = 1) }
                item.overview?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
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
                val buttonsFree = !ui.episodesOpen && ui.streams == null && !trailerOpen
                androidx.compose.runtime.CompositionLocalProvider(LocalButtonsFree provides buttonsFree) {
                // The buttons may run past the text column into the free space on the right, rather than squash.
                // Down from any of them goes to the selected tab below (not just the nearest one).
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && buttonsFree) runCatching { tabFocus.requestFocus() }.isSuccess else false
                }) {
                    if (show) {
                        val next = item.nextSeason?.let { sn -> item.nextEpisode?.let { en -> sn to en } }?.takeIf { onPlex }
                        if (next != null) {
                            Button(onClick = vm::playNextUp, modifier = (Modifier.focusRequester(playFocus)).free()) { Text("Play S${next.first} · E${next.second}") }
                            OutlinedButton(onClick = vm::openEpisodes, modifier = Modifier.free()) { Text("Episodes") }
                        } else {
                            Button(onClick = vm::openEpisodes, modifier = (Modifier.focusRequester(playFocus)).free()) { Text("Episodes") }
                        }
                    } else if (onPlex) {
                        Button(onClick = onPlayPlex, modifier = (Modifier.focusRequester(playFocus)).free()) {
                            Text(if (item.progress != null) "Resume on Plex" else "Play on Plex")
                        }
                        if (onServer) OutlinedButton(onClick = vm::playServer, modifier = Modifier.free()) { Text("Play on $serverName") }
                        if (onProvider) OutlinedButton(onClick = vm::playProvider, modifier = Modifier.free()) { Text("Play from IPTV") }
                    } else if (onServer) {
                        Button(onClick = vm::playServer, modifier = (Modifier.focusRequester(playFocus)).free()) { Text("Play on $serverName") }
                        if (onProvider) OutlinedButton(onClick = vm::playProvider, modifier = Modifier.free()) { Text("Play from IPTV") }
                    } else if (onProvider) {
                        Button(onClick = vm::playProvider, modifier = (Modifier.focusRequester(playFocus)).free()) { Text("Play from IPTV") }
                    }
                    if (ui.hasAddons && !show) {
                        val m = (if (onPlex || onProvider || onServer) Modifier else Modifier.focusRequester(playFocus)).free()
                        if (onPlex || onProvider || onServer) OutlinedButton(onClick = onSearch, modifier = m) { Text(if (ui.searching) "Searching…" else "Search addons") }
                        else Button(onClick = onSearch, modifier = m) { Text(if (ui.searching) "Searching…" else "Search addons") }
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
        Spacer(Modifier.height(16.dp))
        DetailTabs(d, show, sectionsFree, tabFocus, onPerson = { c -> vm.openCastMember(c) }, onReview = { reading = it }, onSimilar = { vm.open(it) })
        }
        reading?.let { r -> ReviewDialog(r) { reading = null } }
        if (ui.episodesOpen) EpisodesPanel(ui, vm, onPlex)
        ui.streams?.takeIf { it.isNotEmpty() }?.let { StreamPicker(it, onPickStream, Modifier.align(Alignment.CenterEnd)) }
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
    val episodeFocus = remember { FocusRequester() }
    val target = ui.episodes?.let { eps ->
        val next = if (item.nextSeason == ui.season) eps.indexOfFirst { it.number == item.nextEpisode } else -1
        if (next >= 0) next else eps.indexOfFirst { e -> ui.plexEpisodes[e.season to e.number]?.watched != true }.coerceAtLeast(0)
    } ?: -1
    var highlighted by remember(ui.season) { mutableStateOf<io.github.petebutty.viewarr.data.media.EpisodeInfo?>(null) }
    val shown = highlighted ?: ui.episodes?.getOrNull(target)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Nothing behind an open pop-up or stream list may take focus.
    val panelFree = ui.episode == null && ui.streams == null
    LaunchedEffect(ui.season, ui.episodes != null, ui.episode == null, ui.streams == null) {
        if (ui.episodes.isNullOrEmpty() || ui.episode != null || ui.streams != null || target < 0) return@LaunchedEffect
        listState.scrollToItem(maxOf(0, target - 1))
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
                        if (plex?.watched == true) "Watched" else null,
                    ).joinToString("  ·  ")
                    if (tags.isNotEmpty()) Text(tags, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    ep.overview?.let { Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = AppColors.TextPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
                }
                Spacer(Modifier.height(24.dp))
            }
            // Right: seasons + compact episodes.
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.seasons, key = { it.number }) { season ->
                        val selected = season.number == ui.season
                        Surface(
                            onClick = { vm.selectSeason(season.number) },
                            modifier = Modifier
                                .focusProperties { canFocus = panelFree }
                                // Only a real move onto a season changes it, never focus passing through behind a pop-up.
                                .onFocusChanged { if (it.isFocused && panelFree) vm.selectSeason(season.number) },
                            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (selected) AppColors.CellCurrent else AppColors.Panel.copy(alpha = 0.8f),
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
                val eps = ui.episodes
                when {
                    eps == null -> Text("Loading episodes…", color = AppColors.TextSecondary)
                    eps.isEmpty() -> Text("No episodes listed for this season.", color = AppColors.TextSecondary)
                    else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        itemsIndexed(eps, key = { _, e -> "${e.season}-${e.number}" }) { i, ep ->
                            val plex = ui.plexEpisodes[ep.season to ep.number]
                            Surface(
                                onClick = { vm.pickEpisode(ep) },
                                modifier = (if (i == target) Modifier.focusRequester(episodeFocus) else Modifier)
                                    .focusProperties { canFocus = panelFree }
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
                                    if (plex?.watched == true) Text("✓", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 10.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (ui.streams.isNullOrEmpty()) ui.episode?.let { ep ->
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
        Column(Modifier.width(520.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(24.dp).trapFocus(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    var left by remember(ep) { androidx.compose.runtime.mutableIntStateOf(10) }
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
    val logo by produceState<String?>(null, item.key) {
        value = null
        if (logoOf == null) return@produceState
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
        tmdb?.takeIf { it > 0 }?.let { add("TMDB" to "%.1f".format(it)) }
        more.forEach { add(it.source to it.value) }
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

private enum class DetailTab(val label: String) { Cast("Cast & crew"), Reviews("Reviews"), Similar("More like this"), Details("Details") }

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
        if (d.cast.isNotEmpty()) add(DetailTab.Cast)
        if (d.reviews.isNotEmpty()) add(DetailTab.Reviews)
        if (d.similar.isNotEmpty()) add(DetailTab.Similar)
        if (facts.isNotEmpty()) add(DetailTab.Details)
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
            ) { Text(t.label + if (t == DetailTab.Reviews) " (${d.reviews.size})" else "", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
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
        DetailTab.Similar -> androidx.compose.foundation.lazy.LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(d.similar, key = { it.key }) { m ->
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
