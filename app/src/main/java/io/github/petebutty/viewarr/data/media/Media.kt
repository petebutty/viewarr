package io.github.petebutty.viewarr.data.media

enum class MediaType { Movie, Show }

/** One poster in a row. tmdbId / imdbId tie the same title together across sources. */
data class MediaItem(
    val key: String,
    val type: MediaType,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val rating: Double? = null,
    /** Where this entry came from, e.g. "Plex" or the addon name. */
    val origin: String,
    /** Extra line under the title, e.g. "S2 · E4" for continue watching. */
    val subtitle: String? = null,
    /** 0..1 watched, for continue watching. */
    val progress: Float? = null,
    /** Shows in continue watching: the episode to play next. */
    val nextSeason: Int? = null,
    val nextEpisode: Int? = null,
)

data class EpisodeInfo(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String?,
    val still: String?,
    val airDate: String?,
    val runtimeMin: Int?,
)

/** One episode in your Plex library: its item key, resume point and whether it's been watched. */
data class PlexEpisode(val ratingKey: String, val viewOffsetMs: Long, val watched: Boolean)

data class MediaRow(val id: String, val title: String, val items: List<MediaItem>)

/** A copy of a title on one of the user's own servers (Plex, Jellyfin, Emby). */
data class LibraryCopy(val source: String, val detail: String? = null, val ref: String? = null)

/** Something the player can open. */
data class VodStream(
    val url: String,
    val title: String,
    val headers: Map<String, String> = emptyMap(),
    val resumeMs: Long = 0,
    /** Tried once if this one can't play (e.g. Plex transcoding when direct play fails). */
    val fallback: VodStream? = null,
    /** What to report watch progress for (Plex timeline, Trakt scrobble). */
    val watch: WatchTarget? = null,
    /** The server's own seek-bar previews, when it made them (else the box takes its own; see SeekThumbs). */
    val thumbs: ThumbSource? = null,
)

/** Where a server keeps its seek-bar preview pictures. */
sealed interface ThumbSource {
    /** One BIF file with every preview (Plex, Emby). */
    data class Bif(val url: String, val headers: Map<String, String> = emptyMap()) : ThumbSource

    /** Jellyfin trickplay: numbered sheets of tileWidth × tileHeight previews of width × height, one every intervalMs. */
    data class Trickplay(
        val urlPattern: String,
        val width: Int,
        val height: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val count: Int,
        val intervalMs: Long,
        val headers: Map<String, String> = emptyMap(),
    ) : ThumbSource {
        fun sheetUrl(n: Int) = urlPattern.replace("{n}", n.toString())
    }
}

/** Identifies what's being watched, for progress reporting. */
data class WatchTarget(
    val type: MediaType,
    val plexRatingKey: String? = null,
    /** Jellyfin / Emby: which server ("Jellyfin" or "Emby") and the item's id there, for saving your place. */
    val serverSource: String? = null,
    val serverItemId: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/** One stream an addon offered for a title. */
data class StreamOption(val addon: String, val name: String, val description: String, val url: String, val headers: Map<String, String>)

data class CastMember(val name: String, val character: String?)

data class SeasonInfo(val number: Int, val name: String, val episodes: Int, val year: Int?)

data class MediaDetail(
    val item: MediaItem,
    val tagline: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMin: Int? = null,
    val certification: String? = null,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<SeasonInfo> = emptyList(),
    val status: String? = null,
)

data class RowsState(val rows: List<MediaRow>, val done: Boolean)
