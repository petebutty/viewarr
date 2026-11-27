package io.github.petebutty.viewarr.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.guide.channelLogo
import io.github.petebutty.viewarr.ui.guide.guideTitle
import io.github.petebutty.viewarr.ui.home.Page
import io.github.petebutty.viewarr.ui.launcher.Launcher
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import java.time.Instant
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Live TV on a phone or tablet: the channel groups as chips along the top, then a card per channel (its logo, name,
 * what's on now and how far in). Tap a channel for its page; swipe one left for its guide, right for the groups. One column upright on a phone, more on a tablet.
 */
@Composable
internal fun MobileLiveTv(session: Session?, modifier: Modifier, vm: GuideViewModel = hiltViewModel()) {
    // The channel whose guide is up (a swipe left on its card), or none, and how far across it has slid (0 to 1:
    // it follows the finger, then slides the rest of the way, or back, when it's let go).
    var guideFor by remember { mutableStateOf<ChannelListItem?>(null) }
    val slide = remember { androidx.compose.animation.core.Animatable(0f) }
    val slideScope = androidx.compose.runtime.rememberCoroutineScope()
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    fun settle(open: Boolean) = slideScope.launch {
        slide.animateTo(if (open) 1f else 0f, androidx.compose.animation.core.tween(220))
        if (!open) guideFor = null
    }
    // A swipe right: the channel groups, a page sliding in from the left the same way (0 to 1).
    var groupsUp by remember { mutableStateOf(false) }
    val groupsSlide = remember { androidx.compose.animation.core.Animatable(0f) }
    fun settleGroups(open: Boolean) = slideScope.launch {
        groupsSlide.animateTo(if (open) 1f else 0f, androidx.compose.animation.core.tween(220))
        if (!open) groupsUp = false
    }
    val groups by vm.groups.collectAsStateWithLifecycle()
    val hiddenGroups by vm.menuHiddenGroups.collectAsStateWithLifecycle()
    val groupId by vm.groupId.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(channels, query) {
        val q = query.trim()
        if (q.isEmpty()) channels else channels.filter { it.name.contains(q, ignoreCase = true) }
    }
    val grid = rememberLazyGridState()
    // The programmes loaded for the channels in view (the guide loads a few dozen rows around its place).
    LaunchedEffect(grid, shown, channels) {
        snapshotFlow { grid.firstVisibleItemIndex }.collect { i ->
            val ch = shown.getOrNull(i) ?: return@collect
            vm.followList(channels.indexOf(ch).coerceAtLeast(0))
        }
    }
    // Another group: back to its top.
    LaunchedEffect(groupId) { grid.scrollToItem(0) }
    val serverUrl = session?.serverUrl.orEmpty()

    Box(modifier) { Column(Modifier.fillMaxSize()) {
        // ---- The top: the logo, the page's name and search / settings (or the search box) ----
        if (!searching) BrandBar("Live TV") {
            TopIcon(AppIcons.Search, "Search channels") { searching = true }
            // (No TV guide button: the grid is a remote's page and too fiddly by finger. 2026-11-26.)
            TopIcon(AppIcons.Person, "Settings") { MobileSettingsNav.open = true }
        }
        else Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            run {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(MobileColors.Card).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search channels", color = AppColors.TextSecondary, fontSize = 16.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(AppColors.Accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                TopIcon(AppIcons.Close, "Close search") { searching = false; query = "" }
            }
        }
        // ---- The channel groups ----
        val chips = remember(groups, hiddenGroups) {
            val list = groups.filter { it.name !in hiddenGroups }.map { it.id as Int? to it.name }
            if (AppColors.palette.hideAllChannels) list else listOf<Pair<Int?, String>>(null to "All channels") + list
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(chips, key = { it.first ?: Int.MIN_VALUE }) { (id, name) ->
                val on = id == groupId
                Box(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (on) Color.White else Color.Transparent)
                        .border(1.dp, if (on) Color.White else MobileColors.Line, RoundedCornerShape(24.dp))
                        .clickable { vm.selectGroup(id) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(name, color = if (on) AppColors.Accent else Color.White, fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
        // ---- The channels ----
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(if (query.isNotBlank()) "No channel called \"${query.trim()}\" in this group." else "No channels here yet.", color = AppColors.TextSecondary, fontSize = 15.sp)
            }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            state = grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(shown, key = { it.id }) { ch ->
                val onNow = programs[ch.id]?.firstOrNull { it.startTime <= now && it.endTime > now }
                // A tap: the channel's page (it starts when you tap its picture). A swipe right: its guide, over the list.
                ChannelCard(ch, onNow, now, serverUrl,
                    // Left: the channel's guide, in from the right. Right: the channel groups, in from the left.
                    onSwipe = { dx ->
                        if (dx <= 0f) {
                            if (guideFor?.id != ch.id) guideFor = ch
                            slideScope.launch { groupsSlide.snapTo(0f); slide.snapTo((-dx / screenPx).coerceIn(0f, 1f)) }
                        } else {
                            groupsUp = true
                            slideScope.launch { slide.snapTo(0f); groupsSlide.snapTo((dx / screenPx).coerceIn(0f, 1f)) }
                        }
                    },
                    onSwipeEnd = {
                        if (guideFor != null) settle(slide.value > 0.3f)
                        if (groupsUp) settleGroups(groupsSlide.value > 0.3f)
                    },
                ) { MobileNav.open(ch, watch = false) }
            }
        }
    }
    guideFor?.let { ch ->
        ChannelGuidePage(ch, session, slide = { slide.value }, width = screenPx,
            onDrag = { f -> slideScope.launch { slide.snapTo(f.coerceIn(0f, 1f)) } },
            onSettle = { open -> settle(open) })
    }
    if (groupsUp) GroupsPage(vm, slide = { groupsSlide.value }, width = screenPx,
        onDrag = { f -> slideScope.launch { groupsSlide.snapTo(f.coerceIn(0f, 1f)) } },
        onSettle = { open -> settleGroups(open) })
    }
}


@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onTap: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** One channel: logo, name, what's on now (its start time and title) and how far through it is. */
@Composable
private fun ChannelCard(ch: ChannelListItem, onNow: GuideProgram?, now: Long, serverUrl: String, onSwipe: (Float) -> Unit, onSwipeEnd: () -> Unit, onTap: () -> Unit) {
    // A swipe on the card: left, the channel's guide slides in with the finger; right, the channel groups ([onSwipe]:
    // how far, in pixels, negative to the left).
    val swipe by androidx.compose.runtime.rememberUpdatedState(onSwipe)
    val swipeEnd by androidx.compose.runtime.rememberUpdatedState(onSwipeEnd)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MobileColors.Card)
            .pointerInput(Unit) {
                var x = 0f
                detectHorizontalDragGestures(
                    onDragStart = { x = 0f },
                    onDragEnd = { swipeEnd() },
                    onDragCancel = { swipeEnd() },
                    onHorizontalDrag = { change, by -> change.consume(); x += by; swipe(x) },
                )
            }
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(84.dp).height(56.dp), contentAlignment = Alignment.Center) {
            val logo = channelLogo(serverUrl, ch)
            if (logo != null) AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().fillMaxHeight())
            else Text(ch.name.take(3).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (ch.catchupDays > 0) Icon(AppIcons.History, contentDescription = "Catch-up", tint = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp).size(22.dp))
            }
            Spacer(Modifier.height(4.dp))
            val line = onNow?.let { p -> Instant.ofEpochMilli(p.startTime).atZone(ZoneId.systemDefault()).format(Dates.time) + "   " + guideTitle(p) } ?: "No guide information"
            Text(line, color = AppColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onNow != null) {
                val f = ((now - onNow.startTime).toFloat() / (onNow.endTime - onNow.startTime).coerceAtLeast(1)).coerceIn(0f, 1f)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(MobileColors.Track)) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(AppColors.Accent))
                }
            }
        }
    }
}
