package io.github.petebutty.viewarr.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.media.CatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Trakt's sync now and Copy watched to Plex, for Settings → Playback → Watch progress (WatchProgressItems.kt). */
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
