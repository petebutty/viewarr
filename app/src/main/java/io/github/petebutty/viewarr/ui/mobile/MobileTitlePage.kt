package io.github.petebutty.viewarr.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.media.EpisodeInfo
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.Review
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.media.DetailUi
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.media.VodSource
import io.github.petebutty.viewarr.ui.media.upTo
import io.github.petebutty.viewarr.ui.theme.AppColors

/** The title page that's open on a phone or tablet, if any: which page's model holds it (films or shows). */
internal object MobileTitle {
    var type by mutableStateOf<MediaType?>(null)
    fun open(vm: MediaViewModel, item: MediaItem, type: MediaType) { vm.open(item); this.type = type }
}

/**
 * A film's or show's page on a phone or tablet, one long page you scroll: its picture across the top with the title,
 * a big Play (or Resume S3 · E8), the watchlist, what it's about and its ratings; then a show's seasons and episodes
 * (swipe along, tap one to play it), the cast, reviews and more like it.
 */
@Composable
internal fun MobileTitlePage(type: MediaType) {
    val vm: MediaViewModel = hiltViewModel(key = if (type == MediaType.Movie) "media-Movie" else "media-Show")
    val detail by vm.detail.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    // The film player is above every page (VodHost): this page hands it what it plays.
    val vodSource = remember(vm) { VodSource(vm.playing, vm.playerArt, vm::stopPlaying, vm::reportProgress, item = { vm.playingItem ?: vm.detail.value?.detail?.item }) }
    LaunchedEffect(playing) { if (playing != null) VodHost.show(vodSource) }
    val ui = detail
    // Closed (Back past the first page): the list again.
    LaunchedEffect(ui == null) { if (ui == null) MobileTitle.type = null }
    BackHandler(enabled = !VodHost.covering) { vm.close() }
    if (ui == null) return
    val item = ui.detail.item
    val show = item.type == MediaType.Show

    BoxWithConstraints(Modifier.fillMaxSize().background(MobileColors.Background)) {
        val w = maxWidth
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item(key = "hero") { Hero(ui, w) { vm.close() } }
            item(key = "actions") { Actions(ui, vm, show) }
            item(key = "about") { About(ui) }
            if (show) item(key = "episodes") { Episodes(ui, vm, w) }
            if (ui.detail.collection.size > 1) item(key = "collection") { PosterRow(ui.detail.collectionName ?: "Collection", ui.detail.collection, w) { vm.open(it) } }
            if (ui.detail.cast.isNotEmpty()) item(key = "cast") { Cast(ui) }
            if (ui.detail.reviews.isNotEmpty()) item(key = "reviews") { Reviews(ui.detail.reviews, w) }
            if (ui.detail.similar.isNotEmpty()) item(key = "similar") { PosterRow("More like this", ui.detail.similar, w) { vm.open(it) } }
        }
        // What Play (or a tapped episode) is doing, where it can be seen from anywhere on the page: it used to show only
        // under the Play button, so tapping an episode further down looked like nothing happened (2026-11-27).
        ui.message?.takeIf { it.isNotBlank() && !VodHost.covering }?.let { m ->
            androidx.compose.foundation.layout.Row(
                Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 24.dp).clip(RoundedCornerShape(24.dp))
                    .background(Color(0xF0262626)).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (m.endsWith("…")) androidx.compose.material3.CircularProgressIndicator(color = AppColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(m, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = if (m.endsWith("…")) 12.dp else 0.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(kicker: String?, title: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp)) {
        kicker?.let { Text(it.uppercase(), color = AppColors.TextSecondary, fontSize = 12.sp, letterSpacing = 1.2.sp) }
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The picture across the top, fading into the page, with the title on it and Back in the corner. */
@Composable
private fun Hero(ui: DetailUi, w: Dp, onBack: () -> Unit) {
    val item = ui.detail.item
    Box(Modifier.fillMaxWidth().height(w * 0.82f)) {
        (item.backdrop ?: item.poster)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.45f to Color.Transparent, 1f to MobileColors.Background)))
        Box(Modifier.padding(12.dp).size(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Back, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(24.dp))
        }
        // Play on TV: the film (or the show's next episode) opened and started on Previewarr on a TV.
        if (MobilePlayOnTv.key != null) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.45f))
            .clickable { MobilePlayOnTv.ask(io.github.petebutty.viewarr.data.cast.PlayOnTv.titleLink(item)) }, contentAlignment = Alignment.Center) {
            Icon(AppIcons.Tv, contentDescription = "Play on TV", tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(
            item.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 38.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Play (or Resume), Start over, and the watchlist; what it's doing ("Opening on Plex…") under them. */
@Composable
private fun Actions(ui: DetailUi, vm: MediaViewModel, show: Boolean) {
    val canPlay = ui.copies?.any { it.ref != null || it.source == "IPTV" } == true || ui.hasAddons || ui.providerEpisodes.isNotEmpty()
    val next = if (show) ui.upTo() else null
    val label = when {
        ui.copies == null && !ui.hasAddons -> "Looking for a copy…"
        !canPlay -> "Not in your library"
        ui.searching -> "Looking…"
        show && next == null -> "Watch S1 · E1"
        show && next!!.third -> "Resume S${next.first} · E${next.second}"
        show -> "Watch S${next!!.first} · E${next.second}"
        ui.detail.item.progress != null -> "Resume"
        else -> "Play"
    }
    val resuming = (show && next?.third == true) || (!show && ui.detail.item.progress != null)
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (canPlay) AppColors.Accent else MobileColors.Card)
                .clickable(enabled = canPlay) { if (show) vm.playNextUp() else vm.playBest() },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canPlay) Icon(AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(26.dp))
            Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.canWatchlist) Pill(if (ui.watchlisted) AppIcons.BookmarkFilled else AppIcons.Bookmark, if (ui.watchlisted) "On your watchlist" else "Add to watchlist") { vm.toggleWatchlist() }
            if (resuming && canPlay) Pill(AppIcons.Replay, "Start over") { vm.playFromStart() }
        }
        ui.message?.let { Text(it, color = AppColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onTap: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(22.dp)).border(1.dp, MobileColors.Line, RoundedCornerShape(22.dp)).clickable(onClick = onTap).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** What it's about, the year, length or seasons, genres and age rating, and the ratings as chips. */
@Composable
private fun About(ui: DetailUi) {
    val d = ui.detail
    val item = d.item
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
        (item.overview)?.let { Text(it, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp) }
        val facts = listOfNotNull(
            item.year?.toString(),
            d.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }?.takeIf { item.type == MediaType.Movie },
            d.seasons.count { it.number > 0 }.takeIf { it > 0 && item.type == MediaType.Show }?.let { "$it season" + if (it == 1) "" else "s" },
            d.certification,
            d.genres.take(3).joinToString(", ").takeIf { it.isNotBlank() },
        )
        if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), color = AppColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        val ratings = buildList {
            item.rating?.takeIf { it > 0 }?.let { add("TMDB" to "%.1f".format(it)) }
            ui.ratings.forEach { add(it.source to it.value) }
        }.distinctBy { it.first }
        if (ratings.isNotEmpty()) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ratings.take(4).forEach { (src, v) ->
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(MobileColors.Card).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(v, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(src.replace("Rotten Tomatoes", "RT"), color = AppColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/** A show's seasons as tabs and that season's episodes as big cards to swipe along; tap one to play it. */
@Composable
private fun Episodes(ui: DetailUi, vm: MediaViewModel, w: Dp) {
    val seasons = (ui.seasons.ifEmpty { ui.detail.seasons }).filter { it.number > 0 }
    // The season you're up to (else the first) is loaded as the page opens: nothing asked for one until a tab was
    // tapped, so the episodes stayed on "Loading…" (2026-11-27).
    LaunchedEffect(ui.detail.item.key, seasons.size) {
        if (seasons.isNotEmpty() && seasons.none { it.number == ui.season }) {
            vm.selectSeason(ui.upTo()?.first?.takeIf { n -> seasons.any { it.number == n } } ?: seasons.first().number)
        }
    }
    SectionTitle(null, "Episodes")
    if (seasons.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(seasons, key = { it.number }) { s ->
            val on = s.number == ui.season
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.selectSeason(s.number) }.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Season ${s.number}", color = if (on) AppColors.Accent else Color.White, fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(48.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) AppColors.Accent else Color.Transparent))
            }
        }
    }
    val eps = ui.episodes
    when {
        eps == null -> Text("Loading episodes…", color = AppColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
        eps.isEmpty() -> Text("No episodes listed.", color = AppColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
        else -> LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(eps, key = { "${it.season}-${it.number}" }) { ep -> EpisodeCard(ep, ui, w * 0.78f) { vm.playBestEpisode(ep.season, ep.number) } }
        }
    }
}

@Composable
private fun EpisodeCard(ep: EpisodeInfo, ui: DetailUi, width: Dp, onTap: () -> Unit) {
    val key = ep.season to ep.number
    val watched = ui.plexEpisodes[key]?.watched == true || ui.serverEpisodes[key]?.watched == true || key in ui.traktEpisodes || ui.localEpisodes[key]?.watched == true
    Column(Modifier.width(width).clip(RoundedCornerShape(20.dp)).background(MobileColors.Card).clickable(onClick = onTap)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            ep.still?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (watched) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(RoundedCornerShape(15.dp)).background(AppColors.Accent), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Check, contentDescription = "Watched", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.padding(14.dp).heightIn(min = 150.dp)) {
            Text(ep.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ep.overview?.let { Text(it, color = AppColors.TextSecondary, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.weight(1f, fill = false))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("S%02d E%02d".format(ep.season, ep.number))
                ep.airDate?.let { Chip(it) }
                ep.runtimeMin?.takeIf { it > 0 }?.let { Chip("${it}m") }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 5.dp))
}

/** The cast: round photos with their names and parts. */
@Composable
private fun Cast(ui: DetailUi) {
    SectionTitle("Meet the stars", "Cast")
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(ui.detail.cast.take(30)) { c ->
            Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(48.dp)).background(MobileColors.Card), contentAlignment = Alignment.Center) {
                    if (c.photo != null) AsyncImage(c.photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text(c.name.take(1), color = Color.White, fontSize = 30.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(c.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                c.character?.let { Text(it, color = AppColors.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Reviews as cards to swipe along: the score as stars, what they said, who and when. */
@Composable
private fun Reviews(reviews: List<Review>, w: Dp) {
    SectionTitle("Audience voices", "Reviews")
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(reviews.take(15)) { r ->
            Column(Modifier.width(w * 0.82f).clip(RoundedCornerShape(20.dp)).background(MobileColors.Card).padding(16.dp)) {
                r.rating?.let { s -> val n = (s / 2).toInt().coerceIn(0, 5); Text("★".repeat(n) + "☆".repeat(5 - n), color = Color(0xFFF5C518), fontSize = 18.sp) }
                Text("“${r.text.trim()}”", color = Color.White, fontSize = 16.sp, fontStyle = FontStyle.Italic, lineHeight = 22.sp, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(r.author, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                r.date?.let { Text(it.take(10), color = AppColors.TextSecondary, fontSize = 13.sp) }
            }
        }
    }
}

/** A row of posters (more like this, the rest of a collection): tap one for its page. */
@Composable
private fun PosterRow(title: String, items: List<MediaItem>, w: Dp, onTap: (MediaItem) -> Unit) {
    SectionTitle(null, title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.key }) { it2 ->
            Column(Modifier.width(w * 0.36f).clickable { onTap(it2) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(MobileColors.Card)) {
                    (it2.poster ?: it2.backdrop)?.let { p -> AsyncImage(p, it2.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Text(it2.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
