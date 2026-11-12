package io.github.petebutty.viewarr.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The start page has what it needs on screen (the guide's channels): the splash can go. Until then it stays up (within
 * a limit), so slower boxes aren't handed a page still loading underneath.
 */
object StartupGate {
    var ready by mutableStateOf(false)
}
