@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.petebutty.viewarr.ui.home

import io.github.petebutty.viewarr.ui.common.touchFocusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import io.github.petebutty.viewarr.ui.common.followingSetting
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import io.github.petebutty.viewarr.ui.common.slowMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.flow.first
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ClickableSurfaceDefaults
import io.github.petebutty.viewarr.ui.common.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.guide.GuideScreen
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.NoScale
import io.github.petebutty.viewarr.ui.theme.Tokens
import io.github.petebutty.viewarr.ui.theme.UiOpts

/**
 * The channel list's programme panel is open inside the menu strip: the strip hides its channel-groups column
 * meanwhile, so groups + channels + programmes (230 + 380–460 + 440 dp) aren't squeezed into 960 (UI review, Tier 1).
 */
internal object ChannelPanelState {
    var programmesOpen by mutableStateOf(false)
}

/** See-through channel list over live TV: Up/Down to browse, OK to switch, Back or Left to close. Hides after 8 s idle. */
@Composable
internal fun ChannelListOverlay(
    vm: GuideViewModel,
    serverUrl: String,
    current: ChannelListItem,
    now: Long,
    onPick: (ChannelListItem) -> Unit,
    onClose: () -> Unit,
    /** A programme that's been on, from the programme panel: play it from catch-up. */
    onCatchup: (ChannelListItem, GuideProgram) -> Unit = { _, _ -> },
    /** A programme still to come: record / remind options. */
    onFuture: (ChannelListItem, GuideProgram) -> Unit = { _, _ -> },
    /**
     * Left from the channels: the menu strip opens beside them (channel groups, then the main list), as classic IPTV apps.
     */
    onLeft: () -> Unit = {},
    /** Inside the menu strip (its background, its Back): focus stays with the groups until a group is picked. */
    embedded: Boolean = false,
) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    // Right on a channel: its programmes in a panel beside the list (Left goes back).
    var focusedCh by remember { mutableStateOf<ChannelListItem?>(null) }
    // (Its place in the list as drawn: with End of a list → Loop, the channels come round again and again.)
    var focusedV by remember { mutableStateOf<Int?>(null) }
    var epgFor by remember { mutableStateOf<ChannelListItem?>(null) }
    val epgFocus = remember { FocusRequester() }
    // (In the menu strip: tells it to make room while the programme panel is open.)
    androidx.compose.runtime.SideEffect { if (embedded) ChannelPanelState.programmesOpen = epgFor != null }
    androidx.compose.runtime.DisposableEffect(embedded) { onDispose { if (embedded) ChannelPanelState.programmesOpen = false } }
    // Closing the programme panel puts you back on the channel it was for.
    var returnTo by remember { mutableStateOf<Int?>(null) }
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(epgFor) {
        if (epgFor != null) { returnTo = focusedV; return@LaunchedEffect }
        if (returnTo == null) return@LaunchedEffect
        awaitFrame()
        runCatching { returnFocus.requestFocus() }
    }
    val programs by vm.programs.collectAsStateWithLifecycle()
    val groupId by vm.groupId.collectAsStateWithLifecycle()
    val start = channels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    // Settings → Live TV → TV guide → End of a list: 1 jumps to the other end on a fresh press, 2 stops.
    val ends = if (AppColors.palette.opt(UiOpts.LIST_ENDS) == 0) 1 else 2
    // (Opening: the highlighted channel at the top of the list.)
    // (Opens with your channel in the middle of the screen, as the guide does: roughly at first, exactly once laid out.)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, start - 5))
    // Jump to the other end: the first and last channels, to move the highlight to.
    val firstCh = remember { FocusRequester() }
    val lastCh = remember { FocusRequester() }
    val jumpScope = androidx.compose.runtime.rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var activity by remember { mutableLongStateOf(System.nanoTime()) }
    // (In the menu strip, Back belongs to the menu unless the programme panel is open.)
    BackHandler(enabled = !embedded || epgFor != null) { if (epgFor != null) epgFor = null else onClose() }
    // The current channel at the top, and focused (in the strip: only scrolled, a group being previewed mustn't take
    // the highlight from the groups).
    // (Waits for the channels: opened before they'd arrived, it scrolled to the top, then the highlight going to your
    // channel scrolled the list all the way down to it in front of you. Now it jumps there first, then highlights.)
    val currentIdx = channels.indexOfFirst { it.id == current.id }
    LaunchedEffect(groupId, currentIdx >= 0 || channels.isNotEmpty()) {
        if (channels.isEmpty()) return@LaunchedEffect
        // Your channel in the middle of the list (how many rows fit is only known once it's laid out), then highlighted:
        // nowhere near an edge, so highlighting it doesn't move the list.
        awaitFrame()
        val rows = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        // (rows / 2: the middle row of what's on screen; counting down from (rows - 1) left it one row too high.)
        listState.scrollToItem(maxOf(0, currentIdx - rows / 2))
        awaitFrame()
        if (!embedded) runCatching { focus.requestFocus() }
    }
    LaunchedEffect(activity) { if (!embedded) { delay(10_000); onClose() } }
    val focusTarget = channels.firstOrNull { it.id == current.id }?.id ?: channels.firstOrNull()?.id
    Row(Modifier.fillMaxHeight().onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown) activity = System.nanoTime()
        when {
            e.type != KeyEventType.KeyDown -> false
            // The programme panel: Left closes it; Right on a channel opens it.
            epgFor != null && e.key == Key.DirectionLeft -> { epgFor = null; true }
            epgFor != null -> false
            e.key == Key.DirectionRight -> { focusedCh?.let { epgFor = it }; true }
            e.key == Key.DirectionLeft -> { onLeft(); true }
            else -> false
        }
    }.trapFocus()) {
    Box(
        Modifier
            .fillMaxHeight()
            .width(channelListWidth())
            // The menu strip's colour (in the strip, the strip draws it once behind every column).
            .then(if (embedded) Modifier else Modifier.background(AppColors.MenuPanel))
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 36.dp),
    ) {
        // Keeps the highlighted channel in the middle of the screen (not the TV's own resting place, which made the list
        // bounce as it opened, nor only at the edges, which let the highlight run to the bottom first).
        val rowMargin = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
        val gentle = remember(rowMargin) {
            object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                // The highlighted channel kept in the middle, the list moving under it (as the guide and classic IPTV apps do);
                // at the top and end of the list it can't move further, so the highlight goes up / down instead.
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                    offset + size / 2f - containerSize / 2f
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(gentle) { gentle.followingSetting() }) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // (One copy of the channels for the count, the keys and the rows: see GuideScreen.)
            val list = channels
            val count = list.size
            items(count, key = { v -> list.getOrNull(v)?.id ?: "x$v" }) { v ->
                val ch = list.getOrNull(v) ?: return@items
                val nowProg = GuideViewModel.programAt(programs[ch.id].orEmpty(), now)
                val nowOn = nowProg?.title
                // Whether this row is the highlighted one, from its own button (for its progress bar's colour).
                val rowSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val rowFocused by rowSource.collectIsFocusedAsState()
                Surface(
                    onClick = { onPick(ch) },
                    interactionSource = rowSource,
                    modifier = (if (ch.id == focusTarget) Modifier.focusRequester(focus) else Modifier)
                        .then(if (v == returnTo) Modifier.focusRequester(returnFocus) else Modifier)
                        .then(if (v == 0) Modifier.focusRequester(firstCh) else Modifier)
                        .then(if (v == count - 1) Modifier.focusRequester(lastCh) else Modifier)
                        // End of a list → Jump to the other end: a fresh press past the last / first channel.
                        .then(if (ends == 1 && count > 1 && (v == 0 || v == count - 1)) Modifier.onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent false
                            val toLast = v == 0 && e.key == Key.DirectionUp
                            val toFirst = v == count - 1 && e.key == Key.DirectionDown
                            if (!toLast && !toFirst) return@onPreviewKeyEvent false
                            jumpScope.launch {
                                listState.scrollToItem(if (toFirst) 0 else maxOf(0, count - 6))
                                awaitFrame()
                                runCatching { (if (toFirst) firstCh else lastCh).requestFocus() }
                            }
                            true
                        } else Modifier)
                        .focusProperties { canFocus = epgFor == null }
                        .onFocusChanged { if (it.isFocused || it.hasFocus) { focusedCh = ch; focusedV = v } }
                        .fillMaxWidth(),
                    scale = NoScale,
                    shape = ClickableSurfaceDefaults.shape(Tokens.RowShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (ch.id == current.id) AppColors.CellCurrent.copy(alpha = 0.8f) else Color.Transparent,
                        contentColor = AppColors.TextPrimary,
                        focusedContainerColor = AppColors.Accent,
                        focusedContentColor = AppColors.OnAccent,
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (AppColors.palette.showNumbers) Text(ch.number?.let { n -> if (n % 1.0 == 0.0) n.toInt().toString() else n.toString() }.orEmpty(), fontSize = 13.sp, modifier = Modifier.width(44.dp), maxLines = 1, softWrap = false)
                        Box(Modifier.size(width = 56.dp, height = 34.dp), contentAlignment = Alignment.Center) {
                            io.github.petebutty.viewarr.ui.guide.channelLogo(serverUrl, ch)?.let { logo ->
                                coil.compose.AsyncImage(
                                    model = logo,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // On the highlighted channel a long programme name slides along after a moment, so it can be
                            // read in full (rows stay one line and the same height); the others end in "…".
                            nowOn?.let {
                                if (rowFocused) Text(it, fontSize = 12.sp, maxLines = 1, softWrap = false,
                                    // (About 20 steps a second: see ui/common/SlowMarquee.)
                                    modifier = Modifier.slowMarquee(initialDelayMillis = 1_200, repeatDelayMillis = 2_000))
                                else Text(it, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            // Settings → Appearance → Live TV → Channel list progress: how far through what's on now.
                            // (Every channel, the highlighted one only, or off.)
                            val progressMode = AppColors.palette.opt(UiOpts.CHLIST_PROGRESS)
                            if (nowProg != null && nowProg.endTime > nowProg.startTime && (progressMode == 0 || (progressMode == 2 && rowFocused))) {
                                val f = ((now - nowProg.startTime).toFloat() / (nowProg.endTime - nowProg.startTime)).coerceIn(0f, 1f)
                                // On the highlighted row (your highlight colour behind it): a solid white bar on a darker
                                // track, so it stands out (it was faint, the row's text colour on the highlight).
                                val highlighted = rowFocused
                                // (Its colour: Settings → Appearance → Live TV → Highlighted progress colour.)
                                val fill = if (!highlighted) AppColors.Accent else when (AppColors.palette.opt(UiOpts.CHLIST_PROGRESS_COLOUR)) {
                                    1 -> Color.Black
                                    2 -> Color(0xFF9AA0A6)
                                    3 -> AppColors.Accent
                                    4 -> androidx.compose.ui.graphics.lerp(AppColors.Accent, Color.Black, 0.55f)
                                    else -> Color.White
                                }
                                val track = if (highlighted) (if (fill == Color.Black) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.3f)) else AppColors.TextPrimary.copy(alpha = 0.25f)
                                Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.85f).height(3.dp).background(track, RoundedCornerShape(2.dp))) {
                                    Box(Modifier.fillMaxWidth(f).height(3.dp).background(fill, RoundedCornerShape(2.dp)))
                                }
                            }
                        }
                        if (ch.id == epgFor?.id) Text("›", fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        }
    }
    epgFor?.let { ch -> ChannelPrograms(vm, ch, now, epgFocus, onLive = { onPick(ch) }, onCatchup = { onCatchup(ch, it) }, onFuture = { onFuture(ch, it) }, embedded = embedded) }
    }
}

/**
 * The channel list's programme panel: one channel's earlier programmes (playable from catch-up when the channel has
 * it), what's on now, and what's coming. Opens on now.
 */
@Composable
internal fun ChannelPrograms(
    vm: GuideViewModel,
    ch: ChannelListItem,
    now: Long,
    focusRequester: FocusRequester,
    onLive: () -> Unit,
    onCatchup: (GuideProgram) -> Unit,
    onFuture: (GuideProgram) -> Unit,
    /** In the menu strip, which already draws the glass behind: no second layer (it doubled, so it barely saw through). */
    embedded: Boolean = false,
) {
    val programs by androidx.compose.runtime.produceState<List<GuideProgram>?>(null, ch.id) { value = runCatching { vm.programsFor(ch) }.getOrDefault(emptyList()) }
    val list = programs
    val nowIndex = list?.indexOfFirst { it.endTime > now }?.coerceAtLeast(0) ?: 0
    val state = rememberLazyListState()
    // The highlight comes in here at once: on "Loading…", then what's on now; or on "No programme information" (the
    // channel rows can't take it while the panel is open, so with nothing to land on the remote went dead until Back).
    LaunchedEffect(list?.isEmpty()) {
        if (!list.isNullOrEmpty()) state.scrollToItem(maxOf(0, nowIndex - 2))
        awaitFrame(); awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }
    val placeholder = Modifier.padding(8.dp).focusRequester(focusRequester).touchFocusable()
    val hhmm = Dates.time
    val day = java.time.format.DateTimeFormatter.ofPattern("EEE d")
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val catchupFrom = if (ch.catchupDays > 0) now - ch.catchupDays * 86_400_000L else Long.MAX_VALUE
    Box(
        Modifier.fillMaxHeight().width(360.dp)  // (was 440: 2026-11-20, "narrow the live screen EPG list also")
            // The glass as Settings → Appearance → Transparency → Glass has it (and its colour).
            .then(if (embedded) Modifier else Modifier.background(AppColors.MenuPanel))
            .padding(start = 16.dp, top = 24.dp, bottom = 24.dp, end = 20.dp),
    ) {
        Column {
            Text(ch.name, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                list == null -> Text("Loading…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = placeholder)
                list.isEmpty() -> Text("No programme information for this channel.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = placeholder)
                else -> LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(list.size, key = { list[it].rowId }) { i ->
                        val p = list[i]
                        val live = p.startTime <= now && p.endTime > now
                        val past = p.endTime <= now
                        val canCatchup = past && p.startTime >= catchupFrom
                        val start = java.time.Instant.ofEpochMilli(p.startTime).atZone(zone)
                        val time = (if (start.toLocalDate() != today) start.format(day) + " " else "") + start.format(hhmm)
                        Surface(
                            onClick = { when { live -> onLive(); canCatchup -> onCatchup(p); !past -> onFuture(p) } },
                            // Hold OK: the same menu as holding OK in the guide (record, from the start, go to show…).
                            onLongClick = { onFuture(p) },
                            modifier = (if (i == nowIndex) Modifier.focusRequester(focusRequester) else Modifier).fillMaxWidth(),
                            scale = NoScale,
                            shape = ClickableSurfaceDefaults.shape(Tokens.RowShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (live) AppColors.CellCurrent.copy(alpha = 0.8f) else Color.Transparent,
                                contentColor = if (past && !canCatchup) AppColors.TextSecondary else AppColors.TextPrimary,
                                focusedContainerColor = AppColors.Accent,
                                focusedContentColor = AppColors.OnAccent,
                            ),
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(time, fontSize = 13.sp, modifier = Modifier.width(if (start.toLocalDate() != today) 92.dp else 52.dp), maxLines = 1, softWrap = false)
                                Column(Modifier.weight(1f)) {
                                    Text(io.github.petebutty.viewarr.ui.guide.guideTitle(p), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    // (Later ones: what holding OK does, as the guide. UI review, Tier 2.)
                                    val tag = when { live -> "● On now"; canCatchup -> "⟲ Catch-up  ·  OK: play"; !past -> "Hold OK: record or remind"; else -> null }
                                    tag?.let { Text(it, fontSize = 11.sp, maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Today 21:00", "Tomorrow 06:30", "Sat 15:00": when a programme starts, as the guide search shows it. */
internal fun whenText(start: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(start).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (d.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE"))
    }
    return day + " " + d.format(Dates.time)
}


/** Settings → Appearance → TV guide → Channel list width: Narrow, Medium (the standard since 0.28.6) or Wide (as before). */
@Composable
internal fun channelListWidth(): androidx.compose.ui.unit.Dp =
    when (AppColors.palette.opt(UiOpts.CHLIST_WIDTH)) { 0 -> 380.dp; 1 -> 320.dp; else -> 460.dp }
