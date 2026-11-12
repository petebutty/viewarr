package io.github.petebutty.viewarr.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width

/** A side panel of [width], shown straight away (it used to grow in from the left). */
@androidx.compose.runtime.Composable
fun GrowFromLeft(width: Dp, content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxHeight().width(width)) { content() }
}

/** [GrowFromLeft] while [visible]. */
@androidx.compose.runtime.Composable
fun GrowFromLeftToggle(visible: Boolean, width: Dp, content: @androidx.compose.runtime.Composable () -> Unit) {
    if (visible) GrowFromLeft(width, content)
}
