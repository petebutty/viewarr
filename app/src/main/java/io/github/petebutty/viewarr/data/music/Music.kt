package io.github.petebutty.viewarr.data.music

import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.MediaServerService
import io.github.petebutty.viewarr.data.remote.services.PlexService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Music from any of your servers. Each server is a [MusicSource]; [MusicLibrary] joins them into one library, so the
 * Music pages, search and player don't care where a song comes from. Every id carries its source ("nd:…", "plex:…",
 * "jf:…") so the library knows where to ask; songs carry ready-to-play addresses and their artwork.
 */

/** Artwork: a small picture for grids and rows, a large one for album pages and Now Playing. */
data class Art(val small: String, val large: String)

data class Album(
    val id: String, val name: String, val artist: String, val artistId: String?, val year: Int?, val art: Art?, val songCount: Int,
    /** When it was added / last played (ms), for merging lists from several servers. */
    val added: Long? = null, val played: Long? = null, val plays: Int? = null,
    val source: String = "",
)
data class Artist(val id: String, val name: String, val albumCount: Int, val art: Art?, val source: String = "")
data class Song(
    val id: String, val title: String, val artist: String, val album: String, val albumId: String?,
    val track: Int?, val disc: Int?, val durationSec: Int, val art: Art?, val year: Int?,
    /** Where the player fetches it (any login is in the address). */
    val streamUrl: String,
    val source: String = "",
)
data class Playlist(val id: String, val name: String, val songCount: Int, val art: Art?, val durationSec: Int, val source: String = "")

/** Which albums: the lists every server can give. */
enum class AlbumList { Newest, Recent, Frequent, Random, Favourites, AZ }

data class MusicSearchResult(val artists: List<Artist> = emptyList(), val albums: List<Album> = emptyList(), val songs: List<Song> = emptyList())

/** One server's music. */
interface MusicSource {
    /** The id prefix ("nd", "plex-<machine>", "jf", "emby"). */
    val id: String
    /** For the menu: "Navidrome", "Plex · Living room". */
    val name: String
    suspend fun albums(list: AlbumList, size: Int, offset: Int = 0): List<Album>
    suspend fun album(id: String): Pair<Album, List<Song>>?
    suspend fun artists(): List<Artist>
    suspend fun artist(id: String): Pair<Artist, List<Album>>?
    suspend fun playlists(): List<Playlist>
    suspend fun playlist(id: String): Pair<Playlist, List<Song>>?
    /** Genre name → how many albums. */
    suspend fun genres(): List<Pair<String, Int>>
    suspend fun albumsInGenre(genre: String, size: Int, offset: Int = 0): List<Album>
    suspend fun randomSongs(size: Int): List<Song>
    suspend fun search(query: String): MusicSearchResult
    /** [submission] false: now playing; true: listened to (play count, scrobblers). */
    suspend fun scrobble(songId: String, submission: Boolean)
}

/** Ids are "<source>:<server's own id>". */
internal fun localId(id: String) = id.substringAfter(':')
internal fun sourceOf(id: String) = id.substringBefore(':')

/** Every music source you've connected, joined into one library. */
@Singleton
class MusicLibrary @Inject constructor(
    private val store: AccountStore,
    private val http: HttpJson,
    private val plex: PlexService,
    private val mediaServers: MediaServerService,
    private val subsonic: SubsonicClient,
) {
    private var cachedFor: Accounts? = null
    private var cached: List<MusicSource> = emptyList()
    /** A server couldn't be reached when the list was made: look again after this (ms), not just when accounts change. */
    private var retryAt = Long.MAX_VALUE

    /** The sources in use (those not switched off), in order of preference. */
    suspend fun sources(): List<MusicSource> {
        val a = store.current()
        if (!a.musicOn) return emptyList()
        return allSources().filter { it.id !in a.musicOff }
    }

    /** Every connected source with music, switched on or off (for the on/off switches). */
    suspend fun allSources(): List<MusicSource> {
        // The switches themselves don't change which sources exist.
        val a = store.current().copy(musicOff = emptySet())
        if (a == cachedFor && System.currentTimeMillis() < retryAt) return cached
        var unreachable = false
        // A server's music libraries, or null if it has none; a server that doesn't answer is tried again later.
        suspend fun <T> look(f: suspend () -> T): T? = runCatching { kotlinx.coroutines.withTimeout(8_000) { f() } }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException || it is kotlinx.coroutines.TimeoutCancellationException) unreachable = true }.getOrNull()
        val list = buildList {
            a.navidrome?.let { add(NavidromeSource(subsonic, it)) }
            val plexes = (listOfNotNull(a.plex) + a.plexMore.filter { m -> m.machineId != a.plex?.machineId }).filter { it.machineId !in a.plexOff }
            // Each Plex server's music libraries (looked up once; servers without one are left out).
            // One source per music library, so each pages through its own albums.
            val withMusic = coroutineScope {
                plexes.map { p -> async { look { PlexMusicSource.find(http, plex, a.deviceId, p) } } }.awaitAll()
            }
            withMusic.forEach { it?.let(::addAll) }
            a.jellyfin?.let { j -> look { JellyfinMusicSource.find(http, mediaServers, a.deviceId, j, emby = false) } }?.let(::addAll)
            a.emby?.let { e -> look { JellyfinMusicSource.find(http, mediaServers, a.deviceId, e, emby = true) } }?.let(::addAll)
        }
        cachedFor = a
        cached = list
        retryAt = if (unreachable) System.currentTimeMillis() + 60_000 else Long.MAX_VALUE
        return list
    }

    private suspend fun source(id: String) = sources().firstOrNull { it.id == sourceOf(id) }

    /** One list from every source (or just [only]): merged, one copy of each album. */
    suspend fun albums(list: AlbumList, size: Int, offsets: Map<String, Int> = emptyMap(), only: String? = null): Pair<List<Album>, Map<String, Int>> {
        // A source already at its end (-1) isn't asked again.
        val srcs = sources().filter { (only == null || it.id == only) && offsets[it.id] != -1 }
        val per = coroutineScope {
            srcs.map { s -> async { s.id to runCatching { s.albums(list, size, offsets[s.id] ?: 0) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        return pageMerge(per, size, offsets, list)
    }

    /**
     * Pages from several sources as one page in order. A source with more to come only gives what sorts before the
     * point every such source has reached, so the next lot never belongs before what's already shown.
     */
    private fun pageMerge(per: List<Pair<String, List<Album>>>, size: Int, offsets: Map<String, Int>, list: AlbumList): Pair<List<Album>, Map<String, Int>> {
        val order: Comparator<Album>? = when (list) {
            AlbumList.AZ -> compareBy { sortKey(it.name) }
            AlbumList.Newest -> compareByDescending { it.added ?: 0 }
            else -> null
        }
        val full = per.filter { it.second.size >= size }
        val cutoff = if (order != null && per.size > 1 && full.isNotEmpty()) full.map { it.second.last() }.minWithOrNull(order) else null
        // Every source (a short last page too) gives only what comes before the cutoff, as an unbroken run from the
        // top of its page, so the next page starts exactly where this one stopped.
        val kept = per.map { (id, l) -> id to if (cutoff == null) l else l.takeWhile { order!!.compare(it, cutoff) <= 0 } }
        // Where each source got to (for loading more); -1 = no more there.
        val next = per.zip(kept).associate { (orig, k) ->
            orig.first to if (orig.second.size < size && k.second.size == orig.second.size) -1 else (offsets[orig.first] ?: 0) + k.second.size
        }
        return mergeAlbums(kept.map { it.second }, list) to next
    }

    suspend fun albumsInGenre(genre: String, size: Int, offsets: Map<String, Int> = emptyMap(), only: String? = null): Pair<List<Album>, Map<String, Int>> {
        val srcs = sources().filter { (only == null || it.id == only) && offsets[it.id] != -1 }
        val per = coroutineScope { srcs.map { s -> async { s.id to runCatching { s.albumsInGenre(genre, size, offsets[s.id] ?: 0) }.getOrDefault(emptyList()) } }.awaitAll() }
        return pageMerge(per, size, offsets, AlbumList.AZ)
    }

    suspend fun album(id: String) = source(id)?.let { runCatching { it.album(id) }.getOrNull() }
    suspend fun artist(id: String) = source(id)?.let { runCatching { it.artist(id) }.getOrNull() }
    suspend fun playlist(id: String) = source(id)?.let { runCatching { it.playlist(id) }.getOrNull() }

    suspend fun artists(only: String? = null): List<Artist> = merged(only) { it.artists() }
        .distinctBy { norm(it.name) }.sortedBy { sortKey(it.name) }

    suspend fun playlists(only: String? = null): List<Playlist> = merged(only) { it.playlists() }

    suspend fun genres(only: String? = null): List<Pair<String, Int>> = merged(only) { it.genres() }
        .groupBy { norm(it.first) }.map { (_, g) -> g.first().first to g.maxOf { it.second } }.sortedByDescending { it.second }

    suspend fun randomSongs(size: Int): List<Song> {
        val srcs = sources()
        if (srcs.isEmpty()) return emptyList()
        return merged(null) { it.randomSongs(size / srcs.size + 1) }.distinctBy { norm(it.artist) + "|" + norm(it.title) }.shuffled().take(size)
    }

    suspend fun search(query: String): MusicSearchResult {
        val per = coroutineScope { sources().map { s -> async { runCatching { s.search(query) }.getOrDefault(MusicSearchResult()) } }.awaitAll() }
        return MusicSearchResult(
            artists = per.flatMap { it.artists }.distinctBy { norm(it.name) },
            albums = per.flatMap { it.albums }.distinctBy { norm(it.artist) + "|" + norm(it.name) },
            songs = per.flatMap { it.songs }.distinctBy { norm(it.artist) + "|" + norm(it.title) + "|" + norm(it.album) },
        )
    }

    suspend fun scrobble(songId: String, submission: Boolean) { source(songId)?.let { runCatching { it.scrobble(songId, submission) } } }

    /** Your servers with a music library (Plex, Jellyfin, Emby: not Navidrome), for setup's switches: id, name. */
    suspend fun servers(): List<Pair<String, String>> = allSources().filter { it.id != "nd" }.map { it.id to it.name }

    /** Every source for the menu: id, name, on. */
    suspend fun names(): List<Triple<String, String, Boolean>> {
        if (!store.current().musicOn) return emptyList()
        val off = store.current().musicOff
        return allSources().map { Triple(it.id, it.name, it.id !in off) }
    }

    /** Switches a source on or off (at least one stays on). */
    suspend fun setOn(id: String, on: Boolean) {
        val all = allSources().map { it.id }
        store.update { a ->
            val off = if (on) a.musicOff - id else a.musicOff + id
            if (all.all { it in off }) a else a.copy(musicOff = off)
        }
    }

    private suspend fun <T> merged(only: String?, f: suspend (MusicSource) -> List<T>): List<T> = coroutineScope {
        sources().filter { only == null || it.id == only }.map { s -> async { runCatching { f(s) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
    }

    /** Several servers' lists as one: in the list's own order where it can be compared, one copy of each album. */
    private fun mergeAlbums(lists: List<List<Album>>, list: AlbumList): List<Album> {
        if (lists.size <= 1) return lists.firstOrNull().orEmpty()
        val all = when (list) {
            AlbumList.Newest -> lists.flatten().sortedByDescending { it.added ?: 0 }
            AlbumList.Recent -> lists.flatten().sortedByDescending { it.played ?: 0 }
            AlbumList.Frequent -> lists.flatten().sortedByDescending { it.plays ?: 0 }
            AlbumList.AZ -> lists.flatten().sortedBy { sortKey(it.name) }
            // No common order: take turns.
            else -> buildList { val n = lists.maxOf { it.size }; for (i in 0 until n) lists.forEach { l -> l.getOrNull(i)?.let(::add) } }
        }
        return all.distinctBy { norm(it.artist) + "|" + norm(it.name) }
    }

    companion object {
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        fun sortKey(s: String) = s.lowercase().removePrefix("the ").trim()
    }
}
