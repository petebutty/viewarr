package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * Settings → Advanced: open, and its entry in the Settings menu (where the highlight goes back to). It's shown through
 * the Troubleshooting page's place in the menu (one page of that kind is up at a time), so the menu needed no new
 * plumbing: with [open] set, that place shows this page instead.
 */
object AdvancedPage {
    var open by mutableStateOf(false)
    val item = FocusRequester()
}
