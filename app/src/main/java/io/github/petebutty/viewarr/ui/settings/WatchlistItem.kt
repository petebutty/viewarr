package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.media.CatalogRepository
import io.github.petebutty.viewarr.ui.common.MenuItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val store: AccountStore,
    private val repo: CatalogRepository,
) : ViewModel() {
    val accounts = store.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, Accounts())
    fun choices(a: Accounts) = repo.watchlistChoices(a)
    fun current(a: Accounts) = repo.watchlistSource(a)

    /** OK: the next connected watchlist. */
    fun next() = viewModelScope.launch {
        val a = store.current()
        val list = repo.watchlistChoices(a)
        if (list.size < 2) return@launch
        val i = list.indexOf(repo.watchlistSource(a))
        store.update { it.copy(watchlist = list[(i + 1) % list.size]) }
    }
}

/** Settings → Watchlist: which watchlist Add / Remove and the "Your watchlist" row use. */
@Composable
fun WatchlistItem(modifier: Modifier = Modifier, vm: WatchlistViewModel = hiltViewModel()) {
    val a by vm.accounts.collectAsStateWithLifecycle()
    val choices = vm.choices(a)
    if (choices.isEmpty()) return
    val label = when (vm.current(a)) {
        "trakt" -> "Trakt"; "plex" -> "Plex"; "jellyfin" -> "Jellyfin favourites"; "emby" -> "Emby favourites"; else -> "–"
    }
    MenuItem(text = "Watchlist", trailing = label, selected = false, dim = choices.size < 2, onClick = { vm.next() }, modifier = modifier)
}
