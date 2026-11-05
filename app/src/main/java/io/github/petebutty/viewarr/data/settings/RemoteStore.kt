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
    PastTapMenuHold("Tap: earlier programmes · Hold: menu"),
    MenuTapPastHold("Tap: menu · Hold: earlier programmes"),
    AlwaysPast("Always earlier programmes"),
}

enum class GuideBack(override val label: String) : RemoteChoice {
    NowThenMenu("Jump to now, then menu"),
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

enum class PlayerOk(override val label: String) : RemoteChoice {
    Controls("Open the controls"),
    Banner("Show the info banner"),
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

data class RemoteSettings(
    val guideLeft: GuideLeft = GuideLeft.entries.first(),
    val guideBack: GuideBack = GuideBack.entries.first(),
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
    val startPage: StartPage = StartPage.entries.first(),
    /** The last live channel played (for "Last channel" start). */
    val lastChannelId: Int? = null,
    /** False until the stored settings have been read. */
    val loaded: Boolean = false,
)

/** Read anywhere in the UI; provided at the app root and updated live when a setting changes. */
val LocalRemote = staticCompositionLocalOf { RemoteSettings() }

private val Context.remoteStore by preferencesDataStore(name = "remote")

@Singleton
class RemoteStore @Inject constructor(@ApplicationContext private val context: Context) {

    val settings: Flow<RemoteSettings> = context.remoteStore.data.map { p ->
        RemoteSettings(
            guideLeft = p.read(GUIDE_LEFT),
            guideBack = p.read(GUIDE_BACK),
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
            startPage = p.read(START_PAGE),
            lastChannelId = p[intPreferencesKey(LAST_CHANNEL)],
            loaded = true,
        )
    }

    suspend fun set(key: String, value: Enum<*>) {
        context.remoteStore.edit { it[stringPreferencesKey(key)] = value.name }
    }

    suspend fun reset() {
        context.remoteStore.edit { prefs ->
            val last = prefs[intPreferencesKey(LAST_CHANNEL)]
            prefs.clear()
            last?.let { prefs[intPreferencesKey(LAST_CHANNEL)] = it }
        }
    }

    suspend fun setLastChannel(id: Int) {
        context.remoteStore.edit { it[intPreferencesKey(LAST_CHANNEL)] = id }
    }

    private inline fun <reified E : Enum<E>> Preferences.read(key: String): E =
        this[stringPreferencesKey(key)]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: enumValues<E>().first()

    companion object {
        const val GUIDE_LEFT = "guide_left"
        const val GUIDE_BACK = "guide_back"
        const val CHANNEL_KEYS = "channel_keys"
        const val ZAP = "zap_v2" // v2: "Show the channel list" became the default (2026-11-05)
        const val SEEK = "seek"
        const val PLAYER_OK = "player_ok"
        const val ROW_MEMORY = "row_memory"
        const val ROW_ALIGN = "row_align"
        const val ROW_LEFT = "row_left"
        const val MEDIA_BACK = "media_back"
        const val MENU_OPENS_ON = "menu_opens_on"
        const val MENU_BACK = "menu_back"
        const val START_PAGE = "start_page"
        const val LAST_CHANNEL = "last_channel"
    }
}
