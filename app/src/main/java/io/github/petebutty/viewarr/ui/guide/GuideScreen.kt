package io.github.petebutty.viewarr.ui.guide

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import io.github.petebutty.viewarr.data.settings.ChannelKeys
import io.github.petebutty.viewarr.data.settings.GuideLeft
import io.github.petebutty.viewarr.data.settings.LocalRemote
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.dvr.DvrRecording
import io.github.petebutty.viewarr.data.dvr.DvrState
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.repository.SyncState
import io.github.petebutty.viewarr.ui.common.Badge
import io.github.petebutty.viewarr.ui.common.GroupNames
import io.github.petebutty.viewarr.ui.common.NewsTicker
import io.github.petebutty.viewarr.ui.common.RemoteAction
import io.github.petebutty.viewarr.ui.common.RemoteKeys
import io.github.petebutty.viewarr.ui.common.Ticker
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.Tokens
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.ui.theme.Wallpaper
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId

private val ChannelColumn = 230.dp
// (36: ten whole rows on a 1080p-shaped screen at the usual text size; at 38 the tenth was cut in half. Peter, 2026-11-19.)
private val RowHeight = 36.dp
private const val PAGE = 7

/**
 * The programme names drawn on the grid, in the app's font (Settings → Appearance → Font; read from the text style
 * AppRoot provides: a fixed style here ignored it and the guide alone stayed in the standard font).
 */
@Composable
private fun rememberCellText(): TextStyle {
    val font = LocalTextStyle.current.fontFamily
    return remember(font) { TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = font) }
}

private val timeFormat get() = Dates.time
private val dateFormat get() = Dates.dayTime
private fun Long.local() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

@Composable
fun GuideScreen(
    vm: GuideViewModel,
    recordings: List<DvrRecording> = emptyList(),
    /** What's playing: live on this channel, or this catch-up programme. Drawn with an accent outline. */
    playingChannelId: Int? = null,
    playingCatchupRowId: Long? = null,
    serverUrl: String,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    onOpenMenu: () -> Unit,
    onSelect: (ChannelListItem, GuideProgram?) -> Unit,
    onLongSelect: (ChannelListItem, GuideProgram?) -> Unit = { _, _ -> },
    /**
     * Up / Down with nowhere further to go (End of a list → Stop, or a held button at the end): a film's picture in the
     * corner takes the highlight (true: it did).
     */
    onPastEnd: (up: Boolean) -> Boolean = { false },
) {
    // (Redrawn when a guide offset changes: the marks in the channel column.)
    vm.shiftVersion.collectAsStateWithLifecycle().value
    val okPending = remember { mutableStateOf(false) }
    val remote = LocalRemote.current
    val channels by vm.channels.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val cursor by vm.cursor.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    // Opens already at the highlighted channel, roughly in the middle (it used to start at the top and then move down
    // to it in front of you); the effect below centres it exactly.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (vm.cursor.value.row - 5).coerceAtLeast(0))
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val leftPending = remember { mutableStateOf(false) }
    // A hold of Left that began on what's on now (going into catch-up): it steps back one programme only.
    val leftIntoCatchup = remember { mutableStateOf(false) }

    val selectedChannel = channels.getOrNull(cursor.row)
    val focused = selectedChannel?.let { GuideViewModel.programAt(programs[it.id].orEmpty(), cursor.focusTime) }

    // Scrolled in the same frame as the highlight moves: the effect below runs a frame later, which showed the
    // highlight a row out first, so holding Down flickered between two rows ("ghosting" through the guide fast).
    // The effect stays for the first open (nothing laid out yet) and is a no-op otherwise.
    SideEffect {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty()) {
            val top = maxOf(0, cursor.row - maxOf(visible.size - 1, 1) / 2)
            // (And whole rows: a part-scroll, the top row half under the times, was never put right as only the
            // row was compared. Peter, 2026-11-17.)
            if (top != listState.firstVisibleItemIndex || listState.firstVisibleItemScrollOffset != 0) listState.requestScrollToItem(top)
        }
    }
    LaunchedEffect(cursor.row, channels.size) {
        val target = cursor.row
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val shown = maxOf(visible.size, 1)
        // The highlighted channel kept in the middle of the list (as classic IPTV apps), the list moving under it, rather than
        // only once it reaches the bottom; near the top and the end the list stops and the highlight moves instead.
        // (The last row is usually only partly on screen, so it isn't counted.)
        val whole = maxOf(shown - 1, 1)
        val top = maxOf(0, target - whole / 2)
        if (visible.isEmpty() || top != first || listState.firstVisibleItemScrollOffset != 0) listState.scrollToItem(top)
        // First time (nothing laid out yet, so the middle wasn't known): once it is, centre it exactly, still a jump.
        if (visible.isEmpty()) {
            awaitFrame()
            val v = listState.layoutInfo.visibleItemsInfo
            val t = maxOf(0, target - (maxOf(v.size, 1) - 1).coerceAtLeast(1) / 2)
            if (v.isNotEmpty() && t != (v.firstOrNull()?.index ?: 0)) listState.scrollToItem(t)
        }
    }

    // Left in the guide: the Viewarr preset carries on back through the programmes into catch-up (tap or hold; it stops
    // at the start); classic IPTV apps' goes back a programme, or on what's on now opens the channel groups, and holding Left
    // goes into catch-up. (Older choices are kept as the Viewarr way.)
    val nowMenu = remote.guideLeft == GuideLeft.NowMenu
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, top = 20.dp)
            .focusRequester(focusRequester)
            .focusable(enabled = focusEnabled)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionLeft && nowMenu) {
                    // Tap: back one programme, or the menu when already on what's on now. Hold: back through the guide;
                    // but a hold on what's on now (going into catch-up) steps back just one programme (it raced past
                    // several, stepping on every repeat; Peter, 2026-11-15). Already in the past, a hold scrolls on.
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { leftPending.value = true; leftIntoCatchup.value = vm.isAtNow() }
                        e.type == KeyEventType.KeyDown -> {
                            if (!leftIntoCatchup.value || leftPending.value) vm.moveLeft()
                            leftPending.value = false
                        }
                        e.type == KeyEventType.KeyUp && leftPending.value -> {
                            leftPending.value = false
                            // Only on what's on now. Back in catch-up, a Left that can't go further (the start of catch-up,
                            // or a channel without catch-up that far back) does nothing: it used to open the channel
                            // groups mid-browse, as soon as the earlier programmes had finished loading.
                            if (vm.isAtNow()) onOpenMenu() else vm.moveLeft()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.key == Key.DirectionLeft) {
                    // (The Viewarr way: back through the programmes, tap or hold. Already at "now" and nowhere further
                    // back: the menu, as Left at the edge of any page. UI review, 2026-11-18.)
                    if (e.type == KeyEventType.KeyDown) {
                        val atNow = vm.isAtNow()
                        if (!vm.moveLeft() && atNow && e.nativeKeyEvent.repeatCount == 0) onOpenMenu()
                    }
                    return@onPreviewKeyEvent true
                }
                // OK: a tap selects (play / info); holding it opens the record options.
                if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> okPending.value = true
                        e.type == KeyEventType.KeyDown && okPending.value -> {
                            okPending.value = false
                            selectedChannel?.let { onLongSelect(it, focused) }
                        }
                        e.type == KeyEventType.KeyUp && okPending.value -> {
                            okPending.value = false
                            selectedChannel?.let { onSelect(it, focused) }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Buttons through RemoteKeys, so ones taught in Settings → Remote work here too.
                val action = RemoteKeys.action(e)
                // Past the last channel / above the first: Settings → Live TV → TV guide → End of a list (Jump: 1, Stop: 2).
                val ends = if (AppColors.palette.opt(UiOpts.LIST_ENDS) == 0) 1 else 2
                val fresh = e.nativeKeyEvent.repeatCount == 0
                when {
                    e.key == Key.DirectionUp -> { if (!vm.moveVertical(-1, ends, fresh) && fresh) onPastEnd(true) }
                    e.key == Key.DirectionDown -> { if (!vm.moveVertical(1, ends, fresh) && fresh) onPastEnd(false) }
                    action == RemoteAction.ChannelUp || e.key == Key.PageUp ->
                        vm.moveVertical(if (remote.channelKeys == ChannelKeys.Reversed) PAGE else -PAGE, ends, fresh)
                    action == RemoteAction.ChannelDown || e.key == Key.PageDown ->
                        vm.moveVertical(if (remote.channelKeys == ChannelKeys.Reversed) -PAGE else PAGE, ends, fresh)
                    e.key == Key.DirectionRight || action == RemoteAction.FastForward -> vm.moveRight()
                    action == RemoteAction.Rewind -> vm.moveLeft()
                    // (Record: Settings → Remote buttons, handled above the guide.)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        // The highlighted programme's picture (looked up once you rest on it a moment), else the channel logo.
        val art by produceState<String?>(null, focused?.rowId, AppColors.palette.guideArt) {
            // The guide's own picture first (from Dispatcharr / the guide source), else a lookup by title.
            // Settings → Appearance → Programme pictures off (or low-memory mode): none at all.
            if (!AppColors.palette.guideArt) { value = null; return@produceState }
            focused?.image?.let { value = it; return@produceState }
            val t = focused?.title ?: run { value = null; return@produceState }
            delay(350)
            // Dispatcharr's own picture for it (the grid the guide syncs from leaves pictures out), else TMDB by title.
            value = focused?.let { vm.pictureFor(it) } ?: vm.artFor(t)
        }
        // Its title logo (TMDB), shown instead of the title text when there is one.
        val titleLogo by produceState<String?>(null, focused?.rowId, AppColors.palette.guideTitleLogos) {
            // Settings → Appearance → Programme title logos (off, or low-memory mode: the name only).
            if (!AppColors.palette.guideTitleLogos) { value = null; return@produceState }
            value = null // (the last programme's logo mustn't sit over this one while it's looked up)
            // Switched off for this channel's group (Settings → Appearance → Title logos by group).
            if (selectedChannel?.groupName?.let { !AppColors.palette.shows("logo:$it") } == true) { value = null; return@produceState }
            val t = focused?.title ?: run { value = null; return@produceState }
            delay(350)
            value = vm.logoFor(t)
        }
        // "What's selected" wallpaper: the highlighted programme's picture.
        // Banner (Settings → Appearance → Programme picture shows as): a card in the info area, not the background.
        val banner = AppColors.palette.opt(UiOpts.GUIDE_ART_PLACE) == 1
        LaunchedEffect(art, banner) { if (banner) Wallpaper.selected = null else art?.let { Wallpaper.selected = it } }
        // Scheduled / in-progress Dispatcharr recordings, per channel: a red dot on the programme, a badge in the panel.
        val recordedByChannel = remember(recordings) {
            recordings.filter { it.state == DvrState.Scheduled || it.state == DvrState.Recording }
                .groupBy({ it.channelId }, { it.start..it.end })
        }
        InfoPanel(
            channel = selectedChannel, program = focused, now = now, serverUrl = serverUrl,
            channelPrograms = selectedChannel?.let { programs[it.id] }.orEmpty(),
            recorded = selectedChannel?.let { recordedByChannel[it.id] }.orEmpty(),
            art = art, titleLogo = titleLogo, banner = banner,
        )
        Spacer(Modifier.height(10.dp))

        if (channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (val s = sync) {
                        is SyncState.Running -> s.message
                        is SyncState.Failed -> s.message
                        // (Where to fix it, not just that it's empty. UI review, Tier 2.)
                        SyncState.Idle -> "No channels yet. Add a live TV source in Settings → Accounts & sources."
                    },
                    color = AppColors.TextSecondary,
                    fontSize = 14.sp,
                )
            }
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val gridWidth = maxWidth - ChannelColumn

            Column(Modifier.fillMaxSize()) {
                TimeHeader(now, cursor.windowStart, sync, gridWidth)
                // The last row is usually only part on screen. It's faded out over its bottom 24 units rather than the
                // list being cut to whole rows: the centring above counts the visible rows (and leaves the part one out),
                // so a list of whole rows would have put the highlight a row off centre and needed that code reworked.
                // A fade touches no layout or scrolling. (Drawn off screen first so the fade is a true alpha, whatever
                // is behind the guide: a flat colour, a wallpaper or the picture.)
                LazyColumn(
                    state = listState, userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize()
                        // (No off-screen buffer: inside one, every cell got a buffer of its own from the renderer,
                        // ~150 MB at 4K once the guide had been open. The soft bottom edge is the cut-off last row
                        // drawn fainter instead: see the row's wrapper below. Peter, 2026-11-19.)
                        .clipToBounds(),
                ) {
                    // One copy of the channels for the count, the keys and the rows (the list changing size between
                    // them, e.g. another group, asked for a channel past its end and crashed).
                    val list = channels
                    items(list.size, key = { v -> list.getOrNull(v)?.id ?: "x$v" }) { v ->
                        val channel = list.getOrNull(v) ?: return@items
                        // The row running off the bottom of the guide: fainter, the more of it is cut off.
                        Box(Modifier.graphicsLayer {
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            val info = listState.layoutInfo
                            val me = info.visibleItemsInfo.firstOrNull { it.index == v }
                            alpha = if (me == null) 1f else {
                                val over = (me.offset + me.size - info.viewportEndOffset).toFloat()
                                if (over <= 0f) 1f else (1f - over / me.size * 1.1f).coerceIn(0.15f, 1f)
                            }
                        }) {
                        GuideRow(
                            channel = channel,
                            recorded = recordedByChannel[channel.id].orEmpty(),
                            playingLive = channel.id == playingChannelId && playingCatchupRowId == null,
                            playingRowId = if (channel.id == playingChannelId) playingCatchupRowId else null,
                            programs = programs[channel.id].orEmpty(),
                            selected = v == cursor.row,
                            focusedRowId = if (v == cursor.row) focused?.rowId else null,
                            windowStart = cursor.windowStart,
                            now = now,
                            serverUrl = serverUrl,
                            textMeasurer = textMeasurer,
                            gridWidth = gridWidth,
                        )
                        }
                    }
                }
            }
            if (now >= cursor.windowStart && now < cursor.windowStart + GuideViewModel.VISIBLE_MS) {
                Box(
                    Modifier
                        .offset(x = ChannelColumn + xOf(now, cursor.windowStart, gridWidth))
                        // The "now" line: a hairline, so it doesn't cover programme names.
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(AppColors.Accent.copy(alpha = 0.8f))
                )
            }
        }
        // Settings → Appearance → News ticker → In the TV guide.
        if (Ticker.inGuide) NewsTicker(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun InfoPanel(
    channel: ChannelListItem?,
    program: GuideProgram?,
    now: Long,
    serverUrl: String,
    /** This channel's programmes (for Next / Then) and the recordings covering it (for the Recording badge). */
    channelPrograms: List<GuideProgram> = emptyList(),
    recorded: List<LongRange> = emptyList(),
    art: String? = null,
    titleLogo: String? = null,
    banner: Boolean = false,
) {
    val boxArt = art.takeUnless { banner }
    Row(Modifier.fillMaxWidth().height(180.dp)) {
        Box(
            Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(AppColors.PreviewBox, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (boxArt != null) {
                // The show's own picture, with the channel's logo small in the corner.
                AsyncImage(
                    model = boxArt,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                )
                if (channel != null && (channel.logoId != null || channel.logoUrl != null)) Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AsyncImage(model = channelLogo(serverUrl, channel), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 64.dp, height = 32.dp))
                }
            } else if (channel != null && (channel.logoId != null || channel.logoUrl != null)) {
                AsyncImage(
                    model = channelLogo(serverUrl, channel),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 180.dp, height = 100.dp),
                )
            }
        }
        // The panel is a fixed 180 units tall: the column is clipped so a long synopsis can't run under the grid.
        // The banner (the picture as a card on the right) takes the room the Next / Then column would have; its badges
        // then sit in the time row instead.
        val sideColumn = !banner
        Column(Modifier.weight(1f).padding(start = 24.dp, top = 4.dp).fillMaxHeight().clipToBounds()) {
            // Channel number · name · group, small, above the programme (the group used to sit alone at the row's end).
            if (channel != null) Text(
                listOfNotNull(channel.number?.let(::formatNumber), channel.name, channel.groupName?.let(GroupNames::display)).joinToString("  ·  "),
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            InfoTitle(channel, program, titleLogo)
            if (program != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InfoTimes(program, channel, now)
                    Badges(program)
                    if (!sideColumn) InfoBadges(channel, program, now, recorded)
                }
                Spacer(Modifier.height(8.dp))
                val description = listOfNotNull(program.subTitle, program.description).joinToString(" — ")
                Text(
                    description,
                    fontSize = 14.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (channel != null) {
                Spacer(Modifier.height(6.dp))
                Text("No guide information", fontSize = 14.sp, color = AppColors.TextSecondary)
            }
        }
        if (sideColumn && channel != null) NextUpColumn(channel, program, now, channelPrograms, recorded)
        // Banner: the programme's picture as a card on the right (16:9, the panel's height).
        if (banner && art != null) AsyncImage(
            model = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.padding(start = 16.dp).fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
        )
    }
}

/** The programme's title logo (TMDB) when it has one, else its name (or the channel's, with no guide). */
@Composable
private fun InfoTitle(channel: ChannelListItem?, program: GuideProgram?, titleLogo: String?) {
    // (A logo that fails to load gives the name back, rather than no title at all.)
    var logoFailed by remember(titleLogo) { mutableStateOf(false) }
    if (titleLogo != null && program != null && !logoFailed) Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
        AsyncImage(
            model = titleLogo,
            contentDescription = guideTitle(program),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { logoFailed = true },
            modifier = Modifier.fillMaxHeight().then(Modifier.widthIn(max = 360.dp)),
        )
    } else Text(
        program?.let(::guideTitle) ?: channel?.name.orEmpty(),
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The time row: "19:00 – 20:00", then how far through it is (on now: a bar and the minutes left; later: when it
 * starts and how long it is; over: when it ended, and that it can be replayed).
 */
@Composable
private fun InfoTimes(program: GuideProgram, channel: ChannelListItem?, now: Long) {
    Text(
        "${program.startTime.local().format(timeFormat)} – ${program.endTime.local().format(timeFormat)}",
        fontSize = 14.sp,
        color = AppColors.TextPrimary,
    )
    val length = (program.endTime - program.startTime) / 60_000
    when {
        program.startTime <= now && program.endTime > now -> {
            val fraction = (now - program.startTime).toFloat() / (program.endTime - program.startTime)
            Spacer(Modifier.width(14.dp))
            Box(Modifier.width(180.dp).height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent, RoundedCornerShape(2.dp)))
            }
            Spacer(Modifier.width(10.dp))
            Text("${(program.endTime - now) / 60_000} min left", fontSize = 14.sp, color = AppColors.TextSecondary)
        }
        program.startTime > now -> {
            Spacer(Modifier.width(14.dp))
            Text("Starts in ${(program.startTime - now) / 60_000} min  ·  $length min", fontSize = 14.sp, color = AppColors.TextSecondary)
        }
        else -> {
            Spacer(Modifier.width(14.dp))
            val catchup = if ((channel?.catchupDays ?: 0) > 0) "  ·  Catch-up" else ""
            Text("Ended ${program.endTime.local().format(timeFormat)}$catchup", fontSize = 14.sp, color = AppColors.TextSecondary)
        }
    }
}

/** On the right of the panel: what's on this channel next and after that, and the programme's badges under them. */
@Composable
private fun NextUpColumn(channel: ChannelListItem, program: GuideProgram?, now: Long, channelPrograms: List<GuideProgram>, recorded: List<LongRange>) {
    // After the highlighted programme (or after now, with nothing highlighted).
    val from = program?.endTime ?: now
    val coming = remember(channelPrograms, from) { channelPrograms.filter { it.startTime >= from }.sortedBy { it.startTime }.take(2) }
    // (260 wide: the screen is laid out at 960 units, and the title column needs its share.)
    Column(Modifier.width(260.dp).padding(start = 24.dp, top = 4.dp).fillMaxHeight().clipToBounds()) {
        // Settings → Appearance → TV guide → Next and Then: off hides the two lines (the badges stay).
        if (AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_NEXT) == 0) listOf("Next", "Then").forEachIndexed { i, label ->
            val p = coming.getOrNull(i) ?: return@forEachIndexed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.width(40.dp))
                Text(p.startTime.local().format(timeFormat), fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(end = 8.dp))
                Text(guideTitle(p), fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
        }
        if (program != null) {
            Spacer(Modifier.height(6.dp))
            // (At the column's right edge, under the programme times: Peter, 2026-11-19.)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { InfoBadges(channel, program, now, recorded) }
        }
    }
}

/** The highlighted programme's own badges: it can be replayed, its series / episode, it's being (or will be) recorded. */
@Composable
private fun InfoBadges(channel: ChannelListItem?, program: GuideProgram, now: Long, recorded: List<LongRange>) {
    val replayable = (channel?.catchupDays ?: 0) > 0 && program.startTime < now
    val episode = when {
        program.season != null && program.episode != null -> "S${program.season} E${program.episode}"
        program.episode != null -> "E${program.episode}"
        else -> null
    }
    val isRecorded = recorded.any { r -> minOf(r.last, program.endTime) - maxOf(r.first, program.startTime) > (program.endTime - program.startTime) / 2 }
    // Settings → Appearance → TV guide → Catch-up badge at the top.
    if (replayable && AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_CATCHUP_BADGE) == 0) Badge("↺ Catch-up", filled = false, modifier = Modifier.padding(end = 8.dp))
    // Settings → Appearance → TV guide → Episode badge at the top.
    if (episode != null && AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_EPISODE_BADGE) == 0) Badge(episode, filled = false, modifier = Modifier.padding(end = 8.dp))
    if (isRecorded) Badge("● Recording", modifier = Modifier.padding(end = 8.dp))
}

@Composable
private fun Badges(program: GuideProgram) {
    val labels = listOfNotNull(
        "LIVE".takeIf { program.isLive },
        "NEW".takeIf { program.isNew },
        "PREMIERE".takeIf { program.isPremiere },
    )
    labels.forEach { Badge(it, modifier = Modifier.padding(start = 10.dp)) }
}

@Composable
private fun TimeHeader(now: Long, windowStart: Long, sync: SyncState, gridWidth: Dp) {
    Box(Modifier.fillMaxWidth().height(28.dp)) {
        // Today: the date and time now. Scrolled to another day: that day, in the highlight colour, so it's clear
        // the times along the top are tomorrow's (or yesterday's), not today's.
        val shownDay = windowStart.local().toLocalDate()
        val otherDay = shownDay != now.local().toLocalDate()
        Text(
            if (otherDay) windowStart.local().format(Dates.day) else now.local().format(dateFormat),
            fontSize = 13.sp,
            color = if (otherDay) AppColors.Accent else AppColors.TextPrimary,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Box(Modifier.padding(start = ChannelColumn).fillMaxSize().clipToBounds()) {
            // (Hourly marks on a long timeline start on the hour.)
            val hourly = GuideViewModel.VISIBLE_MS > 180 * 60_000L
            var t = if (hourly && windowStart % 3_600_000L != 0L) windowStart + GuideViewModel.STEP_MS else windowStart
            while (t < windowStart + GuideViewModel.VISIBLE_MS) {
                // Midnight along the top: the new day's name with it ("Sat 00:00").
                Text(
                    (if (t.local().hour == 0 && t.local().minute == 0) t.local().format(Dates.shortDay) + "  " else "") + t.local().format(timeFormat),
                    fontSize = 13.sp,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = xOf(t, windowStart, gridWidth) + 4.dp),
                )
                // (Hourly marks on a long timeline, so the times don't crowd.)
                t += if (hourly) 2 * GuideViewModel.STEP_MS else GuideViewModel.STEP_MS
            }
            HeaderTicks(now, windowStart)
        }
        if (sync is SyncState.Running) {
            Text(
                sync.message,
                fontSize = 12.sp,
                color = AppColors.Accent,
                // (Over the times on a scrim, not a solid block of the background colour: that showed as a dark patch
                // on wallpapers and glass themes.)
                modifier = Modifier.align(Alignment.CenterEnd).background(AppColors.Scrim).padding(start = 8.dp),
            )
        }
    }
}

/**
 * Along the bottom of the time header: a faint tick every half hour (so the blocks below can be read against the
 * times), and a small accent arrow over the top of the "now" line. (Drawn over the header's grid part, so x is the
 * same as the line's: the grid's width times the share of the window.)
 */
@Composable
private fun HeaderTicks(now: Long, windowStart: Long) {
    val tick = AppColors.TextSecondary.copy(alpha = 0.25f)
    val accent = AppColors.Accent
    val windowEnd = windowStart + GuideViewModel.VISIBLE_MS
    Canvas(Modifier.fillMaxSize()) {
        val pxPerMs = size.width / GuideViewModel.VISIBLE_MS
        val tickH = 8.dp.toPx()
        var t = windowStart
        while (t < windowEnd) {
            val x = (t - windowStart) * pxPerMs
            drawLine(tick, Offset(x, size.height - tickH), Offset(x, size.height), 1.dp.toPx())
            t += GuideViewModel.STEP_MS
        }
        if (now >= windowStart && now < windowEnd) {
            val x = (now - windowStart) * pxPerMs
            val half = 3.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(x - half, size.height - 2 * half)
                    lineTo(x + half, size.height - 2 * half)
                    lineTo(x, size.height)
                    close()
                },
                accent,
            )
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelListItem,
    recorded: List<LongRange>,
    playingLive: Boolean = false,
    playingRowId: Long? = null,
    programs: List<GuideProgram>,
    selected: Boolean,
    focusedRowId: Long?,
    windowStart: Long,
    now: Long,
    serverUrl: String,
    textMeasurer: TextMeasurer,
    gridWidth: Dp = 0.dp,
) {
    val guideAlpha = AppColors.palette.guideAlpha
    val windowEnd = windowStart + GuideViewModel.VISIBLE_MS
    val cellText = rememberCellText()
    val cellTextFocused = cellText.copy(fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 1.5.dp)) {
        Row(
            Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .padding(end = 4.dp)
                // The highlighted channel's tile: a wash of the accent (the same radius as every list row).
                .background(if (selected) AppColors.Accent.copy(alpha = 0.22f) else AppColors.CellFuture.copy(alpha = guideAlpha), RoundedCornerShape(if (AppColors.palette.squareCorners) 0.dp else Tokens.RowRadius))
                .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            if (AppColors.palette.showNumbers) Text(
                channel.number?.let(::formatNumber).orEmpty(),
                fontSize = 13.sp,
                color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
                modifier = Modifier.width(44.dp).padding(start = 6.dp),
                maxLines = 1,
            )
            // (In from the tile's rounded edge, numbers or not: flush against it, wide logos looked cut off on the left.)
            Box(Modifier.padding(start = if (AppColors.palette.showNumbers) 0.dp else 8.dp).width(56.dp).fillMaxHeight().padding(vertical = 5.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                if ((channel.logoId != null || channel.logoUrl != null) && AppColors.palette.channelLogos) {
                    AsyncImage(
                        model = channelLogo(serverUrl, channel),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                channel.name,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            // Its guide moved (Guide time offset / Guide times): shown so it's never forgotten.
            // (No "+2h" by the name: abbey, 2026-11-17. The offset is in the channel's hold-OK menu.)
            // ("Highlighted channel only": the icon on every channel said nothing. UI review, 2026-11-18.)
            if (channel.catchupDays > 0 && AppColors.palette.opt(UiOpts.CATCHUP_ICONS).let { it != 2 && (it != 3 || selected) }) CatchupIcon(Modifier.padding(start = 4.dp).size(14.dp))
        }
        val catchupFrom = if (channel.catchupDays > 0) now - channel.catchupDays * 86_400_000L else null
        val onAccent = AppColors.OnAccent
        val programmeIcons = AppColors.palette.opt(UiOpts.CATCHUP_ICONS) == 0
        val accent = AppColors.Accent
        val iconColor = AppColors.TextSecondary
        val square = AppColors.palette.squareCorners
        val progressBars = AppColors.palette.progressBars
        val shadeElapsed = AppColors.palette.opt(UiOpts.GUIDE_SHADE) == 0
        // The highlighted programme's name, when it doesn't fit its block, scrolls along (pause, scroll, pause), as
        // the channel list's does. Only the row with the highlight animates (read in drawing, so only it redraws).
        // (Only when its name doesn't fit its block: a name that fits leaves the row still, with nothing redrawn.)
        val focusedP = focusedRowId?.let { id -> programs.firstOrNull { it.rowId == id } }
        val density = LocalDensity.current
        val gridPx = with(density) { gridWidth.toPx() }
        val overflows = focusedP != null && gridPx > 0f && run {
            val visibleMs = minOf(focusedP.endTime, windowEnd) - maxOf(focusedP.startTime, windowStart)
            val cellPx = visibleMs * gridPx / GuideViewModel.VISIBLE_MS - with(density) { 22.dp.toPx() }
            textMeasurer.measure(text = guideTitle(focusedP), style = cellTextFocused, maxLines = 1, softWrap = false).size.width > cellPx
        }
        // The scrolling name, measured once (not every frame), at a steady speed: a short pause, along, a pause, back.
        // It's drawn on its own layer over the row, so only it redraws as it moves (the whole row's text was measured
        // and drawn again every frame, too much for a frame on some boxes: it juddered).
        val marqueeText = if (overflows && focusedP != null) remember(focusedP.rowId, focusedP.title, cellTextFocused) {
            textMeasurer.measure(text = guideTitle(focusedP), style = cellTextFocused.copy(color = AppColors.OnAccent), maxLines = 1, softWrap = false)
        } else null
        // Where it goes (set while the row draws): its left edge, the room it has, the row's height.
        val marqueeAt = remember { FloatArray(3) }
        // A clock (ms since the name started scrolling): the layer works out where the name is from its real room, so
        // every name moves at the same speed (each used to take a time worked out from a guess at its block's width).
        val marquee = if (marqueeText != null) {
            val clock = remember(focusedP?.rowId) { mutableLongStateOf(0L) }
            LaunchedEffect(focusedP?.rowId) {
                val start = withFrameMillis { it }
                while (true) withFrameMillis { clock.longValue = it - start }
            }
            clock
        } else null
        Box(Modifier.weight(1f).fillMaxHeight()) {
        Canvas(Modifier.matchParentSize()) {
            val pxPerMs = size.width / GuideViewModel.VISIBLE_MS
            val gap = 3.dp.toPx()
            val pad = 8.dp.toPx()
            val radius = if (square) CornerRadius.Zero else CornerRadius(Tokens.RowRadius.toPx())
            val outline = Stroke(1.dp.toPx())
            // Blocks narrower than this (five-minute fillers, news bulletins) get no name, no gap and no corners, so a
            // run of them reads as one quiet strip rather than a row of stubs with cut-off letters.
            val minText = 34.dp.toPx()
            val ordered = if (focusedRowId == null) programs else programs.sortedBy { it.rowId == focusedRowId }
            for (p in ordered) {
                if (p.endTime <= windowStart || p.startTime >= windowEnd) continue
                val x0 = (maxOf(p.startTime, windowStart) - windowStart) * pxPerMs
                val xEnd = (minOf(p.endTime, windowEnd) - windowStart) * pxPerMs
                val tiny = xEnd - x0 < minText
                val x1 = if (tiny) xEnd else xEnd - gap
                if (x1 - x0 < 1f) continue
                val isFocused = p.rowId == focusedRowId
                val color = when {
                    isFocused -> AppColors.Accent
                    p.endTime <= now -> AppColors.CellPast
                    p.startTime <= now && p.endTime > now -> AppColors.CellCurrent
                    else -> AppColors.CellFuture
                }
                val corners = if (tiny) CornerRadius.Zero else radius
                // Settings → Appearance → Transparency → Guide (the highlighted programme stays solid).
                drawRoundRect(if (isFocused) color else color.copy(alpha = color.alpha * guideAlpha), topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = corners)
                // On now: the part already gone lightly shaded up to the time line (Settings → Appearance → Elapsed shading).
                if (shadeElapsed && p.startTime <= now && p.endTime > now) {
                    val nowX = ((now - windowStart) * pxPerMs).coerceIn(x0, x1)
                    if (nowX > x0) clipRect(x0, 0f, nowX, size.height) {
                        drawRoundRect((if (isFocused) onAccent else Color.White).copy(alpha = 0.10f), topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = corners)
                    }
                }
                // No outline on solid blocks (the gap between them is the edge); a faint one only when the guide is
                // see-through, so blocks still read as blocks over a picture.
                if (!isFocused && !tiny && guideAlpha < 1f) drawRoundRect(Color.White.copy(alpha = 0.03f), topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = corners, style = outline)
                // On now: a bar along the bottom showing how far through it is (Settings → Appearance → Progress bars:
                // every channel, only the one you're watching, or none).
                val barsHere = when (progressBars) { 0 -> true; 1 -> playingLive; else -> false }
                if (barsHere && p.startTime <= now && p.endTime > now && x1 - x0 > 16.dp.toPx()) {
                    val inset = 6.dp.toPx()
                    val h = 3.dp.toPx()
                    val y = size.height - h - 4.dp.toPx()
                    val full = p.endTime - p.startTime
                    val done = (now - p.startTime).toFloat() / full
                    // The bar spans the whole programme, even where the cell runs off the left edge.
                    val px0 = (p.startTime - windowStart) * pxPerMs + inset
                    val px1 = (p.endTime - windowStart) * pxPerMs - gap - inset
                    val l = maxOf(px0, x0 + inset)
                    val r = minOf(px1, x1 - inset)
                    val fillEnd = (px0 + (px1 - px0) * done).coerceIn(l, r)
                    val bar = CornerRadius(h / 2)
                    if (r > l) {
                        drawRoundRect((if (isFocused) onAccent else Color.White).copy(alpha = 0.18f), topLeft = Offset(l, y), size = Size(r - l, h), cornerRadius = bar)
                        if (fillEnd > l) drawRoundRect(if (isFocused) onAccent else accent, topLeft = Offset(l, y), size = Size(fillEnd - l, h), cornerRadius = bar)
                    }
                }
                // The programme on screen right now keeps an accent outline, so you always know what's playing.
                val isPlaying = p.rowId == playingRowId || (playingLive && p.startTime <= now && p.endTime > now)
                if (isPlaying && !isFocused) {
                    val w = 2.5.dp.toPx()
                    drawRoundRect(accent, topLeft = Offset(x0 + w / 2, w / 2), size = Size(x1 - x0 - w, size.height - w), cornerRadius = corners,
                        style = Stroke(w))
                }
                val replayable = catchupFrom != null && p.startTime < now && p.startTime >= catchupFrom
                val iconSide = 12.dp.toPx()
                var textX = x0 + pad
                if (!tiny && replayable && programmeIcons && x1 - x0 > iconSide + 2 * pad) {
                    drawCatchupIcon(Offset(textX, (size.height - iconSide) / 2f), iconSide, if (isFocused) onAccent else iconColor)
                    textX += iconSide + 5.dp.toPx()
                }
                val textWidth = (x1 - textX - pad).toInt()
                val hasText = !tiny && textWidth > 0
                val scrolls = isFocused && marqueeText != null && hasText && marqueeText.size.width > textWidth
                if (scrolls) {
                    // Too long for its block: drawn by the layer above (only it redraws as it moves).
                    marqueeAt[0] = textX; marqueeAt[1] = textWidth.toFloat(); marqueeAt[2] = size.height
                } else if (hasText) {
                    // Over and gone (no catch-up to replay it): its name steps back, so what you can still watch stands out.
                    val style = when {
                        isFocused -> cellTextFocused.copy(color = AppColors.OnAccent)
                        p.endTime <= now && !replayable -> cellText.copy(color = Color.White.copy(alpha = 0.5f))
                        else -> cellText
                    }
                    val layout = textMeasurer.measure(
                        text = guideTitle(p),
                        style = style,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = textWidth),
                    )
                    drawText(layout, topLeft = Offset(textX, (size.height - layout.size.height) / 2f))
                }
                val isRecorded = recorded.any { r -> minOf(r.last, p.endTime) - maxOf(r.first, p.startTime) > (p.endTime - p.startTime) / 2 }
                if (isRecorded && x1 - x0 > 20.dp.toPx()) {
                    val dot = 4.dp.toPx()
                    drawCircle(RecordRed, dot, Offset(x1 - pad - dot, size.height / 2f))
                }
            }
        }
        // The scrolling name, on its own layer.
        if (marqueeText != null && marquee != null) Canvas(Modifier.matchParentSize()) {
            val textX = marqueeAt[0]; val textWidth = marqueeAt[1]
            if (textWidth <= 0f) return@Canvas
            val travel = (marqueeText.size.width - textWidth).coerceAtLeast(0f)
            // 60 units a second for every name: a 1.2 s pause, along, 1.2 s at the end, back to the start.
            val speed = 60.dp.toPx() / 1000f
            val pause = 1_200L
            val scrollMs = (travel / speed).toLong().coerceAtLeast(1L)
            val t = marquee.longValue % (pause + scrollMs + pause)
            val shift = when { t < pause -> 0f; t < pause + scrollMs -> (t - pause) * speed; else -> travel }
            clipRect(left = textX, top = 0f, right = textX + textWidth, bottom = size.height) {
                drawText(marqueeText, topLeft = Offset(textX - shift, (size.height - marqueeText.size.height) / 2f))
            }
        }
        }
    }
}

private val RecordRed = Color(0xFFE53935)

/** Rewind-style arrow marking channels and programmes with catch-up. */
@Composable
private fun CatchupIcon(modifier: Modifier) {
    val color = AppColors.TextSecondary
    Canvas(modifier) { drawCatchupIcon(Offset.Zero, size.minDimension, color) }
}

private fun DrawScope.drawCatchupIcon(topLeft: Offset, side: Float, color: Color) {
    val stroke = side * 0.12f
    val x = topLeft.x
    val y = topLeft.y
    drawArc(
        color = color,
        startAngle = -60f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(x + stroke, y + stroke),
        size = Size(side - 2 * stroke, side - 2 * stroke),
        style = Stroke(width = stroke),
    )
    drawPath(
        Path().apply {
            moveTo(x + side * 0.46f, y + side * 0.1f)
            lineTo(x + side * 0.83f, y + side * 0.02f)
            lineTo(x + side * 0.73f, y + side * 0.4f)
            close()
        },
        color,
    )
    drawLine(color, Offset(x + side / 2, y + side * 0.3f), Offset(x + side / 2, y + side / 2), stroke)
    drawLine(color, Offset(x + side / 2, y + side / 2), Offset(x + side * 0.66f, y + side * 0.62f), stroke)
}

private fun xOf(t: Long, windowStart: Long, gridWidth: Dp): Dp =
    gridWidth * ((t - windowStart).toFloat() / GuideViewModel.VISIBLE_MS)

/** A programme's name in the guide and the channel list: with its series / episode or not (Appearance → Live TV). */
fun guideTitle(p: GuideProgram): String =
    if (AppColors.palette.opt(UiOpts.GUIDE_EPISODE) == 0) displayTitle(p) else p.title

fun displayTitle(p: GuideProgram): String {
    val se = when {
        p.season != null && p.episode != null -> ". S${p.season}E${p.episode}"
        p.episode != null -> ". E${p.episode}"
        else -> ""
    }
    val sep = if (se.isNotEmpty() && p.title.lastOrNull()?.let { it in "?!." } == true) " " else ""
    return p.title + if (sep.isEmpty()) se else sep + se.removePrefix(". ")
}

private fun logoUrl(serverUrl: String, logoId: Int) = "${serverUrl.trimEnd('/')}/api/channels/logos/$logoId/cache/"

/** A channel's logo: the provider's own address (IPTV provider) or Dispatcharr's cached logo. */
fun channelLogo(serverUrl: String, channel: ChannelListItem): String? =
    channel.logoUrl ?: channel.logoId?.let { logoUrl(serverUrl, it) }

fun formatNumber(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
