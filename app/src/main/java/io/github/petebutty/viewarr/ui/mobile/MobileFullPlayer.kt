package io.github.petebutty.viewarr.ui.mobile

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.playback.AspectMode
import io.github.petebutty.viewarr.playback.PlaybackState
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.guide.channelLogo
import io.github.petebutty.viewarr.ui.guide.guideTitle
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay

private const val CONTROLS_MS = 5_000L

private fun Long.hms(): String {
    val s = (this / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/**
 * Live TV full screen on a phone or tablet (on its side, or the full-screen button). A tap shows the controls for a
 * few seconds: back and the channel at the top left; favourite, picture size and the small window at the top right;
 * channel down, back 10 s, play / pause, on 10 s and channel up in the middle; how far in at the bottom with the picture's size and frame rate; and More channels (or a swipe
 * up) for the group's other channels without leaving full screen.
 */
@Composable
internal fun MobileFullPlayer(pb: PlaybackViewModel, guide: GuideViewModel, ch: ChannelListItem, programmes: List<GuideProgram>?, now: Long, serverUrl: String, session: Session?) {
    val state by pb.state.collectAsStateWithLifecycle()
    val paused by pb.paused.collectAsStateWithLifecycle()
    val catchup by pb.catchup.collectAsStateWithLifecycle()
    val position by pb.position.collectAsStateWithLifecycle()
    val favourites by guide.favourites.collectAsStateWithLifecycle()
    val channels by guide.channels.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var shownUntil by remember { mutableLongStateOf(System.currentTimeMillis() + CONTROLS_MS) }
    var tick by remember { mutableLongStateOf(0L) }
    var moreOpen by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    LaunchedEffect(shownUntil) { val left = shownUntil - System.currentTimeMillis(); if (left > 0) delay(left); tick++ }
    // (The clock for the times and the picture's details, once a second.)
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    val controls = moreOpen || System.currentTimeMillis() < shownUntil || paused || state is PlaybackState.Error
    fun poke() { shownUntil = System.currentTimeMillis() + CONTROLS_MS }
    val swipeFar = with(LocalDensity.current) { 70.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (moreOpen) moreOpen = false else shownUntil = if (System.currentTimeMillis() < shownUntil) 0 else System.currentTimeMillis() + CONTROLS_MS }) }
            .pointerInput(Unit) {
                var y = 0f
                detectVerticalDragGestures(onDragStart = { y = 0f }, onDragEnd = { if (y < -swipeFar) moreOpen = true else if (y > swipeFar) moreOpen = false }) { change, by -> change.consume(); y += by }
            },
    ) {
        LivePicture(pb, Modifier.fillMaxSize())
        when (val s = state) {
            PlaybackState.Buffering -> Text("Loading…", color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
            is PlaybackState.Error -> Text(s.message, color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            else -> Unit
        }
        if (sourcesOpen) MobileSources(pb) { sourcesOpen = false }
        if (!controls) return@Box
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
            // ---- Top: back and what's on, then the buttons ----
            val onNow = catchup ?: programmes?.firstOrNull { it.startTime <= now && it.endTime > now }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlainIcon(AppIcons.Back, "Leave full screen") { MobileNav.leaveFullscreen(activity) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(ch.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    onNow?.let { Text(guideTitle(it), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                val fav = ch.uuid in favourites
                PlainIcon(if (fav) AppIcons.Heart else AppIcons.HeartOutline, if (fav) "Remove from favourites" else "Add to favourites") { guide.toggleFavourite(ch); poke() }
                val aspect by pb.aspect.collectAsStateWithLifecycle()
                PlainIcon(AppIcons.Aspect, "Picture size: ${aspect.label}") {
                    pb.setAspect(AspectMode.entries[(aspect.ordinal + 1) % AspectMode.entries.size]); poke()
                }
                // The channel's sources (Dispatcharr's streams for it), as the TV's Source button: tap one to switch.
                if (session != null && !session.isDirect) PlainIcon(AppIcons.Swap, "Source") { sourcesOpen = true; pb.loadSources(); poke() }
                PlainIcon(AppIcons.Pip, "Small window") { enterPip(activity) }
                if (MobilePlayOnTv.key != null) PlainIcon(AppIcons.Tv, "Play on TV") {
                    MobilePlayOnTv.ask(io.github.petebutty.viewarr.data.cast.PlayOnTv.channelLink(ch.uuid)) { pb.stop(); MobileNav.leaveFullscreen(activity) }
                }
            }
            // ---- Middle: channel down, back 10 s, play / pause, on 10 s, channel up ----
            val i = channels.indexOfFirst { it.id == ch.id }
            fun step(d: Int) { if (i >= 0 && channels.isNotEmpty()) channels.getOrNull(((i + d) % channels.size + channels.size) % channels.size)?.let { MobileNav.open(it, watch = true) } }
            val canBack = catchup != null || pb.canRewind(ch)
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                PlainIcon(AppIcons.SkipPrevious, "Previous channel", size = 40.dp) { step(-1); poke() }
                PlainIcon(AppIcons.Rewind, "Back 10 seconds", size = 36.dp, modifier = Modifier.then(if (canBack) Modifier else Modifier.background(Color.Transparent))) { if (canBack) pb.seekBy(-10_000); poke() }
                OverButton(if (paused) AppIcons.Play else AppIcons.Pause, if (paused) "Play" else "Pause", Modifier, big = true) { pb.togglePause(); poke() }
                PlainIcon(AppIcons.FastForward, "On 10 seconds", size = 36.dp) { pb.seekBy(10_000); poke() }
                PlainIcon(AppIcons.SkipNext, "Next channel", size = 40.dp) { step(1); poke() }
            }
            // (No brightness / volume sliders: the phone's own do that. 2026-11-27.)
            // ---- Bottom: how far in, the picture's details, sound, full screen, More channels ----
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                val (done, total) = if (catchup != null) position.first to position.second
                    else onNow?.let { (now - it.startTime) to (it.endTime - it.startTime) } ?: (0L to 0L)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(done.hms(), color = Color.White, fontSize = 15.sp)
                    Text("  /  ${total.hms()}", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    PlainIcon(if (LiveSound.muted) AppIcons.VolumeOff else AppIcons.Audio, if (LiveSound.muted) "Sound on" else "Sound off") { LiveSound.toggle(pb); poke() }
                    PlainIcon(AppIcons.FullscreenExit, "Leave full screen") { MobileNav.leaveFullscreen(activity) }
                }
                val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(AppColors.Accent))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val v = pb.player?.videoFormat
                    if (v != null && v.height > 0) Badge("${v.width}x${v.height}" + when { v.height >= 2160 -> " (4K)"; v.height >= 1080 -> " (FHD)"; v.height >= 720 -> " (HD)"; else -> "" })
                    if (v != null && v.frameRate > 0) Badge("${v.frameRate.toInt()} FPS")
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).clickable { moreOpen = !moreOpen; poke() }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("More channels", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Tap or swipe up", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        }
                        Icon(AppIcons.ChevronUp, null, tint = Color.White, modifier = Modifier.padding(start = 8.dp).size(30.dp))
                    }
                }
            }
        }
        if (moreOpen) MoreChannels(guide, ch, serverUrl, Modifier.align(Alignment.BottomCenter)) { moreOpen = false }
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp))
}

/** The group's other channels along the bottom (and the groups to pick from), without leaving full screen. */
@Composable
private fun MoreChannels(guide: GuideViewModel, ch: ChannelListItem, serverUrl: String, modifier: Modifier, onClose: () -> Unit) {
    val groups by guide.groups.collectAsStateWithLifecycle()
    val hidden by guide.menuHiddenGroups.collectAsStateWithLifecycle()
    val groupId by guide.groupId.collectAsStateWithLifecycle()
    val channels by guide.channels.collectAsStateWithLifecycle()
    val programs by guide.programs.collectAsStateWithLifecycle()
    val now by guide.now.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.85f)).windowInsetsPadding(WindowInsets.safeDrawing).padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("More channels", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            PlainIcon(AppIcons.Close, "Close") { onClose() }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(groups.filter { it.name !in hidden }, key = { it.id }) { g ->
                val on = g.id == groupId
                Text(g.name, color = if (on) AppColors.Accent else Color.White, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Color.White else Color.Transparent)
                        .border(1.dp, if (on) Color.White else MobileColors.Line, RoundedCornerShape(20.dp)).clickable { guide.selectGroup(g.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(channels, key = { it.id }) { c ->
                val p = programs[c.id]?.firstOrNull { it.startTime <= now && it.endTime > now }
                Column(
                    Modifier.width(220.dp).clip(RoundedCornerShape(18.dp)).background(MobileColors.Card)
                        .border(2.dp, if (c.id == ch.id) AppColors.Accent else Color.Transparent, RoundedCornerShape(18.dp))
                        .clickable { MobileNav.open(c, watch = true); onClose() }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                        channelLogo(serverUrl, c)?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(c.name, color = if (c.id == ch.id) AppColors.Accent else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p?.let { guideTitle(it) } ?: "", color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The channel's sources to pick from (current ticked), with what Dispatcharr last saw of each; a note while switching. */
@Composable
internal fun MobileSources(pb: PlaybackViewModel, onClose: () -> Unit) {
    val sources by pb.sources.collectAsStateWithLifecycle()
    val note by pb.sourceNote.collectAsStateWithLifecycle()
    val list = sources
    val options = list?.map { src ->
        listOfNotNull(src.name, src.provider.takeIf { it.isNotBlank() && it != src.name }, src.quality, src.warning?.let { "⚠ $it" }).joinToString("  ·  ")
    }
    Chooser(
        "Source",
        note ?: if (list == null) "Loading the sources…" else "Tap a source to switch to it.",
        options ?: emptyList(),
        list?.indexOfFirst { it.current }?.takeIf { it >= 0 },
        onPick = { i -> list?.getOrNull(i)?.let { if (!it.current) pb.switchSource(it) }; onClose() },
        onDismiss = onClose,
    )
}
