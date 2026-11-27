package io.github.petebutty.viewarr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Whether the app is on screen (MainActivity started). Previewarr Mobile's repeating jobs (the guide's refresh, the
 * recordings check, sports, house sync…) wait while it isn't, so a phone isn't kept busy in a pocket: a TV is always
 * plugged in, a phone isn't (2026-11-27). The TV apps never wait.
 */
object AppVisible {
    // (Previewarr Mobile starts "off screen": a background job starting the app does nothing until it's opened.)
    val visible = MutableStateFlow(!BuildConfig.MOBILE)

    /** Previewarr Mobile: back once the app is on screen again (at once if it is). The TV apps: at once. */
    suspend fun await() {
        if (!BuildConfig.MOBILE) return
        visible.first { it }
    }
}
