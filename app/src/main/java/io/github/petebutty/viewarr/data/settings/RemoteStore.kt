package io.github.petebutty.viewarr.data.settings

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A choice in Settings → Remote & navigation. The first entry of each enum is the default (how the app behaved before). */
interface RemoteChoice { val label: String }

/** How Viewarr is laid out: menus as before, or live TV as the hub (Back from any page returns to the channel). */
enum class NavStyle(override val label: String) : RemoteChoice {
    Classic("Classic menus"),
    LiveHub("Live TV hub"),
}

enum class GuideLeft(override val label: String) : RemoteChoice {
    /** Since 0.23: holding Left scrolls fast (and stops at the start); only a fresh press there opens the menu. */
    EdgeMenu("Earlier programmes and catch-up; at the start, the menu · Hold: scroll fast"),
    NowMenu("Earlier programmes; at \"now\" opens the menu · Hold: catch-up"),
    PastTapMenuHold("Tap: earlier programmes · Hold: menu"),
    MenuTapPastHold("Tap: menu · Hold: earlier programmes"),
    AlwaysPast("Always earlier programmes"),
}

/**
 * Settings → Remote buttons → Presets: several settings at once, the way people know from other apps. Every preset
 * keeps a way to the menu (Left at the guide's start / "now", and Hold Back: the channel groups menu) and a way out
 * (Back on the guide asks to exit, or Back twice quickly). Changing any of its settings afterwards shows Custom.
 */
enum class ControlPreset(override val label: String) : RemoteChoice {
    Viewarr("Viewarr"),
    TiviMate("TiviMate-style"),
    Custom("Custom (your own changes)"),
}

enum class GuideBack(override val label: String) : RemoteChoice {
    /** Since 0.23: the guide and full screen swap with Back (the channel keeps playing in the preview). */
    FullScreen("Jump to now, then back to full screen"),
    NowThenExit("Jump to now, then ask to exit"),
    Menu("Open the menu"),
}

enum class ChannelKeys(override val label: String) : RemoteChoice {
    Normal("Page up / down"),
    Reversed("Page down / up (reversed)"),
}

enum class ZapDirection(override val label: String) : RemoteChoice {
    ChannelList("Show the channel list"),
    UpPrevious("Change channel (Up = previous)"),
    UpNext("Change channel (Up = next)"),
}

enum class StartPage(override val label: String) : RemoteChoice {
    Guide("TV guide"),
    LastChannel("Last channel, full screen"),
    Movies("Movies"),
    Shows("Shows"),
    Search("Search"),
    Recordings("Recordings"),
}

enum class SeekStep(override val label: String, val ms: Long) : RemoteChoice {
    S30("Skip 30 seconds", 30_000),
    S10("Skip 10 seconds", 10_000),
    S60("Skip 60 seconds", 60_000),
}

/** Left / Right on live TV (catch-up always moves through the programme). */
enum class LiveSides(override val label: String) : RemoteChoice {
    Seek("Rewind / skip ahead"),
    ListAndLast("Left: channel list · Right: previous channel"),
}

enum class PlayerOk(override val label: String) : RemoteChoice {
    Controls("Open the controls"),
    Banner("Show the info banner"),
    ChannelList("Show the channel list"),
}

enum class RowMemory(override val label: String) : RemoteChoice {
    Remember("Return to where you were"),
    First("Start at the first poster"),
}

enum class RowAlign(override val label: String) : RemoteChoice {
    LeftEdge("Keep at the left edge"),
    Free("Scroll only when needed"),
}

enum class RowLeft(override val label: String) : RemoteChoice {
    Menu("Open the menu"),
    Nothing("Do nothing"),
}

enum class MediaBack(override val label: String) : RemoteChoice {
    Exit("Ask to exit (press Back twice)"),
    Menu("Open the catalogue menu"),
    Guide("Go to the TV guide"),
}

enum class MenuOpensOn(override val label: String) : RemoteChoice {
    PageList("The page's own list"),
    MainList("The main list"),
}

enum class MenuBack(override val label: String) : RemoteChoice {
    TwiceToExit("Press twice to exit"),
    Close("Close the menu"),
}

/** The menu's Movies / Shows list: what Right on a library does (OK always opens it). */
enum class MenuLibraryRight(override val label: String, val groups: List<String>) : RemoteChoice {
    // Each: the library filters to list, first one the library has (Plex · Jellyfin/Emby · IPTV names), genres after.
    Categories("Categories / genres", listOf("category", "genre", "Genres")),
    Collections("Collections", listOf("collection", "Tags")),
    Decades("Decades", listOf("decade", "Years")),
    Years("Years", listOf("year", "Years")),
    Studios("Studios / networks", listOf("studio", "network", "Studios")),
    AgeRatings("Age ratings", listOf("contentRating", "OfficialRatings")),
    Open("Just open the library", emptyList()),
}

/** The button that pops search up from any screen. */
enum class SearchButton(override val label: String) : RemoteChoice {
    Auto("The remote's search button, or hold Menu (≡)"),
    Red("Red button"),
    Green("Green button"),
    Yellow("Yellow button"),
    Blue("Blue button"),
    Info("Info button"),
    Chosen("A button you choose (press it next)"),
    Off("Off (search from the menu only)"),
}

data class RemoteSettings(
    val guideLeft: GuideLeft = GuideLeft.entries.first(),
    val guideBack: GuideBack = GuideBack.NowThenExit,
    val channelKeys: ChannelKeys = ChannelKeys.entries.first(),
    val zap: ZapDirection = ZapDirection.entries.first(),
    val seek: SeekStep = SeekStep.entries.first(),
    val playerOk: PlayerOk = PlayerOk.entries.first(),
    val rowMemory: RowMemory = RowMemory.entries.first(),
    val rowAlign: RowAlign = RowAlign.entries.first(),
    val rowLeft: RowLeft = RowLeft.entries.first(),
    val mediaBack: MediaBack = MediaBack.entries.first(),
    val menuOpensOn: MenuOpensOn = MenuOpensOn.entries.first(),
    val menuBack: MenuBack = MenuBack.entries.first(),
    val menuLibraryRight: MenuLibraryRight = MenuLibraryRight.entries.first(),
    /** The preset last chosen (Settings → Remote buttons → Presets), for "Custom (from …)"; null: never chosen. */
    val presetBase: ControlPreset? = null,
    val liveSides: LiveSides = LiveSides.entries.first(),
    /** Hold OK on a shortcut → Choose rows: the rows it shows (by name, in order). Not in here: its usual rows. */
    val shortcutRows: Map<String, List<String>> = emptyMap(),
    val startPage: StartPage = StartPage.entries.first(),
    val searchButton: SearchButton = SearchButton.entries.first(),
    val navStyle: NavStyle = NavStyle.entries.first(),
    /** Search button → "A button you choose": the key code learned. */
    val searchKeyCode: Int? = null,
    /** The last live channel played (for "Last channel" start). */
    val lastChannelId: Int? = null,
    /** Settings → Remote → Buttons while watching: an extra button per action (RemoteAction name → key code). */
    val buttons: Map<String, Int> = emptyMap(),
    /** Settings → Remote → the button table: changes from ButtonMap's defaults ("Screen.Press" → action name). */
    val buttonMap: Map<String, String> = emptyMap(),
    /** Shortcuts under the live TV controls (LiveShortcuts ids, in order); null: the defaults. */
    val liveShortcuts: List<String>? = null,
    /** Live channels played lately, newest first (Live TV controls → Recent channels). */
    val recentChannels: List<Int> = emptyList(),
    /** False until the stored settings have been read. */
    val loaded: Boolean = false,
)

/** Read anywhere in the UI; provided at the app root and updated live when a setting changes. */
val LocalRemote = staticCompositionLocalOf { RemoteSettings() }

internal val Context.remoteStore by preferencesDataStore(name = "remote")

@Singleton
class RemoteStore @Inject constructor(@ApplicationContext private val context: Context) {

    val settings: Flow<RemoteSettings> = context.remoteStore.data.map { p ->
        RemoteSettings(
            guideLeft = p.read(GUIDE_LEFT),
            // Not chosen: ask to exit with Classic menus (0.23 made full screen everyone's default by mistake, so
            // Back swapped between the guide and full screen with no way out); the Live TV hub goes to full screen.
            guideBack = if (p[stringPreferencesKey(GUIDE_BACK)] != null) p.read(GUIDE_BACK)
                else if (p.read<NavStyle>(NAV_STYLE) == NavStyle.LiveHub) GuideBack.FullScreen else GuideBack.NowThenExit,
            channelKeys = p.read(CHANNEL_KEYS),
            zap = p.read(ZAP),
            seek = p.read(SEEK),
            playerOk = p.read(PLAYER_OK),
            rowMemory = p.read(ROW_MEMORY),
            rowAlign = p.read(ROW_ALIGN),
            rowLeft = p.read(ROW_LEFT),
            mediaBack = p.read(MEDIA_BACK),
            menuOpensOn = p.read(MENU_OPENS_ON),
            menuBack = p.read(MENU_BACK),
            menuLibraryRight = p.read(MENU_LIBRARY_RIGHT),
            liveSides = p.read(LIVE_SIDES),
            shortcutRows = p[stringPreferencesKey(SHORTCUT_ROWS)].orEmpty().split('\u001D').mapNotNull { r ->
                val parts = r.split('\u001E'); if (parts.size != 2 || parts[0].isBlank()) null else parts[0] to parts[1].split('\u001F').filter { it.isNotBlank() }
            }.toMap(),
            presetBase = p[stringPreferencesKey(PRESET_BASE)]?.let { n -> ControlPreset.entries.firstOrNull { it.name == n } },
            startPage = p.read(START_PAGE),
            searchButton = p.read(SEARCH_BUTTON),
            navStyle = p.read(NAV_STYLE),
            searchKeyCode = p[intPreferencesKey(SEARCH_KEYCODE)],
            lastChannelId = p[intPreferencesKey(LAST_CHANNEL)],
            buttons = p[stringPreferencesKey(BUTTONS)].orEmpty().split(',').mapNotNull { part ->
                val (name, code) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                code.toIntOrNull()?.let { name to it }
            }.toMap(),
            buttonMap = p[stringPreferencesKey(BUTTON_MAP)].orEmpty().split(',').mapNotNull { part ->
                val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                k to v
            }.toMap(),
            liveShortcuts = p[stringPreferencesKey(LIVE_SHORTCUTS)]?.split('|')?.filter { it.isNotBlank() },
            recentChannels = p[stringPreferencesKey(RECENT_CHANNELS)].orEmpty().split(',').mapNotNull { it.toIntOrNull() },
            loaded = true,
        )
    }

    suspend fun setSearchKeyCode(code: Int) {
        context.remoteStore.edit { it[intPreferencesKey(SEARCH_KEYCODE)] = code; it[stringPreferencesKey(SEARCH_BUTTON)] = SearchButton.Chosen.name }
    }

    /** Gives [action] the extra button [code] (taken from any other action that had it), or none (null). */
    suspend fun setButton(action: String, code: Int?) {
        context.remoteStore.edit { prefs ->
            val now = prefs[stringPreferencesKey(BUTTONS)].orEmpty().split(',').mapNotNull { part ->
                val (name, c) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                c.toIntOrNull()?.let { name to it }
            }.toMap().filter { (name, c) -> name != action && c != code }
            val next = if (code == null) now else now + (action to code)
            prefs[stringPreferencesKey(BUTTONS)] = next.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }

    /** One shortcut's chosen rows ([rows] null: back to its usual ones). */
    suspend fun setShortcutRows(id: String, rows: List<String>?) {
        context.remoteStore.edit { prefs ->
            val now = prefs[stringPreferencesKey(SHORTCUT_ROWS)].orEmpty().split('\u001D').filter { it.isNotBlank() && it.substringBefore('\u001E') != id }
            val next = if (rows == null) now else now + (id + '\u001E' + rows.joinToString("\u001F"))
            if (next.isEmpty()) prefs.remove(stringPreferencesKey(SHORTCUT_ROWS)) else prefs[stringPreferencesKey(SHORTCUT_ROWS)] = next.joinToString("\u001D")
        }
    }

    suspend fun setLiveShortcuts(ids: List<String>) {
        context.remoteStore.edit { it[stringPreferencesKey(LIVE_SHORTCUTS)] = ids.joinToString("|") }
    }

    /** One row of the button table ([action] null: back to the default). */
    suspend fun setMapping(key: String, action: String?) {
        context.remoteStore.edit { prefs ->
            val now = prefs[stringPreferencesKey(BUTTON_MAP)].orEmpty().split(',').mapNotNull { part ->
                val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                k to v
            }.toMap()
            val next = if (action == null) now - key else now + (key to action)
            prefs[stringPreferencesKey(BUTTON_MAP)] = next.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }

    /** One screen of the button table back to its defaults (rows whose key starts "Screen."). */
    suspend fun resetMappings(screenPrefix: String) {
        context.remoteStore.edit { prefs ->
            val kept = prefs[stringPreferencesKey(BUTTON_MAP)].orEmpty().split(',').filter { it.isNotBlank() && !it.startsWith(screenPrefix) }
            prefs[stringPreferencesKey(BUTTON_MAP)] = kept.joinToString(",")
        }
    }

    suspend fun set(key: String, value: Enum<*>) {
        context.remoteStore.edit { it[stringPreferencesKey(key)] = value.name }
    }

    /** A preset's settings (see [ControlPreset]); a null value is that setting's own default. */
    suspend fun applyPreset(p: ControlPreset) {
        val values: Map<String, Enum<*>?> = when (p) {
            ControlPreset.Viewarr -> mapOf(GUIDE_LEFT to GuideLeft.EdgeMenu, GUIDE_BACK to null, ZAP to ZapDirection.ChannelList,
                PLAYER_OK to PlayerOk.Controls, MENU_OPENS_ON to MenuOpensOn.PageList, LIVE_SIDES to LiveSides.Seek)
            // As TiviMate comes: watching, Up / Down change channel (Up = the next number), OK the player menu, Left
            // the channel list, Right the previous channel, Back the guide. In the guide, Left goes back to now (then
            // earlier with catch-up: hold it), at now the groups; Back jumps to now, then shows the groups (Back there
            // asks to exit); hold Back: straight back to full screen.
            ControlPreset.TiviMate -> mapOf(GUIDE_LEFT to GuideLeft.NowMenu, GUIDE_BACK to GuideBack.Menu, ZAP to ZapDirection.UpNext,
                PLAYER_OK to PlayerOk.Controls, MENU_OPENS_ON to MenuOpensOn.PageList, LIVE_SIDES to LiveSides.ListAndLast)
            ControlPreset.Custom -> return
        }
        context.remoteStore.edit { prefs ->
            prefs[stringPreferencesKey(PRESET_BASE)] = p.name
            values.forEach { (k, v) -> if (v == null) prefs.remove(stringPreferencesKey(k)) else prefs[stringPreferencesKey(k)] = v.name }
            // Hold Back on the guide and live TV as the preset has it (live TV: the channel groups; the guide: the
            // groups, or with TiviMate back to full screen, its Back and Left reaching the groups instead).
            val map = prefs[stringPreferencesKey(BUTTON_MAP)].orEmpty().split(',').filter { it.isNotBlank() }
                .filterNot { it.startsWith("Guide.BackHold=") || it.startsWith("Live.BackHold=") } +
                listOfNotNull("Guide.BackHold=${BtnAction.FullScreen.name}".takeIf { p == ControlPreset.TiviMate })
            if (map.isEmpty()) prefs.remove(stringPreferencesKey(BUTTON_MAP)) else prefs[stringPreferencesKey(BUTTON_MAP)] = map.joinToString(",")
        }
    }

    /** Only the button settings back to their defaults (not where the app opens, the menu or the poster rows). */
    suspend fun resetButtons() {
        context.remoteStore.edit { prefs ->
            listOf(GUIDE_LEFT, GUIDE_BACK, CHANNEL_KEYS, ZAP, SEEK, PLAYER_OK, ROW_LEFT, MEDIA_BACK, MENU_BACK, SEARCH_BUTTON, BUTTONS, BUTTON_MAP, PRESET_BASE, LIVE_SIDES)
                .forEach { prefs.remove(stringPreferencesKey(it)) }
            prefs.remove(intPreferencesKey(SEARCH_KEYCODE))
        }
    }

    /** Some choice settings back to their defaults (a section's "Reset these buttons"). */
    suspend fun clear(keys: List<String>) {
        context.remoteStore.edit { prefs -> keys.forEach { prefs.remove(stringPreferencesKey(it)) } }
    }

    suspend fun reset() {
        context.remoteStore.edit { prefs ->
            val last = prefs[intPreferencesKey(LAST_CHANNEL)]
            prefs.clear()
            last?.let { prefs[intPreferencesKey(LAST_CHANNEL)] = it }
        }
    }

    suspend fun clearLastChannel() {
        context.remoteStore.edit { it.remove(intPreferencesKey(LAST_CHANNEL)) }
    }

    suspend fun setLastChannel(id: Int) {
        context.remoteStore.edit {
            it[intPreferencesKey(LAST_CHANNEL)] = id
            val before = it[stringPreferencesKey(RECENT_CHANNELS)].orEmpty().split(',').mapNotNull { n -> n.toIntOrNull() }
            it[stringPreferencesKey(RECENT_CHANNELS)] = (listOf(id) + (before - id)).take(12).joinToString(",")
        }
    }

    private inline fun <reified E : Enum<E>> Preferences.read(key: String): E =
        this[stringPreferencesKey(key)]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: enumValues<E>().first()

    companion object {
        const val GUIDE_LEFT = "guide_left_v2" // v2: "at now opens the menu" became the default (2026-11-05)
        const val GUIDE_BACK = "guide_back_v2" // v2: "ask to exit" became the default (2026-11-05)
        const val CHANNEL_KEYS = "channel_keys"
        const val ZAP = "zap_v2" // v2: "Show the channel list" became the default (2026-11-05)
        const val SEEK = "seek"
        const val PLAYER_OK = "player_ok"
        const val ROW_MEMORY = "row_memory"
        const val ROW_ALIGN = "row_align"
        const val ROW_LEFT = "row_left"
        const val MEDIA_BACK = "media_back_v2" // v2: "ask to exit" became the default (2026-11-05)
        const val MENU_OPENS_ON = "menu_opens_on"
        const val MENU_BACK = "menu_back"
        const val MENU_LIBRARY_RIGHT = "menu_library_right"
        const val PRESET_BASE = "preset_base"
        const val LIVE_SIDES = "live_sides"
        const val SHORTCUT_ROWS = "shortcut_rows"
        const val START_PAGE = "start_page"
        const val SEARCH_BUTTON = "search_button"
        const val SEARCH_KEYCODE = "search_keycode"
        const val LAST_CHANNEL = "last_channel"
        const val BUTTONS = "buttons_v1"
        const val BUTTON_MAP = "bmap_v1"
        const val LIVE_SHORTCUTS = "live_shortcuts"
        const val RECENT_CHANNELS = "recent_channels"
        const val NAV_STYLE = "nav_style"
    }
}
