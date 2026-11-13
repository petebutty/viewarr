package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.common.followingSetting
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
    /** Reorderable (e.g. Live TV shortcuts): Right picks the row up, Up / Down move it (by -1 / +1), Right or OK puts it down. */
    val reorder: ((Int) -> Unit)? = null,
    /**
     * Shown but can't be changed: another setting decides it (Low-memory mode, the Live TV hub style). The note says
     * why and how to unlock it; OK does nothing.
     */
    val locked: String? = null,
    val onSelect: (RemoteChoice) -> Unit,
) {
    val isToggle get() = action == null && choices.size == 2 && choices.map { it.label }.toSet() == setOf("On", "Off")
    /** This row locked with [note] (null: as it is). */
    fun locked(note: String?): SettingItem =
        if (note == null) this else SettingItem(title, description, choices, current, action, slider, null, note, onSelect)
}

/** Lock notes, the same words everywhere. */
object Locks {
    const val LOW_MEMORY = "Off in Low-memory mode. To use it, switch off Settings → Troubleshooting → Low-memory mode."
    const val LIVE_HUB = "Set by the Live TV hub style. To choose it yourself, set Settings → Menu & navigation → Start → Style to Classic."
    const val NEEDS_HUB = "Part of the Live TV hub. To use it, set Settings → Menu & navigation → Start → Style to Live TV hub."
}

/** [header]: drawn above the items (e.g. Remote & navigation's remote picture); not focusable. */
/** Settings pages over live TV in the top-left window: their list of sections starts below it (the menu sets it). */
object SettingsLayout {
    var belowPreview by androidx.compose.runtime.mutableStateOf(false)
}

class SettingCategory(val name: String, val blurb: String, val items: List<SettingItem>, val header: (@Composable () -> Unit)? = null)

/**
 * Settings pages: Left from the right-hand side goes back to the entry selected on the left (it went to whichever was
 * nearest on screen). Only when nothing on the right used the Left itself.
 */
fun Modifier.leftReturnsTo(target: () -> FocusRequester?): Modifier = onKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) target()?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
    else false
}

/**
 * Full-screen settings: categories on the left, their settings on the right, each with a plain explanation.
 * OK flips a toggle, or opens the list of choices.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsPage(
    title: String,
    categories: List<SettingCategory>,
    onBack: () -> Unit,
    onReset: (() -> Unit)? = null,
    resetLabel: String = "Reset to defaults",
    /** Open on this section (Search settings: where the setting found lives) … */
    startSection: String? = null,
    /** … with this setting highlighted. */
    startItem: String? = null,
) {
    // The open category, kept by name: a setting can add or hide categories (low-memory mode hides some), and an
    // index alone then pointed past the end (crash) or at the wrong one.
    var categoryName by remember { mutableStateOf<String?>(startSection?.takeIf { s -> categories.any { it.name == s } }) }
    // A category that's gone (e.g. "Live TV hub" once the hub is turned on from it): the one now in its place.
    var lastCategory by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val category = categories.indexOfFirst { it.name == categoryName }.takeIf { it >= 0 } ?: lastCategory.coerceIn(0, maxOf(0, categories.lastIndex))
    androidx.compose.runtime.SideEffect { lastCategory = category }
    // The settings list starts at the top for each category (it kept the last one's scroll).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pageScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(category) { runCatching { listState.scrollToItem(0) } }
    var choosing by remember { mutableStateOf<SettingItem?>(null) }
    // A reorderable row picked up with Right (its title), moved with Up / Down.
    var moving by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(category) { moving = null }
    // One per category, remade when categories come and go (keyed by their names).
    val catFocus = remember(categories.map { it.name }) { categories.map { FocusRequester() } }
    // After a choice list closes, focus goes back to the setting it was opened from (it used to vanish).
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    // A row that changes place when it's changed (a Live TV shortcut turned on, or moved): focus follows it there
    // (it stayed on the old spot and vanished).
    var refocus by remember { mutableStateOf<String?>(null) }
    // Where that row was: if it's gone (taken out of a list), the row now in its place gets focus.
    var refocusAt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val currentItems = categories.getOrNull(category)?.items.orEmpty()
    val hasHeader = categories.getOrNull(category)?.header != null
    LaunchedEffect(refocus, currentItems.map { it.title }) {
        val wanted = refocus ?: return@LaunchedEffect
        val found = currentItems.indexOfFirst { it.title == wanted }
        val idx = if (found >= 0) found else refocusAt.coerceAtMost(currentItems.lastIndex)
        if (idx < 0) return@LaunchedEffect
        val t = currentItems[idx].title
        val listIdx = idx + if (hasHeader) 1 else 0
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == listIdx }
        if (!visible) runCatching { listState.scrollToItem((listIdx - 2).coerceAtLeast(0)) }
        awaitFrame()
        runCatching { rowFocus[t]?.requestFocus() }
        refocus = null
    }
    var returnTo by remember { mutableStateOf<String?>(null) }
    fun closeChoices() {
        returnTo = choosing?.title
        choosing = null
    }
    LaunchedEffect(returnTo) {
        val key = returnTo ?: return@LaunchedEffect
        awaitFrame(); awaitFrame()
        runCatching { rowFocus[key]?.requestFocus() }
        returnTo = null
    }
    BackHandler { if (choosing != null) closeChoices() else onBack() }
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
    // (Not while a pop-up has the screen, e.g. the Live TV hub guide: then when it closes.)
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(pageHasFocus, choosing, windowFocused) {
        if (pageHasFocus || choosing != null || !windowFocused) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        val row = lastRow?.takeIf { t -> currentItems.any { it.title == t } }?.let { rowFocus[it] }
        if (row == null || runCatching { row.requestFocus() }.isFailure) runCatching { catFocus.getOrNull(category)?.requestFocus() }
    }
    Box(Modifier.fillMaxSize().appBackground(readable = true).onFocusChanged { pageHasFocus = it.hasFocus }.trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            // (Below the live TV window when one is up: it sits where this column starts.)
            Column(Modifier.width(300.dp).fillMaxHeight().padding(top = if (SettingsLayout.belowPreview) 170.dp else 0.dp)) {
                Text("Settings", fontSize = 14.sp, color = AppColors.Accent)
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 20.dp))
                // Scrolls: Remote buttons has more sections (every button) than fit, and Ch − was cut off the bottom.
                Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                categories.forEachIndexed { i, c ->
                    MenuItem(
                        text = c.name,
                        selected = i == category,
                        onClick = { categoryName = categories[i].name },
                        modifier = Modifier
                            .focusRequester(catFocus[i])
                            .focusProperties { canFocus = choosing == null }
                            .onFocusChanged { if (it.isFocused) categoryName = categories[i].name }
                            // Right: the category's first setting (not the one nearest on screen).
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                    val first = categories[i].items.firstOrNull()?.title ?: return@onPreviewKeyEvent false
                                    pageScope.launch {
                                        runCatching { listState.scrollToItem(0) }
                                        awaitFrame()
                                        // A section's picture (Remote & navigation) can be taller than the screen on
                                        // some TVs: the first row was then off screen and focus had nowhere to go.
                                        val firstIdx = if (categories[i].header != null) 1 else 0
                                        if (listState.layoutInfo.visibleItemsInfo.none { it.index == firstIdx && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) {
                                            runCatching { listState.scrollToItem(firstIdx) }
                                            awaitFrame()
                                        }
                                        runCatching { rowFocus[first]?.requestFocus() }
                                    }
                                    true
                                } else false
                            },
                    )
                }
                }
                onReset?.let {
                    var confirm by remember { mutableStateOf(false) }
                    MenuItem(
                        text = if (confirm) "Press OK again to reset" else resetLabel,
                        selected = false,
                        modifier = Modifier.focusProperties { canFocus = choosing == null }.onFocusChanged { if (!it.isFocused) confirm = false },
                        onClick = { if (confirm) { it(); confirm = false } else confirm = true },
                    )
                }
            }
            Spacer(Modifier.width(40.dp))
            val c = categories[category]
            Column(Modifier.weight(1f).fillMaxHeight().leftReturnsTo { catFocus[category] }) {
                Text(c.blurb, fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 26.dp, bottom = 14.dp, start = 4.dp))
                // Android TV's own scrolling keeps the focused row about a third of the way down, which pushed a
                // section's picture (Remote & navigation) half off the top. Here the list only moves when the row
                // would go off screen, keeping a row's margin; the first row brings the list right back to the top.
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
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(gentle) { gentle.followingSetting() }) {
                LazyColumn(Modifier.stopAt(androidx.compose.ui.focus.FocusDirection.Down), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    c.header?.let { h -> item(key = c.name + "#header") { h() } }
                    itemsIndexed(c.items, key = { _, it -> c.name + it.title }) { index, item ->
                        val rowFocusReq = rowFocus.getOrPut(item.title) { FocusRequester() }
                        SettingRow(
                            item, enabled = choosing == null, focus = rowFocusReq,
                            moving = moving == item.title,
                            onMove = { picked -> moving = if (picked) item.title else null },
                            onMoved = { refocus = item.title },
                            onFocused = {
                                lastRow = item.title
                                // The first row shows the section's picture above it in full, when both fit on screen.
                                if (index == 0 && c.header != null) pageScope.launch {
                                    awaitFrame()
                                    val info = listState.layoutInfo
                                    val head = info.visibleItemsInfo.firstOrNull { it.index == 0 }?.size
                                    val row = info.visibleItemsInfo.firstOrNull { it.index == 1 }?.size ?: return@launch
                                    if (head != null && head + row + 20 <= info.viewportEndOffset - info.viewportStartOffset) runCatching { listState.animateScrollToItem(0) }
                                }
                            },
                        ) {
                            if (moving == item.title) moving = null
                            else if (item.locked != null) Unit
                            else if (item.slider) Unit
                            else if (item.action != null) item.action.invoke()
                            else if (item.isToggle) {
                                item.onSelect(item.choices.first { it != item.current })
                                // Reorderable lists move a row when it's turned on / off: focus follows it there.
                                // The list redraws after a switch (rows can move, or the redraw drops focus):
                                // focus goes back to the switched row, or the one now in its place.
                                refocusAt = c.items.indexOf(item); refocus = item.title
                            }
                            else choosing = item
                        }
                    }
                }
                }
            }
        }
        choosing?.let { item -> ChoiceList(item, onDone = ::closeChoices) }
    }
}

@Composable
private fun SettingRow(
    item: SettingItem, enabled: Boolean, focus: FocusRequester,
    moving: Boolean = false, onMove: (Boolean) -> Unit = {}, onMoved: () -> Unit = {},
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).focusProperties { canFocus = enabled }.onFocusChanged { if (it.isFocused) onFocused() }
            .then(if (item.reorder == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                // Right: pick up / put down. While picked up, Up / Down move the row (and nothing else moves focus).
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent moving && e.key != Key.Back
                when {
                    e.key == Key.DirectionRight -> { onMove(!moving); true }
                    moving && e.key == Key.DirectionUp -> { item.reorder.invoke(-1); onMoved(); true }
                    moving && e.key == Key.DirectionDown -> { item.reorder.invoke(1); onMoved(); true }
                    moving && e.key == Key.DirectionLeft -> true
                    moving && e.key == Key.Back -> { onMove(false); true }
                    else -> false
                }
            })
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
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (moving) Text("↕ ", fontSize = 18.sp)
            Column(Modifier.weight(1f).graphicsLayer { alpha = if (item.locked != null) 0.75f else 1f }) {
                Text((if (item.locked != null) "🔒 " else "") + item.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (item.description.isNotEmpty()) Text(item.description, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                item.locked?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            }
            Spacer(Modifier.width(20.dp))
            if (item.locked != null) Text("Locked", fontSize = 14.sp, modifier = Modifier.graphicsLayer { alpha = 0.75f })
            else if (item.slider) {
                val i = item.choices.indexOf(item.current).coerceAtLeast(0)
                val fraction = if (item.choices.size > 1) i / item.choices.lastIndex.toFloat() else 0f
                Row(Modifier.width(260.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                    Box(Modifier.weight(1f).height(6.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(LocalContentColor.current, RoundedCornerShape(3.dp)))
                    }
                    Text("›", fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
                    Text(item.current.label, fontSize = 14.sp, maxLines = 1, modifier = Modifier.width(64.dp).padding(start = 10.dp))
                }
            }
            else if (item.isToggle) Toggle(item.current.label == "On")
            else Text(item.current.label, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(260.dp))
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    Box(
        Modifier.size(width = 48.dp, height = 26.dp)
            // Green / grey so it reads clearly whatever the highlight colour is.
            .background(if (on) Color(0xFF2EBD5A) else Color(0xFF6B6F7A), RoundedCornerShape(13.dp))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun ChoiceList(item: SettingItem, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(24.dp).trapFocus(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text(item.description, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 12.dp))
            // Focus starts on the current choice; when it isn't one of them (e.g. a "Custom" theme), on the first.
            val start = item.choices.indexOf(item.current).coerceAtLeast(0)
            item.choices.forEachIndexed { i, choice ->
                MenuItem(
                    text = (if (choice == item.current) "✓  " else "     ") + choice.label,
                    selected = choice == item.current,
                    modifier = if (i == start) Modifier.focusRequester(focus) else Modifier,
                    onClick = { item.onSelect(choice); onDone() },
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
