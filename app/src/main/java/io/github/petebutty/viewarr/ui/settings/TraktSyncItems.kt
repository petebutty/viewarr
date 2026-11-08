package io.github.petebutty.viewarr.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.media.CatalogRepository
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TraktSyncViewModel @Inject constructor(
    private val store: AccountStore,
    private val repo: CatalogRepository,
) : ViewModel() {
    val accounts = store.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, Accounts())
    val plexStatus = repo.plexCopyStatus.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    init {
        repo.traktWatchedState.value?.let { _status.value = summary(it) }
    }

    private fun summary(w: CatalogRepository.TraktWatched): String {
        val mins = ((System.currentTimeMillis() - w.fetchedAt) / 60_000).toInt()
        val ago = if (mins < 1) "just now" else if (mins == 1) "1 minute ago" else "$mins minutes ago"
        return "${w.movieCount} films and ${w.episodeCount} episodes watched · checked $ago"
    }

    fun syncNow() = viewModelScope.launch {
        _status.value = "Syncing with Trakt…"
        val w = runCatching { repo.traktWatched(force = true) }.getOrNull()
        _status.value = w?.let { summary(it) } ?: "Couldn't reach Trakt. Try again in a moment."
    }

    fun toggleCopy() = viewModelScope.launch {
        val on = !accounts.value.traktToPlex
        store.update { it.copy(traktToPlex = on) }
        // Switched on: copy what's already there straight away.
        if (on) repo.traktWatched(force = true)?.let { runCatching { repo.copyTraktToPlex(it) } }
    }
}

/** Settings → Accounts & sources → Trakt: sync now, and copy watched to Plex. */
@Composable
fun TraktSyncItems() {
    val vm: TraktSyncViewModel = hiltViewModel()
    val a by vm.accounts.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val plexStatus by vm.plexStatus.collectAsStateWithLifecycle()
    if (a.trakt == null) return
    MenuItem(text = "Sync with Trakt now", selected = false, onClick = { vm.syncNow() })
    status?.let { Text(it, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)) }
    if (a.plex != null) {
        MenuItem(text = "Copy watched to Plex", trailing = if (a.traktToPlex) "On" else "Off", selected = false, onClick = { vm.toggleCopy() })
        Text(
            plexStatus ?: "Marks on your Plex servers what Trakt says you've watched (never un-marks anything).",
            fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
    }
}
