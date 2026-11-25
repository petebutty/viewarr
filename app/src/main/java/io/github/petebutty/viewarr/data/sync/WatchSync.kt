package io.github.petebutty.viewarr.data.sync

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.local.LocalWatch
import io.github.petebutty.viewarr.data.local.LocalWatchStore
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IPTV (Xtream VOD through Dispatcharr) and addon watch progress shared between every box signed in to the same
 * Dispatcharr user, through Dispatcharr's own per-user app-data store (as the setup is), key [KEY]. The format is
 * shared with companion plugins (the "VOD Watch Progress Companion" plugin syncs it with Emby / Jellyfin):
 *
 *   { "tmdb_movie_693134": {"title": "Dune: Part Two", "pos": 2415, "dur": 9960, "status": "watching", "updated_at": 1794782733},
 *     "tmdb_tv_94997_s02_e04": {"status": "watched", "updated_at": 1794782340},
 *     "tmdb_movie_550": {"status": "unwatched", "updated_at": 1794782900} }
 *
 * Ids: tmdb_movie_<id> / tmdb_tv_<show id>_s<SS>_e<EE>, else imdb_…, else title_movie_<slug> / title_tv_<slug>_s…_e…;
 * pos / dur and updated_at in seconds. Per item the newer updated_at wins (5 s leeway between clocks). Each sync reads
 * the copy, merges, then writes (the whole value is replaced each time, so writing blind lost another box's save).
 * "unwatched" markers tell other boxes to clear their tick (kept 90 days); watched items carry no position. Dispatcharr
 * allows 128 KiB per key: kept under 115 KiB, oldest watched dropped first. Viewarr adds "key", "type", "poster" to
 * items being watched (so another box can show them in Continue watching); others ignore them.
 * Synced on start, every 5 minutes, and a minute after a change here.
 */
@Singleton
class WatchSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val store: LocalWatchStore,
    private val http: HttpJson,
    // (For names and posters of titles another app shared without them. A Provider: no build-time loop.)
    private val catalog: javax.inject.Provider<io.github.petebutty.viewarr.data.media.CatalogRepository>,
    private val accounts: io.github.petebutty.viewarr.data.accounts.AccountStore,
    // (The same switch done by this TV itself while the Viewarr plugin isn't running: TvServerWatch.)
    private val tvServerWatch: TvServerWatch,
) {
    /**
     * Settings → Playback → Watch progress → Sync IPTV progress with my Emby / Jellyfin (off until switched on): this
     * user's Emby / Jellyfin address, user and sign-in left in their own Dispatcharr app data ([SERVERS_KEY], readable
     * only by them and admins), so the Viewarr plugin (0.5.2+) keeps their IPTV watch progress in step with that
     * server, both ways, without Trakt. Off: the details are taken back out. (Asked for 2026-11-17.)
     */
    suspend fun shareServers(on: Boolean): Boolean {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return false
        val a = accounts.current()
        val list = if (!on) emptyList() else listOfNotNull(a.emby?.let { "emby" to it }, a.jellyfin?.let { "jellyfin" to it })
        // Plex too: its address and this person's own server token (a Plex Home user's), no user id needed.
        val plex = a.plex?.takeIf { on }
        val body = buildJsonObject {
            put("servers", kotlinx.serialization.json.JsonArray(list.map { (kind, m) ->
                buildJsonObject {
                    put("kind", kind); put("url", m.serverUrl); put("user_id", m.userId); put("token", m.token); put("name", m.serverName)
                }
            } + listOfNotNull(plex?.let { p ->
                buildJsonObject { put("kind", "plex"); put("url", p.serverUrl); put("token", p.token); put("name", p.serverName) }
            })))
            put("at", System.currentTimeMillis() / 1000)
        }
        val w = http.put(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$SERVERS_KEY/", body, mapOf("X-API-Key" to s.apiKey))
        Log.i(TAG, "Emby / Jellyfin / Plex watch sync ${if (on) "on (${list.size + (if (plex != null) 1 else 0)} servers)" else "off"}: ${w.code}")
        return w.ok
    }

    /** What the plugin was last given ([shareServers]): null not yet this run. */
    @Volatile private var sharedAs: Boolean? = null

    /**
     * The plugin's server sync as it should be: [serversOn] (the switch, and Trakt not connected: with Trakt, the
     * plugin copying IPTV watches into Plex would reach Trakt a second and third time). Acts only on a change, so it's
     * cheap to call from the sync loop: connecting Trakt takes the sign-in back out within a minute.
     */
    private suspend fun applyServers() {
        if (!WatchSource.known) return
        val want = serversOn
        if (sharedAs == want) return
        // Switched off and never shared this run: nothing to take back (switching it off already did).
        if (sharedAs == null && !want && !serversSwitch) { sharedAs = false; return }
        if (runCatching { shareServers(want) }.getOrDefault(false)) sharedAs = want
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val prefs by lazy { context.getSharedPreferences("watch_sync", Context.MODE_PRIVATE) }
    @Volatile private var dirty = false
    private var started = false

    fun start() {
        if (started) return
        started = true
        // Settings → Playback → Watch progress → Sync now.
        requestSync = {
            scope.launch { runCatching { sync(force = true) }.onFailure { Log.w(TAG, "sync failed: ${it.message}") } }
            TvServerWatch.requestRound()
        }
        // No plugin (or its service stopped): this TV syncs with Emby / Jellyfin / Plex itself while Viewarr runs.
        tvServerWatch.start()
        // Settings → … → Sync IPTV progress with my Emby / Jellyfin; and on start, if on (a sign-in may have changed).
        setServers = { on -> scope.launch { val want = on && !WatchSource.hasTrakt; if (runCatching { shareServers(want) }.getOrDefault(false)) sharedAs = want } }
        scope.launch { delay(8_000); runCatching { applyServers() } }
        // A change here (played, ticked, un-ticked): synced within a minute.
        scope.launch { store.entries.drop(1).collect { dirty = true } }
        scope.launch {
            delay(5_000)
            var lastFull = 0L
            while (true) {
                val now = System.currentTimeMillis()
                runCatching { applyServers() }
                if (dirty || now - lastFull > 5 * 60_000L) {
                    dirty = false
                    lastFull = now
                    runCatching { sync() }.onFailure { Log.w(TAG, "sync failed: ${it.message}") }
                }
                delay(60_000)
            }
        }
    }

    /** One sync: read the shared copy, merge with this box, write it back if it changed, and take in what's newer. */
    suspend fun sync(force: Boolean = false) = lock.withLock {
        // Settings → Playback → Watch progress → Sync with Dispatcharr (on unless switched off; asked for the
        // option, for his companion plugin, 2026-11-17). [force]: Sync now, even when off.
        // (Whatever else is connected: IPTV and addon progress is shared between TVs only through here. Not before the
        // accounts have been read once, so a start-up blip can't act on a blank copy.)
        if (!force && (!enabled || !WatchSource.known)) return@withLock
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return@withLock
        val url = s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$KEY/"
        val headers = mapOf("X-API-Key" to s.apiKey)
        val r = http.get(url, headers)
        val remote: Map<String, JsonObject> = when {
            r.code in 200..299 -> (r.body as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }?.toMap() ?: return@withLock
            r.code == 404 -> emptyMap()
            else -> { Log.w(TAG, "couldn't read the shared copy (${r.code})"); return@withLock }
        }
        val local = store.entries.value
        val localByKey = local.groupBy { keyOf(it) }.mapValues { (_, v) -> v.maxBy { it.changed } }
        val snapKey = "seen|" + s.serverUrl.trimEnd('/') + "|" + s.username
        val seenBefore = prefs.getStringSet(snapKey, emptySet()).orEmpty()
        val nowSec = System.currentTimeMillis() / 1000
        val merged = LinkedHashMap(remote)
        var changedRemote = false
        // This box → the shared copy (newer here, or not there yet).
        for ((k, e) in localByKey) {
            val mine = (e.changed / 1000)
            val theirs = remote[k]?.updatedAt() ?: -1
            if (mine > theirs + LEEWAY_S) { merged[k] = toRemote(e, mine); changedRemote = true }
        }
        // Un-ticked here since the last sync (the entry went): an "unwatched" marker for the other boxes.
        for (k in seenBefore) {
            if (k in localByKey) continue
            val theirs = remote[k]
            if (theirs != null && theirs.status() != "unwatched" && nowSec > theirs.updatedAt() + LEEWAY_S) {
                merged[k] = buildJsonObject { put("status", "unwatched"); put("updated_at", nowSec) }
                changedRemote = true
            }
        }
        // Old markers out; then under the quota.
        val cut = nowSec - UNWATCHED_KEEP_DAYS * 86_400L
        merged.entries.removeAll { (_, v) -> v.status() == "unwatched" && v.updatedAt() < cut }.also { if (it) changedRemote = true }
        while (merged.size > 1 && sizeOf(merged) > MAX_BYTES) {
            val pool = merged.filterValues { it.status() == "watched" }.keys.ifEmpty { merged.keys }
            merged.remove(pool.minBy { merged[it]?.updatedAt() ?: 0 }); changedRemote = true
        }
        if (changedRemote) {
            val w = http.put(url, JsonObject(merged), headers)
            if (!w.ok) { Log.w(TAG, "couldn't save the shared copy (${w.code})"); return@withLock }
        }
        // Names and posters for titles shared without them (a companion plugin writes only ids and progress), and for
        // entries already saved as "Unknown": looked up on TMDB first (a few dozen at most per sync).
        // (Also those with a name but no poster: the plugin's Plex / Emby / Jellyfin sync sends names only, and their
        // cards were blank in Continue watching. 2026-11-18. One lookup per show, not per episode.)
        val posterless = store.entries.value.filter { it.poster == null && it.tmdbId != null }.map { Ref(it.type, it.tmdbId, null, null, null, null) }
        val unnamed = (merged.mapNotNull { (k, v) -> parseKey(k)?.takeIf { (v.text("title") == null || v.text("poster") == null) && it.tmdb != null } } +
            localByKey.values.filter { it.title == UNKNOWN && it.tmdbId != null }.map { Ref(it.type, it.tmdbId, null, null, null, null) } + posterless)
            .distinctBy { it.type + ":" + it.tmdb }.take(40)
        val names = HashMap<String, Pair<String, String?>>()
        for (r in unnamed) runCatching { catalog.get().tmdbName(r.type, r.tmdb!!) }.getOrNull()?.let { names[r.type + ":" + r.tmdb] = it }
        // The shared copy → this box (newer there).
        var taken = 0
        store.syncEdit { list ->
            val out = list.toMutableList()
            // Taken from a server by the plugin (Plex / Emby / Jellyfin: "src"), never played in Viewarr: only kept for
            // shows already watched here. Others are that server's own watching, shown in its own rows; they were
            // added as blank IPTV cards (2026-11-18) and go again here, without telling the server anything.
            fun fromServerOnly(v: JsonObject) = v.text("src") != null && v.text("key") == null
            val showsHere = out.filter { it.key != it.id && it.tmdbId != null }.map { it.type + ":" + it.tmdbId }.toSet()
            val gone = out.filter { e -> e.key == e.id && merged[keyOf(e)]?.let(::fromServerOnly) == true && (e.type + ":" + e.tmdbId) !in showsHere }
            if (gone.isNotEmpty()) { out.removeAll(gone.toSet()); Log.i(TAG, "dropped ${gone.size} taken from a server, never played here") }
            for ((k, v) in merged) {
                val here = localByKey[k]
                if (here == null && fromServerOnly(v) && parseKey(k)?.let { r -> (r.type + ":" + r.tmdb) !in showsHere } != false) continue
                if (here != null && here in gone) continue
                if (here != null && here.changed / 1000 + LEEWAY_S >= v.updatedAt()) continue
                val ref = parseKey(k) ?: continue
                val at = v.updatedAt() * 1000
                val status = v.status()
                val pos = (v.long("pos") ?: 0) * 1000
                // A "watching" item with no position: nothing to take in. Checked before this box's entry is removed
                // (removing it first lost it here, and the next sync then sent the other boxes an "unwatched" marker).
                if (status != "unwatched" && status != "watched" && pos <= 0) continue
                // (A "removed" mark for something this box doesn't have: nothing to do. It was counted as taken in,
                // and redone, on every sync: "86 taken in" each time. 2026-11-18.)
                if (status == "unwatched" && here == null) continue
                out.removeAll { keyOf(it) == k }
                when (status) {
                    "unwatched" -> Unit
                    "watched" -> out += base(here, ref, v, names).copy(watched = true, positionMs = 0, changedAt = at, updatedAt = here?.updatedAt ?: at)
                    else -> out += base(here, ref, v, names).copy(watched = false, positionMs = pos, durationMs = (v.long("dur") ?: 0) * 1000,
                        changedAt = at, updatedAt = at, hidden = false)
                }
                taken++
            }
            // Entries already saved as "Unknown": their TMDB name now.
            for (i in out.indices) {
                val e = out[i]
                val n = e.tmdbId?.let { names[e.type + ":" + it] }
                if (e.title == UNKNOWN && n != null) { out[i] = e.copy(title = n.first, poster = e.poster ?: n.second); taken++ }
                else if (e.poster == null && n?.second != null) { out[i] = e.copy(poster = n.second); taken++ }
            }
            out
        }
        prefs.edit().putStringSet(snapKey, store.entries.value.map { keyOf(it) }.toSet()).apply()
        if (changedRemote || taken > 0) Log.i(TAG, "synced: ${merged.size} on Dispatcharr${if (changedRemote) ", saved" else ""}${if (taken > 0) ", $taken taken in" else ""}")
        lastSync = System.currentTimeMillis()
    }

    private fun JsonObject.updatedAt(): Long = (this["updated_at"] as? JsonPrimitive)?.longOrNull ?: 0
    private fun JsonObject.status(): String = (this["status"] as? JsonPrimitive)?.content ?: "watching"
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
    private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
    private fun sizeOf(m: Map<String, JsonObject>) = JsonObject(m).toString().toByteArray().size

    /** A local entry as the shared format. */
    private fun toRemote(e: LocalWatch, atSec: Long): JsonObject = buildJsonObject {
        if (e.watched) { put("status", "watched"); put("updated_at", atSec); return@buildJsonObject }
        put("title", if (e.season != null) "${e.title} S%02dE%02d".format(java.util.Locale.ROOT, e.season, e.episode ?: 0) else e.title)
        put("pos", e.positionMs / 1000)
        put("dur", e.durationMs / 1000)
        put("status", "watching")
        put("updated_at", atSec)
        put("key", e.key)
        put("type", e.type)
        e.poster?.let { put("poster", it) }
    }

    /** What a shared id refers to. */
    private data class Ref(val type: String, val tmdb: Int?, val imdb: String?, val slug: String?, val season: Int?, val episode: Int?)

    private fun parseKey(k: String): Ref? {
        TV.matchEntire(k)?.let { m ->
            val (src, id, se, ep) = m.destructured
            return Ref("Show", id.toIntOrNull().takeIf { src == "tmdb" }, id.takeIf { src == "imdb" }, id.takeIf { src == "title" }, se.toInt(), ep.toInt())
        }
        MOVIE.matchEntire(k)?.let { m ->
            val (src, id) = m.destructured
            return Ref("Movie", id.toIntOrNull().takeIf { src == "tmdb" }, id.takeIf { src == "imdb" }, id.takeIf { src == "title" }, null, null)
        }
        return null
    }

    /**
     * The entry to update, or a new one for a title this box hasn't seen (another box, or Emby, watched it). An entry
     * with no name (a companion plugin writes only the ids and progress) takes its name and poster from TMDB: they all
     * showed "Unknown" in Continue watching (2026-11-17).
     */
    private fun base(here: LocalWatch?, ref: Ref, v: JsonObject, names: Map<String, Pair<String, String?>>): LocalWatch {
        val named = ref.tmdb?.let { names[ref.type + ":" + it] }
        if (here != null) {
            if (named == null) return here
            if (here.title != UNKNOWN) return if (here.poster == null) here.copy(poster = named.second) else here
            return here.copy(title = named.first, poster = here.poster ?: named.second)
        }
        val given = v.text("title")?.replace(Regex("\\s+S\\d+E\\d+$"), "")
        val title = given ?: named?.first ?: ref.slug?.replace('_', ' ') ?: UNKNOWN
        val id = when { ref.tmdb != null -> "tmdb:${ref.type}:${ref.tmdb}"; ref.imdb != null -> "imdb:${ref.imdb}"; else -> "title:${ref.slug}" }
        return LocalWatch(
            id = id, type = v.text("type") ?: ref.type, title = title, poster = v.text("poster") ?: named?.second,
            tmdbId = ref.tmdb, imdbId = ref.imdb, key = v.text("key") ?: id, season = ref.season, episode = ref.episode,
        )
    }

    companion object {
        const val KEY = "viewarr_vod_progress"
        const val SERVERS_KEY = "viewarr_watch_servers"
        /** Settings switch for the Emby / Jellyfin sync (a palette title part marked when ON: off by default). */
        const val SERVERS_SWITCH = "watchsrv:+on"
        /** The switch itself (Settings → Playback → Watch progress). */
        val serversSwitch get() = !io.github.petebutty.viewarr.ui.theme.AppColors.palette.shows(SERVERS_SWITCH)
        /** In effect: the switch on and Trakt not connected (with Trakt it turns itself off; see applyServers). */
        val serversOn get() = serversSwitch && !WatchSource.hasTrakt
        /** Switch it (Settings): set by the settings screen to [shareServers] on the singleton. */
        var setServers: (Boolean) -> Unit = {}
        /** Settings switch (a palette title part: on unless hidden). */
        const val ENABLE_KEY = "watchsync"
        val enabled get() = io.github.petebutty.viewarr.ui.theme.AppColors.palette.shows(ENABLE_KEY)
        /** When it last synced (Settings shows it); 0: not yet this time. */
        var lastSync by androidx.compose.runtime.mutableLongStateOf(0L)
        /** Sync now (set once it's started). */
        var requestSync: () -> Unit = {}
        private const val TAG = "WatchSync"
        private const val UNKNOWN = "Unknown"
        private const val LEEWAY_S = 5L
        private const val UNWATCHED_KEEP_DAYS = 90
        private const val MAX_BYTES = 115_000
        private val TV = Regex("(tmdb|imdb|title)_tv_(.+)_s(\\d+)_e(\\d+)")
        private val MOVIE = Regex("(tmdb|imdb|title)_movie_(.+)")

        /** A local entry's shared id (TMDB first, then IMDb, then its title). Digits always plain 0–9 (Locale.ROOT): on an
         *  Arabic or Persian box "%02d" wrote other digits, and its ids matched no other box or companion plugin. */
        fun keyOf(e: LocalWatch): String {
            val se = if (e.season != null) "_s%02d_e%02d".format(java.util.Locale.ROOT, e.season, e.episode ?: 0) else ""
            val kind = if (e.season != null || e.type == "Show") "tv" else "movie"
            return when {
                e.tmdbId != null -> "tmdb_${kind}_${e.tmdbId}$se"
                e.imdbId != null -> "imdb_${kind}_${e.imdbId}$se"
                else -> "title_${kind}_${slug(e.title)}$se"
            }
        }

        private fun slug(t: String) = t.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_').replace(Regex("_+"), "_")
    }
}
