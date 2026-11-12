package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Keeps the remote inside a pop-up or overlay: moving past its edge does nothing, instead of jumping to
 * whatever is underneath (e.g. a poster behind the detail page, which then reopened the title).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.trapFocus(): Modifier = this
    .focusProperties { exit = { if (EdgeBlocked.letOut) FocusRequester.Default else { EdgeBlocked.hit = true; FocusRequester.Cancel } } }
    .focusGroup()

/**
 * Set when a trap or stop above blocked a move at its edge. Android counts a blocked move as handled, so "did the move
 * go anywhere?" can't tell; the corner picture (reached by pressing past a page's edge) asks this instead.
 */
object EdgeBlocked {
    @Volatile var hit = false
    /**
     * Set only for the moment the highlight is moved onto the corner picture on purpose: the trap lets it out (it also
     * stopped that move, so the picture couldn't be reached from a title page or search).
     */
    @Volatile var letOut = false

    /** Runs [move] with the traps open (see [letOut]). */
    inline fun <T> lettingOut(move: () -> T): T { letOut = true; try { return move() } finally { letOut = false } }
}

/** Moving past these edges of a list or row does nothing (instead of jumping to whatever is nearest outside it). */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.stopAt(dir: androidx.compose.ui.focus.FocusDirection): Modifier = this
    .focusProperties { exit = { if (it == dir && !EdgeBlocked.letOut) { EdgeBlocked.hit = true; FocusRequester.Cancel } else FocusRequester.Default } }
    .focusGroup()

fun Modifier.stopAtRight(): Modifier = stopAt(androidx.compose.ui.focus.FocusDirection.Right)
