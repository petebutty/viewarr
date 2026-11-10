package io.github.petebutty.viewarr.ui.player

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.petebutty.viewarr.data.media.MediaLibrary
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.ui.common.AppIcons

/** One shortcut that can sit under the live TV controls. */
data class LiveShortcut(val id: String, val label: String, val icon: ImageVector)

/**
 * The shortcuts under the live TV controls (OK, then Down): ids saved in RemoteSettings.liveShortcuts in order,
 * "page:Guide", "home:Movie", "lib:Movie:<library key>", "row:Show:<row id>", "page:Settings"… Unset: [defaults].
 */
object LiveShortcuts {
    val defaults = listOf("page:Guide", "home:Movie", "home:Show", "page:Music", "page:Recordings", "page:Search", "page:Settings")

    /** Everything that can be a shortcut, in the order offered, each with a familiar icon. */
    fun candidates(
        movieLibs: List<MediaLibrary>, showLibs: List<MediaLibrary>,
        movieRows: List<MediaRow>, showRows: List<MediaRow>, musicOn: Boolean,
    ): List<LiveShortcut> = buildList {
        add(LiveShortcut("page:Guide", "TV guide", AppIcons.Guide))
        add(LiveShortcut("home:Movie", "Movies", AppIcons.Movie))
        add(LiveShortcut("home:Show", "Shows", AppIcons.Tv))
        if (musicOn) add(LiveShortcut("page:Music", "Music", AppIcons.MusicNote))
        add(LiveShortcut("page:Recordings", "Recordings", AppIcons.Record))
        add(LiveShortcut("page:Search", "Search", AppIcons.Search))
        add(LiveShortcut("page:Settings", "Settings", AppIcons.Settings))
        val sources = (movieLibs + showLibs).map { it.source }.distinct().size > 1
        movieLibs.forEach { add(LiveShortcut("lib:Movie:${it.key}", libName(it, sources), AppIcons.Library)) }
        showLibs.forEach { add(LiveShortcut("lib:Show:${it.key}", libName(it, sources), AppIcons.Library)) }
        movieRows.forEach { add(LiveShortcut("row:Movie:${it.id}", "${it.title} (films)", AppIcons.Layers)) }
        showRows.forEach { add(LiveShortcut("row:Show:${it.id}", "${it.title} (shows)", AppIcons.Layers)) }
    }

    private fun libName(l: MediaLibrary, sources: Boolean) = if (sources) "${l.title} · ${l.source.removePrefix("Plex · ")}" else l.title
}
