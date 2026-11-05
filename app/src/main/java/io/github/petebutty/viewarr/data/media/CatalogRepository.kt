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
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
) {
    private val tmdbCache = ConcurrentHashMap<String, MediaItem>()
    private val tmdbLimit = Semaphore(6)
    private val indexLock = Mutex()
    @Volatile private var index: Map<String, List<LibraryCopy>>? = null
    @Volatile private var traktWatchlist: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun rows(type: MediaType): Flow<RowsState> = channelFlow {
        val a = store.current()
        val traktAuth = a.trakt?.let { freshTrakt(it) }
        val slots: List<suspend () -> List<MediaRow>> = buildList {
            a.plex?.let { p -> add { listOfNotNull(plexOnDeck(a, p, type)) } }
            a.jellyfin?.let { j -> add { listOfNotNull(serverResume(a, j, emby = false, type)) } }
            a.emby?.let { e -> add { listOfNotNull(serverResume(a, e, emby = true, type)) } }
            traktAuth?.let { t -> add { listOfNotNull(traktWatchlistRow(a, t, type)) } }
            a.plex?.let { p -> add { plexRecent(a, p, type) } }
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
        }
        val found = arrayOfNulls<List<MediaRow>>(slots.size)
        val lock = Mutex()
        var finished = 0
        slots.forEachIndexed { i, slot ->
            launch {
                val result = runCatching { withTimeoutOrNull(25_000) { slot() } }
                    .onFailure { Log.w(TAG, "row ${type.name}#$i failed: ${it.javaClass.simpleName} ${it.message}") }
                    .getOrNull().orEmpty().filter { it.items.isNotEmpty() }
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
        val slots: List<suspend () -> List<MediaRow>> = buildList {
            a.plex?.let { p -> add { listOfNotNull(plexSearch(a, p, q)) } }
            a.tmdbKey?.let { k ->
                add { listOfNotNull(tmdbSearch(k, MediaType.Movie, q)) }
                add { listOfNotNull(tmdbSearch(k, MediaType.Show, q)) }
            }
            a.activeAddons.forEach { addon -> add { stremioSearch(addon, q) } }
        }
        val found = arrayOfNulls<List<MediaRow>>(slots.size)
        val lock = Mutex()
        var finished = 0
        slots.forEachIndexed { i, slot ->
            launch {
                val result = runCatching { withTimeoutOrNull(20_000) { slot() } }
                    .onFailure { Log.w(TAG, "search #$i failed: ${it.javaClass.simpleName}") }
                    .getOrNull().orEmpty().filter { it.items.isNotEmpty() }
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
            key = tmdbId?.let { "tmdb:${type.name}:$it" } ?: "plex:$ratingKey",
            type = type,
            title = j.str("title") ?: return null,
            year = j.int("year"),
            poster = plexImage(p, j.str("thumb"), 240, 360),
            backdrop = plexImage(p, j.str("art"), 960, 540),
            overview = j.str("summary")?.takeIf { it.isNotBlank() },
            tmdbId = tmdbId,
            imdbId = imdbId,
            rating = (j.child("audienceRating") as? JsonPrimitive)?.doubleOrNull,
            origin = "Plex",
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
                    val items = plexGet(a, p, "/library/sections/$key/all?sort=addedAt:desc&includeGuids=1&X-Plex-Container-Start=0&X-Plex-Container-Size=40")
                        ?.array("Metadata").orEmpty().mapNotNull { plexItem(p, it, type) }
                    val name = s.str("title") ?: "Plex"
                    MediaRow("plex-recent-$key", if (sections.size == 1) "Recently added" else "Recently added in $name", items)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun plexOnDeck(a: Accounts, p: PlexAuth, type: MediaType): MediaRow? {
        val all = plexGet(a, p, "/library/onDeck?includeGuids=1")?.array("Metadata").orEmpty()
        val items = coroutineScope {
            all.filter { it.str("type") == if (type == MediaType.Movie) "movie" else "episode" }.take(20).map { j ->
                async {
                    val progress = j.long("viewOffset")?.let { off -> j.long("duration")?.takeIf { it > 0 }?.let { off.toFloat() / it } }
                    if (type == MediaType.Movie) plexItem(p, j, type)?.copy(progress = progress)
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
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return MediaRow("plex-deck-${type.name}", "Continue watching", items)
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
            if (type == MediaType.Movie) serverItem(s, j, type, name)?.copy(progress = pct)
            else serverItem(s, buildJsonObject {
                put("Id", j.str("SeriesId"))
                put("Name", j.str("SeriesName"))
            }, type, name)?.copy(
                key = "${name.lowercase()}:${j.str("SeriesId")}:resume",
                nextSeason = j.int("ParentIndexNumber"),
                nextEpisode = j.int("IndexNumber"),
                subtitle = "S${j.int("ParentIndexNumber") ?: "?"} · E${j.int("IndexNumber") ?: "?"}  ${j.str("Name").orEmpty()}",
                progress = pct,
            )
        }
        return MediaRow("$name-resume-${type.name}", "Continue watching on $name", enrich(a, items))
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

    // ------------------------------------------------------------------ library index (In your library)

    private fun indexKeys(type: MediaType, tmdbId: Int?, imdbId: String?) =
        listOfNotNull(tmdbId?.let { "${type.name}:tmdb:$it" }, imdbId?.let { "${type.name}:imdb:$it" })

    /** Which of the user's own servers have this title. Built once per run from Plex and Jellyfin/Emby. */
    suspend fun copiesOf(item: MediaItem): List<LibraryCopy> {
        val idx = index ?: indexLock.withLock { index ?: buildIndex().also { index = it } }
        return indexKeys(item.type, item.tmdbId, item.imdbId).flatMap { idx[it].orEmpty() }.distinctBy { it.source }
    }

    private suspend fun buildIndex(): Map<String, List<LibraryCopy>> {
        val a = store.current()
        val out = HashMap<String, MutableList<LibraryCopy>>()
        fun add(type: MediaType, tmdbId: Int?, imdbId: String?, copy: LibraryCopy) =
            indexKeys(type, tmdbId, imdbId).forEach { out.getOrPut(it) { ArrayList() }.add(copy) }
        a.plex?.let { p ->
            for (type in MediaType.entries) for (s in runCatching { plexSections(a, p, type) }.getOrDefault(emptyList())) {
                val key = s.str("key") ?: continue
                runCatching { plexGet(a, p, "/library/sections/$key/all?includeGuids=1") }.getOrNull()?.array("Metadata")?.forEach { j ->
                    val (tmdbId, imdbId) = guidIds(j)
                    val res = j.array("Media").firstOrNull()?.str("videoResolution")
                    add(type, tmdbId, imdbId, LibraryCopy("Plex", res?.let { if (it.all(Char::isDigit)) "${it}p" else it.uppercase() }, j.str("ratingKey")))
                }
            }
        }
        listOfNotNull(a.jellyfin?.let { it to false }, a.emby?.let { it to true }).forEach { (s, emby) ->
            val r = runCatching {
                http.get(
                    "${s.serverUrl}/Users/${s.userId}/Items?IncludeItemTypes=Movie,Series&Recursive=true&Fields=ProviderIds",
                    mediaServers.headers(emby, a.deviceId, s.token),
                )
            }.getOrNull()?.takeIf { it.ok } ?: return@forEach
            r.body.array("Items").forEach { j ->
                val type = if (j.str("Type") == "Series") MediaType.Show else MediaType.Movie
                add(type, j.child("ProviderIds").str("Tmdb")?.toIntOrNull(), j.child("ProviderIds").str("Imdb"), LibraryCopy(if (emby) "Emby" else "Jellyfin"))
            }
        }
        Log.i(TAG, "library index: ${out.size} keys")
        return out
    }

    // ------------------------------------------------------------------ playback sources

    /**
     * A Plex item to play: the file itself when this TV can decode it, with Plex transcoding (HLS) as the fallback.
     * Files the TV can't handle at all (e.g. AVI, VC-1 on some boxes) go straight to transcoding.
     */
    suspend fun plexStream(ratingKey: String, watch: WatchTarget? = null): VodStream? {
        val a = store.current()
        val p = a.plex ?: return null
        val m = plexGet(a, p, "/library/metadata/$ratingKey")?.array("Metadata")?.firstOrNull() ?: return null
        val media = m.array("Media").firstOrNull()
        val part = media?.array("Part")?.firstOrNull()?.str("key") ?: return null
        val title = m.str("title") ?: "Plex"
        val headers = plex.headers(a.deviceId, p.token)
        val resume = m.long("viewOffset") ?: 0
        val target = (watch ?: WatchTarget(MediaType.Movie)).copy(plexRatingKey = ratingKey)
        val quality = playbackStore.current().plexQuality
        val transcode = VodStream(plexTranscodeUrl(a, p, ratingKey, quality.maxKbps ?: 20_000), "$title (converted by Plex)", headers, resume, watch = target)
        // Settings → Playback → Plex quality: anything but Original always converts at that bitrate.
        if (quality.maxKbps != null) return transcode
        val direct = VodStream("${p.serverUrl}$part?X-Plex-Token=${enc(p.token)}", title, headers, resume, fallback = transcode, watch = target)
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

    /** Tells Plex where you are (so Continue watching and "watched" stay in sync); marks it watched near the end. */
    suspend fun reportPlex(ratingKey: String, state: String, positionMs: Long, durationMs: Long) {
        val a = store.current()
        val p = a.plex ?: return
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
        val p = a.plex ?: return emptyList()
        val showKey = plexShowKey ?: return emptyList()
        return plexGet(a, p, "/library/metadata/$showKey/allLeaves")?.array("Metadata").orEmpty()
            .filter { it.int("parentIndex") == season }
            .mapNotNull { e ->
                val n = e.int("index") ?: return@mapNotNull null
                EpisodeInfo(season, n, e.str("title") ?: "Episode $n", e.str("summary")?.takeIf { it.isNotBlank() },
                    plexImage(p, e.str("thumb"), 400, 225), e.str("originallyAvailableAt"), e.long("duration")?.let { (it / 60_000).toInt() })
            }
    }

    /** Seasons from Plex when TMDB has none (no key, or an unmatched show). */
    suspend fun plexSeasons(showKey: String): List<SeasonInfo> {
        val a = store.current()
        val p = a.plex ?: return emptyList()
        return plexGet(a, p, "/library/metadata/$showKey/children")?.array("Metadata").orEmpty().mapNotNull { s ->
            val n = s.int("index") ?: return@mapNotNull null
            if (n == 0) null else SeasonInfo(n, s.str("title") ?: "Season $n", s.int("leafCount") ?: 0, s.int("year"))
        }
    }

    /** Every episode of a show in Plex, by (season, episode). Cached per show for this run. */
    suspend fun plexEpisodes(showKey: String): Map<Pair<Int, Int>, PlexEpisode> {
        plexEpisodeCache[showKey]?.let { return it }
        val a = store.current()
        val p = a.plex ?: return emptyMap()
        val map = plexGet(a, p, "/library/metadata/$showKey/allLeaves")?.array("Metadata").orEmpty().mapNotNull { e ->
            val s = e.int("parentIndex") ?: return@mapNotNull null
            val n = e.int("index") ?: return@mapNotNull null
            val rk = e.str("ratingKey") ?: return@mapNotNull null
            (s to n) to PlexEpisode(rk, e.long("viewOffset") ?: 0, (e.int("viewCount") ?: 0) > 0)
        }.toMap()
        plexEpisodeCache[showKey] = map
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
        val append = if (movie) "credits,release_dates,external_ids" else "credits,content_ratings,external_ids"
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
            cast = j.child("credits").array("cast").take(8).mapNotNull { c -> c.str("name")?.let { CastMember(it, c.str("character")) } },
            seasons = j.array("seasons").mapNotNull { s ->
                val n = s.int("season_number") ?: return@mapNotNull null
                if (n == 0) return@mapNotNull null
                SeasonInfo(n, s.str("name") ?: "Season $n", s.int("episode_count") ?: 0, s.str("air_date")?.take(4)?.toIntOrNull())
            },
            status = j.str("status"),
        )
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

    private suspend fun findByImdb(key: String, imdbId: String, type: MediaType): Int? = runCatching {
        val r = http.get(tmdb.url("/find/$imdbId", key, mapOf("external_source" to "imdb_id")), tmdb.headers(key))
        r.body.array(if (type == MediaType.Movie) "movie_results" else "tv_results").firstOrNull().int("id")
    }.getOrNull()
}
