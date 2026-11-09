package io.github.petebutty.viewarr.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/*
 * The parts every library grid shares (films, shows, IPTV and music): the toolbar buttons, active-filter chips,
 * the A–Z strip, and the Sort and Filter panels. Each library fills them with what its own server offers.
 */

@Composable
internal fun ToolButton(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) AppColors.TextPrimary else AppColors.Panel,
            contentColor = if (active) AppColors.Background else AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
        ),
    ) { Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { Text(text, fontSize = 14.sp, maxLines = 1) } }
}

/** An active filter: OK removes it. */
@Composable
internal fun FilterChip(text: String, clear: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(15.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (clear) Color.Transparent else AppColors.Accent.copy(alpha = 0.22f),
            contentColor = AppColors.TextPrimary, focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
        ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, AppColors.Accent.copy(alpha = 0.6f)), shape = RoundedCornerShape(15.dp))),
    ) { Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text(if (clear) text else "$text  ✕", fontSize = 13.sp, maxLines = 1) } }
}

/**
 * The A–Z strip down the grid's right side. Up / Down move through the letters (the grid follows), OK goes to the
 * highlighted letter's first title, Left goes back into the grid. Letters with no titles are dimmed.
 */
@Composable
internal fun AlphabetRail(
    letters: Map<Char, Int>?, shown: Char?, focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit, onLetter: (Char) -> Unit, onPick: (Char) -> Unit, onLeft: () -> Unit, onUp: () -> Unit,
    /** Every step along the strip, straight away (for the big letter); [onLetter] only once you stop. */
    onMove: (Char) -> Unit = {},
) {
    val all = remember { listOf('#') + ('A'..'Z') }
    val usable = remember(letters) { if (letters == null) all else all.filter { it in letters } }
    var sel by remember { mutableStateOf(shown ?: 'A') }
    var focusedRail by remember { mutableStateOf(false) }
    LaunchedEffect(shown) { if (!focusedRail && shown != null) sel = shown }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pending by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // The letter the grid was last moved to (Left goes to it if you moved on without stopping).
    var jumped by remember { mutableStateOf<Char?>(null) }
    fun move(d: Int) {
        val list = usable.ifEmpty { all }
        val i = list.indexOf(sel).let { if (it < 0) list.indexOfFirst { c -> c >= sel }.coerceAtLeast(0) else it }
        sel = list[(i + d).coerceIn(0, list.lastIndex)]
        // The grid follows once you pause for a moment (holding Up/Down flies through the letters).
        pending?.cancel()
        onMove(sel)
        // Moving only lights the letter; the grid jumps (and loads) once you stop for a moment, so holding Up/Down
        // flies through the letters without the grid trying to keep up.
        pending = scope.launch { kotlinx.coroutines.delay(500); jumped = sel; onLetter(sel) }
    }
    Column(
        Modifier.width(40.dp).fillMaxHeight().padding(top = 4.dp, bottom = 12.dp, end = 12.dp)
            .background(if (focusedRail) AppColors.Panel.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { focusedRail = it.isFocused; onFocus(it.isFocused); if (it.isFocused) { jumped = sel; onMove(sel) } }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionRight)
                when (e.key) {
                    // Up from the top of the strip: to the toolbar.
                    Key.DirectionUp -> { if (sel == (usable.firstOrNull() ?: '#')) { pending?.cancel(); onUp() } else move(-1); true }
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionLeft -> { pending?.cancel(); if (jumped != sel) { jumped = sel; onPick(sel) } else onLeft(); true }
                    Key.DirectionRight -> true
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { pending?.cancel(); jumped = sel; onPick(sel); true }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Every letter gets an equal share of the height, so all 27 always fit.
        all.forEach { ch ->
            val has = letters == null || ch in letters
            val on = ch == sel
            Box(
                // Letters with no titles are faded (by transparency: the app's text outline ignores a dim colour).
                Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = true).padding(1.dp)
                    .then(if (has || on) Modifier else Modifier.alpha(0.25f)).background(
                    when { on && focusedRail -> AppColors.Accent; on -> Color.White.copy(alpha = 0.16f); else -> Color.Transparent }, CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ch.toString(), fontSize = 10.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = when { on && focusedRail -> AppColors.OnAccent; has -> AppColors.TextPrimary; else -> AppColors.TextSecondary.copy(alpha = 0.3f) },
                )
            }
        }
    }
}

/** A panel sliding in from the right over the grid (Back closes it; the remote stays inside). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SidePanel(title: String, onClose: () -> Unit, onBack: () -> Unit = onClose, content: androidx.compose.foundation.lazy.LazyListScope.(first: FocusRequester) -> Unit) {
    val first = remember(title) { FocusRequester() }
    androidx.activity.compose.BackHandler { onBack() }
    LaunchedEffect(title) { awaitFrame(); awaitFrame(); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(400.dp).fillMaxHeight().background(AppColors.Panel)
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp), contentPadding = PaddingValues(bottom = 24.dp)) { content(first) }
        }
    }
}

/** "Newest first" / "A → Z": how the direction of [sort] reads. */
internal fun directionLabel(sort: String, desc: Boolean): String {
    val s = sort.lowercase()
    return when {
        s.contains("title") || s.contains("name") -> if (desc) "Z → A" else "A → Z"
        listOf("added", "created", "available", "premiere", "released", "viewed", "played", "year", "date").any { s.contains(it) } -> if (desc) "Newest first" else "Oldest first"
        else -> if (desc) "Highest first" else "Lowest first"
    }
}

@Composable
internal fun SortPanel(o: io.github.petebutty.viewarr.data.media.LibraryOptions, q: io.github.petebutty.viewarr.data.media.LibraryQuery, onPick: (String) -> Unit, onDesc: (Boolean) -> Unit, onClose: () -> Unit) {
    SidePanel("Sort by", onClose) { first ->
        if (o.directions) item(key = "dir") {
            io.github.petebutty.viewarr.ui.common.MenuItem(
                text = "Order", trailing = directionLabel(q.sort, q.desc) + "  ⇅", selected = false,
                onClick = { onDesc(!q.desc) }, modifier = Modifier.focusRequester(first).padding(bottom = 8.dp),
            )
        }
        items(o.sorts, key = { "s-" + it.id }) { s ->
            val on = s.id == q.sort
            io.github.petebutty.viewarr.ui.common.MenuItem(
                text = s.label, trailing = if (on) "✓" else null, selected = on, onClick = { onPick(s.id) },
                modifier = if (!o.directions && s == o.sorts.first()) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

@Composable
internal fun FilterPanel(
    o: io.github.petebutty.viewarr.data.media.LibraryOptions, q: io.github.petebutty.viewarr.data.media.LibraryQuery,
    values: Map<String, List<io.github.petebutty.viewarr.data.media.FilterValue>?>,
    onOpenGroup: (String) -> Unit, onToggle: (String) -> Unit,
    onValue: (String, io.github.petebutty.viewarr.data.media.FilterValue) -> Unit, onClear: () -> Unit, onClose: () -> Unit,
) {
    var group by remember { mutableStateOf<io.github.petebutty.viewarr.data.media.FilterGroup?>(null) }
    val g = group
    if (g == null) SidePanel("Filter", onClose) { first ->
        val toggles = o.filters.filter { it.toggle }
        val lists = o.filters.filter { !it.toggle }
        items(toggles, key = { "t-" + it.id }) { t ->
            val on = t.id in q.toggles
            io.github.petebutty.viewarr.ui.common.MenuItem(
                text = t.label, trailing = if (on) "On" else "Off", selected = on, onClick = { onToggle(t.id) },
                modifier = if (t == toggles.first()) Modifier.focusRequester(first) else Modifier,
            )
        }
        if (toggles.isNotEmpty() && lists.isNotEmpty()) item(key = "gap") { Spacer(Modifier.height(10.dp)) }
        items(lists, key = { "g-" + it.id }) { fg ->
            val picked = q.filters[fg.id].orEmpty()
            io.github.petebutty.viewarr.ui.common.MenuItem(
                text = fg.label,
                trailing = (if (picked.isEmpty()) "Any" else picked.take(2).joinToString(", ") { it.label } + if (picked.size > 2) " +${picked.size - 2}" else "") + "  ›",
                selected = picked.isNotEmpty(),
                onClick = { onOpenGroup(fg.id); group = fg },
                modifier = if (toggles.isEmpty() && fg == lists.first()) Modifier.focusRequester(first) else Modifier,
            )
        }
        if (q.active > 0) item(key = "clear") {
            io.github.petebutty.viewarr.ui.common.MenuItem(text = "Clear all filters", selected = false, onClick = onClear, modifier = Modifier.padding(top = 10.dp))
        }
    } else SidePanel(g.label, onClose, onBack = { group = null }) { first ->
        item(key = "back") {
            io.github.petebutty.viewarr.ui.common.MenuItem(text = "‹  All filters", selected = false, onClick = { group = null }, modifier = Modifier.focusRequester(first).padding(bottom = 6.dp))
        }
        val list = values[g.id]
        when {
            list == null -> item(key = "loading") { Text("Loading…", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp)) }
            list.isEmpty() -> item(key = "none") { Text("Nothing to pick from in this library.", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp)) }
            else -> items(list, key = { "v-" + it.id }) { v ->
                val on = q.filters[g.id].orEmpty().any { it.id == v.id }
                io.github.petebutty.viewarr.ui.common.MenuItem(text = v.label, trailing = if (on) "✓" else null, selected = on, onClick = { onValue(g.id, v) })
            }
        }
    }
}
