package io.github.petebutty.viewarr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import io.github.petebutty.viewarr.data.settings.ThemeStore
import io.github.petebutty.viewarr.ui.AppRoot
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.ViewarrTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var themeStore: ThemeStore

    /** The rest of the held Back that brought a film back full screen. */
    private var pipHold = false

    /**
     * Holding Back with a film in the corner brings it back full screen, handled here, before any screen sees the
     * press: right after the film shrank nothing on screen had the remote yet, so the first hold went nowhere.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            val host = io.github.petebutty.viewarr.ui.media.VodHost
            // The rest of a hold that just sent the film to the corner.
            if (host.swallowBack) {
                if (event.action == android.view.KeyEvent.ACTION_UP) host.swallowBack = false
                return true
            }
            if (pipHold) {
                if (event.action == android.view.KeyEvent.ACTION_UP) pipHold = false
                return true
            }
            if (host.mini && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
                pipHold = true
                host.expand()
                // This press is swallowed here, so the player mustn't also eat the next Back.
                host.eatBackRelease = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        io.github.petebutty.viewarr.data.media.DeepLink.parse(intent?.data)?.let { io.github.petebutty.viewarr.data.media.DeepLink.pending.value = it }
        lifecycleScope.launch { themeStore.palette.collect { AppColors.palette = it } }
        setContent {
            ViewarrTheme { AppRoot() }
        }
    }

    /** Already running: a Watch Next card on the home screen opens its title here. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        io.github.petebutty.viewarr.data.media.DeepLink.parse(intent.data)?.let { io.github.petebutty.viewarr.data.media.DeepLink.pending.value = it }
    }
}
