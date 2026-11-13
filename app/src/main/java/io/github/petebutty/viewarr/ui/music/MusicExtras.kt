package io.github.petebutty.viewarr.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.music.Lyrics
import io.github.petebutty.viewarr.data.music.Song
import io.github.petebutty.viewarr.playback.MusicPlayer
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.glass
import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame

/** What hold OK was pressed on (a song, or an album / playlist / artist card): its options pop up. */
object MusicOptions {
    var song by mutableStateOf<Song?>(null)
    var card by mutableStateOf<MusicCard?>(null)
}

/** The last time a button was pressed anywhere (the music screensaver waits for a quiet spell). */
object UserActivity {
    @Volatile var lastKeyAt = android.os.SystemClock.elapsedRealtime()
    fun touch() { lastKeyAt = android.os.SystemClock.elapsedRealtime() }
}

/** The hold-OK options for a song or a card: play next, add to the queue, radio, favourite. */
@Composable
fun MusicOptionsHost(vm: MusicViewModel) {
    val song = MusicOptions.song
    val card = MusicOptions.card
    if (song == null && card == null) return
    val starred by vm.starred.collectAsStateWithLifecycle()
    val close = { MusicOptions.song = null; MusicOptions.card = null }
    androidx.compose.ui.window.Dialog(onDismissRequest = close) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
        // (Opened by holding OK: letting go of it mustn't also press the first option.)
        Column(Modifier.width(420.dp).glass(18.dp).padding(22.dp).ignoreHeldOk(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(song?.title ?: card!!.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song?.artist ?: card!!.sub, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
            if (song != null) {
                MenuItem(text = "Play next", selected = false, modifier = Modifier.focusRequester(first), onClick = { vm.playNext(listOf(song)); close() })
                MenuItem(text = "Add to queue", selected = false, onClick = { vm.addToQueue(listOf(song)); close() })
                MenuItem(text = "Song radio", selected = false, onClick = { vm.radio(song); close() })
                MenuItem(text = if (song.id in starred) "★ Remove from favourites" else "☆ Add to favourites", selected = false, onClick = { vm.toggleStar(song.id, "song"); close() })
            } else if (card != null) {
                val kind = when (card.kind) { MusicKind.Album -> "album"; MusicKind.Artist -> "artist"; else -> null }
                if (card.kind == MusicKind.Album || card.kind == MusicKind.Playlist) {
                    MenuItem(text = "Play next", selected = false, modifier = Modifier.focusRequester(first), onClick = { vm.songsOf(card) { vm.playNext(it) }; close() })
                    MenuItem(text = "Add to queue", selected = false, onClick = { vm.songsOf(card) { vm.addToQueue(it) }; close() })
                }
                if (kind != null) MenuItem(text = if (card.id in starred) "★ Remove from favourites" else "☆ Add to favourites", selected = false,
                    modifier = if (card.kind == MusicKind.Artist) Modifier.focusRequester(first) else Modifier, onClick = { vm.toggleStar(card.id, kind); close() })
            }
            MenuItem(text = "Cancel", selected = false, onClick = close)
        }
    }
}


/**
 * The playing song's lyrics: timed ones follow the song (the line being sung bright, the ones around it dim); plain
 * ones move down through the words as the song goes on.
 */
@Composable
fun LyricsPanel(lyrics: Lyrics, position: Long, duration: Long, modifier: Modifier, big: Boolean = false) {
    val lines = lyrics.lines
    if (lines.isEmpty()) return
    val at = if (lyrics.synced) (lines.indexOfLast { (it.timeMs ?: 0) <= position + 300 }).coerceAtLeast(0)
    else if (duration > 0) ((position.toFloat() / duration) * lines.size).toInt().coerceIn(0, lines.lastIndex) else 0
    val show = if (big) 4 else 2
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (big) 10.dp else 6.dp)) {
        for (i in (at - show)..(at + show)) {
            val line = lines.getOrNull(i)
            val now = i == at && lyrics.synced
            val dist = kotlin.math.abs(i - at)
            Text(
                line?.text?.ifBlank { "♪" } ?: " ",
                fontSize = if (big) (if (now) 30.sp else 24.sp) else (if (now) 20.sp else 16.sp),
                fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White.copy(alpha = if (now) 1f else (0.75f - dist * 0.13f).coerceAtLeast(0.2f)),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (lyrics.from.isNotBlank()) Text("Lyrics: ${lyrics.from}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * The queue (Now Playing → Queue): every song in order, the one playing marked. OK plays from there; hold OK to
 * move it up or down, or take it out.
 */
@Composable
fun QueuePanel(onClose: () -> Unit) {
    BackHandler { onClose() }
    var picked by remember { mutableStateOf<Int?>(null) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (MusicPlayer.index - 2).coerceAtLeast(0))
    val nowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { nowFocus.requestFocus() } }
    Box(Modifier.fillMaxHeight().width(460.dp).glass(0.dp).padding(horizontal = 18.dp, vertical = 28.dp)) {
        Column {
            Text("QUEUE", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Accent, letterSpacing = 2.sp)
            Text("${MusicPlayer.queue.size} songs  ·  OK: play from here  ·  Hold OK: move or remove", fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(MusicPlayer.queue, key = { i, s -> "$i:${s.id}" }) { i, s ->
                    val now = i == MusicPlayer.index
                    MenuItem(
                        text = (if (now) "♪  " else "${i + 1}.  ") + s.title,
                        trailing = s.artist.take(18),
                        selected = now,
                        onClick = { MusicPlayer.jumpTo(i) },
                        onLongClick = { picked = i },
                        modifier = if (now) Modifier.focusRequester(nowFocus) else Modifier,
                    )
                }
            }
        }
    }
    picked?.let { i ->
        val s = MusicPlayer.queue.getOrNull(i) ?: run { picked = null; return }
        val close = { picked = null }
        androidx.compose.ui.window.Dialog(onDismissRequest = close) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
            Column(Modifier.width(380.dp).glass(18.dp).padding(22.dp).ignoreHeldOk(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MenuItem(text = "Play next", selected = false, modifier = Modifier.focusRequester(first), onClick = { MusicPlayer.move(i, (MusicPlayer.index + if (i > MusicPlayer.index) 1 else 0).coerceIn(0, MusicPlayer.queue.lastIndex)); close() })
                if (i > 0) MenuItem(text = "Move up", selected = false, onClick = { MusicPlayer.move(i, i - 1); close() })
                if (i < MusicPlayer.queue.lastIndex) MenuItem(text = "Move down", selected = false, onClick = { MusicPlayer.move(i, i + 1); close() })
                if (i != MusicPlayer.index) MenuItem(text = "Remove from queue", selected = false, onClick = { MusicPlayer.removeAt(i); close() })
                MenuItem(text = "Cancel", selected = false, onClick = close)
            }
        }
    }
}

/** Sleep timer choices: off, a number of minutes, or the end of this song / album. */
@Composable
fun SleepDialog(onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
        Column(Modifier.width(360.dp).glass(18.dp).padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sleep timer", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            val left = MusicPlayer.sleepMinutesLeft()
            Text(
                when { left != null -> "Stops in about $left min"; MusicPlayer.sleepAfter == "song" -> "Stops after this song"; MusicPlayer.sleepAfter == "album" -> "Stops after this album"; else -> "Off" },
                fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 6.dp),
            )
            MenuItem(text = "Off", selected = false, modifier = Modifier.focusRequester(first), onClick = { MusicPlayer.cancelSleep(); onClose() })
            listOf(15, 30, 45, 60, 90).forEach { m -> MenuItem(text = "$m minutes", selected = false, onClick = { MusicPlayer.setSleep(m); onClose() }) }
            MenuItem(text = "End of this song", selected = false, onClick = { MusicPlayer.sleepAfterThis(album = false); onClose() })
            MenuItem(text = "End of this album", selected = false, onClick = { MusicPlayer.sleepAfterThis(album = true); onClose() })
        }
    }
}

/** A small cover with a song's name and artist (the screensaver's "Up next"). */
@Composable
internal fun MiniSong(s: Song) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(MusicPlayer.cover(s, 100), null, contentScale = ContentScale.Crop, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(AppColors.Panel))
        Column(Modifier.padding(start = 8.dp)) {
            Text(s.title, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Now Playing's artwork (Settings → Playback → Music → Artwork): a square cover, a vinyl record with the cover as its
 * label, a CD printed with the cover, a round cover, or a square with a reflection. The discs turn while the music
 * plays and stop when it's paused.
 */
@Composable
fun NowPlayingArt(url: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val style = io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.MUSIC_ART)
    // The turn: degrees, moved on each frame only while playing (so a pause stops it where it is).
    var angle by remember { mutableStateOf(0f) }
    val spins = style == 1 || style == 2 || style == 3
    val degPerSec = when (style) { 1 -> 200f; 2 -> 120f; else -> 18f } // a record at 33⅓, a CD a little slower, a round cover slowly
    LaunchedEffect(spins, MusicPlayer.isPlaying) {
        if (!spins || !MusicPlayer.isPlaying) return@LaunchedEffect
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (last != 0L) angle = (angle + degPerSec * (t - last) / 1_000_000_000f) % 360f
                last = t
            }
        }
    }
    when (style) {
        // Vinyl: black grooved record, the cover as the round label in the middle.
        1 -> Box(modifier.size(size).graphicsLayerRotate(angle), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSizeCompat(size)) {
                val r = this.size.minDimension / 2
                drawCircle(Color(0xFF111111), r)
                var g = r * 0.97f
                while (g > r * 0.4f) { drawCircle(Color.White.copy(alpha = 0.05f), g, style = androidx.compose.ui.graphics.drawscope.Stroke(1f)); g -= r * 0.025f }
                // A soft shine across the grooves.
                drawCircle(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent, Color.White.copy(alpha = 0.06f), Color.Transparent)), r)
            }
            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size * 0.38f).clip(androidx.compose.foundation.shape.CircleShape).background(AppColors.Panel))
            Box(Modifier.size(size * 0.035f).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF111111)))
        }
        // CD: the cover printed on the disc, a rainbow sheen and the clear hole in the middle.
        2 -> Box(modifier.size(size).graphicsLayerRotate(angle), contentAlignment = Alignment.Center) {
            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(AppColors.Panel))
            androidx.compose.foundation.Canvas(Modifier.matchParentSizeCompat(size)) {
                val r = this.size.minDimension / 2
                drawCircle(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(
                    Color.Transparent, Color(0x33FF80C0), Color.Transparent, Color(0x3380E0FF), Color.Transparent, Color(0x33FFF080), Color.Transparent,
                )), r)
                drawCircle(Color.White.copy(alpha = 0.25f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                drawCircle(Color(0xCCB0B0B0), r * 0.17f)
                drawCircle(Color.Black, r * 0.07f)
            }
        }
        // Round: the cover in a circle, turning slowly.
        3 -> AsyncImage(url, null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).graphicsLayerRotate(angle).clip(androidx.compose.foundation.shape.CircleShape).background(AppColors.Panel)
                .border(1.dp, Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape))
        // Reflection: the square cover with a faded mirror image under it.
        4 -> Column(modifier) {
            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(AppColors.Panel))
            Box(Modifier.size(size, size * 0.3f).clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))) {
                AsyncImage(url, null, contentScale = ContentScale.Crop, alignment = Alignment.BottomCenter,
                    modifier = Modifier.size(size).androidx_scaleY(-1f).padding(0.dp), alpha = 0.35f)
                Box(Modifier.matchParentSizeCompat(size).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
            }
        }
        // Square (as before).
        else -> AsyncImage(url, null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(12.dp)).background(AppColors.Panel).border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)))
    }
}

private fun Modifier.graphicsLayerRotate(deg: Float) = this.then(Modifier.graphicsLayer { rotationZ = deg })
private fun Modifier.androidx_scaleY(s: Float) = this.then(Modifier.graphicsLayer { scaleY = s })
private fun Modifier.matchParentSizeCompat(size: androidx.compose.ui.unit.Dp) = this.then(Modifier.size(size))
