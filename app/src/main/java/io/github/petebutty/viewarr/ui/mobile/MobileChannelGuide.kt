package io.github.petebutty.viewarr.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.guide.channelLogo
import io.github.petebutty.viewarr.ui.guide.guideTitle
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * A channel's guide, a page that slides across over the Live TV list (with the finger, from a swipe right on the
 * channel's card, to the left): what was on, what's on and what's coming, day by day. What's on now (or a catch-up programme) opens
 * the channel's page playing it; a programme still to come offers recording. Watch now and the channel's page at the
 * top. Swipe it back to the right, the back arrow or Back slides it away. [slide]: how far across it is (0 to 1).
 */
@Composable
internal fun ChannelGuidePage(ch: ChannelListItem, session: Session?, slide: () -> Float, width: Float, onDrag: (Float) -> Unit, onSettle: (Boolean) -> Unit) {
    val guide: GuideViewModel = hiltViewModel()
    val pb: PlaybackViewModel = hiltViewModel()
    var programmes by remember(ch.id) { mutableStateOf<List<GuideProgram>?>(null) }
    LaunchedEffect(ch.id) { programmes = runCatching { guide.programsFor(ch, AppColors.palette.guideForwardMs) }.getOrNull().orEmpty() }
    val now by guide.now.collectAsStateWithLifecycle()
    val onNow = programmes?.firstOrNull { it.startTime <= now && it.endTime > now }
    fun watch(catchup: GuideProgram? = null) { onSettle(false); MobileNav.open(ch, watch = true, catchup = catchup) }
    BackHandler { onSettle(false) }
    Column(
        Modifier.fillMaxSize()
            .offset { IntOffset(((1f - slide()) * width).roundToInt(), 0) }
            .background(MobileColors.Background)
            .pointerInput(ch.id) {
                // Dragged back to the left (or right again): it goes with the finger; let go, it settles.
                detectHorizontalDragGestures(
                    onDragEnd = { onSettle(slide() > 0.7f) },
                    onDragCancel = { onSettle(slide() > 0.7f) },
                    onHorizontalDrag = { change, by -> change.consume(); onDrag(slide() - by / width) },
                )
            },
    ) {
        // ---- Back, the channel, what's on now ----
        BrandBar("Guide", onBack = { onSettle(false) })
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(76.dp).height(50.dp), contentAlignment = Alignment.Center) {
                val logo = channelLogo(session?.serverUrl.orEmpty(), ch)
                if (logo != null) AsyncImage(logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else Text(ch.name.take(3).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(ch.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(onNow?.let { "Now: " + guideTitle(it) } ?: "No guide information right now", color = AppColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhoneButton("Watch now", Modifier.weight(1f)) { watch() }
            PhoneButton("Channel page", Modifier.weight(1f), primary = false) { onSettle(false); MobileNav.open(ch, watch = false) }
        }
        // ---- Day by day (clear of the bar of pages along the bottom) ----
        Schedule(ch, programmes, now, pb, session, Modifier.weight(1f).fillMaxWidth().padding(bottom = 92.dp),
            onPlayNow = { watch() }, onCatchup = { p -> watch(p) })
    }
}

/**
 * The channel groups, a page sliding in from the left (a swipe right on a channel): every group with how many
 * channels it has, the one shown ticked. A tap shows that group and slides the page away; so do swiping it back to
 * the left, the back arrow and Back.
 */
@Composable
internal fun GroupsPage(vm: GuideViewModel, slide: () -> Float, width: Float, onDrag: (Float) -> Unit, onSettle: (Boolean) -> Unit) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val hidden by vm.menuHiddenGroups.collectAsStateWithLifecycle()
    val groupId by vm.groupId.collectAsStateWithLifecycle()
    BackHandler { onSettle(false) }
    val rows = remember(groups, hidden) {
        val list = groups.filter { it.name !in hidden }.map { Triple(it.id as Int?, it.name, it.channelCount) }
        if (AppColors.palette.hideAllChannels) list else listOf(Triple<Int?, String, Int>(null, "All channels", groups.sumOf { it.channelCount })) + list
    }
    Column(
        Modifier.fillMaxSize()
            .offset { IntOffset(((slide() - 1f) * width).roundToInt(), 0) }
            .background(MobileColors.Background)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { onSettle(slide() > 0.7f) },
                    onDragCancel = { onSettle(slide() > 0.7f) },
                    onHorizontalDrag = { change, by -> change.consume(); onDrag(slide() + by / width) },
                )
            },
    ) {
        BrandBar("Channel groups", onBack = { onSettle(false) })
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows.size, key = { rows[it].first ?: Int.MIN_VALUE }) { i ->
                val (id, name, count) = rows[i]
                val on = id == groupId
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) AppColors.Accent.copy(alpha = 0.25f) else MobileColors.Card)
                    .clickable { vm.selectGroup(id); onSettle(false) }.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = if (on) AppColors.Accent else Color.White, fontSize = 17.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (count > 0) Text("$count", color = AppColors.TextSecondary, fontSize = 15.sp)
                }
            }
        }
    }
}
