package io.github.petebutty.viewarr.ui.media

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.ui.common.posterRequest
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Posters loaded ahead of the highlight (Shield measurements, 2026-11-22: on a fresh cache half the frames were late
 * while posters downloaded and decoded as they slid on). Each row on screen loads the next few posters past its right
 * edge, and the page loads the first posters of the row under the last one showing, into the picture cache at the
 * cards' own size and under their own key, so the card finds them ready. One at a time per row, a moment after the
 * row appears (the posters on screen go first), and stopped when the row leaves the screen.
 */
private const val AHEAD = 8

/** A row poster card's picture size in pixels (2:3 at the rows' poster width). */
@Composable
internal fun rowPosterPx(): IntSize {
    val w = AppColors.palette.rowPosterWidth
    val density = LocalDensity.current
    return remember(w, density) { with(density) { IntSize(w.dp.roundToPx(), (w * 1.5f).dp.roundToPx()) } }
}

/** The picture a poster card shows. */
internal fun cardPicture(item: MediaItem): String? = item.poster ?: item.backdrop

/** In one row: the [AHEAD] posters after the last one on screen. */
@Composable
internal fun RowLookAhead(state: LazyListState, items: List<MediaItem>, sizePx: IntSize) {
    val context = LocalContext.current
    LaunchedEffect(state, items, sizePx) {
        delay(250)
        val done = HashSet<String>()
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().conflate()
            .collect {
                // (The newest place each time round: the highlight may have moved on while one was loading.)
                while (true) {
                    val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: break
                    val end = minOf(last + AHEAD, items.lastIndex)
                    var url: String? = null
                    for (i in last + 1..end) {
                        val u = cardPicture(items[i]) ?: continue
                        if (u !in done) { url = u; break }
                    }
                    if (url == null) break
                    done += url
                    context.imageLoader.execute(posterRequest(context, url, sizePx.width, sizePx.height)) // (Failures come back as a result, not thrown.)
                }
            }
    }
}

/** On the page: the first posters of the row under the last row on screen. */
@Composable
internal fun RowsLookAhead(listState: LazyListState, rows: List<MediaRow>) {
    val context = LocalContext.current
    val sizePx = rowPosterPx()
    LaunchedEffect(listState, rows, sizePx) {
        delay(400)
        val done = HashSet<String>()
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().conflate()
            .collect { last ->
                val next = rows.getOrNull(last + 1) ?: return@collect
                for (item in next.items.take(AHEAD + 2)) {
                    val u = cardPicture(item) ?: continue
                    if (!done.add(u)) continue
                    context.imageLoader.execute(posterRequest(context, u, sizePx.width, sizePx.height)) // (Failures come back as a result, not thrown.)
                }
            }
    }
}
