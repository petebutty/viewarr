package io.github.petebutty.viewarr.data.music

import io.github.petebutty.viewarr.data.accounts.MediaServerAuth
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.MediaServerService
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.long
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/**
 * One music library on a Jellyfin or Emby server as a [MusicSource] (they share the API). Each library is its own
 * source, so paging through albums keeps one place per library.
 */
class JellyfinMusicSource private constructor(
    private val http: HttpJson,
    private val headers: Map<String, String>,
    private val s: MediaServerAuth,
    private val emby: Boolean,
    library: String,
    libraryName: String?,
    /** Playlists and artist search belong to the whole server: only its first music library gives them. */
    private val first: Boolean,
) : MusicSource {
    private val libraries = listOf(library)
    override val id = (if (emby) "emby" else "jf") + if (first) "" else library.filter { it.isLetterOrDigit() }.take(12)
    override val name = (if (emby) "Emby · " else "Jellyfin · ") + s.serverName + (libraryName?.let { " · $it" } ?: "")

    companion object {
        /** The server's music libraries (empty if it has none). Throws if the server can't be reached. */
        suspend fun find(http: HttpJson, servers: MediaServerService, deviceId: String, s: MediaServerAuth, emby: Boolean): List<JellyfinMusicSource> {
            val h = servers.headers(emby, deviceId, s.token)
            val r = http.get("${s.serverUrl}/Users/${s.userId}/Views", h)
            if (!r.ok) error("Server didn't answer")
            val music = r.body.array("Items").filter { it.str("CollectionType") == "music" }
            return music.mapIndexedNotNull { i, v ->
                val lib = v.str("Id") ?: return@mapIndexedNotNull null
                JellyfinMusicSource(http, h, s, emby, lib, v.str("Name").takeIf { music.size > 1 }, first = i == 0)
            }
        }
    }

    private val u get() = "${s.serverUrl}/Users/${s.userId}"
    private suspend fun get(url: String): JsonElement? = runCatching { http.get(url, headers).takeIf { it.ok }?.body }.getOrNull()

    private fun art(itemId: String?) = itemId?.let {
        Art("${s.serverUrl}/Items/$it/Images/Primary?maxWidth=300&quality=85", "${s.serverUrl}/Items/$it/Images/Primary?maxWidth=800&quality=90")
    }
    private fun time(v: String?) = v?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun album(j: JsonElement) = Album(
        id = "$id:" + j.str("Id").orEmpty(), name = j.str("Name") ?: "Unknown album",
        artist = j.str("AlbumArtist") ?: j.array("AlbumArtists").firstOrNull().str("Name") ?: "",
        artistId = j.array("AlbumArtists").firstOrNull().str("Id")?.let { "$id:$it" }, year = j.int("ProductionYear"),
        art = art(j.str("Id")), songCount = j.int("ChildCount") ?: 0,
        added = time(j.str("DateCreated")), played = time(j.child("UserData").str("LastPlayedDate")), plays = j.child("UserData").int("PlayCount"), source = id,
    )
    private fun artist(j: JsonElement) = Artist("$id:" + j.str("Id").orEmpty(), j.str("Name") ?: "Unknown artist", j.int("AlbumCount") ?: j.int("ChildCount") ?: 0, art(j.str("Id")), id)
    private fun song(j: JsonElement): Song {
        val sid = j.str("Id").orEmpty()
        return Song(
            id = "$id:$sid", title = j.str("Name") ?: "Unknown",
            artist = j.array("Artists").firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: j.str("AlbumArtist") ?: "",
            album = j.str("Album") ?: "", albumId = j.str("AlbumId")?.let { "$id:$it" }, track = j.int("IndexNumber"), disc = j.int("ParentIndexNumber"),
            durationSec = ((j.long("RunTimeTicks") ?: 0L) / 10_000_000L).toInt(), art = art(j.str("AlbumId") ?: sid), year = j.int("ProductionYear"),
            streamUrl = "${s.serverUrl}/Audio/$sid/stream?static=true&api_key=${enc(s.token)}", source = id,
        )
    }

    private val fields = "Fields=DateCreated,ChildCount,PrimaryImageAspectRatio&EnableUserData=true"

    /** Items from each music library, joined. */
    private suspend fun items(query: String, size: Int, offset: Int): List<JsonElement> = libraries.flatMap { lib ->
        get("$u/Items?ParentId=$lib&Recursive=true&$fields&$query&StartIndex=$offset&Limit=$size").array("Items")
    }

    override suspend fun albums(list: AlbumList, size: Int, offset: Int): List<Album> {
        val q = when (list) {
            AlbumList.Newest -> "SortBy=DateCreated&SortOrder=Descending"
            AlbumList.Recent -> "SortBy=DatePlayed&SortOrder=Descending&Filters=IsPlayed"
            AlbumList.Frequent -> "SortBy=PlayCount&SortOrder=Descending&Filters=IsPlayed"
            AlbumList.Random -> "SortBy=Random"
            AlbumList.Favourites -> "SortBy=SortName&Filters=IsFavorite"
            AlbumList.AZ -> "SortBy=SortName"
        }
        return items("IncludeItemTypes=MusicAlbum&$q", size, offset).map(::album)
    }
    override suspend fun album(id: String): Pair<Album, List<Song>>? {
        val a = get("$u/Items/${localId(id)}") ?: return null
        val tracks = get("$u/Items?ParentId=${localId(id)}&IncludeItemTypes=Audio&SortBy=ParentIndexNumber,IndexNumber,SortName").array("Items")
        return album(a) to tracks.map(::song)
    }
    override suspend fun artists() = libraries.flatMap { lib ->
        get("${s.serverUrl}/Artists/AlbumArtists?ParentId=$lib&UserId=${s.userId}&SortBy=SortName&Fields=ChildCount").array("Items")
    }.map(::artist)
    override suspend fun artist(id: String): Pair<Artist, List<Album>>? {
        val a = get("$u/Items/${localId(id)}") ?: return null
        val albums = get("$u/Items?AlbumArtistIds=${localId(id)}&IncludeItemTypes=MusicAlbum&Recursive=true&$fields&SortBy=ProductionYear&SortOrder=Descending").array("Items")
        return artist(a) to albums.map(::album)
    }
    override suspend fun playlists() = if (!first) emptyList() else get("$u/Items?IncludeItemTypes=Playlist&Recursive=true&MediaTypes=Audio&Fields=ChildCount").array("Items").map { j ->
        Playlist("$id:" + j.str("Id").orEmpty(), j.str("Name") ?: "Playlist", j.int("ChildCount") ?: 0, art(j.str("Id")), ((j.long("RunTimeTicks") ?: 0L) / 10_000_000L).toInt(), id)
    }
    override suspend fun playlist(id: String): Pair<Playlist, List<Song>>? {
        val p = get("$u/Items/${localId(id)}") ?: return null
        val songs = get("${s.serverUrl}/Playlists/${localId(id)}/Items?UserId=${s.userId}").array("Items").map(::song)
        return Playlist(id, p.str("Name") ?: "Playlist", songs.size, art(localId(id)), songs.sumOf { it.durationSec }, this.id) to songs
    }
    private val genreIds = HashMap<String, String>()
    override suspend fun genres() = libraries.flatMap { lib ->
        get("${s.serverUrl}/MusicGenres?ParentId=$lib&UserId=${s.userId}&SortBy=SortName").array("Items").mapNotNull { g ->
            val n = g.str("Name") ?: return@mapNotNull null
            g.str("Id")?.let { genreIds[MusicLibrary.norm(n)] = it }
            n to 0
        }
    }
    override suspend fun albumsInGenre(genre: String, size: Int, offset: Int): List<Album> {
        if (genreIds.isEmpty()) genres()
        val g = genreIds[MusicLibrary.norm(genre)] ?: return emptyList()
        return items("IncludeItemTypes=MusicAlbum&GenreIds=$g&SortBy=SortName", size, offset).map(::album)
    }
    override suspend fun randomSongs(size: Int) = items("IncludeItemTypes=Audio&SortBy=Random", size, 0).map(::song)
    override suspend fun search(query: String): MusicSearchResult {
        val q = "SearchTerm=${enc(query)}&Limit=25"
        val artists = if (!first) emptyList() else get("${s.serverUrl}/Artists?$q&UserId=${s.userId}").array("Items").map(::artist)
        val albums = get("$u/Items?$q&IncludeItemTypes=MusicAlbum&Recursive=true&$fields").array("Items").map(::album)
        val songs = get("$u/Items?$q&IncludeItemTypes=Audio&Recursive=true").array("Items").map(::song)
        return MusicSearchResult(artists, albums, songs)
    }
    // ---- The Albums grid

    override suspend fun albumOptions() = listOf(
        io.github.petebutty.viewarr.data.media.SortOption("title", "Title", false), io.github.petebutty.viewarr.data.media.SortOption("artist", "Artist", false),
        io.github.petebutty.viewarr.data.media.SortOption("added", "Date added"), io.github.petebutty.viewarr.data.media.SortOption("played", "Recently played"),
        io.github.petebutty.viewarr.data.media.SortOption("plays", "Most played"), io.github.petebutty.viewarr.data.media.SortOption("year", "Year"),
        io.github.petebutty.viewarr.data.media.SortOption("jf.CommunityRating", "Rating"), io.github.petebutty.viewarr.data.media.SortOption("random", "Random"),
    ) to listOf(
        io.github.petebutty.viewarr.data.media.FilterGroup("favourites", "Favourites", toggle = true),
        io.github.petebutty.viewarr.data.media.FilterGroup("genre", "Genre"),
        io.github.petebutty.viewarr.data.media.FilterGroup("decade", "Decade"),
    )

    override suspend fun albumFilterValues(group: String) = when (group) {
        "genre" -> genres().map { (g, _) -> io.github.petebutty.viewarr.data.media.FilterValue(g, g) }
        "decade" -> decades()
        else -> emptyList()
    }

    override suspend fun browseAlbums(q: io.github.petebutty.viewarr.data.media.LibraryQuery, size: Int, offset: Int): List<Album> {
        val by = when (q.sort) {
            "artist" -> "AlbumArtist,SortName"; "added" -> "DateCreated"; "played" -> "DatePlayed"; "plays" -> "PlayCount"
            "year" -> "ProductionYear,SortName"; "random" -> "Random"; "jf.CommunityRating" -> "CommunityRating,SortName"; else -> "SortName"
        }
        val parts = arrayListOf("IncludeItemTypes=MusicAlbum", "SortBy=$by", "SortOrder=" + if (q.desc) "Descending" else "Ascending")
        q.filters["genre"]?.takeIf { it.isNotEmpty() }?.let { vs ->
            if (genreIds.isEmpty()) genres()
            val ids = vs.mapNotNull { genreIds[MusicLibrary.norm(it.label)] }
            if (ids.isEmpty()) return emptyList()
            parts += "GenreIds=" + ids.joinToString("|")
        }
        q.filters["decade"]?.takeIf { it.isNotEmpty() }?.let { vs ->
            parts += "Years=" + vs.flatMap { v -> v.id.toIntOrNull()?.let { d -> (d..d + 9).toList() }.orEmpty() }.joinToString(",")
        }
        if ("favourites" in q.toggles) parts += "Filters=IsFavorite"
        return items(parts.joinToString("&"), size, offset).map(::album)
    }

    override suspend fun scrobble(songId: String, submission: Boolean) {
        if (submission) runCatching { http.post("$u/PlayedItems/${localId(songId)}", null, headers) }
    }
}
