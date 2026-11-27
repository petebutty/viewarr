package io.github.petebutty.viewarr.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.media.Discover
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.player.RowSets
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * Streaming services on a phone or tablet: a tile for each service your addons have rows for (Netflix, Disney+…),
 * the same tiles as Previewarr's TV home (the service's colours and wordmark). A tap opens that service's rows (Just added, New, Originals, Popular, Top rated;
 * films and shows together), big posters as on Films and Shows; a poster opens its page.
 */
@Composable
internal fun MobileServicesPage(modifier: Modifier) {
    // Both pages' rows (the services' rows are among them): loaded here too, in case Films or Shows hasn't been opened.
    val films: MediaViewModel = hiltViewModel(key = "media-Movie")
    val shows: MediaViewModel = hiltViewModel(key = "media-Show")
    LaunchedEffect(Unit) { films.load(MediaType.Movie); shows.load(MediaType.Show) }
    val all by Discover.rows.collectAsStateWithLifecycle()
    val typed = remember(all) {
        all[MediaType.Movie].orEmpty().map { it to MediaType.Movie } + all[MediaType.Show].orEmpty().map { it to MediaType.Show }
    }
    val services = remember(typed) { typed.mapNotNull { RowSets.name(it.first) }.distinct() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val name = open
    if (name != null) { ServiceRows(name, typed.filter { RowSets.name(it.first) == name }, films, shows, modifier, onBack = { open = null }); return }
    Column(modifier) {
        BrandBar("Streaming") { TopBarIcon(AppIcons.Person, "Settings") { MobileSettingsNav.open = true } }
        if (services.isEmpty()) {
            PhoneNote(if (all.isEmpty()) "Loading…" else "No streaming services yet. They come from addons with rows for each service (such as AIOMetadata): Settings → Accounts & sources → Addons.")
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(services, key = { it }) { s ->
                // The TV's own service tile: the service's colours and wordmark (or its app icon), as on Previewarr's home.
                Box(Modifier.fillMaxWidth().aspectRatio(156f / 88f).clip(RoundedCornerShape(14.dp)).background(AppColors.Panel).clickable { open = s }, contentAlignment = Alignment.Center) {
                    io.github.petebutty.viewarr.ui.launcher.LauncherBrands.TileFace(s, films) {
                        Text(s, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

/** One service's rows, films and shows together, in the TV app's order for a service. */
@Composable
private fun ServiceRows(name: String, rows: List<Pair<MediaRow, MediaType>>, films: MediaViewModel, shows: MediaViewModel, modifier: Modifier, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ordered = remember(rows) { rows.filter { it.first.items.isNotEmpty() }.sortedWith(compareBy({ RowSets.kindRank(it.first) }, { if (it.second == MediaType.Show) 0 else 1 })) }
    Column(modifier) {
        BrandBar(name, onBack = onBack) { TopBarIcon(AppIcons.Person, "Settings") { MobileSettingsNav.open = true } }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tileWidth: Dp = minOf(maxWidth * 0.46f, (maxHeight * 0.5f - 64.dp) / 1.5f).coerceAtLeast(120.dp)
            LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
                items(ordered, key = { it.first.id + it.second.name }) { (row, type) ->
                    Column(Modifier.padding(top = 14.dp)) {
                        val title = RowSets.rowTitle(row) + if (type == MediaType.Show) " · Shows" else " · Films"
                        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(row.items, key = { it.key }) { item -> PosterTile(item, tileWidth) { openTitle(if (type == MediaType.Show) shows else films, item, type) } }
                        }
                    }
                }
            }
        }
    }
}
