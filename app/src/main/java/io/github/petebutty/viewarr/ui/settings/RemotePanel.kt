package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.settings.ChannelKeys
import io.github.petebutty.viewarr.data.settings.GuideBack
import io.github.petebutty.viewarr.data.settings.GuideLeft
import io.github.petebutty.viewarr.data.settings.MediaBack
import io.github.petebutty.viewarr.data.settings.MenuOpensOn
import io.github.petebutty.viewarr.data.settings.PlayerOk
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.data.settings.RemoteStore
import io.github.petebutty.viewarr.data.settings.RowAlign
import io.github.petebutty.viewarr.data.settings.RowLeft
import io.github.petebutty.viewarr.data.settings.RowMemory
import io.github.petebutty.viewarr.data.settings.SeekStep
import io.github.petebutty.viewarr.data.settings.StartPage
import io.github.petebutty.viewarr.data.settings.ZapDirection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemoteViewModel @Inject constructor(private val store: RemoteStore) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteSettings())

    fun set(key: String, value: RemoteChoice) = viewModelScope.launch { store.set(key, value as Enum<*>) }
    fun reset() = viewModelScope.launch { store.reset() }
    fun setSearchKey(code: Int) = viewModelScope.launch { store.setSearchKeyCode(code) }
    fun setButton(action: String, code: Int?) = viewModelScope.launch { store.setButton(action, code) }
}

/** A status row's text (Buttons while watching). */
private data class ButtonStatus(override val label: String) : RemoteChoice

/** The button(s) each action has out of the box, as shown in Settings. */
private fun defaultButtons(a: io.github.petebutty.viewarr.ui.common.RemoteAction): String = when (a) {
    io.github.petebutty.viewarr.ui.common.RemoteAction.PlayPause -> "Play/Pause"
    io.github.petebutty.viewarr.ui.common.RemoteAction.Rewind -> "⏪"
    io.github.petebutty.viewarr.ui.common.RemoteAction.FastForward -> "⏩"
    io.github.petebutty.viewarr.ui.common.RemoteAction.Stop -> "Stop"
    io.github.petebutty.viewarr.ui.common.RemoteAction.Info -> "Info"
    io.github.petebutty.viewarr.ui.common.RemoteAction.Menu -> "≡ (tap)"
    io.github.petebutty.viewarr.ui.common.RemoteAction.ChannelUp -> "Channel +"
    io.github.petebutty.viewarr.ui.common.RemoteAction.ChannelDown -> "Channel −"
    io.github.petebutty.viewarr.ui.common.RemoteAction.Guide -> "Guide"
    io.github.petebutty.viewarr.ui.common.RemoteAction.Record -> "Record"
    else -> ""
}

/** The key code that opens search now (Settings → Remote → Search button), so it isn't taught to something else. */
private fun searchCode(s: RemoteSettings): Int? = when (s.searchButton) {
    io.github.petebutty.viewarr.data.settings.SearchButton.Auto -> android.view.KeyEvent.KEYCODE_SEARCH
    io.github.petebutty.viewarr.data.settings.SearchButton.Red -> android.view.KeyEvent.KEYCODE_PROG_RED
    io.github.petebutty.viewarr.data.settings.SearchButton.Green -> android.view.KeyEvent.KEYCODE_PROG_GREEN
    io.github.petebutty.viewarr.data.settings.SearchButton.Yellow -> android.view.KeyEvent.KEYCODE_PROG_YELLOW
    io.github.petebutty.viewarr.data.settings.SearchButton.Blue -> android.view.KeyEvent.KEYCODE_PROG_BLUE
    io.github.petebutty.viewarr.data.settings.SearchButton.Info -> android.view.KeyEvent.KEYCODE_INFO
    io.github.petebutty.viewarr.data.settings.SearchButton.Chosen -> s.searchKeyCode
    io.github.petebutty.viewarr.data.settings.SearchButton.Off -> null
}

private inline fun <reified E> all(): List<RemoteChoice> where E : Enum<E>, E : RemoteChoice = enumValues<E>().toList()

/** Settings → Remote & navigation, full screen like Playback: what each button does, grouped by where you are. */
@Composable
fun RemoteSettingsScreen(onBack: () -> Unit, vm: RemoteViewModel = hiltViewModel(), opts: UiOptsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    // Buttons while watching: the action whose extra button is being taught.
    var teaching by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<io.github.petebutty.viewarr.ui.common.RemoteAction, String>?>(null) }
    val buttonItems = io.github.petebutty.viewarr.ui.common.RemoteKeys.teachable.map { (a, name) ->
        val extra = s.buttons[a.name]
        val status = defaultButtons(a) + (extra?.let { " + " + io.github.petebutty.viewarr.ui.common.RemoteKeys.buttonName(it) } ?: "")
        SettingItem(name, if (extra == null) "OK to add a button for this, e.g. a colour button or one your remote has spare." else "OK to change or remove the extra button.",
            listOf(ButtonStatus(status)), ButtonStatus(status), action = { teaching = a to name }, onSelect = {})
    }
    // "A button you choose": the next button pressed becomes the search button.
    var learning by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun item(title: String, description: String, key: String, choices: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, choices, current) {
            if (it == io.github.petebutty.viewarr.data.settings.SearchButton.Chosen) learning = true else vm.set(key, it)
        }
    val k = RemoteStore.Companion
    val categories = listOf(
        SettingCategory(
            "Start", "What you see when Viewarr opens.",
            listOf(
                item("App opens on", "The page Viewarr starts on. Last channel plays the channel you watched last, full screen.", k.START_PAGE, all<StartPage>(), s.startPage),
                item("Search button", "Pops search up from any screen (live TV, the guide, films and shows); Back closes it and you're where you were.", k.SEARCH_BUTTON, all<io.github.petebutty.viewarr.data.settings.SearchButton>(), s.searchButton),
            ),
        ),
        SettingCategory(
            "TV guide", "Buttons while browsing the guide.",
            listOf(
                item("Left", "What Left does on the guide's programmes. Holding Left (or ⏪) on what's on now goes back into catch-up.", k.GUIDE_LEFT, all<GuideLeft>(), s.guideLeft),
                item("Back", "Back first jumps the guide to what's on now. On now, it asks to exit (press Back again), or opens the menu.", k.GUIDE_BACK, all<GuideBack>(), s.guideBack),
                item("Channel + / −", "Pages through the channels. Reverse it if your remote's buttons feel the wrong way round.", k.CHANNEL_KEYS, all<ChannelKeys>(), s.channelKeys),
            ),
        ),
        SettingCategory(
            "Watching", "Buttons while live TV or a film is playing.",
            listOf(
                item("Up / Down on live TV", "Show the channel list to pick from, or change channel straight away.", k.ZAP, all<ZapDirection>(), s.zap),
                item("Left / Right", "How far each press skips in films, shows, recordings and catch-up.", k.SEEK, all<SeekStep>(), s.seek),
                item("OK on live TV", "Open the playback controls, or just show the banner with what's on.", k.PLAYER_OK, all<PlayerOk>(), s.playerOk),
                optItem(opts, "Back while watching", "Once the controls are closed. Recommended: Back stops the film and holding Back shrinks it to a corner, where it keeps playing while you browse (hold Back again for full screen). Or Back always shrinks it, or always stops it.", io.github.petebutty.viewarr.ui.theme.UiOpts.PLAYER_BACK),
            ),
        ),
        SettingCategory(
            "Buttons while watching", "Give an action an extra button: a colour button, or one your remote has spare. It works on live TV, films and in the guide. OK, Back, the arrows and the numbers stay as they are.",
            buttonItems,
        ),
        SettingCategory(
            "Movies & Shows", "Moving around the poster rows.",
            listOf(
                item("Moving between rows", "Going back to a row lands on the poster you were on, or always on its first poster.", k.ROW_MEMORY, all<RowMemory>(), s.rowMemory),
                item("Poster position", "Keep the highlighted poster at the left edge of the row, or scroll only when it would go off screen.", k.ROW_ALIGN, all<RowAlign>(), s.rowAlign),
                item("Left on the first poster", "Open the catalogue list (Left again for the main menu), or do nothing.", k.ROW_LEFT, all<RowLeft>(), s.rowLeft),
                item("Back", "Back on Movies or Shows asks to exit (press Back again), opens the catalogue list, or goes to the TV guide.", k.MEDIA_BACK, all<MediaBack>(), s.mediaBack),
            ),
        ),
        SettingCategory(
            "Menu", "The side menu. Back steps out one level: from a list to the main menu, then closes the menu.",
            listOf(
                item("Menu opens on", "Open on the current page's own list (channel groups, catalogues), or on the main list.", k.MENU_OPENS_ON, all<MenuOpensOn>(), s.menuOpensOn),
            ),
        ),
    )
    SettingsPage(title = "Remote & navigation", categories = categories, onBack = onBack, onReset = { vm.reset(); opts.reset(listOf(io.github.petebutty.viewarr.ui.theme.UiOpts.PLAYER_BACK)) })
    if (learning) LearnButtonDialog(onKey = { code -> vm.setSearchKey(code); learning = false }, onCancel = { learning = false })
    teaching?.let { (a, name) ->
        TeachButtonDialog(
            name = name,
            current = s.buttons[a.name],
            search = searchCode(s),
            takenBy = { code -> s.buttons.entries.firstOrNull { it.value == code && it.key != a.name }?.key
                ?.let { n -> io.github.petebutty.viewarr.ui.common.RemoteKeys.teachable.firstOrNull { it.first.name == n }?.second } },
            onKey = { code -> vm.setButton(a.name, code); teaching = null },
            onRemove = { vm.setButton(a.name, null); teaching = null },
            onCancel = { teaching = null },
        )
    }
}

/**
 * "Press the button for <action>": any button except the protected ones (and the search button). OK removes the
 * extra button, Back cancels. A button another action had moves here (it says so first).
 */
@androidx.compose.runtime.Composable
private fun TeachButtonDialog(
    name: String,
    current: Int?,
    search: Int?,
    takenBy: (Int) -> String?,
    onKey: (Int) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        val f = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { f.requestFocus() } }
        var note by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        // A button another action has: press it again to move it here.
        var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.width(560.dp)
                .background(io.github.petebutty.viewarr.ui.theme.AppColors.Panel, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .padding(26.dp)
                .focusRequester(f).focusable()
                .onPreviewKeyEvent { e ->
                    // Act on the press; its release (and repeats) do nothing.
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                    val code = e.nativeKeyEvent.keyCode
                    when {
                        code == android.view.KeyEvent.KEYCODE_BACK -> onCancel()
                        code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                            if (current != null) onRemove() else note = "OK, Back, the arrows and the numbers can't be used. Press another button."
                        io.github.petebutty.viewarr.ui.common.RemoteKeys.isProtected(code) -> note = "That button is needed for moving around (or by the TV). Try another one."
                        code == search -> note = "That button opens search (Settings → Remote → Search button). Choose another, or change the search button first."
                        code == current -> onCancel()
                        takenBy(code) != null && confirm != code -> { confirm = code; note = "${io.github.petebutty.viewarr.ui.common.RemoteKeys.buttonName(code)} is the extra button for ${takenBy(code)}. Press it again to use it for $name instead." }
                        else -> onKey(code)
                    }
                    true
                },
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            androidx.tv.material3.Text("Press a button for $name", fontSize = 20.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.TextPrimary)
            androidx.tv.material3.Text(
                (if (current != null) "Now: ${io.github.petebutty.viewarr.ui.common.RemoteKeys.buttonName(current)}. Press another button to change it, OK to remove it, or Back to keep it."
                else "Press the button on your remote you'd like for $name, for example a colour button. Back cancels."),
                fontSize = 14.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.TextSecondary,
            )
            note?.let { androidx.tv.material3.Text(it, fontSize = 14.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.Accent) }
        }
    }
}

/** "Press the button you want for search": any button except OK, the arrows, Back and Home. */
@androidx.compose.runtime.Composable
private fun LearnButtonDialog(onKey: (Int) -> Unit, onCancel: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        val f = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { f.requestFocus() } }
        var note by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        val reserved = setOf(
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_HOME, android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
            android.view.KeyEvent.KEYCODE_VOLUME_MUTE, android.view.KeyEvent.KEYCODE_POWER,
        )
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.width(520.dp)
                .background(io.github.petebutty.viewarr.ui.theme.AppColors.Panel, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .padding(26.dp)
                .focusRequester(f).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    val code = e.nativeKeyEvent.keyCode
                    when {
                        code == android.view.KeyEvent.KEYCODE_BACK -> onCancel()
                        code in reserved -> note = "That button is needed for moving around. Try another one."
                        else -> onKey(code)
                    }
                    true
                },
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            androidx.tv.material3.Text("Press the button for search", fontSize = 20.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.TextPrimary)
            androidx.tv.material3.Text(
                "Press the button on your remote you'd like to open search with (for example ≡ or a colour button). Back cancels.",
                fontSize = 14.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.TextSecondary,
            )
            note?.let { androidx.tv.material3.Text(it, fontSize = 14.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.Accent) }
        }
    }
}
