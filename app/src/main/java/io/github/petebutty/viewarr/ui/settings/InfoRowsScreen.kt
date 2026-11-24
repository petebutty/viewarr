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
import androidx.compose.foundation.layout.fillMaxHeight
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
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.movingHint
import io.github.petebutty.viewarr.ui.theme.Tokens

/** Settings → Rows under the info bar (shown over everything by the app's root, like Send a report). */
object InfoRowsEditor {
    /** Open on this player's list (InfoRows.LIVE / InfoRows.FILM); null: closed. */
    var open by mutableStateOf<String?>(null)
}

internal data class InfoRowsLabel(override val label: String) : RemoteChoice

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
 * hold OK: options, Move / Show or Hide; Right picks it up too, as in Settings lists; Up / Down move it, OK, Back, Left
 * or Right put it down) and, beside them, how they'll stack under the bar.
 * (UI review, 2026-11-17: fits the 960 × 540 screen (it was 1060 wide and ~684 high, the bottom rows and hints
 * off screen); the shared pop-up window, pill look and options pop-up; hold OK no longer picks up straight away.)
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
    // Hold OK on a row: its options.
    var menuFor by remember { mutableStateOf<InfoRows.Kind?>(null) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val kinds = InfoRows.ordered(player)
    val active = InfoRows.active(player)
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { focus.requestFocus() } }
    LaunchedEffect(index, player) { if (index in kinds.indices) runCatching { list.animateScrollToItem((index - 2).coerceAtLeast(0)) } }

    // The row picked up, followed by itself: a held Up / Down outruns the redraw, and going by place moved whichever
    // row was there in the old order.
    var carried by remember { mutableStateOf<InfoRows.Kind?>(null) }
    LaunchedEffect(moving) { carried = if (moving) kinds.getOrNull(index) else null }
    fun move(dir: Int) {
        val k = carried ?: kinds.getOrNull(index) ?: return
        val ids = InfoRows.moved(player, k, dir) ?: return
        appearance.setBarOrder("rows_$player", ids)
        index = ids.filter { id -> InfoRows.kinds(player).any { it.id == id } }.indexOf(k.id).coerceAtLeast(0)
    }
    fun toggle(k: InfoRows.Kind) {
        val on = !InfoRows.shown(player, k)
        // Switched on: up to the end of the rows that are on; off: the top of the rest. The highlight follows it there.
        val ids = InfoRows.toggledOrder(player, k)
        appearance.setBarOrder("rows_$player", ids)
        index = kinds.count { it != k && InfoRows.shown(player, it) }
        InfoRows.setShown(player, k, on, appearance::setTitlePart, opts::set)
    }
    fun switchPlayer() { player = if (player == InfoRows.LIVE) InfoRows.FILM else InfoRows.LIVE; index = 0 }

    io.github.petebutty.viewarr.ui.common.ViewarrPopup(onClose = onClose) {
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
                    RemoteAction.Left, RemoteAction.Right -> if (down && repeat == 0) when {
                        onTabs && !moving -> switchPlayer()
                        // Carrying: Left or Right put it down. Right picks a row up (the Settings lists' shortcut).
                        moving -> moving = false
                        // (Only a row that's on: the ones off keep their place, so there's nothing to move.)
                        RemoteKeys.action(e) == RemoteAction.Right && kinds.getOrNull(index)?.let { InfoRows.shown(player, it) } == true -> moving = true
                    }
                    RemoteAction.Ok -> {
                        if (down && repeat == 0) okHeld = false
                        // Held: the row's options (carrying: just put it down).
                        if (down && repeat > 0 && !okHeld && !onTabs) {
                            okHeld = true
                            if (moving) moving = false else menuFor = kinds.getOrNull(index)
                        }
                        if (!down && !okHeld) {
                            if (onTabs) switchPlayer()
                            else if (moving) moving = false
                            else kinds.getOrNull(index)?.let(::toggle)
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
            Column(
                Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.92f)
                    .background(AppColors.MenuPanel.copy(alpha = 1f), RoundedCornerShape(Tokens.PopupRadius)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Rows under the info bar", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Down from the info bar's buttons: these rows, in this order. They scroll: the row you're on, the next peeking in below.",
                    fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // Which player's rows (the shared pill look, AppPill: highlighted = accent, chosen = the shared tint).
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(InfoRows.LIVE to "Live TV", InfoRows.FILM to "Films & shows").forEach { (id, name) ->
                        TabPill(name, selected = player == id, highlighted = onTabs && player == id)
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    RowsList(kinds, player, list, index, onTabs, moving, Modifier.weight(1f).fillMaxHeight())
                    RowsPreview(player, active, Modifier.weight(1f))
                }
                Text(
                    if (onTabs) "◀ ▶ live TV or films  ·  ▼ the rows  ·  Back: close"
                    else if (moving) movingHint()
                    else "OK: on / off  ·  Hold OK: options  ·  ▶: move  ·  ▲ at the top: live TV or films  ·  Back: close",
                    fontSize = 13.sp, color = if (moving) AppColors.Accent else AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    menuFor?.let { k ->
        val shown = InfoRows.shown(player, k)
        OptionsPopup(
            title = k.label,
            options = listOfNotNull(
                // (Only rows that are on have an order: the ones off sit below.)
                if (shown) PopupOption("Move") { index = kinds.indexOf(k).coerceAtLeast(0); moving = true } else null,
                PopupOption(if (shown) "Hide" else "Show") { toggle(k) },
            ),
            onClose = { menuFor = null },
        )
    }
}

/** A tab at the top: AppPill's look, drawn (this screen moves its own highlight, so nothing here takes focus). */
@Composable
private fun TabPill(text: String, selected: Boolean, highlighted: Boolean) {
    Box(
        Modifier.height(34.dp)
            .background(if (highlighted) AppColors.Accent else if (selected) Tokens.SelectedTint else Tokens.PillIdle, Tokens.PillShape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 14.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (highlighted) AppColors.OnAccent else AppColors.TextPrimary,
        )
    }
}

/** The rows: on / off and the order, the highlighted one (moved by InfoRowsScreen's keys) in the accent. */
@Composable
private fun RowsList(
    kinds: List<InfoRows.Kind>, player: String, list: androidx.compose.foundation.lazy.LazyListState,
    index: Int, onTabs: Boolean, moving: Boolean, modifier: Modifier,
) {
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(kinds, key = { _, k -> player + k.id }) { i, k ->
            val here = !onTabs && i == index
            val on = InfoRows.shown(player, k)
            Row(
                Modifier.fillMaxWidth()
                    .background(if (here) AppColors.Accent else AppColors.PanelAlt.copy(alpha = 0.6f), Tokens.RowShape)
                    .then(if (here && moving) Modifier.border(3.dp, Color.White, Tokens.RowShape) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (here && moving) "↕" else if (on) "✓" else "", fontSize = 16.sp, color = if (here) AppColors.OnAccent else AppColors.Accent, modifier = Modifier.width(26.dp), maxLines = 1, softWrap = false)
                Column(Modifier.weight(1f)) {
                    Text(
                        k.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (here) AppColors.OnAccent else if (on) AppColors.TextPrimary else AppColors.TextSecondary,
                    )
                    if (here) Text(k.about, fontSize = 12.sp, color = AppColors.OnAccent.copy(alpha = 0.85f), maxLines = 2)
                }
            }
        }
    }
}

/** What it'll look like: the bar, the row you'd land on, the next one peeking in. */
@Composable
private fun RowsPreview(player: String, active: List<InfoRows.Kind>, modifier: Modifier) {
    Column(modifier) {
        Text("Preview", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Black, RoundedCornerShape(12.dp)).clipToBounds().padding(12.dp)) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                // The info bar.
                Box(Modifier.fillMaxWidth().height(44.dp).background(AppColors.Panel.copy(alpha = 0.85f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text(if (player == InfoRows.LIVE) "Info bar  ·  live TV" else "Info bar  ·  film / episode", fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (active.isEmpty()) Text("No rows: Down does nothing.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                active.getOrNull(0)?.let { first ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(first.label, fontSize = 13.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (active.size > 1) Text("   1 of ${active.size}", fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, softWrap = false)
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(5) { c ->
                            Box(Modifier.size(72.dp, 40.dp).background(if (c == 0) AppColors.Accent else AppColors.PanelAlt, RoundedCornerShape(6.dp)))
                        }
                    }
                }
                // The next row: only its top edge shows.
                active.getOrNull(1)?.let { next ->
                    Box(Modifier.fillMaxWidth().height(28.dp).clipToBounds()) {
                        Column(Modifier.padding(top = 8.dp)) {
                            Text(next.label, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(5) { Box(Modifier.size(72.dp, 40.dp).background(AppColors.PanelAlt.copy(alpha = 0.6f), RoundedCornerShape(6.dp))) }
                            }
                        }
                    }
                }
            }
        }
        if (active.size > 2) Text(
            "Then: " + active.drop(2).joinToString("  ·  ") { it.label },
            fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
