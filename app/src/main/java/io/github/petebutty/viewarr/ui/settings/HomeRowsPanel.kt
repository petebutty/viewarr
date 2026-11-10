package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.theme.appBackground

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame

/**
 * Settings → Home page & menu, for Movies and for Shows, each in two parts:
 *  - Home page: the rows on the Movies / Shows page, top to bottom (on / off and order);
 *  - Menu list: what the menu lists when you press Right on Movies / Shows (libraries and catalogues, on / off).
 * OK turns an entry on or off; Right picks it up so Up / Down move it, and OK, Right or Left puts it down.
 */
@Composable
fun HomeRowsSettingsScreen(onBack: () -> Unit) {
    var type by remember { mutableStateOf(MediaType.Movie) }
    var menu by remember { mutableStateOf(false) }
    val tabFocus = remember { List(4) { FocusRequester() } }
    var moving by remember { mutableStateOf<String?>(null) }
    BackHandler { if (moving != null) moving = null else onBack() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { tabFocus[0].requestFocus() } }
    Box(Modifier.fillMaxSize().appBackground(readable = true).trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Column(Modifier.width(300.dp).fillMaxHeight()) {
                Text("Settings", fontSize = 14.sp, color = AppColors.Accent)
                Text("Home page & menu", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 20.dp))
                var i = 0
                listOf(MediaType.Movie to "Movies", MediaType.Show to "Shows").forEach { (t, label) ->
                    Text(label, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
                    listOf(false to "Home page", true to "Menu list").forEach { (m, name) ->
                        val fr = tabFocus[i++]
                        MenuItem(
                            text = name,
                            selected = t == type && m == menu,
                            onClick = { type = t; menu = m },
                            modifier = Modifier.focusRequester(fr).onFocusChanged { if (it.isFocused) { type = t; menu = m; moving = null } },
                        )
                    }
                }
                // Both pages: rows that repeat across servers shown once.
                val mediaVm: MediaViewModel = hiltViewModel(key = "media-${MediaType.Movie.name}")
                val merge by mediaVm.mergeRows.collectAsStateWithLifecycle()
                Spacer(Modifier.height(18.dp))
                MenuItem(
                    text = "Combine rows from different servers",
                    trailing = if (merge) "On" else "Off",
                    selected = false,
                    onClick = { mediaVm.setMergeRows(!merge) },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) moving = null },
                )
                Text(
                    "One Continue watching, and one Recently added per library, instead of a row for each server.",
                    fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.width(40.dp))
            // Same view model as the Films / Shows page, so the rows are shared and changes show there at once.
            // Left from the rows: back to the page selected on the left (not the nearest entry).
            Box(Modifier.weight(1f).fillMaxHeight().leftReturnsTo { tabFocus[(if (type == MediaType.Show) 2 else 0) + (if (menu) 1 else 0)] }) {
                androidx.compose.runtime.key(type, menu) {
                    RowsEditor(type, menu, moving, onMoving = { moving = it })
                }
            }
        }
    }
}

@Composable
private fun RowsEditor(type: MediaType, menu: Boolean, moving: String?, onMoving: (String?) -> Unit) {
    val media: MediaViewModel = hiltViewModel(key = "media-${type.name}")
    LaunchedEffect(type) { media.load(type); media.loadLibraries(type) }
    val libs = media.allLibraries.collectAsStateWithLifecycle().value.orEmpty()
    val state by media.editableRows.collectAsStateWithLifecycle()
    val (pageRows, done) = state
    val menuRows by media.editableMenuRows.collectAsStateWithLifecycle()
    // The page: its rows. The menu: libraries first (as the menu lists them), then the catalogues.
    val rows = if (menu) emptyList() else pageRows
    val what = if (type == MediaType.Movie) "Movies" else "Shows"
    val focus = remember { HashMap<String, FocusRequester>() }
    val listState = rememberLazyListState()
    // After a move, keep focus on the row being carried.
    LaunchedEffect(moving, rows.map { it.first.id }, libs.map { it.first.key }) {
        val id = moving ?: return@LaunchedEffect
        val idx = if (id.startsWith("lib:")) 1 + libs.indexOfFirst { "lib:" + it.first.key == id } else rows.indexOfFirst { it.first.id == id }
        if (idx >= 0) {
            listState.animateScrollToItem(maxOf(0, idx - 3))
            awaitFrame()
            runCatching { focus[id]?.requestFocus() }
        }
    }
    Column(Modifier.fillMaxHeight()) {
        Text(
            if (moving != null) "Moving: Up / Down to place it, then OK to put it down."
            else if (menu) "What the menu lists when you press Right on $what. OK turns one on or off, Right picks a library up to move it, and holding OK renames it."
            else "The rows on the $what page, top to bottom. OK turns a row on or off; Right picks it up to move it.",
            fontSize = 14.sp, color = if (moving != null) AppColors.Accent else AppColors.TextSecondary,
            modifier = Modifier.padding(top = 26.dp, bottom = 14.dp, start = 4.dp),
        )
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(rows, key = { _, it -> it.first.id }) { i, (row, hidden) ->
                val fr = focus.getOrPut(row.id) { FocusRequester() }
                val carried = row.id == moving
                fun move(by: Int) {
                    val j = i + by
                    if (j !in rows.indices) return
                    val ids = rows.map { it.first.id }.toMutableList()
                    ids.add(j, ids.removeAt(i))
                    media.setRowOrder(ids)
                }
                Surface(
                    onClick = { if (moving != null) onMoving(null) else media.setRowHidden(row.id, !hidden) },
                    modifier = Modifier.fillMaxWidth().focusRequester(fr).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            moving == null && e.key == Key.DirectionRight -> { onMoving(row.id); true }
                            carried && (e.key == Key.DirectionRight || e.key == Key.DirectionLeft) -> { onMoving(null); true }
                            carried && e.key == Key.DirectionUp -> { move(-1); true }
                            carried && e.key == Key.DirectionDown -> { move(1); true }
                            moving != null && !carried -> true
                            else -> false
                        }
                    },
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = AppColors.Panel,
                        contentColor = if (hidden) AppColors.TextSecondary else AppColors.TextPrimary,
                        focusedContainerColor = AppColors.Accent,
                        focusedContentColor = AppColors.OnAccent,
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (carried) "↕" else "${i + 1}", fontSize = 14.sp, modifier = Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (hidden) "Not on the page" else "On the page  ·  ${row.items.size} titles", fontSize = 12.sp)
                        }
                        Box(
                            Modifier.size(width = 48.dp, height = 26.dp)
                                .background(if (!hidden) Color(0xFF2EBD5A) else Color(0xFF6B6F7A), RoundedCornerShape(13.dp))
                                .padding(3.dp),
                            contentAlignment = if (!hidden) Alignment.CenterEnd else Alignment.CenterStart,
                        ) { Box(Modifier.size(20.dp).background(Color.White, CircleShape)) }
                    }
                }
            }
            if (!done && !menu) item(key = "loading") {
                Text("Loading rows…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
            }
            // The menu's library list (Movies / Shows → Right): on / off and order.
            if (menu && libs.isNotEmpty()) item(key = "libs-head") {
                Text("Libraries", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.Accent, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 4.dp))
            }
            if (menu) itemsIndexed(libs, key = { _, it -> "lib:" + it.first.key }) { i, (lib, hidden) ->
                var renaming by remember { mutableStateOf(false) }
                if (renaming) RenameLibraryDialog(lib.title, onDone = { name -> media.setLibraryName(lib.key, name); renaming = false }, onCancel = { renaming = false })
                val id = "lib:" + lib.key
                val fr = focus.getOrPut(id) { FocusRequester() }
                val carried = id == moving
                fun move(by: Int) {
                    val j = i + by
                    if (j !in libs.indices) return
                    val keys = libs.map { it.first.key }.toMutableList()
                    keys.add(j, keys.removeAt(i))
                    media.setLibraryOrder(keys)
                }
                Surface(
                    onClick = { if (moving != null) onMoving(null) else media.setLibraryHidden(lib.key, !hidden) },
                    onLongClick = { if (moving == null) renaming = true },
                    modifier = Modifier.fillMaxWidth().focusRequester(fr).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            moving == null && e.key == Key.DirectionRight -> { onMoving(id); true }
                            carried && (e.key == Key.DirectionRight || e.key == Key.DirectionLeft) -> { onMoving(null); true }
                            carried && e.key == Key.DirectionUp -> { move(-1); true }
                            carried && e.key == Key.DirectionDown -> { move(1); true }
                            moving != null && !carried -> true
                            else -> false
                        }
                    },
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = AppColors.Panel,
                        contentColor = if (hidden) AppColors.TextSecondary else AppColors.TextPrimary,
                        focusedContainerColor = AppColors.Accent,
                        focusedContentColor = AppColors.OnAccent,
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (carried) "↕" else "▤", fontSize = 14.sp, modifier = Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lib.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(lib.source + "  ·  " + if (hidden) "Not in the menu" else "In the menu", fontSize = 12.sp)
                        }
                        Box(
                            Modifier.size(width = 48.dp, height = 26.dp)
                                .background(if (!hidden) Color(0xFF2EBD5A) else Color(0xFF6B6F7A), RoundedCornerShape(13.dp))
                                .padding(3.dp),
                            contentAlignment = if (!hidden) Alignment.CenterEnd else Alignment.CenterStart,
                        ) { Box(Modifier.size(20.dp).background(Color.White, CircleShape)) }
                    }
                }
            }
            // The menu's Catalogues: on / off only; they follow the page's order.
            if (menu && menuRows.isNotEmpty()) item(key = "cat-head") {
                Text("Under \"Jump to a row\" (same order as the page)", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.Accent, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 4.dp))
            }
            if (menu) itemsIndexed(menuRows, key = { _, it -> "cat:" + it.first.id }) { _, (row, off) ->
                Surface(
                    onClick = { if (moving == null) media.setMenuRowHidden(row.id, !off) else onMoving(null) },
                    modifier = Modifier.fillMaxWidth(),
                    scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = AppColors.Panel,
                        contentColor = if (off) AppColors.TextSecondary else AppColors.TextPrimary,
                        focusedContainerColor = AppColors.Accent,
                        focusedContentColor = AppColors.OnAccent,
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("☰", fontSize = 14.sp, modifier = Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (off) "Not in the menu" else "In the menu", fontSize = 12.sp)
                        }
                        Box(
                            Modifier.size(width = 48.dp, height = 26.dp)
                                .background(if (!off) Color(0xFF2EBD5A) else Color(0xFF6B6F7A), RoundedCornerShape(13.dp))
                                .padding(3.dp),
                            contentAlignment = if (!off) Alignment.CenterEnd else Alignment.CenterStart,
                        ) { Box(Modifier.size(20.dp).background(Color.White, CircleShape)) }
                    }
                }
            }
            if (done) item(key = "reset") {
                var confirm by remember { mutableStateOf(false) }
                MenuItem(
                    text = if (confirm) "Press OK again to reset" else "Reset to the original order",
                    selected = false,
                    modifier = Modifier.padding(top = 10.dp).onFocusChanged { if (!it.isFocused) confirm = false },
                    onClick = { if (confirm) { media.resetRows(); confirm = false } else confirm = true },
                )
            }
        }
    }
}

/** Hold OK on a library: give it your own name (empty puts the server's name back). */
@Composable
private fun RenameLibraryDialog(current: String, onDone: (String?) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.width(520.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Rename library", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text("Leave it empty to use the server's own name.", fontSize = 13.sp, color = AppColors.TextSecondary)
            io.github.petebutty.viewarr.ui.setup.WizardField(
                name, { name = it }, "Name", first,
                androidx.compose.ui.text.input.KeyboardType.Text, androidx.compose.ui.text.input.ImeAction.Done,
                onIme = { onDone(name.ifBlank { null }) },
            )
            MenuItem(text = "Save", selected = false, onClick = { onDone(name.ifBlank { null }) })
            MenuItem(text = "Cancel", selected = false, onClick = onCancel)
        }
    }
}
