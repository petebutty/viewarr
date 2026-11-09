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
    /** Continue watching: the film's or episode's full length (for Watch Next's progress bar). */
    val durationMs: Long? = null,
    /** Search: a TMDB result that's also in one of your libraries (a tick on the poster). */
    val owned: Boolean = false,
    /** Search: where you have it ("Plex", "Jellyfin", "IPTV"…), shown on the poster instead of a plain tick. */
    val ownedFrom: String? = null,
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

/**
 * A row of titles. Rows with the same [group] are shown as one (e.g. continue watching on every server, or the
 * same library's recently added on two Plex servers); [groupName] is the library name for the merged title.
 */
data class MediaRow(val id: String, val title: String, val items: List<MediaItem>, val group: String? = null, val groupName: String? = null)

/**
 * A library of films or shows on Plex, Jellyfin or Emby. [key] is unique across servers; [server] is the Plex server's
 * id (several Plex servers), [id] the library's own id on its server.
 */
data class MediaLibrary(val key: String, val title: String, val source: String, val id: String, val server: String? = null)

/** How a whole library is listed when browsing it. */
enum class LibrarySort(val label: String, val plex: String, val jellyfin: Pair<String, String>) {
    Title("A–Z", "titleSort:asc", "SortName" to "Ascending"),
    Added("Date added", "addedAt:desc", "DateCreated" to "Descending"),
    Released("Release date", "originallyAvailableAt:desc", "PremiereDate,SortName" to "Descending"),
    Rating("Rating", "audienceRating:desc", "CommunityRating,SortName" to "Descending"),
}

/** A copy of a title on one of the user's own servers (Plex, Jellyfin, Emby). */
/** A person's page header: photo, life dates and place, and their TMDB biography. */
data class PersonInfo(
    val name: String,
    val photo: String?,
    val bio: String?,
    val born: String?,
    val died: String?,
    val place: String?,
    val knownFor: String?,
    /** Still fetching the biography (shows "Loading…"); false once TMDB has answered, with or without one. */
    val loading: Boolean = false,
)

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
    /** Start exactly here, whatever the resume setting (e.g. after switching player mid-film). */
    val startMs: Long? = null,
    /**
     * Let the box take its own previews by reading the file a second time. Off for IPTV providers: most allow one
     * connection per account, so a second one could cut off the film (or the live TV).
     */
    val boxPreviews: Boolean = true,
    /** Picked on the title's page before Play: which embedded audio / subtitle track (counting from 0 within its
     *  kind; subtitles -1 = off). Null = the usual choice (Settings → Playback). */
    val audioTrack: Int? = null,
    val subtitleTrack: Int? = null,
)

/** An audio or subtitle track of a film on your own server, to pick before Play. [ordinal]: its place among the
 *  file's own tracks of that kind (as the players count them). */
data class TrackChoice(val label: String, val ordinal: Int, val default: Boolean = false)

/** A film's tracks on one of your servers ([ref]: that copy), for Audio & subtitles on its page. */
data class TitleTracks(val source: String, val ref: String, val audio: List<TrackChoice>, val subtitles: List<TrackChoice>)

/** Pictures for the player: the title's backdrop (loading screen) and its logo (loading screen and controls). */
data class PlayerArt(
    val backdrop: String?,
    val logo: String?,
    /** For the details panel (Info button, or paused): poster, the episode's line, facts and synopsis. */
    val poster: String? = null,
    val episodeLine: String? = null,
    val facts: String? = null,
    val overview: String? = null,
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

data class CastMember(val name: String, val character: String?, val photo: String? = null, val id: Int? = null)

/** One rating from one source, e.g. ("IMDb", "7.8") or ("Rotten Tomatoes", "87%"). */
data class RatingBadge(val source: String, val value: String, val votes: String? = null)

/** A review (TMDB): author, their score out of 10 if given, the text, and when. */
data class Review(val author: String, val rating: Double?, val text: String, val date: String?, val source: String = "TMDB", val spoiler: Boolean = false, val likes: Int = 0)

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
    /** Directors (films) or creators (shows), and writers. */
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    /** Studio (films) or network (shows). */
    val studio: String? = null,
    val released: String? = null,
    val language: String? = null,
    val budget: Long? = null,
    val revenue: Long? = null,
    val voteCount: Int? = null,
    val reviews: List<Review> = emptyList(),
    val similar: List<MediaItem> = emptyList(),
    /** The collection a film belongs to ("Dune Collection") and all its films, in release order. */
    val collectionName: String? = null,
    val collection: List<MediaItem> = emptyList(),
)

data class RowsState(val rows: List<MediaRow>, val done: Boolean)
