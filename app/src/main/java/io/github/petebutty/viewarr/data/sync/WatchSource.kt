package io.github.petebutty.viewarr.data.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts

/**
 * What's connected for "what you've watched", and the few rules that follow from it. There's no choosing one place
 * any more (2026-11-19: "it should all just work"): every play is reported everywhere it belongs (the server it
 * plays from, Trakt when connected, this box's own record for IPTV), Mark as watched goes everywhere the title is,
 * and ticks, bars, Continue watching and Up next read all of them together (ui/media/WatchState.kt).
 */
object WatchSource {
    // What's signed in, as last read from the accounts (AccountStore notes it on every read). State, so settings and
    // pages redraw when an account is added or removed.
    var hasTrakt by mutableStateOf(false)
        private set
    var hasServer by mutableStateOf(false)
        private set
    /** Which servers are signed in, for the settings page ("Plex", "Jellyfin", "Emby"). */
    var serverNames by mutableStateOf(emptyList<String>())
        private set

    /** The accounts have been read at least once (before that nothing automatic that depends on them runs). */
    @Volatile var known = false
        private set

    fun note(a: Accounts) {
        known = true
        hasTrakt = a.trakt != null
        serverNames = listOfNotNull(if (a.plex != null || a.plexMore.isNotEmpty()) "Plex" else null, a.jellyfin?.let { "Jellyfin" }, a.emby?.let { "Emby" })
        hasServer = serverNames.isNotEmpty()
    }

    /**
     * Viewarr tells Trakt about plays and marks: Trakt connected and Settings → Playback → Watch progress → Send plays
     * to Trakt on (it is to start). Off, Trakt is still read for ticks and Continue watching.
     */
    val sendToTrakt get() = hasTrakt && AppColors.palette.opt(UiOpts.SEND_TO_TRAKT) == 0

    /** Counts as watched past this much of the way through (as Trakt and the servers count it). */
    const val WATCHED_AT = 0.8f
}

/** Where Trakt says you stopped part-way (GET /sync/playback): [pct] 0–1 and when ([at], ms; 0 = not known). */
data class TraktPos(val pct: Float, val at: Long)
