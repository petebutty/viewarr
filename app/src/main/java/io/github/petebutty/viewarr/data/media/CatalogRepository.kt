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
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.data.settings.PlaybackStore
import io.github.petebutty.viewarr.data.settings.RowLayoutStore
import io.github.petebutty.viewarr.data.xtream.XtreamClient
import io.github.petebutty.viewarr.data.xtream.XtreamSeries
import io.github.petebutty.viewarr.data.xtream.XtreamVod
import io.github.petebutty.viewarr.ui.theme.Dates
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Catalog"
private const val TMDB_IMG = "https://image.tmdb.org/t/p/"
/** Library copies from the IPTV provider (Xtream Codes) are labelled with this source. */
const val PROVIDER = "IPTV"
/** plex.tv's own service for watchlists (not your server). */
private const val PLEX_DISCOVER = "https://discover.provider.plex.tv"
private val NOT_ALNUM = Regex("[^a-z0-9]")
private val MARKS = Regex("\\p{Mn}+")

/**
 * A home-network address: 192.168.x / 10.x / 172.16–31.x, a .local or single-word name, or Plex's
 * "192-168-1-5.<id>.plex.direct" form of those. Anything else (a domain, Tailscale) counts as away.
 */
fun isHomeAddress(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
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
        // A fixed id for the combined row (not whichever server's row came first), so Home page & menu settings
        // for it stick. One page's rows are all one type, and settings are kept per type.
        r.copy(id = "merged:$g", title = title, items = merged)
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

/** Stremio catalogue types played as single items (sports, live events, channels): shown with films. */
private val ADDON_SINGLE_TYPES = setOf("tv", "channel", "channels", "events", "event", "sports", "sport", "other")

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
    private val playbackStore: PlaybackStore,
    private val plex: PlexService,
    private val mediaServers: MediaServerService,
    private val sessions: SessionStore,
    private val xtream: XtreamClient,
    private val rowLayouts: RowLayoutStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {
    /** Background work that outlives a screen (copying Trakt's history to Plex). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * What one person's or provider's lookups found, forgotten when the Live TV source or profile changes (profiles are
     * Dispatcharr users, each with their own sign-ins): otherwise the last one's IPTV films, Plex copies and episodes
     * (Plex keys are bare numbers, so they'd point at a different title on the new main server) and Trakt lists stayed.
     */
    private fun clearPersonalCaches() {
        xcCache = null
        copyCache.clear()
        groupCache.clear(); groupItemsCache.clear(); groupByKey.clear()
        plexEpisodeCache.clear()
        traktWatchlist.clear()
        _traktWatched.value = null
    }

    init {
        appScope.launch {
            sessions.session.map { it?.id }.distinctUntilChanged().drop(1).collect { clearPersonalCaches() }
        }
        appScope.launch {
            store.accounts.map { listOf(it.plex?.token, it.trakt != null, it.jellyfin?.token, it.emby?.token) }.distinctUntilChanged().drop(1).collect { clearPersonalCaches() }
        }
        // Trakt watched history: checked soon after start and every half hour (copied to Plex too, if that's on).
        appScope.launch {
            delay(5_000)
            while (true) {
                runCatching { traktWatched() }
                delay(30 * 60_000L)
            }
        }
    }
    private val tmdbCache = BoundedCache<String, MediaItem>(2000)
    private val tmdbLimit = Semaphore(6)
    private val traktWatchlist: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun rows(type: MediaType): Flow<RowsState> = channelFlow {
        val a = store.current()
        val traktAuth = a.trakt?.let { freshTrakt(it) }
        // Each source can hand over rows as they come (an addon with many catalogues: network by network).
        val slots: List<suspend (suspend (List<MediaRow>) -> Unit) -> List<MediaRow>> = buildList {
            // Trakt connected: Continue watching from Trakt's own list of what you've stopped partway, from any app or
            // device (Peter, 2026-11-19). Also remembered for the title pages' bars (traktPlaybackOf).
            traktAuth?.let { t -> add { listOfNotNull(traktPlaybackRow(a, t, type)) } }
            plexServers(a).forEach { p -> add { listOfNotNull(plexOnDeck(a, p, type)) } }
            a.jellyfin?.let { j -> add { listOfNotNull(serverResume(a, j, emby = false, type)) } }
            a.emby?.let { e -> add { listOfNotNull(serverResume(a, e, emby = true, type)) } }
            if (type == MediaType.Show) add { listOfNotNull(upcomingRow(a, traktAuth)) }
            // Your watchlist: from the one chosen in Settings → Watchlist.
            when (watchlistSource(a)) {
                "trakt" -> traktAuth?.let { t -> add { listOfNotNull(traktWatchlistRow(a, t, type)) } }
                "plex" -> add { listOfNotNull(plexWatchlistRow(a, type)) }
                "jellyfin" -> a.jellyfin?.let { j -> add { listOfNotNull(serverFavouritesRow(a, j, emby = false, type)) } }
                "emby" -> a.emby?.let { e -> add { listOfNotNull(serverFavouritesRow(a, e, emby = true, type)) } }
            }
            plexServers(a).forEach { p -> add { plexRecent(a, p, type) } }
            a.jellyfin?.let { j -> add { listOfNotNull(serverLatest(a, j, emby = false, type)) } }
            a.emby?.let { e -> add { listOfNotNull(serverLatest(a, e, emby = true, type)) } }
            // Your collections and playlists (as cards), and any collection you've made a row of.
            add { groupRows(a, type) }
            traktAuth?.let { t -> add { listOfNotNull(traktRecommended(a, t, type)) } }
            a.tmdbKey?.let { k ->
                val t = if (type == MediaType.Movie) "movie" else "tv"
                add { listOfNotNull(tmdbList(k, type, "/trending/$t/week", "trending", "Trending this week")) }
                add { listOfNotNull(tmdbList(k, type, "/$t/popular", "popular", "Popular")) }
                add { listOfNotNull(tmdbList(k, type, "/$t/top_rated", "top", "Top rated")) }
                if (type == MediaType.Movie) add { listOfNotNull(tmdbList(k, type, "/movie/now_playing", "cinema", "In cinemas", mapOf("region" to "GB"))) }
                else add { listOfNotNull(tmdbList(k, type, "/tv/on_the_air", "airing", "On TV this week")) }
            }
            a.activeAddons.forEach { addon -> add { part -> stremioRows(addon, type, part) } }
            // What's just arrived on each streaming service ("Netflix · Just added"), from the Viewarr plugin.
            add { justAddedRows(a, type) }
            // IPTV provider films / series, when live TV is signed in to a provider (Xtream Codes).
            add { providerRows(a, type) }
        }
        val found = arrayOfNulls<List<MediaRow>>(slots.size)
        val lock = Mutex()
        var finished = 0
        fun clean(rows: List<MediaRow>) = rows.map { r -> r.copy(items = r.items.distinctBy { it.key }) }.filter { it.items.isNotEmpty() }
        slots.forEachIndexed { i, slot ->
            launch {
                // Rows handed over on the way are shown at once and kept even if the source then runs out of time.
                val part: suspend (List<MediaRow>) -> Unit = { rows ->
                    lock.withLock { found[i] = clean(rows); send(RowsState(found.filterNotNull().flatten(), done = false)) }
                }
                val result = runCatching { withTimeoutOrNull(45_000) { slot(part) } }
                    .onFailure { Log.w(TAG, "row ${type.name}#$i failed: ${it.javaClass.simpleName} ${it.message}") }
                    .getOrNull()?.let(::clean)
                lock.withLock {
                    found[i] = result ?: found[i].orEmpty()
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
        val owned = HashMap<String, String>()
        fun withTicks(rows: List<MediaRow>) = rows.map { r ->
            if (!r.id.startsWith("search-tmdb")) r else r.copy(items = r.items.map { it.copy(owned = it.key in owned, ownedFrom = owned[it.key]) })
        }
        slots.forEachIndexed { i, slot ->
            launch {
                val result = runCatching { withTimeoutOrNull(20_000) { slot() } }
                    .onFailure { Log.w(TAG, "search #$i failed: ${it.javaClass.simpleName}") }
                    .getOrNull().orEmpty().map { r -> r.copy(items = r.items.distinctBy { it.key }) }.filter { it.items.isNotEmpty() }
                lock.withLock {
                    found[i] = result
                    if (i == 0) result.firstOrNull()?.items?.forEach { owned[it.key] = it.origin.split(" · ").distinct().joinToString(" · ") }
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

    /** Who they are: photo, born / died, where, and their biography (British English, else any English). */
    suspend fun personInfo(personId: String, name: String): PersonInfo? {
        val key = store.current().tmdbKey ?: return null
        suspend fun get(lang: String) = runCatching { http.get(tmdb.url("/person/$personId", key, mapOf("language" to lang)), tmdb.headers(key)) }.getOrNull()?.takeIf { it.ok }?.body
        val j = get("en-GB") ?: return null
        val bio = j.str("biography")?.takeIf { it.isNotBlank() } ?: get("en-US")?.str("biography")?.takeIf { it.isNotBlank() }
        fun date(k: String) = j.str(k)?.let { d -> runCatching { LocalDate.parse(d).format(Dates.long) }.getOrNull() }
        return PersonInfo(
            name = j.str("name") ?: name,
            photo = j.str("profile_path")?.let { "https://image.tmdb.org/t/p/w300$it" },
            bio = bio?.trim(),
            born = date("birthday"),
            died = date("deathday"),
            place = j.str("place_of_birth"),
            knownFor = j.str("known_for_department"),
        )
    }

    /** A person's films and shows (acting, and directing / writing), most popular first. */
    suspend fun personCredits(personId: String, name: String): List<MediaRow> {
        val key = store.current().tmdbKey ?: return emptyList()
        val r = http.get(tmdb.url("/person/$personId/combined_credits", key, mapOf("language" to "en-GB")), tmdb.headers(key))
        if (!r.ok) return emptyList()
        fun pop(j: JsonElement?) = (j.child("popularity") as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun items(list: List<JsonElement>, type: MediaType) = list.sortedByDescending { pop(it) }
            .mapNotNull { tmdbItem(it, type) }.filter { it.poster != null }.distinctBy { it.key }.take(40)
        // Not their talk-show and news guest spots, nor appearances as themselves: their own work.
        fun self(j: JsonElement?) = j.str("character")?.trim()?.let { c ->
            c.equals("Self", true) || c.startsWith("Himself", true) || c.startsWith("Herself", true) || c.startsWith("Themselves", true) || c.startsWith("Self ", true) || c.startsWith("Self -", true)
        } == true
        fun talkOrNews(j: JsonElement?) = j.array("genre_ids").any { g -> g.toString().let { it == "10767" || it == "10763" } }
        val cast = r.body.array("cast").filter { !self(it) && !talkOrNews(it) }
        val crew = r.body.array("crew").filter { it.str("job") == "Director" || it.str("department") == "Writing" }
        return listOfNotNull(
            MediaRow("person-films", "Films with $name", items(cast.filter { it.str("media_type") == "movie" }, MediaType.Movie)),
            MediaRow("person-shows", "Shows with $name", items(cast.filter { it.str("media_type") == "tv" }, MediaType.Show)),
            MediaRow("person-made", "Directed or written by $name", items(crew.filter { it.str("media_type") == "movie" }, MediaType.Movie) +
                items(crew.filter { it.str("media_type") == "tv" }, MediaType.Show)),
        ).filter { it.items.isNotEmpty() }
    }

    private val personCache = BoundedCache<String, PersonDetails>(12)

    /**
     * A person's page: who they are, their credits and pictures, in one TMDB call (a second, small one only when
     * there's no British English biography). Kept for the session.
     */
    suspend fun personDetails(personId: String, name: String): PersonDetails? {
        personCache[personId]?.let { return it }
        val key = store.current().tmdbKey ?: return null
        suspend fun get(params: Map<String, String>) = runCatching { http.get(tmdb.url("/person/$personId", key, params), tmdb.headers(key)) }
            .getOrNull()?.takeIf { it.ok }?.body
        val j = get(mapOf("language" to "en-GB", "append_to_response" to "combined_credits,images,tagged_images")) ?: return null
        val bio = j.str("biography")?.takeIf { it.isNotBlank() } ?: get(mapOf("language" to "en-US"))?.str("biography")?.takeIf { it.isNotBlank() }
        return parsePersonDetails(personId, name, j, bio) { e, t -> tmdbItem(e, t) }.also { personCache[personId] = it }
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

    /**
     * A guide programme's film or show on TMDB, by title (exact match only, so a wrong one isn't played). [show]:
     * it has a season / episode, so it's a show; otherwise a film is preferred.
     */
    suspend fun findTitle(title: String, show: Boolean, year: Int? = null, season: Int? = null): MediaItem? {
        val key = store.current().tmdbKey ?: return null
        val clean = title.removePrefix("New: ").trim()
        // Guides abbreviate ("Pt1" for "Part 1") and punctuate differently: compare letters and numbers only.
        fun norm(t: String) = t.lowercase().replace("&", "and").replace(Regex("\\bpart\\s*"), "pt").replace(NOT_ALNUM, "")
        val types = if (show) listOf(MediaType.Show) else listOf(MediaType.Movie, MediaType.Show)
        /**
         * Only a sure match opens by itself: the one title with this name (a show, when the guide gives a season or
         * episode; the year, when it gives one). Several with the same name ("Adultery" is a film and two shows):
         * null, and search shows them all to pick from.
         */
        suspend fun sure(q: String): MediaItem? {
            val w = norm(q)
            val exact = types.flatMap { t -> runCatching { tmdbSearch(key, t, q.replace(Regex("\\bPt\\s*(\\d)"), "Part $1")) }.getOrNull()?.items.orEmpty() }
                .filter { norm(it.title) == w }
            val narrowed = if (year != null) exact.filter { it.year == year }.ifEmpty { exact.filter { it.year == null } } else exact
            narrowed.singleOrNull()?.let { return it }
            if (narrowed.size < 2) return null
            // Several with this name ("S.W.A.T." 1975 and 2017): the ones that have the guide's series…
            var left = narrowed
            if (season != null) {
                val withSeason = left.filter { c -> c.type != MediaType.Show || (c.tmdbId?.let { tvSeasonCount(key, it) } ?: 0) >= season }
                if (withSeason.isNotEmpty()) left = withSeason
                left.singleOrNull()?.let { return it }
            }
            // …then the one on your own servers.
            val owned = left.filter { c -> runCatching { copiesOf(c) }.getOrDefault(emptyList()).any { it.source != PROVIDER } }
            return owned.singleOrNull()
        }
        sure(clean)?.let { return it }
        // Guides add an episode or special's name to the title ("Only Fools and Horses To Hull and Back"): drop words
        // from the end until one title matches surely, keeping at least two words.
        val words = clean.split(' ').filter { it.isNotBlank() }
        for (n in words.size - 1 downTo 2) sure(words.take(n).joinToString(" "))?.let { return it }
        return null
    }

    /** How many seasons a show has on TMDB (0 if unknown). */
    private suspend fun tvSeasonCount(key: String, id: Int): Int = runCatching {
        http.get(tmdb.url("/tv/$id", key, emptyMap()), tmdb.headers(key)).takeIf { it.ok }?.body.int("number_of_seasons") ?: 0
    }.getOrDefault(0)

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
            (c.str("type") == "movie" || c.str("type") == "series" || c.str("type") == "anime" || c.str("type") in ADDON_SINGLE_TYPES) &&
                (c.array("extra").any { it.str("name") == "search" } || c.array("extraSupported").any { (it as? JsonPrimitive)?.content == "search" })
        }.take(6)
        return coroutineScope {
            catalogs.map { c ->
                async {
                    val id = c.str("id") ?: return@async null
                    val st = c.str("type")!!
                    val type = if (st == "series" || st == "anime") MediaType.Show else MediaType.Movie // single-play kinds: like films
                    val r = runCatching { http.get("${addon.baseUrl}/catalog/$st/${enc(id)}/search=${enc(q)}.json") }.getOrNull()?.takeIf { it.ok } ?: return@async null
                    val items = r.body.array("metas").take(30).mapNotNull { m -> stremioMeta(addon, m, type, st) }
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

    /**
     * "<service> · Just added": what arrived on each streaming service in the UK over the last fortnight, newest first,
     * new seasons of shows included (the Viewarr plugin reads it from JustWatch once a day: viewarr_just_added).
     * TMDB's "New" can't show these: it knows which service has a title, not when it arrived (Peter, 2026-11-20).
     */
    private suspend fun justAddedRows(a: Accounts, type: MediaType): List<MediaRow> {
        // Switched off in the Viewarr plugin: no Just added rows for the house (and no fetching of our own).
        if (io.github.petebutty.viewarr.data.sync.ServerSports.offInPlugin(io.github.petebutty.viewarr.data.sync.ServerSports.F_JUST)) return emptyList()
        // From the Viewarr plugin when it's there (it asks JustWatch once a day for the house); else the TV asks JustWatch
        // itself, once a day (JustAddedLocal), so everyone has these rows.
        val s = sessions.session.first()?.takeIf { !it.isDirect }
        val r = s?.let { runCatching { http.get(it.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/viewarr_just_added/", mapOf("X-API-Key" to it.apiKey)) }.getOrNull() }
        val o = (r?.body as? JsonObject)?.takeIf { r.ok && (it["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.let { at -> System.currentTimeMillis() / 1000 - at < 3 * 86_400 } == true }
            ?: (if (io.github.petebutty.viewarr.data.sync.ServerSports.tvDoesJobs) JustAddedLocal.lists(appContext, http) else null) ?: return emptyList()
        if (o.int("v") != 1) return emptyList()
        val field = if (type == MediaType.Movie) "movies" else "shows"
        val day = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.UK)
        return o.array("services").mapNotNull { svc ->
            val name = svc.str("name") ?: return@mapNotNull null
            val items = svc.array(field).mapNotNull { j ->
                val id = j.int("tmdb") ?: return@mapNotNull null
                val season = j.int("season")
                val added = j.str("added")?.let { runCatching { java.time.LocalDate.parse(it).format(day) }.getOrNull() }
                MediaItem(
                    key = "tmdb:${type.name}:$id", type = type, title = j.str("title") ?: return@mapNotNull null,
                    year = j.int("year"), poster = j.str("poster"), tmdbId = id, imdbId = j.str("imdb"), origin = name,
                    // A new season of a show says so; else the day it arrived.
                    subtitle = listOfNotNull(season?.let { "Season $it new" }, added?.let { "Added $it" }).joinToString("  ·  ").ifEmpty { null },
                )
            }
            if (items.isEmpty()) null
            else MediaRow("justadded-" + name.lowercase().filter { it.isLetterOrDigit() } + "-" + type.name, "$name · Just added", enrich(a, items))
        }
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
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        data class Up(val item: MediaItem, val date: LocalDate, val time: ZonedDateTime?, val s: Int?, val e: Int?, val name: String?)
        val found = ArrayList<Up>()
        if (traktAuth != null) {
            val r = runCatching { http.get("https://api.trakt.tv/calendars/my/shows/$today/35", trakt.headers(traktAuth.accessToken)) }.getOrNull()
            r?.takeIf { it.ok }?.body?.asArray()?.forEach { j ->
                val item = traktItem(j.child("show"), MediaType.Show, "Trakt") ?: return@forEach
                val at = j.str("first_aired")?.let { runCatching { Instant.parse(it).atZone(zone) }.getOrNull() } ?: return@forEach
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
                            val date = next.str("air_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@withPermit null
                            val item = (tmdbItem(r.body, MediaType.Show) ?: show).copy(origin = show.origin)
                            Up(item, date, null, next.int("season_number"), next.int("episode_number"), next.str("name"))
                        }
                    }
                }.awaitAll().filterNotNull().forEach { found += it }
            }
        }
        val now = ZonedDateTime.now(zone)
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
                u.date.isBefore(today.plusDays(7)) -> u.date.format(DateTimeFormatter.ofPattern("EEEE"))
                else -> u.date.format(Dates.day)
            }
            val clock = u.time?.format(Dates.time)
            val ep = if (u.s != null && u.e != null) "S${u.s} · E${u.e}" else null
            item.copy(
                key = item.key + ":upcoming",
                subtitle = listOfNotNull(day + (clock?.let { " $it" } ?: ""), ep).joinToString("  ·  "),
            )
        }
        return MediaRow("upcoming-${MediaType.Show.name}", "Upcoming episodes", items)
    }

    // ------------------------------------------------------------------ Trakt

    /** One refresh at a time: Trakt's refresh tokens work once, so two at once (Movies and Shows loading together) left
     *  the second with a spent token and Trakt signed out. */
    private val traktRefreshLock = Mutex()

    private suspend fun freshTrakt(cached: TraktAuth): TraktAuth = traktRefreshLock.withLock {
        // The saved login, not the caller's copy (another call may just have refreshed it).
        val auth = store.current().trakt ?: cached
        if (auth.expiresAt - System.currentTimeMillis() > 3_600_000) return@withLock auth
        refreshTrakt(auth)
    }

    private suspend fun refreshTrakt(auth: TraktAuth): TraktAuth {
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

    /**
     * Continue watching on Trakt: GET /sync/playback/{movies|episodes} — everything stopped between 1% and 80%, from
     * any app (Trakt keeps it about six months). Films with how far through; shows as their most recent paused
     * episode (one card per show, "S2 · E3"). Newest first.
     */
    private suspend fun traktPlaybackRow(a: Accounts, t: TraktAuth, type: MediaType): MediaRow? {
        val kind = if (type == MediaType.Movie) "movies" else "episodes"
        val r = http.get("https://api.trakt.tv/sync/playback/$kind?limit=60", trakt.headers(t.accessToken))
        if (!r.ok) return null
        val entries = r.body.asArray().sortedByDescending { it.str("paused_at").orEmpty() }
        rememberTraktPlayback(type, entries)
        val items = if (type == MediaType.Movie) entries.mapNotNull { e ->
            val pct = e.str("progress")?.toFloatOrNull()?.div(100f)
            traktItem(e.child("movie"), type, "Trakt")?.copy(progress = pct, subtitle = pct?.let { "${(it * 100).toInt()}% watched" })
        } else entries.distinctBy { it.child("show").child("ids").int("trakt") }.mapNotNull { e ->
            val ep = e.child("episode")
            val season = ep.int("season") ?: return@mapNotNull null
            val number = ep.int("number") ?: return@mapNotNull null
            traktItem(e.child("show"), type, "Trakt")?.copy(
                progress = e.str("progress")?.toFloatOrNull()?.div(100f), subtitle = "S$season · E$number", nextSeason = season, nextEpisode = number,
            )
        }
        if (items.isEmpty()) return null
        return MediaRow("trakt-resume-${type.name}", "Continue watching on Trakt", enrich(a, items.take(30)), group = "deck")
    }

    private suspend fun traktWatchlistRow(a: Accounts, t: TraktAuth, type: MediaType): MediaRow? {
        val kind = if (type == MediaType.Movie) "movies" else "shows"
        val field = if (type == MediaType.Movie) "movie" else "show"
        val r = http.get("https://api.trakt.tv/sync/watchlist/$kind/added", trakt.headers(t.accessToken))
        if (!r.ok) return null
        val items = r.body.asArray().mapNotNull { traktItem(it.child(field), type, "Trakt watchlist") }
        // This type's part of the set becomes what Trakt has now, so titles removed on trakt.tv drop out too.
        val now = items.mapNotNull { it.tmdbId }.map { "${type.name}:$it" }.toSet()
        traktWatchlist.removeAll { it.startsWith("${type.name}:") && it !in now }
        traktWatchlist.addAll(now)
        return MediaRow("trakt-watchlist-${type.name}", "Your watchlist", enrich(a, items.take(40)))
    }

    private suspend fun traktRecommended(a: Accounts, t: TraktAuth, type: MediaType): MediaRow? {
        val kind = if (type == MediaType.Movie) "movies" else "shows"
        val r = http.get("https://api.trakt.tv/recommendations/$kind?limit=30&ignore_collected=true&ignore_watchlisted=true", trakt.headers(t.accessToken))
        if (!r.ok) return null
        val items = r.body.asArray().mapNotNull { traktItem(it, type, "Trakt") }
        return MediaRow("trakt-reco-${type.name}", "Recommended for you", enrich(a, items))
    }

    /**
     * Continue watching from Trakt, from your most recent plays (Trakt's history, from any app that scrobbles to it):
     * each show watched lately, at the next episode after the last one you watched (Trakt's own "next episode"). Shows
     * you're up to date with drop out. Films: none (a film in your history has been finished).
     */
    suspend fun traktContinue(type: MediaType): List<MediaItem> = coroutineScope {
        if (type != MediaType.Show) return@coroutineScope emptyList()
        val a = store.current()
        val t = a.trakt?.let { freshTrakt(it) } ?: return@coroutineScope emptyList()
        val r = runCatching { http.get("https://api.trakt.tv/sync/history/episodes?limit=80", trakt.headers(t.accessToken)) }.getOrNull()
        if (r == null || !r.ok) return@coroutineScope emptyList()
        // The shows in the order you last watched them.
        val shows = r.body.asArray().map { it.child("show") }.distinctBy { it.child("ids").int("trakt") }.take(15)
        val limit = Semaphore(4)
        val items = shows.map { show ->
            async {
                val id = show.child("ids").int("trakt") ?: return@async null
                val p = limit.withPermit {
                    runCatching { http.get("https://api.trakt.tv/shows/$id/progress/watched?specials=false", trakt.headers(t.accessToken)) }.getOrNull()
                }?.takeIf { it.ok } ?: return@async null
                val next = p.body.child("next_episode") ?: return@async null
                val s = next.int("season") ?: return@async null
                val e = next.int("number") ?: return@async null
                traktItem(show, MediaType.Show, "Trakt")?.let { item ->
                    item.copy(key = item.key + ":trakt-next", nextSeason = s, nextEpisode = e, subtitle = "S$s · E$e  " + next.str("title").orEmpty())
                }
            }
        }.awaitAll().filterNotNull()
        enrich(a, items)
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

    /** Drop show / bring it back on Trakt too (its "dropped" list: off progress and Upcoming). Null: no Trakt or no id. */
    suspend fun setTraktDropped(item: MediaItem, dropped: Boolean): Boolean? {
        val id = item.tmdbId ?: return null
        val auth = store.current().trakt?.let { freshTrakt(it) } ?: return null
        val body = buildJsonObject { putJsonArray("shows") { add(buildJsonObject { putJsonObject("ids") { put("tmdb", id) } }) } }
        val url = "https://api.trakt.tv/users/hidden/dropped" + if (dropped) "" else "/remove"
        return runCatching { http.post(url, body, trakt.headers(auth.accessToken)).ok }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ watchlist (Trakt, Plex, Jellyfin or Emby)

    /** The watchlists that can be used (connected), in the order offered. */
    fun watchlistChoices(a: Accounts): List<String> = listOfNotNull(
        "trakt".takeIf { a.trakt != null }, "plex".takeIf { a.plex != null },
        "jellyfin".takeIf { a.jellyfin != null }, "emby".takeIf { a.emby != null },
    )

    /** The watchlist in use: the one chosen, if still connected, else the first that is. */
    fun watchlistSource(a: Accounts): String? = a.watchlist?.takeIf { it in watchlistChoices(a) } ?: watchlistChoices(a).firstOrNull()

    /** Whether [item] is on the watchlist in use (null: no watchlist, or this title can't go on it). */
    suspend fun inWatchlist(item: MediaItem): Boolean? {
        val a = store.current()
        return when (watchlistSource(a)) {
            "trakt" -> if (item.tmdbId == null) null else inTraktWatchlist(item)
            "plex" -> plexDiscoverKey(a, item)?.let { plexOnWatchlist(a, it) }
            "jellyfin" -> a.jellyfin?.let { serverFavourite(a, it, false, item) }
            "emby" -> a.emby?.let { serverFavourite(a, it, true, item) }
            else -> null
        }
    }

    /** Adds to or removes from the watchlist in use. The new state, or null if it failed. */
    suspend fun setWatchlist(item: MediaItem, add: Boolean): Boolean? {
        val a = store.current()
        return when (watchlistSource(a)) {
            "trakt" -> setTraktWatchlist(item, add)
            "plex" -> {
                val rk = plexDiscoverKey(a, item) ?: return null
                val ok = runCatching {
                    http.put("$PLEX_DISCOVER/actions/${if (add) "addToWatchlist" else "removeFromWatchlist"}?ratingKey=$rk", JsonObject(emptyMap()), plex.headers(a.deviceId, a.plex?.let { it.accountToken ?: it.token })).ok
                }.getOrDefault(false)
                if (ok) add else null
            }
            "jellyfin" -> a.jellyfin?.let { setServerFavourite(a, it, false, item, add) }
            "emby" -> a.emby?.let { setServerFavourite(a, it, true, item, add) }
            else -> null
        }
    }

    /** The watchlist's name for messages ("your Plex watchlist", "your Jellyfin favourites"). */
    suspend fun watchlistName(): String = when (watchlistSource(store.current())) {
        "trakt" -> "your Trakt watchlist"; "plex" -> "your Plex watchlist"
        "jellyfin" -> "your Jellyfin favourites"; "emby" -> "your Emby favourites"; else -> "your watchlist"
    }

    /** The title's id on plex.tv (watchlists live there, not on your server), found from its TMDB / IMDb id. */
    private val plexKeys = ConcurrentHashMap<String, String>()
    private suspend fun plexDiscoverKey(a: Accounts, item: MediaItem): String? {
        val token = a.plex?.let { it.accountToken ?: it.token } ?: return null
        plexKeys[item.key]?.let { return it }
        val type = if (item.type == MediaType.Movie) 1 else 2
        val guids = listOfNotNull(item.tmdbId?.let { "tmdb://$it" }, item.imdbId?.let { "imdb://$it" })
        for (g in guids) {
            val r = runCatching { http.get("https://metadata.provider.plex.tv/library/metadata/matches?type=$type&guid=${enc(g)}", plex.headers(a.deviceId, token)) }.getOrNull()
            val rk = r?.takeIf { it.ok }?.body.child("MediaContainer").array("Metadata").firstOrNull().str("ratingKey")
            if (rk != null) { plexKeys[item.key] = rk; return rk }
        }
        return null
    }

    private suspend fun plexOnWatchlist(a: Accounts, rk: String): Boolean? {
        val r = runCatching { http.get("$PLEX_DISCOVER/library/metadata/$rk/userState", plex.headers(a.deviceId, a.plex?.let { it.accountToken ?: it.token })) }.getOrNull() ?: return null
        if (!r.ok) return null
        val mc = r.body.child("MediaContainer")
        val st = mc.child("UserState").let { it as? JsonObject ?: mc.array("UserState").firstOrNull() }
        return st.long("watchlistedAt") != null || st.str("watchlistedAt") != null
    }

    private suspend fun plexWatchlistRow(a: Accounts, type: MediaType): MediaRow? {
        val token = a.plex?.let { it.accountToken ?: it.token } ?: return null
        val r = http.get("$PLEX_DISCOVER/library/sections/watchlist/all?type=${if (type == MediaType.Movie) 1 else 2}&includeGuids=1&sort=watchlistedAt:desc", plex.headers(a.deviceId, token))
        if (!r.ok) return null
        val items = r.body.child("MediaContainer").array("Metadata").mapNotNull { j ->
            val ids = j.array("Guid").mapNotNull { it.str("id") }
            val tmdb = ids.firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toIntOrNull()
            val imdb = ids.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://")
            val title = j.str("title") ?: return@mapNotNull null
            j.str("ratingKey")?.let { rk -> plexKeys[tmdb?.let { "tmdb:${type.name}:$it" } ?: "plexwl:$rk"] = rk }
            MediaItem(
                key = tmdb?.let { "tmdb:${type.name}:$it" } ?: "plexwl:${j.str("ratingKey")}",
                type = type, title = title, year = j.int("year"), tmdbId = tmdb, imdbId = imdb,
                poster = j.str("thumb")?.takeIf { it.startsWith("http") }, origin = "Plex watchlist",
            )
        }
        if (items.isEmpty()) return null
        return MediaRow("plex-watchlist-${type.name}", "Your watchlist", enrich(a, items.take(40)))
    }

    /** Jellyfin / Emby have no watchlist: their Favourites stand in (only for titles on that server). */
    private suspend fun serverFavId(a: Accounts, s: MediaServerAuth, emby: Boolean, item: MediaItem): String? =
        runCatching { serverCopies(a, s, emby, item) }.getOrNull()?.firstOrNull()?.ref

    private suspend fun serverFavourite(a: Accounts, s: MediaServerAuth, emby: Boolean, item: MediaItem): Boolean? {
        val id = serverFavId(a, s, emby, item) ?: return null
        val r = runCatching { http.get("${s.serverUrl}/Users/${s.userId}/Items/$id", mediaServers.headers(emby, a.deviceId, s.token)) }.getOrNull() ?: return null
        return if (r.ok) (r.body.child("UserData").child("IsFavorite") as? JsonPrimitive)?.booleanOrNull ?: false else null
    }

    private suspend fun setServerFavourite(a: Accounts, s: MediaServerAuth, emby: Boolean, item: MediaItem, add: Boolean): Boolean? {
        val id = serverFavId(a, s, emby, item) ?: return null
        val url = "${s.serverUrl}/Users/${s.userId}/FavoriteItems/$id"
        val h = mediaServers.headers(emby, a.deviceId, s.token)
        val ok = runCatching { if (add) http.post(url, null, h).ok else http.delete(url, h).ok }.getOrDefault(false)
        return if (ok) add else null
    }

    private suspend fun serverFavouritesRow(a: Accounts, s: MediaServerAuth, emby: Boolean, type: MediaType): MediaRow? {
        val name = if (emby) "Emby" else "Jellyfin"
        val kind = if (type == MediaType.Movie) "Movie" else "Series"
        val r = http.get(
            "${s.serverUrl}/Users/${s.userId}/Items?IncludeItemTypes=$kind&Filters=IsFavorite&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=40&Fields=ProviderIds,Overview,ProductionYear",
            mediaServers.headers(emby, a.deviceId, s.token),
        )
        if (!r.ok) return null
        val items = r.body.array("Items").mapNotNull { serverItem(s, it, type, name) }
        if (items.isEmpty()) return null
        return MediaRow("$name-favourites-${type.name}", "Your watchlist", items)
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
        val groups = async { runCatching { groupEntries(a, type) }.getOrDefault(emptyList()) }
        (plexParts + others + provider + groups).awaitAll().flatten()
    }

    /** A library's genres, from the server itself (id to name), for filtering. */
    suspend fun libraryGenres(lib: MediaLibrary, type: MediaType): List<Pair<String, String>> {
        if (lib.kind != LibKind.Library) return emptyList()
        val a = store.current()
        if (lib.source == PROVIDER_LIBRARY) {
            // The provider's categories stand in for genres ("|EN| ACTION/THRILLER" → "Action/Thriller").
            if (dispatcharrVod() != null) {
                val want = if (type == MediaType.Movie) "movie" else "series"
                val list = dvodGet("categories/", emptyMap())
                val cats = ((list as? JsonArray) ?: list.array("results")).filter { c ->
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


    // ---- Library sorting and filtering (what each server offers, and loading with any of it)

    /** Plex filters worth offering on a TV (people lists and folder paths are left out: too long to pick from). */
    private val plexFilterKeys = listOf(
        "genre", "decade", "year", "contentRating", "collection", "studio", "network", "country", "resolution",
        "editionTitle", "label", "audioLanguage", "subtitleLanguage",
    )
    private val plexToggleKeys = listOf("unwatched", "unwatchedLeaves", "inProgress", "hdr", "dovi", "hdr10plus", "atmos")
    private val plexToggleLabels = mapOf(
        "unwatched" to "Unwatched", "unwatchedLeaves" to "Unwatched", "inProgress" to "In progress",
        "hdr" to "HDR", "dovi" to "Dolby Vision", "hdr10plus" to "HDR10+", "atmos" to "Dolby Atmos",
    )
    private val plexFilterLabels = mapOf("contentRating" to "Age rating", "editionTitle" to "Edition", "label" to "Labels")

    /** What [lib] can be sorted and filtered by (from the server itself where it says). */
    suspend fun libraryOptions(lib: MediaLibrary, type: MediaType): LibraryOptions {
        if (lib.kind != LibKind.Library) return groupOptions(lib)
        val a = store.current()
        if (lib.source == PROVIDER_LIBRARY) return LibraryOptions(
            sorts = listOf(SortOption("Title", "Title", false), SortOption("Added", "Date added"), SortOption("Released", "Release date"), SortOption("Rating", "Rating")),
            filters = listOf(FilterGroup("category", "Category", multi = false)),
            letters = false, titleSort = "Title", directions = false,
        )
        if (lib.server != null) {
            val p = plexServers(a).firstOrNull { it.machineId == lib.server }
            val t = if (type == MediaType.Movie) 1 else 2
            val sorts = p?.let { runCatching { plexGet(a, it, "/library/sections/${lib.id}/sorts?type=$t") }.getOrNull() }
                ?.array("Directory")?.mapNotNull { d ->
                    val k = d.str("key") ?: return@mapNotNull null
                    SortOption(k, plexSortLabel(k, d.str("title")), descFirst = k != "titleSort")
                }.orEmpty()
            val filters = p?.let { runCatching { plexGet(a, it, "/library/sections/${lib.id}/filters?type=$t") }.getOrNull() }
                ?.array("Directory").orEmpty()
            val keys = filters.mapNotNull { it.str("filter") }.toSet()
            val groups = plexToggleKeys.filter { it in keys }.map { FilterGroup(it, plexToggleLabels[it] ?: it, toggle = true) } +
                plexFilterKeys.filter { it in keys }.map { k -> FilterGroup(k, plexFilterLabels[k] ?: filters.first { it.str("filter") == k }.str("title") ?: k) }
            return LibraryOptions(
                sorts = sorts.ifEmpty { listOf(SortOption("titleSort", "Title", false), SortOption("addedAt", "Date added"), SortOption("originallyAvailableAt", "Release date")) },
                filters = groups, letters = true, titleSort = "titleSort",
            )
        }
        // Jellyfin / Emby: the same lists for every library.
        val movie = type == MediaType.Movie
        return LibraryOptions(
            sorts = listOf(
                SortOption("SortName", "Title", false), SortOption("DateCreated", "Date added"), SortOption("PremiereDate", "Release date"),
                SortOption("ProductionYear", "Year"), SortOption("CommunityRating", "Rating"), SortOption("CriticRating", "Critic rating"),
                SortOption("OfficialRating", "Age rating", false), SortOption("Runtime", "Duration"), SortOption("DatePlayed", "Date watched"),
                SortOption("PlayCount", "Plays"), SortOption("Random", "Random"),
            ),
            filters = listOfNotNull(
                FilterGroup("IsUnplayed", "Unwatched", toggle = true), FilterGroup("IsResumable", "In progress", toggle = true),
                FilterGroup("IsFavorite", "Favourites", toggle = true),
                if (movie) FilterGroup("Is4K", "4K", toggle = true) else null, FilterGroup("IsHD", "HD", toggle = true),
                FilterGroup("Genres", "Genre"), FilterGroup("Years", "Year"), FilterGroup("OfficialRatings", "Age rating"),
                FilterGroup("Studios", if (movie) "Studio" else "Network"), FilterGroup("Tags", "Tags"),
            ),
            letters = true, titleSort = "SortName",
        )
    }

    private fun plexSortLabel(key: String, title: String?) = when (key) {
        "titleSort" -> "Title"; "originallyAvailableAt" -> "Release date"; "addedAt" -> "Date added"; "lastViewedAt" -> "Date watched"
        "rating" -> "Critic rating"; "audienceRating" -> "Audience rating"; "mediaHeight" -> "Resolution"; "random" -> "Random"
        "duration" -> "Duration"; "viewCount" -> "Plays"; "year" -> "Year"; "episode.addedAt" -> "Latest episode"
        "unviewedLeafCount" -> "Unwatched episodes"
        else -> title ?: key
    }

    /** The choices inside one filter (a library's genres, decades…). */
    suspend fun filterValues(lib: MediaLibrary, type: MediaType, group: String): List<FilterValue> {
        if (lib.kind != LibKind.Library) return emptyList()
        val a = store.current()
        if (lib.source == PROVIDER_LIBRARY) return libraryGenres(lib, type).map { (id, name) -> FilterValue(id, name) }
        if (lib.server != null) {
            val p = plexServers(a).firstOrNull { it.machineId == lib.server } ?: return emptyList()
            val t = if (type == MediaType.Movie) 1 else 2
            val c = runCatching { plexGet(a, p, "/library/sections/${lib.id}/$group?type=$t") }.getOrNull() ?: return emptyList()
            val values = c.array("Directory").mapNotNull { d -> val k = d.str("key") ?: return@mapNotNull null; FilterValue(k, d.str("title") ?: k) }
            // Newest years and decades first.
            return if (group == "year" || group == "decade") values.sortedByDescending { it.id.toIntOrNull() ?: 0 } else values
        }
        val emby = lib.source == "Emby"
        val srv = (if (emby) a.emby else a.jellyfin) ?: return emptyList()
        val kind = if (type == MediaType.Movie) "Movie" else "Series"
        val h = mediaServers.headers(emby, a.deviceId, srv.token)
        val base = "ParentId=${enc(lib.id)}&UserId=${srv.userId}&IncludeItemTypes=$kind&Recursive=true"
        return when (group) {
            "Genres" -> libraryGenres(lib, type).map { (id, n) -> FilterValue(id, n) }
            "Studios" -> runCatching { http.get("${srv.serverUrl}/Studios?$base&SortBy=SortName", h) }.getOrNull()?.takeIf { it.ok }
                ?.body.array("Items").mapNotNull { s -> val id = s.str("Id") ?: return@mapNotNull null; FilterValue(id, s.str("Name") ?: id) }
            else -> {
                val f = runCatching { http.get("${srv.serverUrl}/Items/Filters?$base", h) }.getOrNull()?.takeIf { it.ok }?.body
                val list = ((f as? JsonObject)?.get(group) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                val values = list.map { FilterValue(it, it) }
                if (group == "Years") values.sortedByDescending { it.id.toIntOrNull() ?: 0 } else values
            }
        }
    }

    /**
     * One stretch of [lib] with [q] applied: titles from [start], and how many match in all; null if the server
     * didn't answer.
     */
    suspend fun libraryPage(lib: MediaLibrary, type: MediaType, q: LibraryQuery, start: Int, size: Int): Pair<List<MediaItem>, Int>? {
        val a = store.current()
        val text = q.query.trim()
        if (lib.kind != LibKind.Library) return groupPage(a, lib, type, q, start, size)
        if (lib.source == PROVIDER_LIBRARY) {
            val sort = LibrarySort.entries.firstOrNull { it.name == q.sort } ?: LibrarySort.Released
            val got = providerPage(type, sort, start, size, text, q.filters["category"]?.firstOrNull()?.id) ?: return null
            // The provider sorts one way only: the other way is the same list reversed (fine for its smaller lists).
            return got
        }
        if (lib.server != null) {
            val p = plexServers(a).firstOrNull { it.machineId == lib.server } ?: return null
            val c = runCatching { plexGet(a, p, "/library/sections/${lib.id}/all?" + plexQuery(q, type) + "&includeGuids=1&X-Plex-Container-Start=$start&X-Plex-Container-Size=$size") }.getOrNull() ?: return null
            val items = c.array("Metadata").mapNotNull { plexItem(p, it, type) }
            return items to (c.int("totalSize") ?: c.int("size") ?: items.size)
        }
        val emby = lib.source == "Emby"
        val srv = (if (emby) a.emby else a.jellyfin) ?: return null
        val r = runCatching {
            http.get(
                "${srv.serverUrl}/Users/${srv.userId}/Items?" + jellyfinQuery(lib, type, q) + "&StartIndex=$start&Limit=$size&Fields=ProviderIds,Overview,ProductionYear",
                mediaServers.headers(emby, a.deviceId, srv.token),
            )
        }.getOrNull()?.takeIf { it.ok } ?: return null
        val items = r.body.array("Items").mapNotNull { serverItem(srv, it, type, lib.source) }
        return items to (r.body.int("TotalRecordCount") ?: items.size)
    }

    private fun plexQuery(q: LibraryQuery, type: MediaType): String = buildString {
        append("type=").append(if (type == MediaType.Movie) 1 else 2)
        append("&sort=").append(enc(if (q.desc) "${q.sort}:desc" else q.sort))
        val text = q.query.trim()
        if (text.isNotEmpty()) append("&title=").append(enc(text))
        // Several values of one filter: any of them (Plex takes them comma-separated).
        q.filters.forEach { (k, v) -> if (v.isNotEmpty()) append("&").append(k).append("=").append(v.joinToString(",") { enc(it.id) }) }
        q.toggles.forEach { append("&").append(it).append("=1") }
    }

    private fun jellyfinQuery(lib: MediaLibrary, type: MediaType, q: LibraryQuery): String = buildString {
        append("ParentId=").append(enc(lib.id)).append("&IncludeItemTypes=").append(if (type == MediaType.Movie) "Movie" else "Series").append("&Recursive=true")
        append("&SortBy=").append(if (q.sort == "SortName") "SortName" else "${q.sort},SortName")
        append("&SortOrder=").append(if (q.desc) "Descending" else "Ascending")
        val text = q.query.trim()
        if (text.isNotEmpty()) append("&SearchTerm=").append(enc(text))
        q.filters["Genres"]?.takeIf { it.isNotEmpty() }?.let { append("&GenreIds=").append(it.joinToString("|") { v -> enc(v.id) }) }
        q.filters["Studios"]?.takeIf { it.isNotEmpty() }?.let { append("&StudioIds=").append(it.joinToString("|") { v -> enc(v.id) }) }
        q.filters["Tags"]?.takeIf { it.isNotEmpty() }?.let { append("&Tags=").append(it.joinToString("|") { v -> enc(v.id) }) }
        q.filters["OfficialRatings"]?.takeIf { it.isNotEmpty() }?.let { append("&OfficialRatings=").append(it.joinToString("|") { v -> enc(v.id) }) }
        q.filters["Years"]?.takeIf { it.isNotEmpty() }?.let { append("&Years=").append(it.joinToString(",") { v -> enc(v.id) }) }
        val flags = q.toggles.filter { it in setOf("IsUnplayed", "IsResumable", "IsFavorite") }
        if (flags.isNotEmpty()) append("&Filters=").append(flags.joinToString(","))
        if ("Is4K" in q.toggles) append("&Is4K=true")
        if ("IsHD" in q.toggles) append("&IsHD=true")
    }

    /**
     * Where each first letter starts, sorted by title with [q]'s filters ('#' = numbers and symbols): for the A–Z
     * strip. Null when the server can't say up front (the strip then asks per letter, see [letterStart]).
     */
    suspend fun letterIndex(lib: MediaLibrary, type: MediaType, q: LibraryQuery): Map<Char, Int>? {
        if (lib.server == null || lib.kind != LibKind.Library) return null
        val a = store.current()
        val p = plexServers(a).firstOrNull { it.machineId == lib.server } ?: return null
        val qq = q.copy(sort = "titleSort", desc = false)
        val c = runCatching { plexGet(a, p, "/library/sections/${lib.id}/firstCharacter?" + plexQuery(qq, type)) }.getOrNull() ?: return null
        val out = LinkedHashMap<Char, Int>()
        var at = 0
        for (d in c.array("Directory")) {
            val t = (d.str("title") ?: d.str("key") ?: "").uppercase().firstOrNull() ?: continue
            val ch = if (t in 'A'..'Z') t else '#'
            if (ch !in out) out[ch] = at
            at += d.int("size") ?: 0
        }
        return out
    }

    /** How many titles (sorted by title, with [q]'s filters) come before [letter]. Null when the server can't say. */
    suspend fun letterStart(lib: MediaLibrary, type: MediaType, q: LibraryQuery, letter: Char): Int? {
        if (letter == '#') return 0
        if (lib.source == PROVIDER_LIBRARY || lib.kind != LibKind.Library) return null
        if (lib.server != null) return letterIndex(lib, type, q)?.let { idx -> idx.entries.firstOrNull { it.key != '#' && it.key >= letter }?.value ?: idx.values.maxOrNull() }
        val a = store.current()
        val emby = lib.source == "Emby"
        val srv = (if (emby) a.emby else a.jellyfin) ?: return null
        val qq = q.copy(sort = "SortName", desc = false)
        val r = runCatching {
            http.get("${srv.serverUrl}/Users/${srv.userId}/Items?" + jellyfinQuery(lib, type, qq) + "&Limit=0&NameLessThan=${enc(letter.toString())}", mediaServers.headers(emby, a.deviceId, srv.token))
        }.getOrNull()?.takeIf { it.ok } ?: return null
        return r.body.int("TotalRecordCount")
    }

    /** Finds your other Plex servers on the account (e.g. a second machine) and remembers them. */
    suspend fun refreshPlexServers() {
        val a = store.current()
        val p = a.plex ?: return
        val found = runCatching { plex.servers(a.deviceId, p.accountToken ?: p.token) }.getOrDefault(emptyList())
        if (found.isEmpty()) return
        // The main server's address doesn't answer from here (a setup made in another house): the one that does.
        found.firstOrNull { it.machineId == p.machineId && it.url != p.serverUrl }?.let { same ->
            // Back on the home address whenever that's the one that answers (an outside one taken during a slow moment
            // at home would otherwise stay, and Plex treats it as remote).
            val backHome = isHomeAddress(same.url) && !isHomeAddress(p.serverUrl)
            val answers = !backHome && withTimeoutOrNull(4_000) { runCatching { http.get("${p.serverUrl}/identity", plex.headers(a.deviceId, p.token)).ok }.getOrDefault(false) } == true
            if (!answers) {
                store.update { it.copy(plex = it.plex?.copy(serverUrl = same.url)) }
                Log.i(TAG, "plex: main server now at ${same.url}")
            }
        }
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

    /** A Plex reply's MediaContainer, or null when the server says no or can't be reached (it never throws). */
    private suspend fun plexGet(a: Accounts, p: PlexAuth, path: String): JsonElement? = try {
        http.get(p.serverUrl + path, plex.headers(a.deviceId, p.token)).takeIf { it.ok }?.body.child("MediaContainer")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

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
                                ?.array("Metadata")?.mapNotNull { plexItem(p, it, type) } // null: no answer
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
                                val added = runCatching { Instant.parse(j.str("DateCreated")).epochSecond }.getOrDefault(0L)
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

    // ------------------------------------------------------------------ your collections and playlists

    /** Each server's collections / playlists (for 10 minutes), by "<what>|<server>|<type>". */
    private val groupCache = ConcurrentHashMap<String, Timed<List<MediaLibrary>>>()
    /** What's in a collection / playlist (for 5 minutes): each title with when it was added. */
    private val groupItemsCache = BoundedCache<String, Pair<Long, List<Pair<MediaItem, Long>>>>(60)
    /** Collections and playlists seen so far, by key: a card ("col:<key>") opens its own. */
    private val groupByKey = ConcurrentHashMap<String, MediaLibrary>()

    /** The collection or playlist a card stands for ("col:<key>"), once listed. */
    fun groupFor(itemKey: String): MediaLibrary? = groupByKey[itemKey.removePrefix("col:")]

    private suspend fun cachedGroups(key: String, load: suspend () -> List<MediaLibrary>): List<MediaLibrary> {
        groupCache[key]?.takeIf { it.fresh }?.let { return it.value }
        val got = load()
        got.forEach { groupByKey[it.key] = it }
        // Nothing may mean the server didn't answer: kept 1 minute instead of 10.
        groupCache[key] = Timed.forMs(got, if (got.isEmpty()) 60_000L else 10 * 60_000L)
        return got
    }

    private fun plexSourceName(a: Accounts, p: PlexAuth) = if (plexServers(a).size > 1 && !isMainPlex(p)) "Plex · ${p.serverName}" else "Plex"

    /** A Plex server's collections in its [type] libraries, in each library's own order. */
    private suspend fun plexCollections(a: Accounts, p: PlexAuth, type: MediaType): List<MediaLibrary> = cachedGroups("pc|${p.machineId}|${type.name}") {
        val src = plexSourceName(a, p)
        coroutineScope {
            plexSections(a, p, type).mapNotNull { it.str("key") }.map { key ->
                async {
                    runCatching { plexGet(a, p, "/library/sections/$key/collections") }.getOrNull()?.array("Metadata").orEmpty().mapNotNull { j ->
                        val rk = j.str("ratingKey") ?: return@mapNotNull null
                        MediaLibrary("plexcol:${p.machineId}:$rk", j.str("title") ?: return@mapNotNull null, src, rk, p.machineId, LibKind.Collection, plexImage(p, j.str("thumb"), 240, 360))
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /** A Plex server's video playlists. */
    private suspend fun plexPlaylists(a: Accounts, p: PlexAuth): List<MediaLibrary> = cachedGroups("pp|${p.machineId}") {
        val src = plexSourceName(a, p)
        runCatching { plexGet(a, p, "/playlists?playlistType=video") }.getOrNull()?.array("Metadata").orEmpty().mapNotNull { j ->
            val rk = j.str("ratingKey") ?: return@mapNotNull null
            if ((j.int("leafCount") ?: 1) == 0) return@mapNotNull null
            MediaLibrary("plexlist:${p.machineId}:$rk", j.str("title") ?: return@mapNotNull null, src, rk, p.machineId, LibKind.Playlist, plexImage(p, j.str("thumb") ?: j.str("composite"), 240, 360))
        }
    }

    /** Jellyfin / Emby collections (box sets) or video playlists. */
    private suspend fun serverGroups(a: Accounts, srv: MediaServerAuth, emby: Boolean, kind: LibKind): List<MediaLibrary> {
        val name = if (emby) "Emby" else "Jellyfin"
        val playlist = kind == LibKind.Playlist
        return cachedGroups("sg|$name|${kind.name}") {
            val r = runCatching {
                http.get(
                    "${srv.serverUrl}/Users/${srv.userId}/Items?Recursive=true&IncludeItemTypes=${if (playlist) "Playlist" else "BoxSet"}&SortBy=SortName&Fields=ChildCount,MediaType&EnableImageTypes=Primary",
                    mediaServers.headers(emby, a.deviceId, srv.token),
                )
            }.getOrNull()?.takeIf { it.ok } ?: return@cachedGroups emptyList()
            r.body.array("Items").mapNotNull { j ->
                val id = j.str("Id") ?: return@mapNotNull null
                // Playlists of music are the Music page's.
                if (playlist && j.str("MediaType").let { it != null && it != "Video" }) return@mapNotNull null
                if ((j.int("ChildCount") ?: 1) == 0) return@mapNotNull null
                MediaLibrary("${name.lowercase()}${if (playlist) "list" else "col"}:$id", j.str("Name") ?: return@mapNotNull null, name, id, null, kind,
                    "${srv.serverUrl}/Items/$id/Images/Primary?maxHeight=360&quality=85&api_key=${enc(srv.token)}")
            }
        }
    }

    /** Every collection (or playlist) for [type] on all your servers. Jellyfin / Emby's mix films and shows: on Movies. */
    private suspend fun allGroups(a: Accounts, type: MediaType, kind: LibKind): List<MediaLibrary> = coroutineScope {
        val parts = buildList {
            plexServers(a).forEach { p ->
                add(async { runCatching { withTimeoutOrNull(12_000) { if (kind == LibKind.Collection) plexCollections(a, p, type) else if (type == MediaType.Movie) plexPlaylists(a, p) else emptyList() } }.getOrNull().orEmpty() })
            }
            if (type == MediaType.Movie) listOfNotNull(a.jellyfin?.let { it to false }, a.emby?.let { it to true }).forEach { (srv, emby) ->
                add(async { runCatching { withTimeoutOrNull(12_000) { serverGroups(a, srv, emby, kind) } }.getOrNull().orEmpty() })
            }
        }
        parts.awaitAll().flatten()
    }

    /** My Library's "Collections" and "Playlists" under each server that has some. */
    private suspend fun groupEntries(a: Accounts, type: MediaType): List<MediaLibrary> {
        val out = mutableListOf<MediaLibrary>()
        for (kind in listOf(LibKind.Collection, LibKind.Playlist)) {
            allGroups(a, type, kind).groupBy { it.source to it.server }.forEach { (src, list) ->
                val (source, server) = src
                val list_ = if (kind == LibKind.Collection) LibKind.Collections else LibKind.Playlists
                out += MediaLibrary(
                    "${list_.name.lowercase()}:${source.lowercase()}:${server.orEmpty()}:${type.name}",
                    if (kind == LibKind.Collection) "Collections" else "Playlists", source, list.size.toString(), server, list_,
                )
            }
        }
        return out
    }

    /** A card for a collection / playlist (OK opens it). */
    private fun groupCard(g: MediaLibrary, type: MediaType) = MediaItem(
        key = "col:" + g.key, type = type, title = g.title, poster = g.poster, origin = g.source,
        subtitle = if (g.kind == LibKind.Playlist) "Playlist" else "Collection",
    )

    /** What's in [g], in its own order, each with when it was added. Films and shows both (each its own type). */
    private suspend fun groupMembers(a: Accounts, g: MediaLibrary): List<Pair<MediaItem, Long>> {
        groupItemsCache[g.key]?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000L }?.let { return it.second }
        val got: List<Pair<MediaItem, Long>> = if (g.server != null) {
            val p = plexServers(a).firstOrNull { it.machineId == g.server } ?: return emptyList()
            val path = if (g.kind == LibKind.Playlist) "/playlists/${g.id}/items?includeGuids=1" else "/library/collections/${g.id}/children?includeGuids=1"
            // Not remembered when Plex couldn't be reached.
            (plexGet(a, p, path) ?: return emptyList()).array("Metadata").mapNotNull { j ->
                val added = j.long("addedAt") ?: 0L
                when (j.str("type")) {
                    "movie" -> plexItem(p, j, MediaType.Movie)
                    "show" -> plexItem(p, j, MediaType.Show)
                    // A playlist's episode: its show.
                    "episode" -> j.str("grandparentRatingKey")?.let { rk ->
                        MediaItem(key = "plex:${plexRef(p, rk)}", type = MediaType.Show, title = j.str("grandparentTitle") ?: return@mapNotNull null,
                            poster = plexImage(p, j.str("grandparentThumb"), 240, 360), backdrop = plexImage(p, j.str("grandparentArt") ?: j.str("art"), 960, 540),
                            origin = if (isMainPlex(p)) "Plex" else "Plex · ${p.serverName}")
                    }
                    else -> null
                }?.let { it to added }
            }
        } else {
            val emby = g.source == "Emby"
            val srv = (if (emby) a.emby else a.jellyfin) ?: return emptyList()
            val fields = "Fields=ProviderIds,Overview,ProductionYear,DateCreated"
            val url = if (g.kind == LibKind.Playlist) "${srv.serverUrl}/Playlists/${g.id}/Items?UserId=${srv.userId}&$fields"
                else "${srv.serverUrl}/Users/${srv.userId}/Items?ParentId=${g.id}&$fields"
            val r = runCatching { http.get(url, mediaServers.headers(emby, a.deviceId, srv.token)) }.getOrNull()?.takeIf { it.ok } ?: return emptyList()
            r.body.array("Items").mapNotNull { j ->
                val added = j.str("DateCreated")?.let { d -> runCatching { Instant.parse(d).epochSecond }.getOrNull() } ?: 0L
                when (j.str("Type")) {
                    "Movie" -> serverItem(srv, j, MediaType.Movie, g.source)
                    "Series" -> serverItem(srv, j, MediaType.Show, g.source)
                    "Episode" -> j.str("SeriesId")?.let { sid ->
                        MediaItem(key = "${g.source.lowercase()}:$sid", type = MediaType.Show, title = j.str("SeriesName") ?: return@mapNotNull null,
                            poster = "${srv.serverUrl}/Items/$sid/Images/Primary?maxHeight=360&quality=85&api_key=${enc(srv.token)}",
                            backdrop = "${srv.serverUrl}/Items/$sid/Images/Backdrop?maxWidth=960&quality=80&api_key=${enc(srv.token)}", origin = g.source)
                    }
                    else -> null
                }?.let { it to added }
            }
        }.distinctBy { it.first.key }
        groupItemsCache[g.key] = System.currentTimeMillis() to got
        return got
    }

    private fun groupOptions(lib: MediaLibrary): LibraryOptions {
        val own = SortOption("order", when (lib.kind) { LibKind.Playlist -> "Playlist order"; LibKind.Collection -> "Collection order"; else -> "As on the server" }, false)
        val sorts = if (lib.isGroup) listOf(own, SortOption("Title", "A–Z", false), SortOption("Added", "Date added"), SortOption("Released", "Release date"), SortOption("Rating", "Rating"))
            else listOf(own, SortOption("Title", "A–Z", false))
        return LibraryOptions(sorts = sorts, filters = emptyList(), letters = false, titleSort = "Title")
    }

    /** One page of a collection / playlist, or of the list of them (cards). Sorted and searched here: they're short. */
    private suspend fun groupPage(a: Accounts, lib: MediaLibrary, type: MediaType, q: LibraryQuery, start: Int, size: Int): Pair<List<MediaItem>, Int> {
        val text = q.query.trim()
        val rows: List<Pair<MediaItem, Long>> = when (lib.kind) {
            LibKind.Collections, LibKind.Playlists -> {
                val kind = if (lib.kind == LibKind.Collections) LibKind.Collection else LibKind.Playlist
                allGroups(a, type, kind).filter { it.source == lib.source && it.server == lib.server }.map { groupCard(it, type) to 0L }
            }
            else -> groupMembers(a, lib)
        }
        val found = rows.filter { text.isEmpty() || it.first.title.contains(text, ignoreCase = true) }
        val sorted = when (q.sort) {
            "Title" -> found.sortedBy { it.first.title.lowercase().removePrefix("the ") }
            "Added" -> found.sortedBy { it.second }
            "Released" -> found.sortedBy { it.first.year ?: 0 }
            "Rating" -> found.sortedBy { it.first.rating ?: 0.0 }
            else -> found
        }.let { if (q.desc) it.reversed() else it }.map { it.first }
        return sorted.drop(start).take(size) to sorted.size
    }

    /** Your collections and playlists as rows of cards, and each collection you've made a row of (hold OK on its card). */
    private suspend fun groupRows(a: Accounts, type: MediaType): List<MediaRow> = coroutineScope {
        val cols = async { allGroups(a, type, LibKind.Collection) }
        val lists = async { if (type == MediaType.Movie) allGroups(a, type, LibKind.Playlist) else emptyList() }
        val chosen = rowLayouts.layout(type).first().hidden.filter { it.startsWith("+col-") }.map { it.removePrefix("+col-") }
        val c = cols.await(); val l = lists.await()
        val own = chosen.mapNotNull { k -> (c + l).firstOrNull { it.key == k } }.map { g ->
            async { MediaRow("col-${g.key}", g.title, runCatching { withTimeoutOrNull(15_000) { groupMembers(a, g) } }.getOrNull().orEmpty().map { it.first }) }
        }
        listOfNotNull(
            c.takeIf { it.isNotEmpty() }?.let { MediaRow("groups-col-${type.name}", "Your collections", it.map { g -> groupCard(g, type) }) },
            l.takeIf { it.isNotEmpty() }?.let { MediaRow("groups-list-${type.name}", "Your playlists", it.map { g -> groupCard(g, type) }) },
        ) + own.awaitAll()
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

    /** Where a row comes from, by its id: Plex, Jellyfin, Emby, an add-on (AIOStreams, AIOMetadata…), Trakt, TMDB, IPTV. */
    fun rowOrigin(id: String): String {
        addonRowSources[id]?.let { return it.name }
        return when {
            id.startsWith("merged:") -> "Your servers"
            id.startsWith("groups-") -> "Your servers"
            id.startsWith("col-") -> when { id.startsWith("col-plex") -> "Plex"; id.startsWith("col-emby") -> "Emby"; else -> "Jellyfin" }
            id.startsWith("plex-") -> "Plex"
            id.startsWith("Jellyfin-") -> "Jellyfin"
            id.startsWith("Emby-") -> "Emby"
            id.startsWith("trakt-") -> "Trakt"
            id.startsWith("upcoming-") -> "Trakt / TMDB"
            id.startsWith("tmdb-") -> "TMDB"
            id.startsWith("xc-") -> "IPTV"
            id.startsWith("stremio-") -> "Add-on"
            else -> "Viewarr"
        }
    }

    /** The addon each addon row came from, for [rowOrigin]. */
    private val addonRowSources = ConcurrentHashMap<String, AddonInfo>()

    // (Off the main thread: an addon's catalogues parsed there cost ~0.8 s of it while browsing. Peter, 2026-11-19.)
    private suspend fun stremioRows(addon: AddonInfo, type: MediaType, part: suspend (List<MediaRow>) -> Unit = {}): List<MediaRow> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { stremioRowsHere(addon, type, part) }

    private suspend fun stremioRowsHere(addon: AddonInfo, type: MediaType, part: suspend (List<MediaRow>) -> Unit): List<MediaRow> {
        val stremioType = if (type == MediaType.Movie) "movie" else "series"
        // Shows also take "anime" catalogues (e.g. One Pace Premium); Movies take the single-play kinds sports and
        // live-event addons use ("tv", "channel", "events"…): normal Stremio types, each asked for under its own name.
        val wanted = if (type == MediaType.Movie) ADDON_SINGLE_TYPES + "movie" else setOf("series", "anime")
        val manifest = http.get("${addon.baseUrl}/manifest.json").takeIf { it.ok }?.body ?: return emptyList()
        val usable = manifest.array("catalogs").filter { c ->
            c.str("type") in wanted &&
                c.array("extra").none { e -> e.str("isRequired") == "true" } &&
                c.array("extraRequired").isEmpty()
        }
        // Network rows ("Netflix · Popular", two or more per name): all of them, each network having its own service
        // page in the menu (the first 8 only gave the first couple of networks). Other catalogues: the first 8, as before.
        fun setName(c: JsonElement) = c.str("name")?.substringBefore(" · ", "")?.trim()?.takeIf { it.isNotEmpty() }
        val grouped = usable.mapNotNull(::setName).groupingBy { it }.eachCount().filter { it.value >= 2 }.keys
        val network = usable.filter { setName(it) in grouped }
        val catalogs = usable.filter { setName(it) !in grouped }.take(8) + network
        // A few at a time (an addon can have a hundred). Groups: its other catalogues, then each network's rows; as a
        // group completes, everything so far is handed over, so networks (and their menu entries) appear one by one
        // instead of all waiting for the slowest catalogue.
        val gate = Semaphore(6)
        val done = ConcurrentHashMap<Int, MediaRow>()
        val groupOf = catalogs.map { setName(it) ?: "" }
        val left = ConcurrentHashMap(groupOf.groupingBy { it }.eachCount())
        val handOver = Mutex()
        coroutineScope {
            catalogs.forEachIndexed { i, c ->
                launch {
                    val id = c.str("id")
                    val catType = c.str("type") ?: stremioType
                    val r = if (id == null) null else gate.withPermit {
                        withTimeoutOrNull(10_000) { runCatching { http.get("${addon.baseUrl}/catalog/$catType/${enc(id)}.json") }.getOrNull() }
                    }?.takeIf { it.ok }
                    if (r != null && id != null) {
                        val take = if (setName(c) in grouped) 20 else 40
                        val items = r.body.array("metas").take(take).mapNotNull { m -> stremioMeta(addon, m, type, catType) }
                        val rowId = "stremio-${addon.id}-$id-$catType-${type.name}"
                        addonRowSources[rowId] = addon
                        done[i] = MediaRow(rowId, c.str("name") ?: addon.name, items)
                    }
                    // This catalogue's group finished: show what's in so far.
                    if (left.merge(groupOf[i], -1) { a, b -> a + b } == 0) handOver.withLock { part(done.toSortedMap().values.toList()) }
                }
            }
        }
        return done.toSortedMap().values.toList()
    }

    private fun stremioMeta(addon: AddonInfo, m: JsonElement, type: MediaType, catType: String? = null): MediaItem? {
        val metaId = m.str("id") ?: return null
        return MediaItem(
            addonType = catType?.takeIf { it != "movie" && it != "series" },
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

    /** Answers already worked out this run (title key → copies), kept for 10 minutes; 1 minute when a server didn't answer. */
    private val copyCache = BoundedCache<String, Timed<List<LibraryCopy>>>(300)
    private val plexSectionCache = ConcurrentHashMap<String, Pair<Long, List<JsonElement>>>()

    /**
     * Which of the user's own servers have this title. Each server is asked about just this title (by name, then
     * matched on its TMDB / IMDb id), so it's quick however big the libraries are. Reading whole libraries instead
     * took minutes, or ran the TV out of memory, on big Plex servers.
     */
    suspend fun copiesOf(item: MediaItem): List<LibraryCopy> {
        copyCache[item.key]?.takeIf { it.fresh }?.let { return it.value }
        val a = store.current()
        // null = that server timed out or failed (not "it hasn't got it").
        val answers = coroutineScope {
            val jobs = buildList {
                plexServers(a).forEach { p -> add(async { runCatching { withTimeoutOrNull(15_000) { plexCopiesOf(a, p, item) } }.getOrNull() }) }
                listOfNotNull(a.jellyfin?.let { it to false }, a.emby?.let { it to true }).forEach { (srv, emby) ->
                    add(async { runCatching { withTimeoutOrNull(15_000) { serverCopies(a, srv, emby, item) } }.getOrNull() })
                }
                add(async { runCatching { withTimeoutOrNull(20_000) { providerCopies(item) } }.getOrNull() })
            }
            jobs.awaitAll()
        }
        // One copy per source, but every Plex server's own: if one server's file has gone, another's can play.
        val found = answers.flatMap { it.orEmpty() }
            .distinctBy { it.source + "|" + (if (it.source == "Plex") it.ref?.substringAfter('@', "") else "") + "|" + it.version?.plexMedia + "|" + it.version?.sourceId }
        // A server that didn't answer would show the title as "not in your library" for 10 minutes: ask again sooner.
        copyCache[item.key] = Timed.forMs(found, if (answers.any { it == null }) 60_000L else 10 * 60_000L)
        return found
    }

    /** True when a server's entry is this title: same TMDB or IMDb id, or (no ids to go on) same name and year. */
    private fun sameTitle(item: MediaItem, tmdbId: Int?, imdbId: String?, name: String?, year: Int?): Boolean {
        if (item.tmdbId != null && tmdbId != null) return item.tmdbId == tmdbId
        if (item.imdbId != null && imdbId != null) return item.imdbId == imdbId
        if (name == null || !name.equals(item.title, ignoreCase = true)) return false
        return item.year == null || year == null || abs(item.year - year) <= 1
    }

    /** Names to ask for: the title, and its part before a colon ("Batman: Year One" is "Batman Year One" on some servers). */
    private fun searchNames(title: String): List<String> {
        // Also without accents: "Filipiñana" may be stored as "Filipinana".
        val plain = Normalizer.normalize(title, Normalizer.Form.NFD).replace(MARKS, "")
        return listOf(title, plain, title.substringBefore(':').trim()).filter { it.length >= 2 }.distinct()
    }

    /**
     * A Plex title's copies: one per version (Plex keeps a 4K and a 1080p file as two "Media" of one title), each with
     * what it is (from the item's full details: resolution, HDR, sound, size).
     */
    private fun plexCopies(p: PlexAuth, j: JsonElement?): List<LibraryCopy> {
        val ref = j.str("ratingKey")?.let { plexRef(p, it) }
        val server = p.serverName.takeIf { !isMainPlex(p) }
        val medias = j.array("Media")
        if (medias.isEmpty()) return listOf(LibraryCopy("Plex", server, ref))
        return medias.mapIndexed { i, m ->
            val part = m.array("Part").firstOrNull()
            val streams = part.array("Stream")
            val video = streams.firstOrNull { it.int("streamType") == 1 }
            val audios = streams.filter { it.int("streamType") == 2 }
            val audio = audios.firstOrNull { it.str("selected") == "true" || it.int("selected") == 1 } ?: audios.firstOrNull()
            val vTitle = listOfNotNull(video.str("displayTitle"), video.str("extendedDisplayTitle")).joinToString(" ")
            val aTitle = listOfNotNull(audio.str("displayTitle"), audio.str("extendedDisplayTitle"), audio.str("title")).joinToString(" ")
            val dv = video.str("DOVIPresent").let { it == "1" || it == "true" } || vTitle.contains("DoVi", true) || vTitle.contains("Dolby Vision", true)
            val hdr = when {
                dv -> "Dolby Vision"
                vTitle.contains("HDR10+") -> "HDR10+"
                video.str("colorTrc") == "smpte2084" || vTitle.contains("HDR") -> "HDR10"
                video.str("colorTrc") == "arib-std-b67" || vTitle.contains("HLG") -> "HLG"
                else -> null
            }
            // The picture's class (1080, 720…): Plex's own word for it, else worked out from the width as well as the
            // height. A wide film is 1920 x 800: by its height alone it was labelled 720p (Peter, 2026-11-20: Inception).
            val height = m.str("videoResolution")?.lowercase()?.let { r -> when (r) { "4k" -> 2160; "8k" -> 4320; "sd" -> 480; else -> r.toIntOrNull() } }
                ?: pictureClass(video.int("width") ?: m.int("width"), video.int("height") ?: m.int("height"))
            val atmos = aTitle.contains("Atmos", true)
            val channels = audio.int("channels") ?: m.int("audioChannels")
            val v = CopyVersion(
                height = height, hdr = hdr, dvOnly = dv && video.int("DOVIProfile") == 5,
                videoCodec = m.str("videoCodec") ?: video.str("codec"), container = m.str("container"),
                audio = VersionPick.audioLabel(audio.str("codec") ?: m.str("audioCodec"), audio.str("profile"), channels, atmos),
                audioChannels = channels, atmos = atmos,
                kbps = m.int("bitrate"), sizeBytes = part.long("size"),
                edition = j.str("editionTitle"), plexMedia = i,
            )
            LibraryCopy("Plex", listOfNotNull(v.resolution, server).joinToString(" · ").ifEmpty { null }, ref, v)
        }
    }

    /** A Plex item's full details (its versions' streams), else what was already found. */
    private suspend fun plexFull(a: Accounts, p: PlexAuth, j: JsonElement?): JsonElement? {
        if (j.array("Media").firstOrNull()?.array("Part")?.firstOrNull()?.array("Stream")?.isNotEmpty() == true) return j
        val rk = j.str("ratingKey") ?: return j
        return runCatching { plexGet(a, p, "/library/metadata/$rk") }.getOrNull()?.array("Metadata")?.firstOrNull() ?: j
    }

    private suspend fun plexCopiesOf(a: Accounts, p: PlexAuth, item: MediaItem): List<LibraryCopy> {
        // A title from a Plex row already knows where it is.
        if (item.key.startsWith("plex:")) {
            val (server, rk) = plexResolve(a, item.key.removePrefix("plex:")) ?: return emptyList()
            if (server.machineId != p.machineId) return emptyList()
            val m = plexGet(a, p, "/library/metadata/$rk")?.array("Metadata")?.firstOrNull() ?: return listOf(LibraryCopy("Plex", null, plexRef(p, rk)))
            return if (item.type == MediaType.Movie) plexCopies(p, m) else listOf(plexCopies(p, m).first())
        }
        val sections = (plexSectionCache[p.machineId]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.second
            ?: plexGet(a, p, "/library/sections")?.array("Directory")?.also { plexSectionCache[p.machineId] = System.currentTimeMillis() to it }
            ?: throw IOException("${p.serverName} didn't answer")) // a failure, not "no copy" (see copiesOf)
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
            if (match != null) return if (item.type == MediaType.Movie) plexCopies(p, plexFull(a, p, match)) else listOf(plexCopies(p, match).first())
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
        return if (item.type == MediaType.Movie) plexCopies(p, full) else listOf(plexCopies(p, full).first())
    }

    private suspend fun serverCopies(a: Accounts, srv: MediaServerAuth, emby: Boolean, item: MediaItem): List<LibraryCopy> {
        val source = if (emby) "Emby" else "Jellyfin"
        val prefix = source.lowercase() + ":"
        if (item.key.startsWith(prefix)) return serverVersions(a, srv, emby, item, item.key.removePrefix(prefix))
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
            if (match != null) return match.str("Id")?.let { serverVersions(a, srv, emby, item, it) }.orEmpty()
        }
        return emptyList()
    }

    /** A Jellyfin / Emby film's copies: one per media source (version), with what each is. Shows: just the one. */
    private suspend fun serverVersions(a: Accounts, srv: MediaServerAuth, emby: Boolean, item: MediaItem, id: String): List<LibraryCopy> {
        val source = if (emby) "Emby" else "Jellyfin"
        if (item.type != MediaType.Movie) return listOf(LibraryCopy(source, null, id))
        val info = runCatching { http.get("${srv.serverUrl}/Users/${srv.userId}/Items/$id?Fields=MediaSources", mediaServers.headers(emby, a.deviceId, srv.token)) }
            .getOrNull()?.takeIf { it.ok }?.body
        val sources = info.array("MediaSources")
        if (sources.isEmpty()) return listOf(LibraryCopy(source, null, id))
        return sources.map { ms ->
            val streams = ms.array("MediaStreams")
            val video = streams.firstOrNull { it.str("Type") == "Video" }
            val audios = streams.filter { it.str("Type") == "Audio" }
            val audio = audios.firstOrNull { it.str("IsDefault") == "true" } ?: audios.firstOrNull()
            val range = video.str("VideoRangeType").orEmpty()
            val hdr = when {
                range.startsWith("DOVI") -> "Dolby Vision"
                range == "HDR10Plus" -> "HDR10+"
                range == "HDR10" || video.str("VideoRange") == "HDR" -> "HDR10"
                range == "HLG" -> "HLG"
                else -> null
            }
            val aText = listOfNotNull(audio.str("Profile"), audio.str("DisplayTitle")).joinToString(" ")
            val atmos = aText.contains("Atmos", true)
            val channels = audio.int("Channels")
            val v = CopyVersion(
                // (The server's own word for the picture first, as with Plex: Jellyfin and Emby put it at the start of
                // the video stream's display title, "1080p HEVC SDR", "4K HEVC HDR". Else from its width and height.)
                height = serverResolution(video.str("DisplayTitle")) ?: pictureClass(video.int("Width"), video.int("Height")),
                hdr = hdr, dvOnly = range == "DOVI",
                videoCodec = video.str("Codec"), container = ms.str("Container"),
                audio = VersionPick.audioLabel(audio.str("Codec"), audio.str("Profile"), channels, atmos),
                audioChannels = channels, atmos = atmos,
                kbps = ms.long("Bitrate")?.let { (it / 1000).toInt() }, sizeBytes = ms.long("Size"),
                // Several versions: each one's own name ("… - Director's Cut").
                edition = ms.str("Name")?.substringAfterLast(" - ", "")?.takeIf { sources.size > 1 && it.isNotBlank() },
                sourceId = ms.str("Id"),
            )
            LibraryCopy(source, v.resolution, id, v)
        }
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
                        XtreamClient.cleanTitle(h.str("name").orEmpty()).equals(item.title, ignoreCase = true) &&
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
    suspend fun plexStream(ref: String, watch: WatchTarget? = null, mediaIndex: Int? = null): VodStream? {
        val a = store.current()
        val (p, ratingKey) = plexResolve(a, ref) ?: return null
        // checkFiles: Plex says whether the file is really there (a library entry can outlive its file).
        // includeMarkers: where the intro and credits are (Plex detects them), for Skip intro / credits.
        val m = plexGet(a, p, "/library/metadata/$ratingKey?checkFiles=1&includeMarkers=1")?.array("Metadata")?.firstOrNull() ?: return null
        val markers = m.array("Marker").mapNotNull { mk ->
            val kind = when (mk.str("type")) { "intro" -> SkipMarker.Kind.Intro; "credits" -> SkipMarker.Kind.Credits; else -> return@mapNotNull null }
            val st = mk.long("startTimeOffset") ?: return@mapNotNull null
            val en = mk.long("endTimeOffset") ?: return@mapNotNull null
            SkipMarker(kind, st, en).takeIf { en > st }
        }
        // The version asked for (4K or 1080p…), else the first.
        val idx = mediaIndex?.takeIf { it in m.array("Media").indices } ?: 0
        val media = m.array("Media").getOrNull(idx)
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
        // The file's frame rate (its video track's), for Match frame rate before it starts.
        val fps = partObj.array("Stream").firstOrNull { it.int("streamType") == 1 }?.str("frameRate")?.toFloatOrNull()?.takeIf { it > 0f }
            // (Not listed exactly, as on some shared servers: Plex's own label for every file, "24p", "PAL"…)
            ?: when (media?.str("videoFrameRate")?.lowercase()) {
                "24p" -> 23.976f; "pal" -> 25f; "ntsc" -> 29.97f; "50p" -> 50f; "60p" -> 59.94f; else -> null
            }
        val target = (watch ?: WatchTarget(MediaType.Movie)).copy(plexRatingKey = ref)
        val quality = playbackStore.current().plexQuality
        val transcode = VodStream(plexTranscodeUrl(a, p, ratingKey, quality.maxKbps ?: 20_000, idx), "$title (converted by Plex)", headers, resume, watch = target, thumbs = thumbs, markers = markers, from = "your library: ${p.serverName} · transcoding", fps = fps)
        // Settings → Playback → Plex quality: anything but Original always converts at that bitrate.
        if (quality.maxKbps != null) return transcode
        val direct = VodStream("${p.serverUrl}$part?X-Plex-Token=${enc(p.token)}", title, headers, resume, fallback = transcode, watch = target, thumbs = thumbs, markers = markers, from = "your library: ${p.serverName} · direct play", fps = fps)
        val playable = DeviceCodecs.canPlay(media.str("videoCodec"), media.str("container"))
        Log.i(TAG, "plex $ratingKey video=${media.str("videoCodec")} container=${media.str("container")} direct=$playable")
        return if (playable) direct else transcode
    }

    private fun plexTranscodeUrl(a: Accounts, p: PlexAuth, ratingKey: String, maxKbps: Int, mediaIndex: Int = 0): String {
        val q = linkedMapOf(
            "path" to "/library/metadata/$ratingKey",
            "mediaIndex" to mediaIndex.toString(), "partIndex" to "0",
            "protocol" to "hls", "fastSeek" to "1",
            "directPlay" to "0", "directStream" to "1", "directStreamAudio" to "1",
            "videoQuality" to "100", "maxVideoBitrate" to maxKbps.toString(), "videoResolution" to "1920x1080",
            "subtitles" to "auto", "location" to "lan",
            "session" to UUID.randomUUID().toString(),
            "X-Plex-Token" to p.token,
            "X-Plex-Client-Identifier" to a.deviceId,
            "X-Plex-Product" to "Viewarr", "X-Plex-Platform" to "Android", "X-Plex-Device" to "Android TV",
        )
        return "${p.serverUrl}/video/:/transcode/universal/start.m3u8?" + q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    // ------------------------------------------------------------------ a film's tracks, and marking it watched

    /** The film's audio and subtitle tracks on your first Plex / Jellyfin / Emby copy (null when there isn't one). */
    suspend fun titleTracks(copies: List<LibraryCopy>): TitleTracks? {
        val a = store.current()
        copies.firstOrNull { it.source == "Plex" && it.ref != null }?.let { c ->
            val (p, rk) = plexResolve(a, c.ref!!) ?: return@let
            val media = plexGet(a, p, "/library/metadata/$rk")?.array("Metadata")?.firstOrNull()?.array("Media")?.firstOrNull()
            val streams = media?.array("Part")?.firstOrNull()?.array("Stream").orEmpty()
                // The file's own tracks only (separate subtitle files aren't in what the player opens).
                .filter { it.str("key") == null }
            fun pick(type: Int) = streams.filter { it.int("streamType") == type }.mapIndexed { i, st ->
                val title = st.str("extendedDisplayTitle") ?: st.str("displayTitle")
                TrackChoice(
                    title ?: st.str("language") ?: "Track ${i + 1}", i, st.str("selected").let { it == "1" || it == "true" },
                    format = if (type == 2) audioFormat(st.str("codec"), st.str("profile"), title) else null,
                    channels = if (type == 2) channelLabel(st.int("channels")) else null,
                )
            }
            val video = streams.firstOrNull { it.int("streamType") == 1 }
            val vTitle = video?.str("displayTitle").orEmpty() + " " + video?.str("extendedDisplayTitle").orEmpty()
            val hdr = when {
                video?.str("DOVIPresent").let { it == "1" || it == "true" } || vTitle.contains("DoVi", true) || vTitle.contains("Dolby Vision", true) -> "Dolby Vision"
                vTitle.contains("HDR10+", true) -> "HDR10+"
                video?.str("colorTrc") == "smpte2084" || vTitle.contains("HDR", true) -> "HDR10"
                video?.str("colorTrc") == "arib-std-b67" -> "HLG"
                else -> null
            }
            return TitleTracks("Plex", c.ref, pick(2), pick(3), resolutionLabel(media?.str("videoResolution"), video?.int("height")), hdr)
        }
        copies.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }?.let { c ->
            val (srv, emby) = serverFor(c.source) ?: return@let
            val info = runCatching { http.get("${srv.serverUrl}/Users/${srv.userId}/Items/${c.ref}?Fields=MediaSources", mediaServers.headers(emby, a.deviceId, srv.token)) }
                .getOrNull()?.takeIf { it.ok }?.body ?: return@let
            val streams = info.array("MediaSources").firstOrNull()?.array("MediaStreams").orEmpty()
                .filter { it.child("IsExternal")?.toString() != "true" }
            fun pick(type: String) = streams.filter { it.str("Type") == type }.mapIndexed { i, st ->
                val title = st.str("DisplayTitle")
                TrackChoice(
                    title ?: st.str("Language") ?: "Track ${i + 1}", i, st.child("IsDefault")?.toString() == "true",
                    format = if (type == "Audio") audioFormat(st.str("Codec"), st.str("Profile"), title) else null,
                    channels = if (type == "Audio") channelLabel(st.int("Channels")) else null,
                )
            }
            val video = streams.firstOrNull { it.str("Type") == "Video" }
            val range = video?.str("VideoRangeType").orEmpty()
            val hdr = when {
                range.startsWith("DOVI", true) -> "Dolby Vision"
                range.equals("HDR10Plus", true) -> "HDR10+"
                range.equals("HDR10", true) || (range.isEmpty() && video?.str("VideoRange") == "HDR") -> "HDR10"
                range.equals("HLG", true) -> "HLG"
                else -> null
            }
            return TitleTracks(c.source, c.ref!!, pick("Audio"), pick("Subtitle"), resolutionLabel(null, video?.int("Height")), hdr)
        }
        return null
    }

    /** Jellyfin's / Emby's own resolution from a video stream's display title ("1080p HEVC SDR", "4K …", "SD …"). */
    private fun serverResolution(displayTitle: String?): Int? {
        val t = displayTitle ?: return null
        Regex("""\b(\d{3,4})[pi]\b""").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        return when {
            Regex("""\b8K\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 4320
            Regex("""\b4K\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 2160
            Regex("""\bSD\b""").containsMatchIn(t) -> 480
            else -> null
        }
    }

    /**
     * A picture's height for naming its quality: the taller of its real height and what a 16:9 picture of its width
     * would be, so a wide film (1920 x 800) counts as 1080, not 720.
     */
    private fun pictureClass(width: Int?, height: Int?): Int? {
        val byWidth = width?.takeIf { it > 0 }?.let { (it * 9 + 8) / 16 }
        return listOfNotNull(height?.takeIf { it > 0 }, byWidth).maxOrNull()
    }

    /** "4K", "1080p", "720p" or "SD", from Plex's videoResolution ("4k", "1080", "sd"…) or the picture's height. */
    private fun resolutionLabel(plex: String?, height: Int?): String? {
        val h = plex?.lowercase()?.let { r -> when (r) { "4k" -> 2160; "8k" -> 4320; "sd" -> 480; else -> r.toIntOrNull() } } ?: height ?: return null
        return when {
            h >= 4000 -> "8K"
            h >= 1800 -> "4K"
            h >= 1000 -> "1080p"
            h >= 700 -> "720p"
            else -> "SD"
        }
    }

    /** A soundtrack's format for people: "Dolby Atmos", "DTS:X", "DTS-HD MA", "TrueHD", "Dolby Digital+", "AAC"… */
    private fun audioFormat(codec: String?, profile: String?, title: String?): String? {
        val c = codec?.lowercase() ?: return null
        val all = (profile.orEmpty() + " " + title.orEmpty()).lowercase()
        return when {
            "atmos" in all -> "Dolby Atmos"
            c.startsWith("dts") && ("dts:x" in all || "dts-x" in all || profile.equals("x", true)) -> "DTS:X"
            c.startsWith("dts") && ("ma" == profile?.lowercase() || "dts-hd ma" in all || "master audio" in all) -> "DTS-HD MA"
            c.startsWith("dts") -> "DTS"
            c == "truehd" -> "TrueHD"
            c == "eac3" -> "Dolby Digital+"
            c == "ac3" -> "Dolby Digital"
            c == "aac" -> "AAC"
            c == "flac" -> "FLAC"
            c.startsWith("pcm") -> "PCM"
            c == "opus" -> "Opus"
            c == "mp3" -> "MP3"
            else -> c.uppercase()
        }
    }

    private fun channelLabel(n: Int?): String? = when (n) {
        null, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        3 -> "2.1"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "${n}ch"
    }

    /** Whether you've watched this film: on any of your servers, or on Trakt (this box's own record: LocalWatchStore). */
    suspend fun filmWatched(item: MediaItem, copies: List<LibraryCopy>): Boolean {
        val a = store.current()
        for (c in copies) {
            val ref = c.ref ?: continue
            when (c.source) {
                "Plex" -> {
                    val (p, rk) = plexResolve(a, ref) ?: continue
                    val m = runCatching { plexGet(a, p, "/library/metadata/$rk") }.getOrNull()?.array("Metadata")?.firstOrNull()
                    if ((m.int("viewCount") ?: 0) > 0) return true
                }
                "Jellyfin", "Emby" -> {
                    val (srv, emby) = serverFor(c.source) ?: continue
                    val info = runCatching { http.get("${srv.serverUrl}/Users/${srv.userId}/Items/$ref", mediaServers.headers(emby, a.deviceId, srv.token)) }.getOrNull()?.takeIf { it.ok }?.body
                    if (info.child("UserData").child("Played")?.toString() == "true") return true
                }
            }
        }
        runCatching { traktWatched() }
        return traktWatchedMovie(item)
    }

    /**
     * Marks a film watched (or not) everywhere it's yours: each Plex copy, Jellyfin / Emby, and Trakt (connected, with
     * Send plays to Trakt on). Returns where it took ("Trakt", "Plex", …); empty: tried, none took it; null: nowhere
     * to try (no server copy, no Trakt).
     */
    suspend fun setFilmWatched(item: MediaItem, copies: List<LibraryCopy>, watched: Boolean): List<String>? {
        val a = store.current()
        val took = LinkedHashSet<String>()
        var tried = false
        for (c in copies) {
            val ref = c.ref ?: continue
            when (c.source) {
                "Plex" -> {
                    val (p, rk) = plexResolve(a, ref) ?: continue
                    val verb = if (watched) "scrobble" else "unscrobble"
                    tried = true
                    if (runCatching { http.get("${p.serverUrl}/:/$verb?identifier=com.plexapp.plugins.library&key=$rk", plex.headers(a.deviceId, p.token)).ok }.getOrDefault(false)) took += "Plex"
                }
                "Jellyfin", "Emby" -> {
                    val (srv, emby) = serverFor(c.source) ?: continue
                    val url = "${srv.serverUrl}/Users/${srv.userId}/PlayedItems/$ref"
                    val h = mediaServers.headers(emby, a.deviceId, srv.token)
                    tried = true
                    if (runCatching { (if (watched) http.post(url, null, h) else http.delete(url, h)).ok }.getOrDefault(false)) took += c.source
                }
            }
        }
        val t = a.trakt?.takeIf { io.github.petebutty.viewarr.data.sync.WatchSource.sendToTrakt }?.let { freshTrakt(it) }
        if (t != null && (item.tmdbId != null || !item.imdbId.isNullOrBlank())) {
            tried = true
            val ids = buildJsonObject {
                item.tmdbId?.let { put("tmdb", it) }
                item.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
            }
            val body = buildJsonObject { put("movies", buildJsonArray { add(buildJsonObject { put("ids", ids) }) }) }
            val url = if (watched) "https://api.trakt.tv/sync/history" else "https://api.trakt.tv/sync/history/remove"
            val sent = runCatching { http.post(url, body, trakt.headers(t.accessToken)).ok }.getOrDefault(false)
            if (sent) took += "Trakt"
            // (Our own copy changed in place, not the whole history fetched again: four marks in a row did four full
            // downloads and ran the AM9 out of memory. Peter, 2026-11-19.)
            if (sent) runCatching { traktMarkLocal(t, item, null, null, watched) }
        }
        return if (!tried) null else took.toList()
    }

    /**
     * One episode watched / not watched on your servers and Trakt (the title page's episode options): Plex by the
     * episode's own key on the show's server, Jellyfin / Emby by the episode's id, Trakt by the show's TMDB id with
     * season and number. Returns where it took, as [setFilmWatched] (null: nowhere to try).
     */
    suspend fun setEpisodeWatched(item: MediaItem, copies: List<LibraryCopy>, season: Int, episode: Int,
                                  plexKey: String?, serverId: String?, watched: Boolean): List<String>? {
        val a = store.current()
        val took = LinkedHashSet<String>()
        var tried = false
        val verb = if (watched) "scrobble" else "unscrobble"
        if (plexKey != null) copies.firstOrNull { it.source == "Plex" }?.ref?.let { ref ->
            plexResolve(a, ref)?.first?.let { p ->
                tried = true
                if (runCatching { http.get("${p.serverUrl}/:/$verb?identifier=com.plexapp.plugins.library&key=$plexKey", plex.headers(a.deviceId, p.token)).ok }.getOrDefault(false)) took += "Plex"
            }
        }
        if (serverId != null) copies.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }?.let { c ->
            serverFor(c.source)?.let { (srv, emby) ->
                val url = "${srv.serverUrl}/Users/${srv.userId}/PlayedItems/$serverId"
                val h = mediaServers.headers(emby, a.deviceId, srv.token)
                tried = true
                if (runCatching { (if (watched) http.post(url, null, h) else http.delete(url, h)).ok }.getOrDefault(false)) took += c.source
            }
        }
        val t = a.trakt?.takeIf { io.github.petebutty.viewarr.data.sync.WatchSource.sendToTrakt }?.let { freshTrakt(it) }
        val tmdb = item.tmdbId
        if (t != null && tmdb != null) {
            tried = true
            val body = buildJsonObject {
                putJsonArray("shows") {
                    add(buildJsonObject {
                        putJsonObject("ids") { put("tmdb", tmdb) }
                        putJsonArray("seasons") {
                            add(buildJsonObject {
                                put("number", season)
                                putJsonArray("episodes") { add(buildJsonObject { put("number", episode) }) }
                            })
                        }
                    })
                }
            }
            val url = if (watched) "https://api.trakt.tv/sync/history" else "https://api.trakt.tv/sync/history/remove"
            val sent = runCatching { http.post(url, body, trakt.headers(t.accessToken)).ok }.getOrDefault(false)
            if (sent) took += "Trakt"
            if (sent) runCatching { traktMarkLocal(t, item, season, episode, watched) }
        }
        return if (!tried) null else took.toList()
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

    /** A Jellyfin / Emby film or episode: the file itself (mpv plays almost anything), resuming where you left off. */
    suspend fun serverStream(source: String, itemId: String, title: String, watch: WatchTarget?, sourceId: String? = null): VodStream? {
        val (s, emby) = serverFor(source) ?: return null
        val a = store.current()
        val info = runCatching { http.get("${s.serverUrl}/Users/${s.userId}/Items/$itemId", mediaServers.headers(emby, a.deviceId, s.token)) }
            .getOrNull()?.takeIf { it.ok }?.body
        val resumeMs = (info.child("UserData").long("PlaybackPositionTicks") ?: 0L) / 10_000
        // The file's frame rate (the chosen version's video stream), for Match frame rate before it starts.
        val src = info.array("MediaSources").let { l -> l.firstOrNull { sourceId != null && it.str("Id") == sourceId } ?: l.firstOrNull() }
        val video = src.array("MediaStreams").firstOrNull { it.str("Type") == "Video" }
        val fps = (video.child("RealFrameRate") as? JsonPrimitive)?.content?.toFloatOrNull()?.takeIf { it > 0f }
            ?: (video.child("AverageFrameRate") as? JsonPrimitive)?.content?.toFloatOrNull()?.takeIf { it > 0f }
        return VodStream(
            fps = fps,
            // Your own server: "from your library: Jellyfin" on the info bar (addons and IPTV keep their names).
            // (Always the file itself, never converted: direct play.)
            from = "your library: " + (if (emby) "Emby" else "Jellyfin") + " · direct play",
            markers = serverMarkers(s, emby, itemId, info),
            thumbs = serverThumbs(s, emby, itemId, info),
            // (A chosen version: its media source; otherwise the server's default.)
            url = "${s.serverUrl}/Videos/$itemId/stream?static=true" + (sourceId?.let { "&MediaSourceId=${enc(it)}" } ?: "") + "&api_key=${enc(s.token)}",
            title = title,
            resumeMs = resumeMs,
            watch = (watch ?: WatchTarget(MediaType.Movie)).copy(serverSource = source, serverItemId = itemId),
        )
    }

    private val introDbCache = BoundedCache<String, Timed<List<SkipMarker>>>(500)

    /**
     * Intro and credits from the community databases, for a film or episode whose own server has no markers (addon
     * streams, IPTV, a Plex without intro detection): TheIntroDB first (by IMDb id, else TMDB), then IntroDB (IMDb).
     * Only the title's id and episode number are sent. Empty when neither knows it.
     */
    suspend fun communityMarkers(w: WatchTarget): List<SkipMarker> {
        val episode = w.type == MediaType.Show
        if (episode && (w.season == null || w.episode == null)) return emptyList()
        val key = listOf(w.imdbId, w.tmdbId, w.type, w.season, w.episode).joinToString("|")
        introDbCache[key]?.takeIf { it.fresh }?.let { return it.value }
        // A timeout or server error isn't "no markers": remembered only briefly.
        var failed = false
        val ep = if (episode) "&season=${w.season}&episode=${w.episode}" else ""
        fun ms(j: JsonElement?, k: String) = (j.child(k) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
        // TheIntroDB: "intro" and "credits" lists; a missing start is the beginning, a missing end the end of the file.
        val id = w.imdbId?.let { "imdb_id=$it" } ?: w.tmdbId?.let { "tmdb_id=$it&type=${if (episode) "tv" else "movie"}" }
        val tidb = id?.let { q ->
            val r = withTimeoutOrNull(8_000) { runCatching { http.get("https://api.theintrodb.org/v4/media?$q$ep") }.getOrNull() }
            if (r == null || r.code >= 500) failed = true
            r?.takeIf { it.ok }?.body
        }
        fun list(arr: List<JsonElement>, kind: SkipMarker.Kind) = arr.mapNotNull { j ->
            val st = ms(j, "start_ms") ?: 0L
            val en = ms(j, "end_ms") ?: 0L
            SkipMarker(kind, st, en).takeIf { en == 0L || en > st }
        }
        var found = if (tidb == null) emptyList() else list(tidb.array("intro"), SkipMarker.Kind.Intro) + list(tidb.array("credits"), SkipMarker.Kind.Credits)
        // IntroDB: one "intro" and one "outro" each, by IMDb id.
        if (found.isEmpty() && w.imdbId != null) {
            val q = "imdb_id=${w.imdbId}" + if (episode) ep else "&is_movie=true"
            val r = withTimeoutOrNull(8_000) { runCatching { http.get("https://api.introdb.app/segments?$q") }.getOrNull() }
            if (r == null || r.code >= 500) failed = true
            val idb = r?.takeIf { it.ok }?.body
            found = listOfNotNull(
                idb.child("intro")?.takeIf { it !is JsonNull }?.let { j -> ms(j, "end_ms")?.let { SkipMarker(SkipMarker.Kind.Intro, ms(j, "start_ms") ?: 0L, it) } },
                idb.child("outro")?.takeIf { it !is JsonNull }?.let { j -> ms(j, "start_ms")?.let { SkipMarker(SkipMarker.Kind.Credits, it, ms(j, "end_ms") ?: 0L) } },
            )
        }
        introDbCache[key] = Timed.forMs(found, if (failed && found.isEmpty()) Timed.MISS_MS else Long.MAX_VALUE / 2)
        return found
    }

    /**
     * Where the intro and credits are. Jellyfin (10.10+): its media segments (the Intro Skipper plugin or its own
     * detection fill them). Emby (4.8+): chapter markers IntroStart / IntroEnd / CreditsStart. None: no skipping.
     */
    private suspend fun serverMarkers(s: MediaServerAuth, emby: Boolean, itemId: String, info: JsonElement?): List<SkipMarker> {
        val ticks = 10_000L
        if (!emby) {
            val a = store.current()
            val segs = runCatching { http.get("${s.serverUrl}/MediaSegments/$itemId", mediaServers.headers(false, a.deviceId, s.token)) }
                .getOrNull()?.takeIf { it.ok }?.body.array("Items")
            return segs.mapNotNull { g ->
                val kind = when (g.str("Type")) { "Intro" -> SkipMarker.Kind.Intro; "Outro" -> SkipMarker.Kind.Credits; else -> return@mapNotNull null }
                val st = (g.long("StartTicks") ?: return@mapNotNull null) / ticks
                val en = (g.long("EndTicks") ?: return@mapNotNull null) / ticks
                SkipMarker(kind, st, en).takeIf { en > st }
            }
        }
        val chapters = info.array("Chapters")
        fun at(type: String) = chapters.firstOrNull { it.str("MarkerType") == type }?.long("StartPositionTicks")?.div(ticks)
        val runtime = (info.long("RunTimeTicks") ?: 0L) / ticks
        return listOfNotNull(
            at("IntroStart")?.let { st -> at("IntroEnd")?.takeIf { it > st }?.let { SkipMarker(SkipMarker.Kind.Intro, st, it) } },
            at("CreditsStart")?.let { st -> SkipMarker(SkipMarker.Kind.Credits, st, if (runtime > st) runtime else st + 600_000) },
        )
    }

    /**
     * Seek-bar previews from the server. Jellyfin: trickplay sheets listed on the item (smallest width).
     * Emby: its BIF index, if it made one (a missing one just falls back to the box's own pictures).
     */
    private fun serverThumbs(s: MediaServerAuth, emby: Boolean, itemId: String, info: JsonElement?): ThumbSource? {
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

    /**
     * Where to carry on with a show: (season, episode, part-way through). Plex's on deck first, then Jellyfin /
     * Emby's next up, then your Trakt progress; null when none of them know (start at the first episode).
     */
    // (Every source's idea, whatever's connected: this box's own record is added by the title page.)
    suspend fun nextEpisodeFor(item: MediaItem, copies: List<LibraryCopy>): Triple<Int, Int, Boolean>? {
        // Each source's idea of where you are; the furthest along wins (Trakt knows what you watched elsewhere, so
        // Plex's on deck can be well behind it). Part-way through an episode beats starting that same one.
        val a = store.current()
        // Plex, Jellyfin / Emby and Trakt are asked at the same time (one after another made the show page slow).
        val found = java.util.Collections.synchronizedList(ArrayList<Triple<Int, Int, Boolean>>())
        coroutineScope {
        launch { runCatching {
        copies.filter { it.source == "Plex" && it.ref != null }.forEach { c ->
            val (p, rk) = plexResolve(a, c.ref!!) ?: return@forEach
            val deck = runCatching { plexGet(a, p, "/library/metadata/$rk?includeOnDeck=1") }.getOrNull()
                ?.array("Metadata")?.firstOrNull()?.child("OnDeck")?.array("Metadata")?.firstOrNull()
            val s = deck.int("parentIndex"); val e = deck.int("index")
            Log.i(TAG, "up to: plex ${c.source} deck=$s/$e")
            if (s != null && e != null) { found += Triple(s, e, (deck.long("viewOffset") ?: 0) > 0); return@forEach }
        }
        } }
        launch { runCatching {
        copies.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }?.let { c ->
            val (srv, emby) = serverFor(c.source) ?: return@let
            val r = runCatching {
                http.get("${srv.serverUrl}/Shows/NextUp?SeriesId=${enc(c.ref!!)}&UserId=${srv.userId}&Limit=1&Fields=UserData", mediaServers.headers(emby, a.deviceId, srv.token))
            }.getOrNull()?.takeIf { it.ok }
            val ep = r?.body?.array("Items")?.firstOrNull()
            val s = ep.int("ParentIndexNumber"); val e = ep.int("IndexNumber")
            if (s != null && e != null) found += Triple(s, e, (ep.child("UserData").long("PlaybackPositionTicks") ?: 0) > 0)
        }
        } }
        launch { runCatching {
        val t = a.trakt?.let { freshTrakt(it) }
        Log.i(TAG, "up to: trakt signed in=${t != null} imdb=${item.imdbId != null} tmdb=${item.tmdbId}")
        // Trakt's show address takes an IMDb id or its own; with only a TMDB id, look its own up.
        val id = item.imdbId ?: if (t != null) item.tmdbId?.let { tm ->
            runCatching { http.get("https://api.trakt.tv/search/tmdb/$tm?type=show", trakt.headers(t.accessToken)) }.getOrNull()
                ?.takeIf { it.ok }?.body.asArray().firstOrNull().child("show").child("ids").int("trakt")?.toString()
        } else null
        if (t != null && id != null) {
            val r = runCatching { http.get("https://api.trakt.tv/shows/$id/progress/watched?specials=false", trakt.headers(t.accessToken)) }.getOrNull()?.takeIf { it.ok }
            val next = r?.body?.child("next_episode")
            val s = next.int("season"); val e = next.int("number")
            // Only when you've started the show: otherwise Trakt's "next" is just the first episode anyway.
            Log.i(TAG, "up to: trakt http=${r?.code} next=$s/$e completed=${r?.body.int("completed")}")
            if (s != null && e != null && (r?.body.int("completed") ?: 0) > 0) found += Triple(s, e, false)
        }
        } }
        }
        return found.maxWithOrNull(compareBy<Triple<Int, Int, Boolean>>({ it.first }, { it.second }, { it.third }))
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
            val at = ud.str("LastPlayedDate")?.let { d -> runCatching { java.time.Instant.parse(d).toEpochMilli() }.getOrNull() } ?: 0L
            (season to number) to PlexEpisode(id, (ud.long("PlaybackPositionTicks") ?: 0L) / 10_000, ud.str("Played") == "true", at)
        }.toMap()
    }

    /** Tells Jellyfin / Emby what's playing and where, so your place is saved and finished items are marked watched. */
    suspend fun reportServer(source: String, itemId: String, state: String, positionMs: Long, durationMs: Long = 0) {
        val (s, emby) = serverFor(source) ?: return
        val a = store.current()
        val path = when (state) { "stopped" -> "/Sessions/Playing/Stopped"; "playing", "paused" -> "/Sessions/Playing/Progress"; else -> return }
        val body = buildJsonObject {
            put("ItemId", itemId)
            put("PositionTicks", positionMs * 10_000)
            put("IsPaused", state == "paused")
            put("CanSeek", true)
        }
        val h = mediaServers.headers(emby, a.deviceId, s.token)
        runCatching { http.post(s.serverUrl + path, body, h) }
        // Stopped past 80%: marked played too (Jellyfin and Emby only do that themselves at their own, later point).
        if (state == "stopped" && durationMs > 0 && positionMs >= durationMs * 0.8) runCatching { http.post("${s.serverUrl}/Users/${s.userId}/PlayedItems/$itemId", null, h) }
    }

    /**
     * Tells Plex where you are (so Continue watching and "watched" stay in sync), and marks it watched past 80% when
     * stopped ([markWatched], always on now: the server a film plays from hears everything).
     */
    suspend fun reportPlex(ref: String, state: String, positionMs: Long, durationMs: Long, markWatched: Boolean = true) {
        val a = store.current()
        val (p, ratingKey) = plexResolve(a, ref) ?: return
        runCatching {
            http.get("${p.serverUrl}/:/timeline?ratingKey=$ratingKey&key=${enc("/library/metadata/$ratingKey")}&state=$state&time=$positionMs&duration=$durationMs",
                plex.headers(a.deviceId, p.token))
            // (Past 80%, as Trakt counts it: stopping in the credits left Plex with a half-watched episode Trakt
            // called watched. Peter, 2026-11-18.)
            if (markWatched && state == "stopped" && durationMs > 0 && positionMs >= durationMs * 0.8) {
                http.get("${p.serverUrl}/:/scrobble?identifier=com.plexapp.plugins.library&key=$ratingKey", plex.headers(a.deviceId, p.token))
            }
        }.onFailure { Log.w(TAG, "plex timeline failed: ${it.message}") }
    }

    /** Trakt scrobble: start / pause / stop with % watched. Trakt marks it watched when stopped past 80%. */
    suspend fun scrobbleTrakt(action: String, target: WatchTarget, progress: Float) {
        // (Every play, from wherever, while Trakt is connected and Send plays to Trakt is on: Peter, 2026-11-19.)
        if (!io.github.petebutty.viewarr.data.sync.WatchSource.sendToTrakt) return
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
        // (Trakt takes one write a second: a pause then play, or a skip landing with a state change, are spaced out.)
        traktPostGap.withLock { val wait = 1_050 - (System.currentTimeMillis() - lastTraktPost); if (wait > 0) delay(wait); lastTraktPost = System.currentTimeMillis() }
        runCatching { http.post("https://api.trakt.tv/scrobble/$action", body, trakt.headers(auth.accessToken)) }
            .onSuccess { r ->
                // 409: Trakt already has this watch (Plex's own webhook, or the same stop twice): the same watch, fine.
                if (r.code == 409) Log.i(TAG, "trakt scrobble $action: already has it (409)")
                else Log.i(TAG, "trakt scrobble $action ${"%.1f".format(progress * 100)}% → ${r.code}")
                if (r.ok || r.code == 409) noteScrobble(action, target, progress)
            }
            .onFailure { Log.w(TAG, "trakt scrobble failed: ${it.message}") }
    }

    private val traktPostGap = Mutex()
    @Volatile private var lastTraktPost = 0L

    // ------------------------------------------------------------------ Trakt playback (where you stopped)

    private val _traktPlayback = MutableStateFlow(TraktPlayback())
    /** As [traktPlaybackOf] reads it, for pages to follow (a scrobble here moves an episode's bar). */
    val traktPlaybackState: StateFlow<TraktPlayback> = _traktPlayback
    /** Trakt's paused points, by "tmdb:1" / "imdb:tt1": films, and shows' episodes. From GET /sync/playback (rows()). */
    data class TraktPlayback(
        val movies: Map<String, io.github.petebutty.viewarr.data.sync.TraktPos> = emptyMap(),
        val episodes: Map<String, Map<Pair<Int, Int>, io.github.petebutty.viewarr.data.sync.TraktPos>> = emptyMap(),
    )

    /** Keeps what GET /sync/playback/{movies|episodes} said (the whole list for that kind, replacing the last). */
    private fun rememberTraktPlayback(type: MediaType, entries: List<JsonElement>) {
        fun at(e: JsonElement) = e.str("paused_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        fun pos(e: JsonElement) = e.str("progress")?.toFloatOrNull()?.let { io.github.petebutty.viewarr.data.sync.TraktPos((it / 100f).coerceIn(0f, 1f), at(e)) }
        if (type == MediaType.Movie) {
            val m = HashMap<String, io.github.petebutty.viewarr.data.sync.TraktPos>()
            entries.forEach { e -> val p = pos(e) ?: return@forEach; val ids = e.child("movie").child("ids"); idKeys(ids.int("tmdb"), ids.str("imdb")).forEach { k -> if ((m[k]?.at ?: -1) < p.at) m[k] = p } }
            _traktPlayback.update { it.copy(movies = m) }
        } else {
            val shows = HashMap<String, HashMap<Pair<Int, Int>, io.github.petebutty.viewarr.data.sync.TraktPos>>()
            entries.forEach { e ->
                val p = pos(e) ?: return@forEach
                val se = e.child("episode").int("season") ?: return@forEach; val n = e.child("episode").int("number") ?: return@forEach
                val ids = e.child("show").child("ids")
                idKeys(ids.int("tmdb"), ids.str("imdb")).forEach { k -> val m = shows.getOrPut(k) { HashMap() }; if ((m[se to n]?.at ?: -1) < p.at) m[se to n] = p }
            }
            _traktPlayback.update { it.copy(episodes = shows) }
        }
    }

    /** A scrobble Trakt took: its paused point kept in step here (stopped past 80%: gone, and counted as watched). */
    private fun noteScrobble(action: String, target: WatchTarget, progress: Float) {
        if (action == "start") return
        val keys = idKeys(target.tmdbId, target.imdbId)
        val done = action == "stop" && progress >= io.github.petebutty.viewarr.data.sync.WatchSource.WATCHED_AT
        val pos = io.github.petebutty.viewarr.data.sync.TraktPos(progress.coerceIn(0f, 1f), System.currentTimeMillis())
        val ep = target.season?.let { s -> target.episode?.let { e -> s to e } }
        _traktPlayback.update { tp ->
            if (target.type == MediaType.Movie) tp.copy(movies = tp.movies.toMutableMap().apply { keys.forEach { if (done) remove(it) else put(it, pos) } })
            else if (ep == null) tp
            else tp.copy(episodes = tp.episodes.toMutableMap().apply {
                keys.forEach { k -> put(k, (get(k).orEmpty().toMutableMap()).apply { if (done) remove(ep) else put(ep, pos) }) }
            })
        }
        // Watched now on Trakt: the tick shows straight away (the next check of Trakt's history confirms it).
        if (done) _traktWatched.update { w ->
            w ?: return@update null
            if (ep == null) w.copy(movies = w.movies + keys)
            else w.copy(episodes = w.episodes.toMutableMap().apply { keys.forEach { k -> put(k, get(k).orEmpty() + ep) } })
        }
    }

    /** Trakt's paused points for this show's episodes (empty when it has none, or Trakt isn't connected). */
    fun traktPlaybackOf(item: MediaItem): Map<Pair<Int, Int>, io.github.petebutty.viewarr.data.sync.TraktPos> =
        idKeys(item.tmdbId, item.imdbId).firstNotNullOfOrNull { _traktPlayback.value.episodes[it] }.orEmpty()

    /** Trakt's paused point for this film, if it has one. */
    fun traktFilmPos(item: MediaItem): io.github.petebutty.viewarr.data.sync.TraktPos? =
        idKeys(item.tmdbId, item.imdbId).firstNotNullOfOrNull { _traktPlayback.value.movies[it] }

    // ------------------------------------------------------------------ Trakt watched history

    /** Everything Trakt says you've watched. Keys are "tmdb:123" / "imdb:tt123" so either id finds a title. */
    data class TraktWatched(
        val movies: Set<String>,
        val episodes: Map<String, Set<Pair<Int, Int>>>,
        val fetchedAt: Long,
        /** Trakt's last-watched times; a new one means there's something to fetch again. */
        val activity: String,
        /** When the whole history was last read (between those, only what's new is fetched). */
        val fullAt: Long = fetchedAt,
    ) {
        val movieCount get() = movies.count { it.startsWith("tmdb:") }.coerceAtLeast(movies.count { it.startsWith("imdb:") })
        val episodeCount get() = episodes.filterKeys { it.startsWith("tmdb:") }.values.sumOf { it.size }
            .coerceAtLeast(episodes.filterKeys { it.startsWith("imdb:") }.values.sumOf { it.size })
    }

    private val _traktWatched = MutableStateFlow<TraktWatched?>(null)
    /** The last Trakt history fetched (null until then, or without Trakt). */
    val traktWatchedState: StateFlow<TraktWatched?> = _traktWatched
    private val traktLock = Mutex()

    private fun idKeys(tmdb: Int?, imdb: String?) = listOfNotNull(tmdb?.let { "tmdb:$it" }, imdb?.takeIf { it.isNotBlank() }?.let { "imdb:$it" })

    /**
     * Your Trakt watched films and episodes. Checked again at most every 10 minutes (and then only fetched in full
     * when Trakt says something changed); [force] fetches now (Settings → Sync with Trakt now).
     */
    /** [force]: don't take the saved copy; [full]: read the whole history, not just what's new (Sync now). */
    suspend fun traktWatched(force: Boolean = false, full: Boolean = force): TraktWatched? = traktLock.withLock {
        val a = store.current()
        val t = a.trakt?.let { freshTrakt(it) } ?: run { _traktWatched.value = null; return@withLock null }
        // The copy saved on the box last time (Peter, 2026-11-18: every start downloaded and sorted the whole history
        // again: 15 seconds of work on the AM9). With it, a start only asks Trakt "anything new?".
        val cur = _traktWatched.value ?: loadTraktWatched(t.username)?.also { _traktWatched.value = it }
        val now = System.currentTimeMillis()
        if (!force && cur != null && now - cur.fetchedAt < 10 * 60_000) return@withLock cur
        val h = trakt.headers(t.accessToken)
        val act = runCatching { http.get("https://api.trakt.tv/sync/last_activities", h) }.getOrNull()?.takeIf { it.ok }?.body
        val stamp = listOf(act.child("movies").str("watched_at"), act.child("episodes").str("watched_at")).joinToString("|")
        if (!force && cur != null && act != null && stamp == cur.activity) return@withLock cur.copy(fetchedAt = now).also { _traktWatched.value = it; saveTraktWatched(t.username, it) }
        // Something new, with a saved copy to show meanwhile: the full fetch in the background, a little after the
        // app has settled, not in the way of the first screen.
        if (!force && cur != null && !traktRefreshing) {
            traktRefreshing = true
            appScope.launch { delay(8_000); runCatching { traktWatched(force = true, full = false) }; traktRefreshing = false }
            return@withLock cur
        }
        // Only what's new since the last look (history from just before then), unless the whole history hasn't been
        // read for a week, or Sync now: then all of it, page by page, each page sorted into the sets and let go (the
        // whole list held at once took hundreds of MB on a big history).
        val week = 7L * 86_400_000L
        val since = cur?.takeIf { now - it.fullAt < week && !full }?.fetchedAt
        val movies = HashSet<String>(cur?.takeIf { since != null }?.movies.orEmpty())
        val episodes = HashMap<String, MutableSet<Pair<Int, Int>>>()
        if (since != null) cur?.episodes?.forEach { (k, v) -> episodes[k] = v.toHashSet() }
        var count = 0
        fun takeMovie(m: JsonElement?) { val ids = m.child("ids"); movies += idKeys(ids.int("tmdb"), ids.str("imdb")) }
        fun takeEpisode(h: JsonElement?) {
            val ids = h.child("show").child("ids"); val ep = h.child("episode")
            val se = ep.int("season") ?: return; val n = ep.int("number") ?: return
            idKeys(ids.int("tmdb"), ids.str("imdb")).forEach { episodes.getOrPut(it) { HashSet() } += se to n }
            count++
        }
        suspend fun pages(path: String, each: (JsonElement) -> Unit): Boolean {
            for (page in 1..400) {
                val r = runCatching { http.get("https://api.trakt.tv$path${if ('?' in path) '&' else '?'}page=$page&limit=250", h) }.getOrNull()
                    ?.takeIf { it.ok } ?: return page > 1
                val items = r.body.asArray()
                items.forEach(each)
                if (items.size < 100) break
            }
            return true
        }
        val ok = if (since != null) {
            val iso = java.time.Instant.ofEpochMilli(since - 15 * 60_000L).toString()
            pages("/sync/history?start_at=$iso") { e ->
                when (e.str("type")) { "movie" -> takeMovie(e.child("movie")); "episode" -> takeEpisode(e) }
            }
        } else {
            pages("/sync/watched/movies") { takeMovie(it.child("movie")) } &&
                // Episodes: Trakt's watched-shows list no longer includes them, so from your history (one entry a watch).
                pages("/sync/history/episodes") { takeEpisode(it) }
        }
        if (!ok) return@withLock cur
        Log.i(TAG, "trakt: ${movies.size / 2} films, ${if (since != null) "$count new" else "$count"} episode watches")
        val fresh = TraktWatched(movies, episodes, now, stamp, fullAt = if (since != null) cur!!.fullAt else now)
        val changed = cur == null || cur.activity != stamp
        _traktWatched.value = fresh
        saveTraktWatched(t.username, fresh)
        // Copy to Plex (Settings → Trakt, off unless switched on): only when the history changed, in the background.
        if (a.traktToPlex && (changed || force)) appScope.launch { runCatching { copyTraktToPlex(fresh) } }
        fresh
    }

    /** Viewarr marked [item] (or one episode) watched / not on Trakt: the copy here follows, without a download. */
    private suspend fun traktMarkLocal(t: TraktAuth, item: MediaItem, season: Int?, episode: Int?, watched: Boolean) = traktLock.withLock {
        val cur = _traktWatched.value ?: return@withLock
        val keys = idKeys(item.tmdbId, item.imdbId)
        val movies = cur.movies.toHashSet()
        val eps = cur.episodes.mapValues { it.value.toHashSet() }.toMutableMap()
        if (season == null || episode == null) { if (watched) movies += keys else movies -= keys.toSet() }
        else keys.forEach { k -> val set = eps.getOrPut(k) { HashSet() }; if (watched) set += season to episode else set -= season to episode }
        // Trakt's new "last watched" stamp, so this change doesn't look like news from elsewhere and fetch again.
        val act = runCatching { http.get("https://api.trakt.tv/sync/last_activities", trakt.headers(t.accessToken)) }.getOrNull()?.takeIf { it.ok }?.body
        val stamp = act?.let { listOf(it.child("movies").str("watched_at"), it.child("episodes").str("watched_at")).joinToString("|") } ?: cur.activity
        val fresh = cur.copy(movies = movies, episodes = eps, activity = stamp)
        _traktWatched.value = fresh
        saveTraktWatched(t.username, fresh)
    }

    @Volatile private var traktRefreshing = false
    private fun traktFile(user: String) = java.io.File(appContext.filesDir, "trakt_watched_" + kotlin.math.abs(user.lowercase().hashCode()).toString(36) + ".json")

    /** The Trakt history as last worked out, from the box (null: none, or older than 30 days). */
    private fun loadTraktWatched(user: String): TraktWatched? = runCatching {
        val f = traktFile(user)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 86_400_000L) return null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return null
        val movies = (o["m"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        val eps = (o["e"] as? JsonObject).orEmpty().mapValues { (_, v) ->
            (v as? JsonArray).orEmpty().mapNotNull { p -> (p as? JsonPrimitive)?.contentOrNull?.split('x')?.takeIf { it.size == 2 }?.let { it[0].toInt() to it[1].toInt() } }.toSet()
        }
        val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L
        TraktWatched(movies, eps, at, (o["a"] as? JsonPrimitive)?.contentOrNull.orEmpty(), fullAt = (o["fa"] as? JsonPrimitive)?.longOrNull ?: at)
            .also { Log.i(TAG, "trakt: from the box, ${it.movieCount} films, ${it.episodeCount} episodes") }
    }.getOrNull()

    private fun saveTraktWatched(user: String, w: TraktWatched) {
        appScope.launch {
            runCatching {
                val o = buildJsonObject {
                    put("at", w.fetchedAt); put("a", w.activity); put("fa", w.fullAt)
                    put("m", buildJsonArray { w.movies.forEach { add(JsonPrimitive(it)) } })
                    put("e", buildJsonObject { w.episodes.forEach { (k, v) -> put(k, buildJsonArray { v.forEach { (se, n) -> add(JsonPrimitive("${se}x$n")) } }) } })
                }
                val f = traktFile(user)
                val tmp = java.io.File(f.path + ".tmp")
                tmp.writeText(o.toString()); tmp.renameTo(f)
            }
        }
    }

    /** The episodes of a show that Trakt says you've watched (from the last fetch). */
    fun traktEpisodesOf(item: MediaItem): Set<Pair<Int, Int>> {
        val w = _traktWatched.value ?: return emptySet()
        return idKeys(item.tmdbId, item.imdbId).firstNotNullOfOrNull { w.episodes[it] }.orEmpty()
    }

    /** Whether Trakt says you've watched this film (from the last fetch). */
    fun traktWatchedMovie(item: MediaItem): Boolean {
        val w = _traktWatched.value ?: return false
        return idKeys(item.tmdbId, item.imdbId).any { it in w.movies }
    }

    private val _plexCopyStatus = MutableStateFlow<String?>(null)
    /** What the last copy to Plex did (Settings shows it). */
    val plexCopyStatus: MutableStateFlow<String?> = _plexCopyStatus
    private val plexCopyLock = Mutex()

    /**
     * Marks on your own Plex server what you've watched on Trakt in the last [COPY_DAYS] days (never the other way,
     * never un-marks). Only titles Plex has as unwatched are touched. Returns how many were marked.
     *
     * (Peter, 2026-11-20: it used to copy the whole Trakt history to every Plex server the account can open. One run
     * marked 128 films at once, and Plex asked "how did you enjoy it?" for each, films watched years ago, some on
     * other people's servers. Recent watches on your own server keep Plex in step without that.)
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun copyTraktToPlex(all: TraktWatched): Int = plexCopyLock.withLock {
        val a = store.current()
        val servers = plexServers(a).filter { isMainPlex(it) }
        if (servers.isEmpty()) return@withLock 0
        val w = recentTraktWatches(a) ?: run { _plexCopyStatus.value = "Couldn't reach Trakt."; return@withLock 0 }
        if (w.movies.isEmpty() && w.episodes.isEmpty()) { _plexCopyStatus.value = "Nothing watched on Trakt in the last $COPY_DAYS days."; return@withLock 0 }
        _plexCopyStatus.value = "Copying to Plex…"
        fun guidKeys(j: JsonElement?) = j.array("Guid").mapNotNull { it.str("id") }.mapNotNull { g ->
            when {
                g.startsWith("tmdb://") -> "tmdb:" + g.removePrefix("tmdb://")
                g.startsWith("imdb://") -> "imdb:" + g.removePrefix("imdb://")
                else -> null
            }
        }
        var marked = 0
        suspend fun scrobble(p: PlexAuth, rk: String) {
            val ok = runCatching { http.get("${p.serverUrl}/:/scrobble?identifier=com.plexapp.plugins.library&key=$rk", plex.headers(a.deviceId, p.token)).ok }.getOrDefault(false)
            if (ok) marked++
        }
        for (p in servers) {
            for (sec in runCatching { plexSections(a, p, MediaType.Movie) }.getOrDefault(emptyList())) {
                val key = sec.str("key") ?: continue
                runCatching { plexGet(a, p, "/library/sections/$key/all?type=1&unwatched=1&includeGuids=1") }.getOrNull()
                    ?.array("Metadata").orEmpty().forEach { m ->
                        val rk = m.str("ratingKey") ?: return@forEach
                        if (guidKeys(m).any { it in w.movies }) scrobble(p, rk)
                    }
            }
            for (sec in runCatching { plexSections(a, p, MediaType.Show) }.getOrDefault(emptyList())) {
                val key = sec.str("key") ?: continue
                // Show → its ids; then every unwatched episode, matched by show, season and number.
                val showKeys = runCatching { plexGet(a, p, "/library/sections/$key/all?type=2&includeGuids=1") }.getOrNull()
                    ?.array("Metadata").orEmpty().mapNotNull { sh -> sh.str("ratingKey")?.let { it to guidKeys(sh) } }.toMap()
                runCatching { plexGet(a, p, "/library/sections/$key/all?type=4&unwatched=1") }.getOrNull()
                    ?.array("Metadata").orEmpty().forEach { e ->
                        val rk = e.str("ratingKey") ?: return@forEach
                        val se = e.int("parentIndex") ?: return@forEach
                        val n = e.int("index") ?: return@forEach
                        val keys = showKeys[e.str("grandparentRatingKey")] ?: return@forEach
                        if (keys.any { k -> w.episodes[k]?.contains(se to n) == true }) scrobble(p, rk)
                    }
            }
        }
        plexEpisodeCache.clear()
        _plexCopyStatus.value = if (marked == 0) "Plex already matches Trakt." else "Marked $marked on Plex as watched."
        marked
    }

    /** What you've watched on Trakt in the last [COPY_DAYS] days (null: Trakt couldn't be read). */
    private suspend fun recentTraktWatches(a: Accounts): TraktWatched? {
        val t = a.trakt?.let { freshTrakt(it) } ?: return null
        val h = trakt.headers(t.accessToken)
        val from = java.time.Instant.now().minus(java.time.Duration.ofDays(COPY_DAYS.toLong())).toString()
        val movies = HashSet<String>()
        val episodes = HashMap<String, MutableSet<Pair<Int, Int>>>()
        for (page in 1..40) {
            val r = runCatching { http.get("https://api.trakt.tv/sync/history?start_at=$from&page=$page&limit=250", h) }.getOrNull()
                ?.takeIf { it.ok } ?: return if (page > 1) TraktWatched(movies, episodes, System.currentTimeMillis(), "") else null
            val items = r.body.asArray()
            items.forEach { e ->
                when (e.str("type")) {
                    "movie" -> e.child("movie").child("ids").let { ids -> movies += idKeys(ids.int("tmdb"), ids.str("imdb")) }
                    "episode" -> {
                        val ids = e.child("show").child("ids"); val ep = e.child("episode")
                        val se = ep.int("season"); val n = ep.int("number")
                        if (se != null && n != null) idKeys(ids.int("tmdb"), ids.str("imdb")).forEach { episodes.getOrPut(it) { HashSet() } += se to n }
                    }
                }
            }
            if (items.size < 250) break
        }
        return TraktWatched(movies, episodes, System.currentTimeMillis(), "")
    }

    // ------------------------------------------------------------------ episodes

    private val plexEpisodeCache = ConcurrentHashMap<String, Map<Pair<Int, Int>, PlexEpisode>>()

    /** Season → episodes from TMDB; falls back to the Plex copy's episodes when TMDB has nothing. */
    suspend fun seasonEpisodes(item: MediaItem, season: Int, plexShowKey: String?): List<EpisodeInfo> {
        if (isAddonOwnTitle(item)) addonMeta(item)?.let { m ->
            return m.videos.filter { it.season == season }.map { EpisodeInfo(season, it.episode, it.title, it.overview, it.thumb, it.released, null) }
        }
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

    /** A show's series on your own servers (Plex, Jellyfin, Emby): series number → episodes there. */
    suspend fun ownSeasons(item: MediaItem): Map<Int, Int> {
        val a = store.current()
        val out = HashMap<Int, Int>()
        runCatching { copiesOf(item) }.getOrDefault(emptyList()).filter { it.source != PROVIDER }.forEach { c ->
            val ref = c.ref ?: return@forEach
            when (c.source) {
                "Plex" -> runCatching { plexSeasons(ref) }.getOrDefault(emptyList()).forEach { s -> out[s.number] = maxOf(out[s.number] ?: 0, s.episodes) }
                "Jellyfin", "Emby" -> {
                    val emby = c.source == "Emby"
                    val s = (if (emby) a.emby else a.jellyfin) ?: return@forEach
                    runCatching {
                        http.get("${s.serverUrl}/Shows/$ref/Seasons?userId=${s.userId}&Fields=ChildCount", mediaServers.headers(emby, a.deviceId, s.token))
                    }.getOrNull()?.takeIf { it.ok }?.body.array("Items").forEach { j ->
                        val n = j.int("IndexNumber")?.takeIf { it > 0 } ?: return@forEach
                        out[n] = maxOf(out[n] ?: 0, j.int("ChildCount") ?: 1)
                    }
                }
            }
        }
        return out
    }

    /** On one of your own servers (not just your IPTV provider's VOD). */
    suspend fun onOwnServer(item: MediaItem) = runCatching { copiesOf(item) }.getOrDefault(emptyList()).any { it.source != PROVIDER }

    /** Seasons from Plex when TMDB has none (no key, or an unmatched show). */
    suspend fun plexSeasons(showRef: String): List<SeasonInfo> {
        val a = store.current()
        val (p, showKey) = plexResolve(a, showRef) ?: return emptyList()
        return plexGet(a, p, "/library/metadata/$showKey/children")?.array("Metadata").orEmpty().mapNotNull { s ->
            val n = s.int("index") ?: return@mapNotNull null
            if (n == 0) null else SeasonInfo(n, "Season $n", s.int("leafCount") ?: 0, s.int("year"))
        }.sortedBy { it.number }
    }

    /** Every episode of a show in Plex, by (season, episode). Cached per show for this run. */
    suspend fun plexEpisodes(showRef: String): Map<Pair<Int, Int>, PlexEpisode> {
        plexEpisodeCache[showRef]?.let { return it }
        val a = store.current()
        val (p, showKey) = plexResolve(a, showRef) ?: return emptyMap()
        // Not cached when Plex couldn't be reached, so the next try asks again.
        val c = plexGet(a, p, "/library/metadata/$showKey/allLeaves") ?: return emptyMap()
        val map = c.array("Metadata").mapNotNull { e ->
            val s = e.int("parentIndex") ?: return@mapNotNull null
            val n = e.int("index") ?: return@mapNotNull null
            val rk = e.str("ratingKey") ?: return@mapNotNull null
            (s to n) to PlexEpisode(plexRef(p, rk), e.long("viewOffset") ?: 0, (e.int("viewCount") ?: 0) > 0, (e.long("lastViewedAt") ?: 0L) * 1000)
        }.toMap()
        plexEpisodeCache[showRef] = map
        return map
    }

    /** Asks every connected Stremio addon for streams of a film, or of one episode. Only direct http(s) links can be played. */
    suspend fun addonStreams(item: MediaItem, season: Int? = null, episode: Int? = null): List<StreamOption> {
        val type = if (item.type == MediaType.Movie) "movie" else "series"
        // The addon's own title (One Pace…): only that addon knows it, by its own episode ids.
        ownAddonTitle(item)?.let { (addon, id) ->
            val type = item.addonType ?: type
            val vid = if (season != null && episode != null) addonMeta(item)?.videos?.firstOrNull { it.season == season && it.episode == episode }?.id ?: return emptyList()
            else addonMeta(item)?.videos?.singleOrNull()?.id ?: id
            return streamsFrom(addon, type, vid)
        }
        val baseImdb = item.imdbId ?: return emptyList()
        val imdb = if (season != null && episode != null) "$baseImdb:$season:$episode" else baseImdb
        val addons = store.current().activeAddons
        return coroutineScope { addons.map { addon -> async { streamsFrom(addon, type, imdb) } }.awaitAll().flatten() }
    }

    /** One addon's streams for one id (direct http(s) links only: what the players can open). */
    private suspend fun streamsFrom(addon: AddonInfo, type: String, id: String): List<StreamOption> {
                    val r = runCatching { withTimeoutOrNull(30_000) { http.get("${addon.baseUrl}/stream/$type/${enc(id)}.json") } }.getOrNull()
                    if (r?.ok != true) return emptyList()
                    return r.body.array("streams").mapNotNull { st ->
                        val url = st.str("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                        val headers = st.child("behaviorHints").child("proxyHeaders").child("request").let { h ->
                            (h as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
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

    // ------------------------------------------------------------------ Addons with their own titles

    /** An addon title's own episode (its id is what the addon's streams are asked for). */
    private data class AddonVideo(val id: String, val season: Int, val episode: Int, val title: String, val overview: String?, val thumb: String?, val released: String?)
    private data class AddonMeta(val description: String?, val background: String?, val genres: List<String>, val videos: List<AddonVideo>)
    private val addonMetaCache = BoundedCache<String, AddonMeta>(50)

    /** A title from an addon with no IMDb / TMDB id (One Pace and the like): Viewarr uses the addon's own details. */
    fun isAddonOwnTitle(item: MediaItem) = item.key.startsWith("stremio:") &&
        ((item.imdbId == null && item.tmdbId == null) || (item.addonType != null && item.addonType != "anime"))

    private suspend fun ownAddonTitle(item: MediaItem): Pair<AddonInfo, String>? {
        if (!isAddonOwnTitle(item)) return null
        val rest = item.key.removePrefix("stremio:")
        val addon = store.current().activeAddons.firstOrNull { rest.startsWith(it.id + ":") } ?: return null
        return addon to rest.removePrefix(addon.id + ":")
    }

    /** The addon's meta for its own title: description, artwork, and every episode with its id (cached for this run). */
    private suspend fun addonMeta(item: MediaItem): AddonMeta? {
        addonMetaCache[item.key]?.let { return it }
        val (addon, id) = ownAddonTitle(item) ?: return null
        val type = item.addonType ?: if (item.type == MediaType.Movie) "movie" else "series"
        val m = runCatching { http.get("${addon.baseUrl}/meta/$type/${enc(id)}.json") }.getOrNull()?.takeIf { it.ok }?.body.child("meta") ?: return null
        var next = 0
        val videos = m.array("videos").mapNotNull { v ->
            val vid = v.str("id") ?: return@mapNotNull null
            val season = v.int("season") ?: 1
            val ep = v.int("episode") ?: v.int("number") ?: ++next
            AddonVideo(vid, season, ep, v.str("title") ?: v.str("name") ?: "Episode $ep", v.str("overview")?.takeIf { it.isNotBlank() } ?: v.str("description"),
                v.str("thumbnail"), v.str("released")?.take(10))
        }.sortedWith(compareBy({ it.season }, { it.episode }))
        return AddonMeta(m.str("description"), m.str("background"), m.array("genres").mapNotNull { (it as? JsonPrimitive)?.content }, videos)
            .also { addonMetaCache[item.key] = it }
    }

    /** Details for an addon's own title: its description and its seasons (episodes from [seasonEpisodes]). */
    private suspend fun addonDetail(item: MediaItem): MediaDetail? {
        val m = addonMeta(item) ?: return null
        val seasons = m.videos.groupBy { it.season }.map { (n, eps) -> SeasonInfo(n, if (n == 0) "Specials" else "Season $n", eps.size, eps.firstOrNull()?.released?.take(4)?.toIntOrNull()) }
        return MediaDetail(
            item.copy(overview = item.overview ?: m.description, backdrop = item.backdrop ?: m.background),
            genres = m.genres,
            seasons = seasons,
        )
    }

    // ------------------------------------------------------------------ detail

    /** Full TMDB details for the detail page; falls back to what the row already had. */
    suspend fun detail(item: MediaItem): MediaDetail {
        if (isAddonOwnTitle(item)) addonDetail(item)?.let { return it }
        val key = store.current().tmdbKey ?: return MediaDetail(item)
        // No id (many IPTV providers send none): found on TMDB by its name and year, so the page isn't bare.
        val tmdbId = item.tmdbId ?: item.imdbId?.let { findByImdb(key, it, item.type) } ?: titleMatch(key, item) ?: return MediaDetail(item)
        val movie = item.type == MediaType.Movie
        val path = if (movie) "/movie/$tmdbId" else "/tv/$tmdbId"
        val append = if (movie) "credits,release_dates,external_ids,reviews,recommendations" else "credits,content_ratings,external_ids,reviews,recommendations"
        val r = runCatching { http.get(tmdb.url(path, key, mapOf("language" to "en-GB", "append_to_response" to append)), tmdb.headers(key)) }
            .getOrNull()?.takeIf { it.ok } ?: return MediaDetail(item)
        val j = r.body
        val full = tmdbItem(j, item.type)
        val collection = if (movie) runCatching { tmdbCollection(key, j) }.getOrNull() else null
        val merged = item.copy(
            tmdbId = tmdbId,
            imdbId = item.imdbId ?: full?.imdbId,
            poster = item.poster ?: full?.poster,
            backdrop = full?.backdrop ?: item.backdrop,
            overview = full?.overview ?: item.overview,
            year = item.year ?: full?.year,
            rating = full?.rating ?: item.rating,
        )
        fun certFor(country: String) = if (movie) {
            j.child("release_dates").array("results").firstOrNull { it.str("iso_3166_1") == country }
                ?.array("release_dates")?.mapNotNull { it.str("certification")?.takeIf(String::isNotBlank) }?.firstOrNull()
        } else {
            j.child("content_ratings").array("results").firstOrNull { it.str("iso_3166_1") == country }?.str("rating")?.takeIf { it.isNotBlank() }
        }
        val cert = certFor("GB")
        val certUs = certFor("US")
        return MediaDetail(
            item = merged,
            tagline = j.str("tagline")?.takeIf { it.isNotBlank() },
            genres = j.array("genres").mapNotNull { it.str("name") },
            runtimeMin = j.int("runtime") ?: j.array("episode_run_time").firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() },
            certification = cert,
            certificationUs = certUs,
            cast = j.child("credits").array("cast").take(20).mapNotNull { c ->
                c.str("name")?.let { CastMember(it, c.str("character")?.takeIf { ch -> ch.isNotBlank() }, c.str("profile_path")?.let { p -> "https://image.tmdb.org/t/p/w185$p" }, c.int("id")) }
            },
            seasons = j.array("seasons").mapNotNull { s ->
                val n = s.int("season_number") ?: return@mapNotNull null
                if (n == 0) return@mapNotNull null
                SeasonInfo(n, "Season $n", s.int("episode_count") ?: 0, s.str("air_date")?.take(4)?.toIntOrNull())
            }.sortedBy { it.number },
            status = j.str("status"),
            directors = if (movie) j.child("credits").array("crew").filter { it.str("job") == "Director" }.mapNotNull { it.str("name") }.distinct()
                else j.array("created_by").mapNotNull { it.str("name") },
            writers = j.child("credits").array("crew").filter { it.str("department") == "Writing" }.mapNotNull { it.str("name") }.distinct().take(4),
            studio = (if (movie) j.array("production_companies") else j.array("networks")).firstOrNull()?.str("name"),
            released = (j.str("release_date") ?: j.str("first_air_date"))?.takeIf { it.isNotBlank() }?.let { d ->
                runCatching { LocalDate.parse(d).format(Dates.long) }.getOrDefault(d)
            },
            language = j.array("spoken_languages").firstOrNull()?.let { it.str("english_name") ?: it.str("name") },
            budget = j.long("budget")?.takeIf { it > 0 },
            revenue = j.long("revenue")?.takeIf { it > 0 },
            reviews = j.child("reviews").array("results").take(10).mapNotNull { r ->
                val text = r.str("content")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Review(
                    author = r.str("author") ?: "Someone",
                    rating = (r.child("author_details").child("rating") as? JsonPrimitive)?.doubleOrNull,
                    text = text,
                    date = r.str("created_at")?.take(10)?.let { d -> runCatching { LocalDate.parse(d).format(Dates.medium) }.getOrNull() },
                )
            },
            similar = j.child("recommendations").array("results").mapNotNull { tmdbItem(it, item.type) }.filter { it.poster != null }.take(20),
            collectionName = collection?.first,
            collection = collection?.second.orEmpty(),
        )
    }

    /** A film's TMDB collection (e.g. all the Dune films), in release order; null when it isn't in one. */
    private suspend fun tmdbCollection(key: String, j: JsonElement?): Pair<String, List<MediaItem>>? {
        val id = j.child("belongs_to_collection").int("id") ?: return null
        val c = runCatching { http.get(tmdb.url("/collection/$id", key, mapOf("language" to "en-GB")), tmdb.headers(key)) }.getOrNull()?.takeIf { it.ok }?.body ?: return null
        val parts = c.array("parts")
            .sortedWith(compareBy({ it.str("release_date").isNullOrBlank() }, { it.str("release_date") }))
            .mapNotNull { tmdbItem(it, MediaType.Movie) }
        if (parts.size < 2) return null
        return (c.str("name") ?: j.child("belongs_to_collection").str("name") ?: "Collection") to parts
    }

    /**
     * Ratings from more places: IMDb and Rotten Tomatoes (critics and audience) as your Plex server has them, and
     * Trakt's. TMDB's own is on the detail already.
     */
    private val ratingsCache = BoundedCache<String, Pair<Long, List<RatingBadge>>>(300)

    suspend fun extraRatings(item: MediaItem, copies: List<LibraryCopy>): List<RatingBadge> {
        ratingsCache[item.key]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return it.second }
        // Kept on the box too (Peter, 2026-11-18: ratings hardly change, but every start fetched them again for each
        // title you rest on): 7 days, or 1 day when there were none.
        val disk = ratingKey(item)
        loadRatings()
        diskRatings[disk]?.let { (day, list) ->
            if (System.currentTimeMillis() / 86_400_000L - day <= (if (list.isEmpty()) 1 else 7)) {
                ratingsCache[item.key] = System.currentTimeMillis() to list
                return list
            }
        }
        val got = fetchExtraRatings(item, copies)
        ratingsCache[item.key] = System.currentTimeMillis() to got
        diskRatings[disk] = System.currentTimeMillis() / 86_400_000L to got
        saveRatingsSoon()
        return got
    }

    // The ratings file ("ratings.json"): {"tmdb:Movie:603": [day, [[source, value, votes], …]]}, the newest 3,000.
    private val diskRatings = ConcurrentHashMap<String, Pair<Long, List<RatingBadge>>>()
    @Volatile private var ratingsLoaded = false
    private var ratingsSave: kotlinx.coroutines.Job? = null
    private val ratingsFile get() = java.io.File(appContext.filesDir, "ratings.json")
    private fun ratingKey(item: MediaItem) = item.tmdbId?.let { "tmdb:${item.type}:$it" } ?: item.imdbId?.let { "imdb:$it" } ?: item.key
    private fun loadRatings() {
        if (ratingsLoaded) return
        ratingsLoaded = true
        runCatching {
            val o = Json.parseToJsonElement(ratingsFile.readText()) as? JsonObject ?: return
            for ((k, v) in o) {
                val a = v as? JsonArray ?: continue
                val day = (a.getOrNull(0) as? JsonPrimitive)?.longOrNull ?: continue
                val list = (a.getOrNull(1) as? JsonArray).orEmpty().mapNotNull { r ->
                    val x = r as? JsonArray ?: return@mapNotNull null
                    val src = (x.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    RatingBadge(src, (x.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty(), (x.getOrNull(2) as? JsonPrimitive)?.contentOrNull)
                }
                diskRatings.putIfAbsent(k, day to list)
            }
        }
    }
    private fun saveRatingsSoon() {
        ratingsSave?.cancel()
        ratingsSave = appScope.launch {
            delay(5_000)
            runCatching {
                val keep = diskRatings.entries.sortedByDescending { it.value.first }.take(3_000)
                val o = buildJsonObject {
                    for ((k, v) in keep) put(k, buildJsonArray {
                        add(JsonPrimitive(v.first))
                        add(buildJsonArray { v.second.forEach { b -> add(buildJsonArray { add(JsonPrimitive(b.source)); add(JsonPrimitive(b.value)); b.votes?.let { add(JsonPrimitive(it)) } }) } })
                    })
                }
                val tmp = java.io.File(appContext.filesDir, "ratings.json.tmp")
                tmp.writeText(o.toString()); tmp.renameTo(ratingsFile)
            }
        }
    }

    /**
     * MDBList (with the user's key): IMDb, Rotten Tomatoes critics and audience, Metacritic, Letterboxd and Trakt for
     * any title, by its IMDb or TMDB id. Null without a key or an answer.
     */
    private val mdbCache = BoundedCache<String, Timed<List<RatingBadge>>>(300)

    private suspend fun mdblistRatings(a: Accounts, item: MediaItem): List<RatingBadge>? {
        val key = a.mdblistKey ?: return null
        mdbCache[item.key]?.takeIf { it.fresh }?.let { return it.value.ifEmpty { null } }
        val got = fetchMdblist(key, item)
        // No answer (network, 429, not listed) is asked again after a while; an answer keeps for the run.
        mdbCache[item.key] = Timed.forMs(got.orEmpty(), if (got == null) Timed.MISS_MS else Long.MAX_VALUE / 2)
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
        return (r.body as? JsonArray).orEmpty().mapNotNull { c ->
            val text = c.str("comment")?.trim()?.takeIf { it.length >= 40 } ?: return@mapNotNull null
            Review(
                author = c.child("user").str("username") ?: "Trakt user",
                rating = (c.child("user_rating") as? JsonPrimitive)?.doubleOrNull,
                text = text,
                date = c.str("created_at")?.take(10)?.let { d -> runCatching { LocalDate.parse(d).format(Dates.medium) }.getOrNull() },
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

    @Volatile private var xcCache: Triple<Long, List<XtreamVod>, List<XtreamSeries>>? = null
    private val xcLock = Mutex()

    /**
     * The Xtream Codes login for provider films and series: the IPTV provider itself, or, with Dispatcharr and
     * Provider VOD switched on in setup, Dispatcharr's own XC side (player_api.php) with this user's XC password
     * (Dispatcharr → Users → the user's XC password), read once with the API key.
     */
    private suspend fun xcSession(): Session? {
        val s = sessions.session.first() ?: return null
        if (s.isXc) return s
        if (s.isM3u) return null
        if (!store.current().vodEnabled) return null
        val id = "${s.serverUrl}|${s.username}|${s.apiKey}"
        dispatcharrXc?.takeIf { it.first == id }?.let { return it.second }
        val r = runCatching { http.get(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/", mapOf("X-API-Key" to s.apiKey)) }.getOrNull()
        if (r?.ok != true) return null
        val pass = r.body.child("custom_properties").str("xc_password")?.takeIf { it.isNotBlank() }
        val xc = pass?.let { s.copy(xcPassword = it) }
        // Only a found password is kept: one added in Dispatcharr later is picked up next time.
        if (xc == null) Log.w(TAG, "provider VOD: Dispatcharr user ${s.username} has no XC password") else dispatcharrXc = id to xc
        return xc
    }
    @Volatile private var dispatcharrXc: Pair<String, Session?>? = null

    // ---- Provider VOD through Dispatcharr: its own API pages, sorts and searches on the server, so the TV never
    // holds the whole list (20,000+ films froze the box when it was fetched in one go over XC).

    /** The Dispatcharr sign-in, when films and series come from Dispatcharr (Provider VOD on). */
    private suspend fun dispatcharrVod() = sessions.session.first()?.takeIf { !it.isDirect && store.current().vodEnabled }

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
            title = XtreamClient.cleanTitle(raw),
            year = j.int("year") ?: XtreamClient.yearIn(raw),
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

    private fun providerFilm(v: XtreamVod) = MediaItem(
        key = v.tmdbId?.let { "tmdb:Movie:$it" } ?: xcKey(MediaType.Movie, v.streamId),
        type = MediaType.Movie, title = v.name, year = v.year, poster = v.poster, rating = v.rating, tmdbId = v.tmdbId,
        origin = "IPTV",
    )

    private fun providerSeries(v: XtreamSeries) = MediaItem(
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
    private suspend fun xcCatalog(s: Session) = xcLock.withLock {
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
        return VodStream(url = xtream.movieUrl(s, id, parts.getOrNull(2) ?: "mp4"), title = title, watch = watch, boxPreviews = false, from = "IPTV")
    }

    /** A provider series' episodes as (season, episode) → stream address, from its library copy ("series:<id>"). */
    suspend fun providerEpisodes(ref: String): Map<Pair<Int, Int>, String> {
        val s = xcSession() ?: return emptyMap()
        val id = ref.removePrefix("series:").toIntOrNull() ?: return emptyMap()
        return runCatching { xtream.episodes(s, id) }.getOrDefault(emptyList())
            .associate { (it.season to it.episode) to xtream.episodeUrl(s, it) }
    }

    private val logoCache = ConcurrentHashMap<String, String>()

    // Title logos kept on the box (Peter, 2026-11-18: every start-up looked each one up again on TMDB, one at a time,
    // as you moved along). File "title_logos.json": {"Movie:603": ["https://…png" or "" (none), day found]}; found ones
    // kept 60 days, "none" 7. Read once, written a few seconds after the last new one.
    private val logoFile get() = java.io.File(appContext.filesDir, "title_logos.json")
    private val logoDays = ConcurrentHashMap<String, Long>()
    @Volatile private var logosLoaded = false
    private var logoSave: kotlinx.coroutines.Job? = null
    private fun loadLogos() {
        if (logosLoaded) return
        logosLoaded = true
        runCatching {
            val today = System.currentTimeMillis() / 86_400_000L
            val o = Json.parseToJsonElement(logoFile.readText()) as? JsonObject ?: return
            for ((k, v) in o) {
                val a = v as? JsonArray ?: continue
                val url = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: continue
                val day = (a.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: continue
                if (today - day <= (if (url.isEmpty()) 7 else 60)) { logoCache.putIfAbsent(k, url); logoDays[k] = day }
            }
        }
    }
    private fun saveLogosSoon() {
        logoSave?.cancel()
        logoSave = appScope.launch {
            delay(5_000)
            runCatching {
                val o = buildJsonObject {
                    for ((k, url) in logoCache) put(k, buildJsonArray { add(JsonPrimitive(url)); add(JsonPrimitive(logoDays[k] ?: (System.currentTimeMillis() / 86_400_000L))) })
                }
                val tmp = java.io.File(appContext.filesDir, "title_logos.json.tmp")
                tmp.writeText(o.toString()); tmp.renameTo(logoFile)
            }
        }
    }

    /**
     * The title's logo (its name as artwork, transparent) from TMDB: English first, then one without text language,
     * then any; the best voted. Null when TMDB has none.
     */
    suspend fun logoFor(item: MediaItem): String? {
        val id = item.tmdbId ?: tmdbIdFor(item) ?: return null
        val cacheKey = "${item.type}:$id"
        loadLogos()
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
        logoDays[cacheKey] = System.currentTimeMillis() / 86_400_000L
        saveLogosSoon()
        return url
    }

    /**
     * The film or show a guide programme is, on TMDB: by title (without "New: " / "Live"), a series number meaning
     * a show, a year (films) meaning a film first. Only a close title match counts: a wrong guess is worse than none.
     */
    suspend fun guideMatch(title: String, season: Int?, year: Int?): MediaItem? {
        val key = store.current().tmdbKey ?: return null
        val q = title.removePrefix("New: ").replace(Regex("^Live:?\\s+", RegexOption.IGNORE_CASE), "").substringBefore(" - ").trim()
        if (q.isEmpty()) return null
        fun norm(s: String) = s.lowercase().replace("&", "and").replace(NOT_ALNUM, "")
        val types = when { season != null -> listOf(MediaType.Show); year != null -> listOf(MediaType.Movie, MediaType.Show); else -> listOf(MediaType.Show, MediaType.Movie) }
        for (type in types) {
            val path = if (type == MediaType.Movie) "/search/movie" else "/search/tv"
            val params = buildMap { put("language", "en-GB"); put("query", q); put("include_adult", "false"); if (type == MediaType.Movie && year != null) put("year", year.toString()) }
            val r = runCatching { http.get(tmdb.url(path, key, params), tmdb.headers(key)) }.getOrNull()?.takeIf { it.ok } ?: continue
            // With a year known, it has to agree (a film: that year; a show: started within a year of it), so a
            // 2024 film called "Trap" isn't taken for the 2022 series of the same name.
            val hit = r.body.array("results").mapNotNull { tmdbItem(it, type) }.firstOrNull { c ->
                norm(c.title) == norm(q) && (year == null || season != null || c.year == null || abs(c.year - year) <= 1)
            }
            if (hit != null) { tmdbCache[hit.key] = hit; return hit }
        }
        return null
    }

    /**
     * A title with no TMDB / IMDb id (IPTV films and series), found by name: the same close match as the guide's
     * (its name, and its year within one when known), its type only. Null rather than a wrong guess.
     */
    private val titleMatchCache = BoundedCache<String, Timed<Int>>(1000)
    private suspend fun titleMatch(key: String, item: MediaItem): Int? {
        val q = item.title.trim().takeIf { it.isNotEmpty() } ?: return null
        val cacheKey = "${item.type}|$q|${item.year}"
        titleMatchCache[cacheKey]?.takeIf { it.fresh }?.let { return it.value.takeIf { id -> id > 0 } }
        fun norm(s: String) = s.lowercase().replace("&", "and").replace(NOT_ALNUM, "")
        val movie = item.type == MediaType.Movie
        val params = buildMap {
            put("language", "en-GB"); put("query", q); put("include_adult", "false")
            if (item.year != null) put(if (movie) "year" else "first_air_date_year", item.year.toString())
        }
        // A search that failed (network, 429, 5xx) isn't "no match": that's remembered only briefly.
        var failed = false
        suspend fun search(p: Map<String, String>) = runCatching { http.get(tmdb.url(if (movie) "/search/movie" else "/search/tv", key, p), tmdb.headers(key)) }
            .getOrNull()?.takeIf { it.ok }.also { if (it == null) failed = true }?.body.array("results").mapNotNull { tmdbItem(it, item.type) }
        // With its year first; a provider's year can be off by one (UK vs US release), so then without it.
        val found = (search(params).ifEmpty { if (item.year != null) search(params - "year" - "first_air_date_year") else emptyList() })
            .firstOrNull { c -> norm(c.title) == norm(q) && (item.year == null || c.year == null || abs(c.year - item.year) <= 1) }
        val id = found?.tmdbId
        titleMatchCache[cacheKey] = Timed.forMs(id ?: 0, if (id == null && failed) Timed.MISS_MS else Long.MAX_VALUE / 2)
        return id
    }

    /** A title's TMDB id: its own, or looked up from its IMDb id (Stremio addons such as AIOStreams use IMDb ids). */
    /**
     * A picture of [item] that needs no sign-in (TMDB's backdrop, else its poster), for the TV home screen's Watch Next
     * row: a Plex / Jellyfin / Emby picture link carries the server's token, so it's never handed to the launcher, and
     * those cards showed a broken picture (Peter, 2026-11-17). Null: no TMDB key or nothing found. Kept per title.
     */
    suspend fun publicArt(item: MediaItem): Pair<String, Boolean>? {
        val cacheKey = item.type.name + ":" + (item.tmdbId ?: item.imdbId ?: item.key)
        publicArtCache[cacheKey]?.let { return it.takeIf { a -> a.first.isNotEmpty() } }
        val key = store.current().tmdbKey ?: return null
        val id = runCatching { tmdbIdFor(item) }.getOrNull()
        val r = id?.let { runCatching { http.get(tmdb.url(if (item.type == MediaType.Movie) "/movie/$it" else "/tv/$it", key), tmdb.headers(key)) }.getOrNull()?.takeIf { x -> x.ok } }
        val art = r?.body?.str("backdrop_path")?.let { "${TMDB_IMG}w780$it" to true }
            ?: r?.body?.str("poster_path")?.let { "${TMDB_IMG}w500$it" to false }
        publicArtCache[cacheKey] = art ?: ("" to false)
        return art
    }
    private val publicArtCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()

    /**
     * A streaming service's or TV network's logo from TMDB (the same ids Viewarr's AIOMetadata catalogues use), by the
     * name its rows start with ("Netflix · New" → Netflix). Second: whether it's an app icon (square, full colour)
     * rather than a network's transparent logo. Null: not a known service, or no TMDB key.
     */
    suspend fun serviceLogo(rowTitle: String): Pair<String, Boolean>? {
        val name = rowTitle.substringBefore(" · ").trim().lowercase()
        serviceLogoCache[name]?.let { return it.takeIf { a -> a.first.isNotEmpty() } }
        val key = store.current().tmdbKey ?: return null
        val net = SERVICE_NETWORKS[name]
        val prov = SERVICE_PROVIDERS[name]
        val out = when {
            // Streaming services: their app icon (what people recognise them by).
            prov != null -> {
                val list = providerLogos ?: runCatching {
                    http.get(tmdb.url("/watch/providers/tv", key, mapOf("watch_region" to "GB")), tmdb.headers(key)).takeIf { it.ok }?.body
                        .array("results").mapNotNull { p -> p.int("provider_id")?.let { id -> p.str("logo_path")?.let { id to it } } }.toMap()
                }.getOrNull()?.also { providerLogos = it }
                list?.get(prov)?.let { "${TMDB_IMG}w300$it" to true }
            }
            net != null -> runCatching {
                http.get(tmdb.url("/network/$net", key), tmdb.headers(key)).takeIf { it.ok }?.body?.str("logo_path")?.let { "${TMDB_IMG}w300$it" to false }
            }.getOrNull()
            else -> null
        }
        serviceLogoCache[name] = out ?: ("" to false)
        return out
    }
    private val serviceLogoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()
    @Volatile private var providerLogos: Map<Int, String>? = null

    /** A title's name and poster from TMDB by its id ([type] "Movie" / "Show"), kept per title; null: no TMDB key. */
    suspend fun tmdbName(type: String, id: Int): Pair<String, String?>? {
        val cacheKey = "$type:$id"
        tmdbNameCache[cacheKey]?.let { return it }
        val key = store.current().tmdbKey ?: return null
        val movie = type.equals("Movie", ignoreCase = true)
        val r = runCatching { http.get(tmdb.url(if (movie) "/movie/$id" else "/tv/$id", key), tmdb.headers(key)) }.getOrNull()?.takeIf { it.ok } ?: return null
        val name = (if (movie) r.body.str("title") else r.body.str("name")) ?: return null
        return (name to r.body.str("poster_path")?.let { "${TMDB_IMG}w342$it" }).also { tmdbNameCache[cacheKey] = it }
    }
    private val tmdbNameCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()

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

/** Copy watched to Plex: how far back Trakt watches are copied. */
private const val COPY_DAYS = 30

/** TMDB watch-provider ids of the streaming services in Viewarr's catalogues (as AIOMetadata has them). */
private val SERVICE_PROVIDERS = mapOf(
    "netflix" to 8, "prime video" to 9, "disney+" to 337, "apple tv+" to 350, "hbo max" to 1899, "now" to 39,
    "bbc iplayer" to 38, "itvx" to 41, "paramount+" to 531, "sky go" to 29, "uktv play" to 137, "my5" to 333,
    "discovery+" to 524, "crunchyroll" to 283, "shudder" to 99, "hulu" to 15, "peacock" to 386,
)
/** TMDB network ids of the TV channels in Viewarr's catalogues (as AIOMetadata has them). */
private val SERVICE_NETWORKS = mapOf(
    "bbc one" to 4, "bbc two" to 332, "itv1" to 9, "channel 4" to 26, "channel 5" to 99, "sky atlantic" to 1063,
    "hbo" to 49, "fx" to 88, "amc" to 174, "fox" to 19, "nbc" to 6, "cbs" to 16, "abc" to 2,
)
