package io.github.petebutty.viewarr.data.sync

import android.util.Log
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-side sports (the Viewarr plugin 0.4.0 on Dispatcharr, viewarr-plugin/viewarr/server.py): Live now and where
 * to watch each game, worked out on the server every few minutes for the whole house and left in each user's app data
 * (key [KEY]). Read here every 2 minutes; while it's [fresh], Live now and the Sports page use it at once instead of
 * each TV working it all out. Missing or old (no plugin, or its service stopped): the TV does the work, as before.
 * (Peter, 2026-11-17.)
 */
@Singleton
class ServerSports @Inject constructor(
    private val sessions: SessionStore,
    private val http: HttpJson,
    /** Score alerts checked by this TV itself, used only while the plugin isn't doing them (no double alerts). */
    private val tvAlerts: io.github.petebutty.viewarr.data.sports.TvScoreAlerts,
) {
    class Live(
        val uuid: String, val start: Long, val end: Long, val title: String, val sport: String?, val group: String?,
        val picture: String?, val game: String?, val score: String?, val soon: Boolean,
    )
    class Game(
        val channels: List<String>, val sure: Int, val why: String, val source: String?,
        val state: String? = null, val start: Long = 0, val home: String? = null, val away: String? = null, val leaguePath: String? = null,
        val league: String? = null,
    )
    /** [hash]: the content's own hash, its time stamp left out (the same list written again hashes the same). */
    class Data(val at: Long, val live: List<Live>, val games: Map<String, Game>, val hash: Int = 0, val functions: Map<String, Boolean> = emptyMap())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    // ---- Live score alerts (the plugin's viewarr_alerts): checked every 30 s while one of this user's teams or
    // leagues has a game live or kicking off soon (by the server's games), else not at all. New ones for what this
    // user follows show as a banner on this TV (ui/common/AlertBanner). (Stage 6, 2026-11-17.)
    private val seenAlerts = java.util.Collections.synchronizedSet(HashSet<String>())
    private var alertsPrimed = false

    private fun followedLive(follows: List<io.github.petebutty.viewarr.data.sports.SportFollow>): Boolean {
        val d = fresh ?: return false
        val now = System.currentTimeMillis() / 1000
        return d.games.values.any { g ->
            (g.state == "in" || (g.state == "pre" && g.start - now in 0..600)) && follows.any { f -> matches(f, g.leaguePath, g.home, g.away) }
        }
    }

    private fun matches(f: io.github.petebutty.viewarr.data.sports.SportFollow, lp: String?, home: String?, away: String?) = followMatches(f, lp, home, away)

    private suspend fun checkAlerts(follows: List<io.github.petebutty.viewarr.data.sports.SportFollow>) {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return
        val r = http.get(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/viewarr_alerts/", mapOf("X-API-Key" to s.apiKey))
        val o = (r.body as? JsonObject)?.takeIf { r.code in 200..299 } ?: return
        val now = System.currentTimeMillis() / 1000
        val events = (o["events"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        for (e in events) {
            val id = e.text("id") ?: continue
            if (!seenAlerts.add(id)) continue
            // (The first look only learns what's already there: no burst of old alerts when the TV comes on.)
            if (!alertsPrimed || now - (e.long("at") ?: 0) > 600) continue
            val teams = (e["teams"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val home = teams.getOrNull(0) ?: e.text("home"); val away = teams.getOrNull(1) ?: e.text("away")
            if (follows.none { f -> matches(f, e.text("lp"), home, away) }) continue
            if (!io.github.petebutty.viewarr.data.sports.ScoreAlerts.kindOn(e.text("kind"))) continue
            val text = e.text("text") ?: continue
            Log.i(TAG, "alert: $text")
            showAlert(text)
        }
        alertsPrimed = true
        // The scores as they stand, for the ticker's Live scores (this user's teams and leagues only).
        scoreLines = (o["live"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { l ->
            val teams = (l["teams"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (follows.none { f -> matches(f, l.text("lp"), teams.getOrNull(0), teams.getOrNull(1)) }) return@mapNotNull null
            val t = l.text("text") ?: return@mapNotNull null
            l.text("detail")?.let { "$t  ·  $it" } ?: t
        }
    }

    /** Started with [start]: the alert loop, given the user's follows as they change. */
    fun startAlerts(follows: kotlinx.coroutines.flow.Flow<List<io.github.petebutty.viewarr.data.sports.SportFollow>>) {
        tvAlerts.start()
        scope.launch {
            var current = emptyList<io.github.petebutty.viewarr.data.sports.SportFollow>()
            launch { follows.collect { current = it } }
            while (true) {
                // (Score alerts off, or off for every follow: nothing is checked at all.)
                // (Only the teams / leagues alerts were switched on for, followed or not: none to start.)
                // (Switched off in the plugin: score alerts are off for the house.)
                val wanted = if (io.github.petebutty.viewarr.data.sports.ScoreAlerts.on && !offInPlugin(F_ALERTS)) io.github.petebutty.viewarr.data.sports.ScoreAlerts.teams() else emptyList()
                if (wanted.isNotEmpty() && followedLive(wanted)) runCatching { checkAlerts(wanted) } else scoreLines = emptyList()
                delay(30_000)
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                runCatching { refresh() }.onFailure { Log.w(TAG, "couldn't read: ${it.message}") }
                delay(120_000)
            }
        }
    }

    suspend fun refresh() {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: run { latest = null; checked = true; return }
        val r = http.get(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$KEY/", mapOf("X-API-Key" to s.apiKey))
        checked = true
        if (r.code == 404) { latest = null; return }
        val o = (r.body as? JsonObject)?.takeIf { r.code in 200..299 } ?: return
        latest = parse(o)?.also { Log.i(TAG, "from the server: ${it.live.size} live now, ${it.games.size} games, ${(System.currentTimeMillis() / 1000 - it.at) / 60} min old") }
    }

    private fun parse(o: JsonObject): Data? {
        if (o.int("v") != 1) return null
        val at = o.long("at") ?: return null
        val live = (o["live"] as? JsonArray).orEmpty().mapNotNull { e ->
            val j = e as? JsonObject ?: return@mapNotNull null
            Live(
                uuid = j.text("uuid") ?: return@mapNotNull null, start = (j.long("start") ?: 0) * 1000, end = (j.long("end") ?: 0) * 1000,
                title = j.text("title").orEmpty(), sport = j.text("sport"), group = j.text("group"), picture = j.text("pic"),
                game = j.text("game"), score = j.text("score"), soon = (j["soon"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        val games = (o["games"] as? JsonObject).orEmpty().mapNotNull { (id, v) ->
            val j = v as? JsonObject ?: return@mapNotNull null
            val chs = (j["ch"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            id to Game(chs, j.int("sure") ?: 0, j.text("why").orEmpty(), j.text("src"),
                j.text("state"), j.long("start") ?: 0, j.text("home"), j.text("away"), j.text("lp"), j.text("league"))
        }.toMap()
        return Data(at, live, games, hash = JsonObject(o - "at").hashCode(), functions = functionsOf(o))
    }

    companion object {
        /** A followed team or league is in this game (by league path, or the team's name either way round). */
        fun followMatches(f: io.github.petebutty.viewarr.data.sports.SportFollow, lp: String?, home: String?, away: String?) = when (f.kind) {
            io.github.petebutty.viewarr.data.sports.SportFollow.Kind.League -> lp != null && lp == f.league
            io.github.petebutty.viewarr.data.sports.SportFollow.Kind.Team ->
                listOfNotNull(home, away).any { it.equals(f.name, ignoreCase = true) || it.contains(f.name, ignoreCase = true) || f.name.contains(it, ignoreCase = true) }
        }
        const val KEY = "viewarr_server"

        // ---- The plugin's functions (its viewarr_server "functions" map: viewarr-plugin server.FUNCTIONS). An admin
        // can switch each off in the plugin (Settings → Plugins → Viewarr); while the plugin runs, that feature is then
        // off for the house: Viewarr hides it and does none of it itself. Only with no plugin at all do this TV's own
        // fallbacks run (tvDoesJobs). A plugin without the map: every function on. (Peter, 2026-11-22.)
        const val F_LIVE = "live"
        const val F_GAMES = "games"
        const val F_RECORD = "recordings"
        const val F_ALERTS = "alerts"
        const val F_EVENTS = "event_channels"
        const val F_JUST = "just_added"
        const val F_WATCH = "watch_sync"
        const val F_TIMESHIFT = "timeshift"
        const val F_BACKUPS = "backups"

        /** [fn] is switched off in the running plugin: hide it, and do none of it on this TV. */
        fun offInPlugin(fn: String): Boolean = pluginAlive && latest?.functions?.get(fn) == false

        /** [fn] is there on this TV: the plugin runs it, or there's no plugin (this TV's own fallback, where one exists). */
        fun available(fn: String): Boolean = !offInPlugin(fn)

        private fun functionsOf(o: JsonObject): Map<String, Boolean> =
            (o["functions"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }.toMap()
        private const val TAG = "ServerSports"
        /** The newest copy read from the server (null: none, or no plugin). */
        @Volatile var latest: Data? = null
            private set
        /** Your teams' live scores as they stand ("Celtic 1–0 Rangers  ·  34'"), for the ticker. Empty: none live. */
        @Volatile var scoreLines: List<String> = emptyList()
            private set
        /** The server has been asked at least once since the app started (whatever the answer). */
        @Volatile var checked = false
            private set
        /**
         * The plugin's service is running for this house: its data was written in the last 10 minutes (it writes every
         * 5). Its score alerts run in the same loop, so while this is true the TV doesn't check scores itself.
         */
        /**
         * This TV does the plugin's jobs itself: no plugin running for the house. (A switch for it was tried and taken
         * out, 2026-11-22: a Shield test showed these jobs weren't what slowed TVs without the plugin.)
         */
        val tvDoesJobs: Boolean get() = checked && !pluginAlive
        val pluginAlive: Boolean get() = latest?.let { System.currentTimeMillis() / 1000 - it.at < 10 * 60 } == true

        /**
         * A score alert on this TV: on the ticker when one's on screen with Live scores on (next to scroll across);
         * else the banner. (Settings → Sports → Score alerts: on the ticker and / or the banner.) Used for the plugin's
         * alerts and the TV's own (data/sports/TvScoreAlerts).
         */
        suspend fun showAlert(text: String) = kotlinx.coroutines.withContext(Dispatchers.Main) {
            val sa = io.github.petebutty.viewarr.data.sports.ScoreAlerts
            if (sa.ticker && io.github.petebutty.viewarr.ui.common.LiveScores.onScreen > 0 && io.github.petebutty.viewarr.ui.common.Ticker.feedOn(io.github.petebutty.viewarr.data.news.NewsFeeds.LIVE_SCORES))
                io.github.petebutty.viewarr.ui.common.LiveScores.pending.add(text)
            else if (sa.banner) io.github.petebutty.viewarr.ui.common.AlertBanner.show(text)
        }

        /** [latest] while it's recent enough to trust (the service writes every 5 minutes): 15 minutes. */
        val fresh: Data? get() = latest?.takeIf { System.currentTimeMillis() / 1000 - it.at < 15 * 60 }

        private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    }
}
