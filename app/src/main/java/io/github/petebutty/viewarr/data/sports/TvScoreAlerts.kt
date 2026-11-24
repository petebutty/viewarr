package io.github.petebutty.viewarr.data.sports

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.sync.ServerSports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live score alerts worked out on this TV, for houses without the Viewarr plugin (2026-11-21: "All features
 * should be available to people also not using the Viewarr plugin"). The same alerts as the plugin's alerts.py
 * (kick-off, goal / score, half-time, full time) with the same wording, for the teams and leagues score alerts are on
 * for, shown the same way (ServerSports.showAlert: the banner or the ticker).
 *
 * Runs only while all of these hold, else it asks ESPN nothing at all:
 * - the plugin isn't doing it (ServerSports.pluginAlive: its data written in the last 10 minutes), so no double alerts;
 * - score alerts are on (Notifications on, a mode chosen, at least one team or league picked);
 * - the app is on screen (not in the background, not just playing music behind another app).
 *
 * How often: a league's scoreboard every 60 s while one of your games in it is live or kicks off within 10 minutes;
 * otherwise each of your leagues' scoreboards at most every 15 minutes (to find today's games), and a team's fixtures
 * (cups and Europe too) every 2 hours. At most [MAX_PER_MIN] requests a minute; a board that fails waits 2 minutes,
 * then 4, 8… up to 30 before it's tried again. What's been alerted is kept in a small file for a day and a half, so a
 * restart doesn't repeat it.
 */
@Singleton
class TvScoreAlerts @Inject constructor(
    private val sports: SportsRepository,
    @ApplicationContext private val context: Context,
) {
    /** How a game stood at the last look (to compare with the next). */
    private class Seen(val state: String, val hs: String, val aw: String, val short: String, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val prev = HashMap<String, Seen>()
    /** The games of the chosen teams and leagues: from their fixtures and the scoreboards looked at. */
    private val known = HashMap<String, SportGame>()
    private val lastLook = HashMap<String, Long>()
    private val waitUntil = HashMap<String, Long>()
    private val fails = HashMap<String, Int>()
    private val fixturesAt = HashMap<String, Long>()
    private val asked = ArrayDeque<Long>()
    /** Alerts already shown: key → when (epoch seconds). Kept in [file]. */
    private val alerted = LinkedHashMap<String, Long>()
    private val file get() = File(context.filesDir, "tv_score_alerts.txt")

    /** Started once with the plugin's alert loop (ServerSports.startAlerts). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { load() }
            while (true) {
                runCatching { tick() }.onFailure { Log.w(TAG, "check failed: ${it.message}") }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun tick() {
        // (Wait for the first read of the plugin's data, so it isn't taken for missing while the app starts.)
        if (!ServerSports.checked) return
        val wanted = if (ScoreAlerts.on) ScoreAlerts.teams() else emptyList()
        if (wanted.isEmpty() || !ServerSports.tvDoesJobs || !onScreen()) { scoreLines = emptyList(); return }
        val now = System.currentTimeMillis()
        known.values.removeAll { now - it.start > 24 * HOUR || !mine(it, wanted) }
        fixtures(wanted, now)
        // The boards with one of your games live or about to start (or one that should have started by its fixture:
        // it may be live by now), then your own leagues to find today's games.
        val hot = known.values.filter { g ->
            !g.isEvent && (g.state == "in" || (g.state == "pre" && g.start - now in -3 * HOUR..10 * 60_000L))
        }.map { it.league }.distinct().take(MAX_HOT)
        val quiet = wanted.map { it.league }.distinct().filter { it !in hot }
        for (lp in hot) if (due(lp, now, 60_000L)) look(lp, wanted, now)
        for (lp in quiet) if (due(lp, now, 15 * 60_000L)) look(lp, wanted, now)
        // Your games' scores as they stand, for the ticker's Live scores.
        scoreLines = known.values.filter { it.state == "in" && !it.isEvent }.sortedBy { it.start }.map { g ->
            val (hs, aw) = scores(g)
            "${g.home} $hs–$aw ${g.away}" + (g.detail?.let { "  ·  $it" } ?: "")
        }
    }

    /** Each chosen team's fixtures (its cups and European games too), every 2 hours. */
    private suspend fun fixtures(wanted: List<SportFollow>, now: Long) {
        for (f in wanted.filter { it.kind == SportFollow.Kind.Team }) {
            if (now - (fixturesAt[f.shortcutId] ?: 0L) < 2 * HOUR) continue
            // (A team's fixtures take a few requests: counted as 4.)
            if (!take(4, now)) return
            fixturesAt[f.shortcutId] = now
            sports.games(f).filter { it.start - now in -HOUR..2 * 24 * HOUR }.forEach { g -> if (g.id !in known) known[g.id] = g }
        }
    }

    private fun due(lp: String, now: Long, every: Long): Boolean =
        now >= (waitUntil[lp] ?: 0L) && now - (lastLook[lp] ?: 0L) >= every - TICK_MS / 2 && take(1, now)

    /** Room for [n] more requests in the last minute (and counts them if so). */
    private fun take(n: Int, now: Long): Boolean {
        while (asked.isNotEmpty() && now - asked.first() > 60_000L) asked.removeFirst()
        if (asked.size + n > MAX_PER_MIN) return false
        repeat(n) { asked.addLast(now) }
        return true
    }

    private suspend fun look(lp: String, wanted: List<SportFollow>, now: Long) {
        lastLook[lp] = now
        val board = sports.scoreboardNow(lp)
        if (board == null) {
            val n = (fails[lp] ?: 0) + 1
            fails[lp] = n
            waitUntil[lp] = now + minOf(2 * 60_000L shl (n - 1).coerceAtMost(4), 30 * 60_000L)
            Log.w(TAG, "$lp scoreboard failed ($n in a row)")
            return
        }
        fails.remove(lp)
        for (g in board) {
            if (g.isEvent || !mine(g, wanted)) continue
            known[g.id] = g
            compare(g, now)
        }
    }

    private fun mine(g: SportGame, wanted: List<SportFollow>) = wanted.any { f -> ServerSports.followMatches(f, g.league, g.home, g.away) }

    private fun scores(g: SportGame): Pair<String, String> {
        val p = g.score?.split("–")?.map { it.trim() }
        return (p?.getOrNull(0)?.ifEmpty { null } ?: "0") to (p?.getOrNull(1)?.ifEmpty { null } ?: "0")
    }

    /** What changed since the last look, worded as the plugin's alerts.py words it. */
    private suspend fun compare(g: SportGame, now: Long) {
        val (hs, aw) = scores(g)
        val short = g.detail.orEmpty()
        val b = prev[g.id]
        prev[g.id] = Seen(g.state, hs, aw, short, now)
        // First sight, or not looked at for a while (the app was away): nothing fair to compare with.
        if (b == null || now - b.at > STALE_MS) return
        val sport = g.league.substringBefore('/')
        val startWord = when (sport) { "basketball" -> "Tip-off"; "hockey" -> "Puck drop"; "baseball" -> "First pitch"; else -> "Kick-off" }
        val endWord = if (sport in setOf("soccer", "rugby", "rugby-league")) "Full time" else "Final"
        fun half(s: String) = s.uppercase().startsWith("HT") || s.lowercase().startsWith("halftime")
        val score = "${g.home} $hs–$aw ${g.away}"
        var key = "${g.id}|"
        val text: String = when {
            b.state == "pre" && g.state == "in" -> { key += "kickoff"; "$startWord: ${g.home} v ${g.away}" }
            // (No scoring alerts for basketball: it scores every minute.)
            g.state == "in" && (hs != b.hs || aw != b.aw) && sport != "basketball" -> {
                val scorer = if (hs != b.hs) g.home else g.away
                val gain = (if (hs != b.hs) hs.toIntOrNull()?.minus(b.hs.toIntOrNull() ?: 0) else aw.toIntOrNull()?.minus(b.aw.toIntOrNull() ?: 0)) ?: 0
                val word = when (sport) {
                    "soccer", "hockey" -> "GOAL!"
                    "football" -> if (gain >= 6) "TOUCHDOWN!" else if (gain == 3) "Field goal" else "Score"
                    "baseball" -> if (gain > 1) "Runs" else "Run"
                    else -> "Score"
                }
                key += "goal|$hs-$aw"
                "$word $scorer · $score" + (if (short.isNotEmpty()) " ($short)" else "")
            }
            g.state == "in" && half(short) && !half(b.short) -> { key += "ht"; "Half-time: $score" }
            b.state == "in" && g.state == "post" -> { key += "ft"; "$endWord: $score" }
            else -> return
        }
        val kind = key.substringAfter('|').substringBefore('|')
        if (!ScoreAlerts.kindOn(kind) || !remember(key)) return
        Log.i(TAG, "alert: $text")
        ServerSports.showAlert(text)
    }

    /** True the first time [key] is seen (and keeps it); false when it's been alerted already. */
    private fun remember(key: String): Boolean {
        if (key in alerted) return false
        val now = System.currentTimeMillis() / 1000
        alerted[key] = now
        alerted.entries.removeAll { now - it.value > KEEP_S }
        while (alerted.size > MAX_KEPT) alerted.remove(alerted.keys.first())
        runCatching { file.writeText(alerted.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
        return true
    }

    private fun load() {
        val now = System.currentTimeMillis() / 1000
        if (!file.exists()) return
        file.readLines().forEach { l ->
            val k = l.substringBefore('\t'); val at = l.substringAfter('\t', "").toLongOrNull() ?: return@forEach
            if (k.isNotEmpty() && now - at <= KEEP_S) alerted[k] = at
        }
    }

    /** The app is on screen (playing in a corner counts; music carrying on behind another app doesn't). */
    private fun onScreen(): Boolean = runCatching {
        val i = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(i)
        i.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            i.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }.getOrDefault(true)

    companion object {
        private const val TAG = "TvScoreAlerts"
        private const val TICK_MS = 20_000L
        private const val HOUR = 3_600_000L
        /** A game not looked at for this long is taken as first seen again (no alerts from an old comparison). */
        private const val STALE_MS = 4 * 60_000L
        private const val MAX_PER_MIN = 8
        private const val MAX_HOT = 5
        private const val KEEP_S = 36 * 3600L
        private const val MAX_KEPT = 400
        /** Your teams' live scores as this TV last saw them, for the ticker (empty while the plugin does it). */
        @Volatile var scoreLines: List<String> = emptyList()
            private set
    }
}
