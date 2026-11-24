package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.common.followingSetting
import io.github.petebutty.viewarr.ui.common.popupScroll
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import io.github.petebutty.viewarr.ui.theme.appBackground

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.stopAt
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.movingHint
import io.github.petebutty.viewarr.ui.common.reorderable
import io.github.petebutty.viewarr.ui.theme.NoScale
import io.github.petebutty.viewarr.ui.theme.Tokens

/** One setting: a toggle (two choices called On/Off) or a list of choices. */
class SettingItem(
    val title: String,
    val description: String,
    val choices: List<RemoteChoice>,
    val current: RemoteChoice,
    /** An action row (e.g. "Check now"): OK runs this, and [current]'s label shows its status. */
    val action: (() -> Unit)? = null,
    /** A slider: Left / Right step through [choices] on the row itself (no list opens). */
    val slider: Boolean = false,
    /**
     * Reorderable (e.g. the menu's libraries, followed teams): hold OK → Move (or Right, the Settings shortcut) picks the row up, Up / Down
     * move it (by -1 / +1), OK, Back, Left or Right put it down (ui/common/Reorder.kt).
     */
    val reorder: ((Int) -> Unit)? = null,
    /**
     * Shown but can't be changed: another setting decides it (what Low-memory mode switches off). The note says
     * why and how to unlock it; OK does nothing.
     */
    val locked: String? = null,
    val onSelect: (RemoteChoice) -> Unit,
) {
    val isToggle get() = action == null && choices.size == 2 && choices.map { it.label }.toSet() == setOf("On", "Off")
    /** Not at its default: the row says "Changed" (set where the row is built, when its default is known). */
    var changed: Boolean = false
    /** This row locked with [note] (null: as it is). */
    fun locked(note: String?): SettingItem =
        if (note == null) this else SettingItem(title, description, choices, current, action, slider, null, note, onSelect).also { it.changed = changed }
}

/** A choice that is only a label: an action row's status ("OK: check"), or a named mode. */
internal data class PlainChoice(override val label: String) : RemoteChoice

/** On / Off, as a settings row's two choices ([SettingItem.isToggle] draws them as a switch). */
internal enum class OnOff(override val label: String) : RemoteChoice { On("On"), Off("Off") }

/** An On / Off setting row: [set] gets the new state. [reorder]: as [SettingItem.reorder]. */
internal fun onOffItem(title: String, description: String, on: Boolean, reorder: ((Int) -> Unit)? = null, set: (Boolean) -> Unit) =
    SettingItem(title, description, OnOff.entries, if (on) OnOff.On else OnOff.Off, reorder = reorder) { c -> set(c == OnOff.On) }

/** Lock notes, the same words everywhere. */
object Locks {
    const val LOW_MEMORY = "Off in Low-memory mode. To use it, switch off Settings → Troubleshooting → Low-memory mode."
}

/** Settings pages over live TV in the top-left window: their list of sections starts below it (the menu sets it). */
object SettingsLayout {
    var belowPreview by androidx.compose.runtime.mutableStateOf(false)
}

/** One section of a settings page: its name, a line about it, and its settings. */
class SettingCategory(
    val name: String, val blurb: String, val items: List<SettingItem>,
    /** The finer settings: listed under "Customise" on the page, closed until opened (SettingsFold). */
    val more: Boolean = false,
)

/**
 * Settings pages start with a few big choices; the rest sits under "Customise", closed until opened (2026-11-20:
 * "the settings pages are very big lists of things that can drastically change the app's look and use"). Which pages
 * have it open, for this run of the app (by page title).
 */
object SettingsFold {
    val open = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
}

/**
 * Settings pages: Left from the right-hand side goes back to the entry selected on the left (it went to whichever was
 * nearest on screen). Only when nothing on the right used the Left itself.
 */
fun Modifier.leftReturnsTo(target: () -> FocusRequester?): Modifier = onKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) target()?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
    else false
}

/**
 * A left-hand column's entries (categories, tabs, a plugin list): Left goes back to the Settings list ([onBack]), once
 * per press. It did nothing there, as the page keeps the remote inside it.
 */
internal fun Modifier.leftGoesBack(onBack: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.key != Key.DirectionLeft) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onBack()
    true
}

/**
 * A key per row: its title, with "#2", "#3"… on repeats. Two news feeds can share a name (or a feed and a channel
 * group), and the list crashed on the second one. The first keeps its plain title, so focus lookups by title still work.
 */
internal fun uniqueRowKeys(titles: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return titles.map { t ->
        val n = (seen[t] ?: 0) + 1
        seen[t] = n
        if (n == 1) t else "$t#$n"
    }
}

/**
 * Full-screen settings: categories on the left, their settings on the right, each with a plain explanation.
 * OK flips a toggle, or opens the list of choices.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsPage(
    title: String,
    allCategories: List<SettingCategory>,
    onBack: () -> Unit,
    onReset: (() -> Unit)? = null,
    resetLabel: String = "Reset to defaults",
    /** Open on this section (Search settings: where the setting found lives) … */
    startSection: String? = null,
    /** … with this setting highlighted. */
    startItem: String? = null,
) {
    // "Customise": the finer sections, closed until opened (opened straight away when Search settings lands in one).
    val foldable = allCategories.any { it.more } && allCategories.any { !it.more }
    LaunchedEffect(Unit) { if (startSection != null && allCategories.any { it.more && it.name == startSection }) SettingsFold.open[title] = true }
    val unfolded = !foldable || SettingsFold.open[title] == true || (startSection != null && allCategories.any { it.more && it.name == startSection })
    val categories = if (unfolded) allCategories else allCategories.filter { !it.more }
    val changedBelow = if (foldable) allCategories.filter { it.more }.sumOf { c -> c.items.count { it.changed } } else 0
    val foldFocus = remember { FocusRequester() }
    // The open category, kept by name: a setting can add or hide categories (low-memory mode hides some), and an
    // index alone then pointed past the end (crash) or at the wrong one.
    var categoryName by remember { mutableStateOf<String?>(startSection?.takeIf { s -> categories.any { it.name == s } }) }
    // A category that's gone (a setting can hide one): the one now in its place.
    var lastCategory by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val category = categories.indexOfFirst { it.name == categoryName }.takeIf { it >= 0 } ?: lastCategory.coerceIn(0, maxOf(0, categories.lastIndex))
    androidx.compose.runtime.SideEffect { lastCategory = category }
    // The settings list starts at the top for each category (it kept the last one's scroll).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pageScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(category) { runCatching { listState.scrollToItem(0) } }
    var choosing by remember { mutableStateOf<SettingItem?>(null) }
    // That row's key and place (the list can move or drop it once a choice is made).
    var choosingKey by remember { mutableStateOf<String?>(null) }
    var choosingAt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // A reorderable row picked up with Right (its title), moved with Up / Down.
    var moving by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(category) { moving = null }
    // One per category, remade when categories come and go (keyed by their names).
    val catFocus = remember(categories.map { it.name }) { categories.map { FocusRequester() } }
    // After a choice list closes, focus goes back to the setting it was opened from (it used to vanish).
    // Keyed by the row's key (its title, "#2" on a repeat: uniqueRowKeys), the same key everywhere below.
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    // A row that changes place when it's changed (switched on, or moved): focus follows it there
    // (it stayed on the old spot and vanished).
    var refocus by remember { mutableStateOf<String?>(null) }
    // Where that row was: if it's gone (taken out of a list), the row now in its place gets focus.
    var refocusAt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val currentItems = categories.getOrNull(category)?.items.orEmpty()
    val currentKeys = remember(currentItems) { uniqueRowKeys(currentItems.map { it.title }) }
    LaunchedEffect(refocus, currentKeys) {
        val wanted = refocus ?: return@LaunchedEffect
        val found = currentKeys.indexOf(wanted)
        val idx = if (found >= 0) found else refocusAt.coerceAtMost(currentKeys.lastIndex)
        if (idx < 0) return@LaunchedEffect
        val t = currentKeys[idx]
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == idx }
        if (!visible) runCatching { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }
        awaitFrame()
        runCatching { rowFocus[t]?.requestFocus() }
        refocus = null
    }
    // A row moved (Right, then Up / Down): the list holds still and the row moves in it. (The list keeps its place by
    // the row at the top; when that was the row being moved, the whole list scrolled with it, so the highlight always
    // sat at the top and it was hard to see where it had got to. 2026-11-15.) It scrolls only when the moved
    // row would go off screen, keeping a row's margin.
    var moveAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var movedTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentItems.map { it.title }) {
        val (first, offset) = moveAnchor ?: return@LaunchedEffect
        moveAnchor = null
        runCatching { listState.scrollToItem(first, offset) }
        awaitFrame()
        val idx = currentItems.indexOfFirst { it.title == movedTitle }
        if (idx < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == idx }
        if (row == null) { runCatching { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }; return@LaunchedEffect }
        val margin = (row.size * 1.2f)
        val top = row.offset - info.viewportStartOffset
        val bottom = info.viewportEndOffset - (row.offset + row.size)
        when {
            top < margin -> runCatching { listState.animateScrollBy(top - margin) }
            bottom < margin -> runCatching { listState.animateScrollBy(margin - bottom) }
        }
    }
    LaunchedEffect(choosing?.title) {
        val open = choosing ?: return@LaunchedEffect
        val idx = currentItems.indexOfFirst { it.title == open.title }
        if (idx < 0) return@LaunchedEffect
        awaitFrame(); awaitFrame(); awaitFrame()
        val info = listState.layoutInfo
        val it = info.visibleItemsInfo.firstOrNull { v -> v.index == idx } ?: run { runCatching { listState.animateScrollToItem(idx) }; return@LaunchedEffect }
        val over = it.offset + it.size - (info.viewportEndOffset - 24)
        if (over > 0) runCatching { listState.animateScrollBy(over.toFloat().coerceAtMost((it.offset - info.viewportStartOffset).toFloat().coerceAtLeast(0f))) }
    }
    // Closing the choices: the same path as a toggle (a picked choice can move or drop the row: focus then landed
    // on the categories), with a frame for the rows to be enabled again.
    fun closeChoices() {
        choosingKey?.let { refocusAt = choosingAt; refocus = it }
        choosing = null
    }
    // A reorderable row's hold-OK options (its title).
    var menuFor by remember { mutableStateOf<String?>(null) }
    // (While a row is carried, Back puts it down: Modifier.reorderable takes the press; this never leaves the page then.)
    BackHandler { if (moving != null) moving = null else if (choosing != null) closeChoices() else onBack() }
    LaunchedEffect(Unit) {
        awaitFrame()
        // From Search settings: straight onto the setting found (the refocus effect scrolls to it).
        if (startItem != null && currentItems.any { it.title == startItem }) refocus = startItem
        else runCatching { catFocus[category].requestFocus() }
    }
    // Safety net: if focus ever drops out of the page (a list redrawing, a pop-up closing), it goes back to the last
    // row used, else the open category, instead of leaving nothing highlighted.
    var pageHasFocus by remember { mutableStateOf(true) }
    var lastRow by remember { mutableStateOf<String?>(null) }
    // (Not while a pop-up has the screen: then when it closes.)
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(pageHasFocus, choosing, windowFocused) {
        if (pageHasFocus || choosing != null || !windowFocused) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        val row = lastRow?.takeIf { it in currentKeys }?.let { rowFocus[it] }
        if (row == null || runCatching { row.requestFocus() }.isFailure) runCatching { catFocus.getOrNull(category)?.requestFocus() }
    }
    Box(Modifier.fillMaxSize().appBackground(readable = true).onFocusChanged { pageHasFocus = it.hasFocus }.trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = Tokens.Gutter, vertical = 40.dp)) {
            // (Below the live TV window when one is up: it sits where this column starts.)
            Column(Modifier.width(300.dp).fillMaxHeight().padding(top = if (SettingsLayout.belowPreview) 170.dp else 0.dp)) {
                Text("Settings", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 20.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Scrolls: Remote buttons has more sections (every button) than fit, and Ch − was cut off the bottom.
                Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                // The "Customise" heading: before the first of the finer sections (or last, while they're closed).
                val fold: @Composable () -> Unit = {
                    MenuItem(
                        text = if (unfolded) "▾  Customise" else "▸  Customise",
                        trailing = if (changedBelow > 0) "$changedBelow changed" else null,
                        selected = false,
                        modifier = Modifier.padding(top = 8.dp).focusRequester(foldFocus).focusProperties { canFocus = choosing == null }.leftGoesBack(onBack),
                        onClick = { SettingsFold.open[title] = !unfolded },
                    )
                }
                categories.forEachIndexed { i, c ->
                    if (foldable && c.more && (i == 0 || !categories[i - 1].more)) fold()
                    MenuItem(
                        text = c.name,
                        selected = i == category,
                        onClick = { categoryName = categories[i].name },
                        modifier = Modifier
                            .focusRequester(catFocus[i])
                            .focusProperties { canFocus = choosing == null }
                            .onFocusChanged { if (it.isFocused) categoryName = categories[i].name }
                            // Right: the category's first setting (not the one nearest on screen). Left: back to the
                            // Settings list (it did nothing: the page keeps the remote inside it).
                            .leftGoesBack(onBack)
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                    val first = categories[i].items.firstOrNull()?.title ?: return@onPreviewKeyEvent false
                                    pageScope.launch {
                                        runCatching { listState.scrollToItem(0) }
                                        awaitFrame()
                                        // (The first row off screen: bring it on, or focus had nowhere to go.)
                                        if (listState.layoutInfo.visibleItemsInfo.none { it.index == 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) {
                                            runCatching { listState.scrollToItem(0) }
                                            awaitFrame()
                                        }
                                        runCatching { rowFocus[first]?.requestFocus() }
                                    }
                                    true
                                } else false
                            },
                    )
                }
                if (foldable && !unfolded) fold()
                }
                onReset?.let {
                    var confirm by remember { mutableStateOf(false) }
                    MenuItem(
                        text = if (confirm) "Press OK again to reset" else resetLabel,
                        selected = false,
                        modifier = Modifier.focusProperties { canFocus = choosing == null }.onFocusChanged { if (!it.isFocused) confirm = false }.leftGoesBack(onBack),
                        onClick = { if (confirm) { it(); confirm = false } else confirm = true },
                    )
                }
            }
            Spacer(Modifier.width(40.dp))
            val c = categories[category]
            Column(Modifier.weight(1f).fillMaxHeight().leftReturnsTo { catFocus[category] }) {
                // While a row is carried: the one "moving" hint in place of the blurb.
                Text(if (moving != null) movingHint() else c.blurb, fontSize = 14.sp, color = if (moving != null) AppColors.Accent else AppColors.TextSecondary, modifier = Modifier.padding(top = 26.dp, bottom = 14.dp, start = 4.dp))
                // Android TV's own scrolling keeps the focused row about a third of the way down. Here the list only
                // moves when the row would go off screen, keeping a row's margin; the first row brings the list right
                // back to the top.
                val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 70.dp.toPx() }
                val gentle = remember(margin) {
                    object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                            val m = margin.coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
                            return when {
                                offset < m -> offset - m
                                offset + size > containerSize - m -> offset + size - (containerSize - m)
                                else -> 0f
                            }
                        }
                    }
                }
                val following = remember(gentle) { gentle.followingSetting() }
                val keys = currentKeys
                // A dropdown open: the page holds still while the highlight moves inside it (it followed the highlight
                // and scrolled the whole page); the dropdown scrolls itself (InlineChoices). Opening one brings it
                // fully on screen once, below.
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides (if (choosing != null) HoldStill else following)) {
                LazyColumn(Modifier.stopAt(androidx.compose.ui.focus.FocusDirection.Down), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    itemsIndexed(c.items, key = { i, _ -> c.name + "/" + keys[i] }) { index, item ->
                        val rowFocusReq = rowFocus.getOrPut(keys[index]) { FocusRequester() }
                        // The row, and its choices opening right under it (a dropdown, as on Windows) when picked.
                        androidx.compose.foundation.layout.Column {
                        SettingRow(
                            item, enabled = choosing == null, focus = rowFocusReq,
                            moving = moving == item.title,
                            onMove = { picked -> moving = if (picked) item.title else null },
                            onLongClick = if (item.reorder != null && item.locked == null) ({ menuFor = item.title }) else null,
                            onMoved = {
                                moveAnchor = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                                movedTitle = item.title
                                // (A move that did nothing, at the top or bottom, leaves no old place to jump back to later.)
                                pageScope.launch { kotlinx.coroutines.delay(1_500); moveAnchor = null }
                                refocus = keys[index]
                            },
                            onFocused = { lastRow = keys[index] },
                        ) {
                            if (moving == item.title) moving = null
                            else if (item.locked != null) Unit
                            // A slider: OK steps it up one (round to the start after the last), so OK does something.
                            else if (item.slider) item.choices.indexOf(item.current).coerceAtLeast(0).let { i -> item.onSelect(item.choices[(i + 1) % item.choices.size]) }
                            else if (item.action != null) item.action.invoke()
                            else if (item.isToggle) {
                                item.onSelect(item.choices.first { it != item.current })
                                // Reorderable lists move a row when it's turned on / off: focus follows it there.
                                // The list redraws after a switch (rows can move, or the redraw drops focus):
                                // focus goes back to the switched row, or the one now in its place.
                                refocusAt = index; refocus = keys[index]
                            }
                            else { choosingKey = keys[index]; choosingAt = index; choosing = item }
                        }
                        if (choosing?.title == item.title) InlineChoices(item, onDone = ::closeChoices)
                        }
                    }
                }
                }
            }
        }
    }
    // Hold OK on a reorderable row: the standard options (Move picks it up; Cancel).
    menuFor?.let { t ->
        OptionsPopup(title = t, options = listOf(PopupOption("Move") { moving = t }), onClose = { menuFor = null })
    }
}

@Composable
private fun SettingRow(
    item: SettingItem, enabled: Boolean, focus: FocusRequester,
    moving: Boolean = false, onMove: (Boolean) -> Unit = {}, onMoved: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).focusProperties { canFocus = enabled }.onFocusChanged { rowFocused = it.isFocused; if (it.isFocused) onFocused() }
            // The shared way to move a row (UI review Tier 4, 2026-11-17): picked up by hold OK → Move or Right
            // (the Settings lists' shortcut); ▲ ▼ move it; OK, Back, Left or Right put it down.
            // (Not a locked row: another setting decides it, so it can't be moved either.)
            .then(if (item.reorder == null || item.locked != null) Modifier else Modifier.reorderable(
                carried = moving,
                onMove = { dir -> item.reorder.invoke(dir); onMoved() },
                onDrop = { onMove(false) },
                rightToggles = true,
                onPickUp = { onMove(true) },
            ))
            .then(if (!item.slider || item.locked != null) Modifier else Modifier.onPreviewKeyEvent { e ->
                // Slider: Left / Right change the value; Left at the lowest goes back to the categories as usual.
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val i = item.choices.indexOf(item.current).coerceAtLeast(0)
                when (e.key) {
                    Key.DirectionLeft -> if (i > 0) { item.onSelect(item.choices[i - 1]); true } else false
                    Key.DirectionRight -> { if (i < item.choices.lastIndex) item.onSelect(item.choices[i + 1]); true }
                    else -> false
                }
            }),
        scale = NoScale,
        shape = ClickableSurfaceDefaults.shape(Tokens.RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (moving) Text("↕ ", fontSize = 18.sp)
            Column(Modifier.weight(1f).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = if (item.locked != null) 0.75f else 1f }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (item.locked != null) "🔒 " else "") + item.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    // Not at its default (so it's clear what's been altered, here and when reading a report).
                    if (item.changed && item.locked == null) Text("Changed", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                        color = if (rowFocused) AppColors.OnAccent else AppColors.Accent,
                        modifier = Modifier.padding(start = 8.dp).border(1.dp, (if (rowFocused) AppColors.OnAccent else AppColors.Accent).copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
                // (The whole description once the row is highlighted: three lines cut most of them off. UI review, 2026-11-18.)
                if (item.description.isNotEmpty()) Text(item.description, fontSize = 12.sp, maxLines = if (rowFocused) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                item.locked?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            }
            Spacer(Modifier.width(20.dp))
            if (item.locked != null) Text("Locked", fontSize = 14.sp, modifier = Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = 0.75f }, maxLines = 1, softWrap = false)
            else if (item.slider) {
                val i = item.choices.indexOf(item.current).coerceAtLeast(0)
                val fraction = if (item.choices.size > 1) i / item.choices.lastIndex.toFloat() else 0f
                Row(Modifier.width(260.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                    Box(Modifier.weight(1f).height(6.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(LocalContentColor.current, RoundedCornerShape(3.dp)))
                    }
                    Text("›", fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
                    Text(item.current.label, fontSize = 14.sp, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 64.dp).padding(start = 10.dp))
                }
            }
            else if (item.isToggle) SettingSwitch(item.current.label == "On")
            else Text(item.current.label, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.widthIn(min = 120.dp, max = 200.dp))
        }
    }
}

/** The green / grey on-off switch at the end of a setting row (also Home page & menu's rows). */
@Composable
internal fun SettingSwitch(on: Boolean) {
    Box(
        Modifier.size(width = 48.dp, height = 26.dp)
            // The highlight colour when on (it was the only green in the app: UI review, 2026-11-18), grey when off.
            .background(if (on) AppColors.Accent else Color(0xFF6B6F7A), RoundedCornerShape(13.dp))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(if (on) AppColors.OnAccent else Color.White, CircleShape))
    }
}

/**
 * A setting's choices as a dropdown under its row (in the settings list itself, not a pop-up over it): opens on the
 * current choice, scrolls when long, and keeps the highlight inside (every other row is switched off while it's open).
 * OK picks; Back closes it unchanged (SettingsPage's Back). Nothing sits on top of the page, so focus can't be left
 * behind it or blocked by the page's traps (the pop-up it replaced had both problems).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InlineChoices(item: SettingItem, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    // Focus starts on the current choice; when it isn't one of them (e.g. a "Custom" theme), on the first.
    val start = item.choices.indexOf(item.current).coerceAtLeast(0)
    LaunchedEffect(Unit) { repeat(3) { awaitFrame(); runCatching { focus.requestFocus() } } }
    Column(
        Modifier
            .padding(start = 28.dp, end = 4.dp, top = 4.dp, bottom = 6.dp)
            .fillMaxWidth()
            .background(AppColors.Panel, RoundedCornerShape(12.dp))
            .border(1.dp, AppColors.Accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(6.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides JustShow) {
        Column(Modifier.popupScroll(0.5f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item.choices.forEachIndexed { i, choice ->
                MenuItem(
                    text = (if (choice == item.current) "✓  " else "     ") + choice.label,
                    selected = choice == item.current,
                    modifier = if (i == start) Modifier.focusRequester(focus) else Modifier,
                    onClick = { item.onSelect(choice); onDone() },
                )
            }
        }
        }
    }
}

/** Scrolling that never moves (the settings list while a dropdown is open). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object HoldStill : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/** Scrolling just far enough to show the highlighted item (a dropdown's list). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object JustShow : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < 0f -> offset
        offset + size > containerSize -> offset + size - containerSize
        else -> 0f
    }
}
