package io.github.petebutty.viewarr.ui.player

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.common.BarLayout
import io.github.petebutty.viewarr.ui.common.Carry
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.PopupPanel
import io.github.petebutty.viewarr.ui.common.PopupTitle
import io.github.petebutty.viewarr.ui.common.ReorderAxis
import io.github.petebutty.viewarr.ui.common.moveOption
import io.github.petebutty.viewarr.ui.common.movingHint
import io.github.petebutty.viewarr.ui.common.rememberCarry
import io.github.petebutty.viewarr.ui.common.reorderable
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * Whether a pop-up of the info bar is open (the Info bar buttons editor, a card's options, Choose
 * rows): the info bar doesn't close on its own meanwhile. Counted, as one can open over another (the editor's own
 * hold-OK options over the editor). (A plain flag before: 2026-11-17 UI review.)
 */
internal object BarEditorOpen {
    private var count by mutableIntStateOf(0)
    val value: Boolean get() = count > 0
    fun hold() { count++ }
    fun release() { count = (count - 1).coerceAtLeast(0) }
}

/** Keeps the info bar up while this is shown (see [BarEditorOpen]). */
@Composable
internal fun HoldBarOpen() {
    DisposableEffect(Unit) { BarEditorOpen.hold(); onDispose { BarEditorOpen.release() } }
}

/** An info bar button for the editor: its id, icon and name. */
internal data class BarItem(val id: String, val icon: ImageVector, val label: String)

/**
 * Info bar buttons (hold OK on a button of live TV's bar or the film bar; the same list as Settings → Appearance →
 * Info bar buttons): every button of that [bar] by icon and name, on the bar (ticked) or not. OK puts one on or takes
 * it off; the bar's main player buttons (BarLayout.liveFixed / filmFixed) are always on, and can only be moved. Hold OK:
 * Move / Take off; while moving, ▲ ▼ place it and OK, Back or Left put it down (Right picks up and puts down too, as in
 * Settings lists).
 * (It replaced editing on the bar itself: ~28 look-alike round buttons in one row. A "⋯ More" menu tried after it was
 * dropped: 2026-11-17. The film bar was still edited in place until the UI review the same day: now both bars
 * use this list.)
 */
@Composable
internal fun BarButtonsEditor(
    items: List<BarItem>,
    setPart: (String, Boolean) -> Unit,
    setOrder: (List<String>) -> Unit,
    onActivity: () -> Unit,
    onClose: () -> Unit,
    bar: BarLayout.Bar = BarLayout.Bar.Live,
) {
    HoldBarOpen()
    val carry = rememberCarry()
    var menuFor by remember { mutableStateOf<BarItem?>(null) }
    // The row to put the highlight back on after it changes list (its row is a new one there).
    var focusOn by remember { mutableStateOf(items.firstOrNull()?.id) }
    val focus = remember { HashMap<String, FocusRequester>() }
    val ordered = BarLayout.arrange(items, { it.id }, editing = true, bar = bar)
    val onBar = ordered.filter { BarLayout.shown(it.id, bar) }
    val off = ordered.filter { !BarLayout.shown(it.id, bar) }
    LaunchedEffect(focusOn, onBar.size, off.size) {
        val id = focusOn ?: return@LaunchedEffect
        repeat(8) { n ->
            if (n > 0) delay(40)
            if (runCatching { focus[id]?.requestFocus() }.isSuccess && focus[id] != null) return@LaunchedEffect
        }
    }
    // (setShown, not a direct mark: a button locked on the other bar only still switches here.)
    fun toggle(b: BarItem, nowOn: Boolean) { onActivity(); BarLayout.setShown(b.id, !nowOn, setPart); focusOn = b.id }
    fun move(b: BarItem, dir: Int) { onActivity(); BarLayout.moved(onBar.map { it.id }, b.id, dir, bar)?.let(setOrder) }

    PopupPanel(
        onClose = { if (carry.active) carry.drop() else onClose() },
        width = Tokens.PopupWide,
        modifier = Modifier.onPreviewKeyEvent { onActivity(); false },
    ) {
        PopupTitle(
            "Info bar buttons",
            if (carry.active) movingHint(ReorderAxis.Vertical)
            else (if (bar == BarLayout.Bar.Live) "Live TV" else "Films & shows") + "  ·  OK: on or off the bar  ·  Hold OK: options",
        )
        Section("ON THE BAR", onBar.isEmpty())
        onBar.forEach { b ->
            key(b.id) {
                EditorRow(b, true, bar, carry, focus.getOrPut(b.id) { FocusRequester() }, canMove = onBar.size > 1,
                    onToggle = { toggle(b, true) }, onOptions = { menuFor = b }, onMove = { move(b, it) })
            }
        }
        Section("NOT ON THE BAR", off.isEmpty())
        off.forEach { b ->
            key(b.id) {
                EditorRow(b, false, bar, carry, focus.getOrPut(b.id) { FocusRequester() }, canMove = false,
                    onToggle = { toggle(b, false) }, onOptions = { menuFor = b }, onMove = {})
            }
        }
        MenuItem(text = "Done", selected = false, onClick = onClose, modifier = Modifier.padding(top = 16.dp))
    }
    menuFor?.let { b ->
        val isOn = onBar.any { it.id == b.id }
        val locked = BarLayout.locked(b.id, bar)
        val options = listOfNotNull(
            if (isOn && onBar.size > 1) carry.moveOption(b.id) else null,
            if (locked) null else PopupOption(if (isOn) "Take off the bar" else "Put on the bar") { toggle(b, isOn) },
        )
        OptionsPopup(title = b.label, options = options, onClose = { menuFor = null; onActivity() },
            subtitle = if (locked) "Always on the bar" else null)
    }
}

/** A section heading in the editor ("ON THE BAR"), with "None" under it when it's empty. */
@Composable
private fun Section(title: String, empty: Boolean) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = AppColors.Accent,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (empty) Text("None", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** One button's row: OK on / off, hold OK its options, and moved with ▲ ▼ while picked up ([carry]). */
@Composable
private fun EditorRow(
    b: BarItem, onBar: Boolean, bar: BarLayout.Bar, carry: Carry, fr: FocusRequester, canMove: Boolean,
    onToggle: () -> Unit, onOptions: () -> Unit, onMove: (Int) -> Unit,
) {
    val carried = carry.isCarried(b.id)
    val fixed = BarLayout.locked(b.id, bar)
    MenuItem(
        text = if (carried) "↕  ${b.label}" else b.label,
        selected = carried,
        dim = !onBar,
        trailing = when {
            carried -> "▲ ▼"
            fixed -> "Always on the bar"
            onBar -> "✓ On the bar"
            else -> "OK: put on the bar"
        },
        leading = { Icon(b.icon, null, tint = AppColors.TextPrimary, modifier = Modifier.size(22.dp)) },
        modifier = Modifier.focusRequester(fr).reorderable(
            carried = carried, onMove = onMove, onDrop = carry::drop, axis = ReorderAxis.Vertical,
            rightToggles = canMove, onPickUp = { carry.pickUp(b.id) },
        ),
        onLongClick = if (carry.active) null else onOptions,
        onClick = { if (!carry.active && !fixed) onToggle() },
    )
}
