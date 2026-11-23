package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.petebutty.viewarr.ui.media.VodHost

/**
 * Every pop-up that sits over a page (record options, programme info, confirmations, a title's hold-OK menu, Audio &
 * subtitles, Sort / Filter…) goes through here: its own window, so Android hands it the remote outright. Drawn in the
 * main window, a pop-up competed with the page for focus, and lost to pages that fence focus in (trapFocus), leaving
 * the remote behind it. Back closes it ([onClose]); when it closes, the remote goes back to what had it before (the
 * page's focus is untouched while it's open). Draw the pop-up's own dimmed backdrop and panel as [content]: the
 * window adds no dimming of its own.
 */
@Composable
fun ViewarrPopup(onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // (The pop-ups draw their own backdrop; the window's grey dim on top of it made everything muddy.)
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        // (OK ignored until a fresh press: the hold that opened it, or its release, mustn't pick a choice.)
        Box(Modifier.fillMaxSize().ignoreHeldOk().onPreviewKeyEvent { e ->
            // Over a full-screen film: hold Back still stops it (the pop-up's own window had the press).
            val film = VodHost
            if (e.key == Key.Back && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 && film.active && !film.mini) {
                onClose(); film.stopHeld(); true
            } else false
        }, content = content)
    }
}
