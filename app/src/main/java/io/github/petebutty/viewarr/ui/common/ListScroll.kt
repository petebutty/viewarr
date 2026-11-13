@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.petebutty.viewarr.ui.common

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts

/**
 * How lists follow the highlight (Settings → Appearance → List scrolling). Instant (the default): the list jumps in
 * the same frame as the highlight moves. Sliding it there over a few frames left the bright highlight a row out on
 * every press, which showed as a trail ("ghosting") when going through a list quickly. Smooth: the slide.
 */
object ListScroll {
    val instant: Boolean get() = AppColors.palette.opt(UiOpts.LIST_SCROLL) == 0
}

/** This spec (where the highlight rests) with the List scrolling setting's movement. */
fun BringIntoViewSpec.followingSetting(): BringIntoViewSpec {
    val base = this
    return object : BringIntoViewSpec {
        override val scrollAnimationSpec: AnimationSpec<Float>
            get() = if (ListScroll.instant) snap() else base.scrollAnimationSpec
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            base.calculateScrollDistance(offset, size, containerSize)
    }
}

/**
 * A card or button that grows when highlighted. TV Material animates that (the old one shrinking while the new one
 * grows, a trail when moving fast), so with List scrolling on Instant the growing is done here at once instead, and
 * the Surface's own scale is switched off. Use with [focusScale] for the Surface's `scale`.
 */
fun androidx.compose.ui.Modifier.instantFocusScale(focused: Float): androidx.compose.ui.Modifier = composed {
    if (!ListScroll.instant) return@composed this
    var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    this.onFocusChanged { on = it.hasFocus }
        .zIndex(if (on) 1f else 0f)
        .graphicsLayer { val s = if (on) focused else 1f; scaleX = s; scaleY = s }
}

/** The Surface's own scale: none with List scrolling on Instant ([instantFocusScale] does it), else TV Material's. */
fun focusScale(focused: Float) =
    if (ListScroll.instant) androidx.tv.material3.ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    else androidx.tv.material3.ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = focused, pressedScale = 1f)
