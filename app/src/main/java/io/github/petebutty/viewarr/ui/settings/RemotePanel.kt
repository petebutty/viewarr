package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.common.touchFocusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.petebutty.viewarr.data.settings.MediaBack
import io.github.petebutty.viewarr.data.settings.MenuBack
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.data.settings.RemoteStore
import io.github.petebutty.viewarr.data.settings.RowAlign
import io.github.petebutty.viewarr.data.settings.RowMemory
import io.github.petebutty.viewarr.data.settings.SeekStep
import io.github.petebutty.viewarr.data.settings.StartPage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.petebutty.viewarr.data.settings.BtnAction
import io.github.petebutty.viewarr.data.settings.BtnPress
import io.github.petebutty.viewarr.data.settings.ButtonMap
import io.github.petebutty.viewarr.data.settings.ControlPreset
import io.github.petebutty.viewarr.data.settings.GuideBack
import io.github.petebutty.viewarr.data.settings.GuideBackNow
import io.github.petebutty.viewarr.data.settings.SearchButton
import io.github.petebutty.viewarr.ui.common.RemoteAction
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.ui.common.glass

@HiltViewModel
class RemoteViewModel @Inject constructor(private val store: RemoteStore) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteSettings())

    fun set(key: String, value: RemoteChoice) = viewModelScope.launch { store.set(key, value as Enum<*>) }
    fun setSearchKey(code: Int) = viewModelScope.launch { store.setSearchKeyCode(code) }
    fun setButton(action: String, code: Int?) = viewModelScope.launch { store.setButton(action, code) }
    fun setMapping(screen: io.github.petebutty.viewarr.data.settings.BtnScreen, press: BtnPress, action: BtnAction) = viewModelScope.launch {
        val map = ButtonMap
        // The default is saved as "no change", so a later change of default reaches it.
        store.setMappings(mapOf(map.key(screen, press) to action.name.takeIf { action != map.default(settings.value, screen, press) }))
    }
    fun resetButtons() = viewModelScope.launch { store.resetButtons() }
    fun applyPreset(p: ControlPreset) = viewModelScope.launch { store.applyPreset(p) }
}

/** A button's drop-down choice: an action. */
private data class ActionChoice(val action: BtnAction) : RemoteChoice { override val label get() = action.label }

/** The button(s) each action has out of the box, as shown in Settings. */
private fun defaultButtons(a: RemoteAction): String = when (a) {
    RemoteAction.PlayPause -> "Play/Pause"
    RemoteAction.Rewind -> "⏪"
    RemoteAction.FastForward -> "⏩"
    RemoteAction.Stop -> "Stop"
    RemoteAction.Info -> "Info"
    RemoteAction.Menu -> "≡ (tap)"
    RemoteAction.ChannelUp -> "Channel +"
    RemoteAction.ChannelDown -> "Channel −"
    RemoteAction.Guide -> "Guide"
    RemoteAction.Record -> "Record"
    else -> ""
}

private fun searchCode(s: RemoteSettings): Int? = ButtonMap.searchCode(s)

private inline fun <reified E> all(): List<RemoteChoice> where E : Enum<E>, E : RemoteChoice = enumValues<E>().toList()

/**
 * Settings → Remote buttons (what each button does, grouped by screen), full screen like Playback. (Navigation's settings are laid out by SettingsMap from [navigationCategories].)
 */
@Composable
fun RemoteSettingsScreen(onBack: () -> Unit, vm: RemoteViewModel = hiltViewModel(), opts: UiOptsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    // Buttons while watching: the action whose extra button is being taught.
    var teaching by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<RemoteAction, String>?>(null) }
    val buttonItems = io.github.petebutty.viewarr.ui.common.RemoteKeys.teachable.map { (a, name) ->
        val extra = s.buttons[a.name]
        val status = defaultButtons(a) + (extra?.let { " + " + io.github.petebutty.viewarr.ui.common.RemoteKeys.buttonName(it) } ?: "")
        SettingItem(name, if (extra == null) "OK: add a button for this, e.g. a colour button or one your remote has spare." else "OK: change or remove the extra button.",
            listOf(PlainChoice(status)), PlainChoice(status), action = { teaching = a to name }, onSelect = {})
    }
    val toastCtx = androidx.compose.ui.platform.LocalContext.current
    fun freeForSearch(code: Int?) {
        val taken = s.buttons.entries.firstOrNull { it.value == code }?.key ?: return
        vm.setButton(taken, null)
        val name = io.github.petebutty.viewarr.ui.common.RemoteKeys.teachable.firstOrNull { it.first.name == taken }?.second ?: taken
        android.widget.Toast.makeText(toastCtx, "That button was the extra button for $name. It opens search now.", android.widget.Toast.LENGTH_LONG).show()
    }
    // "A button you choose": the next button pressed becomes the search button.
    var learning by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun item(title: String, description: String, key: String, choices: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, choices, current) {
            if (it == SearchButton.Chosen) learning = true
            else {
                vm.set(key, it)
                // A Search button that was an extra button for something stops being that (search is checked first).
                if (key == RemoteStore.SEARCH_BUTTON) freeForSearch(searchCode(s.copy(searchButton = it as SearchButton)))
            }
        }
    val k = RemoteStore.Companion
    val searchItem = item("Search button", "Pops search up from any screen (live TV, the guide, films and shows); Back closes it and you're where you were.", k.SEARCH_BUTTON, all<SearchButton>(), s.searchButton)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val preset = io.github.petebutty.viewarr.data.settings.Presets.matching(s)
    fun presetRow(p: ControlPreset, title: String, about: String): SettingItem {
        val status = PlainChoice(if (preset == p) "✓ In use" else if (preset == ControlPreset.Custom && s.presetBase == p) "Changed" else "")
        return SettingItem(title, about, listOf(status), status, action = {
            vm.applyPreset(p)
            android.widget.Toast.makeText(ctx, "$title: every button set, Search and extra buttons back to standard", android.widget.Toast.LENGTH_SHORT).show()
        }, onSelect = {})
    }
    val shareRow = SettingItem("Share this button setup", "Writes every button to the app's log (for building a preset from it).",
        listOf(PlainChoice("")), PlainChoice(""), action = {
            val map = ButtonMap
            val lines = listOf("guideLeft=${s.guideLeft.name}", "guideBack=${s.guideBack.name}", "channelKeys=${s.channelKeys.name}",
                "zap=${s.zap.name}", "playerOk=${s.playerOk.name}", "liveSides=${s.liveSides.name}", "seek=${s.seek.name}",
                "rowLeft=${s.rowLeft.name}", "mediaBack=${s.mediaBack.name}", "menuOpensOn=${s.menuOpensOn.name}", "menuBack=${s.menuBack.name}") +
                map.presses.flatMap { (screen, presses) -> presses.map { "${map.key(screen, it)}=${map.action(s, screen, it).name}" } }
            lines.forEach { android.util.Log.i("ViewarrButtons", it) }
            android.widget.Toast.makeText(ctx, "Button setup written to the log", android.widget.Toast.LENGTH_SHORT).show()
        }, onSelect = {})
    // By button (as classic IPTV apps): every button on the remote, each with what it does in every part of Viewarr.
    val bm = ButtonMap
    val places = listOf(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide to "In the TV guide", io.github.petebutty.viewarr.data.settings.BtnScreen.Live to "On live TV", io.github.petebutty.viewarr.data.settings.BtnScreen.Film to "In films & shows", io.github.petebutty.viewarr.data.settings.BtnScreen.Pages to "In menus & pages")
    fun placeRow(screen: io.github.petebutty.viewarr.data.settings.BtnScreen, press: BtnPress, title: String): SettingItem {
        // A button that's the Search button, or an extra button for something else, does that everywhere: no choices
        // here that would never run.
        bm.claimedBy(s, press)?.let { by ->
            val status = PlainChoice(by)
            val why = if (by == "Search") "Opens search: it's your Search button (Other buttons, below). Pick another Search button to use this one here."
                else "It's the extra button for $by (Other buttons, below). Remove it there to use this one here."
            return SettingItem(title, why, listOf(status), status, action = {}, onSelect = {})
        }
        val locked = if (press == BtnPress.Ok) bm.okLocked(s, screen) else null
        return SettingItem(title, "", bm.actions[screen].orEmpty().filter { bm.allowed(screen, press, it) }.map { ActionChoice(it) }, ActionChoice(bm.action(s, screen, press))) { c ->
            val a = (c as ActionChoice).action
            // OK is the only button doing the screen's core job: it keeps it until another button is given it (the change
            // isn't saved either, so it can't turn up later).
            val refused = locked != null && a != bm.default(s, screen, press) &&
                bm.action(s.copy(buttonMap = s.buttonMap + (bm.key(screen, press) to a.name)), screen, press) != a
            if (!refused) vm.setMapping(screen, press, a)
            else android.widget.Toast.makeText(toastCtx, "OK is the only button for $locked here, so it keeps that. Give Hold OK, an arrow or Menu ${if (screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Guide) "Watch" else "Controls"} first, then change OK.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val perButton = BtnPress.entries.mapNotNull { press ->
        val rows = buildList {
            places.forEach { (screen, place) ->
                when {
                    // Back and Left step through the guide and pages first, so they have their own choices there.
                    screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Guide && press == BtnPress.Back -> {
                        add(item("In the TV guide: jump to now first", if (io.github.petebutty.viewarr.BuildConfig.LAUNCHER) "Scrolled away from what's on now, Back first comes back to now, and the next Back goes home. Off: Back goes home straight away." else "Scrolled away from what's on now, Back first comes back to now, and the next Back goes full screen (something playing) or does what's set below. Off: Back does its job straight away.", k.GUIDE_BACK_NOW, all<GuideBackNow>(), s.guideBackNow))
                        // (Previewarr: Back in the guide is always home, no main menu to open; no choice here.)
                        if (!io.github.petebutty.viewarr.BuildConfig.LAUNCHER) add(item(place, "With a channel or film playing, Back always goes back to it full screen. With nothing playing: ask to exit, or open the menu. (Ask to exit needs another way to the menu, such as Menu set to Main menu; without one, Back opens the menu.)", k.GUIDE_BACK, listOf(GuideBack.NowThenExit, GuideBack.Menu), s.guideBack))
                    }
                    // (Left in the guide and on the first poster work one way since 0.28.3: no choice to make.)
                    screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Guide && press == BtnPress.Left -> Unit
                    screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Pages && press == BtnPress.Back -> {
                        add(item(place, "Ask to exit, open the menu, or go to the TV guide.", k.MEDIA_BACK, all<MediaBack>(), s.mediaBack))
                        add(item("In the menu", "Press twice to exit, or close the menu.", k.MENU_BACK, all<MenuBack>(), s.menuBack))
                    }
                    screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Pages && press == BtnPress.Left -> Unit
                    press in bm.presses[screen].orEmpty() -> add(placeRow(screen, press, place))
                }
            }
        }
        if (rows.isEmpty()) null
        else SettingCategory(press.label, "What ${press.label} does in each part of Viewarr. Pick one for each; a preset above sets them all at once.", rows)
    }
    val buttonCategories = listOf(
        SettingCategory(
            "Presets", "Set every button at once, then change any you like: each button below lists what it does in the TV guide, on live TV, in films and in menus.",
            listOfNotNull(
                // (Previewarr: one preset, Classic; its buttons can still each be changed below. 2026-11-25.)
                if (io.github.petebutty.viewarr.BuildConfig.LAUNCHER) null else presetRow(ControlPreset.Viewarr, "Use Viewarr buttons",
                    "The Classic buttons with a few changes. The guide: Left carries on back into catch-up (no hold), Back jumps to now then the menu, colour buttons: record, groups, a day back, a day forward. Live TV: Up / Down change channel, OK the controls, Left / Right rewind and skip, hold Play: go live. Search and extra buttons go back to standard too."),
                presetRow(ControlPreset.Classic, "Use Classic buttons",
                    "The layout most IPTV apps use. Live TV: Up / Down change channel, OK the controls, Left the channel list, Right the last channel, Menu the main menu. The guide: Left back a programme (on now: the channel groups); Hold OK: record options; Back: back to the player. Search and extra buttons go back to standard too."),
                shareRow,
            ),
        ),
    ) + perButton + listOf(
        SettingCategory(
            "Other buttons", "Skipping, changing channel quickly, the search button, and spare buttons your remote has.",
            listOf(
                item("Skip size", "How far each skip goes (catch-up, paused live TV, films and recordings).", k.SEEK, all<SeekStep>(), s.seek),
                optSlider(opts, "Channel change delay", "Zapping through channels: how long one has to stay chosen before Viewarr asks for its stream (Left / Right, a tenth of a second at a time), so passing quickly over channels doesn't use up your provider's connections. No wait: every channel starts at once.", UiOpts.ZAP_DELAY, AppColors.palette.zapDelayMs.toInt() / 100),
                searchItem,
            ) + buttonItems,
        ),
    )
    // Reset: the buttons only (not where the app opens, the menu or the poster rows).
    SettingsPage(title = "Remote buttons", allCategories = buttonCategories, onBack = onBack, onReset = { vm.resetButtons() }, resetLabel = "Reset buttons")
    if (learning) LearnButtonDialog(onKey = { code -> freeForSearch(code); vm.setSearchKey(code); learning = false }, onCancel = { learning = false })
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
    // (Through ViewarrPopup, the shared pop-up window, with its glass and radius: was a raw Dialog. 2026-11-17.)
    io.github.petebutty.viewarr.ui.common.ViewarrPopup(onClose = onCancel) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)))
        val f = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { f.requestFocus() } }
        var note by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        // A button another action has: press it again to move it here.
        var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Center).width(io.github.petebutty.viewarr.ui.theme.Tokens.PopupWide)
                .glass(io.github.petebutty.viewarr.ui.theme.Tokens.PopupRadius)
                .padding(26.dp)
                .focusRequester(f).touchFocusable()
                .onPreviewKeyEvent { e ->
                    // Act on the press; its release (and repeats) do nothing.
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                    val code = e.nativeKeyEvent.keyCode
                    when {
                        code == android.view.KeyEvent.KEYCODE_BACK -> onCancel()
                        code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                            if (current != null) onRemove() else note = "OK, Back, the arrows and the numbers can't be used. Press another button."
                        io.github.petebutty.viewarr.ui.common.RemoteKeys.isProtected(code) -> note = "That button is needed for moving around (or by the TV). Try another one."
                        io.github.petebutty.viewarr.ui.common.RemoteKeys.isCore(code) -> note = "Play / pause and the channel buttons keep their own jobs. Try a spare button, like a colour one."
                        code == search -> note = "That button opens search (Remote buttons → Other buttons → Search button). Choose another, or change the search button first."
                        code == current -> onCancel()
                        takenBy(code) != null && confirm != code -> { confirm = code; note = "${io.github.petebutty.viewarr.ui.common.RemoteKeys.buttonName(code)} is the extra button for ${takenBy(code)}. Press it again to use it for $name instead." }
                        else -> onKey(code)
                    }
                    true
                },
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            androidx.tv.material3.Text("Press a button for $name", fontSize = 20.sp, color = AppColors.TextPrimary)
            androidx.tv.material3.Text(
                (if (current != null) "Now: ${io.github.petebutty.viewarr.ui.common.RemoteKeys.buttonName(current)}. Press another button to change it, OK to remove it, or Back to keep it."
                else "Press the button on your remote you'd like for $name, for example a colour button. Back cancels."),
                fontSize = 14.sp, color = AppColors.TextSecondary,
            )
            note?.let { androidx.tv.material3.Text(it, fontSize = 14.sp, color = AppColors.Accent) }
        }
    }
}

/** "Press the button you want for search": any button except OK, the arrows, Back and Home. */
@androidx.compose.runtime.Composable
private fun LearnButtonDialog(onKey: (Int) -> Unit, onCancel: () -> Unit) {
    // (Through ViewarrPopup, the shared pop-up window, with its glass and radius: was a raw Dialog. 2026-11-17.)
    io.github.petebutty.viewarr.ui.common.ViewarrPopup(onClose = onCancel) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)))
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
            androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Center).width(io.github.petebutty.viewarr.ui.theme.Tokens.PopupWide)
                .glass(io.github.petebutty.viewarr.ui.theme.Tokens.PopupRadius)
                .padding(26.dp)
                .focusRequester(f).touchFocusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    val code = e.nativeKeyEvent.keyCode
                    when {
                        code == android.view.KeyEvent.KEYCODE_BACK -> onCancel()
                        io.github.petebutty.viewarr.ui.common.RemoteKeys.isCore(code) -> note = "Play / pause and the channel buttons keep their own jobs. Try another one."
                        code in reserved || io.github.petebutty.viewarr.ui.common.RemoteKeys.isProtected(code) -> note = "That button is needed for moving around. Try another one."
                        else -> onKey(code)
                    }
                    true
                },
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            androidx.tv.material3.Text("Press the button for search", fontSize = 20.sp, color = AppColors.TextPrimary)
            androidx.tv.material3.Text(
                "Press the button on your remote you'd like to open search with (for example ≡ or a colour button). Back cancels.",
                fontSize = 14.sp, color = AppColors.TextSecondary,
            )
            note?.let { androidx.tv.material3.Text(it, fontSize = 14.sp, color = AppColors.Accent) }
        }
    }
}

/** Settings → Menu & navigation's settings: where Viewarr opens, the menu, and the poster rows (also the catalogue's). */
@Composable
fun navigationCategories(vm: RemoteViewModel = hiltViewModel(), opts: UiOptsViewModel = hiltViewModel()): List<SettingCategory> {
    val s by vm.settings.collectAsStateWithLifecycle()
    fun item(title: String, description: String, key: String, choices: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, choices, current) { vm.set(key, it) }
    val k = RemoteStore.Companion
    return listOf(
        SettingCategory(
            "Start", "How Viewarr works and what you see when it opens.",
            listOf(
                item("App opens on", "The page Viewarr starts on: live TV (Last channel plays the channel you watched last, full screen), the TV guide, or one of your libraries.", k.START_PAGE, all<StartPage>(), s.startPage),
            ),
        ),
        SettingCategory(
            "Menu", "The side menu: Left from any page opens it on that page. On an entry, Right goes into its page and Left opens its own list (channel groups, libraries, albums…); Left or Back steps back out, then Back closes the menu.",
            listOf(
                optItem(opts, "Button hints", "A small pill at the bottom of the screen saying what the buttons do there: the first few times on each screen, always, or off.", UiOpts.BUTTON_HINTS),
            ),
        ),
        SettingCategory(
            "Poster rows", "Moving around the rows of posters on Movies, Shows and title pages.",
            listOf(
                item("Moving between rows", "Going back to a row lands on the poster you were on, or always on its first poster.", k.ROW_MEMORY, all<RowMemory>(), s.rowMemory),
                item("Poster position", "Keep the highlighted poster at the left edge of the row, or scroll only when it would go off screen.", k.ROW_ALIGN, all<RowAlign>(), s.rowAlign),
            ),
        ),
    )
}
