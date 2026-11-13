package io.github.petebutty.viewarr.data.music

import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Navidrome (or any Subsonic server). The password itself is never stored: only a salt and the salted hash Subsonic
 * accepts in its place (token = md5(password + salt)).
 */
@Serializable
data class NavidromeAuth(val url: String, val user: String, val salt: String, val token: String, val serverName: String = "Navidrome")

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Signing in to a Subsonic server, and its API calls. */
@Singleton
class SubsonicClient @Inject constructor(val http: HttpJson) {

    /** Checks the address and login; returns what to keep (no password). */
    /**
     * A new Navidrome user (not an admin), made with an admin's login through Navidrome's own API. The admin's
     * password is used for this alone and not kept.
     */
    suspend fun createUser(server: String, adminUser: String, adminPassword: String, userName: String, password: String): Result<Unit> = runCatching {
        val base = server.trimEnd('/')
        val login = http.post("$base/auth/login", kotlinx.serialization.json.buildJsonObject {
            put("username", kotlinx.serialization.json.JsonPrimitive(adminUser)); put("password", kotlinx.serialization.json.JsonPrimitive(adminPassword))
        })
        check(login.ok) { "Navidrome didn't accept your password." }
        check((login.body.child("isAdmin") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") { "Your Navidrome user isn't an admin, so it can't make users." }
        val token = login.body.str("token") ?: error("Navidrome didn't sign you in.")
        val made = http.post("$base/api/user", kotlinx.serialization.json.buildJsonObject {
            put("userName", kotlinx.serialization.json.JsonPrimitive(userName)); put("name", kotlinx.serialization.json.JsonPrimitive(userName))
            put("password", kotlinx.serialization.json.JsonPrimitive(password)); put("isAdmin", kotlinx.serialization.json.JsonPrimitive(false))
        }, mapOf("x-nd-authorization" to "Bearer $token"))
        check(made.ok) { if (made.code == 400 || made.code == 409) "Navidrome already has a user called $userName: use Their existing login instead." else "Navidrome wouldn't make the user (error ${made.code})." }
    }

    suspend fun connect(address: String, user: String, password: String): Result<NavidromeAuth> {
        val a = address.trim().trimEnd('/')
        if (a.isEmpty()) return Result.failure(Exception("Enter your Navidrome address."))
        if (user.isBlank() || password.isEmpty()) return Result.failure(Exception("Enter your username and password."))
        val withScheme = if ("://" in a) a else "http://$a"
        // As typed, then on Navidrome's own port if none was given.
        val hostPart = withScheme.substringAfter("://").substringBefore('/')
        val candidates = buildList {
            add(withScheme)
            if (':' !in hostPart && "://" !in a) add(withScheme.replaceFirst(hostPart, "$hostPart:4533"))
        }
        val salt = (1..12).map { "0123456789abcdef".random() }.joinToString("")
        val token = md5(password + salt)
        var lastError = "Couldn't reach Navidrome at $a."
        for (url in candidates) {
            val auth = NavidromeAuth(url, user.trim(), salt, token)
            val r = runCatching { http.get(api(auth, "ping")) }.getOrNull() ?: continue
            val resp = r.body.child("subsonic-response")
            if (resp == null) { lastError = "That address answered, but it isn't Navidrome."; continue }
            if (resp.str("status") == "ok") {
                val name = resp.str("type")?.replaceFirstChar { it.uppercase() } ?: "Navidrome"
                return Result.success(auth.copy(serverName = name))
            }
            lastError = when (resp.child("error").int("code")) {
                40 -> "Navidrome didn't accept that username or password."
                41 -> "This server doesn't allow token sign-in."
                else -> resp.child("error").str("message") ?: "Navidrome refused the sign-in."
            }
            if (resp.child("error").int("code") == 40) break
        }
        return Result.failure(Exception(lastError))
    }

    /** Navidrome / Subsonic servers on the home network (Navidrome's port, 4533). */
    suspend fun discover(): List<String> = io.github.petebutty.viewarr.data.remote.services.lanScan(4533) { url ->
        val r = kotlinx.coroutines.withTimeoutOrNull(3_000) { runCatching { http.get("$url/rest/ping.view?f=json") }.getOrNull() }
        // Without a login it answers with an error, but a Subsonic one.
        url.takeIf { r?.body.child("subsonic-response") != null }
    }

    private fun params(a: NavidromeAuth) = "u=${enc(a.user)}&t=${a.token}&s=${a.salt}&v=1.16.1&c=Viewarr&f=json"
    fun api(a: NavidromeAuth, method: String, extra: String = "") = "${a.url}/rest/$method.view?${params(a)}$extra"

    suspend fun call(a: NavidromeAuth, method: String, extra: String = ""): JsonElement? {
        val r = runCatching { http.get(api(a, method, extra)) }.getOrNull() ?: return null
        return r.body.child("subsonic-response").takeIf { it.str("status") == "ok" }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Navidrome as a [MusicSource]. */
class NavidromeSource(private val c: SubsonicClient, private val a: NavidromeAuth) : MusicSource {
    override val id = "nd"
    override val name = a.serverName

    private fun art(cover: String?) = cover?.let { Art(c.api(a, "getCoverArt", "&id=${enc(it)}&size=300"), c.api(a, "getCoverArt", "&id=${enc(it)}&size=800")) }
    private fun time(s: String?) = s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun album(j: JsonElement) = Album(
        id = "nd:" + j.str("id").orEmpty(), name = j.str("name") ?: j.str("title") ?: "Unknown album",
        artist = j.str("artist") ?: "", artistId = j.str("artistId")?.let { "nd:$it" }, year = j.int("year"),
        art = art(j.str("coverArt")), songCount = j.int("songCount") ?: 0,
        added = time(j.str("created")), played = time(j.str("played")), plays = j.int("playCount"), source = id,
    )
    private fun artist(j: JsonElement) = Artist("nd:" + j.str("id").orEmpty(), j.str("name") ?: "Unknown artist", j.int("albumCount") ?: 0, art(j.str("coverArt")), id)
    private fun song(j: JsonElement) = Song(
        id = "nd:" + j.str("id").orEmpty(), title = j.str("title") ?: "Unknown", artist = j.str("artist") ?: "", album = j.str("album") ?: "",
        albumId = j.str("albumId")?.let { "nd:$it" }, track = j.int("track"), disc = j.int("discNumber"), durationSec = j.int("duration") ?: 0,
        art = art(j.str("coverArt")), year = j.int("year"), streamUrl = c.api(a, "stream", "&id=${enc(j.str("id").orEmpty())}"), source = id,
    )
    private fun playlist(j: JsonElement) = Playlist("nd:" + j.str("id").orEmpty(), j.str("name") ?: "Playlist", j.int("songCount") ?: 0, art(j.str("coverArt")), j.int("duration") ?: 0, id)

    override suspend fun albums(list: AlbumList, size: Int, offset: Int): List<Album> {
        val type = when (list) {
            AlbumList.Newest -> "newest"; AlbumList.Recent -> "recent"; AlbumList.Frequent -> "frequent"
            AlbumList.Random -> "random"; AlbumList.Favourites -> "starred"; AlbumList.AZ -> "alphabeticalByName"
        }
        return c.call(a, "getAlbumList2", "&type=$type&size=$size&offset=$offset").child("albumList2").array("album").map(::album)
    }
    override suspend fun album(id: String) = c.call(a, "getAlbum", "&id=${enc(localId(id))}").child("album")?.let { album(it) to it.array("song").map(::song) }
    override suspend fun artists() = c.call(a, "getArtists").child("artists").array("index").flatMap { it.array("artist") }.map(::artist)
    override suspend fun artist(id: String) = c.call(a, "getArtist", "&id=${enc(localId(id))}").child("artist")?.let { artist(it) to it.array("album").map(::album) }
    override suspend fun playlists() = c.call(a, "getPlaylists").child("playlists").array("playlist").map(::playlist)
    override suspend fun playlist(id: String) = c.call(a, "getPlaylist", "&id=${enc(localId(id))}").child("playlist")?.let { playlist(it) to it.array("entry").map(::song) }
    override suspend fun genres() = c.call(a, "getGenres").child("genres").array("genre")
        .mapNotNull { g -> g.str("value")?.takeIf { it.isNotBlank() }?.let { it to (g.int("albumCount") ?: 0) } }.filter { it.second > 0 }
    override suspend fun albumsInGenre(genre: String, size: Int, offset: Int) =
        c.call(a, "getAlbumList2", "&type=byGenre&genre=${enc(genre)}&size=$size&offset=$offset").child("albumList2").array("album").map(::album)
    override suspend fun randomSongs(size: Int) = c.call(a, "getRandomSongs", "&size=$size").child("randomSongs").array("song").map(::song)
    override suspend fun search(query: String): MusicSearchResult {
        val r = c.call(a, "search3", "&query=${enc(query)}&artistCount=12&albumCount=20&songCount=25").child("searchResult3")
        return MusicSearchResult(r.array("artist").map(::artist), r.array("album").map(::album), r.array("song").map(::song))
    }
    override suspend fun scrobble(songId: String, submission: Boolean) { c.call(a, "scrobble", "&id=${enc(localId(songId))}&submission=$submission") }

    // Lyrics: OpenSubsonic's timed lyrics where the server has them, else the plain getLyrics.
    override suspend fun lyrics(song: Song): Lyrics? {
        val structured = c.call(a, "getLyricsBySongId", "&id=${enc(localId(song.id))}").child("lyricsList").array("structuredLyrics")
        val pick = structured.firstOrNull { it.str("synced") == "true" } ?: structured.firstOrNull()
        if (pick != null) {
            val synced = pick.str("synced") == "true"
            val lines = pick.array("line").map { l -> LyricLine(if (synced) l.str("start")?.toLongOrNull() else null, l.str("value").orEmpty()) }
            if (lines.isNotEmpty()) return Lyrics(lines, synced, name)
        }
        val plain = c.call(a, "getLyrics", "&artist=${enc(song.artist)}&title=${enc(song.title)}").child("lyrics").str("value")
        return plain?.takeIf { it.isNotBlank() }?.let { parseLrc(it) }?.copy(from = name)
    }
    override suspend fun similarSongs(song: Song, size: Int) =
        c.call(a, "getSimilarSongs", "&id=${enc(localId(song.id))}&count=$size").child("similarSongs").array("song").map(::song)
    override suspend fun artistInfo(artist: Artist): ArtistInfo? {
        val info = c.call(a, "getArtistInfo2", "&id=${enc(localId(artist.id))}&count=12").child("artistInfo2")
        val bio = info.str("biography")?.replace(Regex("<[^>]+>"), "")?.replace(Regex("\\s*Read more on Last\\.fm\\.?\\s*$"), "")?.trim()?.takeIf { it.isNotBlank() }
        val similar = info.array("similarArtist").filter { it.str("id") != null }.map(::artist)
        val top = c.call(a, "getTopSongs", "&artist=${enc(artist.name)}&count=10").child("topSongs").array("song").map(::song)
        return ArtistInfo(bio, similar, top)
    }
    override suspend fun setStarred(id: String, kind: String, on: Boolean): Boolean {
        val param = when (kind) { "album" -> "albumId"; "artist" -> "artistId"; else -> "id" }
        return c.call(a, if (on) "star" else "unstar", "&$param=${enc(localId(id))}") != null
    }
    override suspend fun starredIds(): Set<String> {
        val r = c.call(a, "getStarred2").child("starred2")
        return (r.array("song") + r.array("album") + r.array("artist")).mapNotNull { it.str("id")?.let { i -> "nd:$i" } }.toSet()
    }

    // Navidrome's album lists: one sort or one filter at a time (favourites, else genre, else decade).
    override suspend fun albumOptions() = listOf(
        io.github.petebutty.viewarr.data.media.SortOption("title", "Title", false), io.github.petebutty.viewarr.data.media.SortOption("artist", "Artist", false),
        io.github.petebutty.viewarr.data.media.SortOption("added", "Date added"), io.github.petebutty.viewarr.data.media.SortOption("played", "Recently played"),
        io.github.petebutty.viewarr.data.media.SortOption("plays", "Most played"), io.github.petebutty.viewarr.data.media.SortOption("year", "Year"),
        io.github.petebutty.viewarr.data.media.SortOption("random", "Random"),
    ) to listOf(
        io.github.petebutty.viewarr.data.media.FilterGroup("favourites", "Favourites", toggle = true),
        io.github.petebutty.viewarr.data.media.FilterGroup("genre", "Genre", multi = false),
        io.github.petebutty.viewarr.data.media.FilterGroup("decade", "Decade", multi = false),
    )

    override suspend fun albumFilterValues(group: String) = when (group) {
        "genre" -> genres().map { (g, _) -> io.github.petebutty.viewarr.data.media.FilterValue(g, g) }
        "decade" -> decades()
        else -> emptyList()
    }

    override suspend fun browseAlbums(q: io.github.petebutty.viewarr.data.media.LibraryQuery, size: Int, offset: Int): List<Album> {
        val genre = q.filters["genre"]?.firstOrNull()?.id
        val decade = q.filters["decade"]?.firstOrNull()?.id?.toIntOrNull()
        val extra = when {
            "favourites" in q.toggles -> "&type=starred"
            genre != null -> "&type=byGenre&genre=${enc(genre)}"
            decade != null -> if (q.desc) "&type=byYear&fromYear=${decade + 9}&toYear=$decade" else "&type=byYear&fromYear=$decade&toYear=${decade + 9}"
            else -> when (q.sort) {
                "artist" -> "&type=alphabeticalByArtist"; "added" -> "&type=newest"; "played" -> "&type=recent"; "plays" -> "&type=frequent"
                "random" -> "&type=random"
                "year" -> if (q.desc) "&type=byYear&fromYear=9999&toYear=0" else "&type=byYear&fromYear=0&toYear=9999"
                else -> "&type=alphabeticalByName"
            }
        }
        return c.call(a, "getAlbumList2", "$extra&size=$size&offset=$offset").child("albumList2").array("album").map(::album)
    }
}

/** The decades a music library can be filtered by ("2020" → "2020s"), newest first. */
internal fun decades(): List<io.github.petebutty.viewarr.data.media.FilterValue> {
    val now = java.time.Year.now().value / 10 * 10
    return (now downTo 1950 step 10).map { io.github.petebutty.viewarr.data.media.FilterValue(it.toString(), "${it}s") }
}
