@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.petebutty.viewarr.ui.music

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.playback.MusicPlayer
import io.github.petebutty.viewarr.ui.common.CardAspect
import io.github.petebutty.viewarr.ui.common.FocusCard
import io.github.petebutty.viewarr.ui.common.LiveBehind
import io.github.petebutty.viewarr.ui.common.leftOpens
import io.github.petebutty.viewarr.ui.media.RowGap
import io.github.petebutty.viewarr.ui.media.RowTitle
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.delay

/*
 * The Music home: a hero for the highlighted album, artist or playlist (its artwork, softened, behind the whole
 * page) with a Start radio button, a row of pills to the rest of the library, then rows to find something to play:
 * mixes made for you, what you played lately, artists like the ones you play, what's new, artists you own and don't
 * play, albums to rediscover, favourites, most played, random picks, genres and decades as picture tiles, and every
 * playlist. The rows come from MusicHomeRows.kt (cheap ones) and MusicPersonalRows.kt (made from what you play).
 */

/** Square covers; artists are round and a little smaller; genre / decade tiles are wide. */
private val ALBUM = 140.dp
private val ARTIST = 110.dp
private val TILE = 200.dp

@Composable
internal fun MusicHomePage(
    home: MusicHome, vm: MusicViewModel, page: String, mem: FocusMemory, list: LazyListState,
    rowState: (String) -> LazyListState, focusRequester: FocusRequester, onOpenMenu: () -> Unit,
) {
    LaunchedEffect(Unit) { vm.homeShown() }
    // The card highlighted (once you've rested on it for a moment), else the first one: the hero and the background
    // follow it. (Moving along a row doesn't redraw the hero for every card passed.)
    val first = home.rows.firstOrNull()?.cards?.firstOrNull()
    val hover = MusicHover.card ?: first
    val settled by produceState(hover, hover?.key) { delay(150); value = hover }
    // An artist with no picture: one of their covers stands in (looked up once each).
    val art by produceState(settled?.art, settled?.key) {
        val c = settled
        value = c?.art
        if (c != null && c.art == null && c.kind == MusicKind.Artist) value = runCatching { vm.artistArt(c.id) }.getOrNull()
        if (c != null && c.art == null && c.kind == MusicKind.Mix) value = vm.mixArt(c.id)
    }
    // Which card each row was left on, so coming back onto a row lands there again.
    val rowPos = remember { mutableStateMapOf<String, Int>() }
    // Beside the corner window the header above already names the highlighted card: no hero as well.
    val beside = LiveBehind.preview
    Box(Modifier.fillMaxSize()) {
        HomeBackdrop(art?.large)
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(top = if (beside) 24.dp else 0.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            if (!beside) item(key = "hero") { MusicHero(settled, art?.large, vm, mem, page, onOpenMenu) }
            // (Previewarr: the page opens with the highlight at its top, on the first of these, not down on the first
            // row's first card. 2026-11-26.)
            item(key = "pills") { BrowsePills(vm, entry = home.rows.isEmpty() || io.github.petebutty.viewarr.ui.launcher.LauncherLook.on, focusRequester, onOpenMenu) }
            if (home.loading && home.rows.isEmpty()) items(3, key = { "skeleton$it" }) { SkeletonRow() }
            home.error?.let { e -> item(key = "error") { Text(e, fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = 8.dp)) } }
            itemsIndexed(home.rows, key = { _, r -> r.id }) { ri, row ->
                HomeRow(row, firstRow = ri == 0 && !io.github.petebutty.viewarr.ui.launcher.LauncherLook.on, vm, page, mem, rowState(row.id), rowPos, focusRequester, onOpenMenu)
            }
        }
    }
}

/** The highlighted card's artwork behind the page: softened, dim, fading out to the left (the menu's side) and down. */
@Composable
private fun HomeBackdrop(url: String?) {
    if (!AppColors.palette.heavyExtras) return
    // The last picture stays while a card without one is highlighted (no flash to nothing).
    var shown by remember { mutableStateOf<String?>(null) }
    url?.let { shown = it }
    val bg = AppColors.Scrim
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = shown, animationSpec = tween(700), label = "music-backdrop") { u ->
            if (u != null) AsyncImage(u, null, contentScale = ContentScale.Crop, alpha = 0.45f, modifier = Modifier.fillMaxSize().blur(28.dp))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg, 0.3f to bg.copy(alpha = bg.alpha * 0.55f), 1f to bg.copy(alpha = bg.alpha * 0.2f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to bg)))
    }
}

/**
 * The highlighted album / artist / playlist large at the top: its art on the left, name and details beside, and a
 * Start radio button for it (OK starts a radio from it; Down goes on to the pills, Left opens the menu).
 */
@Composable
private fun MusicHero(card: MusicCard?, artUrl: String?, vm: MusicViewModel, mem: FocusMemory, page: String, onOpenMenu: () -> Unit) {
    // Gone the moment the highlight changes, then one gentle fade in (as the Movies hero).
    val anim = remember(card?.key) { Animatable(0f) }
    LaunchedEffect(card?.key) { anim.animateTo(1f, tween(500)) }
    val round = card?.kind == MusicKind.Artist
    Row(
        Modifier.fillMaxWidth().height(200.dp).padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 22.dp, bottom = 10.dp).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = anim.value },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(150.dp).clip(if (round) Tokens.RoundShape else Tokens.CardShape).background(AppColors.Panel), contentAlignment = Alignment.Center) {
            // (A mix shows its four covers here; its background is its first.)
            card?.let { CardFace(it, large = true, artUrl = artUrl.takeIf { _ -> it.kind != MusicKind.Mix }) }
        }
        Spacer(Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(card?.let { "Music · ${kindName(it.kind)}" } ?: "Music", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(card?.title ?: "Your music", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = card?.let { it.meta.ifBlank { it.sub } }.orEmpty()
            if (line.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(line, fontSize = 15.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            if (card != null && card.kind != MusicKind.Decade) {
                Pill("Start radio", Modifier.remembered(mem, page, "home|hero").leftOpens(onMenu = onOpenMenu)) { vm.radioFrom(card) }
            } else Text("Hold OK on a card for its options", fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun kindName(kind: MusicKind) = when (kind) {
    MusicKind.Album -> "Album"; MusicKind.Artist -> "Artist"; MusicKind.Playlist -> "Playlist"; MusicKind.Genre -> "Genre"; MusicKind.Decade -> "Decade"; MusicKind.Mix -> "Mix"
}

/** The way to the rest of the library: the grids, search, a shuffle of everything, and Now Playing while it plays. */
@Composable
private fun BrowsePills(vm: MusicViewModel, entry: Boolean, focusRequester: FocusRequester, onOpenMenu: () -> Unit) {
    val pills = listOf<Pair<String, () -> Unit>>(
        "Albums A–Z" to { vm.openBrowse(MusicBrowseKind.Albums) },
        "Artists" to { vm.openBrowse(MusicBrowseKind.Artists) },
        "Playlists" to { vm.openBrowse(MusicBrowseKind.Playlists) },
        "Genres" to { vm.openBrowse(MusicBrowseKind.Genres) },
        // The app's search (over the page), which looks through the music too.
        "⌕  Search" to { DeepLink.pendingCommand.value = DeepLink.Command("search") },
        "Shuffle all" to { vm.shuffleLibrary() },
    ) + (if (MusicPlayer.active) listOf<Pair<String, () -> Unit>>("♪  Now playing" to { MusicPlayer.nowPlayingOpen = true }) else emptyList())
    LazyRow(contentPadding = PaddingValues(horizontal = Tokens.Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(pills, key = { _, p -> p.first }) { i, (text, action) ->
            // (Coming in from the menu lands on the first row's first card once there are rows; it lands here till then.)
            val m = (if (i == 0 && entry) Modifier.focusRequester(focusRequester) else Modifier).then(if (i == 0) Modifier.leftOpens(onMenu = onOpenMenu) else Modifier)
            Pill(text, m, action)
        }
    }
}

/** One row of cards or tiles. Left on its first card opens the menu; moving onto it lands on the card it was left on. */
@Composable
private fun HomeRow(
    row: MusicRow, firstRow: Boolean, vm: MusicViewModel, page: String, mem: FocusMemory, state: LazyListState,
    rowPos: MutableMap<String, Int>, focusRequester: FocusRequester, onOpenMenu: () -> Unit,
) {
    Column {
        RowTitle(row.title, Modifier.padding(horizontal = Tokens.Gutter))
        val entryIndex = (rowPos[row.id] ?: 0).coerceIn(0, row.cards.lastIndex.coerceAtLeast(0))
        val rowEntry = remember { FocusRequester() }
        // True only while the remembered card is composed (aiming focus at one that isn't crashed Movies once).
        val entryAttached = remember { booleanArrayOf(false) }
        LazyRow(
            state = state,
            modifier = Modifier.focusProperties {
                enter = { if (entryAttached[0] && state.layoutInfo.visibleItemsInfo.any { it.index == entryIndex }) rowEntry else FocusRequester.Default }
            },
            contentPadding = PaddingValues(horizontal = Tokens.Gutter), horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(row.cards, key = { _, c -> c.key }) { i, c ->
                if (i == entryIndex) DisposableEffect(entryIndex) {
                    entryAttached[0] = true
                    onDispose { entryAttached[0] = false }
                }
                val m = Modifier
                    .then(if (firstRow && i == 0) Modifier.focusRequester(focusRequester) else Modifier)
                    .then(if (i == entryIndex) Modifier.focusRequester(rowEntry) else Modifier)
                    .remembered(mem, page, "home|${row.id}|${c.key}")
                    .onFocusChanged { if (it.isFocused) rowPos[row.id] = i }
                    .then(if (i == 0) Modifier.leftOpens(onMenu = onOpenMenu) else Modifier)
                when (row.look) {
                    // (A mix tile plays: hold OK for its options.)
                    RowLook.Tiles -> PictureTile(c, m, if (c.kind == MusicKind.Mix) ({ MusicOptions.card = c }) else null) { vm.open(c) }
                    RowLook.Cards -> MusicCardView(c, if (c.kind == MusicKind.Artist) ARTIST else ALBUM, m) { vm.open(c) }
                }
            }
        }
    }
}

/** A genre or decade: a wide tile of its first four covers with its name over a dark fade (a plain colour with none). */
@Composable
private fun PictureTile(c: MusicCard, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    // A colour per name (always the same one), so tiles without covers aren't a wall of grey.
    val hue = (c.title.lowercase().hashCode().toUInt() % 360u).toFloat()
    val tint = Color.hsv(hue, 0.5f, 0.45f)
    FocusCard(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) MusicHover.card = c }.width(TILE),
        aspect = CardAspect.Wide, containerColor = tint,
    ) {
        Mosaic(c.mosaic, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.sub.isNotBlank()) Text(c.sub, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
        }
    }
}

/** A card's face: its cover ([artUrl] when the caller has a better one), else its mosaic, else its first letter. */
@Composable
internal fun CardFace(c: MusicCard, large: Boolean = false, artUrl: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(c.title.take(1).uppercase(), fontSize = if (large) 48.sp else 32.sp, color = AppColors.TextSecondary, maxLines = 1, softWrap = false)
        val art = artUrl ?: (if (large) c.art?.large else c.cover)
        if (art != null) AsyncImage(art, c.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Mosaic(c.mosaic, Modifier.fillMaxSize())
    }
}

/** Four covers as a 2×2 grid; fewer than four, the first fills the space; none, nothing. */
@Composable
internal fun Mosaic(urls: List<String>, modifier: Modifier) {
    when {
        urls.isEmpty() -> Unit
        urls.size < 4 -> AsyncImage(urls[0], null, contentScale = ContentScale.Crop, modifier = modifier)
        else -> Column(modifier) {
            repeat(2) { r ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(2) { k -> AsyncImage(urls[r * 2 + k], null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

/** A row's shape while the rows load: a title bar and dim squares, breathing gently. */
@Composable
private fun SkeletonRow() {
    val pulse by rememberInfiniteTransition(label = "music-skeleton").animateFloat(
        0.05f, 0.11f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "alpha",
    )
    val dim = Color.White.copy(alpha = pulse)
    Column(Modifier.padding(horizontal = Tokens.Gutter)) {
        Box(Modifier.size(140.dp, 14.dp).background(dim, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(6) { Box(Modifier.size(ALBUM).background(dim, Tokens.CardShape)) }
        }
        Spacer(Modifier.height(30.dp))
    }
}
