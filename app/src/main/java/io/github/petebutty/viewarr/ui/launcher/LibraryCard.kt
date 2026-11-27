package io.github.petebutty.viewarr.ui.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.media.MediaDetail
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.media.TitleLogo
import io.github.petebutty.viewarr.ui.media.ageRating
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * A title's marks over its poster on a library's wall (2026-11-26), from what the server says: a tick once watched,
 * how many episodes of a show are left, NEW for a week after it's added, and a bar along the foot of one you've begun.
 */
@Composable
internal fun BoxScope.PosterMarks(item: MediaItem) {
    // (Marked from the hold-OK menu since the wall was listed: that, over what the server said then.)
    val marked = LauncherLibrary.watched[item.key]
    val m = item.marks ?: (if (marked != null) io.github.petebutty.viewarr.data.media.Marks() else return)
    val left = m.unwatched
    if (marked ?: m.watched) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(AppColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
        Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Black, color = AppColors.OnAccent, maxLines = 1)
    }
    else if (left != null) Text(
        if (left > 99) "99+" else left.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.OnAccent, maxLines = 1,
        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(AppColors.Accent, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
    val added = m.addedAt
    if (added != null && !(marked ?: m.watched) && System.currentTimeMillis() / 1000 - added < NEW_FOR_S) Text(
        "NEW", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(0xFF111111), letterSpacing = 0.8.sp, maxLines = 1,
        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
    m.started?.takeIf { marked == null }?.let { p ->
        Box(Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 8.dp).fillMaxWidth().height(5.dp)
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.03f, 1f)).background(AppColors.Accent, RoundedCornerShape(3.dp)))
        }
    }
}

private const val NEW_FOR_S = 7 * 86_400L

/** "2026-12-18" (or a longer date and time starting so) as "18 Dec 2026"; anything else as it is. */
private fun day(date: String): String = runCatching {
    java.time.LocalDate.parse(date.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK))
}.getOrDefault(date)

/** How long the highlight rests on a poster before its card comes up. */
internal const val CARD_AFTER_MS = 3_000L

/**
 * The card of the poster you've rested on: its name, facts, score, story and main cast. It slides in at the side of
 * the screen away from the poster ([atEnd]: the right), over the wall, and takes no buttons: OK still opens the title.
 */
@Composable
internal fun BoxScope.InfoCard(item: MediaItem?, atEnd: Boolean, vm: MediaViewModel) {
    // (The last one kept while it slides away.)
    var last by remember { mutableStateOf<MediaItem?>(null) }
    var side by remember { mutableStateOf(atEnd) }
    if (item != null) { last = item; side = atEnd }
    AnimatedVisibility(
        visible = item != null,
        enter = slideInHorizontally(tween(260)) { if (side) it / 3 else -it / 3 } + fadeIn(tween(260)),
        exit = slideOutHorizontally(tween(180)) { if (side) it / 3 else -it / 3 } + fadeOut(tween(180)),
        modifier = Modifier.align(if (side) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 36.dp, vertical = 28.dp),
    ) {
        val shown = last ?: return@AnimatedVisibility
        val detail by produceState(LauncherLibrary.details[shown.key], shown.key) {
            if (value == null) value = vm.cardDetail(shown)?.also { d ->
                if (LauncherLibrary.details.size > 60) LauncherLibrary.details.clear()
                LauncherLibrary.details[shown.key] = d
            }
        }
        CardBody(shown, detail, vm)
    }
}

@Composable
private fun CardBody(item: MediaItem, d: MediaDetail?, vm: MediaViewModel) {
    Column(
        Modifier.width(400.dp).background(AppColors.Panel.copy(alpha = 0.97f), RoundedCornerShape(18.dp)).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (item.type == MediaType.Show) "Show" else "Film", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Accent)
        TitleLogo(item, vm::logoFor, height = 52.dp, maxWidth = 300.dp, fontSize = 24)
        // Released, how long, its age rating, and its score.
        val facts = listOfNotNull(
            (d?.released ?: item.releaseDate)?.let { day(it) } ?: item.year?.toString(),
            d?.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
            d?.seasons?.takeIf { it.isNotEmpty() }?.let { s -> "${s.size} season${if (s.size == 1) "" else "s"}" },
            d?.let { ageRating(it) },
            item.rating?.takeIf { it > 0 }?.let { "★ " + "%.1f".format(it) },
        ).joinToString("  ·  ")
        if (facts.isNotEmpty()) Text(facts, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        d?.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.take(4).joinToString("  ·  "), fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        (item.overview ?: d?.item?.overview)?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = AppColors.TextPrimary.copy(alpha = 0.9f), maxLines = 7, overflow = TextOverflow.Ellipsis)
        }
        d?.directors?.takeIf { it.isNotEmpty() }?.let {
            Text((if (item.type == MediaType.Show) "Created by " else "Directed by ") + it.take(2).joinToString(", "), fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val cast = d?.cast.orEmpty().take(4)
        if (cast.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            cast.forEach { c ->
                Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                        if (c.photo != null) AsyncImage(model = c.photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(c.name.take(1), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary)
                    }
                    Text(c.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    c.character?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 10.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (d == null) Text("Loading…", fontSize = 12.sp, color = AppColors.TextSecondary)
    }
}

/**
 * Hold OK on a poster on a library's wall: play it, its page, its trailer, your watchlist, and (a film) watched or not.
 * What opens a title's page or plays goes through Viewarr's own screens, and comes back to the wall after.
 */
@Composable
internal fun WallMenu(item: MediaItem, movies: MediaViewModel, shows: MediaViewModel, onClose: () -> Unit) {
    val show = item.type == MediaType.Show
    val vm = if (show) shows else movies
    val context = androidx.compose.ui.platform.LocalContext.current
    var trailer by remember { mutableStateOf(false) }
    if (trailer) { io.github.petebutty.viewarr.ui.media.TrailerPlayer(item = item, trailers = vm.trailers, onClose = { trailer = false; onClose() }); return }
    val trailerId by produceState<String?>(null, item.key) { value = vm.trailers.youtubeId(item) }
    // (null: no watchlist to add to.)
    val watchlisted by produceState<Boolean?>(null, item.key) { value = vm.watchlistState(item) }
    val known = LauncherLibrary.watched[item.key] ?: item.marks?.watched
    val done by produceState(known ?: false, item.key, known) { if (known == null && !show) value = vm.quickWatched(item) }
    fun over(act: () -> Unit) {
        movies.closeTitlePages(); shows.closeTitlePages()
        act()
        Launcher.openTitle(if (show) io.github.petebutty.viewarr.ui.home.Page.Shows else io.github.petebutty.viewarr.ui.home.Page.Movies)
        LauncherLibrary.keep()
    }
    val options = buildList {
        add(io.github.petebutty.viewarr.ui.common.PopupOption(if (show) "Episodes" else if (item.marks?.started != null) "Resume" else "Play") { over { if (show) vm.quickEpisodes(item) else vm.quickPlay(item) } })
        add(io.github.petebutty.viewarr.ui.common.PopupOption("Details") { over { vm.open(item) } })
        if (trailerId != null) add(io.github.petebutty.viewarr.ui.common.PopupOption("Watch trailer", keepOpen = true) { trailer = true })
        if (!show) add(io.github.petebutty.viewarr.ui.common.PopupOption(if (done) "Mark as not watched" else "Mark as watched") {
            val to = !done
            LauncherLibrary.watched[item.key] = to
            vm.markWatched(item, to) { said ->
                if (said == null) LauncherLibrary.watched.remove(item.key)
                android.widget.Toast.makeText(context, said ?: "Couldn't change it. Try again in a moment.", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        watchlisted?.let { on -> add(io.github.petebutty.viewarr.ui.common.PopupOption(if (on) "Remove from watchlist" else "Add to watchlist") { vm.quickWatchlist(item, add = !on) }) }
    }
    io.github.petebutty.viewarr.ui.common.OptionsPopup(
        title = item.title,
        subtitle = listOfNotNull(item.year?.toString(), item.origin).joinToString("  ·  "),
        options = options,
        onClose = onClose,
    )
}
