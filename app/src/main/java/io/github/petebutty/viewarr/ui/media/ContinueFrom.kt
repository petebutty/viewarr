package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * Continue watching, server by server (Settings → Home page & menu → Continue watching from; Peter, 2026-11-18: a
 * switch for each library). Kept as palette title parts, on to start: "cw:<source>".
 */
object ContinueFrom {
    const val IPTV = "IPTV"
    const val TRAKT = "Trakt"
    fun key(source: String) = "cw:" + source.lowercase()
    /** Watching resumes from [source] go in Continue watching. (Read in composition: the palette is state.) */
    fun on(source: String) = AppColors.palette.shows(key(source))

    /** A Continue watching row's source: the main Plex's is "Plex", the others say so ("Continue watching on X"). */
    fun ofRow(row: MediaRow): String? = when {
        row.id.startsWith("upcoming-") -> null
        row.title == "Continue watching" && row.group == "deck" -> "Plex"
        row.title.startsWith("Continue watching on ") -> row.title.removePrefix("Continue watching on ")
        else -> null
    }

    /** A title's source by where it came from ("Plex · Kids server" → "Kids server"). */
    fun ofItem(item: MediaItem): String = item.origin.substringAfter("Plex · ", item.origin)
}
