package io.github.petebutty.viewarr.data.sports

import android.util.Log
import io.github.petebutty.viewarr.data.dvr.DvrRepository
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.data.sync.ServerSports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * "Record all <team>" and "Record when it's in the guide" without the Viewarr plugin: while Viewarr is running, this
 * TV looks every 15 minutes, and after each guide update, for the wanted games (GameWatch) that the guide now lists,
 * and schedules each one's Dispatcharr recording, as the Sports page does when it's opened (SportsHub.resolvePending).
 * Only while the plugin isn't running for the house (ServerSports.pluginAlive): with it, the plugin records them on
 * the server, as before. Nothing wanted: nothing is asked of anyone.
 *
 * No duplicates: a game already done is skipped (GameWatch.done / recorded), and Dispatcharr's recordings are read
 * again just before recording, so a recording already there for the same channel and time (by hand, a series rule,
 * another TV) is used instead of making another. A recording that fails is tried again at most 3 times.
 * (2026-11-21: "All features should be available to people also not using the Viewarr plugin.")
 */
@Singleton
class TvGameRecorder @Inject constructor(
    private val sports: SportsRepository,
    private val gameWatch: GameWatch,
    private val guide: GuideRepository,
    private val dvr: DvrRepository,
    private val sessions: SessionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var started = false
    /** Failed tries per game id (kept for this run of the app). */
    private val fails = HashMap<String, Int>()

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                io.github.petebutty.viewarr.AppVisible.await()
                runCatching { check("every 15 minutes") }.onFailure { Log.w(TAG, "check failed: ${it.message}") }
                delay(15 * 60_000L)
            }
        }
        // After each guide update (a minute's quiet first: a top-up and a full update can come close together).
        scope.launch {
            guide.generation.drop(1).debounce(60_000L).collect {
                runCatching { check("after a guide update") }.onFailure { Log.w(TAG, "check failed: ${it.message}") }
            }
        }
    }

    private suspend fun check(why: String) = lock.withLock {
        // The plugin running (or not asked yet): it records them, so nothing here.
        if (!ServerSports.tvDoesJobs) return@withLock
        val s = sessions.session.first() ?: return@withLock
        if (s.isDirect) return@withLock
        val ids = gameWatch.recordIds()
        val teams = gameWatch.teams()
        if (ids.isEmpty() && teams.isEmpty()) return@withLock
        val follows = sports.follows.first()
        if (follows.isEmpty()) return@withLock
        val now = System.currentTimeMillis()
        val wanted = ArrayList<SportGame>()
        for (f in follows) {
            // (ESPN's answers are cached for 10 minutes; one follow at a time, with a pause.)
            val games = runCatching { sports.games(f) }.getOrDefault(emptyList())
            wanted += games.filter { g ->
                g.state == "pre" && g.start > now && g.start < now + 8 * DAY && (fails[g.id] ?: 0) < 3 &&
                    (g.id in ids || ((gameWatch.recordsTeam(g.home) || gameWatch.recordsTeam(g.away)) && !gameWatch.done(g.id)))
            }
            delay(300)
        }
        val todo = wanted.distinctBy { it.id }.sortedBy { it.start }
        if (todo.isEmpty()) return@withLock
        val channels = guide.observeAllChannels().first().associateBy { it.id }
        var fresh = false
        var made = 0
        for (g in todo) {
            val (ch, p) = channelForGame(guide, channels, g) ?: continue
            if (p.startTime < System.currentTimeMillis()) continue
            // Dispatcharr's list read once, just before the first recording of this check.
            if (!fresh) { runCatching { dvr.refresh() }; fresh = true }
            if (dvr.allowed.value == false) { Log.i(TAG, "this account can't record: nothing scheduled"); return@withLock }
            if (dvr.recordingFor(ch.id, p) != null) {
                Log.i(TAG, "${g.title}: already recording on ${ch.name}")
                gameWatch.recorded(g.id)
                continue
            }
            val r = runCatching { dvr.record(ch, p) }.getOrNull()
            if (r?.ok == true) {
                Log.i(TAG, "recording ${g.title} on ${ch.name} ($why)")
                gameWatch.recorded(g.id)
                made++
            } else {
                fails[g.id] = (fails[g.id] ?: 0) + 1
                Log.w(TAG, "couldn't record ${g.title} on ${ch.name}: ${r?.message}")
            }
            delay(500)
        }
        if (made > 0) Log.i(TAG, "$made recording(s) scheduled ($why)")
    }

    companion object {
        private const val TAG = "TvGameRecorder"
        private const val DAY = 86_400_000L

        /** Added to "recorded once it's in your TV guide" messages: without the plugin it's done only while Viewarr runs. */
        val whileRunning: String get() = if (ServerSports.pluginAlive) "" else " (while Viewarr is running on this TV)"

        /**
         * The channel showing [game], from the guide: a programme naming both sides (GameMatch), starting from 3 hours
         * before kick-off to an hour after. Null if none is listed yet. Shared with the Sports page
         * (GuideViewModel.channelForGame), so both find the same channel and programme.
         */
        suspend fun channelForGame(repo: GuideRepository, channels: Map<Int, ChannelListItem>, game: SportGame): Pair<ChannelListItem, GuideProgram>? {
            // The shows around kick-off (starting up to 3 h before to 1 h after; so ending by 7 h after) with either
            // team's telling word, then only those naming both teams (GameMatch: ESPN's names for them, whole words).
            // One team alone isn't enough here: a club's channel shows its name all day.
            val found = GameMatch.searchWords(game).flatMap { w ->
                runCatching { repo.searchProgramsEnding(w, game.start - 3 * 3_600_000L, game.start + 7 * 3_600_000L) }.getOrNull().orEmpty()
            }.distinctBy { it.channelId to it.startTime }.mapNotNull { p -> channels[p.channelId]?.let { it to p } }
            return found.filter { (_, p) ->
                p.startTime in (game.start - 3 * 3_600_000L)..(game.start + 3_600_000L) &&
                    GameMatch.textIsGame(p.title + " " + p.subTitle.orEmpty(), game) == GameMatch.SURE // (a few dozen at most)
            }.minByOrNull { (_, p) -> abs(p.startTime - game.start) }
        }
    }
}
