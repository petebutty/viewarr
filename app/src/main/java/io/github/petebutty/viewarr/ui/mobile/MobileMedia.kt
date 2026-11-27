package io.github.petebutty.viewarr.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.home.Page
import io.github.petebutty.viewarr.ui.launcher.Launcher
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * Films or Shows on a phone or tablet: the same rows as the TV pages (Continue watching, Recently added, your
 * watchlist…), each a row of big posters you swipe along: about half the screen tall, the full width. No artwork
 * behind and no trailers. Tap a poster for its page.
 */
@Composable
internal fun MobileMediaPage(type: MediaType, modifier: Modifier) {
    val vm: MediaViewModel = hiltViewModel(key = if (type == MediaType.Movie) "media-Movie" else "media-Show")
    LaunchedEffect(type) { vm.load(type) }
    // A collection (or a library) open: its titles as a wall of posters (2026-11-27: tapping one did nothing on a phone).
    val browse by vm.browse.collectAsStateWithLifecycle()
    browse?.let { b -> MobileCollectionPage(vm, b, type, modifier); return }
    val rows by vm.rows.collectAsStateWithLifecycle()
    // The search box at the top: while it has words in it, the page shows what matches (this page's kind only).
    var query by androidx.compose.runtime.saveable.rememberSaveable(type) { androidx.compose.runtime.mutableStateOf("") }
    val searchVm: MediaViewModel = hiltViewModel(key = "media-search-phone")
    LaunchedEffect(query, type) {
        searchVm.setSearchFilter(if (type == MediaType.Movie) io.github.petebutty.viewarr.ui.media.SearchFilter.Films else io.github.petebutty.viewarr.ui.media.SearchFilter.Shows)
        searchVm.search(query)
    }
    val results by searchVm.rows.collectAsStateWithLifecycle()
    val searching = query.trim().length >= 2
    androidx.activity.compose.BackHandler(enabled = query.isNotEmpty()) { query = "" }
    Column(modifier) {
        BrandBar(if (type == MediaType.Movie) "Films" else "Shows") { TopBarIcon(AppIcons.Person, "Settings") { MobileSettingsNav.open = true } }
        SearchBox(query, { query = it }, if (type == MediaType.Movie) "Search films" else "Search shows")
        val list = (if (searching) results.rows.filter { it.id != "search-people" } else rows.rows).filter { it.items.isNotEmpty() }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(when { searching && results.done -> "Nothing matches \"${query.trim()}\"."; searching -> "Searching…"; rows.done -> "Nothing here yet."; else -> "Loading…" },
                    color = AppColors.TextSecondary, fontSize = 16.sp)
            }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // About three posters across a phone (see posterWidth).
            val tileWidth: Dp = posterWidth(maxWidth, maxHeight)
            LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
                items(list, key = { it.id }) { row ->
                    Column(Modifier.padding(top = 14.dp)) {
                        Text(row.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(row.items, key = { it.key }) { item -> PosterTile(item, tileWidth) { openTitle(vm, item, if (searching) item.type else type) } }
                        }
                    }
                }
            }
        }
    }
}

/** A title's page: the phone's own (MobileTitlePage). */
internal fun openTitle(vm: MediaViewModel, item: MediaItem, type: MediaType) {
    // A collection's card: its titles open on the Films or Shows page (from the Streaming tab too).
    if (item.key.startsWith("col:")) {
        vm.open(item)
        Mobile.tab = if (type == MediaType.Show) "shows" else "films"
        return
    }
    MobileTitle.open(vm, item, type)
}

/** A search box: rounded, with a clear button once something's typed. */
@Composable
internal fun SearchBox(query: String, onChange: (String) -> Unit, hint: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(MobileColors.Card).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(AppIcons.Search, null, tint = AppColors.TextSecondary, modifier = Modifier.padding(end = 10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(hint, color = AppColors.TextSecondary, fontSize = 16.sp)
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) TopBarIcon(AppIcons.Close, "Clear") { onChange("") }
    }
}

/** A poster (2:3) with its title under it, and how far you got where it's started. */
/**
 * A row's poster width: about a third of a phone's width, so three fit across with the next peeking in (2026-11-27:
 * half the width showed two and barely more than one row); never so tall that a row takes over half the screen
 * turned sideways; on a tablet capped, so there are simply more across.
 */
internal fun posterWidth(maxWidth: Dp, maxHeight: Dp): Dp =
    minOf(maxWidth * 0.3f, (maxHeight * 0.36f - 48.dp) / 1.5f, 150.dp).coerceAtLeast(96.dp)

@Composable
internal fun PosterTile(item: MediaItem, width: Dp, onTap: () -> Unit) {
    Column(Modifier.width(width).clickable(onClick = onTap)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(MobileColors.Card)) {
            val picture = item.poster ?: item.backdrop
            if (picture != null) AsyncImage(picture, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(item.title, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center).padding(12.dp))
            item.progress?.takeIf { it > 0f }?.let { f ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().background(AppColors.Accent))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        (item.subtitle ?: item.year?.toString())?.let { Text(it, color = AppColors.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** A collection's (or a library's) titles: big posters in a grid, more loaded as you scroll; a tap opens one. */
@Composable
private fun MobileCollectionPage(vm: MediaViewModel, b: MediaViewModel.LibraryBrowse, type: MediaType, modifier: Modifier) {
    val back = { if (!vm.libraryBack(type)) vm.openLibrary(null, type) }
    androidx.activity.compose.BackHandler(onBack = back)
    val count = if (b.total >= 0) b.total else b.items.size
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(b.version) { grid.scrollToItem(0) }
    // The stretch in view (and a little either side) loaded as you go.
    LaunchedEffect(grid, b.version) {
        androidx.compose.runtime.snapshotFlow { grid.firstVisibleItemIndex to (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .collect { (a, z) -> vm.ensureRange(a, maxOf(z, a + 20)) }
    }
    Column(modifier) {
        BrandBar(b.library.title, onBack = back) { TopBarIcon(AppIcons.Person, "Settings") { MobileSettingsNav.open = true } }
        if (count == 0) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(if (b.failed) "Couldn't load this." else if (b.total == 0) "Nothing in here." else "Loading…", color = AppColors.TextSecondary, fontSize = 16.sp)
            }
            return@Column
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Adaptive(108.dp), state = grid, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(count) { i ->
                val item = b.item(i)
                if (item == null) Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(MobileColors.Card))
                else PosterTile(item, androidx.compose.ui.unit.Dp.Unspecified) { openTitle(vm, item, item.type) }
            }
        }
    }
}
