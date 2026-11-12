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
import io.github.petebutty.viewarr.data.settings.GuideBack
import io.github.petebutty.viewarr.data.settings.GuideLeft
import io.github.petebutty.viewarr.data.settings.MediaBack
import io.github.petebutty.viewarr.data.settings.MenuBack
import io.github.petebutty.viewarr.data.settings.MenuOpensOn
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.data.settings.RemoteStore
import io.github.petebutty.viewarr.data.settings.RowAlign
import io.github.petebutty.viewarr.data.settings.RowLeft
import io.github.petebutty.viewarr.data.settings.RowMemory
import io.github.petebutty.viewarr.data.settings.SeekStep
import io.github.petebutty.viewarr.data.settings.StartPage
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
        val map = io.github.petebutty.viewarr.data.settings.ButtonMap
        // The default is saved as "no change", so a later change of default reaches it.
        store.setMappings(mapOf(map.key(screen, press) to action.name.takeIf { action != map.default(settings.value, screen, press) }))
    }
    fun resetButtons() = viewModelScope.launch { store.resetButtons() }
    fun applyPreset(p: io.github.petebutty.viewarr.data.settings.ControlPreset) = viewModelScope.launch { store.applyPreset(p) }
    fun setLiveShortcuts(ids: List<String>) = viewModelScope.launch { store.setLiveShortcuts(ids) }
    fun setShortcutRows(id: String, rows: List<String>?) = viewModelScope.launch { store.setShortcutRows(id, rows) }
    fun resetLiveShortcuts() = viewModelScope.launch { store.clear(listOf(RemoteStore.LIVE_SHORTCUTS)) }
}

/** On / Off for the Live TV shortcuts list. */
private data class OnOff(override val label: String) : RemoteChoice

/** A button's drop-down choice: an action. */
private data class ActionChoice(val action: io.github.petebutty.viewarr.data.settings.BtnAction) : RemoteChoice { override val label get() = action.label }

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

/** The key code that opens search now (Settings → Remote buttons → Search & extra buttons), so it isn't taught to something else. */
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

/** Which of the two Settings pages this screen is: Remote buttons, or Navigation (with the Live TV hub). */
enum class RemotePart { Buttons, Navigation }

/**
 * Settings → Remote buttons (what each button does, grouped by screen) or Settings → Navigation (where the app opens,
 * the menu, poster rows and the Live TV hub's shortcuts), full screen like Playback. One screen, two pages.
 */
@Composable
fun RemoteSettingsScreen(onBack: () -> Unit, part: RemotePart = RemotePart.Buttons, vm: RemoteViewModel = hiltViewModel(), opts: UiOptsViewModel = hiltViewModel()) {
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
    var networkSetup by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun item(title: String, description: String, key: String, choices: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, choices, current) {
            if (it == io.github.petebutty.viewarr.data.settings.SearchButton.Chosen) learning = true
            else vm.set(key, it)
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
    val musicSources by musicM.sources.collectAsStateWithLifecycle()
    val candidates = io.github.petebutty.viewarr.ui.player.LiveShortcuts.candidates(mLibs.orEmpty(), sLibs.orEmpty(), mRows.rows, sRows.rows, musicOn,
        musicSources.firstOrNull { it.on }?.name)
    // Shown: the chosen ones that exist right now. Kept: every chosen one, also those whose rows haven't loaded yet
    // (an addon's networks arrive a few seconds in): changing the list mustn't drop them.
    val stored = s.liveShortcuts ?: io.github.petebutty.viewarr.ui.player.LiveShortcuts.defaults
    val byId = candidates.associateBy { it.id }
    val rowsForIds = mRows.rows + sRows.rows
    // Saved ids from older versions (a network's single row or films-only shortcut) count as its one shortcut.
    val chosen = stored.mapNotNull { io.github.petebutty.viewarr.ui.player.LiveShortcuts.resolve(it, byId, rowsForIds)?.id }.distinct()
    val unseen = stored.filter { id -> io.github.petebutty.viewarr.ui.player.LiveShortcuts.resolve(id, byId, rowsForIds) == null }
    val onOffChoices = listOf(OnOff("On"), OnOff("Off"))
    fun scTitle(sc: io.github.petebutty.viewarr.ui.player.LiveShortcut) = (sc.emoji?.let { "$it " } ?: "") + sc.label
    fun setChosen(ids: List<String>) = vm.setLiveShortcuts(ids + unseen)
    // "Live TV shortcuts": only what's in the row, in its order (Right picks one up, Up / Down move it; OK takes it out).
    val inRow = chosen.mapNotNull { id -> candidates.firstOrNull { it.id == id } }.map { sc ->
        SettingItem(
            scTitle(sc), "${chosen.indexOf(sc.id) + 1} of ${chosen.size}  ·  Right to move it  ·  OK takes it out",
            onOffChoices, onOffChoices[0],
            reorder = { delta ->
                val i = chosen.indexOf(sc.id)
                val j = (i + delta).coerceIn(0, chosen.lastIndex)
                if (i != j) setChosen(chosen.toMutableList().apply { add(j, removeAt(i)) })
            },
        ) { setChosen(chosen - sc.id) }
    }
    val shortcutItems = inRow + SettingItem("Choose network shortcuts…", "Pick your favourites from the networks your addons give (Netflix, BBC One…), as logos: they go in your row, most popular first.",
        listOf(ButtonStatus("")), ButtonStatus(""), action = { networkSetup = true }, onSelect = {}) + optItem(opts, "Network rows", "An addon's rows named \"Netflix · Popular\", \"BBC One · New\"… become one shortcut per network, opening just its rows. Choose whether they're on the Movies / Shows home page as well.", io.github.petebutty.viewarr.ui.theme.UiOpts.SET_ROWS_HOME) +
        optItem(opts, "Shortcut style", "Round, rounded squares (the services' app icons), or wide tiles showing their full logos (where TMDB has them).", io.github.petebutty.viewarr.ui.theme.UiOpts.SHORTCUT_STYLE) +
        optItem(opts, "Shortcut size", "How big the round shortcut icons and logos are on live TV.", io.github.petebutty.viewarr.ui.theme.UiOpts.SHORTCUT_SIZE) +
        optItem(opts, "Show the rows", "When you press Down: just the shortcuts, and Down shows the highlighted one's rows. Always: a dimmed preview of them under the shortcuts.", io.github.petebutty.viewarr.ui.theme.UiOpts.OSD_PEEK) +
        optItem(opts, "Rows under the shortcuts", "How many poster rows Down reaches from the shortcuts on live TV. The rest are on the shortcut's own page (OK on it).", io.github.petebutty.viewarr.ui.theme.UiOpts.OSD_ROWS) +
        optItem(opts, "Poster rows", "Down from the shortcuts: Continue watching, Recent channels and Recently added, one row at a time (Up / Down change row).", io.github.petebutty.viewarr.ui.theme.UiOpts.OSD_POSTERS)
        .locked(if (io.github.petebutty.viewarr.ui.theme.AppColors.palette.lowMemory) Locks.LOW_MEMORY else null) +
        SettingItem("Reset shortcuts", "Back to TV guide, Live now, Movies, Shows, Music, Recordings, Search and Settings.", listOf(ButtonStatus("")), ButtonStatus(""), action = { vm.resetLiveShortcuts() }, onSelect = {})
    // The rest by kind, each switch adding to the end of the row (or taking it out). They stay in place when switched.
    fun addList(list: List<io.github.petebutty.viewarr.ui.player.LiveShortcut>) = list.map { sc ->
        val on = sc.id in chosen
        SettingItem(
            scTitle(sc) + if (sc.id.startsWith("set:")) " (rows)" else "",
            if (on) "In your row: ${chosen.indexOf(sc.id) + 1} of ${chosen.size}" else "",
            onOffChoices, if (on) onOffChoices[0] else onOffChoices[1],
        ) { c -> setChosen(if ((c as OnOff).label == "On") (chosen - sc.id) + sc.id else chosen - sc.id) }
    }
    // Your own libraries and rows: Plex, Jellyfin, Emby, and your music.
    fun isPlex(sc: io.github.petebutty.viewarr.ui.player.LiveShortcut) = sc.logoName in setOf("Plex", "Jellyfin", "Emby", "Navidrome") || sc.id.startsWith("music:")
    val pageShortcuts = candidates.filter { it.id.startsWith("home:") || it.id.startsWith("page:") }
    val networkShortcuts = candidates.filter { it.id.startsWith("set:") && it.id.contains(":name:") }
    val plexShortcuts = candidates.filter { isPlex(it) && it !in pageShortcuts }
    val otherShortcuts = candidates.filter { it !in pageShortcuts && it !in networkShortcuts && it !in plexShortcuts }
    val searchItem = item("Search button", "Pops search up from any screen (live TV, the guide, films and shows); Back closes it and you're where you were.", k.SEARCH_BUTTON, all<io.github.petebutty.viewarr.data.settings.SearchButton>(), s.searchButton)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val preset = io.github.petebutty.viewarr.data.settings.Presets.matching(s)
    fun presetRow(p: io.github.petebutty.viewarr.data.settings.ControlPreset, title: String, about: String): SettingItem {
        val status = ButtonStatus(if (preset == p) "✓ In use" else if (preset == io.github.petebutty.viewarr.data.settings.ControlPreset.Custom && s.presetBase == p) "Changed" else "")
        return SettingItem(title, about, listOf(status), status, action = { vm.applyPreset(p) }, onSelect = {})
    }
    val shareRow = SettingItem("Share this button setup", "Writes every button to the app's log (for building a preset from it).",
        listOf(ButtonStatus("")), ButtonStatus(""), action = {
            val map = io.github.petebutty.viewarr.data.settings.ButtonMap
            val lines = listOf("guideLeft=${s.guideLeft.name}", "guideBack=${s.guideBack.name}", "channelKeys=${s.channelKeys.name}",
                "zap=${s.zap.name}", "playerOk=${s.playerOk.name}", "liveSides=${s.liveSides.name}", "seek=${s.seek.name}",
                "rowLeft=${s.rowLeft.name}", "mediaBack=${s.mediaBack.name}", "menuOpensOn=${s.menuOpensOn.name}", "menuBack=${s.menuBack.name}") +
                map.presses.flatMap { (screen, presses) -> presses.map { "${map.key(screen, it)}=${map.action(s, screen, it).name}" } }
            lines.forEach { android.util.Log.i("ViewarrButtons", it) }
            android.widget.Toast.makeText(ctx, "Button setup written to the log", android.widget.Toast.LENGTH_SHORT).show()
        }, onSelect = {})
    // By button (as TiviMate): every button on the remote, each with what it does in every part of Viewarr.
    val bm = io.github.petebutty.viewarr.data.settings.ButtonMap
    val places = listOf(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide to "In the TV guide", io.github.petebutty.viewarr.data.settings.BtnScreen.Live to "On live TV", io.github.petebutty.viewarr.data.settings.BtnScreen.Film to "In films & shows", io.github.petebutty.viewarr.data.settings.BtnScreen.Pages to "In menus & pages")
    fun placeRow(screen: io.github.petebutty.viewarr.data.settings.BtnScreen, press: io.github.petebutty.viewarr.data.settings.BtnPress, title: String) =
        SettingItem(title, "", bm.actions[screen].orEmpty().map { ActionChoice(it) }, ActionChoice(bm.action(s, screen, press))) { c ->
            vm.setMapping(screen, press, (c as ActionChoice).action)
        }
    val perButton = io.github.petebutty.viewarr.data.settings.BtnPress.entries.mapNotNull { press ->
        val rows = buildList {
            places.forEach { (screen, place) ->
                when {
                    // Back and Left step through the guide and pages first, so they have their own choices there.
                    screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Guide && press == io.github.petebutty.viewarr.data.settings.BtnPress.Back -> add(item(place, "Back first jumps to what's on now. On now: back to full screen, ask to exit, or the menu.", k.GUIDE_BACK, all<GuideBack>(), s.guideBack))
                    screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Guide && press == io.github.petebutty.viewarr.data.settings.BtnPress.Left -> add(item(place, "Along the programmes, and how it reaches the menu.", k.GUIDE_LEFT, all<GuideLeft>(), s.guideLeft))
                    screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Pages && press == io.github.petebutty.viewarr.data.settings.BtnPress.Back -> {
                        add(item(place, "Ask to exit, open the menu, or go to the TV guide.", k.MEDIA_BACK, all<MediaBack>(), s.mediaBack))
                        add(item("In the menu", "Press twice to exit, or close the menu.", k.MENU_BACK, all<MenuBack>(), s.menuBack))
                    }
                    screen == io.github.petebutty.viewarr.data.settings.BtnScreen.Pages && press == io.github.petebutty.viewarr.data.settings.BtnPress.Left -> add(item("$place (first poster)", "Open the menu, or do nothing.", k.ROW_LEFT, all<RowLeft>(), s.rowLeft))
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
            listOf(
                presetRow(io.github.petebutty.viewarr.data.settings.ControlPreset.Viewarr, "Use Viewarr buttons",
                    "Live TV: Up / Down the channel list, OK the controls, Left / Right rewind and skip. The guide: Left goes into catch-up and opens the menu at the start; Back jumps to now, then asks to exit."),
                presetRow(io.github.petebutty.viewarr.data.settings.ControlPreset.TiviMate, "Use TiviMate buttons",
                    "As TiviMate comes. Live TV: Up / Down change channel, OK the controls, Left the channel list, Right the channel before, Menu the main menu. The guide: Left back to now, then the menu; Back back to the player."),
                shareRow,
            ),
        ),
    ) + perButton + listOf(
        SettingCategory(
            "Other buttons", "Skipping, changing channel quickly, the search button, and spare buttons your remote has.",
            listOf(
                item("Skip size", "How far each skip goes (catch-up, paused live TV, films and recordings).", k.SEEK, all<SeekStep>(), s.seek),
                optSlider(opts, "Channel change delay", "Zapping through channels: how long one has to stay chosen before Viewarr asks for its stream (Left / Right, a tenth of a second at a time), so passing quickly over channels doesn't use up your provider's connections. No wait: every channel starts at once.", io.github.petebutty.viewarr.ui.theme.UiOpts.ZAP_DELAY, io.github.petebutty.viewarr.ui.theme.AppColors.palette.zapDelayMs.toInt() / 100),
                searchItem,
            ) + buttonItems,
        ),
    )
    val navigationCategories = listOf(
        SettingCategory(
            "Start", "How Viewarr works and what you see when it opens.",
            listOf(
                item("App opens on", "The page Viewarr starts on: live TV (Last channel plays the channel you watched last, full screen), the TV guide, or one of your libraries.", k.START_PAGE, all<StartPage>(), s.startPage),
            ),
        ),
        SettingCategory(
            "Menu", "The side menu (Left on the first poster, or hold Back). Going into a list slides the one before it away to the left; Left or Back brings it back, one level at a time, then Back closes the menu.",
            listOf(
                item("Menu opens on", "Open on the current page's own list (channel groups, catalogues), or on the main list.", k.MENU_OPENS_ON, all<MenuOpensOn>(), s.menuOpensOn),
                item("Right on a library", "In the menu's Movies and Shows lists: Right on a library lists what to jump straight into: its categories (IPTV) or genres, collections, decades, years, studios / networks or age ratings. Or Right just opens it. OK always opens the whole library.", k.MENU_LIBRARY_RIGHT, all<io.github.petebutty.viewarr.data.settings.MenuLibraryRight>(), s.menuLibraryRight),
                optItem(opts, "Button hints", "A small pill at the bottom of the screen saying what the buttons do there: the first few times on each screen, always, or off.", io.github.petebutty.viewarr.ui.theme.UiOpts.BUTTON_HINTS),
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
    val categories = if (part == RemotePart.Buttons) buttonCategories else navigationCategories
    // Reset: the buttons only (not where the app opens, the menu or the poster rows).
    if (part == RemotePart.Buttons) SettingsPage(title = "Remote buttons", categories = categories, onBack = onBack, onReset = { vm.resetButtons() }, resetLabel = "Reset buttons")
    else SettingsPage(title = "Navigation", categories = categories, onBack = onBack)
    // After choosing networks: the shortcut row is the networks picked (most popular first), then Movies, Shows,
    // your Plex rows, Music, Recordings and Search. Network ids are kept even before their rows have loaded.
    if (networkSetup) NetworkSetupScreen(
        onDone = { names ->
            val plex = candidates.filter { it.logoName in setOf("Plex", "Jellyfin", "Emby") && it.id.startsWith("row:") &&
                (it.id.contains("deck") || it.id.contains("recent") || it.id.contains("watchlist")) }.map { it.id }.take(5)
            // Streaming services and channels first, then your own: Movies, Shows, Plex, Music, Recordings, Search.
            val ids = names.map { "set:Show:name:$it" } + listOf("page:Live", "home:Movie", "home:Show") + plex +
                listOf("page:Music", "page:Recordings", "page:Search").filter { id -> candidates.any { it.id == id } }
            vm.setLiveShortcuts(ids)
            networkSetup = false
        },
        onClose = { networkSetup = false },
    )
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
                        code == search -> note = "That button opens search (Settings → Remote buttons → Search & extra buttons). Choose another, or change the search button first."
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
