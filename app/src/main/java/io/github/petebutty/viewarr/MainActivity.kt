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
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.common.A11yTrim
import io.github.petebutty.viewarr.ui.common.HeldOk

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var themeStore: ThemeStore

    private var okDown = false
    private var okHeld = false
    private var okHoldEndedAt = 0L

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Holding OK on some boxes (Ugoos: the remote repeats the hold over infrared, and the box's remote service turns
        // an infrared OK hold into a Search key) opened voice search over the hold-OK menu (Peter, 2026-11-19). A Search /
        // Assist key that arrives while OK is down, or just after a hold, is the hold, not a search.
        val isOk = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) { okDown = true; if (event.repeatCount > 0) okHeld = true }
            else if (event.action == android.view.KeyEvent.ACTION_UP) { okDown = false; if (okHeld) okHoldEndedAt = android.os.SystemClock.uptimeMillis(); okHeld = false }
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH || event.keyCode == android.view.KeyEvent.KEYCODE_ASSIST || event.keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (okDown || android.os.SystemClock.uptimeMillis() - okHoldEndedAt < 1_500) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) android.util.Log.i("RemoteKeys", "dropped a ${android.view.KeyEvent.keyCodeToString(event.keyCode)} that came with an OK hold")
                return true
            }
        }
        // Holding OK opens things (record options, an episode's menu): the hold is decided on its first repeat, so the
        // release that ends it means nothing. It's still delivered, marked (HeldOk.releasePending): dropped here, a TV
        // Surface that had noted its long click never forgot it, and skipped the next tap's click (every row with a
        // hold-OK menu). Pop-ups ignore the release themselves (ignoreHeldOk); the guide forgets its OK on the repeat;
        // a handler acting on OK's release checks HeldOk.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
            when {
                event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> HeldOk.releasePending = false
                event.action == android.view.KeyEvent.ACTION_DOWN -> HeldOk.releasePending = true
                event.action == android.view.KeyEvent.ACTION_UP && HeldOk.releasePending -> {
                    val handled = super.dispatchKeyEvent(event)
                    HeldOk.releasePending = false
                    return handled
                }
            }
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            val host = VodHost
            // The rest of a hold that just sent the film to the corner.
            // (A fresh press, not a repeat of that hold, is never eaten: if the hold's release went missing, the flag
            // stayed set and swallowed the next Back.)
            if (host.swallowBack && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) host.swallowBack = false
            if (host.swallowBack) {
                if (event.action == android.view.KeyEvent.ACTION_UP) host.swallowBack = false
                return true
            }
            // (Holding Back with the film in the corner used to bring it full screen here. Since 0.28.3 a hold closes it
            // and a tap enlarges it, timed in HomeState.onRootKey: this fought that, flashing it full screen first.)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // viewarr://pause, play, playpause (Home Assistant, ADB): one press of that key, as from the remote.
        io.github.petebutty.viewarr.data.media.DeepLink.keySink = { code ->
            val t = android.os.SystemClock.uptimeMillis()
            dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
            dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0))
        }
        // (Only a fresh start: Android hands a restored app its original link again, which replayed it on every restart.)
        if (savedInstanceState == null) { DeepLink.handle(intent?.data); voiceRequest(intent) }
        lifecycleScope.launch { themeStore.palette.collect { AppColors.palette = it } }
        setContent {
            ViewarrTheme { AppRoot() }
        }
        // No screen reader on: skip Compose's screen-reader bookkeeping (a launcher's button-catching accessibility
        // service switched it on for everything). See A11yTrim.
        A11yTrim.install(this)
    }

    // Compose refreshes its accessibility state when its view attaches and the window gains focus: apply again after.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) A11yTrim.refresh(this)
    }

    /** Already running: a Watch Next card opens its title here; a viewarr://channel link changes channel. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        DeepLink.handle(intent.data)
        voiceRequest(intent)
    }

    /**
     * The remote's mic / search button, when Viewarr is the app chosen for voice search (see the manifest): Viewarr's
     * search opens listening, or searching for the words when the request already carries them.
     */
    private fun voiceRequest(intent: android.content.Intent?) {
        val action = intent?.action ?: return
        if (action != "android.speech.action.WEB_SEARCH" && action != android.content.Intent.ACTION_ASSIST &&
            action != android.content.Intent.ACTION_VOICE_COMMAND && action != android.content.Intent.ACTION_SEARCH_LONG_PRESS) return
        val words = intent.getStringExtra(android.app.SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        DeepLink.pendingCommand.value = if (words != null) DeepLink.Command("search", words) else DeepLink.Command("voice")
    }
}
