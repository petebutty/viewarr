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
    .focusProperties { exit = { FocusRequester.Cancel } }
    .focusGroup()

/** Moving past these edges of a list or row does nothing (instead of jumping to whatever is nearest outside it). */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.stopAt(dir: androidx.compose.ui.focus.FocusDirection): Modifier = this
    .focusProperties { exit = { if (it == dir) FocusRequester.Cancel else FocusRequester.Default } }
    .focusGroup()

fun Modifier.stopAtRight(): Modifier = stopAt(androidx.compose.ui.focus.FocusDirection.Right)
