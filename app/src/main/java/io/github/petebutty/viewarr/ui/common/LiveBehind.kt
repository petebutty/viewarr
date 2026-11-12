package io.github.petebutty.viewarr.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A library or catalogue opened from the menu over full-screen live TV: the channel carries on full screen behind it,
 * and the page draws see-through (no artwork of its own, no trailers) so it shows.
 */
object LiveBehind {
    var on by mutableStateOf(false)
    /** Movies / Shows from the guide: the channel in the guide's preview place, top left (the hero makes room). */
    var preview by mutableStateOf(false)
}
