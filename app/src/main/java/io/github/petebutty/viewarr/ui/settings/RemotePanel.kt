package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
}

private inline fun <reified E> all(): List<RemoteChoice> where E : Enum<E>, E : RemoteChoice = enumValues<E>().toList()

/** Settings → Remote & navigation, full screen like Playback: what each button does, grouped by where you are. */
@Composable
fun RemoteSettingsScreen(onBack: () -> Unit, vm: RemoteViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    fun item(title: String, description: String, key: String, choices: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, choices, current) { vm.set(key, it) }
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
                item("Left", "Default: Left goes back to earlier programmes; on what's on now it opens the menu, and holding Left (or ⏪) goes back into catch-up.", k.GUIDE_LEFT, all<GuideLeft>(), s.guideLeft),
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
            ),
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
    SettingsPage(title = "Remote & navigation", categories = categories, onBack = onBack, onReset = { vm.reset() })
}
