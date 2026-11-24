package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.settings.RowLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * My Library's page: the menu's My Library, highlighted, shows your own servers' rows (Plex, Jellyfin, Emby, IPTV:
 * Continue watching, Recently added, each library's Recently added, your collections) from Movies and Shows together
 * on one page, as Movies shows its rows. (2026-11-17: it used to leave the TV guide behind.)
 *
 * Each page's view model publishes its own servers' rows here ([publish]) and the saved choices ([setLayout], from
 * Settings → Home page & menu → My Library); the Movies page draws them while its row set is [SET].
 */
object MyLibrary {
    /** The Movies page's row set while it's showing My Library (MediaViewModel.showRowSet). */
    const val SET = "mine"

    /** One row as My Library lists it: the page it comes from, its saved key ("M:<id>" / "S:<id>"), on or off. */
    data class Entry(val type: MediaType, val row: MediaRow, val key: String, val off: Boolean)

    data class State(val own: Map<MediaType, List<MediaRow>> = emptyMap(), val layout: RowLayout = RowLayout()) {
        /** Every row, in My Library's order (the ones off kept in their places). */
        val entries: List<Entry> by lazy {
            val all = listOf(MediaType.Movie, MediaType.Show).flatMap { t -> own[t].orEmpty().map { t to it } }
            // Never arranged: Continue watching first (films, then shows), then the films' rows, then the shows'.
            val default = all.filter { isDeck(it.second) } + all.filterNot { isDeck(it.second) }
            layout.orderedBy(default) { key(it.first, it.second) }.map { (t, r) ->
                val k = key(t, r)
                Entry(t, r, k, if (r.id.startsWith("col-")) "+$k" !in layout.hidden else k in layout.hidden)
            }
        }

        /** The page's rows: the ones on, a shows row under its own id (Movies and Shows can share one: "merged:deck"). */
        val pageRows: List<MediaRow> by lazy {
            val on = entries.filter { !it.off }
            val twice = on.groupingBy { it.row.title }.eachCount().filter { it.value > 1 }.keys
            on.map { e ->
                val title = if (e.row.title in twice) e.row.title + if (e.type == MediaType.Show) " (shows)" else " (films)" else e.row.title
                if (e.type == MediaType.Show) e.row.copy(id = SHOW_PREFIX + e.row.id, title = title) else e.row.copy(title = title)
            }
        }
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** A page's rows as it shows them; only your own servers' are kept. [origin]: where a row comes from (rowOrigin). */
    fun publish(type: MediaType, rows: List<MediaRow>, origin: (MediaRow) -> String) {
        val own = rows.filter { isOwn(it, origin(it)) }
        if (_state.value.own[type] != own) _state.update { it.copy(own = it.own + (type to own)) }
    }

    fun setLayout(layout: RowLayout) { if (_state.value.layout != layout) _state.update { it.copy(layout = layout) } }

    fun key(type: MediaType, row: MediaRow) = (if (type == MediaType.Show) "S:" else "M:") + row.id

    /** The id a shows row has on the My Library page (a row id never starts with it otherwise). */
    const val SHOW_PREFIX = "mylib-show:"

    private val OWN = setOf("Your servers", "Plex", "Jellyfin", "Emby", "IPTV")
    /** Your servers' rows: not their watchlists / favourites (those are lists, not your library). */
    private fun isOwn(row: MediaRow, origin: String) =
        (origin in OWN || row.id.startsWith("iptv-")) && !row.id.contains("watchlist") && !row.id.contains("-favourites-")
    private fun isDeck(row: MediaRow) = row.group == "deck" || row.title.startsWith("Continue watching")
}
