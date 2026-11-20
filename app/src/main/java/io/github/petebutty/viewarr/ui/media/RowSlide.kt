package io.github.petebutty.viewarr.ui.media

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A row that moves the way the poster rows do (Peter, 2026-11-20: "episode panels should animate the same as posters"):
 * the highlight keeps its place at the start of the row and the cards slide under it. On Left / Right the row jumps so
 * the card being moved to sits in the first place, in the same instant as the highlight, and is then drawn where it was
 * and eased into place (one layer moved, nothing laid out again per frame).
 *
 * Use: [modifier] on the LazyRow, [focused] set from each card's focus change, and the row's bring-into-view switched
 * off (NoRowScroll), since the row is moved here, from the key press. (Never from a focus change: moving a row while a
 * focus change is being applied crashes Compose.)
 */
internal class RowSlide(private val state: LazyListState, private val scope: CoroutineScope, private val count: () -> Int) {
    private val glide = mutableFloatStateOf(0f)
    private var job: Job? = null

    /** The highlighted card's index (-1: the row doesn't have the highlight). */
    var focused = -1

    /**
     * The card at [newIdx] brought to the first place, sliding. For a row whose highlight isn't the focus system's (the
     * film player's episode cards): called when its highlighted index changes. False: that card isn't on screen (jump).
     * [place]: where the card should sit, in pixels from the row's start (the film player keeps it in the middle).
     */
    fun moveTo(newIdx: Int, place: Int = 0): Boolean {
        if (state.layoutInfo.visibleItemsInfo.none { it.index == newIdx }) return false
        slideTo(newIdx, place)
        return true
    }

    private fun slideTo(newIdx: Int, place: Int = 0) {
        val to = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == newIdx } ?: return
        // To its place (at the row's end there's nothing further to bring in, so the highlight walks on).
        val used = state.dispatchRawDelta((to.offset - place).toFloat())
        if (used == 0f) return
        // Never more than one card behind, however fast the button's pressed.
        val step = kotlin.math.abs(used)
        glide.floatValue = (glide.floatValue + used).coerceIn(-step, step)
        job?.cancel()
        job = scope.launch {
            animate(glide.floatValue, 0f, animationSpec = tween(260, easing = FastOutSlowInEasing)) { v, _ -> glide.floatValue = v }
        }
    }

    val modifier: Modifier = Modifier
        .graphicsLayer { translationX = glide.floatValue }
        .onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && focused >= 0) when (e.key) {
                Key.DirectionRight -> if (focused < count() - 1) slideTo(focused + 1)
                Key.DirectionLeft -> if (focused > 0) slideTo(focused - 1)
                else -> Unit
            }
            false
        }
}

@Composable
internal fun rememberRowSlide(state: LazyListState, count: () -> Int): RowSlide {
    val scope = rememberCoroutineScope()
    val latest = androidx.compose.runtime.rememberUpdatedState(count)
    return remember(state) { RowSlide(state, scope) { latest.value() } }
}
