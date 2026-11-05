package io.github.petebutty.viewarr.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Side panels (menu, channel list) slide in quickly from the left when they open. Only the opening is animated:
 * closing stays instant, so focus never lands on a panel that's on its way out.
 */
fun Modifier.slideInFromLeft(distance: Dp = 420.dp, millis: Int = 180): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(millis, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        translationX = -(1f - p.value) * distance.toPx()
        alpha = 0.3f + 0.7f * p.value
    }
}
