package io.github.petebutty.viewarr.ui.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.playback.PlaybackState
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.guide.channelLogo
import io.github.petebutty.viewarr.ui.guide.guideTitle
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** Where the phone screens are: the channel list, or one channel's page (and whether it was opened to watch it). */
internal object MobileNav {
    var channel by mutableStateOf<ChannelListItem?>(null)
        private set
    /** Full screen asked for with the button (as well as turning the phone on its side). */
    var fullscreen by mutableStateOf(false)

    /** [watch]: start it playing; false: the page only (tap the picture to watch). [catchup]: start that programme from its start. */
    fun open(ch: ChannelListItem, watch: Boolean, catchup: GuideProgram? = null) { channel = ch; startWatching = watch; startCatchup = catchup }
    var startCatchup: GuideProgram? = null
        private set
    fun close() { channel = null; fullscreen = false }

    /** Out of full screen: a phone stays upright until full screen is asked for again (a tablet turns freely). */
    fun leaveFullscreen(activity: Activity?) {
        fullscreen = false
        val tablet = (activity?.resources?.configuration?.smallestScreenWidthDp ?: 0) >= 600
        activity?.requestedOrientation = if (tablet) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }
    var startWatching = true
        private set
}

private fun Long.clock() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(Dates.time)

/**
 * One channel on a phone or tablet: its picture at the top (upright), what's on now, and its programmes day by day
 * (earlier ones it has catch-up for can be played). On its side, a phone shows only the picture, full screen; a tablet
 * on its side shows the picture and the programmes side by side.
 */
@Composable
internal fun MobileChannelPage(ch: ChannelListItem, session: Session?) {
    val pb: PlaybackViewModel = hiltViewModel()
    val guide: GuideViewModel = hiltViewModel()
    val activity = LocalContext.current as? Activity
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tablet = config.smallestScreenWidthDp >= 600
    val full = MobileNav.fullscreen || (landscape && !tablet)
    val playingChannel by pb.channel.collectAsStateWithLifecycle()
    val watching = playingChannel?.id == ch.id

    // Opened from the channel's guide (watch now, or a catch-up programme): it starts at once. From the list: tap the picture.
    LaunchedEffect(ch.id) {
        val c = MobileNav.startCatchup
        if (c != null) pb.playCatchup(ch, c, session) else if (MobileNav.startWatching) pb.play(ch, session)
    }
    // Leaving the page stops it, and the phone turns as it's held again.
    DisposableEffect(Unit) {
        onDispose {
            pb.stop()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
    // Full screen with the button: sideways until it's left (by the button, or Back).
    LaunchedEffect(MobileNav.fullscreen) {
        if (MobileNav.fullscreen) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    BackHandler { if (full) MobileNav.leaveFullscreen(activity) else MobileNav.close() }

    var programmes by remember(ch.id) { mutableStateOf<List<GuideProgram>?>(null) }
    // Every day the guide has (Settings → Advanced → TV guide days ahead), not only the next day and a bit.
    LaunchedEffect(ch.id) { programmes = runCatching { guide.programsFor(ch, AppColors.palette.guideForwardMs) }.getOrNull().orEmpty() }
    val now by guide.now.collectAsStateWithLifecycle()
    val serverUrl = session?.serverUrl.orEmpty()

    if (full) {
        MobileFullPlayer(pb, guide, ch, programmes, now, serverUrl, session)
        return
    }
    val favourites by guide.favourites.collectAsStateWithLifecycle()
    var sourcesOpen by remember { mutableStateOf(false) }
    if (sourcesOpen) MobileSources(pb) { sourcesOpen = false }
    val topBar: @Composable () -> Unit = {
        BrandBar(ch.name, onBack = { MobileNav.close() }) {
            // Source: while this channel plays, its sources to switch between (as the TV's Source button).
            if (playingChannel?.id == ch.id && session != null && !session.isDirect) TopBarIcon(AppIcons.Swap, "Source") { sourcesOpen = true; pb.loadSources() }
            // Play on TV: the channel on Previewarr on a TV; it stops here.
            PlayOnTvButton({ io.github.petebutty.viewarr.data.cast.PlayOnTv.channelLink(ch.uuid) }) { pb.stop() }
            val fav = ch.uuid in favourites
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(if (fav) Color(0xFFE5534B) else Color.Transparent).clickable { guide.toggleFavourite(ch) },
                contentAlignment = Alignment.Center,
            ) { Icon(if (fav) AppIcons.Heart else AppIcons.HeartOutline, contentDescription = if (fav) "Remove from favourites" else "Add to favourites", tint = Color.White, modifier = Modifier.size(26.dp)) }
        }
    }
    Box(Modifier.fillMaxSize().background(MobileColors.Background)) {
        if (landscape && tablet) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1.4f).fillMaxHeight()) {
                    topBar()
                    MobilePicture(pb, ch, watching, session, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    NowInfo(ch, programmes, now, serverUrl)
                }
                Schedule(ch, programmes, now, pb, session, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                topBar()
                MobilePicture(pb, ch, watching, session, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                NowInfo(ch, programmes, now, serverUrl)
                Schedule(ch, programmes, now, pb, session, Modifier.weight(1f).fillMaxWidth())
            }
        }
        // Full screen, bottom right (as well as turning the phone on its side).
        if (watching) Box(
            Modifier.align(Alignment.BottomEnd).padding(20.dp).size(60.dp).clip(RoundedCornerShape(18.dp)).background(AppColors.Accent)
                .clickable { MobileNav.fullscreen = true },
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Fullscreen, contentDescription = "Full screen", tint = Color.White, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
private fun PageIcon(icon: ImageVector, label: String, onTap: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** The live picture (the player's own view), as big as [modifier] makes it. */
@Composable
internal fun LivePicture(pb: PlaybackViewModel, modifier: Modifier) {
    val playerVersion by pb.playerVersion.collectAsStateWithLifecycle()
    val aspect by pb.aspect.collectAsStateWithLifecycle()
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { view ->
            @Suppress("UNUSED_EXPRESSION") playerVersion
            if (view.player !== pb.player) view.player = pb.player
            view.resizeMode = when (aspect) {
                io.github.petebutty.viewarr.playback.AspectMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                io.github.petebutty.viewarr.playback.AspectMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                io.github.petebutty.viewarr.playback.AspectMode.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        },
        modifier = modifier,
    )
}

/** Sound on or off for the live picture (the player's own volume: the phone's stays as it is). */
internal object LiveSound {
    var muted by mutableStateOf(false)
    fun toggle(pb: PlaybackViewModel) { muted = !muted; pb.player?.volume = if (muted) 0f else 1f }
}

/** Android's own small window (picture-in-picture) for what's playing. */
internal fun enterPip(activity: Activity?) {
    val a = activity ?: return
    runCatching {
        a.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build())
    }
}

/**
 * The picture on the channel's page: sound on / off (top left), the small window and full screen (top right), play /
 * pause in the middle, for a few seconds after a tap. Not playing yet (opened with a swipe): the logo and Watch.
 */
@Composable
private fun MobilePicture(pb: PlaybackViewModel, ch: ChannelListItem, watching: Boolean, session: Session?, modifier: Modifier) {
    val state by pb.state.collectAsStateWithLifecycle()
    val paused by pb.paused.collectAsStateWithLifecycle()
    val catchup by pb.catchup.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var shownUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 3_000) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(shownUntil) { val left = shownUntil - System.currentTimeMillis(); if (left > 0) delay(left); tick++ }
    @Suppress("UNUSED_EXPRESSION") tick
    val controls = System.currentTimeMillis() < shownUntil || paused || state is PlaybackState.Error

    Box(modifier.background(Color.Black).clickable { if (!watching) pb.play(ch, session) else shownUntil = if (controls) 0 else System.currentTimeMillis() + 3_500 }) {
        if (!watching) {
            Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(AppColors.Accent).padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(24.dp))
                Text("Watch", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            }
            return@Box
        }
        LivePicture(pb, Modifier.fillMaxSize())
        when (val s = state) {
            PlaybackState.Buffering -> Text("Loading…", color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
            is PlaybackState.Error -> Text(s.message, color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            else -> Unit
        }
        if (controls) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            PlainIcon(if (LiveSound.muted) AppIcons.VolumeOff else AppIcons.Audio, if (LiveSound.muted) "Sound on" else "Sound off", Modifier.align(Alignment.TopStart).padding(6.dp)) { LiveSound.toggle(pb); shownUntil = System.currentTimeMillis() + 3_500 }
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                PlainIcon(AppIcons.Pip, "Small window") { enterPip(activity) }
                PlainIcon(AppIcons.Fullscreen, "Full screen") { MobileNav.fullscreen = true }
            }
            if (state !is PlaybackState.Error) OverButton(if (paused) AppIcons.Play else AppIcons.Pause, if (paused) "Play" else "Pause", Modifier.align(Alignment.Center), big = true) {
                pb.togglePause(); shownUntil = System.currentTimeMillis() + 3_500
            }
            if (catchup != null) Row(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.6f))
                    .clickable { pb.play(ch, session) }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("Back to live", color = Color.White, fontSize = 14.sp) }
        }
    }
}

@Composable
internal fun PlainIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 28.dp, onTap: () -> Unit) {
    Box(modifier.size(size + 20.dp).clip(RoundedCornerShape((size + 20.dp) / 2)).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
internal fun OverButton(icon: ImageVector, label: String, modifier: Modifier, big: Boolean = false, onTap: () -> Unit) {
    val size = if (big) 64.dp else 44.dp
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 2)).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(if (big) 36.dp else 24.dp)) }
}

/** The channel's name and logo, and what's on now: its title, how long is left, how far in, and what it's about. */
@Composable
private fun NowInfo(ch: ChannelListItem, programmes: List<GuideProgram>?, now: Long, serverUrl: String) {
    val p = programmes?.firstOrNull { it.startTime <= now && it.endTime > now }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(96.dp).height(60.dp), contentAlignment = Alignment.Center) {
            channelLogo(serverUrl, ch)?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) {
                Text(guideTitle(p), color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${((p.endTime - now) / 60_000).coerceAtLeast(1)} minutes left", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
        }
    }
    if (p != null) {
        val f = ((now - p.startTime).toFloat() / (p.endTime - p.startTime).coerceAtLeast(1)).coerceIn(0f, 1f)
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MobileColors.Track)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(AppColors.Accent))
        }
        p.description?.let { Text(it, color = AppColors.TextSecondary, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
    }
}

/** The channel's programmes, a day at a time (days as tabs): earlier ones with catch-up play from the start. */
@Composable
internal fun Schedule(
    ch: ChannelListItem, programmes: List<GuideProgram>?, now: Long, pb: PlaybackViewModel, session: Session?, modifier: Modifier,
    /** What's on now tapped (the channel's guide over the list: open its page and watch). */
    onPlayNow: () -> Unit = { pb.play(ch, session) },
    /** An earlier programme with catch-up tapped. */
    onCatchup: (GuideProgram) -> Unit = { pb.playCatchup(ch, it, session) },
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val byDay = remember(programmes) { programmes.orEmpty().groupBy { Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate() }.toSortedMap() }
    var day by remember(ch.id) { mutableStateOf(today) }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val catchupFrom = if (ch.catchupDays > 0) now - ch.catchupDays * 86_400_000L else Long.MAX_VALUE
    // A programme still to come: record it (or every episode); one set to record: cancel it. (No reminders on the
    // phone: they pop up from the TV home screen, which Previewarr Mobile doesn't have.)
    val dvr: io.github.petebutty.viewarr.ui.dvr.DvrViewModel = hiltViewModel()
    dvr.recordings.collectAsStateWithLifecycle().value
    dvr.rules.collectAsStateWithLifecycle().value
    val canRecord = dvr.allowed.collectAsStateWithLifecycle().value != false && !dvr.xc.collectAsStateWithLifecycle().value
    var picked by remember { mutableStateOf<GuideProgram?>(null) }
    DvrToasts(dvr)
    Column(modifier) {
        if (byDay.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(byDay.keys.toList()) { d ->
                val on = d == day
                val label = when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; today.plusDays(1) -> "Tomorrow"; else -> d.format(dayFormat) }
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { day = d }.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = if (on) AppColors.Accent else Color.White, fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) AppColors.Accent else Color.Transparent))
                }
            }
        }
        val list = byDay[day].orEmpty()
        val listState = rememberLazyListState()
        // Today opens on what's on now (a few before it in view).
        LaunchedEffect(day, list.size) {
            val i = list.indexOfFirst { it.endTime > now }
            if (i > 0) listState.scrollToItem((i - 1).coerceAtLeast(0))
        }
        when {
            programmes == null -> Text("Loading the guide…", color = AppColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
            list.isEmpty() -> Text("No guide information for this day.", color = AppColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.rowId }) { p ->
                    val onNow = p.startTime <= now && p.endTime > now
                    val past = p.endTime <= now
                    val canPlay = onNow || (past && p.startTime >= catchupFrom)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MobileColors.Card)
                            .clickable(enabled = canPlay || !past) { if (onNow) onPlayNow() else if (past) onCatchup(p) else picked = p }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val colour = when { onNow -> AppColors.Accent; past && !canPlay -> AppColors.TextSecondary; else -> Color.White }
                        Text(p.startTime.clock(), color = colour, fontSize = 16.sp, modifier = Modifier.width(78.dp))
                        Text(guideTitle(p), color = colour, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        when {
                            onNow -> Text("LIVE", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            past && canPlay -> Icon(AppIcons.History, contentDescription = "Catch-up", tint = AppColors.TextSecondary, modifier = Modifier.size(22.dp))
                            dvr.recordingFor(ch.id, p) != null || dvr.ruleFor(ch, p) != null -> Icon(AppIcons.Record, contentDescription = "Recording", tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
    picked?.let { p ->
        val rec = dvr.recordingFor(ch.id, p)
        val opts = buildList<Pair<String, () -> Unit>> {
            if (rec != null) add("Cancel this recording" to { dvr.cancel(rec); Unit })
            else if (!canRecord) add("OK" to {})
            else {
                add("Record" to { dvr.record(ch, p); Unit })
                add("Record every episode" to { dvr.recordSeries(ch, p, newOnly = false); Unit })
                add("Record new episodes only" to { dvr.recordSeries(ch, p, newOnly = true); Unit })
            }
        }
        Chooser(guideTitle(p), (if (rec == null && !canRecord) "Recording needs a Dispatcharr login that's allowed to record.\n\n" else "") + "${ch.name} · ${io.github.petebutty.viewarr.ui.dvr.span(p.startTime, p.endTime)}" + (p.description?.takeIf { it.isNotBlank() }?.let { "\n\n" + it.take(300) } ?: ""),
            opts.map { it.first }, null, onPick = { i -> picked = null; opts[i].second() }, onDismiss = { picked = null })
    }
}
