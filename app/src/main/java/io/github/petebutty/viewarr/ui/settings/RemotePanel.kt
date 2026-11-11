package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
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
    fun applyPreset(p: io.github.petebutty.viewarr.data.settings.ControlPreset) = viewModelScope.launch { store.applyPreset(p) }
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
    val cs = listOf(0xFFE53935, 0xFF43A047, 0xFFFDD835, 0xFF1E88E5)
    val names = listOf("Red", "Green", "Yellow", "Blue")
    // Every button as one entry (the colour buttons too), in two columns so a long list doesn't make the picture
    // taller: the same compact size on every section (a tall one pushed the settings off small screens).
    val entries: List<Triple<Long?, String, String>> = lines.map { (k, v) -> Triple(null, k, v) } +
        colours.take(4).mapIndexed { i, v -> Triple(cs[i], names[i], v) }
    val half = (entries.size + 1) / 2
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().height(170.dp).padding(bottom = 10.dp)
            .background(io.github.petebutty.viewarr.ui.theme.AppColors.PanelAlt.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.width(44.dp).height(120.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(body, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.45f))
            drawRoundRect(ink, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            // Power, then the arrows round OK, Back / Home / Menu, play/pause, the colour buttons, the numbers.
            drawCircle(ink, w * 0.06f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.06f))
            val c = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.24f)
            drawCircle(ink, w * 0.34f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            drawCircle(accent, w * 0.15f, c)
            listOf(0.25f, 0.5f, 0.75f).forEach { x -> drawCircle(ink, w * 0.07f, androidx.compose.ui.geometry.Offset(w * x, h * 0.42f)) }
            drawCircle(ink, w * 0.07f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.51f))
            val dots = listOf(androidx.compose.ui.graphics.Color(0xFFE53935), androidx.compose.ui.graphics.Color(0xFF43A047), androidx.compose.ui.graphics.Color(0xFFFDD835), androidx.compose.ui.graphics.Color(0xFF1E88E5))
            dots.forEachIndexed { i, col -> drawRoundRect(col, topLeft = androidx.compose.ui.geometry.Offset(w * (0.14f + i * 0.19f), h * 0.59f), size = androidx.compose.ui.geometry.Size(w * 0.14f, h * 0.025f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f)) }
            for (r in 0..3) for (col in 0..2) drawCircle(ink.copy(alpha = 0.6f), w * 0.05f, androidx.compose.ui.geometry.Offset(w * (0.28f + col * 0.22f), h * (0.68f + r * 0.07f)))
        }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(20.dp))
        listOf(entries.take(half), entries.drop(half)).forEachIndexed { col, part ->
            if (col == 1) androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(16.dp))
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
                part.forEach { (colour, k, v) ->
                    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.width(110.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            if (colour != null) {
                                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(12.dp).height(5.dp).background(androidx.compose.ui.graphics.Color(colour), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(6.dp))
                            }
                            androidx.tv.material3.Text(k, fontSize = 12.sp, maxLines = 1, color = io.github.petebutty.viewarr.ui.theme.AppColors.TextPrimary)
                        }
                        androidx.tv.material3.Text(v, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = io.github.petebutty.viewarr.ui.theme.AppColors.TextSecondary, modifier = androidx.compose.ui.Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The Preset row's choice: a preset, or Custom with the one it started from in its label. */
private data class PresetChoice(val preset: io.github.petebutty.viewarr.data.settings.ControlPreset, override val label: String) : RemoteChoice

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
    var hubGuide by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var networkSetup by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun item(title: String, description: String, key: String, choices: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, choices, current) {
            if (it == io.github.petebutty.viewarr.data.settings.SearchButton.Chosen) learning = true
            // The Live TV hub: its guide first (what it needs, networks), which turns it on.
            else if (it == io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub && s.navStyle != it) hubGuide = true
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
    val hubOn = s.navStyle == io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub
    val searchItem = item("Search button", "Pops search up from any screen (live TV, the guide, films and shows); Back closes it and you're where you were.", k.SEARCH_BUTTON, all<io.github.petebutty.viewarr.data.settings.SearchButton>(), s.searchButton)
    // Which preset the settings match now (any change of your own: Custom).
    val preset = run {
        val styleBack = if (hubOn) GuideBack.FullScreen else GuideBack.NowThenExit
        fun holdIsStandard(screen: io.github.petebutty.viewarr.data.settings.BtnScreen) = io.github.petebutty.viewarr.data.settings.BtnPress.BackHold.let {
            io.github.petebutty.viewarr.data.settings.ButtonMap.action(s, screen, it) == io.github.petebutty.viewarr.data.settings.ButtonMap.default(screen, it) }
        val guideHold = io.github.petebutty.viewarr.data.settings.ButtonMap.action(s, io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold)
        val common = s.menuOpensOn == MenuOpensOn.PageList && s.playerOk == PlayerOk.Controls && holdIsStandard(io.github.petebutty.viewarr.data.settings.BtnScreen.Live)
        when {
            common && holdIsStandard(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide) && s.liveSides == io.github.petebutty.viewarr.data.settings.LiveSides.Seek &&
                s.guideLeft == GuideLeft.EdgeMenu && s.guideBack == styleBack && s.zap == ZapDirection.ChannelList -> io.github.petebutty.viewarr.data.settings.ControlPreset.Viewarr
            common && guideHold == io.github.petebutty.viewarr.data.settings.BtnAction.FullScreen && s.liveSides == io.github.petebutty.viewarr.data.settings.LiveSides.ListAndLast &&
                s.guideLeft == GuideLeft.NowMenu && s.guideBack == GuideBack.Menu && s.zap == ZapDirection.UpNext -> io.github.petebutty.viewarr.data.settings.ControlPreset.TiviMate
            else -> io.github.petebutty.viewarr.data.settings.ControlPreset.Custom
        }
    }
    val presetChoices = listOf(io.github.petebutty.viewarr.data.settings.ControlPreset.Viewarr, io.github.petebutty.viewarr.data.settings.ControlPreset.TiviMate)
        .map { PresetChoice(it, it.label) } +
        listOfNotNull(PresetChoice(io.github.petebutty.viewarr.data.settings.ControlPreset.Custom,
            "Custom" + (s.presetBase?.takeIf { it != io.github.petebutty.viewarr.data.settings.ControlPreset.Custom }?.let { " (from ${it.label})" } ?: "")).takeIf { preset == io.github.petebutty.viewarr.data.settings.ControlPreset.Custom })
    val presetNow = presetChoices.first { it.preset == preset }
    // The buttons a preset sets, here under it to see and change (they're also in their own sections below).
    fun holdBack(screen: io.github.petebutty.viewarr.data.settings.BtnScreen, title: String) = io.github.petebutty.viewarr.data.settings.BtnPress.BackHold.let { press ->
        SettingItem(title, "Part of the preset · also under ${screen.label}", io.github.petebutty.viewarr.data.settings.ButtonMap.actions[screen].orEmpty().map { ActionChoice(it) },
            ActionChoice(io.github.petebutty.viewarr.data.settings.ButtonMap.action(s, screen, press))) { c -> vm.setMapping(screen, press, (c as ActionChoice).action) }
    }
    val presetItems = listOf(
        item("Guide: Left", "Part of the preset · also under TV guide", k.GUIDE_LEFT, all<GuideLeft>(), s.guideLeft),
        item("Guide: Back", "Part of the preset · also under TV guide", k.GUIDE_BACK, all<GuideBack>(), s.guideBack),
        holdBack(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, "Guide: hold Back"),
        item("Live TV: Up / Down", "Part of the preset · also under Live TV", k.ZAP, all<ZapDirection>(), s.zap),
        item("Live TV: OK", "Part of the preset · also under Live TV", k.PLAYER_OK, all<PlayerOk>(), s.playerOk),
        item("Live TV: Left / Right", "Part of the preset · also under Live TV", k.LIVE_SIDES, all<io.github.petebutty.viewarr.data.settings.LiveSides>(), s.liveSides),
        holdBack(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, "Live TV: hold Back"),
        item("Menu opens on", "Part of the preset · also under Navigation → Menu", k.MENU_OPENS_ON, all<MenuOpensOn>(), s.menuOpensOn),
    )
    val buttonCategories = listOf(
        SettingCategory(
            "Presets", "Set the main buttons the way you know them, in one go. The buttons it sets are listed under it: change any of them here (the preset then shows Custom, and which preset it came from). Every preset keeps a way to the menu (Hold Back, and Left at the start of the guide) and a way out (Back on the guide).",
            listOf(SettingItem("Preset",
                "TiviMate-style, as TiviMate comes: watching, Up / Down change channel, OK opens the player menu, Left the channel list, Right goes back to the channel before, Back the guide. In the guide, Left goes back to now and at \"now\" opens the channel groups; hold Left to go back through catch-up. Back jumps to now, then shows the groups (Back there asks to exit); hold Back goes straight back to full screen.\n" +
                    "Viewarr: in the guide, Left goes straight back into catch-up and opens the menu at the start; hold Left scrolls fast. Up / Down on live TV show the channel list. Back on the guide goes to full screen with the Live TV hub, else asks to exit.",
                presetChoices, presetNow) { c -> (c as PresetChoice).preset.takeIf { it != io.github.petebutty.viewarr.data.settings.ControlPreset.Custom }?.let { vm.applyPreset(it) } }) + presetItems,
            header = { RemotePicture(listOf(
                "Guide ◀" to s.guideLeft.label, "Guide Back" to s.guideBack.label,
                "Guide hold Back" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold),
                "Live ▲ ▼" to s.zap.label, "Live OK" to s.playerOk.label, "Live ◀ ▶" to s.liveSides.label,
                "Live hold Back" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold),
                "Menu opens on" to s.menuOpensOn.label,
            ), emptyList()) },
        ),
        SettingCategory(
            "TV guide", "What each button does while you browse the TV guide (the channel list with programmes). The main buttons first, then Record, Stop, the colour buttons and Info.",
            listOf(
                item("Left", "What Left does on the guide's programmes.", k.GUIDE_LEFT, all<GuideLeft>(), s.guideLeft),
                item("Back", "Back first jumps the guide to what's on now. On now: back to the channel full screen, ask to exit (press Back again), or open the menu. Back twice quickly from live TV always asks to exit.", k.GUIDE_BACK, all<GuideBack>(), s.guideBack),
                item("Channel + / −", "Pages through the channels. Reverse it if your remote's buttons feel the wrong way round.", k.CHANNEL_KEYS, all<ChannelKeys>(), s.channelKeys),
            ) + tableRows(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide) + resetRow(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, listOf(k.GUIDE_LEFT, k.GUIDE_BACK, k.CHANNEL_KEYS)),
            header = { RemotePicture(
                listOf("OK" to "Watch, or details for later", "▲ ▼" to "Channels", "◀ ▶" to s.guideLeft.label, "Back" to s.guideBack.label,
                    "Hold Back" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold), "Ch + / −" to s.channelKeys.label,
                    "Record" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, io.github.petebutty.viewarr.data.settings.BtnPress.Record), "Stop" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide, io.github.petebutty.viewarr.data.settings.BtnPress.Stop)),
                colours(io.github.petebutty.viewarr.data.settings.BtnScreen.Guide),
            ) },
        ),
        SettingCategory(
            "Live TV", "What each button does while a channel plays full screen. The main buttons first, then Record, Stop, the colour buttons and Info.",
            listOf(
                item("Up / Down", "Show the channel list to pick from, or change channel straight away.", k.ZAP, all<ZapDirection>(), s.zap),
                optSlider(opts, "Channel change delay", "Zapping through channels: how long one has to stay chosen before Viewarr asks for its stream (Left / Right, a tenth of a second at a time). Passing quickly over channels then doesn't start each one on Dispatcharr and use up your provider's connections, which could leave the channel you stop on loading. A single change starts straight away. No wait: every channel starts at once.", io.github.petebutty.viewarr.ui.theme.UiOpts.ZAP_DELAY, io.github.petebutty.viewarr.ui.theme.AppColors.palette.zapDelayMs.toInt() / 100),
                item("OK", "Open the playback controls, just show the banner with what's on, or show the channel list (as TiviMate).", k.PLAYER_OK, all<PlayerOk>(), s.playerOk),
                item("Left / Right", "Rewind and skip ahead (live TV asks \"Rewind?\" first), or as TiviMate: Left the channel list, Right back to the channel you watched before. In catch-up they always move through the programme.", k.LIVE_SIDES, all<io.github.petebutty.viewarr.data.settings.LiveSides>(), s.liveSides),
                item("Skip size", "How far each press skips (catch-up, paused live TV; films and recordings too).", k.SEEK, all<SeekStep>(), s.seek),
            ) + tableRows(io.github.petebutty.viewarr.data.settings.BtnScreen.Live) + resetRow(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, listOf(k.ZAP, k.PLAYER_OK, k.SEEK, k.LIVE_SIDES)),
            header = { RemotePicture(
                listOf("OK" to s.playerOk.label, "▲ ▼" to s.zap.label, "◀ ▶" to (if (s.liveSides == io.github.petebutty.viewarr.data.settings.LiveSides.Seek) s.seek.label else s.liveSides.label), "Back" to "The guide, the channel in its preview (twice quickly: exit)",
                    "Hold Back" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold), "Ch + / −" to "Change channel",
                    "Record" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, io.github.petebutty.viewarr.data.settings.BtnPress.Record), "Stop" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, io.github.petebutty.viewarr.data.settings.BtnPress.Stop), "Info" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Live, io.github.petebutty.viewarr.data.settings.BtnPress.Info)),
                colours(io.github.petebutty.viewarr.data.settings.BtnScreen.Live),
            ) },
        ),
        SettingCategory(
            "Films & shows", "What each button does while a film or episode plays. OK, the arrows and Back work as shown; the colour buttons and Info are yours to set.",
            tableRows(io.github.petebutty.viewarr.data.settings.BtnScreen.Film) + resetRow(io.github.petebutty.viewarr.data.settings.BtnScreen.Film, emptyList()),
            header = { RemotePicture(
                listOf("OK" to "Controls · hold: audio & subtitles", "◀ ▶" to s.seek.label, "▼" to "Seek bar with previews", "▲" to "Time and title",
                    "Back" to "Close what's open, then stop", "Play / Pause" to "Pause", "Info" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Film, io.github.petebutty.viewarr.data.settings.BtnPress.Info)),
                colours(io.github.petebutty.viewarr.data.settings.BtnScreen.Film),
            ) },
        ),
        SettingCategory(
            "Movies & Shows", "What the buttons do on the Movies and Shows pages (the poster rows) and in libraries.",
            listOf(
                item("Left on the first poster", "Open the menu on the page's own list (Left again for the main list), or do nothing.", k.ROW_LEFT, all<RowLeft>(), s.rowLeft),
                item("Back", "Back on Movies or Shows asks to exit (press Back again), opens the menu, or goes to the TV guide.", k.MEDIA_BACK, all<MediaBack>(), s.mediaBack),
            ) + tableRows(io.github.petebutty.viewarr.data.settings.BtnScreen.Pages) + resetRow(io.github.petebutty.viewarr.data.settings.BtnScreen.Pages, listOf(k.ROW_LEFT, k.MEDIA_BACK)),
            header = { RemotePicture(listOf("OK" to "Open", "Hold OK" to "Quick menu (play, trailer, watchlist)", "◀ first poster" to s.rowLeft.label, "Back" to s.mediaBack.label, "Hold Back" to act(io.github.petebutty.viewarr.data.settings.BtnScreen.Pages, io.github.petebutty.viewarr.data.settings.BtnPress.BackHold)), emptyList()) },
        ),
        SettingCategory(
            "Search & extra buttons", "The button that pops up search anywhere, and extra buttons for actions (play/pause, stop, the guide…): a colour button, or one your remote has spare. They work on live TV, films and in the guide. OK, Back, the arrows and the numbers stay as they are.",
            listOf(searchItem) + buttonItems,
        ),
    )
    // The Live TV hub's shortcuts: only with the hub on (they appear nowhere else). With Classic menus, one section
    // saying what the hub is and turning it on, instead of five locked lists.
    val hubCategories = if (hubOn) listOf(
        SettingCategory(
            "Live TV shortcuts", "Your row of shortcuts on live TV: press OK, then Down. Each opens a page (Movies, the guide…), a network's rows (Netflix, BBC One…) or one of your libraries, with live TV carrying on in the corner. Add them from the \"Add shortcuts\" lists below. To change the order, press Right on one, move it with Up / Down, then Right again. On live TV, holding OK on a shortcut takes it off.",
            shortcutItems,
        ),
        SettingCategory("Add shortcuts: pages", "Shortcuts to Viewarr's own pages: Movies, Shows, the TV guide, Live now (sport and events on now), Music, Recordings, Search and Settings. Switched on, they join the end of your row on live TV.", addList(pageShortcuts)),
        SettingCategory("Add shortcuts: networks", "Streaming services and TV channels from your addons: each shortcut opens that network's rows (Popular, New, Top rated…). Switched on, they join the end of your row on live TV.", addList(networkShortcuts)),
        SettingCategory("Add shortcuts: your libraries", "Your Plex, Jellyfin and Emby libraries and rows (Continue watching, Recently added, your watchlist), and your music: Albums, Artists, Playlists. Switched on, they join the end of your row on live TV.", addList(plexShortcuts)),
        SettingCategory("Add shortcuts: other rows", "Everything else: other libraries, TMDB and Trakt rows, addon catalogues. Switched on, they join the end of your row on live TV.", addList(otherShortcuts)),
    ) else listOf(
        SettingCategory(
            "Live TV hub", "The Live TV hub is another way to use Viewarr: it opens on your last channel, Back from any page goes back to it, and on live TV, OK then Down shows a row of shortcuts (Netflix, your Plex libraries, Movies…) that open with the channel carrying on in the corner. Its shortcuts are chosen here once it's on. You're using Classic menus, so none of this shows.",
            listOf(SettingItem("Turn on the Live TV hub…", "Shows what it needs first (live TV, and addons with network rows for the network shortcuts), then turns it on. You can go back to Classic menus any time under Start → Style.",
                listOf(ButtonStatus("")), ButtonStatus(""), action = { hubGuide = true }, onSelect = {})),
        ),
    )
    val navigationCategories = listOf(
        SettingCategory(
            "Start", "How Viewarr works and what you see when it opens.",
            listOf(
                item("Style", "Classic menus: as Viewarr has always worked. Live TV hub: Viewarr opens on your last channel, Back from any page (Movies, Shows, Music, Recordings…) goes back to it, and the shortcuts under the live TV controls (OK, then Down) take you anywhere.", k.NAV_STYLE, all<io.github.petebutty.viewarr.data.settings.NavStyle>(), s.navStyle),
                item("App opens on", "The page Viewarr starts on. Last channel plays the channel you watched last, full screen.", k.START_PAGE, all<StartPage>(), s.startPage)
                    .locked(if (hubOn) Locks.LIVE_HUB else null),
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
    ) + hubCategories
    val categories = if (part == RemotePart.Buttons) buttonCategories else navigationCategories
    // Reset: the buttons only (not where the app opens, the menu or the poster rows).
    if (part == RemotePart.Buttons) SettingsPage(title = "Remote buttons", categories = categories, onBack = onBack, onReset = { vm.resetButtons() }, resetLabel = "Reset buttons")
    else SettingsPage(title = "Navigation", categories = categories, onBack = onBack)
    if (hubGuide) LiveHubGuide(
        onTurnOn = { vm.set(k.NAV_STYLE, io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub); hubGuide = false },
        onNetworks = { networkSetup = true },
        onClose = { hubGuide = false },
    )
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
