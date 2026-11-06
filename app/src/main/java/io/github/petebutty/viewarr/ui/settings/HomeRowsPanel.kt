package io.github.petebutty.viewarr.ui.settings

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
 * Settings → Home rows: the rows on the Films and Shows pages. OK shows or hides a row; Right picks it up so
 * Up / Down move it, and OK, Right or Left puts it down.
 */
@Composable
fun HomeRowsSettingsScreen(onBack: () -> Unit) {
    var type by remember { mutableStateOf(MediaType.Movie) }
    val tabFocus = remember { listOf(FocusRequester(), FocusRequester()) }
    var moving by remember { mutableStateOf<String?>(null) }
    BackHandler { if (moving != null) moving = null else onBack() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { tabFocus[0].requestFocus() } }
    Box(Modifier.fillMaxSize().background(AppColors.Background).trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Column(Modifier.width(300.dp).fillMaxHeight()) {
                Text("Settings", fontSize = 14.sp, color = AppColors.Accent)
                Text("Home rows", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 20.dp))
                listOf(MediaType.Movie to "Movies", MediaType.Show to "Shows").forEachIndexed { i, (t, label) ->
                    MenuItem(
                        text = label,
                        selected = t == type,
                        onClick = { type = t },
                        modifier = Modifier.focusRequester(tabFocus[i]).onFocusChanged { if (it.isFocused) { type = t; moving = null } },
                    )
                }
            }
            Spacer(Modifier.width(40.dp))
            // Same view model as the Films / Shows page, so the rows are shared and changes show there at once.
            androidx.compose.runtime.key(type) {
                RowsEditor(type, moving, onMoving = { moving = it })
            }
        }
    }
}

@Composable
private fun RowsEditor(type: MediaType, moving: String?, onMoving: (String?) -> Unit) {
    val media: MediaViewModel = hiltViewModel(key = "media-${type.name}")
    LaunchedEffect(type) { media.load(type) }
    val state by media.editableRows.collectAsStateWithLifecycle()
    val (rows, done) = state
    val focus = remember { HashMap<String, FocusRequester>() }
    val listState = rememberLazyListState()
    // After a move, keep focus on the row being carried.
    LaunchedEffect(moving, rows.map { it.first.id }) {
        val id = moving ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it.first.id == id }
        if (idx >= 0) {
            listState.animateScrollToItem(maxOf(0, idx - 3))
            awaitFrame()
            runCatching { focus[id]?.requestFocus() }
        }
    }
    Column(Modifier.fillMaxHeight()) {
        Text(
            if (moving != null) "Moving: Up / Down to place it, then OK to put it down."
            else "OK shows or hides a row. Right picks a row up to move it.",
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
                            Text(if (hidden) "Hidden" else "${row.items.size} titles", fontSize = 12.sp)
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
            if (!done) item(key = "loading") {
                Text("Loading rows…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
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
