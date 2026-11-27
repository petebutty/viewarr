package io.github.petebutty.viewarr.data.sync

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.local.LocalWatch
import io.github.petebutty.viewarr.data.local.LocalWatchStore
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.MediaServerService
import io.github.petebutty.viewarr.data.remote.services.PlexService
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.long
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * "Sync IPTV progress with my Emby / Jellyfin / Plex" done by the TV itself, for houses without the Viewarr plugin
 * (or while its service is stopped). With the plugin running ([ServerSports.pluginAlive]) this does nothing: the
 * server does it for the whole house (viewarr-plugin/viewarr/watchsync.py), and this follows the same rules.
 *
 * While Viewarr runs and the switch is on, every 15 minutes and a little after an IPTV play stops, for this person's
 * own main Plex, Jellyfin and Emby (not friends' shared servers):
 *  - server → TV: films and episodes played or part played there (found by TMDB id) go into the IPTV progress when
 *    newer, for titles already here (or another episode of a show that's here);
 *  - TV → server: what's newer here is marked there (watched, or the resume point).
 * The newer change wins (5 s leeway between clocks). Gentle: at most [MAX_PUSH] marks and [MAX_PULL] changes taken in
 * per server per round; what's been sent (or isn't in that library) is remembered so it's never sent twice; the
 * library's TMDB list is only fetched when something new needs finding, and kept [INDEX_TTL_MS] (on disk too); a
 * server that fails is left alone for longer each time (up to 4 hours). No retries within a round.
 */
@Singleton
class TvServerWatch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LocalWatchStore,
    private val accounts: AccountStore,
    private val http: HttpJson,
    private val plexApi: PlexService,
    private val mediaApi: MediaServerService,
) {
    /** One title's state on a server: status "watched" / "watching", position and length (s), when (epoch s). */
    private class Remote(val status: String, val pos: Long, val dur: Long, val at: Long, val id: String)

    private class Srv(val kind: String, val url: String, val userId: String?, token: String, val headers: Map<String, String>) {
        val plex get() = kind == "plex"
        /** Who on which server (different people on one TV get their own memory). */
        val ident = kind + "|" + url.trimEnd('/') + "|" + (userId ?: token.takeLast(6))
        val base = url.trimEnd('/')
    }

    private class Index(val at: Long, val movies: Map<Int, String>, val shows: Map<Int, String>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val prefs by lazy { context.getSharedPreferences("tv_server_watch", Context.MODE_PRIVATE) }
    private val indexes = ConcurrentHashMap<String, Index>()
    private val fails = ConcurrentHashMap<String, Int>()
    private val restAt = ConcurrentHashMap<String, Long>()
    private var started = false
    @Volatile private var lastRound = 0L
    @Volatile private var lastChange = 0L
    @Volatile private var seenChanged = 0L

    fun start() {
        if (started) return
        started = true
        requestRound = { scope.launch { delay(3_000); runCatching { round() }.onFailure { Log.w(TAG, "round failed: ${it.message}") } } }
        // A play stopped or a tick by hand (something here newer than before): a round once things settle.
        scope.launch {
            store.entries.collect { list ->
                val m = list.maxOfOrNull { it.changed } ?: 0L
                if (m > seenChanged) {
                    if (seenChanged > 0 && !lock.isLocked) lastChange = System.currentTimeMillis()
                    seenChanged = m
                }
            }
        }
        scope.launch {
            delay(60_000)
            while (true) {
                io.github.petebutty.viewarr.AppVisible.await()
                val now = System.currentTimeMillis()
                val settled = lastChange > lastRound && now - lastChange >= SETTLE_MS && now - lastRound >= MIN_GAP_MS
                if ((now - lastRound >= ROUND_MS || settled) && active()) {
                    runCatching { round() }.onFailure { Log.w(TAG, "round failed: ${it.message}") }
                }
                delay(30_000)
            }
        }
    }

    /** The switch on (and Trakt not connected), the accounts read, and no plugin doing it for the house. */
    private fun active() = WatchSync.serversOn && WatchSource.known && ServerSports.tvDoesJobs

    /** One round with each server. */
    suspend fun round() = lock.withLock {
        if (!active()) return@withLock
        val servers = servers(accounts.current())
        if (servers.isEmpty()) return@withLock
        lastRound = System.currentTimeMillis()
        var pulled = 0
        var pushed = 0
        var failed = 0
        for (s in servers) {
            if ((restAt[s.ident] ?: 0L) > System.currentTimeMillis()) continue
            val remote = try { if (s.plex) plexState(s) else serverState(s) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "${s.kind} unreachable: ${e.message}"); rest(s); failed++; continue
            }
            val done = doneOf(s)
            pulled += pull(remote, done)
            val p = push(s, remote, done)
            saveDone(s, done)
            if (p < 0) { rest(s); failed++ } else { fails.remove(s.ident); pushed += p }
        }
        seenChanged = maxOf(seenChanged, store.entries.value.maxOfOrNull { it.changed } ?: 0L)
        status = "Last check on this TV: ${io.github.petebutty.viewarr.ui.theme.Dates.time(System.currentTimeMillis())}" +
            (if (pulled + pushed > 0) ", $pulled taken in, $pushed marked" else ", nothing new") + (if (failed > 0) ", $failed not reached" else "")
        if (pulled + pushed + failed > 0) Log.i(TAG, "round: $pulled taken in, $pushed marked, $failed not reached")
    }

    /** This person's own main servers: the Plex linked in setup (if not switched off), Jellyfin, Emby. */
    private fun servers(a: Accounts): List<Srv> = listOfNotNull(
        a.plex?.takeIf { it.machineId !in a.plexOff }?.let { Srv("plex", it.serverUrl, null, it.token, plexApi.headers(a.deviceId, it.token)) },
        a.jellyfin?.let { Srv("jellyfin", it.serverUrl, it.userId, it.token, mediaApi.headers(false, a.deviceId, it.token)) },
        a.emby?.let { Srv("emby", it.serverUrl, it.userId, it.token, mediaApi.headers(true, a.deviceId, it.token)) },
    )

    /** Left alone for 30 min, 1 h, 2 h, then 4 h after each failure in a row. */
    private fun rest(s: Srv) {
        val n = (fails[s.ident] ?: 0) + 1
        fails[s.ident] = n
        restAt[s.ident] = System.currentTimeMillis() + ROUND_MS * (1L shl n.coerceAtMost(4))
    }

    private suspend fun get(s: Srv, path: String): JsonElement? {
        val r = http.get(s.base + path, s.headers)
        if (!r.ok) throw java.io.IOException("HTTP ${r.code}")
        return r.body
    }

    // ---- Jellyfin / Emby

    private fun tmdbOf(i: JsonElement?): Int? {
        val p = i.child("ProviderIds")
        return (p.str("Tmdb") ?: p.str("tmdb") ?: p.str("TMDB"))?.toIntOrNull()
    }

    private fun epoch(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return runCatching { java.time.OffsetDateTime.parse(s).toEpochSecond() }
            .recoverCatching { java.time.LocalDateTime.parse(s).toEpochSecond(java.time.ZoneOffset.UTC) }.getOrDefault(0L)
    }

    /** Played and part played films and episodes there (the most recent [SERVER_RECENT] of each). */
    private suspend fun serverState(s: Srv): Map<String, Remote> {
        val fields = "ProviderIds,UserData,SeriesId,ParentIndexNumber,IndexNumber,RunTimeTicks"
        val got = LinkedHashMap<String, JsonElement>()
        for (f in listOf("IsPlayed", "IsResumable")) {
            get(s, "/Users/${s.userId}/Items?Recursive=true&IncludeItemTypes=Movie,Episode&Filters=$f&Fields=$fields" +
                "&SortBy=DatePlayed&SortOrder=Descending&Limit=$SERVER_RECENT").array("Items").forEach { i -> i.str("Id")?.let { got[it] = i } }
        }
        val seriesTmdb = HashMap<String, Int?>()
        got.values.filter { it.str("Type") == "Episode" }.mapNotNull { it.str("SeriesId") }.distinct().chunked(100).forEach { ids ->
            get(s, "/Users/${s.userId}/Items?Ids=${ids.joinToString(",")}&Fields=ProviderIds").array("Items")
                .forEach { x -> x.str("Id")?.let { seriesTmdb[it] = tmdbOf(x) } }
        }
        val out = HashMap<String, Remote>()
        for (i in got.values) {
            val ud = i.child("UserData")
            val pos = (ud.long("PlaybackPositionTicks") ?: 0L) / TICKS
            val played = ((ud as? JsonObject)?.get("Played") as? JsonPrimitive)?.booleanOrNull == true
            val status = if (played) "watched" else if (pos > 0) "watching" else continue
            val id = i.str("Id") ?: continue
            val r = Remote(status, pos, (i.long("RunTimeTicks") ?: 0L) / TICKS, epoch(ud.str("LastPlayedDate")), id)
            if (i.str("Type") == "Movie") { tmdbOf(i)?.let { out["tmdb_movie_$it"] = r }; continue }
            val t = seriesTmdb[i.str("SeriesId")] ?: continue
            val se = i.int("ParentIndexNumber") ?: continue
            val ep = i.int("IndexNumber") ?: continue
            out[epKey(t, se, ep)] = r
        }
        return out
    }

    private suspend fun serverMark(s: Srv, id: String, status: String, posS: Long): Boolean {
        val uid = s.userId ?: return false
        val (legacy, newer, body) = if (status == "watched") Triple("/Users/$uid/PlayedItems/$id", "/UserPlayedItems/$id?userId=$uid", null)
        else Triple("/Users/$uid/Items/$id/UserData", "/UserItems/$id/UserData?userId=$uid",
            buildJsonObject { put("PlaybackPositionTicks", posS * TICKS); put("Played", false) })
        val r = http.post(s.base + legacy, body, s.headers)
        // (Newer Jellyfin moved these; the old address answers 404 there.)
        if (r.code == 404 && s.kind == "jellyfin") return http.post(s.base + newer, body, s.headers).ok
        return r.ok
    }

    private suspend fun serverEpisode(s: Srv, show: String, se: Int, ep: Int): String? =
        get(s, "/Shows/$show/Episodes?season=$se&userId=${s.userId}").array("Items").firstOrNull { it.int("IndexNumber") == ep }?.str("Id")

    // ---- Plex (tmdb:// guids; scrobble, and the progress call for a resume point)

    private fun plexTmdb(i: JsonElement?): Int? =
        i.array("Guid").firstNotNullOfOrNull { g -> g.str("id")?.takeIf { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toIntOrNull() }

    private suspend fun plexSections(s: Srv): List<Pair<String, String>> =
        get(s, "/library/sections").child("MediaContainer").array("Directory")
            .mapNotNull { d -> val k = d.str("key") ?: return@mapNotNull null; d.str("type")?.takeIf { it == "movie" || it == "show" }?.let { k to it } }

    /** Every item of a Plex list, [PAGE] at a time. */
    private suspend fun plexAll(s: Srv, path: String): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        val sep = if ('?' in path) '&' else '?'
        var start = 0
        while (true) {
            val mc = get(s, "$path${sep}X-Plex-Container-Start=$start&X-Plex-Container-Size=$PAGE").child("MediaContainer")
            val items = mc.array("Metadata")
            out += items
            start += items.size
            val total = mc.int("totalSize") ?: mc.int("size") ?: 0
            if (items.isEmpty() || start >= total || items.size < PAGE || start >= MAX_LIST) return out
        }
    }

    /** Watched / part watched on Plex in the last 30 days, and anything on deck. */
    private suspend fun plexState(s: Srv): Map<String, Remote> {
        val since = System.currentTimeMillis() / 1000 - 30 * 86_400L
        val items = LinkedHashMap<String, JsonElement>()
        for ((key, kind) in plexSections(s)) {
            plexAll(s, "/library/sections/$key/all?type=${if (kind == "movie") 1 else 4}&includeGuids=1&lastViewedAt%3E%3E=$since")
                .forEach { i -> i.str("ratingKey")?.let { items[it] = i } }
        }
        get(s, "/library/onDeck?includeGuids=1").child("MediaContainer").array("Metadata").forEach { i -> i.str("ratingKey")?.let { items.putIfAbsent(it, i) } }
        val showTmdb = HashMap<String, Int?>()
        items.values.filter { it.str("type") == "episode" }.mapNotNull { it.str("grandparentRatingKey") }.distinct().chunked(50).forEach { ks ->
            get(s, "/library/metadata/${ks.joinToString(",")}?includeGuids=1").child("MediaContainer").array("Metadata")
                .forEach { sh -> sh.str("ratingKey")?.let { showTmdb[it] = plexTmdb(sh) } }
        }
        val out = HashMap<String, Remote>()
        for (i in items.values) {
            val pos = (i.long("viewOffset") ?: 0L) / 1000
            val status = if (pos > 0) "watching" else if ((i.int("viewCount") ?: 0) > 0) "watched" else continue
            val rk = i.str("ratingKey") ?: continue
            val r = Remote(status, pos, (i.long("duration") ?: 0L) / 1000, i.long("lastViewedAt") ?: 0L, rk)
            when (i.str("type")) {
                "movie" -> plexTmdb(i)?.let { out["tmdb_movie_$it"] = r }
                "episode" -> {
                    val t = showTmdb[i.str("grandparentRatingKey")] ?: continue
                    val se = i.int("parentIndex") ?: continue
                    val ep = i.int("index") ?: continue
                    out[epKey(t, se, ep)] = r
                }
            }
        }
        return out
    }

    private suspend fun plexMark(s: Srv, rk: String, status: String, posS: Long): Boolean {
        val q = "key=$rk&identifier=com.plexapp.plugins.library"
        val path = if (status == "watched") "/:/scrobble?$q" else "/:/progress?$q&time=${posS * 1000}&state=stopped"
        return http.get(s.base + path, s.headers).ok
    }

    private suspend fun plexEpisode(s: Srv, show: String, se: Int, ep: Int): String? =
        plexAll(s, "/library/metadata/$show/allLeaves").firstOrNull { it.int("parentIndex") == se && it.int("index") == ep }?.str("ratingKey")

    // ---- The library by TMDB id (only to mark something not played there yet)

    private fun indexFile(s: Srv) = java.io.File(context.cacheDir, "tvwatch_index_" + s.ident.hashCode().toUInt().toString(16) + ".json")

    private suspend fun index(s: Srv): Index {
        val now = System.currentTimeMillis()
        indexes[s.ident]?.takeIf { now - it.at < INDEX_TTL_MS }?.let { return it }
        readIndex(s)?.takeIf { now - it.at < INDEX_TTL_MS }?.let { indexes[s.ident] = it; return it }
        val movies = HashMap<Int, String>()
        val shows = HashMap<Int, String>()
        if (s.plex) {
            for ((key, kind) in plexSections(s)) {
                plexAll(s, "/library/sections/$key/all?type=${if (kind == "movie") 1 else 2}&includeGuids=1").forEach { i ->
                    val t = plexTmdb(i) ?: return@forEach
                    val rk = i.str("ratingKey") ?: return@forEach
                    if (kind == "movie") movies[t] = rk else shows[t] = rk
                }
            }
        } else {
            var start = 0
            while (start < MAX_LIST) {
                val items = get(s, "/Users/${s.userId}/Items?Recursive=true&IncludeItemTypes=Movie,Series&Fields=ProviderIds&StartIndex=$start&Limit=$SERVER_PAGE").array("Items")
                items.forEach { i ->
                    val t = tmdbOf(i) ?: return@forEach
                    val id = i.str("Id") ?: return@forEach
                    if (i.str("Type") == "Movie") movies[t] = id else shows[t] = id
                }
                start += items.size
                if (items.size < SERVER_PAGE) break
            }
        }
        val idx = Index(now, movies, shows)
        indexes[s.ident] = idx
        runCatching { indexFile(s).writeText(buildJsonObject { put("at", now); put("m", map(movies)); put("s", map(shows)) }.toString()) }
        Log.i(TAG, "${s.kind} library: ${movies.size} films, ${shows.size} shows by TMDB id")
        return idx
    }

    private fun map(m: Map<Int, String>) = JsonObject(m.entries.associate { (k, v) -> k.toString() to JsonPrimitive(v) })

    private fun readIndex(s: Srv): Index? = runCatching {
        val o = http.json.parseToJsonElement(indexFile(s).readText()) as JsonObject
        fun back(k: String) = (o[k] as JsonObject).entries.mapNotNull { (t, v) -> t.toIntOrNull()?.let { it to (v as JsonPrimitive).content } }.toMap()
        Index((o["at"] as JsonPrimitive).content.toLong(), back("m"), back("s"))
    }.getOrNull()

    /** A key → the server's item id, or null when it isn't in that library. */
    private suspend fun find(s: Srv, idx: Index, key: String): String? {
        MOVIE.matchEntire(key)?.let { return idx.movies[it.groupValues[1].toInt()] }
        val m = TV.matchEntire(key) ?: return null
        val show = idx.shows[m.groupValues[1].toInt()] ?: return null
        val se = m.groupValues[2].toInt()
        val ep = m.groupValues[3].toInt()
        return if (s.plex) plexEpisode(s, show, se, ep) else serverEpisode(s, show, se, ep)
    }

    // ---- Both ways

    private fun newest(list: List<LocalWatch>) = list.filter { it.tmdbId != null }.groupBy { WatchSync.keyOf(it) }.mapValues { (_, v) -> v.maxBy { it.changed } }

    private fun statusOf(e: LocalWatch) = if (e.watched) "watched" else "watching"

    /** Server → TV: newer there, for titles here (or another episode of a show here). Returns how many were taken in. */
    private suspend fun pull(remote: Map<String, Remote>, done: MutableMap<String, Long>): Int {
        val local = store.entries.value
        val byKey = newest(local)
        val shows = local.filter { it.tmdbId != null && (it.season != null || it.type == "Show") }
            .groupBy { "tmdb_tv_${it.tmdbId}" }.mapValues { (_, v) -> v.maxBy { it.changed } }
        val take = remote.entries.filter { (k, r) ->
            val mine = byKey[k]
            if (mine == null && !(k.startsWith("tmdb_tv_") && k.substringBeforeLast("_s") in shows)) return@filter false
            if (r.at <= (mine?.changed ?: 0L) / 1000 + LEEWAY_S) return@filter false
            mine == null || statusOf(mine) != r.status || (r.status == "watching" && abs(mine.positionMs / 1000 - r.pos) > 30)
        }.sortedByDescending { it.value.at }.take(MAX_PULL)
        if (take.isEmpty()) return 0
        var n = 0
        store.syncEdit { list ->
            val out = list.toMutableList()
            for ((k, r) in take) {
                val here = out.filter { it.tmdbId != null && WatchSync.keyOf(it) == k }.maxByOrNull { it.changed }
                if (here != null && here.changed / 1000 + LEEWAY_S >= r.at) continue
                val base = here ?: shows[k.substringBeforeLast("_s")]?.let { newEpisode(it, k) } ?: continue
                out.removeAll { it.tmdbId != null && WatchSync.keyOf(it) == k }
                val at = r.at * 1000
                val dur = if (r.dur > 0) r.dur * 1000 else base.durationMs
                out += if (r.status == "watched") base.copy(watched = true, positionMs = 0, durationMs = dur, changedAt = at, updatedAt = here?.updatedAt ?: at)
                else base.copy(watched = false, positionMs = r.pos * 1000, durationMs = dur, changedAt = at, updatedAt = at, hidden = false)
                done[k] = r.at
                n++
            }
            out
        }
        return n
    }

    /** Another episode of a show that's here, as a fresh entry. */
    private fun newEpisode(show: LocalWatch, key: String): LocalWatch? {
        val m = TV.matchEntire(key) ?: return null
        return show.copy(
            season = m.groupValues[2].toInt(), episode = m.groupValues[3].toInt(), positionMs = 0, watched = false, hidden = false,
            nextSeason = null, nextEpisode = null, lastOfShow = false,
        )
    }

    private fun needsPush(e: LocalWatch, theirs: Remote?, nowS: Long): Boolean {
        val mine = e.changed / 1000
        if (mine <= 0 || (!e.watched && e.positionMs <= 0)) return false
        if (theirs != null && theirs.at + LEEWAY_S >= mine) return false
        val status = statusOf(e)
        if (theirs != null && theirs.status == status && (status != "watching" || abs(theirs.pos - e.positionMs / 1000) <= 30)) return false
        // (Old history isn't pushed into a library that never had it.)
        return !(theirs == null && nowS - mine > 30 * 86_400L)
    }

    /** TV → server: newer here, marked there. Returns how many were marked, or -1 when the server kept failing. */
    private suspend fun push(s: Srv, remote: Map<String, Remote>, done: MutableMap<String, Long>): Int {
        val nowS = System.currentTimeMillis() / 1000
        val todo = newest(store.entries.value).entries
            .filter { (k, e) -> done[k] != e.changed / 1000 && needsPush(e, remote[k], nowS) }
            .sortedByDescending { it.value.changed }.take(MAX_PUSH)
        if (todo.isEmpty()) return 0
        val idx = if (todo.all { remote[it.key] != null }) null else try { index(s) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "${s.kind} library list failed: ${e.message}"); return -1
        }
        var n = 0
        var errors = 0
        for ((k, e) in todo) {
            val mine = e.changed / 1000
            val ok = try {
                val id = remote[k]?.id ?: idx?.let { find(s, it, k) }
                if (id == null) { done[k] = mine; continue } // (not in that library)
                if (s.plex) plexMark(s, id, statusOf(e), e.positionMs / 1000) else serverMark(s, id, statusOf(e), e.positionMs / 1000)
            } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (_: Exception) { false }
            if (ok) { done[k] = mine; n++ } else if (++errors >= 3) return -1
        }
        return n
    }

    // ---- What's been sent to (or taken from) each server, so it's never done twice: key → change time (s).

    private fun doneKey(s: Srv) = "done_" + s.ident.hashCode().toUInt().toString(16)

    private fun doneOf(s: Srv): MutableMap<String, Long> =
        prefs.getStringSet(doneKey(s), emptySet()).orEmpty().mapNotNull { v ->
            v.substringAfterLast('=').toLongOrNull()?.let { v.substringBeforeLast('=') to it }
        }.toMap(HashMap())

    private fun saveDone(s: Srv, done: Map<String, Long>) {
        val keep = done.entries.sortedByDescending { it.value }.take(MAX_DONE).map { "${it.key}=${it.value}" }.toSet()
        prefs.edit().putStringSet(doneKey(s), keep).apply()
    }

    companion object {
        private const val TAG = "TvServerWatch"
        private const val ROUND_MS = 15 * 60_000L
        /** After a change here: this long with no more changes (a play saves as it goes) … */
        private const val SETTLE_MS = 90_000L
        /** … and at least this long since the last round. */
        private const val MIN_GAP_MS = 3 * 60_000L
        private const val LEEWAY_S = 5L
        private const val TICKS = 10_000_000L
        private const val MAX_PUSH = 50
        private const val MAX_PULL = 50
        private const val MAX_DONE = 3000
        private const val SERVER_RECENT = 300
        private const val SERVER_PAGE = 2000
        private const val PAGE = 500
        private const val MAX_LIST = 100_000
        private const val INDEX_TTL_MS = 12 * 3_600_000L
        private val MOVIE = Regex("tmdb_movie_(\\d+)")
        private val TV = Regex("tmdb_tv_(\\d+)_s(\\d+)_e(\\d+)")
        private fun epKey(t: Int, se: Int, ep: Int) = "tmdb_tv_${t}_s%02d_e%02d".format(java.util.Locale.ROOT, se, ep)

        /** The last round on this TV, for the settings page (null: none yet, or the plugin is doing it). */
        var status by mutableStateOf<String?>(null)
        /** A round now (Settings → Sync now), once started. */
        var requestRound: () -> Unit = {}
    }
}
