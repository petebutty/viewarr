package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
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
    fun setMapping(screen: io.github.petebutty.viewarr.data.settings.BtnScreen, press: io.github.petebutty.viewarr.data.settings.BtnPress, action: io.github.petebutty.viewarr.data.settings.BtnAction) = viewModelScope.launch {
        // The default is saved as "no change", so a later change of default reaches it.
        store.setMapping(io.github.petebutty.viewarr.data.settings.ButtonMap.key(screen, press), action.name.takeIf { action != io.github.petebutty.viewarr.data.settings.ButtonMap.default(screen, press) })
    }
    fun resetScreen(screen: io.github.petebutty.viewarr.data.settings.BtnScreen, keys: List<String>) = viewModelScope.launch {
        store.resetMappings(screen.name + ".")
        store.clear(keys)
    }
    fun resetButtons() = viewModelScope.launch { store.resetButtons() }
    fun setLiveShortcuts(ids: List<String>) = viewModelScope.launch { store.setLiveShortcuts(ids) }
    fun resetLiveShortcuts() = viewModelScope.launch { store.clear(listOf(RemoteStore.LIVE_SHORTCUTS)) }
}

/** On / Off for the Live TV shortcuts list. */
private data class OnOff(override val label: String) : RemoteChoice

/** A choice in the button table (the action's label). */
private data class ActionChoice(val action: io.github.petebutty.viewarr.data.settings.BtnAction) : RemoteChoice { override val label get() = action.label }

/**
 * A generic remote (our own drawing, no brand's), with what the main buttons do on one screen beside it. Shown at
 * the top of each screen's section in Settings → Remote & navigation; it follows the rows below as they change.
 */
@Composable
private fun RemotePicture(lines: List<Pair<String, String>>, colours: List<String>) {
    val ink = io.github.petebutty.viewarr.ui.theme.AppColors.TextSecondary
    val body = io.github.petebutty.viewarr.ui.theme.AppColors.Panel
    val accent = io.github.petebutty.viewarr.ui.theme.AppColors.Accent
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.padding(bottom = 10.dp).background(io.github.petebutty.viewarr.ui.theme.AppColors.PanelAlt.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.width(84.dp).height(230.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(body, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.45f))
            drawRoundRect(ink, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            // Power, then the arrows round OK, Back / Home / Menu, play/pause, the colour buttons, the numbers.
            drawCircle(ink, w * 0.06f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.06f))
            val c = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.24f)
            drawCircle(ink, w * 0.34f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            drawCircle(accent, w * 0.15f, c)
            listOf(0.25f, 0.5f, 0.75f).forEach { x -> drawCircle(ink, w * 0.07f, androidx.compose.ui.geometry.Offset(w * x, h * 0.42f)) }
            drawCircle(ink, w * 0.07f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.51f))
            val dots = listOf(androidx.compose.ui.graphics.Color(0xFFE53935), androidx.compose.ui.graphics.Color(0xFF43A047), androidx.compose.ui.graphics.Color(0xFFFDD835), androidx.compose.ui.graphics.Color(0xFF1E88E5))
            dots.forEachIndexed { i, col -> drawRoundRect(col, topLeft = androidx.compose.ui.geometry.Offset(w * (0.14f + i * 0.19f), h * 0.59f), size = androidx.compose.ui.geometry.Size(w * 0.14f, h * 0.025f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)) }
            for (r in 0..3) for (col in 0..2) drawCircle(ink.copy(alpha = 0.6f), w * 0.05f, androidx.compose.ui.geometry.Offset(w * (0.28f + col * 0.22f), h * (0.68f + r * 0.07f)))
        }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(20.dp))
        androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            lines.forEach { (k, v) ->
                androidx.compose.foundation.layout.Row {
                    androidx.tv.material3.Text(k, fontSize = 13.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.TextPrimary, modifier = androidx.compose.ui.Modifier.width(150.dp))
                    androidx.tv.material3.Text(v, fontSize = 13.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.TextSecondary)
                }
            }
            if (colours.isNotEmpty()) androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                val cs = listOf(0xFFE53935, 0xFF43A047, 0xFFFDD835, 0xFF1E88E5)
                colours.forEachIndexed { i, label ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(14.dp).height(6.dp).background(androidx.compose.ui.graphics.Color(cs[i]), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                        androidx.tv.material3.Text(" " + label, fontSize = 12.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.TextSecondary)
                    }
                }
            }
        }
    }
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
    // Live TV shortcuts: what can be one (the same lists the menu shows).
    val moviesM: io.github.petebutty.viewarr.ui.media.MediaViewModel = hiltViewModel(key = "media-Movie")
    val showsM: io.github.petebutty.viewarr.ui.media.MediaViewModel = hiltViewModel(key = "media-Show")
    val musicM: io.github.petebutty.viewarr.ui.music.MusicViewModel = hiltViewModel()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        moviesM.load(io.github.petebutty.viewarr.data.media.MediaType.Movie); moviesM.loadLibraries(io.github.petebutty.viewarr.data.media.MediaType.Movie)
        showsM.load(io.github.petebutty.viewarr.data.media.MediaType.Show); showsM.loadLibraries(io.github.petebutty.viewarr.data.media.MediaType.Show)
    }
    val mLibs by moviesM.libraries.collectAsStateWithLifecycle()
    val sLibs by showsM.libraries.collectAsStateWithLifecycle()
    val mRows by moviesM.menuRows.collectAsStateWithLifecycle()
    val sRows by showsM.menuRows.collectAsStateWithLifecycle()
    val musicOn by musicM.enabled.collectAsStateWithLifecycle()
    val candidates = io.github.petebutty.viewarr.ui.player.LiveShortcuts.candidates(mLibs.orEmpty(), sLibs.orEmpty(), mRows.rows, sRows.rows, musicOn)
    // Only ids that still exist (a library removed since drops out).
    val chosen = (s.liveShortcuts ?: io.github.petebutty.viewarr.ui.player.LiveShortcuts.defaults).filter { id -> candidates.any { it.id == id } }
    val onOffChoices = listOf(OnOff("On"), OnOff("Off"))
    // Chosen ones first, in their order (Right picks one up, Up / Down move it); then the rest, off.
    val shortcutItems = (chosen.mapNotNull { id -> candidates.firstOrNull { it.id == id } } + candidates.filter { it.id !in chosen }).map { sc ->
        val on = sc.id in chosen
        SettingItem(
            sc.label,
            if (on) "In the row: ${chosen.indexOf(sc.id) + 1} of ${chosen.size}  ·  Right to move it" else "Not in the row",
            onOffChoices, if (on) onOffChoices[0] else onOffChoices[1],
            reorder = if (!on) null else ({ delta ->
                val i = chosen.indexOf(sc.id)
                val j = (i + delta).coerceIn(0, chosen.lastIndex)
                if (i != j) vm.setLiveShortcuts(chosen.toMutableList().apply { add(j, removeAt(i)) })
            }),
        ) { c ->
            vm.setLiveShortcuts(if ((c as OnOff).label == "On") (chosen - sc.id) + sc.id else chosen - sc.id)
        }
    } + SettingItem("Reset shortcuts", "Back to TV guide, Movies, Shows, Music, Recordings, Search and Settings.", listOf(ButtonStatus("")), ButtonStatus(""), action = { vm.resetLiveShortcuts() }, onSelect = {})
    fun act(screen: io.github.petebutty.viewarr.data.settings.BtnScreen, press: io.github.petebutty.viewarr.data.settings.BtnPress) = io.github.petebutty.viewarr.data.settings.ButtonMap.action(s, screen, press).label
    // The colour buttons' actions for the remote picture (only screens that have them).
    fun colours(screen: io.github.petebutty.viewarr.data.settings.BtnScreen) = listOf(io.github.petebutty.viewarr.data.settings.BtnPress.Red, io.github.petebutty.viewarr.data.settings.BtnPress.Green, io.github.petebutty.viewarr.data.settings.BtnPress.Yellow, io.github.petebutty.viewarr.data.settings.BtnPress.Blue)
        .filter { it in io.github.petebutty.viewarr.data.settings.ButtonMap.presses[screen].orEmpty() }.map { act(screen, it) }
    fun tableRows(screen: io.github.petebutty.viewarr.data.settings.BtnScreen): List<SettingItem> = io.github.petebutty.viewarr.data.settings.ButtonMap.presses[screen].orEmpty().map { press ->
        val choices = io.github.petebutty.viewarr.data.settings.ButtonMap.actions[screen].orEmpty().map { ActionChoice(it) }
        SettingItem(press.label, press.group + (if (io.github.petebutty.viewarr.data.settings.ButtonMap.action(s, screen, press) == io.github.petebutty.viewarr.data.settings.ButtonMap.default(screen, press)) " · as standard" else " · changed"),
            choices, ActionChoice(io.github.petebutty.viewarr.data.settings.ButtonMap.action(s, screen, press))) { c -> vm.setMapping(screen, press, (c as ActionChoice).action) }
    }
    fun resetRow(screen: io.github.petebutty.viewarr.data.settings.BtnScreen, keys: List<String>) = SettingItem(
        "Reset these buttons", "Back to how Viewarr comes, for this screen only.",
        listOf(ButtonStatus("")), ButtonStatus(""), action = { vm.resetScreen(screen, keys) }, onSelect = {},
    )
    val categories = listOf(
        SettingCategory(
            "Start", "What you see when Viewarr opens.",
            listOf(
                item("Style", "Classic menus: as Viewarr has always worked. Live TV hub: Viewarr opens on your last channel, Back from any page (Movies, Shows, Music, Recordings…) goes back to it, and the shortcuts under the live TV controls (OK, then Down) take you anywhere.", k.NAV_STYLE, all<io.github.petebutty.viewarr.data.settings.NavStyle>(), s.navStyle),
                item("App opens on", "The page Viewarr starts on (the Live TV hub style always opens on your last channel). Last channel plays the channel you watched last, full screen.", k.START_PAGE, all<StartPage>(), s.startPage),
                item("Search button", "Pops search up from any screen (live TV, the guide, films and shows); Back closes it and you're where you were.", k.SEARCH_BUTTON, all<io.github.petebutty.viewarr.data.settings.SearchButton>(), s.searchButton),
            ),
        ),
        SettingCategory(
            "TV guide", "Buttons while browsing the guide: the main buttons first, then Record, the colour buttons and Info.",
            listOf(
                item("Left", "What Left does on the guide's programmes.", k.GUIDE_LEFT, all<GuideLeft>(), s.guideLeft),
                item("Back", "Back first jumps the guide to what's on now. On now: back to the channel full screen, ask to exit (press Back again), or open the menu.", k.GUIDE_BACK, all<GuideBack>(), s.guideBack),
                item("Channel + / −", "Pages through the channels. Reverse it if your remote's buttons feel the wrong way round.", k.CHANNEL_KEYS, all<ChannelKeys>(), s.channelKeys),
            ) + tableRows(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide) + resetRow(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, listOf(k.GUIDE_LEFT, k.GUIDE_BACK, k.CHANNEL_KEYS)),
            header = { RemotePicture(
                listOf("OK" to "Watch, or details for later", "▲ ▼" to "Channels", "◀ ▶" to s.guideLeft.label, "Back" to s.guideBack.label,
                    "Hold Back" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold), "Ch + / −" to s.channelKeys.label,
                    "Record" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, io.github.petebutty.viewarr.data.settings.BtnPress.Record)),
                colours(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide),
            ) },
        ),
        SettingCategory(
            "Live TV", "Buttons while a channel plays full screen: the main buttons first, then Record, the colour buttons and Info.",
            listOf(
                item("Up / Down", "Show the channel list to pick from, or change channel straight away.", k.ZAP, all<ZapDirection>(), s.zap),
                item("OK", "Open the playback controls, or just show the banner with what's on.", k.PLAYER_OK, all<PlayerOk>(), s.playerOk),
                item("Left / Right", "How far each press skips (catch-up, paused live TV; films and recordings too).", k.SEEK, all<SeekStep>(), s.seek),
            ) + tableRows(io.github.petebutty.viewarr.data.settings.BtnScreen.Live) + resetRow(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, listOf(k.ZAP, k.PLAYER_OK, k.SEEK)),
            header = { RemotePicture(
                listOf("OK" to s.playerOk.label, "▲ ▼" to s.zap.label, "◀ ▶" to s.seek.label, "Back" to "The guide, the channel in its preview",
                    "Hold Back" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold), "Ch + / −" to "Change channel",
                    "Record" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, io.github.petebutty.viewarr.data.settings.BtnPress.Record), "Info" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, io.github.petebutty.viewarr.data.settings.BtnPress.Info)),
                colours(io.github.petebutty.viewarr.data.settings.BtnScreen.Live),
            ) },
        ),
        SettingCategory(
            "Live TV shortcuts", "On live TV, OK then Down: a row of round icons to what you turn on here. To change the order, press Right on one, move it with Up / Down, then Right again.",
            shortcutItems,
        ),
        SettingCategory(
            "Films & shows", "Buttons while a film or episode plays. OK, the arrows and Back work as shown; the colour buttons and Info are yours to set.",
            tableRows(io.github.petebutty.viewarr.data.settings.BtnScreen.Film) + resetRow(io.github.petebutty.viewarr.data.settings.BtnScreen.Film, emptyList()),
            header = { RemotePicture(
                listOf("OK" to "Controls · hold: audio & subtitles", "◀ ▶" to s.seek.label, "▼" to "Seek bar with previews", "▲" to "Time and title",
                    "Back" to "Close what's open, then stop", "Play / Pause" to "Pause", "Info" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Film, io.github.petebutty.viewarr.data.settings.BtnPress.Info)),
                colours(io.github.petebutty.viewarr.data.settings.BtnScreen.Film),
            ) },
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
            ) + tableRows(io.github.petebutty.viewarr.data.settings.BtnScreen.Pages) + resetRow(io.github.petebutty.viewarr.data.settings.BtnScreen.Pages, listOf(k.ROW_LEFT, k.MEDIA_BACK)),
            header = { RemotePicture(listOf("OK" to "Open", "Hold OK" to "Quick menu (play, trailer, watchlist)", "◀ on the first poster" to s.rowLeft.label, "Back" to s.mediaBack.label, "Hold Back" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Pages, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold)), emptyList()) },
        ),
        SettingCategory(
            "Menu", "The side menu. Back steps out one level: from a list to the main menu, then closes the menu.",
            listOf(
                item("Menu opens on", "Open on the current page's own list (channel groups, catalogues), or on the main list.", k.MENU_OPENS_ON, all<MenuOpensOn>(), s.menuOpensOn),
                optItem(opts, "Button hints", "A small pill at the bottom of the screen saying what the buttons do there: the first few times on each screen, always, or off.", io.github.petebutty.viewarr.ui.theme.UiOpts.BUTTON_HINTS),
            ),
        ),
    )
    // Reset: the buttons only (not where the app opens, the menu or the poster rows).
    SettingsPage(title = "Remote & navigation", categories = categories, onBack = onBack, onReset = { vm.resetButtons() }, resetLabel = "Reset buttons")
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
