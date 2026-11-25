package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.ui.common.followingSetting
import io.github.petebutty.viewarr.ui.common.cinemaGlide
import io.github.petebutty.viewarr.ui.theme.appBackground

import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.common.stopAtRight
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusGroup
import io.github.petebutty.viewarr.ui.library.AlphabetRail
import io.github.petebutty.viewarr.ui.library.FilterChip
import io.github.petebutty.viewarr.ui.library.FilterPanel
import io.github.petebutty.viewarr.ui.library.SortPanel
import io.github.petebutty.viewarr.ui.library.ToolButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.runtime.mutableStateMapOf
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
import io.github.petebutty.viewarr.data.settings.RowMemory
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import coil.imageLoader
import kotlinx.coroutines.async
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.petebutty.viewarr.ui.common.Button
import androidx.tv.material3.ClickableSurfaceDefaults
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
import kotlinx.coroutines.flow.collectLatest
import io.github.petebutty.viewarr.data.media.CastMember
import io.github.petebutty.viewarr.data.media.EpisodeInfo
import io.github.petebutty.viewarr.data.media.MediaDetail
import io.github.petebutty.viewarr.data.media.PROVIDER
import io.github.petebutty.viewarr.data.media.PersonInfo
import io.github.petebutty.viewarr.data.media.RatingBadge
import io.github.petebutty.viewarr.data.media.Review
import io.github.petebutty.viewarr.data.media.TitleTracks
import io.github.petebutty.viewarr.data.trailers.TrailerRepository
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.common.BackLayer
import io.github.petebutty.viewarr.ui.common.LiveBehind
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.PosterImage
import io.github.petebutty.viewarr.ui.theme.TitleParts
import io.github.petebutty.viewarr.ui.theme.Wallpaper
import io.github.petebutty.viewarr.ui.common.AppPill
import io.github.petebutty.viewarr.ui.common.CardAspect
import io.github.petebutty.viewarr.ui.common.FocusCard
import io.github.petebutty.viewarr.ui.common.PopupPanel
import io.github.petebutty.viewarr.ui.common.PopupTitle
import io.github.petebutty.viewarr.ui.common.SearchField
import io.github.petebutty.viewarr.ui.common.VoiceButton
import io.github.petebutty.viewarr.ui.common.leftOpens
import io.github.petebutty.viewarr.ui.theme.NoScale
import io.github.petebutty.viewarr.ui.theme.Tokens

/** Movies or Shows: a hero for the focused title over Netflix-style poster rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaScreen(
    type: MediaType,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    /**
     * The picture in the corner has the highlight (Settings → Live TV → Highlight the corner picture): the rows stand
     * aside (they'd take it back), a title page stays as it is (it put the highlight back on Play on its return).
     */
    cornerHasFocus: Boolean = false,
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
    // The page's name over the title: My Library's and a network's pages draw on this screen too (UI review, 2026-11-18).
    val setNow = vm.currentRowSet.collectAsStateWithLifecycle().value
    val heroLabel = when { setNow == io.github.petebutty.viewarr.ui.media.MyLibrary.SET -> "My Library"; Discover.isSet(setNow) -> Discover.entryOf(setNow); setNow != null && setNow.startsWith("name:") -> setNow.removePrefix("name:"); else -> null }
    val scope = rememberCoroutineScope()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val playingStream by vm.playing.collectAsStateWithLifecycle()
    // The player itself is above every page (VodHost), so it can shrink to a corner. Shrunk, this page is in use again.
    val vodSource = remember(vm) { VodSource(vm.playing, vm.playerArt, vm::stopPlaying, vm::reportProgress, item = { vm.playingItem ?: vm.detail.value?.detail?.item }) }
    LaunchedEffect(playingStream) { if (playingStream != null) VodHost.show(vodSource) }
    val playing = playingStream.takeIf { !VodHost.mini }
    // A whole library being browsed (menu → Movies / Shows → a library), shown instead of the rows.
    val browse by vm.browse.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    var lastCard by remember { mutableStateOf<String?>(null) }
    // The card a title was opened from (Back returns to it; see below).
    var openedCard by remember { mutableStateOf<String?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    // Waiting a moment before swapping the hero keeps fast scrolling smooth on the weak box.
    // (Followed through snapshotFlow, not read here: reading it rebuilt the whole page on every move.)
    // (2026-11-19: the title, details and background arrive once you stop on a poster, not on every press:
    // 900 ms after the last press, so going along a row nothing above it is rebuilt.)
    val hero by produceState<MediaItem?>(null) {
        androidx.compose.runtime.snapshotFlow { focused }.collectLatest { delay(HERO_SETTLE_MS); value = it }
    }
    // Still on the poster the top panel shows (false from the first press of a scroll until you stop again): its
    // trailer plays only then. It used to carry on for the last poster you'd stopped on while you scrolled past others
    // (2026-11-20). (A yes / no, so the page is rebuilt twice a scroll, not on every press.)
    val onHero by remember { androidx.compose.runtime.derivedStateOf { focused?.key == hero?.key } }

    LaunchedEffect(type) { if (searchHeader == null) vm.load(type) }
    // Typing in the library's search box: results arriving mustn't take focus (and the keyboard) away.
    var librarySearching by remember { mutableStateOf(false) }
    // The library grid looks after its own focus (first title, the one you came back to, A–Z jumps).
    // Rows arrive after the screen opens: grab focus once there's something to focus.
    // (Not under a full-screen film: the page and the film took the highlight off each other in turn.)
    val covered = VodHost.covering
    LaunchedEffect(state.rows.isNotEmpty(), focusEnabled, detail == null, covered) {
        if (searchHeader != null || !focusEnabled || covered || detail != null || state.rows.isEmpty() || hasFocus) return@LaunchedEffect
        awaitFrame()
        awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }

    // One scroll per move: the list itself puts the focused row (title included) at the top. Previously a second,
    // manual scroll ran after the built-in one and the rows jumped up and down.
    // Sideways: keep the focused poster at the row's left edge (no half-cut poster on the left).
    val leftPad = with(LocalDensity.current) { 48.dp.toPx() }
    val defaultSpec = LocalBringIntoViewSpec.current
    val leftSpec = remember(leftPad) { insetSpec(leftPad) }
    val rowSpec = if (remote.rowAlign == RowAlign.Free) defaultSpec else leftSpec
    // Each row remembers its scroll and focused poster, even after it scrolls out of view and is disposed.
    val rowStates = remember(type) { HashMap<String, LazyListState>() }
    val rowPos = remember(type) { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    val titleSpace = with(LocalDensity.current) { 28.dp.toPx() }
    val topAligned = remember(titleSpace) { insetSpec(titleSpace) }

    // (A row with nothing in it isn't drawn: it left an empty gap with a title that the highlight couldn't reach.)
    val rows = remember(state.rows) { state.rows.filter { it.items.isNotEmpty() } }
    // Cinema: one page-tall row at a time; Viewarr style: two rows a page (the list below says how they turn).
    val cinemaRows = AppColors.palette.heroStyle == 1 && searchHeader == null
    // (Live TV behind the page too, as My Library opens: it fell back to Android's own slow scroll there. 2026-11-20.)
    val pairRows = AppColors.palette.heroStyle == 0 && searchHeader == null
    // Scrolling the list to a row by hand: lined up as the page turns are (a pair's first row, the title's room
    // above it), or the next page turn started from a half-turned page.
    val scrollRow: suspend (Int) -> Unit = { i ->
        // (Every row turns on its own now: the highlighted row always at the top. 2026-11-19.)
        runCatching { listState.scrollToItem(i, 0) }
    }
    // Where focus is in the rows, for Back (Netflix): first back to the top row's first poster, then leave.
    var focusedRow by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var focusedIndex by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var quick by remember { mutableStateOf<MediaItem?>(null) }
    var quickTrailer by remember { mutableStateOf<MediaItem?>(null) }
    val personShown by vm.person.collectAsStateWithLifecycle()
    BackHandler(enabled = focusEnabled && detail == null && quick == null && quickTrailer == null) {
        // In a library: Back returns to the page's rows.
        if (browse != null) {
            // From a collection picked in your list of them: back to that list.
            if (vm.libraryBack(type)) return@BackHandler
            vm.openLibrary(null, type)
            scope.launch { awaitFrame(); awaitFrame(); runCatching { focusRequester.requestFocus() } }
            return@BackHandler
        }
        // Search, a person's films and shows: Back to the results, on the card the person (or the title whose cast
        // they were in) was opened from; it used to land on the first poster.
        if (personShown != null) {
            vm.closePerson()
            lastCard = openedCard ?: lastCard
            openedCard = null
            scope.launch {
                awaitFrame()
                // (The results as the list draws them: fresh from the model, the empty rows left out.)
                focusCard(vm.rows.value.rows.filter { it.items.isNotEmpty() }, lastCard, listState, rowStates, cinemaRows || pairRows, scrollRow, focusRequester)
            }
            return@BackHandler
        }
        val top = rows.firstOrNull()
        // (Not with a channel playing small top left: Back enlarges it in one press, it doesn't scroll to the top first.)
        val playingSmall = LiveBehind.preview
        if (searchHeader == null && hasFocus && top != null && (focusedRow > 0 || focusedIndex > 0) && !playingSmall) {
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
    // Menu → Jump to a row: once the menu has gone (until then the posters can't take focus, and the jump was used up
    // with focus landing wherever the page put it), that row's first poster. Tried for a moment while it's drawn.
    LaunchedEffect(jump, rows.size, focusEnabled, detail == null) {
        val (rowId, _) = jump ?: return@LaunchedEffect
        if (!focusEnabled || detail != null) return@LaunchedEffect
        val index = if (rowId == null) 0 else rows.indexOfFirst { it.id == rowId }
        val row = rows.getOrNull(index) ?: return@LaunchedEffect
        rowPos[row.id] = 0
        rowStates[row.id]?.let { runCatching { it.scrollToItem(0) } }
        row.items.firstOrNull()?.let { lastCard = cardId(row, it) }
        listState.scrollToItem(index)
        repeat(6) {
            awaitFrame(); awaitFrame()
            if (runCatching { focusRequester.requestFocus() }.isSuccess && hasFocus) { vm.jumpDone(); return@LaunchedEffect }
            listState.scrollToItem(index)
            delay(80)
        }
        vm.jumpDone()
    }
    // Back from a title opened from the rows: straight back to that poster. Without this, focus fell to whichever
    // poster grabbed it first as the title page went (the first card of the row above) and stayed there.
    LaunchedEffect(detail == null) {
        if (detail != null || browse != null) return@LaunchedEffect
        val key = openedCard ?: return@LaunchedEffect
        val rowIndex = rows.indexOfFirst { r -> r.items.any { cardId(r, it) == key } }
        // A person's page opened from the title (the title closed for it, its rows gone): the card is kept for Back
        // from the person.
        if (rowIndex < 0) { if (personShown == null) openedCard = null; return@LaunchedEffect }
        openedCard = null
        lastCard = key
        focusCard(rows, key, listState, rowStates, cinemaRows || pairRows, scrollRow, focusRequester)
    }
    // Where the highlight goes into the rows: the card you were on; else the row you were on (one put in above it
    // moved the rows under the highlight); else the first row's first poster.
    val entryCard = lastCard?.takeIf { key -> rows.any { r -> r.items.any { cardId(r, it) == key } } }
        ?: rows.getOrNull(focusedRow)?.let { r -> r.items.getOrNull(rowPos[r.id] ?: 0)?.let { cardId(r, it) } }
        ?: rows.firstOrNull()?.let { r -> r.items.firstOrNull()?.let { cardId(r, it) } }
    // Rows keep arriving after the page opens (slow servers, many libraries) and can be put in above the one you're
    // on, which drops focus: the remote then does nothing until a direction is pressed. Take it back by itself.
    // A row that arrives late can land above the top of the list (out of sight): while you're not in the rows yet,
    // keep the list at its top so the first row (e.g. search's "In your library") is the one you see.
    LaunchedEffect(rows.firstOrNull()?.id) { if (!hasFocus && rows.isNotEmpty()) runCatching { listState.scrollToItem(0) } }
    val upNextShown by vm.upNext.collectAsStateWithLifecycle()
    LaunchedEffect(hasFocus, focusEnabled, covered, detail == null, quick == null, quickTrailer == null, playing == null, rows.size, upNextShown == null) {
        if (searchHeader != null || upNextShown != null || hasFocus || !focusEnabled || covered || detail != null || quick != null || quickTrailer != null || playing != null) return@LaunchedEffect
        if (rows.isEmpty() || browse != null) return@LaunchedEffect
        delay(400)
        if (hasFocus) return@LaunchedEffect
        // The poster that takes focus has to be on screen: bring its row (and it, along the row) in first.
        focusCard(rows, entryCard, listState, rowStates, cinemaRows || pairRows, scrollRow, focusRequester)
    }
    // Trailers rest while a pop-up (hold OK), the full-screen trailer or a film (full screen or in the corner) is up.
    val quiet = quick == null && quickTrailer == null && !VodHost.active

    // Movies and Shows: the highlighted title's artwork fills the page, whatever the wallpaper setting.
    if (searchHeader == null) androidx.compose.runtime.DisposableEffect(Unit) {
        Wallpaper.titlePages++
        onDispose { Wallpaper.titlePages-- }
    }
    // Over live TV (opened from the menu while a channel was full screen): a see-through dark page, so it shows.
    val overLive = LiveBehind.on && searchHeader == null
    Box(Modifier.fillMaxSize().then(when {
        searchHeader != null -> Modifier
        // Over live TV: as see-through as the Glass setting says (0% solid … 100% nearly clear; a touch stays so the
        // text can be read).
        overLive -> Modifier.background(AppColors.palette.main.copy(alpha = maxOf(0.12f, AppColors.palette.menuAlpha)))
        // The app's own background already shows the artwork full screen, under the menu too (Wallpaper.titleBehind):
        // nothing of its own, so the picture runs on unbroken across the menu (a second copy, cropped to the page's
        // smaller size, met it in a visible join at the menu's edge. 2026-11-17).
        Wallpaper.titleBehind -> Modifier
        else -> Modifier.appBackground(titleArt = true)
    })) {
        // A library's grid has no hero: the background follows the highlighted title here too (it kept the last
        // page's artwork, e.g. a show's on the films' grid).
        LaunchedEffect(browse != null) {
            if (browse == null) return@LaunchedEffect
            androidx.compose.runtime.snapshotFlow { focused?.backdrop }.collectLatest { art -> delay(HERO_SETTLE_MS); art?.let { Wallpaper.title = it } }
        }
        // As the catalogues' pages: the top-left window (live TV, or the highlighted title's trailer) with its details
        // beside it, and the library's grid below.
        browse?.let { b ->
            LibraryPage(
                b = b, vm = vm, type = type, heroLabel = heroLabel, focused = hero,
                trailerOk = focusEnabled && !covered && hasFocus && detail == null && playing == null && quiet,
                focusRequester = focusRequester,
                gridEnabled = focusEnabled && detail == null && playing == null && quick == null,
                searching = librarySearching,
                onSearchFocus = { librarySearching = it },
                onFocus = { item -> focused = item },
                onHasFocus = { hasFocus = it },
                onLong = { quick = it },
                onLeftEdge = onOpenMenu,
            )
        }
        // A person's page (PersonPage.kt) in place of the rows.
        if (browse == null && personShown != null) CompositionLocalProvider(LocalButtonsFree provides (detail == null && playing == null)) {
            PersonPageHost(vm, focusRequester, focusEnabled && !covered && detail == null && playing == null, onOpenMenu)
        }
        if (browse == null && personShown == null) Column(Modifier.fillMaxSize()) {
            val personInfo by vm.personInfo.collectAsStateWithLifecycle()
            val pi = personInfo?.takeIf { personShown != null }
            SideEffect { PosterTrailer.repo = vm.trailers }
            PublishPosterTrailerActive(focusEnabled && !covered && hasFocus && detail == null && playing == null && quiet)
            // Trending: its first row carousels while the highlight is off the page; the header shows its title.
            val spot = RowCarousel(setNow == Discover.TRENDING && pi == null && searchHeader == null && !hasFocus && detail == null,
                rows.firstOrNull(), rows.firstOrNull()?.let { rowStates.getOrPut(it.id) { LazyListState() } })
            PageHeader(
                person = pi, searchHeader = searchHeader, type = type, heroLabel = heroLabel,
                item = spot ?: hero ?: rows.firstOrNull()?.items?.firstOrNull(),
                // Only the poster you've stopped on plays: not the first-row fallback, nor while anything covers the rows.
                trailerItem = hero,
                trailerOk = spot == null && onHero && focusEnabled && !covered && hasFocus && detail == null && playing == null && quiet,
                vm = vm,
            )
            // Cinema: one page-tall row at a time, the posters along the bottom. Moving to another row turns the
            // page: the list glides so that row's top is at the top (its posters at the bottom), eased over a third of
            // a second; nothing else ever scrolls it (Left / Right along a row pulled the rows up, 2026-11-18).
            // Viewarr style: two rows a page (2026-11-18: the next row's title peeked out at the bottom). Rows go
            // in pairs: a gap after every second row so the next pair starts a page down, and Down / Up move a pair
            // at a time; the list never scrolls on its own otherwise. (cinemaRows / pairRows are set above the rows.)
            var listHeightPx by remember { mutableStateOf(0) }
            val glideScope = rememberCoroutineScope()
            val glideJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
            // The jump to the newly highlighted row, in the same instant as the highlight moves (a frame later, the
            // ring showed on the lower row for a moment: 2026-11-19), then the eased slide drawn over it.
            val jumpToRow: (Int) -> Boolean = jump@{ target ->
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target } ?: return@jump false
                val moved = info.offset.toFloat()
                if (moved == 0f) return@jump true
                // The list really scrolls (so the row leaving at the top slides away instead of vanishing), eased over
                // 280 ms, the highlighted poster coming up with its row.
                glideJob[0]?.cancel()
                glideJob[0] = glideScope.launch {
                    // (Another press mid-way: this one stops where it is and the next scrolls on from there to its row.)
                    var done = 0f
                    androidx.compose.animation.core.animate(0f, moved,
                        animationSpec = androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { v, _ ->
                        done += listState.dispatchRawDelta(v - done)
                    }
                }
                true
            }
            val rowHeightsPx = remember { mutableStateMapOf<Int, Int>() }
            val gapPx = with(LocalDensity.current) { RowGap.roundToPx() }
            // The highlighted row always in the first row's place (2026-11-19: "the focus poster should always be
            // there; only the posters move"): the list jumps there in the same frame as the highlight moves, then the
            // rows are eased up (or down) into place, the highlighted poster's ring going with its row. Rows above the highlighted one are off the
            // top of the list, so none peeks out under the top panel.
            LaunchedEffect(cinemaRows, pairRows, focusedRow) {
                if (!cinemaRows && !pairRows) return@LaunchedEffect
                // (Normally already done as the highlight moved: jumpToRow. Here for a row that wasn't on screen.)
                if (!jumpToRow(focusedRow)) listState.scrollToItem(focusedRow, 0)
            }
            when {
                searchHeader != null && rows.isEmpty() && state.done -> searchFooter?.invoke()
                rows.isEmpty() && !state.done -> Text(
                    if (searchHeader != null) "Searching…" else "Loading ${if (type == MediaType.Movie) "films" else "shows"}…",
                    color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp),
                )
                rows.isEmpty() -> Text(
                    "Nothing to show yet. Connect Plex, TMDB, Trakt or an addon in Settings → Accounts & sources.",
                    color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp, end = 48.dp),
                )
                else -> CompositionLocalProvider(
                    LocalBringIntoViewSpec provides remember(topAligned, cinemaRows, pairRows) {
                        if (cinemaRows || pairRows) CinemaNoScroll else topAligned.followingSetting()
                    },
                    LocalButtonsFree provides (detail == null && playing == null),
                ) {
                    LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).onFocusChanged { hasFocus = it.hasFocus }.onSizeChanged { listHeightPx = it.height }
                        .clipToBounds()
                        // Play / Pause on the remote while the corner trailer plays: that trailer full screen.
                        .onPreviewKeyEvent { e ->
                            // Down / Up: the list moved to the next row from the key press (see jumpToRow; done from the
                            // focus change it crashed the app, Compose being mid-way through applying that change).
                            if (e.type == KeyEventType.KeyDown && (cinemaRows || pairRows) && !PosterTrailer.panel) when (e.key) {
                                Key.DirectionDown -> if (focusedRow < rows.lastIndex) jumpToRow(focusedRow + 1)
                                Key.DirectionUp -> if (focusedRow > 0) jumpToRow(focusedRow - 1)
                                else -> Unit
                            }
                            val playKey = e.key == Key.MediaPlayPause || e.key == Key.MediaPlay
                            if (playKey && e.type == KeyEventType.KeyDown && HeroTrailer.showing && focused != null) { quickTrailer = focused; true } else false
                        },
                    // Room below the last row so it can also scroll up to the top (no half row left above it). Pairs
                    // with an odd row count: enough under the lone last row for its page to line up like the others.
                    contentPadding = PaddingValues(bottom = when {
                        cinemaRows -> 0.dp
                        // (Room under the last row for it to come up to the top too.)
                        pairRows -> with(LocalDensity.current) {
                            maxOf(150.dp, (listHeightPx - (rowHeightsPx[rows.lastIndex] ?: 0)).coerceAtLeast(0).toDp())
                        }
                        else -> 150.dp
                    }),
                    verticalArrangement = Arrangement.spacedBy(RowGap),
                ) {
                    itemsIndexed(rows, key = { _, it -> it.id }, contentType = { _, _ -> "row" }) { index, row ->
                        // Cinema (big top panel): each row the whole height left, so one row shows at a time, no half
                        // row under it (2026-11-18); Down brings the next one up.
                        // (Its posters along the bottom of the screen, the backdrop above them, as Nuvio.)
                        // (Pairs: the room under an odd row that puts the next pair exactly a page down.)
                        // (No pairs any more: every row turns on its own. Kept at 0.)
                        val pairGap = if (false) with(LocalDensity.current) {
                            (listHeightPx - (rowHeightsPx[index - 1] ?: 0) - (rowHeightsPx[index] ?: 0) - 2 * gapPx).coerceAtLeast(0).toDp()
                        } else 0.dp
                        Box(if (cinemaRows) Modifier.fillParentMaxHeight().padding(bottom = 10.dp) else Modifier.padding(bottom = pairGap), contentAlignment = Alignment.BottomStart) {
                        Box(if (pairRows) Modifier.onSizeChanged { rowHeightsPx[index] = it.height } else Modifier) {
                        PosterRow(
                            row = row,
                            rowSpec = rowSpec,
                            pinned = remote.rowAlign != RowAlign.Free,
                            state = rowStates.getOrPut(row.id) { LazyListState() },
                            entryIndex = if (remote.rowMemory == RowMemory.First) 0 else (rowPos[row.id] ?: 0).coerceAtMost(row.items.lastIndex),
                            // (Not while a title is open over the rows: Back to its poster found the row wound back
                            // to the start and the poster gone.)
                            resetOnLeave = remote.rowMemory == RowMemory.First && detail == null,
                            onFocusIndex = {
                                rowPos[row.id] = it
                                focusedRow = index
                                focusedIndex = it
                                vm.setCurrentRow(row.id)
                            },
                            onLongOpen = { if (!it.key.startsWith("person:")) quick = it },
                            // (Left on the first poster: always the menu.)
                            onLeftEdge = onOpenMenu,
                            // Only the row that has it gets it: given to every row, each move rebuilt them all.
                            entryCard = entryCard?.takeIf { it.startsWith(row.id + "|") },
                            entryFocus = focusRequester,
                            onFocus = { item, id ->
                                focused = item
                                lastCard = id
                            },
                            onOpen = { openedCard = lastCard; vm.open(it) },
                        )
                        }
                        }
                    }
                    searchFooter?.let { footer -> item(key = "search-footer") { footer() } }
                }
                RowsLookAhead(listState, rows) }
            }
        }
        // While a film plays full screen, the title page stays alive under it, set aside: no keys, no Back, and not
        // drawn once the player covers it. It used to be removed the moment the film grew (the library flashed up
        // under the growing picture) and rebuilt from nothing when it went small again (the stutter). 2026-11-18.
        var titleDrawn by remember { mutableStateOf(true) }
        LaunchedEffect(playing != null) { if (playing != null) { delay(600); titleDrawn = false } else titleDrawn = true }
        detail?.let { d -> Box(Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = if (playing != null && !titleDrawn) 0f else 1f }
            .then(if (playing != null) Modifier.noFocusInside() else Modifier)) {
            TitlePageLayer(
                ui = d, vm = vm,
                // Not while the menu (hold Back), search or Accounts is over it: its focus trap kept the remote
                // here, under the menu. Focus comes back to Play when they close. (Nor under the next episode's
                // countdown: the trap kept the highlight off its buttons.)
                active = playing == null && upNextShown == null && (focusEnabled || cornerHasFocus),
                onLeftEdge = onOpenMenu, focusRequester = focusRequester,
                hidden = playing != null,
            )
        } }
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
        MediaNotice(vm)
        if (playing == null) upNextShown?.let { UpNextOverlay(it.episode, onPlay = vm::playUpNext, onCancel = vm::cancelUpNext) }
    }
}

/**
 * The top of the Movies / Shows / search page: a person's details, search's own header, or the highlighted title's hero
 * (Cinema's taller), or Lite's one line when the hero is off. [trailerItem] / [trailerOk]: what may play a trailer now.
 */
@Composable
private fun PageHeader(
    person: PersonInfo?, searchHeader: (@Composable () -> Unit)?, type: MediaType, heroLabel: String?,
    item: MediaItem?, trailerItem: MediaItem?, trailerOk: Boolean, vm: MediaViewModel,
) {
    if (person != null) PersonHeader(person) else if (searchHeader != null) searchHeader() else if (AppColors.palette.heroStyle != 2) Hero(
        item = item,
        type = type,
        label = heroLabel,
        trailerItem = trailerItem,
        trailerActive = trailerOk && !PosterTrailer.on,
        trailers = vm.trailers,
        librarySourcesOf = vm::librarySources,
        logoOf = vm::logoFor,
        ratingsOf = vm::ratingsFor,
        // (Previewarr: as tall as its home screen's banner, so the rows start at the same height on every page and its
        // trailer is the same size and place as on home. 2026-11-25.)
        height = if (io.github.petebutty.viewarr.ui.launcher.LauncherLook.on) 300.dp else if (AppColors.palette.heroStyle == 1) 296.dp else 212.dp,
    ) else LiteStrip(type, item, heroLabel)
}

/**
 * A library's page (menu → Movies / Shows → a library): the header (banner and trailer, always there while the grid
 * scrolls) above the grid. [trailerOk]: the highlighted poster may play a trailer now; [gridEnabled]: the grid may take
 * focus (nothing over it).
 */
@Composable
private fun LibraryPage(
    b: MediaViewModel.LibraryBrowse,
    vm: MediaViewModel,
    type: MediaType,
    heroLabel: String?,
    focused: MediaItem?,
    trailerOk: Boolean,
    focusRequester: FocusRequester,
    gridEnabled: Boolean,
    searching: Boolean,
    onSearchFocus: (Boolean) -> Unit,
    onFocus: (MediaItem) -> Unit,
    onHasFocus: (Boolean) -> Unit,
    onLong: (MediaItem) -> Unit,
    onLeftEdge: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // (The header stays while scrolling: banner and trailer always there.)
        val heroOn = !LiveBehind.on && AppColors.palette.heroStyle != 2
        // Trailers in the poster: whether the highlighted poster may play one now.
        SideEffect { PosterTrailer.repo = vm.trailers }
        PublishPosterTrailerActive(trailerOk)
        if (heroOn) Hero(
            item = focused,
            type = type,
            label = heroLabel,
            trailerItem = focused,
            trailerActive = trailerOk && !PosterTrailer.on,
            trailers = vm.trailers,
            librarySourcesOf = vm::librarySources,
            logoOf = vm::logoFor,
            ratingsOf = vm::ratingsFor,
            // (One height for every style, and shorter than the home pages' 206: the library's rows sat too far down.
            // The corner trailer window shrinks to fit it. 2026-11-19.)
            // (With live TV in the guide's preview place at the top left, the header is as tall as that window, so
            // the library's own heading and toolbar sit under it, not hidden behind it. 2026-11-19.)
            // (205: the window ends at 200, so the toolbar sits just under it; 250 left a gap and cut the grid's second row
            // off. 2026-11-20: "needs to come up a bit".)
            height = if (io.github.petebutty.viewarr.ui.launcher.LauncherLook.on) 300.dp else if (LiveBehind.preview) 205.dp else 180.dp,
            compact = true,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LibraryGrid(
                b = b,
                vm = vm,
                focusRequester = focusRequester,
                focusEnabled = gridEnabled,
                focused = focused,
                onFocus = onFocus,
                onHasFocus = onHasFocus,
                searching = searching,
                onSearchFocus = onSearchFocus,
                onOpen = { vm.open(it) },
                onLong = onLong,
                onLeftEdge = onLeftEdge,
                withHero = heroOn,
            )
        }
    }
}

/** The title page over the rows, with its model's actions wired. */
@Composable
private fun TitlePageLayer(ui: DetailUi, vm: MediaViewModel, active: Boolean, onLeftEdge: () -> Unit, focusRequester: FocusRequester, hidden: Boolean = false) {
    androidx.compose.runtime.key(ui.detail.item.key) {
        DetailScreen(
            ui = ui,
            vm = vm,
            active = active && !hidden,
            hidden = hidden,
            onSearch = vm::searchAddons,
            onPickStream = vm::playStream,
            onCloseStreams = vm::closeStreams,
            onWatchlist = vm::toggleWatchlist,
            onFollow = vm::toggleFollow,
            onLeftEdge = onLeftEdge,
            onClose = {
                vm.close()
                // Back to the title it was opened from (More like this) keeps focus on that page.
                if (vm.detail.value == null) runCatching { focusRequester.requestFocus() }
            },
        )
    }
}

/** Brings what's focused to [inset] px from the list's start edge (no scrolling when it's already within a pixel of it). */
@OptIn(ExperimentalFoundationApi::class)
private fun insetSpec(inset: Float) = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val d = offset - inset
        return if (kotlin.math.abs(d) < 1f) 0f else d
    }
}

private fun cardId(row: MediaRow, item: MediaItem) = row.id + "|" + item.key

/**
 * The highlight onto the card [key] (one of [rows], by [cardId]): its row scrolled in if it's off screen (always
 * lined up afresh when the rows turn by the page, [paged]), then the card along its row (one opened deep in a row
 * had been let go while the title was up, so there was nothing to highlight), then [focus] once both are drawn.
 */
private suspend fun focusCard(
    rows: List<MediaRow>, key: String?, listState: LazyListState, rowStates: Map<String, LazyListState>, paged: Boolean,
    scrollRow: suspend (Int) -> Unit, focus: FocusRequester,
) {
    val rowIndex = rows.indexOfFirst { r -> r.items.any { cardId(r, it) == key } }.coerceAtLeast(0)
    val row = rows.getOrNull(rowIndex)
    if (paged || listState.layoutInfo.visibleItemsInfo.none { it.index == rowIndex }) scrollRow(rowIndex)
    val i = row?.items?.indexOfFirst { cardId(row, it) == key } ?: -1
    val along = row?.let { rowStates[it.id] }
    if (i >= 0 && along != null && along.layoutInfo.visibleItemsInfo.none { it.index == i }) runCatching { along.scrollToItem(i) }
    awaitFrame(); awaitFrame()
    runCatching { focus.requestFocus() }
}

@Composable
private fun Hero(
    item: MediaItem?,
    type: MediaType,
    /** The page's name over the title: "My Library", a network's name; null: Movies / Shows by [type]. */
    label: String? = null,
    trailerItem: MediaItem? = null,
    trailerActive: Boolean = false,
    trailers: TrailerRepository? = null,
    /** Where a title from an addon row really is, e.g. "Plex" (AIOStreams can pass on your own library). */
    librarySourcesOf: (suspend (MediaItem) -> String?)? = null,
    logoOf: (suspend (MediaItem) -> String?)? = null,
    ratingsOf: (suspend (MediaItem) -> List<RatingBadge>)? = null,
    /** How tall: 212 on the Movies / Shows / service pages (the rows come up a little, the second row's ratings no longer cut
     *  off); library pages a little shorter still. The preview window (20 + 180) fits in either. */
    height: androidx.compose.ui.unit.Dp = 212.dp,
    /** Library pages: the ordinary logo and two-line synopsis even in the Cinema style, so the grid starts higher
     *  (2026-11-19: the library's rows sat too far down). */
    compact: Boolean = false,
) {
    val cinema = AppColors.palette.heroStyle == 1 && !compact
    // The highlighted title's details (logo, ratings, where it's from) fetched together, the logo's picture loaded
    // too, then shown at once with a fade (they used to pop in one by one as each arrived). A slow one doesn't hold
    // the rest up: after a second they show without it, and it slips in when it comes.
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val extras by produceState<HeroExtras?>(null, item?.key) {
        value = null
        val i = item ?: return@produceState
        delay(120) // (moving along a row: only for the one you stop on)
        val logosOn = AppColors.palette.shows(TitleParts.LOGO) && !AppColors.palette.lowMemory
        val tp = TitleParts
        val ratingsWanted = ratingsOf != null && !AppColors.palette.lowMemory && tp.ratings.any { (src, _) -> src != "TMDB" && AppColors.palette.shows(tp.rating(src)) }
        val logoA = async {
            val url = if (logoOf != null && logosOn) runCatching { logoOf(i) }.getOrNull() else null
            // The picture itself, so it's ready when the details show (at the hero logo's size, as TitleLogo asks).
            if (url != null) runCatching { context.imageLoader.execute(if (cinema) logoRequest(context, density, url, 460, 84) else logoRequest(context, density, url, 340, 54)) }
            url
        }
        val ratingsA = async { if (ratingsWanted) runCatching { ratingsOf!!(i) }.getOrNull().orEmpty() else emptyList() }
        val libA = async {
            if (librarySourcesOf != null && i.origin !in LIBRARY_ORIGINS && !i.origin.startsWith("Plex")) runCatching { librarySourcesOf(i) }.getOrNull() else null
        }
        val until = System.currentTimeMillis() + 1_000
        suspend fun <T> waitFor(d: kotlinx.coroutines.Deferred<T>): T? = kotlinx.coroutines.withTimeoutOrNull((until - System.currentTimeMillis()).coerceAtLeast(1)) { d.await() }
        val logo = waitFor(logoA); val ratings = waitFor(ratingsA); val lib = waitFor(libA)
        value = HeroExtras(i.key, logo, ratings.orEmpty(), lib)
        // Late ones, when they come.
        if (logo == null) logoA.await()?.let { l -> value = value?.copy(logo = l) }
        if (ratings == null) value = value?.copy(ratings = ratingsA.await())
        if (lib == null) libA.await()?.let { l -> value = value?.copy(inLibrary = l) }
    }
    val ready = extras?.key == item?.key && item != null
    // Gone the moment the title changes (a new one starts at 0 in that same frame: fading the old out let the new name
    // flash up first), then one fade in once its logo, ratings and library check are in.
    val detailsAnim = remember(item?.key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(item?.key, ready) { if (ready) detailsAnim.animateTo(1f, androidx.compose.animation.core.tween(550)) }
    val inLibrary = extras?.inLibrary
    // The Movies / Shows background: the highlighted title's backdrop.
    LaunchedEffect(item?.backdrop) { item?.backdrop?.let { Wallpaper.title = it } }
    // On the Movies and Shows pages the artwork fills the whole page (drawn by the page's background), so the
    // hero doesn't draw its own copy (it left a seam where it ended).
    // (Your wallpaper behind Movies / Shows: the hero shows the title's artwork itself, as a panel.)
    // (None: no picture at all, the panel's own copy included.)
    val pageArt = (Wallpaper.titlePages > 0 && AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.TITLE_BG) != 1) || LiveBehind.on
    // (Clipped: a tall logo with a long description ran down over the library's heading and search box below.)
    // (Viewarr launcher: not clipped, so its trailer can run down behind the first row; the details are clipped instead.)
    val launcherHero = io.github.petebutty.viewarr.ui.launcher.LauncherLook.on
    Box(Modifier.fillMaxWidth().height(height).then(if (launcherHero) Modifier else Modifier.clipToBounds()).clearAndSetSemantics { item?.title?.let { contentDescription = it } }) {
        if (!pageArt) item?.backdrop?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // The picture itself fades out to its left and bottom edges (see-through there), so it melts into the
                // page behind whatever its colour. (2026-11-24: a dark shade over the whole header ended in a
                // hard line against the page's background below it and at its left.)
                modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.68f)
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = 0.15f))
                        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.75f), 0.8f to Color.Black),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                        drawRect(Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    },
            )
        }
        // The top-left window, always in the same place (the guide's preview place): live TV when a channel's on
        // (drawn by the home screen), otherwise the highlighted title's picture with its trailer playing over it.
        // (Trailers in the poster: no corner window, so the details start at the left edge. 2026-11-18.)
        val besidePreview = !LiveBehind.on && !PosterTrailer.on
        // The trailer's window: nothing there until a trailer plays (it fades in), the page's background picture
        // showing through instead of a second copy of it in a box (2026-11-18).
        // The trailer window at the top right (2026-11-18: the title logo and description belong at the left,
        // where the live TV window used to push them from): a dimmed still of the title until its trailer fades in.
        // (A shorter header, the library pages: a smaller window that still fits.)
        val winH = if (height < 206.dp) (height - 24.dp) else 180.dp
        // Viewarr launcher: the trailer without a frame, faded into the page (as on its home screen), over the
        // header's right-hand side: libraries and catalogues alike (2026-11-25).
        if (besidePreview && !LiveBehind.preview && io.github.petebutty.viewarr.ui.launcher.LauncherLook.on) {
            if (trailers != null) // (Exactly as on home: in the corner picture's place and size, the picture fading away under it, so there's no
            // edge where a smaller trailer met it; it runs down behind the first rows and fades out. 2026-11-25.)
            // (The same size and place as on home: the header is home's banner height here. 2026-11-25.)
            FadedTrailer(trailerItem, trailerActive, trailers, fadeEnd = 300.dp, onShowing = { HeroTrailer.showing = it })
        } else if (besidePreview && !LiveBehind.preview) Box(
            Modifier.align(Alignment.TopEnd).padding(end = Tokens.Gutter, top = if (height < 206.dp) 12.dp else 20.dp).size(width = winH * 16 / 9, height = winH).clip(RoundedCornerShape(8.dp)),
        ) {
            // (No still picture first: the window stays empty until the trailer itself fades in. 2026-11-19.)
            if (trailers != null) TrailerPreview(item = trailerItem, active = trailerActive, trailers = trailers, modifier = Modifier.fillMaxSize(), onShowing = { HeroTrailer.showing = it })
            // While it plays: how to see it full screen (hold OK on the poster → Trailer does the same).
            if (HeroTrailer.showing) Text("⏯  Full screen", fontSize = 11.sp, color = Color.White, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        // From the left edge; with the live TV window up at the top left, over at the right, right-aligned (2026-11-18).
        val endSide = LiveBehind.preview
        val align = if (endSide) androidx.compose.ui.text.style.TextAlign.End else null
        val colAlign = if (endSide) Alignment.End else Alignment.Start
        Column(
            Modifier.align(if (endSide) Alignment.TopEnd else Alignment.TopStart)
                // (The header itself isn't clipped in the launcher: its details are, to the header's height.)
                .then(if (launcherHero) Modifier.heightIn(max = height).clipToBounds() else Modifier)
                .padding(start = Tokens.Gutter, top = if (endSide) 14.dp else 26.dp, end = if (endSide) 0.dp else Tokens.Gutter)
                .fillMaxWidth(if (endSide) 0.6f else if (besidePreview) 0.6f else 0.5f)
                // Over at the right it sits on the artwork: a shade behind it, fading in from the left, so the details
                // and description stay readable on a bright picture (2026-11-20).
                // (Its full height from the start: sized by the details, it grew when they came in. 2026-11-21.)
                .then(if (endSide) Modifier.padding(bottom = 10.dp).fillMaxHeight().background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to Color.Transparent, 0.3f to AppColors.palette.main.copy(alpha = 0.7f), 1f to AppColors.palette.main.copy(alpha = 0.85f)),
                    RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)).padding(start = 24.dp, top = 12.dp, bottom = 14.dp, end = Tokens.Gutter)
                else Modifier),
            horizontalAlignment = colAlign,
        ) {
            Text(label ?: if (type == MediaType.Movie) "Movies" else "Shows", fontSize = 14.sp, color = AppColors.Accent, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            val ex = extras
            // (Cinema: the details rise a little as they fade in, eased, for a cleaner change between titles.)
            // (Then it slides in from off the screen once it's ready, from the side it sits on, easing to a stop.
            // 2026-11-21: it popped up. Read in the layer only, so the slide never lays the page out again.)
            if (item != null) Column(Modifier.graphicsLayer {
                val t = detailsAnim.value
                val e = androidx.compose.animation.core.LinearOutSlowInEasing.transform(t)
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                alpha = (t * 2.5f).coerceAtMost(1f)
                translationX = (1f - e) * (size.width + 48.dp.toPx()) * (if (endSide) 1f else -1f)
            }, horizontalAlignment = colAlign) {
                // (The logo already fetched and its picture loaded: shown straight from there.)
                val exLogo = ex?.takeIf { it.key == item.key }?.logo
                val logoFn: (suspend (MediaItem) -> String?)? = if (ex?.key == item.key) ({ _: MediaItem -> exLogo }) else null
                if (cinema) TitleLogo(item, logoFn, height = 84.dp, maxWidth = 460.dp, fontSize = 34, known = exLogo, end = endSide)
                else TitleLogo(item, logoFn, height = 54.dp, maxWidth = 340.dp, fontSize = 26, known = exLogo, end = endSide)
                Spacer(Modifier.height(6.dp))
                Text(
                    inLibrary?.let { where -> metaLine(item.copy(origin = "$where · via ${item.origin}")) } ?: metaLine(item),
                    fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, textAlign = align,
                )
                // IMDb / Rotten Tomatoes / Trakt for the highlighted title (fetched with the rest, above).
                val heroRatings = ex?.takeIf { it.key == item.key }?.ratings.orEmpty()
                // (One line of ratings here, the ones that don't fit left off: wrapping onto a second line pushed the
                // description out of the hero's height, cut off mid-line. 2026-11-17.)
                // (Cinema: no ratings; the title as its logo, its details and the description. 2026-11-18.)
                val cinemaHero = cinema
                if (heroRatings.isNotEmpty() && !cinemaHero) {
                    Spacer(Modifier.height(6.dp))
                    RatingsLine(null, heroRatings, oneLine = true)
                }
                item.subtitle?.let {
                    Spacer(Modifier.height(4.dp))
                    var tooLong by remember(it) { mutableStateOf(false) }
                    Text(it, fontSize = 14.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (tooLong) androidx.compose.ui.text.style.TextAlign.Start else align,
                        onTextLayout = { r -> if (align != null && !tooLong && (r.hasVisualOverflow || r.isLineEllipsized(0))) tooLong = true })
                }
                // Ratings and a subtitle (or the shorter library hero): one line of description, so it all fits.
                val roomy = heroRatings.isEmpty() || (item.subtitle == null && height >= 212.dp)
                item.overview?.let {
                    Spacer(Modifier.height(10.dp))
                    // (Right-aligned text that doesn't fit lost its "…" and was cut mid-word (Android's end-aligned ellipsis):
                    // a description too long for its lines is laid out from the left instead. 2026-11-21.)
                    var tooLong by remember(it) { mutableStateOf(false) }
                    Text(it, fontSize = if (cinemaHero) 15.sp else 14.sp, lineHeight = if (cinemaHero) 22.sp else 20.sp, color = AppColors.TextPrimary,
                        // (Over at the right: two shorter lines, not one very long one. 2026-11-21.)
                        // (On the left: kept clear of the picture, cut off with "…" rather than running across it.
                        // 2026-11-24: the description ran right over the Trending picture.)
                        maxLines = if (cinemaHero) 3 else if (roomy || endSide) 2 else 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = if (endSide) 420.dp else 440.dp),
                        textAlign = if (tooLong) androidx.compose.ui.text.style.TextAlign.Start else align,
                        onTextLayout = { r -> if (align != null && !tooLong && (r.hasVisualOverflow || r.isLineEllipsized(r.lineCount - 1))) tooLong = true })
                }
            }
        }
    }
}

/** The header's details for one title, fetched together (see Hero). */
private data class HeroExtras(val key: String, val logo: String?, val ratings: List<RatingBadge>, val inLibrary: String?)

/** Settings → Appearance → Library posters: how wide each poster is (Small: the rows' own 100 dp, so they match). */
private val GRID_POSTER_DP get() = AppColors.palette.libraryPosterDp

/**
 * A whole library as a poster grid, one page at a time (so a big library never weighs on the box), with the sort
 * choices along the top. The last card goes to the next page, the first (after page 1) to the previous one.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    /** The page's header above it (the highlighted title's details): no copy of them here, and less room on top. */
    withHero: Boolean = false,
) {
    // How many posters fit across, worked out from the space the grid really has (it's narrower with the menu or the
    // mini player beside it), so each poster is the same size as everywhere else.
    var gridColumns by remember { androidx.compose.runtime.mutableIntStateOf(8) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gridScope = rememberCoroutineScope()
    val gridState = remember(b.library.key, b.version) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    val cells = remember(b.library.key, b.version) { HashMap<Int, FocusRequester>() }
    fun cell(i: Int) = cells.getOrPut(i) { FocusRequester() }
    var panel by remember { mutableStateOf<String?>(null) }
    val railFocus = remember { FocusRequester() }
    // The Sort button (Up from the A–Z strip). The page's own focus target is the grid: coming into a library lands on
    // a poster (it landed on the toolbar's mic).
    val sortFocus = remember { FocusRequester() }
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
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) runCatching { gridState.scrollToItem(idx - idx % gridColumns) }
        awaitFrame(); awaitFrame()
        runCatching { cell(idx).requestFocus() }
    }
    // Where the highlight goes coming into the grid: the poster you were on if it's drawn, else the first one on screen,
    // else (nothing loaded yet) Sort. Only drawn posters can take the highlight: the grid draws what's on screen and a
    // little either side, and sending it to one that isn't drawn could lose it or close the app. (The effect below
    // still scrolls to the one you were on and moves the highlight there once it's drawn.)
    fun gridEntry(): FocusRequester {
        if (count == 0) return sortFocus
        val shown = gridState.layoutInfo.visibleItemsInfo.map { it.index }
        if (shown.isEmpty()) return sortFocus
        val want = (vm.lastIndexFor(b.version) ?: 0).coerceIn(0, count - 1)
        return cell(if (want in shown) want else shown.first())
    }
    // A new list (opened, re-sorted, filtered): its first title. Back from a title, a panel or the menu: the one you were on.
    LaunchedEffect(b.version, count > 0, focusEnabled, panel == null) {
        if (!focusEnabled || panel != null || count == 0 || searching || railOn) return@LaunchedEffect
        focusAt(vm.lastIndexFor(b.version) ?: 0)
    }
    // A–Z: the grid moves to the letter; picked with OK on the strip, focus goes there too.
    LaunchedEffect(b.jump) {
        val j = b.jump ?: return@LaunchedEffect
        if (railOn) runCatching { gridState.scrollToItem(j - j % gridColumns) } else focusAt(j)
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
        Column(Modifier.fillMaxSize().padding(top = if (withHero) 0.dp else 24.dp)) {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryTitle(b)
                // Keyed by library: another library starts with an empty box.
                androidx.compose.runtime.key(b.library.key) {
                    // (Down from the box through focusAt: the poster it goes to is scrolled in and drawn first; aimed
                    // straight at one that wasn't drawn, the highlight went nowhere.)
                    LibrarySearchBox(b.query.query, vm::setLibraryQuery, onSearchFocus, onDone = { gridScope.launch { focusAt(vm.lastIndexFor(b.version) ?: 0) } }, onLeft = onLeftEdge)
                }
                ToolButton(
                    "Sort" + (b.options?.sorts?.firstOrNull { it.id == b.query.sort }?.let { ": ${it.label}" } ?: ""),
                    active = false, modifier = Modifier.focusRequester(sortFocus),
                ) { if (b.options != null) panel = "sort" }
                if (b.options?.filters?.isNotEmpty() == true) ToolButton(
                    if (b.query.active > 0) "Filter · ${b.query.active}" else "Filter",
                    active = b.query.active > 0, modifier = Modifier.padding(start = 8.dp),
                ) { panel = "filter" }
            }
            // What's filtered, each removable; then the highlighted title.
            if (b.query.active > 0) FilterChips(b, vm)
            if (withHero) Spacer(Modifier.height(4.dp)) else Text(
                focused?.takeIf { f -> b.items.any { it.key == f.key } }?.let { "${it.title}   ${metaLine(it)}" } ?: " ",
                fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 6.dp),
            )
            EmptyNote(b)
            Row(Modifier.fillMaxWidth().weight(1f).onSizeChanged { sz ->
                val wDp = with(density) { sz.width.toDp().value }
                // Side padding (48 dp + the A–Z strip's room) and 12 dp between posters.
                val usable = wDp - 48f - (if (b.canLetters) 12f + 40f else 48f) + 12f
                gridColumns = (usable / (GRID_POSTER_DP + 12f)).toInt().coerceIn(4, 12)
            }) {
                // The highlighted row snaps to the top of the grid: each Down / Up moves exactly one whole row, and
                // the row you're on is never cut off (the app's usual resting place, a third of the way down, left
                // too little room under the header: half a poster per press, the row half hidden).
                val rowTop = with(density) { 4.dp.toPx() }
                // Down / Up move the grid a row from the key press, in the same instant as the highlight, and the
                // posters are then drawn where they were and eased into place: one layer moved, nothing laid out again
                // per frame (Android's own scroll re-laid the grid every frame while new posters loaded: slow and
                // jerky; 2026-11-20). Anything left to bring into view snaps.
                val gridSpec = remember(rowTop) { insetSpec(rowTop).snapped() }
                val gridGlide = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                val gridGlideJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
                val gridScope = rememberCoroutineScope()
                val slideGrid: (Int, Int) -> Unit = { from, to ->
                    val info = gridState.layoutInfo.visibleItemsInfo
                    val a = info.firstOrNull { it.index == from }
                    val b2 = info.firstOrNull { it.index == to }
                    if (a != null && b2 != null) {
                        val used = gridState.dispatchRawDelta((b2.offset.y - a.offset.y).toFloat())
                        if (used != 0f) {
                            val step = kotlin.math.abs(used)
                            gridGlide.floatValue = (gridGlide.floatValue + used).coerceIn(-step, step)
                            gridGlideJob[0]?.cancel()
                            gridGlideJob[0] = gridScope.launch {
                                androidx.compose.animation.core.animate(gridGlide.floatValue, 0f,
                                    animationSpec = androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { v, _ -> gridGlide.floatValue = v }
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalBringIntoViewSpec provides gridSpec) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridColumns),
                    state = gridState,
                    // Into the grid (from the menu, from the toolbar, back from a title): the poster you were on, else the
                    // first (Down from the toolbar landed on whichever was nearest; from the menu, on the toolbar).
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .clipToBounds().graphicsLayer { translationY = gridGlide.floatValue }
                        .focusRequester(focusRequester)
                        .focusProperties { enter = { gridEntry() } }
                        .focusGroup()
                        .onFocusChanged { onHasFocus(it.hasFocus) },
                    contentPadding = PaddingValues(start = 48.dp, end = if (b.canLetters) 12.dp else 48.dp, top = 4.dp, bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(count, key = { it }) { i ->
                        val item = b.item(i)
                        val col = i % gridColumns
                        val m = Modifier.focusRequester(cell(i))
                            .onFocusChanged { if (it.isFocused) { vm.setLastIndex(b.version, i); item?.let(onFocus) } }
                            // Left on the first column: the menu (pick another library), once per press.
                            .then(if (col == 0) Modifier.leftOpens(onMenu = onLeftEdge) else Modifier)
                            .onPreviewKeyEvent { e ->
                                // (Back leaves the library in one press: it used to go up to the toolbar first.)
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    // Right at the end of a row: onto the A–Z strip.
                                    e.key == Key.DirectionRight && b.canLetters && (col == gridColumns - 1 || i == count - 1) -> { runCatching { railFocus.requestFocus() }; true }
                                    // (The grid moves from here; the highlight then moves as usual.)
                                    e.key == Key.DirectionDown && i + gridColumns < count -> { slideGrid(i, i + gridColumns); false }
                                    e.key == Key.DirectionUp && i - gridColumns >= 0 -> { slideGrid(i, i - gridColumns); false }
                                    else -> false
                                }
                            }
                        if (item != null) PosterCard(item = item, modifier = m, onClick = { onOpen(item) }, onLongClick = { onLong(item) }, fill = true)
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
                    onUp = { runCatching { sortFocus.requestFocus() } },
                    onLeft = {
                        val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
                        val target = (first + gridColumns - 1).coerceAtMost(count - 1)
                        runCatching { cell(target).requestFocus() }
                    },
                )
            }
        }
        // Moving along the A–Z strip: the letter, big, so you can see where you're going.
        if (railOn) Box(
            Modifier.align(Alignment.Center).size(140.dp).background(AppColors.Panel.copy(alpha = 0.92f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(railLetter.toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, maxLines = 1, softWrap = false) }
        LibraryPanels(b, vm, panel) { panel = null }
    }
}

/** A library's name and what it is on one line (two lines took room from the posters): source, count, sort. */
@Composable
private fun RowScope.LibraryTitle(b: MediaViewModel.LibraryBrowse) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text(b.library.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp))
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
            fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The filters in force along a library's top, each removable. */
@Composable
private fun FilterChips(b: MediaViewModel.LibraryBrowse, vm: MediaViewModel) {
    androidx.compose.foundation.lazy.LazyRow(
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
}

/** Why a library shows nothing: no match for the search, for the filters, or an empty library. */
@Composable
private fun EmptyNote(b: MediaViewModel.LibraryBrowse) {
    when {
        b.total == 0 && b.query.query.isNotBlank() -> Text("Nothing in this library matches \"${b.query.query}\"", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp))
        b.total == 0 && b.filtered -> Text("Nothing in this library matches these filters", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp))
        b.total == 0 -> Text("This library is empty", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 12.dp))
    }
}

/** The Sort / Filter pop-ups over a library ([panel]: "sort", "filter" or null); [onClose] puts it away. */
@Composable
private fun LibraryPanels(b: MediaViewModel.LibraryBrowse, vm: MediaViewModel, panel: String?, onClose: () -> Unit) {
    if (panel == "sort") b.options?.let { o -> SortPanel(o, b.query, onPick = { id -> val same = id == b.query.sort; vm.setLibrarySort(id); if (!same) onClose() }, onDesc = vm::setLibraryDesc, onClose = onClose) }
    if (panel == "filter") b.options?.let { o ->
        FilterPanel(o, b.query, b.values, onOpenGroup = vm::loadFilterValues, onToggle = vm::toggleFlag, onValue = vm::toggleFilterValue, onClear = vm::clearFilters, onClose = onClose)
    }
}




/**
 * Search within a library: a slim box. The keyboard opens only on OK (moving through doesn't pop it up); Search /
 * Done or Down returns to the titles.
 */
@Composable
internal fun LibrarySearchBox(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit, onDone: () -> Unit, onLeft: (() -> Unit)? = null) {
    var text by remember { mutableStateOf(query) }
    var editing by remember { mutableStateOf(false) }
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
    // The shared mic and search box (common/SearchBar.kt), at the library's 36 dp size. (2026-11-17: was its
    // own copy of both.)
    VoiceButton(
        onClick = { voice.toggle() },
        listening = voice.listening, level = voice.level, size = 36.dp, pulse = false,
        // Left on the mic (the first thing in the row): the menu, as Left on the first poster does (it did nothing).
        modifier = Modifier.leftOpens(enabled = onLeft != null) { onLeft?.invoke() },
    )
    Spacer(Modifier.width(8.dp))
    SearchField(
        value = text,
        onValueChange = { text = it; onQuery(it) },
        placeholder = "Search this library",
        readOnly = !editing,
        compact = true,
        listening = voice.listening,
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { editing = false; keyboard?.hide(); onDone() }),
        onFocusChanged = { f -> onFocus(f); if (!f) { editing = false; keyboard?.hide() } },
        modifier = Modifier.padding(end = 8.dp).width(260.dp).height(36.dp)
            .focusRequester(boxFocus)
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
    )
}


/** Rows that already are your library (no need to look the title up). */
internal val LIBRARY_ORIGINS = setOf("Plex", "Jellyfin", "Emby", "IPTV")

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PosterRow(
    row: MediaRow,
    rowSpec: BringIntoViewSpec,
    /** The highlight keeps its place at the row's start (Row alignment not Free): the row slides under it. */
    pinned: Boolean = true,
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
        RowTitle(row.title, Modifier.padding(start = RowStart, end = Tokens.Gutter))
        // The next posters past the right edge, loaded before they slide on.
        RowLookAhead(state, row.items, rowPosterPx())
        val rowEntry = remember { FocusRequester() }
        // True only while the remembered poster is actually composed. A poster can still be listed as visible while
        // its row is being torn down (e.g. leaving Movies for Shows); aiming focus at it then crashed the app
        // ("FocusRequester is not initialized").
        val entryAttached = remember { booleanArrayOf(false) }
        val scope = rememberCoroutineScope()
        // Fixed highlight, sliding posters (2026-11-19: "the focus poster should stay where it is, only the
        // posters move"): the row jumps to its new place in the same frame as the highlight (no slide of its own, so
        // the highlight never runs one poster ahead and comes back), and the posters are then drawn where they were
        // and eased into place (one layer moved, nothing laid out again per frame). The ring is drawn over the row
        // at the highlighted poster's place, not by the poster, so it stays still while the posters slide under it.
        val glide = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val glideJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
        var rowFocused by remember { mutableStateOf(false) }
        var focusedIdx by remember { mutableStateOf(-1) }
        // The row moved by the poster-to-poster step in the same instant as the highlight (a frame later, the posters
        // showed in their new place for a moment, then slid back from the old one), then eased from there.
        // (Done from the key press, not from the focus change: moving the row while Compose was applying a focus
        // change crashed the app, "Unprocessed FocusProperties nodes". 2026-11-19.)
        val slideTo: (Int) -> Unit = slide@{ newIdx ->
            if (!pinned || !rowFocused || focusedIdx < 0) return@slide
            val items = state.layoutInfo.visibleItemsInfo
            val from = items.firstOrNull { it.index == focusedIdx }
            val to = items.firstOrNull { it.index == newIdx }
            // (Not both on screen: no slide; the row jumps to the poster being moved to, which would otherwise be
            // highlighted off the row's edge, this row's own scrolling being off while pinned.)
            if (from == null || to == null) { scope.launch { runCatching { state.scrollToItem(newIdx) } }; return@slide }
            val used = state.dispatchRawDelta((to.offset - from.offset).toFloat())
            if (used == 0f) return@slide
            // Never more than one poster behind: a held or fast-pressed button used to add a step per press (up to 1500
            // px), so the whole row was drawn far to one side and its near side showed empty (2026-11-20).
            val step = kotlin.math.abs(used)
            glide.floatValue = (glide.floatValue + used).coerceIn(-step, step)
            glideJob[0]?.cancel()
            glideJob[0] = scope.launch {
                androidx.compose.animation.core.animate(glide.floatValue, 0f,
                    animationSpec = androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { v, _ -> glide.floatValue = v }
            }
        }
        Box {
        // (Pinned: only slideTo moves the row. The focus system's own bring-into-view, worked out from where the
        // poster was before slideTo's move, scrolled it a second step: Right from the second poster then took two
        // presses. 2026-11-19.)
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(rowSpec, pinned) { if (pinned) NoRowScroll else rowSpec.snapped() }) { LazyRow(
            state = state,
            // Moving onto this row from above or below lands on its remembered poster, not the nearest one on screen.
            modifier = Modifier
                .focusProperties {
                    // Only aim at the remembered poster if it's on screen; otherwise let focus pick normally.
                    enter = {
                        if (entryAttached[0] && state.layoutInfo.visibleItemsInfo.any { it.index == entryIndex }) rowEntry else FocusRequester.Default
                    }
                }
                .onFocusChanged {
                    rowFocused = it.hasFocus
                    if (resetOnLeave && !it.hasFocus && state.firstVisibleItemIndex != 0) scope.launch { state.scrollToItem(0) }
                }
                // (Nothing shows left of the highlighted poster's place: the poster before it was peeking in at the
                // screen's edge. A little room is kept for the highlighted poster's zoom. 2026-11-19.)
                .drawWithContent {
                    val left = (RowStart - 8.dp).toPx()
                    clipRect(left = left, top = -size.height, right = size.width * 2, bottom = size.height * 2) { this@drawWithContent.drawContent() }
                }
                .graphicsLayer { translationX = glide.floatValue }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && pinned && !PosterTrailer.panel) when (e.key) {
                        Key.DirectionRight -> if (focusedIdx < row.items.lastIndex) slideTo(focusedIdx + 1)
                        Key.DirectionLeft -> if (focusedIdx > 0) slideTo(focusedIdx - 1)
                        else -> Unit
                    }
                    false
                },
            contentPadding = PaddingValues(start = RowStart, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(row.items, key = { _, it -> it.key }, contentType = { _, _ -> "poster" }) { index, item ->
                val id = cardId(row, item)
                if (index == entryIndex) DisposableEffect(entryIndex) {
                    entryAttached[0] = true
                    onDispose { entryAttached[0] = false }
                }
                PosterCard(
                    item = item,
                    rowCards = true,
                    modifier = (if (id == entryCard) Modifier.focusRequester(entryFocus) else Modifier)
                        .then(if (index == entryIndex) Modifier.focusRequester(rowEntry) else Modifier)
                        // Left on a row's first poster opens the catalogue menu (once per press: the shared leftOpens).
                        .then(if (index == 0 && onLeftEdge != null) Modifier.leftOpens(onMenu = onLeftEdge) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                focusedIdx = index
                                onFocus(item, id)
                                onFocusIndex(index)
                            }
                        },
                    onClick = { onOpen(item) },
                    onLongClick = { onLongOpen(item) },
                )
            }
        } }
        // (The ring is the poster's own again, as on the Android TV home screen: it goes with the poster being
        // highlighted as that poster slides into the first place, and the poster grows as it arrives.
        // 2026-11-19.)
        }
    }
}

/** A pinned poster row: bring-into-view never scrolls it (PosterRow's slideTo does). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal val NoRowScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}


/** This spec's resting place, reached in the same frame (the posters' own glide is drawn separately). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun BringIntoViewSpec.snapped(): BringIntoViewSpec {
    val base = this
    return object : BringIntoViewSpec {
        override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.snap()
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            base.calculateScrollDistance(offset, size, containerSize)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PosterCard(item: MediaItem, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit = {}, fill: Boolean = false,
                       /** False: the row draws the highlight ring itself (PosterRow: a still ring over sliding posters). */
                       ring: Boolean = true,
                       /** False: no trailer in this card (the title page's small rows). */
                       trailer: Boolean = true,
                       /** A page's poster row (PosterRow): Settings → Row cards → Wide applies (not the info bar's rows,
                        *  person pages or a title page's rows, which kept their poster sizes and came out tiny). */
                       rowCards: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    // Trailers in the poster (rows only, not grids or people): one trailer for the app (PosterTrailerHost) sits on the
    // highlighted poster, which widens to 16:9 once it shows; then it grows into the big panel. This card only starts
    // and stops it and says where it is on screen.
    // Settings → Appearance → Posters → Row cards → Wide: a 16:9 picture (the title's backdrop) with its name over the
    // foot, as the Viewarr launcher's home screen (2026-11-25). Not in a grid, not for people. (No trailer in the card:
    // it's wide already; the corner window plays it.)
    val wide = rowCards && AppColors.palette.wideRows && !fill && !item.key.startsWith("person:")
    val inPoster = trailer && !fill && !wide && PosterTrailer.on && !item.key.startsWith("person:")
    // (Worked out apart, so a trailer moving to another poster rebuilds only the two posters concerned, not the row.)
    val mine by remember(item.key, inPoster) { androidx.compose.runtime.derivedStateOf { inPoster && PosterTrailer.item?.key == item.key } }
    val playing = mine && PosterTrailer.showing
    LaunchedEffect(inPoster, focused, PosterTrailer.active, item.key) {
        if (!inPoster) return@LaunchedEffect
        if (focused && PosterTrailer.active && PosterTrailer.dismissed != item.key) PosterTrailer.start(item, onClick)
        if (!focused) {
            PosterTrailer.stop(item.key)
            // (Moved off it: it may play again next time.)
            if (PosterTrailer.dismissed == item.key && !PosterTrailer.panel) PosterTrailer.dismissed = null
        }
    }
    // The card leaving (its row refreshed, the page closed) takes its trailer with it, else a box floated at its old place.
    DisposableEffect(item.key) { onDispose { if (PosterTrailer.item?.key == item.key) PosterTrailer.close(quiet = true) } }
    // (Read only while drawing the poster's wash: the card isn't rebuilt on every frame of the fade.)
    val dimAlpha = androidx.compose.animation.core.animateFloatAsState(if (focused || fill) 1f else AppColors.palette.posterDimAlpha, androidx.compose.animation.core.tween(160), label = "posterDim")
    val baseW = AppColors.palette.rowPosterWidth.dp
    val width by androidx.compose.animation.core.animateDpAsState(if (playing) baseW * 1.5f * 16f / 9f else baseW, androidx.compose.animation.core.tween(320), label = "posterTrailer")
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(playing) { if (playing) { kotlinx.coroutines.delay(340); runCatching { bring.bringIntoView() } } }
    // Back from its panel: the highlight back on this poster.
    val cardFocus = remember { FocusRequester() }
    var hadPanel by remember { mutableStateOf(false) }
    if (mine && PosterTrailer.panel) hadPanel = true
    // (Only the poster that had the panel watches for it closing.)
    // (Cleared last: clearing it ends this watch, which would have cut the focus move short.)
    if (hadPanel) LaunchedEffect(PosterTrailer.closedAt) { if (hadPanel && PosterTrailer.item == null) { if (!focused) { kotlinx.coroutines.delay(60); runCatching { cardFocus.requestFocus() } }; hadPanel = false } }
    // The shared card (FocusCard: 3 dp accent ring, no growing).
    FocusCard(
        // OK while its trailer plays in the card: the big panel with sound (2026-11-18); otherwise the title
        // page as always. (In the panel the poster keeps the highlight: its keys, via panelKey below, work the panel.)
        onClick = { if (playing) PosterTrailer.panel = true else onClick() },
        onLongClick = onLongClick,
        // In a grid ([fill]): as wide as its column, always 2:3 (a fixed size was squeezed to the column's width
        // but kept its height, so posters came out too wide).
        // (The panel's keys before anything the row added, e.g. Left opening the menu. UI review, 2026-11-18.)
        modifier = (if (mine && PosterTrailer.panel) Modifier.onPreviewKeyEvent { e -> PosterTrailer.panelKey(e) } else Modifier).then(modifier).free().then(
            if (fill) Modifier.fillMaxWidth()
            else if (inPoster) Modifier.width(width).height(baseW * 1.5f).bringIntoViewRequester(bring)
            else if (wide) Modifier.width(baseW * 16f / 9f * 1.05f)
            else Modifier.width(baseW),
        ).focusRequester(cardFocus).onFocusChanged { focused = it.isFocused }
            // One accessibility element per poster (its title), not its image, badges and texts: with any
            // accessibility service on (Projectivy's, for its remote buttons) Android re-describes every element on
            // screen after each move, and that cost ~15% of the main thread while sliding (2026-11-19).
            .clearAndSetSemantics { contentDescription = item.title }
            // Posters that aren't highlighted fade a little (Settings → Appearance → Posters → Other posters faded;
            // 2026-11-18), the highlighted one is solid.
            // (The fade is a colour wash in the picture's own drawing (PosterImage tint), not a see-through layer or
            // a shade drawn over the card: each of those made Android's renderer keep an off-screen buffer, a
            // full-screen one at start and one per poster as you moved; ~75 MB at 4K. 2026-11-19.)
            .then(if (mine) Modifier.onGloballyPositioned { PosterTrailer.cardRect = it.boundsInRoot() } else Modifier),
        aspect = if (inPoster) null else if (wide) CardAspect.Wide else CardAspect.Poster,
        ring = ring && !(mine && PosterTrailer.panel),
        // (Grows a little when highlighted, except in a grid and while its trailer plays in it.)
        focusedScale = if (fill || playing) 1f else AppColors.palette.posterZoom,
        smoothScale = !fill,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (playing) item.backdrop?.let { PosterImage(it, item.title, Modifier.fillMaxSize()) }
            val picture = if (playing) null else if (wide) (item.backdrop ?: cardPicture(item)) else cardPicture(item)
            // The name, under the picture until it loads (or always, with no picture).
            if (picture == null) CardName(item.title)
            else PosterImage(picture, item.title, Modifier.fillMaxSize(), tintOf = posterWash(dimAlpha),
                // (A wide card's picture loaded at its own size, not full size.)
                sizePx = if (fill) null else if (wide) rowPosterPx().let { r -> androidx.compose.ui.unit.IntSize((r.width * 1.87f).toInt(), (r.width * 1.87f * 9f / 16f).toInt()) } else rowPosterPx(),
                placeholder = { CardName(item.title) })
            // (No poster, only a wide picture: the name over its foot. UI review, 2026-11-18.)
            if (!playing && item.backdrop != null && (wide || item.poster == null)) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))).padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
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
                // Inside the artwork, clear of the edge (on the edge it ran into the highlight border); white while
                // highlighted, so it stands out against the highlight colour.
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 8.dp).fillMaxWidth().height(5.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(3.dp)),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.03f, 1f)).background(if (focused) Color.White else AppColors.Accent, RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

/** A poster card's name, in the middle of its space (no picture, or until the picture loads). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CardName(title: String) = Text(
    title, fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center,
    modifier = Modifier.align(Alignment.Center).padding(8.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
)

/** The "Other posters faded" wash for a poster at [dim] (1 = none), worked out while drawing. */
@Composable
private fun posterWash(dim: androidx.compose.runtime.State<Float>): () -> Color? = remember(dim) {
    { val a = dim.value; if (a < 0.999f) AppColors.palette.main.copy(alpha = 1f - a) else null }
}

@Composable
private fun DetailScreen(
    ui: DetailUi,
    vm: MediaViewModel,
    active: Boolean,
    /** Under a full-screen film: kept alive (no rebuild when it goes small again) but taking no keys or Back. */
    hidden: Boolean = false,
    onSearch: () -> Unit,
    onPickStream: (StreamOption) -> Unit,
    onCloseStreams: () -> Unit,
    onWatchlist: () -> Unit,
    onFollow: () -> Unit = {},
    onClose: () -> Unit,
    /** Left on the first button (Play): the menu, as Left at the edge of any page. */
    onLeftEdge: () -> Unit = {},
) {
    val d = ui.detail
    val item = d.item
    val playFocus = remember { FocusRequester() }
    // The Back icon's own (it shared playFocus with the Find a source button: two nodes on one requester).
    val backFocus = remember { FocusRequester() }
    var playFocused by remember { mutableStateOf(false) }
    val onPlex = ui.copies?.any { it.source == "Plex" && it.ref != null } == true
    val onProvider = ui.copies?.any { it.source == PROVIDER } == true
    val serverName = ui.copies?.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }?.source
    val onServer = serverName != null
    val show = item.type == MediaType.Show
    val parts = AppColors.palette
    val trailerId by produceState<String?>(null, item.key, parts.shows(TitleParts.TRAILER)) { value = if (parts.shows(TitleParts.TRAILER)) vm.trailers.youtubeId(item) else null }
    var trailerOpen by remember { mutableStateOf(false) }
    // Other sources: every copy (each Plex server, Jellyfin / Emby, IPTV) and the addon search, to pick one by hand.
    var sourcesOpen by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    // Closed: back to Play (once the buttons can take focus again, a frame later).
    FocusBackWhenClosed(tracksOpen, playFocus)
    // The "Play from" choice closed: the highlight back on Play (it was made unpickable while open and left nowhere).
    FocusBackWhenClosed(sourcesOpen, playFocus)
    if (sourcesOpen) SourcesDialog(ui, vm, onDone = { sourcesOpen = false })
    val trailerFocus = remember { FocusRequester() }
    // (The stream list, an episode's actions and the old episodes list are pop-ups over the title page.)
    BackLayer(!hidden && (ui.streams != null || ui.episode != null || ui.episodesOpen))
    BackHandler(enabled = !trailerOpen && !hidden) {
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
    // The channel in the preview place (top left): the details beside it follow what's highlighted below (an episode,
    // a title in More like this), and are the page's own again back up on the buttons.
    val besidePreview = LiveBehind.preview
    var hover by remember(item.key) { mutableStateOf<Pair<String, String?>?>(null) }
    LaunchedEffect(buttonsFocused) { if (buttonsFocused) hover = null }
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
    Box(Modifier.fillMaxSize().background(AppColors.Background).onFocusChanged { pageFocused = it.hasFocus }
        .then(if (active) Modifier.trapFocus() else Modifier.focusGroup())) {
        if (io.github.petebutty.viewarr.ui.launcher.LauncherLook.on) {
            // Viewarr launcher: the title's artwork in the top-right corner, faded into the page (its one look, as
            // every page there), not across the whole page behind the text (2026-11-25: "messy").
            LaunchedEffect(item.key) { item.backdrop?.let { io.github.petebutty.viewarr.ui.theme.Wallpaper.title = it } }
            Box(Modifier.fillMaxSize().appBackground(titleArt = true))
        } else {
            item.backdrop?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.75f))
            }
            val bg = AppColors.Background
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.25f to bg, 0.6f to bg.copy(alpha = 0.6f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to bg)))
        }
        // One screen: a compact header (poster, logo, details, ratings, description, buttons), then tabs with one
        // row under them (cast & crew, reviews, more like this, details), as on other TV media apps.
        var reading by remember { mutableStateOf<Review?>(null) }
        val tabFocus = remember { FocusRequester() }
        val sectionsFree = !ui.episodesOpen && ui.streams == null && !trailerOpen
        // Shows: the seasons and episodes come between the header and the tabs, so the page scrolls (focus leads it).
        val episodesEntry = remember { FocusRequester() }
        // An episode's actions closed (hold OK on an episode): the highlight back on the episodes (it was left nowhere).
        var episodeWasOpen by remember { mutableStateOf(false) }
        LaunchedEffect(ui.episode == null) {
            if (ui.episode != null) { episodeWasOpen = true; return@LaunchedEffect }
            if (!episodeWasOpen) return@LaunchedEffect
            episodeWasOpen = false
            awaitFrame(); awaitFrame()
            if (runCatching { episodesEntry.requestFocus() }.isFailure) runCatching { playFocus.requestFocus() }
        }
        val inlineEpisodes = show && ui.seasons.isNotEmpty() && parts.shows(TitleParts.EPISODES)
        // The header (logo, details, buttons) stays put; only what's under it scrolls (the logo was cut off the top as
        // the page scrolled down to the episodes).
        // (The one page margin, Tokens.Gutter, both sides: it was 56 on the left and 40 on the right.)
        // Everything on the page shows at once, faded in together, when its details, your copies and (a show) the
        // season's episodes are in: piece by piece, the logo, buttons, pills and rows each jumped in at different
        // times (2026-11-25). At most 1.5 s: a slow server's parts still come in after.
        // (Previewarr only: Viewarr shows the page straight away, piece by piece, as it always has.)
        var settled by remember(item.key) { mutableStateOf(!io.github.petebutty.viewarr.ui.launcher.LauncherLook.on) }
        // (A show with its episodes row switched off has no episodes to wait for.)
        val ready = !ui.loading && ui.copies != null && (!show || ui.seasons.isEmpty() || ui.episodes != null || !parts.shows(TitleParts.EPISODES))
        LaunchedEffect(item.key) { kotlinx.coroutines.delay(1_500); settled = true }
        LaunchedEffect(ready) { if (ready) { kotlinx.coroutines.delay(60); settled = true } }
        val reveal by androidx.compose.animation.core.animateFloatAsState(if (settled) 1f else 0f, androidx.compose.animation.core.tween(280), label = "reveal")
        Column(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = reveal }
            // (Until it shows, no button but Back: OK pressed Play on a page you couldn't see yet.)
            .onPreviewKeyEvent { e -> !settled && e.key != androidx.compose.ui.input.key.Key.Back }
            .padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 24.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top, modifier = if (besidePreview) Modifier.heightIn(min = 190.dp) else Modifier) {
            // The preview's place (12–332 dp from the left) instead of the poster: the text starts at 356, as on every page.
            if (besidePreview) Spacer(Modifier.width(Tokens.BesideWindowStart - Tokens.Gutter))
            else item.poster?.let {
                PosterImage(it, null, Modifier.size(width = 120.dp, height = 180.dp).clip(Tokens.CardShape))
                Spacer(Modifier.width(24.dp))
            }
            Column(Modifier.fillMaxWidth(0.68f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val hovered = hover.takeIf { besidePreview }
                if (hovered != null) Text(hovered.first, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else TitleLogo(item, vm::logoFor, height = 54.dp, maxWidth = 320.dp, fontSize = 28)
                val meta = detailMeta(item, d)
                if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TitleBadges(item, d, ui.media)
                RatingsLine(item.rating, ui.ratings)
                (ui.upTo()?.let { "S${it.first} · E${it.second}" } ?: item.subtitle)?.takeIf { parts.shows(TitleParts.UP_NEXT) }?.let { Text("Up next: $it", fontSize = 13.sp, color = AppColors.Accent, maxLines = 1) }
                (if (hovered != null) hovered.second else item.overview)?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary, maxLines = if (show) 2 else 3, overflow = TextOverflow.Ellipsis) }
                Text(
                    detailLibraryLine(ui, show),
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top, modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).onFocusChanged { buttonsFocused = it.hasFocus }.onPreviewKeyEvent { e ->
                    // Left on the first button: the menu (it did nothing, or went onto the picture in the corner).
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && playFocused && buttonsFree) { if (e.nativeKeyEvent.repeatCount == 0) onLeftEdge(); return@onPreviewKeyEvent true }
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && buttonsFree) runCatching { (if (inlineEpisodes && ui.episodes != null) episodesEntry else tabFocus).requestFocus() }.isSuccess else false
                }) {
                    if (show) {
                        // Play / Resume: continue watching's episode, else where Plex / Jellyfin / Trakt say you're up to,
                        // else S1 E1; from the best source (as a film's Play). Episodes for picking one.
                        val canPlay = onPlex || onServer || onProvider || ui.hasAddons
                        val next = ui.upTo()
                        // The episode you're up to not out yet (a new season part-way through): the button says when it
                        // is, rather than trying to play it and failing. Its air date is in the season's episodes,
                        // which the page loads for that season.
                        val upDate = notOutYet(ui, next)
                        if (canPlay && next != null && upDate != null) {
                            val whenOut = outWhen(upDate)
                            // OK: the episodes (that one shows "Not out yet"), not a play that can only fail.
                            Button(compact = true, onClick = vm::openEpisodes, modifier = (Modifier.focusRequester(playFocus).onFocusChanged { playFocused = it.isFocused }).free()) {
                                androidx.compose.material3.Icon(AppIcons.Clock, null, modifier = Modifier.padding(end = 6.dp).size(20.dp), tint = androidx.tv.material3.LocalContentColor.current)
                                Text("S${next.first} · E${next.second} out $whenOut", maxLines = 1, softWrap = false)
                            }
                        } else if (canPlay) {
                            val label = when {
                                next == null -> "Play S1 · E1"
                                next.third -> "Resume S${next.first} · E${next.second}"
                                else -> "Play S${next.first} · E${next.second}"
                            }
                            // Holding OK: the sources found for that episode, to pick from (as a film's Play). The seasons and
                            // episodes are on the page itself (below).
                            Button(compact = true, onClick = vm::playNextUp, onLongClick = vm::pickNextUp, modifier = (Modifier.focusRequester(playFocus).onFocusChanged { playFocused = it.isFocused }).free()) { androidx.compose.material3.Icon(AppIcons.Play, null, modifier = Modifier.padding(end = 6.dp).size(17.dp), tint = androidx.tv.material3.LocalContentColor.current); Text(if (ui.searching) "Looking…" else label, maxLines = 1, softWrap = false) }
                            // Part-way through that episode: or start it again.
                            if (next?.third == true) IconAction(AppIcons.Replay, "Start over", vm::playFromStart, Modifier.free())
                        } else {
                            Button(onClick = vm::openEpisodes, modifier = (Modifier.focusRequester(playFocus).onFocusChanged { playFocused = it.isFocused }).free()) { Text("Episodes", maxLines = 1, softWrap = false) }
                        }
                    } else if (onPlex || onServer || onProvider || ui.hasAddons) {
                        // One Play: your servers, IPTV and addons in the Play order setting (MediaViewModel.playBest).
                        // OK plays from the best source; holding OK lists every source to pick from.
                        Button(compact = true, onClick = vm::playBest, onLongClick = { sourcesOpen = true }, modifier = (Modifier.focusRequester(playFocus).onFocusChanged { playFocused = it.isFocused }).free()) {
                            androidx.compose.material3.Icon(AppIcons.Play, null, modifier = Modifier.padding(end = 6.dp).size(17.dp), tint = androidx.tv.material3.LocalContentColor.current)
                            Text(if (ui.searching) "Looking…" else if (item.progress != null) "Resume" else "Play", maxLines = 1, softWrap = false)
                        }
                        // Part-way through: or watch it again from the beginning.
                        if (item.progress != null) IconAction(AppIcons.Replay, "Start over", vm::playFromStart, Modifier.free())
                    } else {
                        // Still checking your servers (or nowhere to play it): a button to land on, so the page has a
                        // highlight and Left reaches the menu (there was none until the check finished).
                        Button(onClick = { if (ui.copies != null) onSearch() }, modifier = (Modifier.focusRequester(playFocus).onFocusChanged { playFocused = it.isFocused }).free()) {
                            Text(if (ui.copies == null || ui.searching) "Looking…" else "Find a source", maxLines = 1, softWrap = false)
                        }
                    }
                    // Films on your own server: pick the soundtrack and subtitles before Play (this play only).
                    if (!show && ui.tracks != null) {
                        val picked = ui.pickedAudio != null || ui.pickedSubtitle != null
                        IconAction(AppIcons.Subtitles, if (picked) "Audio & subtitles (chosen)" else "Audio & subtitles", { tracksOpen = true }, Modifier.free(), on = picked)
                    }
                    // Films: watched / not, everywhere it is (Trakt, your servers, Viewarr's own record).
                    if (!show && ui.watched != null) {
                        IconAction(if (ui.watched) AppIcons.CheckCircle else AppIcons.Check, if (ui.watched) "Watched (OK: undo)" else "Mark watched", vm::toggleWatched, Modifier.free(), on = ui.watched)
                    }
                    if (!show && !onPlex && !onProvider && !onServer && !ui.hasAddons && ui.copies != null) {
                        IconAction(AppIcons.Back, "Back", onClose, Modifier.focusRequester(backFocus).onFocusChanged { playFocused = it.isFocused }.free())
                    }
                    if (trailerId != null) {
                        IconAction(AppIcons.Movie, "Trailer", { trailerOpen = true }, Modifier.focusRequester(trailerFocus).free())
                    }
                    if (ui.canWatchlist) {
                        IconAction(if (ui.watchlisted) AppIcons.BookmarkFilled else AppIcons.Bookmark, if (ui.watchlisted) "Remove from watchlist" else "Add to watchlist", onWatchlist, Modifier.free(), on = ui.watchlisted)
                    }
                    // Shows: follow it, for a card when a new episode is in your library (FollowedShows).
                    if (show) {
                        val following = io.github.petebutty.viewarr.data.media.FollowedShows.isFollowed(ui.detail.item)
                        IconAction(if (following) AppIcons.Bell else AppIcons.BellOutline, if (following) "Following (OK: stop)" else "Follow: new episodes", onFollow, Modifier.free(), on = following)
                    }
                    SeerrAction(ui, vm)
                }
                }
                if (!ui.episodesOpen) ui.message?.let { Text(it, fontSize = 14.sp, color = AppColors.Accent, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (!onPlex && !onProvider && !onServer && !ui.hasAddons && ui.copies != null) {
                    Text("Connect Plex or an addon in Settings → Accounts & sources to play this.", fontSize = 13.sp, color = AppColors.TextSecondary)
                }
            }
        }
        // Cinema (films and shows without the episode rows here): the tabs and their row along the bottom of the
        // screen, the backdrop clear between the buttons and them (2026-11-18). Otherwise they follow the header.
        val cinemaPage = AppColors.palette.heroStyle == 1 && !inlineEpisodes
        Column(
            if (cinemaPage) Modifier.weight(1f).fillMaxWidth() else Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = if (cinemaPage) Arrangement.Bottom else Arrangement.Top,
        ) {
        if (inlineEpisodes) InlineEpisodes(ui, vm, free = sectionsFree && ui.episode == null, entry = episodesEntry, up = playFocus, down = tabFocus, onHover = { ep -> hover = "S${ep.season} E${ep.number} · ${ep.name}" to ep.overview }, onLeftEdge = onLeftEdge)
        // "Episodes" (hold OK on a show, the guide's Go to show…): onto this page's episode row, once the season's in.
        // (Once per request: changing season afterwards mustn't pull focus down to the episodes again.)
        var focusedFor by remember { mutableStateOf(0L) }
        LaunchedEffect(ui.focusEpisodes, ui.episodes != null) {
            if (ui.focusEpisodes == 0L || ui.focusEpisodes == focusedFor || ui.episodes == null || !inlineEpisodes) return@LaunchedEffect
            focusedFor = ui.focusEpisodes
            awaitFrame(); awaitFrame()
            runCatching { episodesEntry.requestFocus() }
        }
        Spacer(Modifier.height(16.dp))
        DetailTabs(d, show, sectionsFree && ui.episode == null, tabFocus, onPerson = { c -> vm.openCastMember(c) }, onReview = { reading = it }, onSimilar = { vm.open(it) }, onHover = { m -> hover = m?.let { it.title to it.overview } }, onLeftEdge = onLeftEdge,
            focusSimilar = vm.returnToSimilar, onFocusedSimilar = { vm.returnToSimilar = null })
                }
}
        reading?.let { r -> ReviewDialog(r) { reading = null } }
        if (ui.episodesOpen) EpisodesPanel(ui, vm)
        else if (ui.streams.isNullOrEmpty()) EpisodeActionsFor(ui, vm)
        ui.streams?.takeIf { it.isNotEmpty() }?.let { StreamPicker(it, onPickStream, Modifier, onClose = { if (ui.episode != null) vm.closeEpisodeStreams() else onCloseStreams() }) }
        ui.tracks?.takeIf { tracksOpen }?.let { t -> TracksDialog(t, ui.pickedAudio, ui.pickedSubtitle, onPick = { a, sub -> vm.pickTracks(a, sub) }, onDone = { tracksOpen = false }) }
        if (trailerOpen) {
            TrailerPlayer(item = item, trailers = vm.trailers, onClose = { trailerOpen = false })
            DisposableEffect(Unit) {
                onDispose { trailerFocus.let { f -> android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { f.requestFocus() } } } }
            }
        }
    }
}

/**
 * Episodes: the show's artwork fills the screen. On the left, the title and the highlighted episode's details;
 * on the right, seasons and a compact episode list. OK on an episode → Play on Plex / Search addons.
 */
@Composable
private fun EpisodesPanel(ui: DetailUi, vm: MediaViewModel) {
    val item = ui.detail.item
    // Watched anywhere: Trakt, your servers or Viewarr's own record (WatchState.kt).
    val localEntries by vm.localWatch.entries.collectAsState()
    val localEps = remember(localEntries, item.key) { vm.localWatch.episodesOf(item) }
    fun watched(s: Int, e: Int) = ui.episodeWatched(s to e, localEps)
    val episodeFocus = remember { FocusRequester() }
    val target = ui.episodes?.let { eps ->
        val next = if (ui.upTo()?.first == ui.season) eps.indexOfFirst { it.number == ui.upTo()?.second } else -1
        if (next >= 0) next else eps.indexOfFirst { e -> !watched(e.season, e.number) }.coerceAtLeast(0)
    } ?: -1
    var highlighted by remember(ui.season) { mutableStateOf<EpisodeInfo?>(null) }
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
    var lastEpisodes by remember { mutableStateOf<List<EpisodeInfo>?>(null) }
    LaunchedEffect(ui.episodes) { ui.episodes?.let { lastEpisodes = it } }
    // The episode last highlighted this season: an episode's actions closing (hold OK, then Cancel / Back) put the
    // highlight back on it, not on the one you're up to.
    var lastEp by remember(ui.season) { androidx.compose.runtime.mutableIntStateOf(-1) }
    var episodeWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(ui.season, ui.episodes != null, ui.episode == null, ui.streams == null) {
        val justClosed = episodeWasOpen && ui.episode == null
        episodeWasOpen = ui.episode != null
        if (ui.episodes.isNullOrEmpty() || ui.episode != null || ui.streams != null || target < 0) return@LaunchedEffect
        // (Not after an episode's actions: the list stays where it was.)
        if (!justClosed) listState.scrollToItem(maxOf(0, target - 1))
        if (onSeasons) return@LaunchedEffect
        awaitFrame()
        runCatching { episodeFocus.requestFocus() }
    }
    val entryEp = lastEp.takeIf { it >= 0 } ?: target
    val bg = AppColors.Background
    Box(Modifier.fillMaxSize().background(bg).trapFocus()) {
        (shown?.still?.takeIf { false } ?: item.backdrop)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Artwork stays clear on the left; the list side darkens for readability.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = 0.55f), 0.38f to bg.copy(alpha = 0.35f), 0.55f to bg.copy(alpha = 0.85f), 1f to bg.copy(alpha = 0.95f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to bg.copy(alpha = 0.8f))))
        Row(Modifier.fillMaxSize().padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 40.dp, bottom = 24.dp)) {
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
                    if (tags.isNotEmpty()) Text(tags, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ep.overview?.let { Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = AppColors.TextPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
                }
                Spacer(Modifier.height(24.dp))
            }
            // Right: seasons + compact episodes.
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                SeasonPills(
                    ui, seasonRow, free = panelFree, selectedFocus = seasonFocus,
                    onSeasons = { onSeasons = it }, onPending = { pendingSeason = it },
                    onPick = { pendingSeason = null; vm.selectSeason(it) },
                )
                Spacer(Modifier.height(12.dp))
                val loading = ui.episodes == null
                val eps = ui.episodes ?: lastEpisodes
                when {
                    eps == null -> Text("Loading episodes…", color = AppColors.TextSecondary)
                    eps.isEmpty() -> Text("No episodes listed for this season.", color = AppColors.TextSecondary)
                    else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = if (loading) 0.4f else 1f }) {
                        itemsIndexed(eps, key = { _, e -> "${e.season}-${e.number}" }) { i, ep ->
                            val plex = ui.plexEpisodes[ep.season to ep.number]
                            Surface(
                                // OK plays it from the best source; holding OK lists the sources.
                                onClick = { vm.playBestEpisode(ep.season, ep.number) },
                                onLongClick = { vm.pickEpisode(ep) },
                                modifier = (if (i == entryEp) Modifier.focusRequester(episodeFocus) else Modifier)
                                    // Not while the next season loads (that's the last season's list); Up from the top
                                    // episode goes to the season being viewed.
                                    .focusProperties { canFocus = panelFree && !loading }
                                    .then(if (i == 0) Modifier.safeMove(Key.DirectionUp) { seasonFocus } else Modifier)
                                    .onFocusChanged { if (it.isFocused) { highlighted = ep; lastEp = i } }
                                    .fillMaxWidth(),
                                scale = NoScale,
                                shape = ClickableSurfaceDefaults.shape(Tokens.RowShape),
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
                                        ui.episodeBar(ep.season to ep.number, ep.runtimeMin, localEps).takeIf { it > 0f }?.let {
                                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(it).height(3.dp).background(AppColors.Accent))
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
                                            Text(it, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = 0.85f })
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

/** A film's soundtrack and subtitles, picked before Play. On the shared pop-up (PopupPanel). */
@Composable
private fun TracksDialog(
    t: TitleTracks,
    audio: Int?, subtitle: Int?,
    onPick: (audio: Int?, subtitle: Int?) -> Unit,
    onDone: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    // What the server would pick by itself, when you haven't picked.
    val a = audio ?: t.audio.firstOrNull { it.default }?.ordinal ?: 0
    val sub = subtitle ?: t.subtitles.firstOrNull { it.default }?.ordinal
    // (Was a solid panel of its own; now the one pop-up style, which scrolls with the highlight. 2026-11-17.)
    PopupPanel(onClose = onDone, width = Tokens.PopupWide) {
        PopupTitle("Audio & subtitles", "For this play, from ${t.source}.")
        if (t.audio.isNotEmpty()) PopupSection("Audio")
        t.audio.forEachIndexed { i, c ->
            MenuItem(
                text = (if (c.ordinal == a) "✓  " else "     ") + c.label, selected = false,
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                onClick = { onPick(c.ordinal, subtitle) },
            )
        }
        PopupSection("Subtitles")
        MenuItem(
            text = (if (sub == null || sub < 0) "✓  " else "     ") + "Off", selected = false,
            modifier = if (t.audio.isEmpty()) Modifier.focusRequester(first) else Modifier,
            onClick = { onPick(audio, -1) },
        )
        t.subtitles.forEach { c ->
            MenuItem(text = (if (c.ordinal == sub) "✓  " else "     ") + c.label, selected = false, onClick = { onPick(audio, c.ordinal) })
        }
        MenuItem(text = "Done", selected = false, onClick = onDone, modifier = Modifier.padding(top = 10.dp))
    }
}

/** A heading inside a pop-up's list ("Audio", "Subtitles"). */
@Composable
private fun PopupSection(text: String) {
    Text(text, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp, start = 12.dp, bottom = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Holding OK on an episode: where to play it from. */
@Composable
private fun EpisodeActionsFor(ui: DetailUi, vm: MediaViewModel) {
    val ep = ui.episode ?: return
    val plex = ui.plexEpisodes[ep.season to ep.number]
    // IPTV: watched / not watched kept on the box (resume too: "Resume from IPTV").
    val onIptv = (ep.season to ep.number) in ui.providerEpisodes
    val localEntries by vm.localWatch.entries.collectAsState()
    val local = remember(localEntries, ui.detail.item.key) { vm.localWatch.episodesOf(ui.detail.item) }[ep.season to ep.number]
    // Watched anywhere: Trakt, your servers or Viewarr's own record (WatchState.kt).
    val k = ep.season to ep.number
    val localEps = remember(localEntries, ui.detail.item.key) { vm.localWatch.episodesOf(ui.detail.item) }
    val seen = ui.episodeWatched(k, localEps)
    // The addons searched as soon as it opens (null: still searching).
    val addonStreams by produceState<List<StreamOption>?>(if (ui.hasAddons) null else emptyList(), ui.detail.item.key, ep.season, ep.number) { value = vm.addonOptions(ep.season, ep.number) }
    EpisodeActions(
        addonStreams = addonStreams,
        onAddon = { opt -> vm.playStream(opt); vm.pickEpisode(null) },
        providerLabel = if (local != null && local.viewOffsetMs > 0 && !local.watched) "Resume from IPTV" else "Play from IPTV",
        markLabel = if (seen) "Mark as not watched" else "Mark as watched",
        onMark = { vm.markEpisode(ep.season, ep.number, !seen); vm.pickEpisode(null) },
        title = "S${ep.season} · E${ep.number}  ${ep.name}",
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InlineEpisodes(ui: DetailUi, vm: MediaViewModel, free: Boolean, entry: FocusRequester, up: FocusRequester, down: FocusRequester, onHover: (EpisodeInfo) -> Unit = {}, onLeftEdge: () -> Unit = {}) {
    val item = ui.detail.item
    // Watched anywhere: Trakt, your servers or Viewarr's own record (WatchState.kt).
    val localEntries by vm.localWatch.entries.collectAsState()
    val localEps = remember(localEntries, item.key) { vm.localWatch.episodesOf(item) }
    fun watched(s: Int, e: Int) = ui.episodeWatched(s to e, localEps)
    // Opens on the season you're up to (once that's known; a few seconds at most), else the first.
    val latest by androidx.compose.runtime.rememberUpdatedState(ui)
    LaunchedEffect(ui.seasons.size, ui.nextUpChecked) {
        if (ui.seasons.isEmpty() || ui.season != null) return@LaunchedEffect
        // After the wait, the current state (not the one this started with): a season chosen meanwhile stays.
        if (!ui.nextUpChecked && item.nextSeason == null) { kotlinx.coroutines.delay(4_000); if (latest.season != null) return@LaunchedEffect }
        val want = ui.upTo()?.first
        vm.selectSeason(want?.takeIf { n -> ui.seasons.any { it.number == n } } ?: ui.seasons.minOf { it.number })
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
    var lastEpisodes by remember { mutableStateOf<List<EpisodeInfo>?>(null) }
    LaunchedEffect(ui.episodes) { ui.episodes?.let { lastEpisodes = it } }
    val target = ui.episodes?.let { eps ->
        val next = if (ui.upTo()?.first == ui.season) eps.indexOfFirst { it.number == ui.upTo()?.second }
            else ui.upTo()?.let { up -> if (up.first == ui.season) eps.indexOfFirst { it.number == up.second } else -1 } ?: -1
        if (next >= 0) next else eps.indexOfFirst { e -> !watched(e.season, e.number) }.coerceAtLeast(0)
    } ?: -1
    // Right past a season's last episode: the next season, on its first episode (2026-11-24). The highlight waits on
    // that season's pill while its episodes load, then goes down to episode 1.
    var startAtFirst by remember { mutableStateOf(false) }
    val nextScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(ui.season, ui.episodes) {
        val eps = ui.episodes
        if (!startAtFirst || eps.isNullOrEmpty() || eps.first().season != ui.season) return@LaunchedEffect
        awaitFrame()
        runCatching { episodeRow.scrollToItem(0) }
        awaitFrame()
        runCatching { entry.requestFocus() }
        startAtFirst = false
    }
    // The row starts at the episode you're up to.
    LaunchedEffect(ui.season, ui.episodes != null) {
        // (In the first place, where the highlight stays: the row moves as the poster rows do.)
        if (target > 0 && !startAtFirst) runCatching { episodeRow.scrollToItem(target) }
    }
    // The episode last highlighted this season: coming back to the row (an episode's actions closed with Cancel /
    // Back, Down from the buttons) lands on it, not on the one you're up to.
    var lastEp by remember(ui.season) { androidx.compose.runtime.mutableIntStateOf(-1) }
    val entryEp = if (startAtFirst) 0 else lastEp.takeIf { it >= 0 } ?: target.coerceAtLeast(0)
    LaunchedEffect(ui.season, ui.seasons.size) {
        if (onSeasons) return@LaunchedEffect
        val idx = ui.seasons.indexOfFirst { it.number == ui.season }
        if (idx >= 0) runCatching { seasonRow.scrollToItem(maxOf(0, idx - 1)) }
    }
    if (ui.seasons.isEmpty()) return
    // The episode cards move as the poster rows do: the highlight stays in the first place, the cards slide under it.
    val episodeCount = (ui.episodes ?: lastEpisodes)?.size ?: 0
    val episodeSlide = rememberRowSlide(episodeRow) { episodeCount }
    // Left on the first season or episode: the menu, as Left at the edge of the tabs and buttons (it did nothing,
    // 2026-11-24, most noticed with a film in the mini player). After the row's own handling (onKeyEvent, not preview:
    // as preview it took Left before the row could slide back to an episode scrolled off the left, so episode 1 couldn't
    // be reached).
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Column(Modifier.padding(top = 10.dp).onKeyEvent { e ->
        if (e.key != Key.DirectionLeft || e.type != KeyEventType.KeyDown) return@onKeyEvent false
        if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left) && e.nativeKeyEvent.repeatCount == 0) onLeftEdge()
        true
    }) {
        SeasonPills(
            ui, seasonRow, free = free, selectedFocus = seasonFocus,
            onSeasons = { onSeasons = it }, onPending = { pendingSeason = it },
            onPick = { pendingSeason = null; vm.selectSeason(it) },
            pillModifier = Modifier.safeMove(Key.DirectionUp) { up },
        )
        Spacer(Modifier.height(10.dp))
        val loading = ui.episodes == null
        val eps = ui.episodes ?: lastEpisodes
        when {
            eps == null -> Text("Loading episodes…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.height(150.dp))
            eps.isEmpty() -> Text("No episodes listed for this season.", fontSize = 13.sp, color = AppColors.TextSecondary)
            // Room round the cards for the focused one's frame (it was clipped at the row's edge); pulled back by the
            // same amount so the row still lines up with the page.
            else -> CompositionLocalProvider(LocalBringIntoViewSpec provides NoRowScroll) { LazyRow(
                state = episodeRow, horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.offset(x = (-8).dp).onFocusChanged { if (!it.hasFocus) episodeSlide.focused = -1 }.then(episodeSlide.modifier).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = if (loading) 0.4f else 1f },
            ) {
                itemsIndexed(eps, key = { _, e -> "${e.season}-${e.number}" }) { i, ep ->
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.width(EpisodeCardW)) {
                        // The shared card (3 dp accent ring, no growing).
                        FocusCard(
                            onClick = { vm.playBestEpisode(ep.season, ep.number) },
                            onLongClick = { vm.pickEpisode(ep) },
                            modifier = (if (i == entryEp) Modifier.focusRequester(entry) else Modifier)
                                .focusProperties { canFocus = free && !loading }
                                .safeMove(Key.DirectionUp) { seasonFocus }.safeMove(Key.DirectionDown) { down }
                                // The last episode: Right goes on to the next season (once per press; nothing after the last).
                                .onPreviewKeyEvent { e ->
                                    if (e.key != Key.DirectionRight || i != eps.lastIndex || loading) return@onPreviewKeyEvent false
                                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                                        val next = ui.seasons.map { it.number }.sorted().firstOrNull { it > (ui.season ?: ep.season) }
                                        if (next != null) {
                                            startAtFirst = true
                                            vm.selectSeason(next)
                                            nextScope.launch { awaitFrame(); awaitFrame(); runCatching { seasonFocus.requestFocus() } }
                                        }
                                    }
                                    true
                                }
                                .onFocusChanged { focused = it.isFocused; if (it.isFocused) { lastEp = i; episodeSlide.focused = i; onHover(ep) } }
                                .width(EpisodeCardW),
                            aspect = CardAspect.Wide,
                            containerColor = AppColors.PanelAlt,
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                (ep.still ?: item.backdrop)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                if (watched(ep.season, ep.number)) Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(AppColors.Accent, androidx.compose.foundation.shape.CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { androidx.compose.material3.Icon(AppIcons.Check, contentDescription = "Watched", tint = AppColors.OnAccent, modifier = Modifier.size(16.dp)) }
                                ui.episodeBar(ep.season to ep.number, ep.runtimeMin, localEps).takeIf { it > 0f && AppColors.palette.progressBadges }?.let {
                                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(it).height(4.dp).background(AppColors.Accent))
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
            } }
        }
    }
}

/**
 * A show's seasons as pills: the one season selector, shared by the title page's episodes and the Episodes panel
 * (they were two copies). The season being viewed stays tinted even when you're down in its episodes. Moving onto a
 * pill asks for that season ([onPending], loaded once you pause there); OK picks it now ([onPick]). Only a real move
 * changes it ([free] false: behind a pop-up, the pills can't take focus). (2026-11-17.)
 */
@Composable
private fun SeasonPills(
    ui: DetailUi,
    state: LazyListState,
    free: Boolean,
    selectedFocus: FocusRequester,
    onSeasons: (Boolean) -> Unit,
    onPending: (Int) -> Unit,
    onPick: (Int) -> Unit,
    pillModifier: Modifier = Modifier,
) {
    LazyRow(Modifier.onFocusChanged { onSeasons(it.hasFocus) }, state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ui.seasons, key = { it.number }) { season ->
            val selected = season.number == ui.season
            AppPill(
                text = season.name, selected = selected, onClick = { onPick(season.number) },
                modifier = (if (selected) Modifier.focusRequester(selectedFocus) else Modifier)
                    .focusProperties { canFocus = free }
                    .then(pillModifier)
                    .onFocusChanged { if (it.isFocused && free) onPending(season.number) },
            )
        }
    }
}

@Composable
private fun EpisodeActions(
    title: String,
    plexLabel: String?,
    onProvider: (() -> Unit)? = null,
    providerLabel: String = "Play from IPTV",
    markLabel: String? = null,
    onMark: () -> Unit = {},
    serverLabel: String? = null,
    onServer: () -> Unit = {},
    hasAddons: Boolean,
    searching: Boolean,
    message: String?,
    onPlex: () -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    addonStreams: List<StreamOption>? = null,
    onAddon: (StreamOption) -> Unit = {},
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    // Which option has the highlight, and whether you've moved it yourself since the pop-up opened.
    var focusedAt by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    var moved by remember { mutableStateOf(false) }
    // Nothing of your own above them: once the addons answer, the first stream is highlighted (not Mark as watched)
    // unless you've already moved the highlight somewhere (it was pulled off what you'd chosen).
    val noneAbove = plexLabel == null && serverLabel == null && onProvider == null
    LaunchedEffect(addonStreams?.isNotEmpty() == true) {
        if (addonStreams?.isNotEmpty() == true && noneAbove && !moved) { awaitFrame(); runCatching { first.requestFocus() } }
    }
    // The shared pop-up (its own window; the held OK that opened it is ignored, so letting go doesn't pick the
    // first source). Cancel last, as every options pop-up. (2026-11-17: was a solid panel with "Close".)
    PopupPanel(onClose = onClose, width = Tokens.PopupWide) {
        PopupTitle(title)
        var n = 0
        fun m(): Modifier {
            val i = n++
            return (if (i == 0) Modifier.focusRequester(first) else Modifier)
                .onFocusChanged { if (it.isFocused) { if (focusedAt >= 0 && focusedAt != i) moved = true; focusedAt = i } }
        }
        plexLabel?.let { MenuItem(text = it, selected = false, onClick = onPlex, modifier = m()) }
        serverLabel?.let { MenuItem(text = it, selected = false, onClick = onServer, modifier = m()) }
        onProvider?.let { MenuItem(text = providerLabel, selected = false, onClick = it, modifier = m()) }
        AddonStreamItems(hasAddons, addonStreams, ::m, onAddon)
        if (plexLabel == null && serverLabel == null && onProvider == null && !hasAddons) Text("This episode isn't in Plex or on IPTV, and no addon is connected.", fontSize = 13.sp, color = AppColors.TextSecondary)
        markLabel?.let { MenuItem(text = it, selected = false, onClick = onMark, modifier = m()) }
        MenuItem(text = "Cancel", selected = false, onClick = onClose, modifier = m())
        message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/**
 * The streams the addons found (Find a source / an episode's search): the shared pop-up, each stream one row as in
 * Play from (a short summary, where it's from beside it). (2026-11-17: was a solid side panel with two-line
 * rows of its own.)
 */
@Composable
private fun StreamPicker(streams: List<StreamOption>, onPick: (StreamOption) -> Unit, modifier: Modifier, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { first.requestFocus() }
    }
    PopupPanel(onClose = onClose, width = Tokens.PopupWide, modifier = modifier) {
        PopupTitle("${streams.size} stream${if (streams.size == 1) "" else "s"} found", "Back to close")
        streams.forEachIndexed { i, s ->
            MenuItem(
                text = StreamLabel.summary(s), selected = false, trailing = StreamLabel.from(s),
                onClick = { onPick(s) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

internal val LocalButtonsFree = androidx.compose.runtime.compositionLocalOf { true }

/** Detail page buttons that step aside (unfocusable) while a pop-up is open. */
@Composable
internal fun Modifier.free(): Modifier {
    val free = LocalButtonsFree.current
    return this.focusProperties { canFocus = free }
}



private enum class DetailTab(val label: String) { Cast("Cast & crew"), Reviews("Reviews"), Collection("Collection"), Similar("More like this"), Details("Details") }

/**
 * Below the header: a row of tabs; moving onto one shows its row underneath (cast & crew, reviews, more like this,
 * details). Down goes from a tab into its row.
 */
@Composable
private fun DetailTabs(
    d: MediaDetail,
    show: Boolean,
    free: Boolean,
    /** On the selected tab: where Down from the buttons above goes. */
    tabFocus: FocusRequester,
    onPerson: (CastMember) -> Unit,
    onReview: (Review) -> Unit,
    onSimilar: (MediaItem) -> Unit,
    /** A title highlighted in More like this / the collection (null: off them, onto the tabs). */
    onHover: (MediaItem?) -> Unit = {},
    /** Left at the start of the tabs or a row: the menu (it did nothing). */
    onLeftEdge: () -> Unit = {},
    /** Back from a title opened in More like this: the poster to put the highlight on (then [onFocusedSimilar]). */
    focusSimilar: String? = null,
    onFocusedSimilar: () -> Unit = {},
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Left with nothing further left in the tabs or a row: the menu, as Left at the edge of any page.
    val leftOpensMenu = Modifier.onPreviewKeyEvent { e ->
        if (e.key != Key.DirectionLeft || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        // (At the edge, once per press: holding Left to run along a row stops there instead of opening the menu.)
        if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left) && e.nativeKeyEvent.repeatCount == 0) onLeftEdge()
        true
    }
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
        if (d.cast.isNotEmpty() && p.shows(TitleParts.CAST)) add(DetailTab.Cast)
        if (d.reviews.isNotEmpty() && p.shows(TitleParts.REVIEWS)) add(DetailTab.Reviews)
        if (d.collection.isNotEmpty() && p.shows(TitleParts.COLLECTION)) add(DetailTab.Collection)
        if (d.similar.isNotEmpty() && p.shows(TitleParts.SIMILAR)) add(DetailTab.Similar)
        if (facts.isNotEmpty() && p.shows(TitleParts.DETAILS)) add(DetailTab.Details)
    }
    if (tabs.isEmpty()) return
    var tab by remember(d.item.key) { mutableStateOf(tabs.first()) }
    if (tab !in tabs) tab = tabs.first()
    // Back from a More like this / Collection title: that row as the tab, scrolled to the poster it was opened from,
    // the highlight on it (a moment after the page's own Play focus, which runs first).
    val similarReturn = remember { FocusRequester() }
    val similarState = rememberLazyListState()
    val collectionState = rememberLazyListState()
    // The film you're on stays out of its own collection row.
    val collectionItems = remember(d.item.key, d.collection) { d.collection.filter { it.tmdbId != d.item.tmdbId } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(focusSimilar, d.similar.size, collectionItems.size) {
        val key = focusSimilar ?: return@LaunchedEffect
        val inSimilar = d.similar.indexOfFirst { it.key == key }
        val inCollection = collectionItems.indexOfFirst { it.key == key }
        val (t, along, index) = when {
            inSimilar >= 0 && DetailTab.Similar in tabs -> Triple(DetailTab.Similar, similarState, inSimilar)
            inCollection >= 0 && DetailTab.Collection in tabs -> Triple(DetailTab.Collection, collectionState, inCollection)
            else -> { onFocusedSimilar(); return@LaunchedEffect }
        }
        tab = t
        delay(150); awaitFrame()
        // (Scrolled to the poster first: off screen it isn't drawn, and there's nothing to highlight.)
        runCatching { along.scrollToItem(index) }
        awaitFrame()
        runCatching { similarReturn.requestFocus() }
        onFocusedSimilar()
    }
    // Right past the last tab stays put (it used to jump up to the Play button).
    Row(Modifier.stopAtRight().then(leftOpensMenu), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEach { t ->
            // The shared pill (selected: the tint; highlighted: the accent fill).
            AppPill(
                text = (if (t == DetailTab.Collection) d.collectionName ?: t.label else t.label) + if (t == DetailTab.Reviews) " (${d.reviews.size})" else "",
                selected = t == tab,
                // OK: into the tab's row (it only re-picked the tab already showing), once that row is drawn (a frame
                // later: moving at once went into the row of the tab that was showing before).
                onClick = { tab = t; scope.launch { awaitFrame(); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) } },
                modifier = (if (t == tab) Modifier.focusRequester(tabFocus) else Modifier).onFocusChanged { if (it.isFocused) { tab = t; onHover(null) } }.focusProperties { canFocus = free }
                    .then(if (t == DetailTab.Collection) Modifier.widthIn(max = 220.dp) else Modifier), // (a long collection name pushed the other tabs off)
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    val rowPadding = PaddingValues(end = Tokens.Gutter)
    // One tab's row (the chosen tab's, and the second row under it).
    @Composable
    fun TabRow(t: DetailTab) {
        when (t) {
            // (Right past a row's last card stays put, as on the tabs: it jumped up to the buttons.)
            DetailTab.Cast -> androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.stopAtRight().then(leftOpensMenu), contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(d.cast, key = { "${it.id}-${it.name}" }) { c -> CastCard(c, free) { onPerson(c) } }
            }
            DetailTab.Reviews -> androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.stopAtRight().then(leftOpensMenu), contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(d.reviews.size) { i -> ReviewCard(d.reviews[i], free) { onReview(d.reviews[i]) } }
            }
            DetailTab.Similar, DetailTab.Collection -> androidx.compose.foundation.lazy.LazyRow(
                state = if (t == DetailTab.Collection) collectionState else similarState,
                modifier = Modifier.stopAtRight().then(leftOpensMenu), contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(if (t == DetailTab.Collection) collectionItems else d.similar, key = { it.key }) { m ->
                    Column(Modifier.width(AppColors.palette.rowPosterWidth.dp)) {
                        PosterCard(m, Modifier.then(if (m.key == focusSimilar) Modifier.focusRequester(similarReturn) else Modifier).focusProperties { canFocus = free }.onFocusChanged { if (it.isFocused) onHover(m) }, onClick = { onSimilar(m) }, trailer = false)
                        Text(m.title, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            DetailTab.Details -> Surface(
                onClick = {}, modifier = Modifier.focusProperties { canFocus = free },
                scale = NoScale,
                shape = ClickableSurfaceDefaults.shape(Tokens.CardShape),
                colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Panel.copy(alpha = 0.6f), focusedContainerColor = AppColors.Panel),
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    facts.forEach { (k, v) ->
                        Column {
                            Text(k, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(v, fontSize = 14.sp, color = AppColors.TextPrimary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    TabRow(tab)
    // A second row under it, so the page isn't half empty below one short row (2026-11-17): More like this, or
    // the cast when More like this is the tab chosen. Down from the first row goes into it.
    // (Cinema: just the chosen tab's row along the bottom, the backdrop above it. 2026-11-18.)
    val second = if (AppColors.palette.heroStyle == 1) null else when {
        tab != DetailTab.Similar && DetailTab.Similar in tabs -> DetailTab.Similar
        tab == DetailTab.Similar && DetailTab.Cast in tabs -> DetailTab.Cast
        else -> null
    }
    if (second != null) {
        Spacer(Modifier.height(16.dp))
        RowTitle(second.label, Modifier.padding(start = 4.dp))
        TabRow(second)
    }
}


/**
 * [key] moves the highlight to [target] when it's on screen, else wherever that key normally goes. (A fixed Up / Down
 * target that wasn't drawn, e.g. a season scrolled out of view or tabs switched off, crashed the app.)
 */
private fun Modifier.safeMove(key: Key, target: () -> FocusRequester?): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown || e.key != key) return@onPreviewKeyEvent false
    val t = target() ?: return@onPreviewKeyEvent false
    // (requestFocus says nothing back on this Compose version: a throw is the only sign it didn't take.)
    runCatching { t.requestFocus() }.isSuccess
}

/**
 * Where a poster row starts: nearer the screen's edge than the page's text (2026-11-19), so once the row has
 * moved on, the poster before the highlighted one is off screen rather than peeking in.
 */
private val RowStart = 20.dp

/** How long the highlight rests before the top panel and the background follow it. */
private const val HERO_SETTLE_MS = 900L

/** A title page's episode card width: five across the screen (192 already did that on the AM9). */
private val EpisodeCardW = 192.dp

/** Cinema's row list: focus and bring-into-view never scroll it; the page turn is animated by index (above). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val CinemaNoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Nothing inside can take the highlight (the title page set aside under a full-screen film). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.noFocusInside(): Modifier = focusProperties { canFocus = false; enter = { FocusRequester.Cancel } }.focusGroup()
