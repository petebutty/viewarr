package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A settings page asking for another one to open over it (the menu's settings list opens it). */
object SettingsJump {
    /** Settings → Sports (follow a team or league), e.g. from the Sports page. */
    var sports by mutableStateOf(false)
    /** Settings → Live TV → Reports (ui/report/ReportsScreen), over everything. */
    var reports by mutableStateOf(false)
}
