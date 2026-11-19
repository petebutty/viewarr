package io.github.petebutty.viewarr.data.sports

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.guide.LiveSports
import io.github.petebutty.viewarr.data.settings.ThemeStore
import io.github.petebutty.viewarr.data.settings.remoteStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One thing picked for Live now (Settings → Live TV → Live now; Peter, 2026-11-19: "search for any team or sport"):
 * a team, a league or a sport. [id]: the team's ESPN id, the league's path ("soccer/eng.1") or the sport's name as
 * [LiveSports.names] has it ("Darts"). [league]: a team's league path (blank for the others). [detail]: a team's
 * league name, for the settings row.
 */
data class LiveNowPick(val kind: Kind, val id: String, val name: String, val league: String = "", val detail: String? = null) {
    enum class Kind { Team, League, Sport }
    val key get() = when (kind) { Kind.Team -> "team:$league:$id"; Kind.League -> "league:$id"; Kind.Sport -> "sport:" + id.lowercase() }
    /** The settings row's second line: "Team · Premier League", "League", "Sport". */
    val kindLine get() = when (kind) { Kind.Team -> "Team" + (detail?.let { " · $it" } ?: ""); Kind.League -> "League"; Kind.Sport -> "Sport" }
}

/**
 * The Live now list, per profile: kept with the remote settings (as Settings → Sports' follows), so it goes with the
 * profile's copy and the server sync. Empty: Live now shows everything live.
 */
@Singleton
class LiveNowPicks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val theme: ThemeStore,
    private val sportsRepo: SportsRepository,
) {
    val picks: Flow<List<LiveNowPick>> = context.remoteStore.data.map { p -> decode(p[stringPreferencesKey(KEY)]) }

    private val lock = Mutex()

    suspend fun add(p: LiveNowPick) = lock.withLock {
        val now = picks.first()
        if (now.none { it.key == p.key }) save(now + p)
    }

    suspend fun remove(p: LiveNowPick) = lock.withLock { save(picks.first().filter { it.key != p.key }) }

    /** The list as it is now, after the one-time carry-over from the old switches. */
    suspend fun current(): List<LiveNowPick> { migrate(); return picks.first() }

    private suspend fun save(list: List<LiveNowPick>) {
        context.remoteStore.edit { it[stringPreferencesKey(KEY)] = list.joinToString(REC) { p -> listOf(p.kind.name, p.id, p.name, p.league, p.detail.orEmpty()).joinToString(SEP) } }
    }

    private fun decode(s: String?): List<LiveNowPick> = s.orEmpty().split(REC).mapNotNull { r ->
        val f = r.split(SEP)
        if (f.size < 5) return@mapNotNull null
        LiveNowPick(runCatching { LiveNowPick.Kind.valueOf(f[0]) }.getOrNull() ?: return@mapNotNull null, f[1], f[2], f[3], f[4].ifEmpty { null })
    }

    /**
     * Once per profile, quietly: the old switches (Live now sports, Live now leagues, Only what I follow) into the list.
     * Only what the user really changed counts (Peter, 2026-11-19: nothing is filled in for anyone by default):
     * Only what I follow on → their follows; else sports or leagues switched off → the ones left on (the sports by
     * name; for leagues, the followed teams and leagues whose league wasn't switched off: the leagues listed there were
     * the ones followed); else nothing. The old switches are then never read again.
     */
    suspend fun migrate() = lock.withLock {
        val prefs = context.remoteStore.data.first()
        if (prefs[stringPreferencesKey(DONE)] == "1") return@withLock
        val hidden = runCatching { theme.palette.first().titleHidden }.getOrNull() ?: return@withLock
        val follows = runCatching { sportsRepo.follows.first() }.getOrDefault(emptyList())
        val leaguesOff = hidden.filter { it.startsWith("live:league:") }.map { it.removePrefix("live:league:") }.toSet()
        val sportsOff = LiveSports.names.filter { LiveSports.key(it) in hidden }
        val seed = when {
            "live:everything" in hidden -> follows.map(::fromFollow)
            sportsOff.isNotEmpty() || leaguesOff.isNotEmpty() ->
                (if (leaguesOff.isNotEmpty()) follows.filter { it.league.lowercase() !in leaguesOff }.map(::fromFollow) else emptyList()) +
                    (if (sportsOff.isNotEmpty()) (LiveSports.names - sportsOff.toSet()).map(::sport) else emptyList())
            else -> emptyList()
        }
        val existing = decode(prefs[stringPreferencesKey(KEY)])
        val merged = (existing + seed).distinctBy { it.key }
        context.remoteStore.edit {
            if (merged != existing) it[stringPreferencesKey(KEY)] = merged.joinToString(REC) { p -> listOf(p.kind.name, p.id, p.name, p.league, p.detail.orEmpty()).joinToString(SEP) }
            it[stringPreferencesKey(DONE)] = "1"
        }
    }

    /** A team or league from Settings → Sports' search (or a follow) as a pick. */
    fun fromFollow(f: SportFollow) = if (f.kind == SportFollow.Kind.League) LiveNowPick(LiveNowPick.Kind.League, f.league, f.name)
        else LiveNowPick(LiveNowPick.Kind.Team, f.id, f.name, f.league, sportsRepo.league(f.league).name)

    companion object {
        const val KEY = "live_now_picks"
        const val DONE = "live_now_picks_v1"
        private const val SEP = "\u001F"
        private const val REC = "\u001E"

        fun sport(name: String) = LiveNowPick(LiveNowPick.Kind.Sport, name, LiveSports.label(name))

        /**
         * Whether a Live now item goes in, by the list ([picks] empty: everything). [leaguePath], [home], [away]: its
         * game, if it's a known fixture; [text]: its title (and channel) for items without one; [sport]: its sport as
         * [LiveSports.names] has it (null: unknown).
         * A team: either side is that team (the follows' matching, ServerSports.followMatches), or, with no fixture,
         * its name in the title. A league: the fixture's league; with no fixture, the league's name in the title when
         * the sport fits. A sport: the item's sport, or its fixture's league's sport.
         */
        fun matches(picks: List<LiveNowPick>, leaguePath: String?, home: String?, away: String?, text: String, sport: String?): Boolean {
            if (picks.isEmpty()) return true
            val fixture = home != null || away != null
            val gameSport = leaguePath?.let { LiveSports.ofLeague(it) }
            return picks.any { p ->
                when (p.kind) {
                    LiveNowPick.Kind.Team ->
                        if (fixture) io.github.petebutty.viewarr.data.sync.ServerSports.followMatches(SportFollow(SportFollow.Kind.Team, p.league, p.id, p.name, null), leaguePath, home, away)
                        else p.name.length >= 4 && text.contains(p.name, ignoreCase = true)
                    LiveNowPick.Kind.League ->
                        if (leaguePath != null) leaguePath.equals(p.id, ignoreCase = true)
                        else p.name.length >= 4 && text.contains(p.name, ignoreCase = true) &&
                            (sport == null || LiveSports.ofLeague(p.id)?.equals(sport, ignoreCase = true) != false)
                    LiveNowPick.Kind.Sport -> p.id.equals(sport, ignoreCase = true) || p.id.equals(gameSport, ignoreCase = true)
                }
            }
        }
    }
}
