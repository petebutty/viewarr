package io.github.petebutty.viewarr.ui.guide

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.PopupPanel
import io.github.petebutty.viewarr.ui.common.PopupTitle
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.abs

/** A guide offset as people say it: "+1 h", "−30 min", "+1 h 30 min", "None". */
fun shiftLabel(min: Int): String {
    if (min == 0) return "None"
    val sign = if (min > 0) "+" else "−"
    val a = abs(min); val h = a / 60; val m = a % 60
    return sign + listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 }?.let { "$it min" }).joinToString(" ")
}

/** Settings → Live TV → Guide time offset: −10 h to +10 h in half hours. */
class ShiftChoice(val min: Int) : RemoteChoice {
    override val label = shiftLabel(min)
    // (−10 to +10 hours, half an hour at a time: a slider in Settings. Peter, 2026-11-17.)
    companion object { val all = (-20..20).map { ShiftChoice(it * 30) } }
}

/**
 * Hold OK in the guide → Guide times: this channel's programmes moved earlier or later (its guide, so channels sharing
 * it move together), on top of the source's own offset. Each change slides its saved listings at once (nothing is fetched again).
 */
@Composable
fun GuideShiftDialog(channel: ChannelListItem, vm: GuideViewModel, onToast: (String) -> Unit, onClose: () -> Unit) {
    val start = remember(channel.id) { vm.channelShiftMin(channel) }
    var min by remember(channel.id) { mutableIntStateOf(start) }
    val source = remember { vm.sourceShiftMin() }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        // (Already applied as the slider moved: the guide slid with it, nothing to fetch.)
        if (min != start) onToast("EPG offset for ${channel.name}: ${shiftLabel(min)}")
        onClose()
    }
    // The shared pop-up container. Back closes it (and saves).
    PopupPanel(onClose = ::close, width = Tokens.PopupStandard) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
        PopupTitle(
            "EPG offset: ${channel.name}",
            "Move this channel's programmes later (+) or earlier (−) when its guide is hours out." +
                (if (source != 0) "  Whole source: ${shiftLabel(source)} (Settings → Live TV)." else ""),
        )
        // The slider: Left / Right half an hour at a time, −10 to +10 hours; OK: done. (Peter, 2026-11-17: it was four
        // +/− buttons.)
        // Each step slides this channel's listings in the guide behind straight away (and is saved).
        fun set(v: Int) { min = v.coerceIn(-600, 600); vm.setChannelShiftMin(channel, min) }
        ShiftSlider(min, ::set, Modifier.focusRequester(first), onOk = ::close)
        if (min != 0) MenuItem(text = "Reset this channel", selected = false, compact = true, onClick = { set(0) })
        MenuItem(text = "Done", selected = false, compact = true, onClick = ::close)
    }
}

/** −10 h … +10 h, filled from the middle (no offset) towards the side it's moved to; Left / Right: 30 minutes. */
@Composable
private fun ShiftSlider(min: Int, onChange: (Int) -> Unit, modifier: Modifier, onOk: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onOk,
        modifier = modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key == Key.DirectionLeft || e.key == Key.DirectionRight
            when (e.key) {
                Key.DirectionLeft -> { onChange(min - 30); true }
                Key.DirectionRight -> { onChange(min + 30); true }
                else -> false
            }
        },
        scale = io.github.petebutty.viewarr.ui.theme.NoScale,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(Tokens.RowShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("EPG offset", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (min == 0) "None" else shiftLabel(min), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                val c = LocalContentColor.current
                Box(Modifier.weight(1f).height(6.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))) {
                    val f = min / 600f
                    Row(Modifier.fillMaxSize()) {
                        // Left half: filled from its right end when moved earlier; right half: from its left when later.
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                            if (f < 0) Box(Modifier.fillMaxWidth(-f).fillMaxHeight().background(c, RoundedCornerShape(3.dp)))
                        }
                        Box(Modifier.width(2.dp).height(10.dp).background(c))
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            if (f > 0) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(c, RoundedCornerShape(3.dp)))
                        }
                    }
                }
                Text("›", fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text("−10 h  earlier", fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("later  +10 h", fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}
/**
 * Hold OK in the guide → Timeline: how much time the guide shows across the screen (Settings → Appearance → TV guide →
 * Timeline), picked from the guide itself. (Peter, 2026-11-17.)
 */
@Composable
fun GuideTimelineDialog(onClose: () -> Unit) {
    val vm: io.github.petebutty.viewarr.ui.settings.UiOptsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val name = io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_SPAN
    val labels = io.github.petebutty.viewarr.ui.theme.UiOpts.choices.getValue(name)
    val now = AppColors.palette.opt(name)
    // Shortest first (the setting's own order starts with the default, 2½ hours).
    val order = labels.indices.sortedBy { labels[it].replace("½", ".5").substringBefore(" ").toFloatOrNull() ?: 0f }
    io.github.petebutty.viewarr.ui.common.OptionsPopup(
        title = "Timeline",
        subtitle = "How much time the TV guide shows across the screen",
        options = order.map { i ->
            io.github.petebutty.viewarr.ui.common.PopupOption((if (i == now) "✓  " else "     ") + labels[i]) { vm.set(name, i) }
        },
        onClose = onClose,
    )
}
