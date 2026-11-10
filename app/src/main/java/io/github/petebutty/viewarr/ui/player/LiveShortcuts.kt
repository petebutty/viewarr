package io.github.petebutty.viewarr.ui.player

import io.github.petebutty.viewarr.data.media.MediaLibrary
import io.github.petebutty.viewarr.data.media.MediaRow

/**
 * The shortcuts under the live TV controls (Down from the buttons): ids saved in RemoteSettings.liveShortcuts,
 * "home:Movie", "lib:Movie:<library key>", "row:Show:<row id>", "page:Music"… Unset: [defaults].
 */
object LiveShortcuts {
    val defaults = listOf("home:Movie", "home:Show", "page:Music", "page:Recordings", "page:Search")

    /** Everything that can be a shortcut, in the order offered (id to name). */
    fun candidates(
        movieLibs: List<MediaLibrary>, showLibs: List<MediaLibrary>,
        movieRows: List<MediaRow>, showRows: List<MediaRow>, musicOn: Boolean,
    ): List<Pair<String, String>> = buildList {
        add("home:Movie" to "Movies")
        add("home:Show" to "Shows")
        if (musicOn) add("page:Music" to "Music")
        add("page:Recordings" to "Recordings")
        add("page:Search" to "Search")
        val sources = (movieLibs + showLibs).map { it.source }.distinct().size > 1
        movieLibs.forEach { add("lib:Movie:${it.key}" to libName(it, sources)) }
        showLibs.forEach { add("lib:Show:${it.key}" to libName(it, sources)) }
        movieRows.forEach { add("row:Movie:${it.id}" to "${it.title} (films)") }
        showRows.forEach { add("row:Show:${it.id}" to "${it.title} (shows)") }
    }

    private fun libName(l: MediaLibrary, sources: Boolean) = if (sources) "${l.title} · ${l.source.removePrefix("Plex · ")}" else l.title
}
