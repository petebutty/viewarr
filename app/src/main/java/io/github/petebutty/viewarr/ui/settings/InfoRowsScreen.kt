package io.github.petebutty.viewarr.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.common.InfoRows
import io.github.petebutty.viewarr.ui.common.RemoteAction
import io.github.petebutty.viewarr.ui.common.RemoteKeys
import io.github.petebutty.viewarr.ui.theme.AppColors

/** Settings → Rows under the info bar (shown over everything by the app's root, like Send a report). */
object InfoRowsEditor {
    /** Open on this player's list (InfoRows.LIVE / InfoRows.FILM); null: closed. */
    var open by mutableStateOf<String?>(null)
}

internal data class InfoRowsLabel(override val label: String) : io.github.petebutty.viewarr.data.settings.RemoteChoice

/** The settings row that opens the editor, with how many rows each player has switched on. */
fun infoRowsItem(): SettingItem {
    val label = InfoRowsLabel("Live TV ${InfoRows.active(InfoRows.LIVE).size}  ·  Films ${InfoRows.active(InfoRows.FILM).size}")
    return SettingItem(
        "Rows under the info bar",
        "Down from the info bar's buttons: rows of channels or titles to jump to (recent channels, next episodes, " +
            "continue watching, favourites…). Choose which rows live TV and films & shows have, and their order, with a preview.",
        listOf(label), label, action = { InfoRowsEditor.open = InfoRows.LIVE }, onSelect = {},
    )
}

/**
 * Choose the rows under each player's info bar: Live TV or Films & shows along the top, the rows below (OK: on / off;
 * hold OK: pick it up, Up / Down to move, OK to put it down) and, beside them, how they'll stack under the bar.
 */
@Composable
fun InfoRowsScreen(start: String, onClose: () -> Unit) {
    val appearance: AppearanceViewModel = hiltViewModel()
    val opts: UiOptsViewModel = hiltViewModel()
    var player by remember { mutableStateOf(start) }
    var onTabs by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var moving by remember { mutableStateOf(false) }
    var okHeld by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val kinds = InfoRows.ordered(player)
    val active = InfoRows.active(player)
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { focus.requestFocus() } }
    LaunchedEffect(index, player) { if (index in kinds.indices) runCatching { list.animateScrollToItem((index - 2).coerceAtLeast(0)) } }

    fun move(dir: Int) {
        val k = kinds.getOrNull(index) ?: return
        val ids = InfoRows.moved(player, k, dir) ?: return
        appearance.setBarOrder("rows_$player", ids)
        index += dir
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                val repeat = e.nativeKeyEvent.repeatCount
                when (RemoteKeys.action(e)) {
                    RemoteAction.Up -> if (down) {
                        if (moving) move(-1) else if (index > 0) index-- else onTabs = true
                    }
                    RemoteAction.Down -> if (down) {
                        if (onTabs) onTabs = false else if (moving) move(1) else if (index < kinds.lastIndex) index++
                    }
                    RemoteAction.Left, RemoteAction.Right -> if (down && onTabs && !moving) {
                        player = if (player == InfoRows.LIVE) InfoRows.FILM else InfoRows.LIVE; index = 0
                    }
                    RemoteAction.Ok -> {
                        if (down && repeat == 0) okHeld = false
                        // Held: pick the row up (or put it down).
                        if (down && repeat > 0 && !okHeld && !onTabs) { okHeld = true; moving = !moving }
                        if (!down && !okHeld) {
                            if (onTabs) { player = if (player == InfoRows.LIVE) InfoRows.FILM else InfoRows.LIVE; index = 0 }
                            else if (moving) moving = false
                            else kinds.getOrNull(index)?.let { k ->
                                InfoRows.setShown(player, k, !InfoRows.shown(player, k), appearance::setTitlePart, opts::set)
                            }
                        }
                    }
                    // Back while moving a row: put it down (otherwise the window's own Back closes it).
                    RemoteAction.Back -> if (moving) { if (down) moving = false } else return@onPreviewKeyEvent false
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
            contentAlignment = Alignment.Center,
        ) {
            // The settings page behind darkened, and a solid panel (the glass let the page show through the text).
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
            Column(Modifier.width(1060.dp).background(AppColors.MenuPanel.copy(alpha = 1f), RoundedCornerShape(16.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Rows under the info bar", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(
                    "Down from the info bar's buttons: these rows, in this order. They scroll: the row you're on, the next peeking in below.",
                    fontSize = 14.sp, color = AppColors.TextSecondary,
                )
                // Which player's rows.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(InfoRows.LIVE to "Live TV", InfoRows.FILM to "Films & shows").forEach { (id, name) ->
                        val on = player == id
                        Box(
                            Modifier
                                .background(if (on) AppColors.Accent.copy(alpha = if (onTabs) 1f else 0.55f) else AppColors.PanelAlt, RoundedCornerShape(20.dp))
                                .border(2.dp, if (on && onTabs) Color.White else Color.Transparent, RoundedCornerShape(20.dp))
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                        ) { Text(name, fontSize = 16.sp, color = if (on) AppColors.OnAccent else AppColors.TextPrimary) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    // The rows: on / off and the order.
                    LazyColumn(Modifier.width(470.dp).height(470.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(kinds, key = { _, k -> player + k.id }) { i, k ->
                            val here = !onTabs && i == index
                            val on = InfoRows.shown(player, k)
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (here) AppColors.Accent else AppColors.PanelAlt.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (on) "✓" else "", fontSize = 16.sp, color = if (here) AppColors.OnAccent else AppColors.Accent, modifier = Modifier.width(26.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (here && moving) "▲  ${k.label}  ▼" else k.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (here) AppColors.OnAccent else if (on) AppColors.TextPrimary else AppColors.TextSecondary,
                                    )
                                    if (here) Text(k.about, fontSize = 12.sp, color = AppColors.OnAccent.copy(alpha = 0.85f), maxLines = 2)
                                }
                            }
                        }
                    }
                    // What it'll look like: the bar, the row you'd land on, the next one peeking in.
                    Column(Modifier.weight(1f)) {
                        Text("Preview", fontSize = 14.sp, color = AppColors.Accent)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(300.dp).background(Color.Black, RoundedCornerShape(12.dp)).clipToBounds().padding(14.dp)) {
                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                                // The info bar.
                                Box(Modifier.fillMaxWidth().height(54.dp).background(AppColors.Panel.copy(alpha = 0.85f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                                    Text(if (player == InfoRows.LIVE) "Info bar  ·  live TV" else "Info bar  ·  film / episode", fontSize = 13.sp, color = AppColors.TextPrimary)
                                }
                                if (active.isEmpty()) Text("No rows: Down does nothing.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                                active.getOrNull(0)?.let { first ->
                                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(first.label, fontSize = 13.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                                        if (active.size > 1) Text("   1 of ${active.size}", fontSize = 11.sp, color = AppColors.TextSecondary)
                                    }
                                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        repeat(5) { c ->
                                            Box(Modifier.size(92.dp, 52.dp).background(if (c == 0) AppColors.Accent else AppColors.PanelAlt, RoundedCornerShape(6.dp)))
                                        }
                                    }
                                }
                                // The next row: only its top edge shows.
                                active.getOrNull(1)?.let { next ->
                                    Box(Modifier.fillMaxWidth().height(30.dp).clipToBounds()) {
                                        Column(Modifier.padding(top = 8.dp)) {
                                            Text(next.label, fontSize = 12.sp, color = AppColors.TextSecondary)
                                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                repeat(5) { Box(Modifier.size(92.dp, 52.dp).background(AppColors.PanelAlt.copy(alpha = 0.6f), RoundedCornerShape(6.dp))) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (active.size > 2) Text(
                            "Then: " + active.drop(2).joinToString("  ·  ") { it.label },
                            fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                Text(
                    if (onTabs) "◀ ▶ switch between live TV and films  ·  ▼ the rows  ·  Back: close"
                    else if (moving) "▲ ▼ move it  ·  OK: put it down"
                    else "OK: on / off  ·  hold OK: move it  ·  ▲ at the top: switch player  ·  Back: close",
                    fontSize = 13.sp, color = AppColors.TextSecondary,
                )
            }
        }
    }
}
