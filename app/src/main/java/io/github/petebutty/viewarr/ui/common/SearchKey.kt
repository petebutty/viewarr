package io.github.petebutty.viewarr.ui.common

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.data.settings.SearchButton

/** Settings → Remote → Search button: whether this key press (down) is it. Pages and the film player share it. */
object SearchKey {
    fun hit(e: KeyEvent, remote: RemoteSettings): Boolean {
        val code = e.nativeKeyEvent.keyCode
        return when (remote.searchButton) {
            SearchButton.Auto -> code == android.view.KeyEvent.KEYCODE_SEARCH || (e.key == Key.Menu && e.nativeKeyEvent.repeatCount == 1)
            SearchButton.Red -> code == android.view.KeyEvent.KEYCODE_PROG_RED
            SearchButton.Green -> code == android.view.KeyEvent.KEYCODE_PROG_GREEN
            SearchButton.Yellow -> code == android.view.KeyEvent.KEYCODE_PROG_YELLOW
            SearchButton.Blue -> code == android.view.KeyEvent.KEYCODE_PROG_BLUE
            SearchButton.Info -> code == android.view.KeyEvent.KEYCODE_INFO
            SearchButton.Chosen -> remote.searchKeyCode != null && code == remote.searchKeyCode
            SearchButton.Off -> false
        }
    }
}
