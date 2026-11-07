package io.github.petebutty.viewarr.data.media

import android.util.Log
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.accounts.AddonInfo
import io.github.petebutty.viewarr.data.accounts.MediaServerAuth
import io.github.petebutty.viewarr.data.accounts.PlexAuth
import io.github.petebutty.viewarr.data.accounts.TraktAuth
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.MediaServerService
import io.github.petebutty.viewarr.data.remote.services.PlexService
import io.github.petebutty.viewarr.data.remote.services.TmdbService
import io.github.petebutty.viewarr.data.remote.services.TraktService
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.asArray
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.long
import io.github.petebutty.viewarr.data.remote.services.obj
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Catalog"
private const val TMDB_IMG = "https://image.tmdb.org/t/p/"
/** Library copies from the IPTV provider (Xtream Codes) are labelled with this source. */
const val PROVIDER = "IPTV"

/**
 * A home-network address: 192.168.x / 10.x / 172.16–31.x, a .local or single-word name, or Plex's
 * "192-168-1-5.<id>.plex.direct" form of those. Anything else (a domain, Tailscale) counts as away.
 */
fun isHomeAddress(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
    val ip = if (host.endsWith(".plex.direct")) host.substringBefore('.').replace('-', '.') else host
    if (ip.endsWith(".local") || !ip.contains('.')) return true
    val p = ip.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || p[0] == 127
}

/** "movies" → "Movies": library names as the servers give them, tidied for display. */
fun tidyLibraryName(name: String): String = name.trim().replaceFirstChar { it.uppercase() }

/**
 * Rows that repeat across servers become one: continue watching from every server, and the same library's
 * recently added on two Plex servers (e.g. "Movies" and "movies"). Titles are interleaved, each shown once; the
 * merged row keeps the first one's id, so Settings → Home page & menu still knows it.
 */
fun mergeRows(rows: List<MediaRow>): List<MediaRow> {
    val groups = rows.filter { it.group != null }.groupBy { it.group }
    val recentGroups = groups.keys.count { it?.startsWith("recent:") == true }
    val done = HashSet<String>()
    return rows.mapNotNull { r ->
        val g = r.group ?: return@mapNotNull r
        if (!done.add(g)) return@mapNotNull null
        val members = groups.getValue(g)
        val title = when {
            g == "deck" -> "Continue watching"
            recentGroups == 1 -> "Recently added"
            else -> "Recently added in ${members.first().groupName ?: ""}".trim()
        }
        val seen = HashSet<String>()
        val merged = ArrayList<MediaItem>()
        val longest = members.maxOf { it.items.size }
        for (i in 0 until longest) for (m in members) {
            val item = m.items.getOrNull(i) ?: continue
            val id = item.tmdbId?.let { "${item.type}:$it" } ?: item.key
            if (seen.add(id)) merged += item
        }
        r.copy(title = title, items = merged)
    }
}
/** The provider's films / series as a library in the menu (IPTV provider, or Dispatcharr's provider VOD). */
const val PROVIDER_LIBRARY = "IPTV"

/** "|EN| ACTION/THRILLER" → "Action/Thriller": provider category names, tidied for the genre picker. */
fun tidyCategory(name: String): String {
    val t = name.trim().replace(Regex("^\\|[A-Z]{2,5}\\|\\s*"), "").replace(Regex("^[A-Z]{2,3}\\s*[|:-]\\s*"), "").trim()
    if (t.any { it.isLowerCase() }) return t
    val keep = setOf("UK", "US", "TV", "HBO", "BBC", "NBC", "CBS", "ABC", "AMC", "BET", "FX", "IMDB", "4K", "HD", "UFC", "WWE", "MGM", "ALLBLK", "SD")
    return t.split(' ').joinToString(" ") { w ->
        w.split('/').joinToString("/") { p -> if (p.uppercase() in keep || p.any { it.isDigit() }) p else p.lowercase().replaceFirstChar { it.uppercase() } }
    }
}
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/**
 * Builds the Movies / Shows rows from whatever sources are connected. Each row loads independently and
 * the flow emits the rows found so far, in a fixed order, so the screen fills in as sources answer.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val http: HttpJson,
    private val store: AccountStore,
    private val tmdb: TmdbService,
    private val trakt: TraktService,
    private val playbackStore: io.github.petebutty.viewarr.data.settings.PlaybackStore,
    private val plex: PlexService,
    private val mediaServers: MediaServerService,
    private val sessions: io.github.petebutty.viewarr.data.session.SessionStore,
    private val xtream: io.github.petebutty.viewarr.data.xtream.XtreamClient,
) {
    private val tmdbCache = ConcurrentHashMap<String, MediaItem>()
    private val tmdbLimit = Semaphore(6)
    @Volatile private var traktWatchlist: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun rows(type: MediaType): Flow<RowsState> = channelFlow {
        val a = store.current()
        val traktAuth = a.trakt?.let { freshTrakt(it) }
        val slots: List<suspend () -> List<MediaRow>> = buildList {
            plexServers(a).forEach { p -> add { listOfNotNull(plexOnDeck(a, p, type)) } }
            a.jellyfin?.let { j -> add { listOfNotNull(serverResume(a, j, emby = false, type)) } }
            a.emby?.let { e -> add { listOfNotNull(serverResume(a, e, emby = true, type)) } }
            if (type == MediaType.Show) add { listOfNotNull(upcomingRow(a, traktAuth)) }
            traktAuth?.let { t -> add { listOfNotNull(traktWatchlistRow(a, t, type)) } }
            plexServers(a).forEach { p -> add { plexRecent(a, p, type) } }
            a.jellyfin?.let { j -> add { listOfNotNull(serverLatest(a, j, emby = false, type)) } }
            a.emby?.let { e -> add { listOfNotNull(serverLatest(a, e, emby = true, type)) } }
            traktAuth?.let { t -> add { listOfNotNull(traktRecommended(a, t, type)) } }
            a.tmdbKey?.let { k ->
                val t = if (type == MediaType.Movie) "movie" else "tv"
                add { listOfNotNull(tmdbList(k, type, "/trending/$t/week", "trending", "Trending this week")) }
                add { listOfNotNull(tmdbList(k, type, "/$t/popular", "popular", "Popular")) }
                add { listOfNotNull(tmdbList(k, type, "/$t/top_rated", "top", "Top rated")) }
                if (type == MediaType.Movie) add { listOfNotNull(tmdbList(k, type, "/movie/now_playing", "cinema", "In cinemas", mapOf("region" to "GB"))) }
                else add { listOfNotNull(tmdbList(k, type, "/tv/on_the_air", "airing", "On TV this week")) }
            }
            a.activeAddons.forEach { addon -> add { stremioRows(addon, type) } }
            // IPTV provider films / series, when live TV is signed in to a provider (Xtream Codes).
            add { providerRows(a, type) }
        }
        val found = arrayOfNulls<List<MediaRow>>(slots.size)
        val lock = Mutex()
        var finished = 0
        slots.forEachIndexed { i, slot ->
            launch {
                val result = runCatching { withTimeoutOrNull(25_000) { slot() } }
                    .onFailure { Log.w(TAG, "row ${type.name}#$i failed: ${it.javaClass.simpleName} ${it.message}") }
                    .getOrNull().orEmpty().map { r -> r.copy(items = r.items.distinctBy { it.key }) }.filter { it.items.isNotEmpty() }
                lock.withLock {
                    found[i] = result
                    finished++
                    send(RowsState(found.filterNotNull().flatten(), done = finished == slots.size))
                }
            }
        }
        if (slots.isEmpty()) send(RowsState(emptyList(), done = true))
        awaitClose()
    }

    // ------------------------------------------------------------------ search

    /** Search everything connected: TMDB films & shows, your Plex library, and addon catalogues that support search. */
    fun search(query: String): Flow<RowsState> = channelFlow {
        val q = query.trim()
        val a = store.current()
        // Rows in this order, each shown as soon as it arrives: your own copies first, then discovery, then addons.
        val slots: List<suspend () -> List<MediaRow>> = buildList {
            add { listOfNotNull(librarySearch(a, q)) }
            a.tmdbKey?.let { k ->
                add { listOfNotNull(tmdbSearch(k, MediaType.Movie, q)) }
                add { listOfNotNull(tmdbSearch(k, MediaType.Show, q)) }
                add { listOfNotNull(tmdbPeople(k, q)) }
            }
            a.activeAddons.forEach { addon -> add { stremioSearch(addon, q) } }
        }
        val found = arrayOfNulls<List<MediaRow>>(slots.size)
        val lock = Mutex()
        var finished = 0
        // Titles found in your libraries: TMDB results with the same id get a tick.
        val owned = HashSet<String>()
        fun withTicks(rows: List<MediaRow>) = rows.map { r ->
            if (!r.id.startsWith("search-tmdb")) r else r.copy(items = r.items.map { it.copy(owned = it.key in owned) })
        }
        slots.forEachIndexed { i, slot ->
            launch {
                val result = runCatching { withTimeoutOrNull(20_000) { slot() } }
                    .onFailure { Log.w(TAG, "search #$i failed: ${it.javaClass.simpleName}") }
                    .getOrNull().orEmpty().map { r -> r.copy(items = r.items.distinctBy { it.key }) }.filter { it.items.isNotEmpty() }
                lock.withLock {
                    found[i] = result
                    if (i == 0) result.firstOrNull()?.items?.forEach { owned.add(it.key) }
                    finished++
                    send(RowsState(withTicks(found.filterNotNull().flatten()), done = finished == slots.size))
                }
            }
        }
        if (slots.isEmpty()) send(RowsState(emptyList(), done = true))
        awaitClose()
    }

    /**
     * "In your library": the search words asked of Plex, Jellyfin, Emby and the IPTV provider's films and series,
     * merged into one row. A title on several servers is one poster that says where it is ("Plex · Jellyfin").
     * Closest names first.
     */
    private suspend fun librarySearch(a: Accounts, q: String): MediaRow? = coroutineScope {
        val parts = buildList {
            plexServers(a).forEach { p -> add(async { runCatching { withTimeoutOrNull(12_000) { plexSearch(a, p, q)?.items } }.getOrNull().orEmpty() }) }
            listOfNotNull(a.jellyfin?.let { it to false }, a.emby?.let { it to true }).forEach { (srv, emby) ->
                add(async { runCatching { withTimeoutOrNull(12_000) { serverSearch(a, srv, emby, q) } }.getOrNull().orEmpty() })
            }
            add(async { runCatching { withTimeoutOrNull(12_000) { providerSearch(q) } }.getOrNull().orEmpty() })
        }
        val all = parts.awaitAll().flatten()
        if (all.isEmpty()) return@coroutineScope null
        val merged = all.groupBy { it.key }.map { (_, copies) ->
            copies.first().copy(origin = copies.map { it.origin }.distinct().joinToString(" · "))
        }
        val needle = q.lowercase()
        fun rank(m: MediaItem): Int {
            val t = m.title.lowercase()
            return when { t == needle -> 0; t.startsWith(needle) -> 1; t.contains(needle) -> 2; else -> 3 }
        }
        val sorted = merged.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
        // Copies with no poster of their own (a bare library item) borrow TMDB's, rather than a blank card.
        val filled = enrich(a, sorted.filter { it.poster == null && it.tmdbId != null }).associateBy { it.key }
        MediaRow("search-library", "In your library", sorted.map { filled[it.key] ?: it })
    }

    private suspend fun serverSearch(a: Accounts, srv: MediaServerAuth, emby: Boolean, q: String): List<MediaItem> {
        val name = if (emby) "Emby" else "Jellyfin"
        val r = http.get(
            "${srv.serverUrl}/Users/${srv.userId}/Items?Recursive=true&IncludeItemTypes=Movie,Series&SearchTerm=${enc(q)}&Limit=30&Fields=ProviderIds,Overview,ProductionYear",
            mediaServers.headers(emby, a.deviceId, srv.token),
        )
        if (!r.ok) return emptyList()
        return r.body.array("Items").mapNotNull { j ->
            serverItem(srv, j, if (j.str("Type") == "Series") MediaType.Show else MediaType.Movie, name)
        }
    }

    private suspend fun providerSearch(q: String): List<MediaItem> {
        if (dispatcharrVod() != null) return coroutineScope {
            listOf(MediaType.Movie, MediaType.Show).map { t -> async { dvodPage(t, mapOf("name" to q, "page_size" to "20"))?.first.orEmpty() } }.awaitAll().flatten()
        }
        val s = xcSession() ?: return emptyList()
        val (films, series) = xcCatalog(s)
        val needle = q.lowercase()
        val f = films.asSequence().filter { it.name.lowercase().contains(needle) }.take(20).map { v ->
            MediaItem(
                key = v.tmdbId?.let { "tmdb:Movie:$it" } ?: xcKey(MediaType.Movie, v.streamId),
                type = MediaType.Movie, title = v.name, year = v.year, poster = v.poster, rating = v.rating, tmdbId = v.tmdbId,
                origin = "IPTV",
            )
        }
        val sr = series.asSequence().filter { it.name.lowercase().contains(needle) }.take(20).map { v ->
            MediaItem(
                key = v.tmdbId?.let { "tmdb:Show:$it" } ?: xcKey(MediaType.Show, v.seriesId),
                type = MediaType.Show, title = v.name, year = v.year, poster = v.poster, backdrop = v.backdrop, overview = v.plot,
                rating = v.rating, tmdbId = v.tmdbId, origin = "IPTV",
            )
        }
        return (f + sr).toList()
    }

    /** People whose name matches (actors, directors…), with their photo. Opening one lists their films and shows. */
    private suspend fun tmdbPeople(key: String, q: String): MediaRow? {
        val r = http.get(tmdb.url("/search/person", key, mapOf("language" to "en-GB", "query" to q, "include_adult" to "false")), tmdb.headers(key))
        if (!r.ok) return null
        val people = r.body.array("results").mapNotNull { j ->
            val id = j.int("id") ?: return@mapNotNull null
            val photo = j.str("profile_path") ?: return@mapNotNull null
            MediaItem(
                key = "person:$id", type = MediaType.Movie, title = j.str("name") ?: return@mapNotNull null,
                poster = TMDB_IMG + "w185" + photo, origin = "TMDB", subtitle = j.str("known_for_department"),
            )
        }
        return MediaRow("search-people", "People", people.take(20))
    }

    /** A person's films and shows (acting, and directing / writing), most popular first. */
    suspend fun personCredits(personId: String, name: String): List<MediaRow> {
        val key = store.current().tmdbKey ?: return emptyList()
        val r = http.get(tmdb.url("/person/$personId/combined_credits", key, mapOf("language" to "en-GB")), tmdb.headers(key))
        if (!r.ok) return emptyList()
        fun pop(j: JsonElement?) = (j.child("popularity") as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun items(list: List<JsonElement>, type: MediaType) = list.sortedByDescending { pop(it) }
            .mapNotNull { tmdbItem(it, type) }.filter { it.poster != null }.distinctBy { it.key }.take(40)
        val cast = r.body.array("cast")
        val crew = r.body.array("crew").filter { it.str("job") == "Director" || it.str("department") == "Writing" }
        return listOfNotNull(
            MediaRow("person-films", "Films with $name", items(cast.filter { it.str("media_type") == "movie" }, MediaType.Movie)),
            MediaRow("person-shows", "Shows with $name", items(cast.filter { it.str("media_type") == "tv" }, MediaType.Show)),
            MediaRow("person-made", "Directed or written by $name", items(crew.filter { it.str("media_type") == "movie" }, MediaType.Movie) +
                items(crew.filter { it.str("media_type") == "tv" }, MediaType.Show)),
        ).filter { it.items.isNotEmpty() }
    }

    /** Quick picks while typing: the best-known titles and names that match (TMDB). */
    suspend fun suggestions(q: String): List<String> {
        val key = store.current().tmdbKey ?: return emptyList()
        val r = runCatching { http.get(tmdb.url("/search/multi", key, mapOf("language" to "en-GB", "query" to q, "include_adult" to "false")), tmdb.headers(key)) }
            .getOrNull()?.takeIf { it.ok } ?: return emptyList()
        return r.body.array("results")
            .sortedByDescending { (it.child("popularity") as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
            .mapNotNull { it.str("title") ?: it.str("name") }
            .distinctBy { it.lowercase() }
            .filter { !it.equals(q, ignoreCase = true) }
            .take(6)
    }

    private suspend fun tmdbSearch(key: String, type: MediaType, q: String): MediaRow? {
        val path = if (type == MediaType.Movie) "/search/movie" else "/search/tv"
        val r = http.get(tmdb.url(path, key, mapOf("language" to "en-GB", "query" to q, "include_adult" to "false")), tmdb.headers(key))
        if (!r.ok) return null
        val items = r.body.array("results").mapNotNull { tmdbItem(it, type) }.filter { it.poster != null }
        items.forEach { tmdbCache[it.key] = it }
        return MediaRow("search-tmdb-${type.name}", if (type == MediaType.Movie) "Films" else "Shows", items)
    }

    private suspend fun plexSearch(a: Accounts, p: PlexAuth, q: String): MediaRow? {
        val hubs = plexGet(a, p, "/hubs/search?query=${enc(q)}&limit=30&includeGuids=1")?.array("Hub").orEmpty()
        val items = hubs.flatMap { h ->
            val type = when (h.str("type")) { "movie" -> MediaType.Movie; "show" -> MediaType.Show; else -> null } ?: return@flatMap emptyList()
            h.array("Metadata").mapNotNull { plexItem(p, it, type) }
        }
        return MediaRow("search-plex", "In your Plex library", items)
    }

    private suspend fun stremioSearch(addon: AddonInfo, q: String): List<MediaRow> {
        val manifest = http.get("${addon.baseUrl}/manifest.json").takeIf { it.ok }?.body ?: return emptyList()
        val catalogs = manifest.array("catalogs").filter { c ->
            (c.str("type") == "movie" || c.str("type") == "series") &&
                (c.array("extra").any { it.str("name") == "search" } || c.array("extraSupported").any { (it as? JsonPrimitive)?.content == "search" })
        }.take(6)
        return coroutineScope {
            catalogs.map { c ->
                async {
                    val id = c.str("id") ?: return@async null
                    val st = c.str("type")!!
                    val type = if (st == "movie") MediaType.Movie else MediaType.Show
                    val r = runCatching { http.get("${addon.baseUrl}/catalog/$st/${enc(id)}/search=${enc(q)}.json") }.getOrNull()?.takeIf { it.ok } ?: return@async null
                    val items = r.body.array("metas").take(30).mapNotNull { m -> stremioMeta(addon, m, type) }
                    MediaRow("search-stremio-${addon.id}-$id-$st", "${addon.name}: ${c.str("name") ?: id}", items)
                }
            }.awaitAll().filterNotNull()
        }
    }

    // ------------------------------------------------------------------ TMDB

    private fun tmdbItem(j: JsonElement?, type: MediaType, origin: String = "TMDB"): MediaItem? {
        val id = j.int("id") ?: return null
        val title = j.str("title") ?: j.str("name") ?: return null
        val date = j.str("release_date") ?: j.str("first_air_date")
        return MediaItem(
            key = "tmdb:${type.name}:$id",
            type = type,
            title = title,
            year = date?.take(4)?.toIntOrNull(),
            poster = j.str("poster_path")?.let { TMDB_IMG + "w342" + it },
            backdrop = j.str("backdrop_path")?.let { TMDB_IMG + "w780" + it },
            overview = j.str("overview")?.takeIf { it.isNotBlank() },
            tmdbId = id,
            imdbId = j.child("external_ids").str("imdb_id") ?: j.str("imdb_id"),
            rating = (j.child("vote_average") as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 },
            origin = origin,
        )
    }

    private suspend fun tmdbList(key: String, type: MediaType, path: String, id: String, title: String, extra: Map<String, String> = emptyMap()): MediaRow? {
        val r = http.get(tmdb.url(path, key, mapOf("language" to "en-GB") + extra), tmdb.headers(key))
        if (!r.ok) return null
        val items = r.body.array("results").mapNotNull { tmdbItem(it, type) }
        items.forEach { tmdbCache[it.key] = it }
        return MediaRow("tmdb-$id-${type.name}", title, items)
    }

    /** Fills poster, backdrop and overview from TMDB for items that only have ids (Trakt, Stremio without art). */
    private suspend fun enrich(a: Accounts, items: List<MediaItem>): List<MediaItem> {
        val key = a.tmdbKey ?: return items
        return coroutineScope {
            items.map { item ->
                async {
                    val id = item.tmdbId ?: return@async item
                    val cacheKey = "tmdb:${item.type.name}:$id"
                    val hit = tmdbCache[cacheKey] ?: tmdbLimit.withPermit {
                        val path = if (item.type == MediaType.Movie) "/movie/$id" else "/tv/$id"
                        runCatching { http.get(tmdb.url(path, key, mapOf("language" to "en-GB")), tmdb.headers(key)) }.getOrNull()
                            ?.takeIf { it.ok }?.let { tmdbItem(it.body, item.type) }?.also { tmdbCache[cacheKey] = it }
                    }
                    if (hit == null) item else item.copy(
                        poster = item.poster ?: hit.poster,
                        backdrop = item.backdrop ?: hit.backdrop,
                        overview = item.overview ?: hit.overview,
                        rating = item.rating ?: hit.rating,
                        year = item.year ?: hit.year,
                    )
                }
            }.awaitAll()
        }
    }

    // ------------------------------------------------------------------ Upcoming episodes

    /**
     * "Upcoming episodes": when the next episodes of the shows you follow air. From your Trakt calendar when Trakt is
     * signed in; otherwise from TMDB's next episode for the shows you're watching (continue watching on Plex,
     * Jellyfin and Emby). One card per show, soonest first, the next five weeks.
     */
    private suspend fun upcomingRow(a: Accounts, traktAuth: TraktAuth?): MediaRow? {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        data class Up(val item: MediaItem, val date: java.time.LocalDate, val time: java.time.ZonedDateTime?, val s: Int?, val e: Int?, val name: String?)
        val found = ArrayList<Up>()
        if (traktAuth != null) {
            val r = runCatching { http.get("https://api.trakt.tv/calendars/my/shows/$today/35", trakt.headers(traktAuth.accessToken)) }.getOrNull()
            r?.takeIf { it.ok }?.body?.asArray()?.forEach { j ->
                val item = traktItem(j.child("show"), MediaType.Show, "Trakt") ?: return@forEach
                val at = j.str("first_aired")?.let { runCatching { java.time.Instant.parse(it).atZone(zone) }.getOrNull() } ?: return@forEach
                val ep = j.child("episode")
                found += Up(item, at.toLocalDate(), at, ep.int("season"), ep.int("number"), ep.str("title"))
            }
        }
        val key = a.tmdbKey
        if (found.isEmpty() && key != null) {
            // The shows you're part-way through, then each one's next episode on TMDB.
            val watching = coroutineScope {
                (plexServers(a).map { p -> async { runCatching { plexOnDeck(a, p, MediaType.Show) }.getOrNull()?.items.orEmpty() } } +
                    listOfNotNull(a.jellyfin).map { j -> async { runCatching { serverResume(a, j, emby = false, MediaType.Show) }.getOrNull()?.items.orEmpty() } } +
                    listOfNotNull(a.emby).map { e -> async { runCatching { serverResume(a, e, emby = true, MediaType.Show) }.getOrNull()?.items.orEmpty() } })
                    .awaitAll().flatten()
            }.filter { it.tmdbId != null }.distinctBy { it.tmdbId }.take(30)
            coroutineScope {
                watching.map { show ->
                    async {
                        tmdbLimit.withPermit {
                            val r = runCatching { http.get(tmdb.url("/tv/${show.tmdbId}", key, mapOf("language" to "en-GB")), tmdb.headers(key)) }.getOrNull()
                            val next = r?.takeIf { it.ok }?.body?.child("next_episode_to_air") ?: return@withPermit null
                            val date = next.str("air_date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return@withPermit null
                            val item = (tmdbItem(r.body, MediaType.Show) ?: show).copy(origin = show.origin)
                            Up(item, date, null, next.int("season_number"), next.int("episode_number"), next.str("name"))
                        }
                    }
                }.awaitAll().filterNotNull().forEach { found += it }
            }
        }
        val now = java.time.ZonedDateTime.now(zone)
        // Not yet aired: a timed episode (Trakt) still to come; a dated one (TMDB) today or later.
        val soon = found.filter { (it.time?.isAfter(now) ?: !it.date.isBefore(today)) && it.date.isBefore(today.plusDays(35)) }
            .sortedWith(compareBy({ it.date }, { it.time }))
            .distinctBy { it.item.tmdbId ?: it.item.key }
            .take(30)
        if (soon.isEmpty()) return null
        val items = enrich(a, soon.map { it.item }).zip(soon).map { (item, u) ->
            val day = when {
                u.date == today -> "Today"
                u.date == today.plusDays(1) -> "Tomorrow"
                u.date.isBefore(today.plusDays(7)) -> u.date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE"))
                else -> u.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
            }
            val clock = u.time?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            val ep = if (u.s != null && u.e != null) "S${u.s} · E${u.e}" else null
            item.copy(
                key = item.key + ":upcoming",
                subtitle = listOfNotNull(day + (clock?.let { " $it" } ?: ""), ep).joinToString("  ·  "),
            )
        }
        return MediaRow("upcoming-${MediaType.Show.name}", "Upcoming episodes", items)
    }

    // ------------------------------------------------------------------ Trakt

    private suspend fun freshTrakt(auth: TraktAuth): TraktAuth {
        if (auth.expiresAt - System.currentTimeMillis() > 3_600_000) return auth
        val r = runCatching {
            http.post("https://api.trakt.tv/oauth/token", buildJsonObject {
                put("refresh_token", auth.refreshToken)
                put("client_id", TraktService.CLIENT_ID)
                put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                put("grant_type", "refresh_token")
            }, trakt.headers())
        }.getOrNull()?.takeIf { it.ok }
        if (r == null) {
            Log.w(TAG, "trakt refresh failed")
            return auth
        }
        val access = r.body.str("access_token") ?: return auth
        val created = r.body.long("created_at") ?: (System.currentTimeMillis() / 1000)
        val fresh = auth.copy(
            accessToken = access,
            refreshToken = r.body.str("refresh_token") ?: auth.refreshToken,
            expiresAt = (created + (r.body.long("expires_in") ?: 86_400)) * 1000,
        )
        store.update { it.copy(trakt = fresh) }
        return fresh
    }

    private fun traktItem(j: JsonElement?, type: MediaType, origin: String): MediaItem? {
        val ids = j.child("ids")
        val title = j.str("title") ?: return null
        val tmdbId = ids.int("tmdb")
        return MediaItem(
            key = tmdbId?.let { "tmdb:${type.name}:$it" } ?: "trakt:${type.name}:${ids.int("trakt")}",
            type = type,
            title = title,
            year = j.int("year"),
            tmdbId = tmdbId,
            imdbId = ids.str("imdb"),
            origin = origin,
        )
    }

    private suspend fun traktWatchlistRow(a: Accounts, t: TraktAuth, type: MediaType): MediaRow? {
        val kind = if (type == MediaType.Movie) "movies" else "shows"
        val field = if (type == MediaType.Movie) "movie" else "show"
        val r = http.get("https://api.trakt.tv/sync/watchlist/$kind/added", trakt.headers(t.accessToken))
        if (!r.ok) return null
        val items = r.body.asArray().mapNotNull { traktItem(it.child(field), type, "Trakt watchlist") }
        items.mapNotNull { it.tmdbId }.forEach { traktWatchlist.add("${type.name}:$it") }
        return MediaRow("trakt-watchlist-${type.name}", "Your watchlist", enrich(a, items.take(40)))
    }

    private suspend fun traktRecommended(a: Accounts, t: TraktAuth, type: MediaType): MediaRow? {
        val kind = if (type == MediaType.Movie) "movies" else "shows"
        val r = http.get("https://api.trakt.tv/recommendations/$kind?limit=30&ignore_collected=true&ignore_watchlisted=true", trakt.headers(t.accessToken))
        if (!r.ok) return null
        val items = r.body.asArray().mapNotNull { traktItem(it, type, "Trakt") }
        return MediaRow("trakt-reco-${type.name}", "Recommended for you", enrich(a, items))
    }

    fun inTraktWatchlist(item: MediaItem) = item.tmdbId?.let { "${item.type.name}:$it" in traktWatchlist } == true

    /** Adds to or removes from the Trakt watchlist. Returns the new state, or null if it failed. */
    suspend fun setTraktWatchlist(item: MediaItem, add: Boolean): Boolean? {
        val id = item.tmdbId ?: return null
        val auth = store.current().trakt?.let { freshTrakt(it) } ?: return null
        val body = buildJsonObject {
            putJsonArray(if (item.type == MediaType.Movie) "movies" else "shows") {
                add(buildJsonObject { putJsonObject("ids") { put("tmdb", id) } })
            }
        }
        val url = "https://api.trakt.tv/sync/watchlist" + if (add) "" else "/remove"
        val ok = runCatching { http.post(url, body, trakt.headers(auth.accessToken)).ok }.getOrDefault(false)
        if (!ok) return null
        if (add) traktWatchlist.add("${item.type.name}:$id") else traktWatchlist.remove("${item.type.name}:$id")
        return add
    }

    // ------------------------------------------------------------------ Plex

    // ------------------------------------------------------------------ several Plex servers

    /** The main server's id (the one linked in setup): its items keep plain keys, others get "@server". */
    @Volatile private var mainPlexMachine: String? = null

    /** Every Plex server: the one linked in setup first, then the others on the same account. */
    private fun plexServers(a: Accounts): List<PlexAuth> {
        mainPlexMachine = a.plex?.machineId
        return (listOfNotNull(a.plex) + a.plexMore.filter { m -> m.machineId != a.plex?.machineId }).filter { it.machineId !in a.plexOff }
    }

    private fun isMainPlex(p: PlexAuth) = mainPlexMachine == null || p.machineId == mainPlexMachine

    /** A rating key that also says which server it's on: plain on the main one, "rk@server" on others. */
    private fun plexRef(p: PlexAuth, rk: String) = if (isMainPlex(p)) rk else "$rk@${p.machineId}"

    /** The server and plain rating key behind a [plexRef]. */
    private fun plexResolve(a: Accounts, ref: String): Pair<PlexAuth, String>? {
        val servers = plexServers(a)
        val rk = ref.substringBefore('@')
        val machine = ref.substringAfter('@', "")
        val p = if (machine.isEmpty()) a.plex?.takeIf { it.machineId !in a.plexOff } else servers.firstOrNull { it.machineId == machine }
        return p?.let { it to rk }
    }

    // ------------------------------------------------------------------ whole libraries (menu → Movies / Shows)

    /** Your film or show libraries on every Plex server, Jellyfin and Emby. */
    suspend fun libraries(type: MediaType): List<MediaLibrary> = coroutineScope {
        val a = store.current()
        val servers = plexServers(a)
        val many = servers.size > 1
        val plexParts = servers.map { p ->
            async {
                runCatching { withTimeoutOrNull(15_000) { plexSections(a, p, type) } }.getOrNull().orEmpty().mapNotNull { s ->
                    val k = s.str("key") ?: return@mapNotNull null
                    MediaLibrary("plex:${p.machineId}:$k", tidyLibraryName(s.str("title") ?: "Plex"), if (many && !isMainPlex(p)) "Plex · ${p.serverName}" else "Plex", k, p.machineId)
                }
            }
        }
        val others = listOfNotNull(a.jellyfin?.let { it to false }, a.emby?.let { it to true }).map { (srv, emby) ->
            async {
                val name = if (emby) "Emby" else "Jellyfin"
                val r = runCatching { withTimeoutOrNull(15_000) { http.get("${srv.serverUrl}/Users/${srv.userId}/Views", mediaServers.headers(emby, a.deviceId, srv.token)) } }
                    .getOrNull()?.takeIf { it.ok } ?: return@async emptyList()
                val want = if (type == MediaType.Movie) "movies" else "tvshows"
                r.body.array("Items").filter { it.str("CollectionType") == want }.mapNotNull { v ->
                    val id = v.str("Id") ?: return@mapNotNull null
                    MediaLibrary("${name.lowercase()}:$id", tidyLibraryName(v.str("Name") ?: name), name, id)
                }
            }
        }
        val provider = async {
            if (dispatcharrVod() == null && xcSession() == null) emptyList()
            else listOf(MediaLibrary("provider:${type.name}", if (type == MediaType.Movie) "IPTV Movies" else "IPTV Shows", PROVIDER_LIBRARY, "all"))
        }
        (plexParts + others + provider).awaitAll().flatten()
    }

    /** One page of a library and how many titles it has in all; null if the server didn't answer. */
    /** A library's genres, from the server itself (id to name), for filtering. */
    suspend fun libraryGenres(lib: MediaLibrary, type: MediaType): List<Pair<String, String>> {
        val a = store.current()
        if (lib.source == PROVIDER_LIBRARY) {
            // The provider's categories stand in for genres ("|EN| ACTION/THRILLER" → "Action/Thriller").
            if (dispatcharrVod() != null) {
                val want = if (type == MediaType.Movie) "movie" else "series"
                val list = dvodGet("categories/", emptyMap())
                val cats = ((list as? kotlinx.serialization.json.JsonArray) ?: list.array("results")).filter { c ->
                    c.str("category_type") == want && c.array("m3u_accounts").any { it.child("enabled")?.toString() == "true" }
                }
                return cats.mapNotNull { c -> c.str("name")?.let { n -> "$n|$want" to tidyCategory(n) } }.distinctBy { it.second }.sortedBy { it.second.lowercase() }
            }
            val s = xcSession() ?: return emptyList()
            val cats = runCatching { if (type == MediaType.Movie) xtream.vodCategories(s) else xtream.seriesCategories(s) }.getOrDefault(emptyList())
            return cats.map { c -> c.id to tidyCategory(c.name) }.sortedBy { it.second.lowercase() }
        }
        if (lib.server != null) {
            val p = plexServers(a).firstOrNull { it.machineId == lib.server } ?: return emptyList()
            val c = runCatching { plexGet(a, p, "/library/sections/${lib.id}/genre") }.getOrNull() ?: return emptyList()
            return c.array("Directory").mapNotNull { d -> val k = d.str("key") ?: return@mapNotNull null; k to (d.str("title") ?: k) }
        }
        val emby = lib.source == "Emby"
        val srv = (if (emby) a.emby else a.jellyfin) ?: return emptyList()
        val kind = if (type == MediaType.Movie) "Movie" else "Series"
        val r = runCatching {
            http.get("${srv.serverUrl}/Genres?ParentId=${enc(lib.id)}&UserId=${srv.userId}&IncludeItemTypes=$kind&Recursive=true&SortBy=SortName", mediaServers.headers(emby, a.deviceId, srv.token))
        }.getOrNull()?.takeIf { it.ok } ?: return emptyList()
        return r.body.array("Items").mapNotNull { g -> val id = g.str("Id") ?: return@mapNotNull null; id to (g.str("Name") ?: id) }
    }

    suspend fun libraryPage(
        lib: MediaLibrary, type: MediaType, sort: LibrarySort, start: Int, size: Int, query: String = "",
        genre: String? = null, unwatched: Boolean = false,
    ): Pair<List<MediaItem>, Int>? {
        val q = query.trim()
        val a = store.current()
        if (lib.source == PROVIDER_LIBRARY) return providerPage(type, sort, start, size, q, genre)
        if (lib.server != null) {
            val p = plexServers(a).firstOrNull { it.machineId == lib.server } ?: return null
            val c = runCatching {
                plexGet(a, p, "/library/sections/${lib.id}/all?sort=${sort.plex}&includeGuids=1&X-Plex-Container-Start=$start&X-Plex-Container-Size=$size" +
                    (if (q.isNotEmpty()) "&title=${enc(q)}" else "") +
                    (if (genre != null) "&genre=${enc(genre)}" else "") +
                    (if (unwatched) (if (type == MediaType.Movie) "&unwatched=1" else "&unwatchedLeaves=1") else ""))
            }.getOrNull() ?: return null
            val items = c.array("Metadata").mapNotNull { plexItem(p, it, type) }
            return items to (c.int("totalSize") ?: c.int("size") ?: items.size)
        }
        val emby = lib.source == "Emby"
        val srv = (if (emby) a.emby else a.jellyfin) ?: return null
        val kind = if (type == MediaType.Movie) "Movie" else "Series"
        val (by, order) = sort.jellyfin
        val r = runCatching {
            http.get(
                "${srv.serverUrl}/Users/${srv.userId}/Items?ParentId=${enc(lib.id)}&IncludeItemTypes=$kind&Recursive=true" +
                    "&SortBy=$by&SortOrder=$order&StartIndex=$start&Limit=$size&Fields=ProviderIds,Overview,ProductionYear" +
                    (if (q.isNotEmpty()) "&SearchTerm=${enc(q)}" else "") +
                    (if (genre != null) "&GenreIds=${enc(genre)}" else "") +
                    (if (unwatched) "&IsPlayed=false" else ""),
                mediaServers.headers(emby, a.deviceId, srv.token),
            )
        }.getOrNull()?.takeIf { it.ok } ?: return null
        val items = r.body.array("Items").mapNotNull { serverItem(srv, it, type, lib.source) }
        return items to (r.body.int("TotalRecordCount") ?: items.size)
    }

    /** Finds your other Plex servers on the account (e.g. a second machine) and remembers them. */
    suspend fun refreshPlexServers() {
        val a = store.current()
        val p = a.plex ?: return
        val found = runCatching { plex.servers(a.deviceId, p.token) }.getOrDefault(emptyList())
        if (found.isEmpty()) return
        val more = found.filter { it.machineId != p.machineId }.map(plex::toAuth)
        if (more != a.plexMore) {
            store.update { it.copy(plexMore = more) }
            Log.i(TAG, "plex servers: ${p.serverName} + ${more.joinToString { it.serverName }}")
        }
    }

    private fun plexImage(p: PlexAuth, path: String?, w: Int, h: Int) = path?.let {
        "${p.serverUrl}/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1&url=${enc(it)}&X-Plex-Token=${enc(p.token)}"
    }

    private fun guidIds(j: JsonElement?): Pair<Int?, String?> {
        val guids = j.array("Guid").mapNotNull { it.str("id") }
        return guids.firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toIntOrNull() to
            guids.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://")
    }

    private fun plexItem(p: PlexAuth, j: JsonElement?, type: MediaType): MediaItem? {
        val ratingKey = j.str("ratingKey") ?: return null
        val (tmdbId, imdbId) = guidIds(j)
        return MediaItem(
            key = tmdbId?.let { "tmdb:${type.name}:$it" } ?: "plex:${plexRef(p, ratingKey)}",
            type = type,
            title = j.str("title") ?: return null,
            year = j.int("year"),
            poster = plexImage(p, j.str("thumb"), 240, 360),
            backdrop = plexImage(p, j.str("art"), 960, 540),
            overview = j.str("summary")?.takeIf { it.isNotBlank() },
            tmdbId = tmdbId,
            imdbId = imdbId,
            rating = (j.child("audienceRating") as? JsonPrimitive)?.doubleOrNull,
            origin = if (isMainPlex(p)) "Plex" else "Plex · ${p.serverName}",
        )
    }

    private suspend fun plexGet(a: Accounts, p: PlexAuth, path: String) =
        http.get(p.serverUrl + path, plex.headers(a.deviceId, p.token)).takeIf { it.ok }?.body.child("MediaContainer")

    private suspend fun plexSections(a: Accounts, p: PlexAuth, type: MediaType) =
        plexGet(a, p, "/library/sections")?.array("Directory").orEmpty()
            .filter { it.str("type") == if (type == MediaType.Movie) "movie" else "show" }

    private suspend fun plexRecent(a: Accounts, p: PlexAuth, type: MediaType): List<MediaRow> {
        val sections = plexSections(a, p, type)
        return coroutineScope {
            sections.map { s ->
                async {
                    val key = s.str("key") ?: return@async null
                    val name = s.str("title") ?: "Plex"
                    // Each library on its own clock: one slow library (big, or a remote server) mustn't hold up the rest.
                    val items = runCatching {
                        withTimeoutOrNull(15_000) {
                            plexGet(a, p, "/library/sections/$key/all?sort=addedAt:desc&includeGuids=1&X-Plex-Container-Start=0&X-Plex-Container-Size=40")
                                ?.array("Metadata").orEmpty().mapNotNull { plexItem(p, it, type) }
                        }
                    }.getOrNull() ?: run { Log.w(TAG, "plex library '$name' didn't answer in time"); return@async null }
                    // The main server keeps its old row ids (Home rows settings); others are told apart by server.
                    val main = isMainPlex(p)
                    MediaRow(
                        if (main) "plex-recent-$key" else "plex-${p.machineId}-recent-$key",
                        (if (sections.size == 1) "Recently added" else "Recently added in $name") + if (main) "" else " · ${p.serverName}",
                        items,
                        group = "recent:" + tidyLibraryName(name).lowercase(),
                        groupName = tidyLibraryName(name),
                    )
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** A handful of the newest titles across your libraries (the menu's Movies / Shows panel). */
    suspend fun recentlyAdded(type: MediaType, limit: Int = 12): List<MediaItem> = coroutineScope {
        val a = store.current()
        val parts = buildList {
            plexServers(a).forEach { p ->
                add(async {
                    runCatching {
                        withTimeoutOrNull(15_000) {
                            val sections = plexSections(a, p, type).mapNotNull { it.str("key") }
                            sections.map { key ->
                                async {
                                    runCatching { plexGet(a, p, "/library/sections/$key/all?sort=addedAt:desc&includeGuids=1&X-Plex-Container-Start=0&X-Plex-Container-Size=4") }
                                        .getOrNull()?.array("Metadata").orEmpty().mapNotNull { j -> plexItem(p, j, type)?.let { it to (j.long("addedAt") ?: 0L) } }
                                }
                            }.awaitAll().flatten()
                        }
                    }.getOrNull().orEmpty()
                })
            }
            listOfNotNull(a.jellyfin?.let { it to false }, a.emby?.let { it to true }).forEach { (srv, emby) ->
                add(async {
                    runCatching {
                        withTimeoutOrNull(15_000) {
                            val name = if (emby) "Emby" else "Jellyfin"
                            val kind = if (type == MediaType.Movie) "Movie" else "Series"
                            http.get(
                                "${srv.serverUrl}/Users/${srv.userId}/Items?IncludeItemTypes=$kind&SortBy=DateCreated&SortOrder=Descending&Recursive=true&Limit=8&Fields=ProviderIds,Overview,ProductionYear,DateCreated",
                                mediaServers.headers(emby, a.deviceId, srv.token),
                            ).takeIf { it.ok }?.body?.array("Items").orEmpty().mapNotNull { j ->
                                val added = runCatching { java.time.Instant.parse(j.str("DateCreated")).epochSecond }.getOrDefault(0L)
                                serverItem(srv, j, type, name)?.let { it to added }
                            }
                        }
                    }.getOrNull().orEmpty()
                })
            }
        }
        parts.awaitAll().flatten().sortedByDescending { it.second }.map { it.first }.distinctBy { it.key }.take(limit)
    }

    /** Continue watching from every server (Plex On Deck, Jellyfin / Emby resume), for the TV's Watch Next row. */
    suspend fun continueWatching(type: MediaType): List<MediaItem> = coroutineScope {
        val a = store.current()
        val parts = buildList {
            plexServers(a).forEach { p -> add(async { runCatching { withTimeoutOrNull(20_000) { plexOnDeck(a, p, type)?.items } }.getOrNull().orEmpty() }) }
            a.jellyfin?.let { j -> add(async { runCatching { withTimeoutOrNull(20_000) { serverResume(a, j, emby = false, type)?.items } }.getOrNull().orEmpty() }) }
            a.emby?.let { e -> add(async { runCatching { withTimeoutOrNull(20_000) { serverResume(a, e, emby = true, type)?.items } }.getOrNull().orEmpty() }) }
        }
        parts.awaitAll().flatten().distinctBy { it.key }
    }

    private suspend fun plexOnDeck(a: Accounts, p: PlexAuth, type: MediaType): MediaRow? {
        val all = plexGet(a, p, "/library/onDeck?includeGuids=1")?.array("Metadata").orEmpty()
        val items = coroutineScope {
            all.filter { it.str("type") == if (type == MediaType.Movie) "movie" else "episode" }.take(20).map { j ->
                async {
                    val progress = j.long("viewOffset")?.let { off -> j.long("duration")?.takeIf { it > 0 }?.let { off.toFloat() / it } }
                    if (type == MediaType.Movie) plexItem(p, j, type)?.copy(progress = progress, durationMs = j.long("duration"))
                    else {
                        // An episode: show the series, with the episode underneath.
                        val showKey = j.str("grandparentRatingKey") ?: return@async null
                        val show = plexGet(a, p, "/library/metadata/$showKey?includeGuids=1")?.array("Metadata")?.firstOrNull()
                        val base = show?.let { plexItem(p, it, type) } ?: return@async null
                        base.copy(
                            nextSeason = j.int("parentIndex"),
                            nextEpisode = j.int("index"),
                            key = base.key + ":deck",
                            subtitle = "S${j.int("parentIndex") ?: "?"} · E${j.int("index") ?: "?"}  ${j.str("title").orEmpty()}",
                            backdrop = plexImage(p, j.str("art") ?: j.str("grandparentArt"), 960, 540) ?: base.backdrop,
                            progress = progress,
                            durationMs = j.long("duration"),
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return if (isMainPlex(p)) MediaRow("plex-deck-${type.name}", "Continue watching", items, group = "deck")
        else MediaRow("plex-deck-${type.name}-${p.machineId}", "Continue watching on ${p.serverName}", items, group = "deck")
    }

    // ------------------------------------------------------------------ Jellyfin / Emby

    private fun serverItem(s: MediaServerAuth, j: JsonElement?, type: MediaType, origin: String, idField: String = "Id"): MediaItem? {
        val id = j.str(idField) ?: return null
        val tmdbId = j.child("ProviderIds").str("Tmdb")?.toIntOrNull()
        return MediaItem(
            key = tmdbId?.let { "tmdb:${type.name}:$it" } ?: "${origin.lowercase()}:$id",
            type = type,
            title = j.str("Name") ?: return null,
            year = j.int("ProductionYear"),
            poster = "${s.serverUrl}/Items/$id/Images/Primary?maxHeight=360&quality=85&api_key=${enc(s.token)}",
            backdrop = "${s.serverUrl}/Items/$id/Images/Backdrop?maxWidth=960&quality=80&api_key=${enc(s.token)}",
            overview = j.str("Overview"),
            tmdbId = tmdbId,
            imdbId = j.child("ProviderIds").str("Imdb"),
            rating = (j.child("CommunityRating") as? JsonPrimitive)?.doubleOrNull,
            origin = origin,
        )
    }

    private suspend fun serverLatest(a: Accounts, s: MediaServerAuth, emby: Boolean, type: MediaType): MediaRow? {
        val name = if (emby) "Emby" else "Jellyfin"
        val kind = if (type == MediaType.Movie) "Movie" else "Series"
        val r = http.get(
            "${s.serverUrl}/Users/${s.userId}/Items?IncludeItemTypes=$kind&SortBy=DateCreated&SortOrder=Descending&Recursive=true&Limit=30&Fields=ProviderIds,Overview,ProductionYear",
            mediaServers.headers(emby, a.deviceId, s.token),
        )
        if (!r.ok) return null
        val items = r.body.array("Items").mapNotNull { serverItem(s, it, type, name) }
        return MediaRow("$name-latest-${type.name}", "Recently added on $name", items)
    }

    private suspend fun serverResume(a: Accounts, s: MediaServerAuth, emby: Boolean, type: MediaType): MediaRow? {
        val name = if (emby) "Emby" else "Jellyfin"
        val kind = if (type == MediaType.Movie) "Movie" else "Episode"
        val r = http.get(
            "${s.serverUrl}/Users/${s.userId}/Items/Resume?IncludeItemTypes=$kind&Limit=20&Fields=ProviderIds,Overview,ProductionYear",
            mediaServers.headers(emby, a.deviceId, s.token),
        )
        if (!r.ok) return null
        val items = r.body.array("Items").mapNotNull { j ->
            val pct = (j.child("UserData").child("PlayedPercentage") as? JsonPrimitive)?.doubleOrNull?.toFloat()?.div(100f)
            val length = j.long("RunTimeTicks")?.div(10_000)
            if (type == MediaType.Movie) serverItem(s, j, type, name)?.copy(progress = pct, durationMs = length)
            else serverItem(s, buildJsonObject {
                put("Id", j.str("SeriesId"))
                put("Name", j.str("SeriesName"))
            }, type, name)?.copy(
                key = "${name.lowercase()}:${j.str("SeriesId")}:resume",
                nextSeason = j.int("ParentIndexNumber"),
                nextEpisode = j.int("IndexNumber"),
                subtitle = "S${j.int("ParentIndexNumber") ?: "?"} · E${j.int("IndexNumber") ?: "?"}  ${j.str("Name").orEmpty()}",
                progress = pct,
                durationMs = length,
            )
        }
        return MediaRow("$name-resume-${type.name}", "Continue watching on $name", enrich(a, items), group = "deck")
    }

    // ------------------------------------------------------------------ Stremio addons

    private suspend fun stremioRows(addon: AddonInfo, type: MediaType): List<MediaRow> {
        val stremioType = if (type == MediaType.Movie) "movie" else "series"
        val manifest = http.get("${addon.baseUrl}/manifest.json").takeIf { it.ok }?.body ?: return emptyList()
        val catalogs = manifest.array("catalogs").filter { c ->
            c.str("type") == stremioType &&
                c.array("extra").none { e -> e.str("isRequired") == "true" } &&
                c.array("extraRequired").isEmpty()
        }.take(8)
        return coroutineScope {
            catalogs.map { c ->
                async {
                    val id = c.str("id") ?: return@async null
                    val r = runCatching { http.get("${addon.baseUrl}/catalog/$stremioType/${enc(id)}.json") }.getOrNull()?.takeIf { it.ok }
                        ?: return@async null
                    val items = r.body.array("metas").take(40).mapNotNull { m -> stremioMeta(addon, m, type) }
                    MediaRow("stremio-${addon.id}-$id-${type.name}", c.str("name") ?: addon.name, items)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun stremioMeta(addon: AddonInfo, m: JsonElement, type: MediaType): MediaItem? {
        val metaId = m.str("id") ?: return null
        return MediaItem(
            key = "stremio:${addon.id}:$metaId",
            type = type,
            title = m.str("name") ?: return null,
            year = m.str("releaseInfo")?.take(4)?.toIntOrNull() ?: m.int("year"),
            poster = m.str("poster"),
            backdrop = m.str("background"),
            overview = m.str("description"),
            imdbId = metaId.takeIf { it.startsWith("tt") } ?: m.str("imdb_id"),
            tmdbId = m.str("moviedb_id")?.toIntOrNull() ?: metaId.removePrefix("tmdb:").toIntOrNull(),
            rating = m.str("imdbRating")?.toDoubleOrNull(),
            origin = addon.name,
        )
    }

    // ------------------------------------------------------------------ In your library

    /** Answers already worked out this run (title key → copies), kept for 10 minutes. */
    private val copyCache = ConcurrentHashMap<String, Pair<Long, List<LibraryCopy>>>()
    private val plexSectionCache = ConcurrentHashMap<String, Pair<Long, List<JsonElement>>>()

    /**
     * Which of the user's own servers have this title. Each server is asked about just this title (by name, then
     * matched on its TMDB / IMDb id), so it's quick however big the libraries are. Reading whole libraries instead
     * took minutes, or ran the TV out of memory, on big Plex servers.
     */
    suspend fun copiesOf(item: MediaItem): List<LibraryCopy> {
        copyCache[item.key]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        val a = store.current()
        val found = coroutineScope {
            val jobs = buildList {
                plexServers(a).forEach { p -> add(async { runCatching { withTimeoutOrNull(15_000) { plexCopies(a, p, item) } }.getOrNull().orEmpty() }) }
                listOfNotNull(a.jellyfin?.let { it to false }, a.emby?.let { it to true }).forEach { (srv, emby) ->
                    add(async { runCatching { withTimeoutOrNull(15_000) { serverCopies(a, srv, emby, item) } }.getOrNull().orEmpty() })
                }
                add(async { runCatching { withTimeoutOrNull(20_000) { providerCopies(item) } }.getOrNull().orEmpty() })
            }
            jobs.awaitAll().flatten()
        // One copy per source, but every Plex server's own: if one server's file has gone, another's can play.
        }.distinctBy { it.source + "|" + (if (it.source == "Plex") it.ref?.substringAfter('@', "") else "") }
        copyCache[item.key] = System.currentTimeMillis() to found
        return found
    }

    /** True when a server's entry is this title: same TMDB or IMDb id, or (no ids to go on) same name and year. */
    private fun sameTitle(item: MediaItem, tmdbId: Int?, imdbId: String?, name: String?, year: Int?): Boolean {
        if (item.tmdbId != null && tmdbId != null) return item.tmdbId == tmdbId
        if (item.imdbId != null && imdbId != null) return item.imdbId == imdbId
        if (name == null || !name.equals(item.title, ignoreCase = true)) return false
        return item.year == null || year == null || kotlin.math.abs(item.year - year) <= 1
    }

    /** Names to ask for: the title, and its part before a colon ("Batman: Year One" is "Batman Year One" on some servers). */
    private fun searchNames(title: String): List<String> {
        // Also without accents: "Filipiñana" may be stored as "Filipinana".
        val plain = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return listOf(title, plain, title.substringBefore(':').trim()).filter { it.length >= 2 }.distinct()
    }

    private fun plexCopy(p: PlexAuth, j: JsonElement?): LibraryCopy {
        val res = j.array("Media").firstOrNull()?.str("videoResolution")?.let { if (it.all(Char::isDigit)) "${it}p" else it.uppercase() }
        val detail = listOfNotNull(res, p.serverName.takeIf { !isMainPlex(p) }).joinToString(" · ").ifEmpty { null }
        return LibraryCopy("Plex", detail, j.str("ratingKey")?.let { plexRef(p, it) })
    }

    private suspend fun plexCopies(a: Accounts, p: PlexAuth, item: MediaItem): List<LibraryCopy> {
        // A title from a Plex row already knows where it is.
        if (item.key.startsWith("plex:")) {
            val (server, rk) = plexResolve(a, item.key.removePrefix("plex:")) ?: return emptyList()
            if (server.machineId != p.machineId) return emptyList()
            val m = plexGet(a, p, "/library/metadata/$rk")?.array("Metadata")?.firstOrNull() ?: return listOf(LibraryCopy("Plex", null, plexRef(p, rk)))
            return listOf(plexCopy(p, m))
        }
        val sections = (plexSectionCache[p.machineId]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.second
            ?: (plexGet(a, p, "/library/sections")?.array("Directory").orEmpty().also { plexSectionCache[p.machineId] = System.currentTimeMillis() to it }))
            .filter { it.str("type") == if (item.type == MediaType.Movie) "movie" else "show" }
            .mapNotNull { it.str("key") }
        for (name in searchNames(item.title)) {
            val hits = coroutineScope {
                sections.map { key ->
                    async {
                        runCatching { plexGet(a, p, "/library/sections/$key/all?title=${enc(name)}&includeGuids=1&X-Plex-Container-Start=0&X-Plex-Container-Size=30") }
                            .getOrNull()?.array("Metadata").orEmpty()
                    }
                }.awaitAll().flatten()
            }
            val match = hits.firstOrNull { j -> val (t, i) = guidIds(j); sameTitle(item, t, i, j.str("title"), j.int("year")) }
            if (match != null) return listOf(plexCopy(p, match))
        }
        // Plex's own search is looser (accents, punctuation, alternative titles): last try, matched on the ids.
        val want = if (item.type == MediaType.Movie) "movie" else "show"
        val hubs = runCatching { plexGet(a, p, "/hubs/search?query=${enc(item.title)}&limit=20&includeGuids=1") }.getOrNull()?.array("Hub").orEmpty()
        // Only real library items: Plex's search also returns online "Discover" titles that aren't on the server.
        val loose = hubs.filter { it.str("type") == want }.flatMap { it.array("Metadata") }
            .filter { j -> j.str("librarySectionID") != null || j.int("librarySectionID") != null }
            .firstOrNull { j -> val (t, i) = guidIds(j); sameTitle(item, t, i, j.str("title"), j.int("year")) }
            ?: return emptyList()
        // Search results carry less detail (no resolution): read the item itself.
        val full = loose.str("ratingKey")?.let { rk -> runCatching { plexGet(a, p, "/library/metadata/$rk") }.getOrNull()?.array("Metadata")?.firstOrNull() }
            ?: return emptyList()
        // And only when it has a file to play.
        if (full.array("Media").firstOrNull()?.array("Part").isNullOrEmpty()) return emptyList()
        return listOf(plexCopy(p, full))
    }

    private suspend fun serverCopies(a: Accounts, srv: MediaServerAuth, emby: Boolean, item: MediaItem): List<LibraryCopy> {
        val source = if (emby) "Emby" else "Jellyfin"
        val prefix = source.lowercase() + ":"
        if (item.key.startsWith(prefix)) return listOf(LibraryCopy(source, null, item.key.removePrefix(prefix)))
        val kind = if (item.type == MediaType.Movie) "Movie" else "Series"
        for (name in searchNames(item.title)) {
            val r = runCatching {
                http.get(
                    "${srv.serverUrl}/Users/${srv.userId}/Items?Recursive=true&IncludeItemTypes=$kind&SearchTerm=${enc(name)}&Fields=ProviderIds,ProductionYear&EnableImages=false&Limit=30",
                    mediaServers.headers(emby, a.deviceId, srv.token),
                )
            }.getOrNull()?.takeIf { it.ok } ?: return emptyList()
            val match = r.body.array("Items").firstOrNull { j ->
                sameTitle(item, j.child("ProviderIds").str("Tmdb")?.toIntOrNull(), j.child("ProviderIds").str("Imdb"), j.str("Name"), j.int("ProductionYear"))
            }
            if (match != null) return listOfNotNull(match.str("Id")?.let { LibraryCopy(source, null, it) })
        }
        return emptyList()
    }

    /** The IPTV provider's films / series (one list, fetched every few hours and kept in memory). */
    private suspend fun providerCopies(item: MediaItem): List<LibraryCopy> {
        if (dispatcharrVod() != null) {
            // Opened from the IPTV library: its id is in the key. Otherwise look the title up by name, then match TMDB.
            val direct = item.key.takeIf { it.startsWith("xc:") }?.substringAfterLast(':')?.toIntOrNull()
            val id = direct ?: run {
                val hits = dvodGet(dvodPath(item.type), mapOf("name" to item.title, "page_size" to "50"))?.array("results").orEmpty()
                hits.firstOrNull { h -> item.tmdbId != null && (h.str("tmdb_id")?.toIntOrNull() ?: h.int("tmdb_id")) == item.tmdbId }?.int("id")
                    ?: hits.firstOrNull { h ->
                        io.github.petebutty.viewarr.data.xtream.XtreamClient.cleanTitle(h.str("name").orEmpty()).equals(item.title, ignoreCase = true) &&
                            (item.year == null || h.int("year") == null || h.int("year") == item.year)
                    }?.int("id")
            } ?: return emptyList()
            return listOf(if (item.type == MediaType.Movie) LibraryCopy(PROVIDER, null, "movie:$id:mp4") else LibraryCopy(PROVIDER, null, "series:$id"))
        }
        val s = xcSession() ?: return emptyList()
        val (films, series) = xcCatalog(s)
        return if (item.type == MediaType.Movie) {
            films.firstOrNull { v -> xcKey(MediaType.Movie, v.streamId) == item.key || (item.tmdbId != null && v.tmdbId == item.tmdbId) }
                ?.let { v -> listOf(LibraryCopy(PROVIDER, v.extension.uppercase(), "movie:${v.streamId}:${v.extension}")) }.orEmpty()
        } else {
            series.firstOrNull { v -> xcKey(MediaType.Show, v.seriesId) == item.key || (item.tmdbId != null && v.tmdbId == item.tmdbId) }
                ?.let { v -> listOf(LibraryCopy(PROVIDER, null, "series:${v.seriesId}")) }.orEmpty()
        }
    }

    // ------------------------------------------------------------------ playback sources

    /**
     * A Plex item to play: the file itself when this TV can decode it, with Plex transcoding (HLS) as the fallback.
     * Files the TV can't handle at all (e.g. AVI, VC-1 on some boxes) go straight to transcoding.
     */
    suspend fun plexStream(ref: String, watch: WatchTarget? = null): VodStream? {
        val a = store.current()
        val (p, ratingKey) = plexResolve(a, ref) ?: return null
        // checkFiles: Plex says whether the file is really there (a library entry can outlive its file).
        val m = plexGet(a, p, "/library/metadata/$ratingKey?checkFiles=1")?.array("Metadata")?.firstOrNull() ?: return null
        val media = m.array("Media").firstOrNull()
        val partObj = media?.array("Part")?.firstOrNull()
        val part = partObj?.str("key") ?: return null
        val exists = (partObj.child("exists") as? JsonPrimitive)?.content
        if (exists == "0" || exists == "false") {
            Log.w(TAG, "plex: ${p.serverName} lists ${m.str("title")} but its file is missing")
            return null
        }
        // Plex's own previews, if "Generate video preview thumbnails" made them for this file.
        val thumbs = partObj.str("id")?.takeIf { partObj.str("indexes")?.contains("sd") == true }
            ?.let { ThumbSource.Bif("${p.serverUrl}/library/parts/$it/indexes/sd?X-Plex-Token=${enc(p.token)}") }
        val title = m.str("title") ?: "Plex"
        val headers = plex.headers(a.deviceId, p.token)
        val resume = m.long("viewOffset") ?: 0
        val target = (watch ?: WatchTarget(MediaType.Movie)).copy(plexRatingKey = ref)
        val quality = playbackStore.current().plexQuality
        val transcode = VodStream(plexTranscodeUrl(a, p, ratingKey, quality.maxKbps ?: 20_000), "$title (converted by Plex)", headers, resume, watch = target, thumbs = thumbs)
        // Settings → Playback → Plex quality: anything but Original always converts at that bitrate.
        if (quality.maxKbps != null) return transcode
        val direct = VodStream("${p.serverUrl}$part?X-Plex-Token=${enc(p.token)}", title, headers, resume, fallback = transcode, watch = target, thumbs = thumbs)
        val playable = DeviceCodecs.canPlay(media.str("videoCodec"), media.str("container"))
        Log.i(TAG, "plex $ratingKey video=${media.str("videoCodec")} container=${media.str("container")} direct=$playable")
        return if (playable) direct else transcode
    }

    private fun plexTranscodeUrl(a: Accounts, p: PlexAuth, ratingKey: String, maxKbps: Int): String {
        val q = linkedMapOf(
            "path" to "/library/metadata/$ratingKey",
            "mediaIndex" to "0", "partIndex" to "0",
            "protocol" to "hls", "fastSeek" to "1",
            "directPlay" to "0", "directStream" to "1", "directStreamAudio" to "1",
            "videoQuality" to "100", "maxVideoBitrate" to maxKbps.toString(), "videoResolution" to "1920x1080",
            "subtitles" to "auto", "location" to "lan",
            "session" to java.util.UUID.randomUUID().toString(),
            "X-Plex-Token" to p.token,
            "X-Plex-Client-Identifier" to a.deviceId,
            "X-Plex-Product" to "Viewarr", "X-Plex-Platform" to "Android", "X-Plex-Device" to "Android TV",
        )
        return "${p.serverUrl}/video/:/transcode/universal/start.m3u8?" + q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    // ------------------------------------------------------------------ watch progress

    // ------------------------------------------------------------------ Jellyfin / Emby playback

    private suspend fun serverFor(source: String): Pair<MediaServerAuth, Boolean>? {
        val a = store.current()
        return when (source) {
            "Jellyfin" -> a.jellyfin?.let { it to false }
            "Emby" -> a.emby?.let { it to true }
            else -> null
        }
    }

    /** A Jellyfin / Emby film or episode: the file itself (mpv plays almost anything), resuming where you left off. */
    /** True when this copy's server is on the home network (tried first: quicker, and no internet upload needed). */
    suspend fun isHomeCopy(copy: LibraryCopy): Boolean {
        val a = store.current()
        val url = when (copy.source) {
            "Plex" -> copy.ref?.let { plexResolve(a, it)?.first?.serverUrl }
            "Jellyfin" -> a.jellyfin?.serverUrl
            "Emby" -> a.emby?.serverUrl
            else -> null
        } ?: return false
        return isHomeAddress(url)
    }

    suspend fun serverStream(source: String, itemId: String, title: String, watch: WatchTarget?): VodStream? {
        val (s, emby) = serverFor(source) ?: return null
        val a = store.current()
        val info = runCatching { http.get("${s.serverUrl}/Users/${s.userId}/Items/$itemId", mediaServers.headers(emby, a.deviceId, s.token)) }
            .getOrNull()?.takeIf { it.ok }?.body
        val resumeMs = (info.child("UserData").long("PlaybackPositionTicks") ?: 0L) / 10_000
        return VodStream(
            thumbs = serverThumbs(s, emby, itemId, info),
            url = "${s.serverUrl}/Videos/$itemId/stream?static=true&api_key=${enc(s.token)}",
            title = title,
            resumeMs = resumeMs,
            watch = (watch ?: WatchTarget(MediaType.Movie)).copy(serverSource = source, serverItemId = itemId),
        )
    }

    /**
     * Seek-bar previews from the server. Jellyfin: trickplay sheets listed on the item (smallest width).
     * Emby: its BIF index, if it made one (a missing one just falls back to the box's own pictures).
     */
    private fun serverThumbs(s: MediaServerAuth, emby: Boolean, itemId: String, info: kotlinx.serialization.json.JsonElement?): ThumbSource? {
        val key = "api_key=${enc(s.token)}"
        if (emby) return ThumbSource.Bif("${s.serverUrl}/Videos/$itemId/index.bif?width=320&$key")
        val tp = info.child("Trickplay").obj() ?: return null
        val sourceId = tp.keys.firstOrNull() ?: return null
        val byWidth = tp[sourceId].obj() ?: return null
        val w = byWidth.keys.minByOrNull { it.toIntOrNull() ?: Int.MAX_VALUE } ?: return null
        val t = byWidth[w]
        return ThumbSource.Trickplay(
            urlPattern = "${s.serverUrl}/Videos/$itemId/Trickplay/$w/{n}.jpg?MediaSourceId=${enc(sourceId)}&$key",
            width = t.int("Width") ?: 0, height = t.int("Height") ?: 0,
            tileWidth = t.int("TileWidth") ?: 10, tileHeight = t.int("TileHeight") ?: 10,
            count = t.int("ThumbnailCount") ?: 0, intervalMs = t.long("Interval") ?: 10_000,
        ).takeIf { it.width > 0 && it.height > 0 && it.tileWidth > 0 && it.tileHeight > 0 && it.count > 0 && it.intervalMs > 0 }
    }

    /** A Jellyfin / Emby series' episodes as (season, episode) → id, resume point and watched. */
    suspend fun serverEpisodes(source: String, seriesId: String): Map<Pair<Int, Int>, PlexEpisode> {
        val (s, emby) = serverFor(source) ?: return emptyMap()
        val a = store.current()
        val r = runCatching {
            http.get("${s.serverUrl}/Shows/$seriesId/Episodes?UserId=${s.userId}&Fields=UserData", mediaServers.headers(emby, a.deviceId, s.token))
        }.getOrNull()?.takeIf { it.ok } ?: return emptyMap()
        return r.body.array("Items").mapNotNull { e ->
            val season = e.int("ParentIndexNumber") ?: return@mapNotNull null
            val number = e.int("IndexNumber") ?: return@mapNotNull null
            val id = e.str("Id") ?: return@mapNotNull null
            val ud = e.child("UserData")
            (season to number) to PlexEpisode(id, (ud.long("PlaybackPositionTicks") ?: 0L) / 10_000, ud.str("Played") == "true")
        }.toMap()
    }

    /** Tells Jellyfin / Emby what's playing and where, so your place is saved and finished items are marked watched. */
    suspend fun reportServer(source: String, itemId: String, state: String, positionMs: Long) {
        val (s, emby) = serverFor(source) ?: return
        val a = store.current()
        val path = when (state) { "stopped" -> "/Sessions/Playing/Stopped"; "playing", "paused" -> "/Sessions/Playing/Progress"; else -> return }
        val body = buildJsonObject {
            put("ItemId", itemId)
            put("PositionTicks", positionMs * 10_000)
            put("IsPaused", state == "paused")
            put("CanSeek", true)
        }
        runCatching { http.post(s.serverUrl + path, body, mediaServers.headers(emby, a.deviceId, s.token)) }
    }

    /** Tells Plex where you are (so Continue watching and "watched" stay in sync); marks it watched near the end. */
    suspend fun reportPlex(ref: String, state: String, positionMs: Long, durationMs: Long) {
        val a = store.current()
        val (p, ratingKey) = plexResolve(a, ref) ?: return
        runCatching {
            http.get("${p.serverUrl}/:/timeline?ratingKey=$ratingKey&key=${enc("/library/metadata/$ratingKey")}&state=$state&time=$positionMs&duration=$durationMs",
                plex.headers(a.deviceId, p.token))
            if (state == "stopped" && durationMs > 0 && positionMs >= durationMs * 0.9) {
                http.get("${p.serverUrl}/:/scrobble?identifier=com.plexapp.plugins.library&key=$ratingKey", plex.headers(a.deviceId, p.token))
            }
        }.onFailure { Log.w(TAG, "plex timeline failed: ${it.message}") }
    }

    /** Trakt scrobble: start / pause / stop with % watched. Trakt marks it watched when stopped past 80%. */
    suspend fun scrobbleTrakt(action: String, target: WatchTarget, progress: Float) {
        val auth = store.current().trakt?.let { freshTrakt(it) } ?: return
        val tmdbId = target.tmdbId ?: return
        val body = buildJsonObject {
            if (target.type == MediaType.Movie) putJsonObject("movie") { putJsonObject("ids") { put("tmdb", tmdbId) } }
            else {
                val s = target.season ?: return
                val e = target.episode ?: return
                putJsonObject("show") { putJsonObject("ids") { put("tmdb", tmdbId) } }
                putJsonObject("episode") { put("season", s); put("number", e) }
            }
            put("progress", (progress * 100).coerceIn(0f, 100f).toDouble())
        }
        runCatching { http.post("https://api.trakt.tv/scrobble/$action", body, trakt.headers(auth.accessToken)) }
            .onFailure { Log.w(TAG, "trakt scrobble failed: ${it.message}") }
    }

    // ------------------------------------------------------------------ episodes

    private val plexEpisodeCache = ConcurrentHashMap<String, Map<Pair<Int, Int>, PlexEpisode>>()

    /** Season → episodes from TMDB; falls back to the Plex copy's episodes when TMDB has nothing. */
    suspend fun seasonEpisodes(item: MediaItem, season: Int, plexShowKey: String?): List<EpisodeInfo> {
        val key = store.current().tmdbKey
        val tmdbId = item.tmdbId
        if (key != null && tmdbId != null) {
            val r = runCatching { http.get(tmdb.url("/tv/$tmdbId/season/$season", key, mapOf("language" to "en-GB")), tmdb.headers(key)) }.getOrNull()
            val eps = r?.takeIf { it.ok }?.body.array("episodes").mapNotNull { e ->
                val n = e.int("episode_number") ?: return@mapNotNull null
                EpisodeInfo(season, n, e.str("name") ?: "Episode $n", e.str("overview")?.takeIf { it.isNotBlank() },
                    e.str("still_path")?.let { TMDB_IMG + "w300" + it }, e.str("air_date"), e.int("runtime"))
            }
            if (eps.isNotEmpty()) return eps
        }
        val a = store.current()
        val (p, showKey) = plexShowKey?.let { plexResolve(a, it) } ?: return emptyList()
        return plexGet(a, p, "/library/metadata/$showKey/allLeaves")?.array("Metadata").orEmpty()
            .filter { it.int("parentIndex") == season }
            .mapNotNull { e ->
                val n = e.int("index") ?: return@mapNotNull null
                EpisodeInfo(season, n, e.str("title") ?: "Episode $n", e.str("summary")?.takeIf { it.isNotBlank() },
                    plexImage(p, e.str("thumb"), 400, 225), e.str("originallyAvailableAt"), e.long("duration")?.let { (it / 60_000).toInt() })
            }
    }

    /** Seasons from Plex when TMDB has none (no key, or an unmatched show). */
    suspend fun plexSeasons(showRef: String): List<SeasonInfo> {
        val a = store.current()
        val (p, showKey) = plexResolve(a, showRef) ?: return emptyList()
        return plexGet(a, p, "/library/metadata/$showKey/children")?.array("Metadata").orEmpty().mapNotNull { s ->
            val n = s.int("index") ?: return@mapNotNull null
            if (n == 0) null else SeasonInfo(n, s.str("title") ?: "Season $n", s.int("leafCount") ?: 0, s.int("year"))
        }
    }

    /** Every episode of a show in Plex, by (season, episode). Cached per show for this run. */
    suspend fun plexEpisodes(showRef: String): Map<Pair<Int, Int>, PlexEpisode> {
        plexEpisodeCache[showRef]?.let { return it }
        val a = store.current()
        val (p, showKey) = plexResolve(a, showRef) ?: return emptyMap()
        val map = plexGet(a, p, "/library/metadata/$showKey/allLeaves")?.array("Metadata").orEmpty().mapNotNull { e ->
            val s = e.int("parentIndex") ?: return@mapNotNull null
            val n = e.int("index") ?: return@mapNotNull null
            val rk = e.str("ratingKey") ?: return@mapNotNull null
            (s to n) to PlexEpisode(plexRef(p, rk), e.long("viewOffset") ?: 0, (e.int("viewCount") ?: 0) > 0)
        }.toMap()
        plexEpisodeCache[showRef] = map
        return map
    }

    /** Asks every connected Stremio addon for streams of a film, or of one episode. Only direct http(s) links can be played. */
    suspend fun addonStreams(item: MediaItem, season: Int? = null, episode: Int? = null): List<StreamOption> {
        val baseImdb = item.imdbId ?: return emptyList()
        val type = if (item.type == MediaType.Movie) "movie" else "series"
        val imdb = if (season != null && episode != null) "$baseImdb:$season:$episode" else baseImdb
        val addons = store.current().activeAddons
        return coroutineScope {
            addons.map { addon ->
                async {
                    val r = runCatching { withTimeoutOrNull(30_000) { http.get("${addon.baseUrl}/stream/$type/$imdb.json") } }.getOrNull()
                    if (r?.ok != true) return@async emptyList()
                    r.body.array("streams").mapNotNull { st ->
                        val url = st.str("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                        val headers = st.child("behaviorHints").child("proxyHeaders").child("request").let { h ->
                            (h as? kotlinx.serialization.json.JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
                        }
                        StreamOption(
                            addon = addon.name,
                            name = st.str("name")?.replace('\n', ' ')?.trim() ?: addon.name,
                            description = (st.str("description") ?: st.str("title")).orEmpty().replace('\n', ' ').trim(),
                            url = url,
                            headers = headers,
                        )
                    }
                }
            }.awaitAll().flatten()
        }
    }

    fun hasAddons(a: Accounts) = a.activeAddons.isNotEmpty()

    // ------------------------------------------------------------------ detail

    /** Full TMDB details for the detail page; falls back to what the row already had. */
    suspend fun detail(item: MediaItem): MediaDetail {
        val key = store.current().tmdbKey ?: return MediaDetail(item)
        val tmdbId = item.tmdbId ?: item.imdbId?.let { findByImdb(key, it, item.type) } ?: return MediaDetail(item)
        val movie = item.type == MediaType.Movie
        val path = if (movie) "/movie/$tmdbId" else "/tv/$tmdbId"
        val append = if (movie) "credits,release_dates,external_ids,reviews,recommendations" else "credits,content_ratings,external_ids,reviews,recommendations"
        val r = runCatching { http.get(tmdb.url(path, key, mapOf("language" to "en-GB", "append_to_response" to append)), tmdb.headers(key)) }
            .getOrNull()?.takeIf { it.ok } ?: return MediaDetail(item)
        val j = r.body
        val full = tmdbItem(j, item.type)
        val merged = item.copy(
            tmdbId = tmdbId,
            imdbId = item.imdbId ?: full?.imdbId,
            poster = item.poster ?: full?.poster,
            backdrop = full?.backdrop ?: item.backdrop,
            overview = full?.overview ?: item.overview,
            year = item.year ?: full?.year,
            rating = full?.rating ?: item.rating,
        )
        val cert = if (movie) {
            j.child("release_dates").array("results").firstOrNull { it.str("iso_3166_1") == "GB" }
                ?.array("release_dates")?.mapNotNull { it.str("certification")?.takeIf(String::isNotBlank) }?.firstOrNull()
        } else {
            j.child("content_ratings").array("results").firstOrNull { it.str("iso_3166_1") == "GB" }?.str("rating")?.takeIf { it.isNotBlank() }
        }
        return MediaDetail(
            item = merged,
            tagline = j.str("tagline")?.takeIf { it.isNotBlank() },
            genres = j.array("genres").mapNotNull { it.str("name") },
            runtimeMin = j.int("runtime") ?: j.array("episode_run_time").firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() },
            certification = cert,
            cast = j.child("credits").array("cast").take(20).mapNotNull { c ->
                c.str("name")?.let { CastMember(it, c.str("character")?.takeIf { ch -> ch.isNotBlank() }, c.str("profile_path")?.let { p -> "https://image.tmdb.org/t/p/w185$p" }, c.int("id")) }
            },
            seasons = j.array("seasons").mapNotNull { s ->
                val n = s.int("season_number") ?: return@mapNotNull null
                if (n == 0) return@mapNotNull null
                SeasonInfo(n, s.str("name") ?: "Season $n", s.int("episode_count") ?: 0, s.str("air_date")?.take(4)?.toIntOrNull())
            },
            status = j.str("status"),
            directors = if (movie) j.child("credits").array("crew").filter { it.str("job") == "Director" }.mapNotNull { it.str("name") }.distinct()
                else j.array("created_by").mapNotNull { it.str("name") },
            writers = j.child("credits").array("crew").filter { it.str("department") == "Writing" }.mapNotNull { it.str("name") }.distinct().take(4),
            studio = (if (movie) j.array("production_companies") else j.array("networks")).firstOrNull()?.str("name"),
            released = (j.str("release_date") ?: j.str("first_air_date"))?.takeIf { it.isNotBlank() }?.let { d ->
                runCatching { java.time.LocalDate.parse(d).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")) }.getOrDefault(d)
            },
            language = j.array("spoken_languages").firstOrNull()?.let { it.str("english_name") ?: it.str("name") },
            budget = j.long("budget")?.takeIf { it > 0 },
            revenue = j.long("revenue")?.takeIf { it > 0 },
            voteCount = j.int("vote_count"),
            reviews = j.child("reviews").array("results").take(10).mapNotNull { r ->
                val text = r.str("content")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Review(
                    author = r.str("author") ?: "Someone",
                    rating = (r.child("author_details").child("rating") as? JsonPrimitive)?.doubleOrNull,
                    text = text,
                    date = r.str("created_at")?.take(10)?.let { d -> runCatching { java.time.LocalDate.parse(d).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrNull() },
                )
            },
            similar = j.child("recommendations").array("results").mapNotNull { tmdbItem(it, item.type) }.filter { it.poster != null }.take(20),
        )
    }

    /**
     * Ratings from more places: IMDb and Rotten Tomatoes (critics and audience) as your Plex server has them, and
     * Trakt's. TMDB's own is on the detail already.
     */
    private val ratingsCache = ConcurrentHashMap<String, Pair<Long, List<RatingBadge>>>()

    suspend fun extraRatings(item: MediaItem, copies: List<LibraryCopy>): List<RatingBadge> {
        ratingsCache[item.key]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return it.second }
        val got = fetchExtraRatings(item, copies)
        ratingsCache[item.key] = System.currentTimeMillis() to got
        return got
    }

    /**
     * MDBList (with the user's key): IMDb, Rotten Tomatoes critics and audience, Metacritic, Letterboxd and Trakt for
     * any title, by its IMDb or TMDB id. Null without a key or an answer.
     */
    private val mdbCache = ConcurrentHashMap<String, List<RatingBadge>>()

    private suspend fun mdblistRatings(a: Accounts, item: MediaItem): List<RatingBadge>? {
        val key = a.mdblistKey ?: return null
        mdbCache[item.key]?.let { return it.ifEmpty { null } }
        val got = fetchMdblist(key, item)
        mdbCache[item.key] = got.orEmpty()
        return got
    }

    private suspend fun fetchMdblist(key: String, item: MediaItem): List<RatingBadge>? {
        val kind = if (item.type == MediaType.Movie) "movie" else "show"
        val (provider, id) = item.imdbId?.let { "imdb" to it } ?: item.tmdbId?.let { "tmdb" to it.toString() } ?: return null
        val r = runCatching { http.get("https://api.mdblist.com/$provider/$kind/$id?apikey=${enc(key)}") }.getOrNull()?.takeIf { it.ok } ?: return null
        val values = r.body.array("ratings").mapNotNull { x ->
            val v = (x.child("value") as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val votes = x.int("votes")?.takeIf { it > 0 }?.let(::compactCount)
            when (x.str("source")) {
                "imdb" -> "imdb" to RatingBadge("IMDb", "%.1f".format(v), votes)
                "tomatoes" -> "rt" to RatingBadge("RT", "🍅 ${v.toInt()}%")
                "popcorn", "audience", "tomatoesaudience" -> "rta" to RatingBadge("Audience", "🍿 ${v.toInt()}%")
                "metacritic" -> "mc" to RatingBadge("Metacritic", v.toInt().toString())
                "letterboxd" -> "lb" to RatingBadge("Letterboxd", "%.1f".format(if (v > 5) v / 20 else v))
                "trakt" -> "trakt" to RatingBadge("Trakt", "${v.toInt()}%", votes)
                else -> null
            }
        }.toMap()
        return listOfNotNull(values["imdb"], values["rt"], values["rta"], values["mc"], values["lb"], values["trakt"]).takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchExtraRatings(item: MediaItem, copies: List<LibraryCopy>): List<RatingBadge> = coroutineScope {
        val a = store.current()
        // With an MDBList key: everything in one call; Plex and Trakt only fill in what it lacks.
        mdblistRatings(a, item)?.let { fromMdb ->
            if (fromMdb.any { it.source == "RT" }) return@coroutineScope fromMdb
        }
        val plexPart = async {
            val ref = copies.firstOrNull { it.source == "Plex" }?.ref ?: return@async emptyList()
            val (p, rk) = plexResolve(a, ref) ?: return@async emptyList()
            val m = runCatching { plexGet(a, p, "/library/metadata/$rk")?.array("Metadata")?.firstOrNull() }.getOrNull() ?: return@async emptyList()
            plexRatings(m)
        }
        val traktPart = async {
            val imdb = item.imdbId ?: return@async null
            if (TraktService.CLIENT_ID.isBlank()) return@async null
            val r = runCatching { http.get("https://api.trakt.tv/${if (item.type == MediaType.Movie) "movies" else "shows"}/$imdb/ratings", trakt.headers()) }
                .getOrNull()?.takeIf { it.ok } ?: return@async null
            val v = (r.body.child("rating") as? JsonPrimitive)?.doubleOrNull ?: return@async null
            val votes = r.body.int("votes") ?: 0
            if (votes < 5) null else RatingBadge("Trakt", "${(v * 10).toInt()}%", compactCount(votes))
        }
        val mdb = mdblistRatings(a, item).orEmpty()
        (mdb + plexPart.await() + listOfNotNull(traktPart.await())).distinctBy { it.source }
    }

    /** Trakt users' comments on a title (most liked first), as reviews. Spoilers are flagged. */
    suspend fun traktReviews(item: MediaItem): List<Review> {
        val imdb = item.imdbId ?: return emptyList()
        if (TraktService.CLIENT_ID.isBlank()) return emptyList()
        val kind = if (item.type == MediaType.Movie) "movies" else "shows"
        val r = runCatching { http.get("https://api.trakt.tv/$kind/$imdb/comments/likes?limit=15", trakt.headers()) }.getOrNull()?.takeIf { it.ok } ?: return emptyList()
        return (r.body as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { c ->
            val text = c.str("comment")?.trim()?.takeIf { it.length >= 40 } ?: return@mapNotNull null
            Review(
                author = c.child("user").str("username") ?: "Trakt user",
                rating = (c.child("user_rating") as? JsonPrimitive)?.doubleOrNull,
                text = text,
                date = c.str("created_at")?.take(10)?.let { d -> runCatching { java.time.LocalDate.parse(d).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrNull() },
                source = "Trakt",
                spoiler = c.str("spoiler") == "true",
                likes = c.int("likes") ?: 0,
            )
        }.take(10)
    }

    /** IMDb / Rotten Tomatoes from a Plex item ("rating"/"audienceRating" with their images, or the Rating list). */
    private fun plexRatings(m: JsonElement): List<RatingBadge> {
        val found = linkedMapOf<String, RatingBadge>()
        fun add(image: String?, value: Double?) {
            if (image == null || value == null || value <= 0) return
            when {
                image.startsWith("imdb://") -> found.putIfAbsent("imdb", RatingBadge("IMDb", "%.1f".format(value)))
                image.startsWith("rottentomatoes://image.rating.ripe") || image.startsWith("rottentomatoes://image.rating.rotten") ->
                    found.putIfAbsent("rt", RatingBadge("RT", "🍅 ${(value * 10).toInt()}%"))
                image.startsWith("rottentomatoes://image.rating.upright") || image.startsWith("rottentomatoes://image.rating.spilled") ->
                    found.putIfAbsent("rta", RatingBadge("Audience", "🍿 ${(value * 10).toInt()}%"))
            }
        }
        m.array("Rating").forEach { r -> add(r.str("image"), (r.child("value") as? JsonPrimitive)?.doubleOrNull) }
        add(m.str("ratingImage"), (m.child("rating") as? JsonPrimitive)?.doubleOrNull)
        add(m.str("audienceRatingImage"), (m.child("audienceRating") as? JsonPrimitive)?.doubleOrNull)
        return listOfNotNull(found["imdb"], found["rt"], found["rta"])
    }

    private fun compactCount(n: Int) = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }

    private val trailerCache = ConcurrentHashMap<String, String>()

    /** The YouTube id of the title's trailer on TMDB (official English trailer first, then teasers), or null. */
    suspend fun trailerKey(item: MediaItem): String? {
        trailerCache[item.key]?.let { return it.ifEmpty { null } }
        val key = store.current().tmdbKey ?: return null
        val id = item.tmdbId ?: item.imdbId?.let { findByImdb(key, it, item.type) } ?: return null
        val path = (if (item.type == MediaType.Movie) "/movie/" else "/tv/") + id + "/videos"
        val r = runCatching { http.get(tmdb.url(path, key, mapOf("include_video_language" to "en,null")), tmdb.headers(key)) }
            .getOrNull()?.takeIf { it.ok } ?: return null
        fun score(v: JsonElement) = (if (v.str("type") == "Trailer") 0 else 10) +
            (if (v.str("official") == "true") 0 else 4) + (if (v.str("iso_639_1") == "en") 0 else 2)
        val pick = r.body.array("results")
            .filter { it.str("site") == "YouTube" && !it.str("key").isNullOrBlank() && it.str("type") in setOf("Trailer", "Teaser") }
            .minByOrNull(::score)?.str("key")
        trailerCache[item.key] = pick.orEmpty()
        return pick
    }

    // ------------------------------------------------------------------ IPTV provider films & series (Xtream Codes)

    @Volatile private var xcCache: Triple<Long, List<io.github.petebutty.viewarr.data.xtream.XtreamVod>, List<io.github.petebutty.viewarr.data.xtream.XtreamSeries>>? = null
    private val xcLock = Mutex()

    /**
     * The Xtream Codes login for provider films and series: the IPTV provider itself, or, with Dispatcharr and
     * Provider VOD switched on in setup, Dispatcharr's own XC side (player_api.php) with this user's XC password
     * (Dispatcharr → Users → the user's XC password), read once with the API key.
     */
    private suspend fun xcSession(): io.github.petebutty.viewarr.data.session.Session? {
        val s = sessions.session.first() ?: return null
        if (s.isXc) return s
        if (!store.current().vodEnabled) return null
        val id = "${s.serverUrl}|${s.username}|${s.apiKey}"
        dispatcharrXc?.takeIf { it.first == id }?.let { return it.second }
        val r = runCatching { http.get(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/", mapOf("X-API-Key" to s.apiKey)) }.getOrNull()
        if (r?.ok != true) return null
        val pass = r.body.child("custom_properties").str("xc_password")?.takeIf { it.isNotBlank() }
        val xc = pass?.let { s.copy(xcPassword = it) }
        if (xc == null) Log.w(TAG, "provider VOD: Dispatcharr user ${s.username} has no XC password")
        dispatcharrXc = id to xc
        return xc
    }
    @Volatile private var dispatcharrXc: Pair<String, io.github.petebutty.viewarr.data.session.Session?>? = null

    // ---- Provider VOD through Dispatcharr: its own API pages, sorts and searches on the server, so the TV never
    // holds the whole list (20,000+ films froze the box when it was fetched in one go over XC).

    /** The Dispatcharr sign-in, when films and series come from Dispatcharr (Provider VOD on). */
    private suspend fun dispatcharrVod() = sessions.session.first()?.takeIf { !it.isXc && store.current().vodEnabled }

    private suspend fun dvodGet(path: String, params: Map<String, String>): JsonElement? {
        val s = dispatcharrVod() ?: return null
        val url = s.serverUrl.trimEnd('/') + "/api/vod/" + path + "?" + params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        return withTimeoutOrNull(20_000) { runCatching { http.get(url, mapOf("X-API-Key" to s.apiKey)) }.getOrNull() }?.takeIf { it.ok }?.body
    }

    private fun dvodItem(j: JsonElement?, type: MediaType): MediaItem? {
        val id = j.int("id") ?: return null
        val raw = j.str("name") ?: return null
        val tmdbId = j.str("tmdb_id")?.toIntOrNull() ?: j.int("tmdb_id")
        return MediaItem(
            key = tmdbId?.let { "tmdb:${type.name}:$it" } ?: xcKey(type, id),
            type = type,
            title = io.github.petebutty.viewarr.data.xtream.XtreamClient.cleanTitle(raw),
            year = j.int("year") ?: io.github.petebutty.viewarr.data.xtream.XtreamClient.yearIn(raw),
            poster = j.child("logo").str("url"),
            overview = j.str("description")?.takeIf { it.isNotBlank() },
            rating = j.str("rating")?.toDoubleOrNull(),
            tmdbId = tmdbId,
            origin = "IPTV",
        )
    }

    private fun dvodPath(type: MediaType) = if (type == MediaType.Movie) "movies/" else "series/"

    /** One page from Dispatcharr's VOD API: the titles and how many there are in all. */
    private suspend fun dvodPage(type: MediaType, params: Map<String, String>): Pair<List<MediaItem>, Int>? {
        val body = dvodGet(dvodPath(type), params) ?: return null
        return body.array("results").mapNotNull { dvodItem(it, type) } to (body.int("count") ?: 0)
    }

    /** Sort orders Dispatcharr's API knows (name, year, created_at); rating isn't one, so newest added stands in. */
    private fun dvodOrder(sort: LibrarySort): Map<String, String> = when (sort) {
        LibrarySort.Title -> mapOf("ordering" to "name")
        LibrarySort.Added, LibrarySort.Rating -> mapOf("ordering" to "-created_at")
        // Titles with no year would sort first: leave them out of this order.
        LibrarySort.Released -> mapOf("ordering" to "-year", "year_lte" to (java.time.Year.now().value + 1).toString())
    }

    private fun providerFilm(v: io.github.petebutty.viewarr.data.xtream.XtreamVod) = MediaItem(
        key = v.tmdbId?.let { "tmdb:Movie:$it" } ?: xcKey(MediaType.Movie, v.streamId),
        type = MediaType.Movie, title = v.name, year = v.year, poster = v.poster, rating = v.rating, tmdbId = v.tmdbId,
        origin = "IPTV",
    )

    private fun providerSeries(v: io.github.petebutty.viewarr.data.xtream.XtreamSeries) = MediaItem(
        key = v.tmdbId?.let { "tmdb:Show:$it" } ?: xcKey(MediaType.Show, v.seriesId),
        type = MediaType.Show, title = v.name, year = v.year, poster = v.poster, backdrop = v.backdrop, overview = v.plot,
        rating = v.rating, tmdbId = v.tmdbId, origin = "IPTV",
    )

    /** One page of the provider's whole film or series list (menu → Movies / Shows → Your provider). */
    private suspend fun providerPage(type: MediaType, sort: LibrarySort, start: Int, size: Int, q: String, category: String?): Pair<List<MediaItem>, Int>? {
        if (dispatcharrVod() != null) {
            val params = dvodOrder(sort) + mapOf("page" to (start / size + 1).toString(), "page_size" to size.toString()) +
                (if (q.isNotEmpty()) mapOf("name" to q) else emptyMap()) + (category?.let { mapOf("category" to it) } ?: emptyMap())
            val (items, count) = dvodPage(type, params) ?: return null
            return enrich(store.current(), items) to count
        }
        val s = xcSession() ?: return null
        val (films, series) = xcCatalog(s)
        data class Row(val item: MediaItem, val added: Long, val cat: String?)
        val all = if (type == MediaType.Movie) films.map { Row(providerFilm(it), it.added, it.categoryId) }
        else series.map { Row(providerSeries(it), it.lastModified, it.categoryId) }
        val picked = all.asSequence()
            .filter { category == null || it.cat == category }
            .filter { q.isEmpty() || it.item.title.contains(q, ignoreCase = true) }
            .distinctBy { it.item.key }
            .toList()
        val sorted = when (sort) {
            LibrarySort.Title -> picked.sortedBy { it.item.title.lowercase().removePrefix("the ") }
            LibrarySort.Added -> picked.sortedByDescending { it.added }
            LibrarySort.Released -> picked.sortedWith(compareByDescending<Row> { it.item.year ?: 0 }.thenByDescending { it.added })
            LibrarySort.Rating -> picked.sortedByDescending { it.item.rating ?: 0.0 }
        }
        val page = sorted.drop(start).take(size).map { it.item }
        return enrich(store.current(), page) to sorted.size
    }

    private fun xcKey(type: MediaType, id: Int) = "xc:${type.name}:$id"

    /** The provider's whole film and series lists, fetched once every few hours (they can be large). */
    private suspend fun xcCatalog(s: io.github.petebutty.viewarr.data.session.Session) = xcLock.withLock {
        xcCache?.takeIf { System.currentTimeMillis() - it.first < 6 * 3_600_000L }?.let { it.second to it.third }
            ?: run {
                val films = runCatching { xtream.vodStreams(s) }.getOrDefault(emptyList())
                val series = runCatching { xtream.series(s) }.getOrDefault(emptyList())
                // A failed fetch (provider slow or down) isn't kept, so the next page load tries again.
                if (films.isNotEmpty() || series.isNotEmpty()) xcCache = Triple(System.currentTimeMillis(), films, series)
                films to series
            }
    }

    private suspend fun providerRows(a: Accounts, type: MediaType): List<MediaRow> {
        if (dispatcharrVod() != null) {
            val what = if (type == MediaType.Movie) "IPTV movies" else "IPTV shows"
            return coroutineScope {
                val newest = async { dvodPage(type, mapOf("ordering" to "-created_at", "page_size" to "40"))?.first.orEmpty() }
                val latest = async { dvodPage(type, dvodOrder(LibrarySort.Released) + mapOf("page_size" to "40"))?.first.orEmpty() }
                listOf(
                    MediaRow("xc-new-${type.name}", "New $what", enrich(a, newest.await())),
                    MediaRow("xc-top-${type.name}", "Latest $what releases", enrich(a, latest.await())),
                ).filter { it.items.isNotEmpty() }
            }
        }
        val s = xcSession() ?: return emptyList()
        val (films, series) = xcCatalog(s)
        val items: List<Pair<MediaItem, Long>> = if (type == MediaType.Movie) films.map { v -> providerFilm(v) to v.added }
        else series.map { v -> providerSeries(v) to v.lastModified }
        if (items.isEmpty()) return emptyList()
        val newest = enrich(a, items.sortedByDescending { it.second }.map { it.first }.distinctBy { it.key }.take(40))
        val best = enrich(a, items.map { it.first }.filter { it.rating != null }.sortedByDescending { it.rating }.distinctBy { it.key }.take(40))
        val what = if (type == MediaType.Movie) "IPTV movies" else "IPTV shows"
        return listOf(
            MediaRow("xc-new-${type.name}", "New $what", newest),
            MediaRow("xc-top-${type.name}", "Top-rated $what", best),
        )
    }

    /** A provider film, from its library copy ("movie:<id>:<ext>"). */
    suspend fun providerMovie(ref: String, title: String, watch: WatchTarget?): VodStream? {
        val s = xcSession() ?: return null
        val parts = ref.split(':')
        val id = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return VodStream(url = xtream.movieUrl(s, id, parts.getOrNull(2) ?: "mp4"), title = title, watch = watch, boxPreviews = false)
    }

    /** A provider series' episodes as (season, episode) → stream address, from its library copy ("series:<id>"). */
    suspend fun providerEpisodes(ref: String): Map<Pair<Int, Int>, String> {
        val s = xcSession() ?: return emptyMap()
        val id = ref.removePrefix("series:").toIntOrNull() ?: return emptyMap()
        return runCatching { xtream.episodes(s, id) }.getOrDefault(emptyList())
            .associate { (it.season to it.episode) to xtream.episodeUrl(s, it) }
    }

    private val logoCache = ConcurrentHashMap<String, String>()

    /**
     * The title's logo (its name as artwork, transparent) from TMDB: English first, then one without text language,
     * then any; the best voted. Null when TMDB has none.
     */
    suspend fun logoFor(item: MediaItem): String? {
        val id = item.tmdbId ?: tmdbIdFor(item) ?: return null
        val cacheKey = "${item.type}:$id"
        logoCache[cacheKey]?.let { return it.ifEmpty { null } }
        val key = store.current().tmdbKey ?: return null
        val path = if (item.type == MediaType.Movie) "/movie/$id/images" else "/tv/$id/images"
        val r = runCatching { http.get(tmdb.url(path, key, mapOf("include_image_language" to "en,null")), tmdb.headers(key)) }.getOrNull()
            ?.takeIf { it.ok } ?: return null
        val logos = r.body.array("logos")
        val best = logos.sortedWith(
            compareBy<JsonElement>(
                { when (it.str("iso_639_1")) { "en" -> 0; null -> 1; else -> 2 } },
                { if (it.str("file_path")?.endsWith(".png") == true) 0 else 1 },
                { -(((it.child("vote_average") as? JsonPrimitive)?.doubleOrNull) ?: 0.0) },
            ),
        ).firstOrNull()?.str("file_path")
        val url = best?.let { "https://image.tmdb.org/t/p/w500$it" }
        Log.i(TAG, "logo for ${item.title}: ${logos.size} on TMDB, ${if (url == null) "none" else "found"}")
        logoCache[cacheKey] = url.orEmpty()
        return url
    }

    /** A title's TMDB id: its own, or looked up from its IMDb id (Stremio addons such as AIOStreams use IMDb ids). */
    suspend fun tmdbIdFor(item: MediaItem): Int? {
        item.tmdbId?.let { return it }
        val imdb = item.imdbId ?: item.key.substringAfterLast(':').takeIf { it.startsWith("tt") } ?: return null
        val key = store.current().tmdbKey ?: return null
        return findByImdb(key, imdb, item.type)
    }

    private suspend fun findByImdb(key: String, imdbId: String, type: MediaType): Int? = runCatching {
        val r = http.get(tmdb.url("/find/$imdbId", key, mapOf("external_source" to "imdb_id")), tmdb.headers(key))
        r.body.array(if (type == MediaType.Movie) "movie_results" else "tv_results").firstOrNull().int("id")
    }.getOrNull()
}
