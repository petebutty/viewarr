package io.github.petebutty.viewarr.data.music

import io.github.petebutty.viewarr.data.accounts.PlexAuth
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.PlexService
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.long
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/**
 * One music library on a Plex server as a [MusicSource]. Each library is its own source, so paging through albums
 * keeps one place per library.
 */
class PlexMusicSource private constructor(
    private val http: HttpJson,
    private val headers: Map<String, String>,
    private val p: PlexAuth,
    /** The music library (section id). */
    section: String,
    libraryName: String?,
    /** Playlists belong to the whole server: only its first music library lists them. */
    private val withPlaylists: Boolean,
) : MusicSource {
    private val sections = listOf(section)
    override val id = "px" + p.machineId.take(10).filter { it.isLetterOrDigit() } + if (withPlaylists) "" else "s$section"
    override val name = "Plex · ${p.serverName}" + (libraryName?.let { " · $it" } ?: "")

    companion object {
        /** The server's music libraries (empty if it has none). Throws if the server can't be reached. */
        suspend fun find(http: HttpJson, plex: PlexService, deviceId: String, p: PlexAuth): List<PlexMusicSource> {
            val h = plex.headers(deviceId, p.token)
            val r = http.get("${p.serverUrl}/library/sections", h)
            if (!r.ok) error("Plex didn't answer")
            val music = r.body.child("MediaContainer").array("Directory").filter { it.str("type") == "artist" }
            return music.mapIndexedNotNull { i, d ->
                val key = d.str("key") ?: return@mapIndexedNotNull null
                PlexMusicSource(http, h, p, key, d.str("title").takeIf { music.size > 1 }, withPlaylists = i == 0)
            }
        }
    }

    private suspend fun get(path: String): JsonElement? =
        runCatching { http.get(p.serverUrl + path, headers).takeIf { it.ok }?.body.child("MediaContainer") }.getOrNull()

    private fun art(thumb: String?) = thumb?.let {
        fun at(n: Int) = "${p.serverUrl}/photo/:/transcode?width=$n&height=$n&minSize=1&upscale=1&url=${enc(it)}&X-Plex-Token=${enc(p.token)}"
        Art(at(300), at(800))
    }

    private fun album(j: JsonElement) = Album(
        id = "$id:" + j.str("ratingKey").orEmpty(), name = j.str("title") ?: "Unknown album",
        artist = j.str("parentTitle") ?: "", artistId = j.str("parentRatingKey")?.let { "$id:$it" }, year = j.int("year"),
        art = art(j.str("thumb")), songCount = j.int("leafCount") ?: 0,
        added = j.long("addedAt")?.times(1000), played = j.long("lastViewedAt")?.times(1000), plays = j.int("viewCount"), source = id,
    )
    private fun artist(j: JsonElement) = Artist("$id:" + j.str("ratingKey").orEmpty(), j.str("title") ?: "Unknown artist", j.int("childCount") ?: 0, art(j.str("thumb")), id)
    private fun song(j: JsonElement): Song? {
        val part = j.array("Media").firstOrNull().array("Part").firstOrNull().str("key") ?: return null
        return Song(
            id = "$id:" + j.str("ratingKey").orEmpty(), title = j.str("title") ?: "Unknown",
            artist = j.str("originalTitle") ?: j.str("grandparentTitle") ?: "", album = j.str("parentTitle") ?: "",
            albumId = j.str("parentRatingKey")?.let { "$id:$it" }, track = j.int("index"), disc = j.int("parentIndex"),
            durationSec = ((j.long("duration") ?: 0L) / 1000).toInt(), art = art(j.str("parentThumb") ?: j.str("thumb")), year = j.int("parentYear") ?: j.int("year"),
            streamUrl = "${p.serverUrl}$part?X-Plex-Token=${enc(p.token)}", source = id,
        )
    }

    /** Each music library, joined. */
    private suspend fun all(type: Int, query: String, size: Int, offset: Int): List<JsonElement> = sections.flatMap { s ->
        get("/library/sections/$s/all?type=$type&$query&X-Plex-Container-Start=$offset&X-Plex-Container-Size=$size").array("Metadata")
    }

    override suspend fun albums(list: AlbumList, size: Int, offset: Int): List<Album> {
        val (sort, keep) = when (list) {
            AlbumList.Newest -> "addedAt:desc" to { _: Album -> true }
            AlbumList.Recent -> "lastViewedAt:desc" to { a: Album -> (a.played ?: 0) > 0 }
            AlbumList.Frequent -> "viewCount:desc" to { a: Album -> (a.plays ?: 0) > 0 }
            AlbumList.Random -> "random" to { _: Album -> true }
            AlbumList.Favourites -> "userRating:desc" to { _: Album -> true }
            AlbumList.AZ -> "titleSort" to { _: Album -> true }
        }
        val extra = if (list == AlbumList.Favourites) "&userRating>>=1" else ""
        return all(9, "sort=$sort$extra", size, offset).map(::album).filter(keep)
    }

    override suspend fun album(id: String): Pair<Album, List<Song>>? {
        val rk = localId(id)
        val meta = get("/library/metadata/$rk").array("Metadata").firstOrNull() ?: return null
        return album(meta) to get("/library/metadata/$rk/children").array("Metadata").mapNotNull(::song)
    }
    override suspend fun artists() = all(8, "sort=titleSort", 5000, 0).map(::artist)
    override suspend fun artist(id: String): Pair<Artist, List<Album>>? {
        val rk = localId(id)
        val meta = get("/library/metadata/$rk").array("Metadata").firstOrNull() ?: return null
        return artist(meta) to get("/library/metadata/$rk/children").array("Metadata").map(::album)
    }
    override suspend fun playlists() = if (!withPlaylists) emptyList() else get("/playlists?playlistType=audio").array("Metadata").map { j ->
        Playlist("$id:" + j.str("ratingKey").orEmpty(), j.str("title") ?: "Playlist", j.int("leafCount") ?: 0, art(j.str("composite") ?: j.str("thumb")), ((j.long("duration") ?: 0L) / 1000).toInt(), id)
    }
    override suspend fun playlist(id: String): Pair<Playlist, List<Song>>? {
        val rk = localId(id)
        val meta = get("/playlists/$rk").array("Metadata").firstOrNull() ?: return null
        val pl = Playlist(id, meta.str("title") ?: "Playlist", meta.int("leafCount") ?: 0, art(meta.str("composite")), ((meta.long("duration") ?: 0L) / 1000).toInt(), this.id)
        return pl to get("/playlists/$rk/items").array("Metadata").mapNotNull(::song)
    }

    // Genre name → Plex's id for it (Plex filters by id).
    private val genreIds = HashMap<String, String>()
    override suspend fun genres(): List<Pair<String, Int>> = sections.flatMap { s ->
        get("/library/sections/$s/genre?type=9").array("Directory").mapNotNull { g ->
            val name = g.str("title") ?: return@mapNotNull null
            g.str("key")?.let { genreIds[MusicLibrary.norm(name)] = it }
            name to 0
        }
    }
    override suspend fun albumsInGenre(genre: String, size: Int, offset: Int): List<Album> {
        if (genreIds.isEmpty()) genres()
        val g = genreIds[MusicLibrary.norm(genre)] ?: return emptyList()
        return all(9, "genre=${enc(g)}&sort=titleSort", size, offset).map(::album)
    }
    override suspend fun randomSongs(size: Int) = all(10, "sort=random", size, 0).mapNotNull(::song)

    override suspend fun search(query: String): MusicSearchResult {
        val hubs = sections.flatMap { s -> get("/hubs/search?query=${enc(query)}&sectionId=$s&limit=20").array("Hub") }
        fun of(type: String) = hubs.filter { it.str("type") == type }.flatMap { it.array("Metadata") }
        return MusicSearchResult(of("artist").map(::artist), of("album").map(::album), of("track").mapNotNull(::song))
    }

    // ---- The Albums grid: Plex's own sorts and filters for its music library

    /** Plex sort keys for the shared ids (the rest of Plex's are offered as its own, when it's the only source). */
    private val sharedSorts = mapOf(
        "titleSort" to "title", "addedAt" to "added", "lastViewedAt" to "played", "viewCount" to "plays",
        "originallyAvailableAt" to "year", "random" to "random",
    )
    private val sharedFilters = mapOf("genre" to "genre", "decade" to "decade")
    private val keepFilters = setOf("mood", "style", "year", "studio", "format", "subformat", "collection", "label")
    /** Plex's own sort key for each id we offer. */
    private val sortKeys = HashMap<String, String>()

    override suspend fun albumOptions(): Pair<List<io.github.petebutty.viewarr.data.media.SortOption>, List<io.github.petebutty.viewarr.data.media.FilterGroup>> {
        val s0 = sections.first()
        val sorts = get("/library/sections/$s0/sorts?type=9").array("Directory").mapNotNull { d ->
            val k = d.str("key") ?: return@mapNotNull null
            val id = when {
                k in sharedSorts -> sharedSorts.getValue(k)
                k.startsWith("artist.titleSort") -> "artist"
                else -> "px.$k"
            }
            sortKeys[id] = k
            val label = when (id) {
                "title" -> "Title"; "artist" -> "Artist"; "added" -> "Date added"; "played" -> "Recently played"; "plays" -> "Most played"
                "year" -> "Year"; "random" -> "Random"; else -> if (k == "userRating") "Your rating" else d.str("title") ?: k
            }
            io.github.petebutty.viewarr.data.media.SortOption(id, label, descFirst = id !in setOf("title", "artist"))
        }
        val filters = get("/library/sections/$s0/filters?type=9").array("Directory").mapNotNull { d ->
            val k = d.str("filter") ?: return@mapNotNull null
            when {
                k in sharedFilters -> io.github.petebutty.viewarr.data.media.FilterGroup(sharedFilters.getValue(k), d.str("title") ?: k)
                k in keepFilters -> io.github.petebutty.viewarr.data.media.FilterGroup("px.$k", d.str("title") ?: k)
                else -> null
            }
        }
        return sorts to filters
    }

    // Each filter's values as Plex knows them: shown name → Plex's id (genres are matched across sources by name).
    private val valueIds = HashMap<String, HashMap<String, String>>()

    override suspend fun albumFilterValues(group: String): List<io.github.petebutty.viewarr.data.media.FilterValue> {
        val key = group.removePrefix("px.")
        val ids = valueIds.getOrPut(group) { HashMap() }
        return sections.flatMap { s -> get("/library/sections/$s/$key?type=9").array("Directory") }.mapNotNull { d ->
            val id = d.str("key") ?: return@mapNotNull null
            val label = d.str("title") ?: id
            ids[MusicLibrary.norm(label)] = id
            // Genres: by name, so the same genre on Navidrome / Jellyfin matches. Decades: "1990" everywhere.
            io.github.petebutty.viewarr.data.media.FilterValue(if (group == "genre") label else id, label)
        }
    }

    override suspend fun browseAlbums(q: io.github.petebutty.viewarr.data.media.LibraryQuery, size: Int, offset: Int): List<Album> {
        if (sortKeys.isEmpty()) albumOptions()
        val parts = ArrayList<String>()
        val key = sortKeys[q.sort] ?: "titleSort"
        // Plex's artist sort is a list of keys: only the first is turned round.
        parts += "sort=" + enc(if (q.desc) key.substringBefore(',') + ":desc" + key.substringAfter(',', "").let { if (it.isEmpty()) "" else ",$it" } else key)
        for ((g, vs) in q.filters) {
            if (vs.isEmpty()) continue
            val pk = g.removePrefix("px.")
            val ids = if (g == "genre") {
                if (valueIds["genre"].isNullOrEmpty()) albumFilterValues("genre")
                vs.mapNotNull { valueIds["genre"]?.get(MusicLibrary.norm(it.label)) }
            } else vs.map { it.id }
            // A genre this server doesn't have: nothing here matches.
            if (ids.isEmpty()) return emptyList()
            parts += "$pk=" + ids.joinToString(",") { enc(it) }
        }
        if ("favourites" in q.toggles) parts += "userRating>>=1"
        return all(9, parts.joinToString("&"), size, offset).map(::album)
    }

    override suspend fun scrobble(songId: String, submission: Boolean) {
        if (submission) get("/:/scrobble?identifier=com.plexapp.plugins.library&key=${enc(localId(songId))}")
    }
}
