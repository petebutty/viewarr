package io.github.petebutty.viewarr.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.layout
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
import androidx.tv.material3.Surface
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
     * Left from the channels: the menu strip opens beside them (channel groups, then the main list), as TiviMate.
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
    // Settings → Appearance → End of a list: 1 jumps to the other end on a fresh press, 2 stops.
    val ends = if (AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.LIST_ENDS) == 0) 1 else 2
    // (Opening: the highlighted channel at the top of the list.)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, start))
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
    LaunchedEffect(groupId) {
        awaitFrame()
        listState.scrollToItem(maxOf(0, channels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)))
        if (!embedded) { awaitFrame(); runCatching { focus.requestFocus() } }
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
            .width(460.dp)
            // The menu strip's colour (in the strip, the strip draws it once behind every column).
            .then(if (embedded) Modifier else Modifier.background(AppColors.MenuPanel))
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 36.dp),
    ) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // (One copy of the channels for the count, the keys and the rows: see GuideScreen.)
            val list = channels
            val count = list.size
            items(count, key = { v -> list.getOrNull(v)?.id ?: "x$v" }) { v ->
                val ch = list.getOrNull(v) ?: return@items
                val nowOn = GuideViewModel.programAt(programs[ch.id].orEmpty(), now)?.title
                Surface(
                    onClick = { onPick(ch) },
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
                        .onFocusChanged { if (it.isFocused) { focusedCh = ch; focusedV = v } }
                        .fillMaxWidth(),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (ch.id == current.id) AppColors.CellCurrent.copy(alpha = 0.8f) else Color.Transparent,
                        contentColor = AppColors.TextPrimary,
                        focusedContainerColor = AppColors.Accent,
                        focusedContentColor = AppColors.OnAccent,
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (AppColors.palette.showNumbers) Text(ch.number?.let { n -> if (n % 1.0 == 0.0) n.toInt().toString() else n.toString() }.orEmpty(), fontSize = 13.sp, modifier = Modifier.width(44.dp))
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
                            nowOn?.let { Text(it, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        if (ch.id == epgFor?.id) Text("›", fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
    epgFor?.let { ch -> ChannelPrograms(vm, ch, now, epgFocus, onLive = { onPick(ch) }, onCatchup = { onCatchup(ch, it) }, onFuture = { onFuture(ch, it) }) }
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
) {
    val programs by androidx.compose.runtime.produceState<List<GuideProgram>?>(null, ch.id) { value = runCatching { vm.programsFor(ch) }.getOrDefault(emptyList()) }
    val list = programs
    val nowIndex = list?.indexOfFirst { it.endTime > now }?.coerceAtLeast(0) ?: 0
    val state = rememberLazyListState()
    LaunchedEffect(list != null) {
        if (list.isNullOrEmpty()) return@LaunchedEffect
        state.scrollToItem(maxOf(0, nowIndex - 2))
        awaitFrame(); awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }
    val hhmm = io.github.petebutty.viewarr.ui.theme.Dates.time
    val day = java.time.format.DateTimeFormatter.ofPattern("EEE d")
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val catchupFrom = if (ch.catchupDays > 0) now - ch.catchupDays * 86_400_000L else Long.MAX_VALUE
    Box(
        Modifier.fillMaxHeight().width(440.dp)
            .background(AppColors.Panel.copy(alpha = AppColors.palette.menuAlpha))
            .padding(start = 16.dp, top = 24.dp, bottom = 24.dp, end = 20.dp),
    ) {
        Column {
            Text(ch.name, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            when {
                list == null -> Text("Loading…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
                list.isEmpty() -> Text("No programme information for this channel.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(8.dp))
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
                            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (live) AppColors.CellCurrent.copy(alpha = 0.8f) else Color.Transparent,
                                contentColor = if (past && !canCatchup) AppColors.TextSecondary else AppColors.TextPrimary,
                                focusedContainerColor = AppColors.Accent,
                                focusedContentColor = AppColors.OnAccent,
                            ),
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(time, fontSize = 13.sp, modifier = Modifier.width(if (start.toLocalDate() != today) 92.dp else 52.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(io.github.petebutty.viewarr.ui.guide.displayTitle(p), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val tag = when { live -> "● On now"; canCatchup -> "⟲ Catch-up: OK to play"; else -> null }
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
    return day + " " + d.format(io.github.petebutty.viewarr.ui.theme.Dates.time)
}
