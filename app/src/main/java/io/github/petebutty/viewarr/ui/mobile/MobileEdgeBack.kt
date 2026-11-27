package io.github.petebutty.viewarr.ui.mobile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Back by finger on a phone or tablet: a swipe in from the left edge of the screen does what the phone's Back button
 * does, on every page and over the players (2026-11-27: reaching down to the Back button is a pain, and the
 * phone's own gestures don't work with every home app). A round arrow follows the finger in; let go far enough and
 * it's Back, not far enough and nothing happens. Swipes that start away from the edge are the pages' own.
 */
internal object EdgeBack {
    /** How far the finger has come in (pixels), and where (y): for the arrow. 0: no swipe. */
    var pull by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)

    fun Modifier.edgeBack(): Modifier = if (!Mobile.on) this else composed {
        val activity = LocalContext.current as? ComponentActivity
        // (The device's own sizes: the TV pages draw on a canvas of their own.)
        val d = Mobile.density ?: LocalDensity.current
        val edge = with(d) { 20.dp.toPx() }
        val claimAt = with(d) { 14.dp.toPx() }
        val far = with(d) { 80.dp.toPx() }
        pointerInput(activity) {
            awaitEachGesture {
                // (After the pages: a row that can scroll back, or a channel card's swipe, takes it first; only a swipe
                // nothing under the finger wanted is Back. 2026-11-27: swiping a row back went Back.)
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                if (down.position.x > edge) return@awaitEachGesture
                var dx = 0f
                var dy = 0f
                var claimed = false
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Final)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!claimed && ch.isConsumed) break
                    if (!ch.pressed) {
                        // (Only when something on screen takes Back: on the first page it would close the app.)
                        if (claimed) { ch.consume(); if (dx > far && activity?.onBackPressedDispatcher?.hasEnabledCallbacks() == true) activity.onBackPressedDispatcher.onBackPressed() }
                        break
                    }
                    dx += ch.position.x - ch.previousPosition.x
                    dy += ch.position.y - ch.previousPosition.y
                    if (!claimed && dx > claimAt && abs(dx) > abs(dy) * 1.5f) claimed = true
                    if (!claimed && (abs(dy) > claimAt * 2 || dx < -claimAt)) break
                    if (claimed) { ch.consume(); pull = dx.coerceAtLeast(0f); y = ch.position.y }
                }
                pull = 0f
            }
        }
    }
}

/** The round Back arrow that follows a swipe in from the left edge (fuller and in the app's colour once far enough). */
@Composable
internal fun EdgeBackArrow() {
    val pull = EdgeBack.pull
    if (!Mobile.on || pull <= 0f) return
    val d = LocalDensity.current
    val far = with(d) { 80.dp.toPx() }
    val size = with(d) { 48.dp.toPx() }
    val ready = pull > far
    Box(
        Modifier
            .offset { IntOffset((minOf(pull, far * 1.3f) - size).roundToInt().coerceAtLeast((-size / 2).toInt()), (EdgeBack.y - size / 2).roundToInt()) }
            .size(48.dp)
            .graphicsLayer { alpha = (pull / far).coerceIn(0.3f, 1f) }
            .clip(CircleShape)
            .background(if (ready) AppColors.Accent else Color.Black.copy(alpha = 0.7f)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { Icon(AppIcons.Back, "Back", tint = Color.White, modifier = Modifier.size(26.dp)) }
}
