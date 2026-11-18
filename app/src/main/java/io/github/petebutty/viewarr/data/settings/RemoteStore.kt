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
    Classic("Classic"),
    Custom("Custom (your own changes)"),
}

/** Back in the guide on what's on now with nothing playing (with something playing it always goes back to it full screen). */
enum class GuideBack(override val label: String) : RemoteChoice {
    /** Old "back to full screen": with nothing playing the same as asking to exit, so read as [NowThenExit], never offered. */
    FullScreen("Ask to exit"),
    NowThenExit("Ask to exit"),
    Menu("Open the menu"),
}

/** Back in the guide away from what's on now: jumps to now first (as classic IPTV apps' "Use Back to return to current programs"), or acts at once. */
enum class GuideBackNow(override val label: String) : RemoteChoice {
    On("On"),
    Off("Off"),
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
    val guideLeft: GuideLeft = GuideLeft.AlwaysPast,
    val guideBack: GuideBack = GuideBack.Menu,
    val guideBackNow: GuideBackNow = GuideBackNow.On,
    val channelKeys: ChannelKeys = ChannelKeys.entries.first(),
    val zap: ZapDirection = ZapDirection.UpNext,
    val seek: SeekStep = SeekStep.entries.first(),
    val playerOk: PlayerOk = PlayerOk.entries.first(),
    val rowMemory: RowMemory = RowMemory.entries.first(),
    val rowAlign: RowAlign = RowAlign.entries.first(),
    val rowLeft: RowLeft = RowLeft.entries.first(),
    val mediaBack: MediaBack = MediaBack.entries.first(),
    val menuOpensOn: MenuOpensOn = MenuOpensOn.entries.first(),
    val menuBack: MenuBack = MenuBack.entries.first(),
    /** The preset last chosen (Settings → Remote buttons → Presets), for "Custom (from …)"; null: never chosen. */
    val presetBase: ControlPreset? = null,
    val liveSides: LiveSides = LiveSides.entries.first(),
    val startPage: StartPage = StartPage.entries.first(),
    val searchButton: SearchButton = SearchButton.entries.first(),
    /** Search button → "A button you choose": the key code learned. */
    val searchKeyCode: Int? = null,
    /** The last live channel played (for "Last channel" start). */
    val lastChannelId: Int? = null,
    /** Settings → Remote → Buttons while watching: an extra button per action (RemoteAction name → key code). */
    val buttons: Map<String, Int> = emptyMap(),
    /** Settings → Remote → the button table: changes from ButtonMap's defaults ("Screen.Press" → action name). */
    val buttonMap: Map<String, String> = emptyMap(),
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
            // Not chosen: the Viewarr preset's (Left carries on into catch-up; Back jumps to now, then the menu).
            guideLeft = p.readOr(GUIDE_LEFT, GuideLeft.AlwaysPast).let { if (it == GuideLeft.NowMenu) it else GuideLeft.AlwaysPast },
            // (As chosen: ButtonMap.guideBack keeps a way to the menu when "ask to exit" would leave none.)
            guideBack = p.readOr(GUIDE_BACK, GuideBack.Menu).let { if (it == GuideBack.FullScreen) GuideBack.NowThenExit else it },
            guideBackNow = p.readOr(GUIDE_BACK_NOW, GuideBackNow.On),
            channelKeys = p.read(CHANNEL_KEYS),
            zap = p.readOr(ZAP, ZapDirection.UpNext),
            seek = p.read(SEEK),
            playerOk = p.read(PLAYER_OK),
            rowMemory = p.read(ROW_MEMORY),
            rowAlign = p.read(ROW_ALIGN),
            rowLeft = p.read(ROW_LEFT),
            mediaBack = p.read(MEDIA_BACK),
            menuOpensOn = p.read(MENU_OPENS_ON),
            menuBack = p.read(MENU_BACK),
            liveSides = p.read(LIVE_SIDES),
            presetBase = p[stringPreferencesKey(PRESET_BASE)]?.let { n -> ControlPreset.entries.firstOrNull { it.name == n || (n == LEGACY_CLASSIC && it == ControlPreset.Classic) } },
            startPage = p.read(START_PAGE),
            searchButton = p.read(SEARCH_BUTTON),
            searchKeyCode = p[intPreferencesKey(SEARCH_KEYCODE)],
            lastChannelId = p[intPreferencesKey(LAST_CHANNEL)],
            buttons = buttonsOf(p[stringPreferencesKey(BUTTONS)]),
            buttonMap = buttonMapOf(p[stringPreferencesKey(BUTTON_MAP)]).let { m ->
                // (Since 0.28.5 the classic IPTV apps preset's hold OK in the guide is the record options, as Viewarr's: its old
                // "main menu" isn't kept, so those buttons still read as the preset and hold OK records.)
                if (p[stringPreferencesKey(PRESET_BASE)] in setOf(ControlPreset.Classic.name, LEGACY_CLASSIC) && m["Guide.OkHold"] == BtnAction.MainMenu.name) m - "Guide.OkHold" else m
            },
            recentChannels = p[stringPreferencesKey(RECENT_CHANNELS)].orEmpty().split(',').mapNotNull { it.toIntOrNull() },
            loaded = true,
        )
    }

    /** Stored extra buttons: "action=code,…". */
    private fun buttonsOf(raw: String?): Map<String, Int> = raw.orEmpty().split(',').mapNotNull { part ->
        val (name, code) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        code.toIntOrNull()?.let { name to it }
    }.toMap()

    /** The stored button table: "Screen.Press=action,…". */
    private fun buttonMapOf(raw: String?): Map<String, String> = raw.orEmpty().split(',').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        k to v
    }.toMap()

    suspend fun setSearchKeyCode(code: Int) {
        context.remoteStore.edit { it[intPreferencesKey(SEARCH_KEYCODE)] = code; it[stringPreferencesKey(SEARCH_BUTTON)] = SearchButton.Chosen.name }
    }

    /** Gives [action] the extra button [code] (taken from any other action that had it), or none (null). */
    suspend fun setButton(action: String, code: Int?) {
        context.remoteStore.edit { prefs ->
            val now = buttonsOf(prefs[stringPreferencesKey(BUTTONS)]).filter { (name, c) -> name != action && c != code }
            val next = if (code == null) now else now + (action to code)
            prefs[stringPreferencesKey(BUTTONS)] = next.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }

    /** Rows of the button table at once ("Screen.Press" → action name; null: back to the default). */
    suspend fun setMappings(rows: Map<String, String?>) {
        context.remoteStore.edit { prefs ->
            val now = buttonMapOf(prefs[stringPreferencesKey(BUTTON_MAP)]).toMutableMap()
            rows.forEach { (k, v) -> if (v == null) now.remove(k) else now[k] = v }
            prefs[stringPreferencesKey(BUTTON_MAP)] = now.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }

    suspend fun set(key: String, value: Enum<*>) {
        context.remoteStore.edit { it[stringPreferencesKey(key)] = value.name }
    }

    /**
     * A preset (see [ControlPreset]): every button on every screen set its way, the choices ([Presets.values]) and the
     * button table ([Presets.button]) alike, so nothing is left over from before: the Search button goes back to
     * Automatic and extra buttons taught for actions are removed too (either could take a key the preset uses, say
     * classic IPTV apps' Yellow for the controls).
     */
    suspend fun applyPreset(p: ControlPreset) {
        if (p == ControlPreset.Custom) return
        val values = Presets.values(p)
        context.remoteStore.edit { prefs ->
            prefs[stringPreferencesKey(PRESET_BASE)] = p.name
            values.forEach { (k, v) -> prefs[stringPreferencesKey(k)] = v.name }
            prefs.remove(stringPreferencesKey(SEARCH_BUTTON))
            prefs.remove(intPreferencesKey(SEARCH_KEYCODE))
            prefs.remove(stringPreferencesKey(BUTTONS))
            // The button table: only what differs from how Viewarr comes is stored.
            val base = Presets.settings(p)
            val map = ButtonMap.presses.flatMap { (screen, presses) ->
                presses.mapNotNull { press ->
                    Presets.button(p, screen, press, base).takeIf { it != ButtonMap.default(base, screen, press) }?.let { "${ButtonMap.key(screen, press)}=${it.name}" }
                }
            }
            if (map.isEmpty()) prefs.remove(stringPreferencesKey(BUTTON_MAP)) else prefs[stringPreferencesKey(BUTTON_MAP)] = map.joinToString(",")
        }
    }

    /** Only the button settings back to how Viewarr comes: the Viewarr preset in full (not where the app opens). */
    suspend fun resetButtons() {
        context.remoteStore.edit { prefs ->
            listOf(GUIDE_LEFT, GUIDE_BACK, GUIDE_BACK_NOW, CHANNEL_KEYS, ZAP, SEEK, PLAYER_OK, ROW_LEFT, MEDIA_BACK, MENU_BACK, SEARCH_BUTTON, BUTTONS, BUTTON_MAP, PRESET_BASE, LIVE_SIDES)
                .forEach { prefs.remove(stringPreferencesKey(it)) }
            prefs.remove(intPreferencesKey(SEARCH_KEYCODE))
        }
        // (Every button set, so it reads "Viewarr" straight after, whatever was stored before.)
        applyPreset(ControlPreset.Viewarr)
    }

    /** Some choice settings back to their defaults (a section's "Reset these buttons"). */
    suspend fun clear(keys: List<String>) {
        context.remoteStore.edit { prefs -> keys.forEach { prefs.remove(stringPreferencesKey(it)) } }
    }

    suspend fun setLastChannel(id: Int) {
        context.remoteStore.edit {
            it[intPreferencesKey(LAST_CHANNEL)] = id
            val before = it[stringPreferencesKey(RECENT_CHANNELS)].orEmpty().split(',').mapNotNull { n -> n.toIntOrNull() }
            it[stringPreferencesKey(RECENT_CHANNELS)] = (listOf(id) + (before - id)).take(12).joinToString(",")
        }
    }

    private inline fun <reified E : Enum<E>> Preferences.readOr(key: String, default: E): E =
        this[stringPreferencesKey(key)]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private inline fun <reified E : Enum<E>> Preferences.read(key: String): E = readOr(key, enumValues<E>().first())

    companion object {
        const val GUIDE_LEFT = "guide_left_v2" // v2: "at now opens the menu" became the default (2026-11-05)
        const val GUIDE_BACK = "guide_back_v2" // v2: "ask to exit" became the default (2026-11-05)
        const val GUIDE_BACK_NOW = "guide_back_now"
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
        const val PRESET_BASE = "preset_base"
        /** The Classic preset's name as saved before it was renamed (2026-11-17): still read as Classic. */
        const val LEGACY_CLASSIC = "TiviMate"
        const val LIVE_SIDES = "live_sides"
        const val START_PAGE = "start_page"
        const val SEARCH_BUTTON = "search_button"
        const val SEARCH_KEYCODE = "search_keycode"
        const val LAST_CHANNEL = "last_channel"
        const val BUTTONS = "buttons_v1"
        const val BUTTON_MAP = "bmap_v1"
        const val RECENT_CHANNELS = "recent_channels"
    }
}

/**
 * The presets in full: what every button does on every screen. Viewarr is how Viewarr comes; classic-style is as
 * classic IPTV apps comes (watching: Up / Down change channel, the next number Up; OK the player menu; Left the channel list,
 * Right the channel before; the guide's Left back to now, then the groups; Back jumps to now, then the groups; hold Back
 * straight back to full screen). Buttons classic IPTV apps has nothing for keep Viewarr's.
 */
object Presets {
    /** The choice settings a preset sets, by their key. */
    fun values(p: ControlPreset): Map<String, Enum<*>> = when (p) {
        ControlPreset.Classic -> mapOf(
            // (Back in classic IPTV apps' guide goes back to the player.)
            RemoteStore.GUIDE_LEFT to GuideLeft.NowMenu, RemoteStore.GUIDE_BACK to GuideBack.NowThenExit, RemoteStore.GUIDE_BACK_NOW to GuideBackNow.On, RemoteStore.CHANNEL_KEYS to ChannelKeys.Normal,
            RemoteStore.ZAP to ZapDirection.UpNext, RemoteStore.PLAYER_OK to PlayerOk.Controls, RemoteStore.LIVE_SIDES to LiveSides.ListAndLast,
            RemoteStore.SEEK to SeekStep.S30, RemoteStore.ROW_LEFT to RowLeft.Menu, RemoteStore.MEDIA_BACK to MediaBack.Exit,
            RemoteStore.MENU_OPENS_ON to MenuOpensOn.PageList, RemoteStore.MENU_BACK to MenuBack.TwiceToExit,
        )
        else -> mapOf(
            // classic IPTV apps', but Left carries on back into catch-up (no hold), so Back's second press opens the menu.
            RemoteStore.GUIDE_LEFT to GuideLeft.AlwaysPast, RemoteStore.GUIDE_BACK to GuideBack.Menu, RemoteStore.GUIDE_BACK_NOW to GuideBackNow.On, RemoteStore.CHANNEL_KEYS to ChannelKeys.Normal,
            RemoteStore.ZAP to ZapDirection.UpNext, RemoteStore.PLAYER_OK to PlayerOk.Controls, RemoteStore.LIVE_SIDES to LiveSides.Seek,
            RemoteStore.SEEK to SeekStep.S30, RemoteStore.ROW_LEFT to RowLeft.Menu, RemoteStore.MEDIA_BACK to MediaBack.Exit,
            RemoteStore.MENU_OPENS_ON to MenuOpensOn.PageList, RemoteStore.MENU_BACK to MenuBack.TwiceToExit,
        )
    }

    /** The button settings with a preset's choices (what its presses' own handling then does). */
    fun settings(p: ControlPreset, base: RemoteSettings = RemoteSettings()): RemoteSettings {
        val v = values(p)
        return base.copy(
            guideLeft = v[RemoteStore.GUIDE_LEFT] as? GuideLeft ?: base.guideLeft, guideBack = v[RemoteStore.GUIDE_BACK] as? GuideBack ?: base.guideBack,
            guideBackNow = v[RemoteStore.GUIDE_BACK_NOW] as? GuideBackNow ?: base.guideBackNow,
            channelKeys = v[RemoteStore.CHANNEL_KEYS] as? ChannelKeys ?: base.channelKeys, zap = v[RemoteStore.ZAP] as? ZapDirection ?: base.zap,
            playerOk = v[RemoteStore.PLAYER_OK] as? PlayerOk ?: base.playerOk, liveSides = v[RemoteStore.LIVE_SIDES] as? LiveSides ?: base.liveSides,
        )
    }

    /**
     * classic IPTV apps' own button settings (Settings → Remote control → TV guide / Player, as it comes), in Viewarr's
     * actions. Where classic IPTV apps does something Viewarr hasn't got (favourites, history, channel options…), Viewarr's
     * button stays.
     */
    private val classic: Map<Pair<BtnScreen, BtnPress>, BtnAction> = mapOf(
        (BtnScreen.Guide to BtnPress.OkHold) to BtnAction.RecordOptions, // (Peter, 2026-11-15: record options, as Viewarr)
        (BtnScreen.Guide to BtnPress.BackHold) to BtnAction.FullScreen,
        (BtnScreen.Guide to BtnPress.Menu) to BtnAction.MainMenu,
        (BtnScreen.Guide to BtnPress.MenuHold) to BtnAction.Search,
        (BtnScreen.Guide to BtnPress.PlayPause) to BtnAction.Description,
        (BtnScreen.Guide to BtnPress.Rewind) to BtnAction.PageUp,
        (BtnScreen.Guide to BtnPress.FastForward) to BtnAction.PageDown,
        (BtnScreen.Guide to BtnPress.Record) to BtnAction.Recordings,
        (BtnScreen.Guide to BtnPress.Info) to BtnAction.Description,
        (BtnScreen.Guide to BtnPress.Blue) to BtnAction.Settings,
        (BtnScreen.Live to BtnPress.Ok) to BtnAction.Controls,
        (BtnScreen.Live to BtnPress.OkHold) to BtnAction.MainMenu,
        (BtnScreen.Live to BtnPress.LeftHold) to BtnAction.ChannelList,
        (BtnScreen.Live to BtnPress.RightHold) to BtnAction.Description,
        (BtnScreen.Live to BtnPress.DownHold) to BtnAction.Search,
        (BtnScreen.Live to BtnPress.Menu) to BtnAction.MainMenu,
        (BtnScreen.Live to BtnPress.MenuHold) to BtnAction.Guide,
        (BtnScreen.Live to BtnPress.PlayPauseHold) to BtnAction.GoLive,
        (BtnScreen.Live to BtnPress.Record) to BtnAction.RecordNow,
        (BtnScreen.Live to BtnPress.Info) to BtnAction.Banner,
        (BtnScreen.Live to BtnPress.Red) to BtnAction.Guide,
        (BtnScreen.Live to BtnPress.Green) to BtnAction.GroupsMenu,
        (BtnScreen.Live to BtnPress.Yellow) to BtnAction.Controls,
        (BtnScreen.Live to BtnPress.Blue) to BtnAction.Settings,
    )

    /** What a press does on a screen in a preset. */
    fun button(p: ControlPreset, screen: BtnScreen, press: BtnPress, base: RemoteSettings = settings(p)): BtnAction =
        (if (p == ControlPreset.Classic) classic[screen to press] else null)?.takeIf { !(screen == BtnScreen.Guide && press == BtnPress.MenuHold && it == BtnAction.Search && base.searchButton != SearchButton.Auto) }
            ?: ButtonMap.default(base, screen, press)

    /** The preset the settings match exactly, else Custom. */
    fun matching(s: RemoteSettings): ControlPreset {
        val now: Map<String, Enum<*>> = mapOf(
            RemoteStore.GUIDE_LEFT to s.guideLeft, RemoteStore.GUIDE_BACK to s.guideBack, RemoteStore.GUIDE_BACK_NOW to s.guideBackNow, RemoteStore.CHANNEL_KEYS to s.channelKeys,
            RemoteStore.ZAP to s.zap, RemoteStore.PLAYER_OK to s.playerOk, RemoteStore.LIVE_SIDES to s.liveSides, RemoteStore.SEEK to s.seek,
            RemoteStore.ROW_LEFT to s.rowLeft, RemoteStore.MEDIA_BACK to s.mediaBack, RemoteStore.MENU_OPENS_ON to s.menuOpensOn, RemoteStore.MENU_BACK to s.menuBack,
        )
        return listOf(ControlPreset.Viewarr, ControlPreset.Classic).firstOrNull { p ->
            values(p) == now && ButtonMap.presses.all { (screen, presses) -> presses.all { ButtonMap.action(s, screen, it) == button(p, screen, it, settings(p, RemoteSettings(searchButton = s.searchButton))) } }
        } ?: ControlPreset.Custom
    }
}
