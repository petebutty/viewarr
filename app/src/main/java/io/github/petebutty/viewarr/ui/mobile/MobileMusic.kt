package io.github.petebutty.viewarr.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.music.Song
import io.github.petebutty.viewarr.playback.MusicPlayer
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.music.MusicCard
import io.github.petebutty.viewarr.ui.music.MusicDetail
import io.github.petebutty.viewarr.ui.music.MusicKind
import io.github.petebutty.viewarr.ui.music.MusicViewModel
import io.github.petebutty.viewarr.ui.music.RowLook
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay

/** Previewarr Mobile's music: the full Now playing page open or not (the small bar shows otherwise, while music plays). */
internal object MobileMusic {
    var nowOpen by mutableStateOf(false)
}

/** The Music tab: the home rows (or search), an album / playlist / artist page, or a grid (a genre's albums). */
@Composable
internal fun MobileMusicPage(modifier: Modifier) {
    val vm: MusicViewModel = hiltViewModel()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    LaunchedEffect(enabled) { if (enabled) { if (home.rows.isEmpty()) vm.load() else vm.homeShown() } }
    BackHandler(enabled = detail != null || browse != null) { vm.back() }
    Box(modifier.background(MobileColors.Background)) {
        val d = detail
        val b = browse
        when {
            !enabled -> Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
                BrandBar("Music")
                PhoneNote("Music is off. Turn it on in Settings → Accounts & sources → Music, and choose which servers' music to use.")
                ButtonColumn { PhoneButton("Open Accounts & sources", Modifier.fillMaxWidth()) { MobileSettingsNav.page = "m:accounts"; MobileSettingsNav.open = true } }
            }
            d != null -> MusicDetailPage(d, vm)
            b != null -> MusicGrid(b.title, b.cards, b.loading, vm)
            else -> MusicHomePage(vm)
        }
    }
}

/** Card sizes from the screen: about 42% of the width on a phone (two and a bit across), smaller rows on a tablet. */
@Composable
private fun cardWidth(maxW: Dp, maxH: Dp): Dp = minOf(maxW * 0.42f, maxH * 0.32f).coerceAtLeast(120.dp)

@Composable
private fun MusicHomePage(vm: MusicViewModel) {
    val home by vm.home.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = cardWidth(maxWidth, maxHeight)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 190.dp)) {
            item(key = "top") {
                BrandBar("Music") { TopBarIcon(AppIcons.Person, "Settings") { MobileSettingsNav.open = true } }
                PhoneField(query, { query = it; if (it.isNotBlank()) vm.search(it) }, "Search artists, albums and songs", last = true)
            }
            if (query.isNotBlank()) {
                if (search.artists.isNotEmpty()) item(key = "s:ar") { CardRow("Artists", search.artists, w, vm, fromSearch = true) }
                if (search.albums.isNotEmpty()) item(key = "s:al") { CardRow("Albums", search.albums, w, vm, fromSearch = true) }
                if (search.songs.isNotEmpty()) item(key = "s:so") {
                    GroupTitle("Songs")
                    SongList(search.songs, vm, showArt = true)
                }
                if (search.query == query.trim() && search.artists.isEmpty() && search.albums.isEmpty() && search.songs.isEmpty()) item(key = "s:none") { PhoneNote("Nothing found.") }
            } else {
                if (home.loading && home.rows.isEmpty()) item(key = "loading") { PhoneNote("Loading your music…") }
                home.error?.let { e -> item(key = "error") { PhoneNote(e, error = true) } }
                home.rows.forEach { row ->
                    item(key = "row:" + row.id) {
                        if (row.look == RowLook.Tiles) TileRow(row.title, row.cards, vm) else CardRow(row.title, row.cards, w, vm)
                    }
                }
            }
        }
    }
}

/** A home row: square covers (artists round), title and line under each. */
@Composable
private fun CardRow(title: String, cards: List<MusicCard>, width: Dp, vm: MusicViewModel, fromSearch: Boolean = false) {
    if (cards.isEmpty()) return
    GroupTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cards, key = { it.key }) { c ->
            Column(Modifier.width(width).clip(RoundedCornerShape(12.dp)).clickable { if (fromSearch) vm.openFromSearch(c) else vm.open(c) }) {
                Cover(c, Modifier.fillMaxWidth().aspectRatio(1f), if (c.kind == MusicKind.Artist) CircleShape else RoundedCornerShape(12.dp), vm)
                Text(c.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = if (c.kind == MusicKind.Artist) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                if (c.sub.isNotBlank()) Text(c.sub, color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = if (c.kind == MusicKind.Artist) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Genres and decades: wide coloured tiles with the name on them. */
@Composable
private fun TileRow(title: String, cards: List<MusicCard>, vm: MusicViewModel) {
    if (cards.isEmpty()) return
    GroupTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(cards, key = { it.key }) { c ->
            Box(Modifier.width(170.dp).height(96.dp).clip(RoundedCornerShape(14.dp)).background(MobileColors.Card).clickable { vm.open(c) }, contentAlignment = Alignment.BottomStart) {
                c.cover?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                Text(c.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

/** A card's picture: its cover, else up to four covers in a square, else a note. */
@Composable
private fun Cover(c: MusicCard, modifier: Modifier, shape: Shape, vm: MusicViewModel) {
    Box(modifier.clip(shape).background(MobileColors.Card), contentAlignment = Alignment.Center) {
        val cover = c.cover ?: if (c.kind == MusicKind.Mix) vm.mixArt(c.id)?.small else null
        when {
            cover != null -> AsyncImage(cover, c.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            c.mosaic.size >= 4 -> Column(Modifier.fillMaxSize()) {
                c.mosaic.take(4).chunked(2).forEach { pair ->
                    Row(Modifier.weight(1f)) { pair.forEach { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize()) } }
                }
            }
            c.mosaic.isNotEmpty() -> AsyncImage(c.mosaic.first(), c.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Icon(AppIcons.MusicNote, null, tint = AppColors.TextSecondary, modifier = Modifier.size(48.dp))
        }
    }
}

/** An album, playlist, mix or artist: big picture, Play and Shuffle, then its songs (an artist: top songs, albums, similar). */
@Composable
private fun MusicDetailPage(d: MusicDetail, vm: MusicViewModel) {
    val ctx = LocalContext.current
    val artist = d.card.kind == MusicKind.Artist
    val songs = if (artist) d.topSongs else d.songs
    var bioOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = cardWidth(maxWidth, maxHeight)
        val cover = minOf(maxWidth * 0.7f, maxHeight * 0.4f, 360.dp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 190.dp)) {
            item(key = "head") {
                BrandBar("Music", onBack = { vm.back() })
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Cover(d.card, Modifier.size(cover), if (artist) CircleShape else RoundedCornerShape(16.dp), vm)
                }
                Text(d.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp))
                val sub = listOf(d.sub, d.card.meta).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, color = AppColors.TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val ready = !d.loading && (songs.isNotEmpty() || (artist && d.albums.isNotEmpty()))
                    PhoneButton("Play", Modifier.weight(1f), enabled = ready) {
                        if (artist) vm.playArtist(d, false) else play(ctx, songs, 0, false)
                    }
                    PhoneButton("Shuffle", Modifier.weight(1f), primary = false, enabled = ready) {
                        if (artist) vm.playArtist(d, true) else play(ctx, songs, 0, true)
                    }
                }
                if (d.loading) PhoneNote("Loading…")
            }
            if (artist) d.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                item(key = "bio") {
                    Text(bio, color = AppColors.TextSecondary, fontSize = 15.sp, lineHeight = 21.sp, maxLines = if (bioOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp).clickable { bioOpen = !bioOpen })
                }
            }
            if (songs.isNotEmpty()) item(key = "songs") {
                if (artist) GroupTitle("Top songs")
                SongList(songs, vm, showArt = artist || d.card.kind != MusicKind.Album)
            }
            if (d.albums.isNotEmpty()) item(key = "albums") { CardRow(if (artist) "Albums" else "More", d.albums, w, vm) }
            d.artist?.let { a -> item(key = "artist") { Spacer(Modifier.height(14.dp)); PhoneCard { PhoneRow("More by ${a.title}", opens = true) { vm.open(a) } } } }
            if (d.similar.isNotEmpty()) item(key = "similar") { CardRow("Similar artists", d.similar, w, vm) }
        }
    }
}

/** Songs to tap and play from; a long press for Play next / Add to the queue. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongList(songs: List<Song>, vm: MusicViewModel, showArt: Boolean) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf<Song?>(null) }
    val current = MusicPlayer.current?.id
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        songs.forEachIndexed { i, s ->
            val on = s.id == current
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { play(ctx, songs, i, false) }, onLongClick = { menu = s })
                .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showArt) Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MobileColors.Card)) {
                    s.art?.small?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                } else Text(s.track?.toString() ?: "${i + 1}", color = AppColors.TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(s.title, color = if (on) AppColors.Accent else Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (showArt) "${s.artist} · ${s.album}" else s.artist, color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(clock(s.durationSec * 1000L), color = AppColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    menu?.let { s ->
        Chooser(s.title, s.artist, listOf("Play next", "Add to the queue", "Song radio"), null, onPick = { i ->
            menu = null
            when (i) { 0 -> vm.playNext(listOf(s)); 1 -> vm.addToQueue(listOf(s)); else -> { vm.radio(s); MobileMusic.nowOpen = true } }
        }, onDismiss = { menu = null })
    }
}

/** A genre's (or decade's) albums, or another whole list: a grid of covers, more loaded at the end. */
@Composable
private fun MusicGrid(title: String, cards: List<MusicCard>, loading: Boolean, vm: MusicViewModel) {
    Column(Modifier.fillMaxSize()) {
        BrandBar(title, onBack = { vm.back() })
        if (loading && cards.isEmpty()) PhoneNote("Loading…")
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 190.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(cards, key = { it.key }) { c ->
                if (c == cards.lastOrNull()) LaunchedEffect(cards.size) { vm.loadMore() }
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { vm.open(c) }) {
                    Cover(c, Modifier.fillMaxWidth().aspectRatio(1f), if (c.kind == MusicKind.Artist) CircleShape else RoundedCornerShape(12.dp), vm)
                    Text(c.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    if (c.sub.isNotBlank()) Text(c.sub, color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun play(ctx: android.content.Context, songs: List<Song>, start: Int, shuffled: Boolean) {
    if (songs.isEmpty()) return
    MusicPlayer.play(ctx, songs, start, shuffled)
    MobileMusic.nowOpen = true
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** While music plays: a small bar above the pages' bar (cover, song, play / pause, next); a tap opens Now playing. */
@Composable
internal fun MobileMiniPlayer(modifier: Modifier) {
    if (!MusicPlayer.active || MobileMusic.nowOpen) return
    val song = MusicPlayer.current ?: return
    Row(
        modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 92.dp).widthIn(max = 560.dp).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).background(Color(0xFF2A2A2A)).clickable { MobileMusic.nowOpen = true }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MobileColors.Card)) {
            MusicPlayer.cover(song, 200)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TopBarIcon(if (MusicPlayer.isPlaying) AppIcons.Pause else AppIcons.Play, if (MusicPlayer.isPlaying) "Pause" else "Play") { MusicPlayer.toggle() }
        TopBarIcon(AppIcons.SkipNext, "Next") { MusicPlayer.next() }
    }
}

/** Now playing, full screen: big cover, the song, a bar to drag, the controls, then what's up next. Swipe down or Back closes it. */
@Composable
internal fun MobileNowPlaying() {
    BackHandler { MobileMusic.nowOpen = false }
    val song = MusicPlayer.current
    LaunchedEffect(song) { if (song == null) MobileMusic.nowOpen = false }
    if (song == null) return
    var pos by remember { mutableLongStateOf(MusicPlayer.positionMs) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) { while (true) { if (dragging == null) pos = MusicPlayer.positionMs; delay(500) } }
    val dur = MusicPlayer.durationMs.coerceAtLeast(1)
    var pull by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MobileColors.Background)
        .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (pull > 220f) MobileMusic.nowOpen = false; pull = 0f }) { _, by -> pull = (pull + by).coerceAtLeast(0f) } }) {
        val wide = maxWidth > maxHeight
        val cover = if (wide) minOf(maxHeight * 0.7f, maxWidth * 0.4f) else minOf(maxWidth * 0.82f, maxHeight * 0.45f)
        val upNext = MusicPlayer.upNext(30)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item(key = "top") {
                BrandBar(if (MusicPlayer.radio) "Radio" else "Now playing", onBack = { MobileMusic.nowOpen = false }) {
                    // Play on TV: the queue carries on there from this point; it stops here.
                    // (The queue is saved on the music server when a TV is picked, and the TV loads it from there.)
                    val links: MusicLinkViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                    if (MobilePlayOnTv.key != null) TopBarIcon(AppIcons.Tv, "Play on TV") {
                        MobilePlayOnTv.askLater({ io.github.petebutty.viewarr.data.cast.MusicHandoff.link(links.library) }) { MusicPlayer.stop(); MobileMusic.nowOpen = false }
                    }
                }
            }
            item(key = "cover") {
                Box(Modifier.padding(top = 12.dp).size(cover).clip(RoundedCornerShape(20.dp)).background(MobileColors.Card), contentAlignment = Alignment.Center) {
                    val art = MusicPlayer.cover(song, 800)
                    if (art != null) AsyncImage(art, song.album, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(AppIcons.MusicNote, null, tint = AppColors.TextSecondary, modifier = Modifier.size(72.dp))
                }
            }
            item(key = "controls") {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text(song.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 22.dp))
                    Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "), color = AppColors.TextSecondary, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    val shown = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
                    Slider(
                        value = shown, onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let { f -> MusicPlayer.seekBy((f * dur).toLong() - MusicPlayer.positionMs); pos = (f * dur).toLong() }; dragging = null },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppColors.Accent, inactiveTrackColor = MobileColors.Track),
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(clock((shown * dur).toLong()), color = AppColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(clock(dur), color = AppColors.TextSecondary, fontSize = 13.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        RoundIcon(AppIcons.Shuffle, "Shuffle", on = MusicPlayer.shuffle) { MusicPlayer.toggleShuffle() }
                        RoundIcon(AppIcons.SkipPrevious, "Previous", size = 56.dp) { MusicPlayer.previous() }
                        Box(Modifier.size(80.dp).clip(CircleShape).background(AppColors.Accent).clickable { MusicPlayer.toggle() }, contentAlignment = Alignment.Center) {
                            Icon(if (MusicPlayer.isPlaying) AppIcons.Pause else AppIcons.Play, if (MusicPlayer.isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        RoundIcon(AppIcons.SkipNext, "Next", size = 56.dp) { MusicPlayer.next() }
                        RoundIcon(if (MusicPlayer.repeat == Player.REPEAT_MODE_ONE) AppIcons.RepeatOne else AppIcons.Repeat, "Repeat", on = MusicPlayer.repeat != Player.REPEAT_MODE_OFF) { MusicPlayer.cycleRepeat() }
                    }
                    MusicPlayer.error?.let { Text(it, color = AppColors.Error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            if (upNext.isNotEmpty()) {
                item(key = "nexttitle") { Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) { GroupTitle("Up next") } }
                itemsIndexed(upNext, key = { i, s -> "n$i:${s.id}" }) { _, s ->
                    Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().clickable {
                        MusicPlayer.queue.withIndex().firstOrNull { it.index > MusicPlayer.index && it.value.id == s.id }?.let { MusicPlayer.jumpTo(it.index) }
                    }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MobileColors.Card)) {
                            s.art?.small?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(s.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Dp = 48.dp, on: Boolean? = null, onTap: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = when (on) { null -> Color.White; true -> AppColors.Accent; false -> AppColors.TextSecondary }, modifier = Modifier.size(size * 0.55f))
    }
}
