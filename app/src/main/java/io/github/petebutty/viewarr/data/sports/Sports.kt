package io.github.petebutty.viewarr.data.sports

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.settings.remoteStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A league or competition ESPN lists ([path]: "soccer/sco.1"). [teams]: it has clubs to pick (not F1, tennis…). */
data class SportLeague(val path: String, val name: String, val short: String, val emoji: String, val teams: Boolean = true) {
    /** Its sport (Settings → Sports' sport buttons, and typing a sport's name). */
    val sport: Sport get() = Sport.of(path)
}

/** A sport: what the search knows it as ([words]: "soccer", "nfl", "f1"…). */
enum class Sport(val label: String, val emoji: String, val words: List<String>) {
    Football("Football", "⚽", listOf("football", "soccer", "footy")),
    Rugby("Rugby", "🏉", listOf("rugby", "rugby union", "rugby league")),
    Golf("Golf", "⛳", listOf("golf")),
    Tennis("Tennis", "🎾", listOf("tennis")),
    Motor("Motor racing", "🏎️", listOf("motor racing", "motorsport", "racing", "formula", "f1", "nascar", "indycar")),
    Fighting("UFC / MMA", "🥊", listOf("mma", "ufc", "fighting", "fight", "cage")),
    Basketball("Basketball", "🏀", listOf("basketball", "hoops")),
    AmericanFootball("American football", "🏈", listOf("american football", "nfl", "gridiron", "college football")),
    Hockey("Ice hockey", "🏒", listOf("ice hockey", "hockey", "nhl")),
    Baseball("Baseball", "⚾", listOf("baseball", "mlb")),
    Cricket("Cricket", "🏏", listOf("cricket")),
    /** Anything else ESPN's search finds (Aussie rules, lacrosse…): no button of its own. */
    Other("Sport", "🏆", emptyList());

    companion object {
        fun of(path: String) = when (path.substringBefore('/')) {
            "soccer" -> Football; "rugby", "rugby-league" -> Rugby; "golf" -> Golf; "tennis" -> Tennis; "racing" -> Motor
            "mma" -> Fighting; "basketball" -> Basketball; "football" -> AmericanFootball; "hockey" -> Hockey; "baseball" -> Baseball
            "cricket" -> Cricket; else -> Other
        }

        /** The sport [q] names ("golf", "soccer", "Ameri…"), if it names one: a word it's known by, or the start of one. */
        fun named(q: String): Sport? {
            val t = q.trim().lowercase()
            if (t.length < 3) return null
            return entries.firstOrNull { s -> s.words.any { it == t } } ?: entries.firstOrNull { s -> s.words.any { it.startsWith(t) } }
        }
    }
}

/** A team or league you follow (Settings → Sports): saved per profile; [pinned] also puts it in the main shortcut row. */
data class SportFollow(
    val kind: Kind, val league: String, val id: String, val name: String, val logo: String?, val pinned: Boolean = false,
    /** In search results only (not saved): which league / that it's a women's side, to tell same-named ones apart. */
    val detail: String? = null,
) {
    enum class Kind { Team, League }
    /** Its shortcut id ("sport:team:soccer/sco.1:256", "sport:league:soccer/sco.1"). */
    val shortcutId get() = if (kind == Kind.Team) "sport:team:$league:$id" else "sport:league:$league"
}

/** One game: who, when, how it stands. [state]: "pre", "in" (on now) or "post". */
data class SportGame(
    val id: String, val league: String, val leagueName: String,
    val home: String, val away: String, val homeLogo: String?, val awayLogo: String?,
    val start: Long, val state: String, val score: String?, val detail: String?,
) {
    val title get() = "$home v $away"
}

/**
 * The Sports hub's data, from ESPN's public (unofficial) API: the leagues offered, their teams (for the search in
 * Settings → Sports), and the games of a team or league. Nothing is sent but the league / team asked about. Which
 * channel shows a game is worked out from your guide elsewhere (GuideViewModel).
 */
@Singleton
class SportsRepository @Inject constructor(
    private val http: HttpJson,
    @ApplicationContext private val context: android.content.Context,
) {
    val leagues: List<SportLeague> = listOf(
        SportLeague("soccer/sco.1", "Scottish Premiership", "SPFL", "⚽"),
        SportLeague("soccer/sco.2", "Scottish Championship", "SPFL Championship", "⚽"),
        SportLeague("soccer/sco.tennents", "Scottish Cup", "Scottish Cup", "⚽"),
        SportLeague("soccer/sco.cis", "Scottish League Cup", "League Cup", "⚽"),
        SportLeague("soccer/eng.1", "Premier League", "EPL", "⚽"),
        SportLeague("soccer/eng.2", "Championship", "EFL", "⚽"),
        SportLeague("soccer/eng.3", "League One", "League One", "⚽"),
        SportLeague("soccer/eng.4", "League Two", "League Two", "⚽"),
        SportLeague("soccer/eng.fa", "FA Cup", "FA Cup", "⚽"),
        SportLeague("soccer/eng.league_cup", "Carabao Cup", "Carabao Cup", "⚽"),
        SportLeague("soccer/uefa.champions", "Champions League", "UCL", "⚽"),
        SportLeague("soccer/uefa.europa", "Europa League", "UEL", "⚽"),
        SportLeague("soccer/uefa.europa.conf", "Conference League", "UECL", "⚽"),
        SportLeague("soccer/uefa.nations", "Nations League", "Nations League", "⚽"),
        SportLeague("soccer/fifa.worldq.uefa", "World Cup Qualifying (Europe)", "WCQ", "⚽"),
        SportLeague("soccer/fifa.world", "World Cup", "World Cup", "⚽"),
        SportLeague("soccer/esp.1", "La Liga", "La Liga", "⚽"),
        SportLeague("soccer/ita.1", "Serie A", "Serie A", "⚽"),
        SportLeague("soccer/ger.1", "Bundesliga", "Bundesliga", "⚽"),
        SportLeague("soccer/fra.1", "Ligue 1", "Ligue 1", "⚽"),
        SportLeague("soccer/ned.1", "Eredivisie", "Eredivisie", "⚽"),
        SportLeague("soccer/por.1", "Primeira Liga", "Primeira Liga", "⚽"),
        SportLeague("soccer/usa.1", "MLS", "MLS", "⚽"),
        SportLeague("rugby/180659", "Six Nations", "Six Nations", "🏉"),
        SportLeague("rugby/267979", "Premiership Rugby", "Prem Rugby", "🏉"),
        SportLeague("rugby/270557", "United Rugby Championship", "URC", "🏉"),
        SportLeague("rugby/244293", "The Rugby Championship", "Rugby Championship", "🏉"),
        SportLeague("rugby/164205", "Rugby World Cup", "RWC", "🏉"),
        SportLeague("rugby-league/3", "NRL", "NRL", "🏉"),
        SportLeague("basketball/nba", "NBA", "NBA", "🏀"),
        SportLeague("basketball/wnba", "WNBA", "WNBA", "🏀"),
        SportLeague("basketball/mens-college-basketball", "College Basketball", "NCAAB", "🏀"),
        SportLeague("football/nfl", "NFL", "NFL", "🏈"),
        SportLeague("football/college-football", "College Football", "NCAAF", "🏈"),
        SportLeague("hockey/nhl", "NHL", "NHL", "🏒"),
        SportLeague("baseball/mlb", "MLB", "MLB", "⚾"),
        SportLeague("racing/f1", "Formula 1", "F1", "🏎️", teams = false),
        SportLeague("racing/irl", "IndyCar", "IndyCar", "🏎️", teams = false),
        SportLeague("racing/nascar-premier", "NASCAR Cup Series", "NASCAR", "🏎️", teams = false),
        SportLeague("mma/ufc", "UFC", "UFC", "🥊", teams = false),
        SportLeague("tennis/atp", "ATP Tennis", "ATP", "🎾", teams = false),
        SportLeague("tennis/wta", "WTA Tennis", "WTA", "🎾", teams = false),
        SportLeague("golf/pga", "PGA Tour", "PGA", "⛳", teams = false),
        SportLeague("golf/eur", "DP World Tour", "DP World", "⛳", teams = false),
        SportLeague("golf/lpga", "LPGA Tour", "LPGA", "⛳", teams = false),
    )
    /** Leagues ESPN's search turned up that aren't in [leagues] (any ESPN has: Ligue 2, LIV Golf, the A-League…). */
    private val found = ConcurrentHashMap<String, SportLeague>()

    /** A league by its path: one offered, one found by a search, else just its sport's look. */
    fun league(path: String): SportLeague =
        leagues.firstOrNull { it.path == path } ?: found[path] ?: SportLeague(path, path.substringAfter('/'), path.substringAfter('/'), Sport.of(path).emoji, hasTeams(path))

    /** Whether a sport has clubs to pick (not golf, tennis, racing or fights: players and drivers, not teams). */
    private fun hasTeams(path: String) = Sport.of(path) !in setOf(Sport.Golf, Sport.Tennis, Sport.Motor, Sport.Fighting)

    private val base = "https://site.api.espn.com/apis/site/v2/sports"

    // ---- Following (per profile, saved with the remote settings so it's backed up with them)

    val follows: Flow<List<SportFollow>> = context.remoteStore.data.map { p -> decode(p[stringPreferencesKey(KEY)]) }

    suspend fun setFollows(list: List<SportFollow>) {
        context.remoteStore.edit { it[stringPreferencesKey(KEY)] = list.joinToString(REC) { f -> listOf(f.kind.name, f.league, f.id, f.name, f.logo.orEmpty(), if (f.pinned) "1" else "0").joinToString(SEP) } }
    }

    private fun decode(s: String?): List<SportFollow> = s.orEmpty().split(REC).mapNotNull { r ->
        val p = r.split(SEP)
        if (p.size < 6) return@mapNotNull null
        SportFollow(runCatching { SportFollow.Kind.valueOf(p[0]) }.getOrNull() ?: return@mapNotNull null, p[1], p[2], p[3], p[4].ifEmpty { null }, p[5] == "1")
    }

    // ---- Search (teams across the leagues offered, and the leagues themselves)

    private val teamCache = ConcurrentHashMap<String, List<SportFollow>>()

    private suspend fun teamsOf(l: SportLeague): List<SportFollow> {
        if (!l.teams) return emptyList()
        teamCache[l.path]?.let { return it }
        val body = withTimeoutOrNull(10_000) { runCatching { http.get("$base/${l.path}/teams") }.getOrNull() }?.takeIf { it.ok }?.body
        val list = body.array("sports").firstOrNull().array("leagues").firstOrNull().array("teams").mapNotNull { t ->
            val team = t.child("team")
            val id = team.str("id") ?: return@mapNotNull null
            SportFollow(SportFollow.Kind.Team, l.path, id, team.str("displayName") ?: return@mapNotNull null, team.array("logos").firstOrNull().str("href"))
        }
        if (list.isNotEmpty()) teamCache[l.path] = list
        return list
    }

    /** A league as a follow (OK in the search follows it). */
    fun asFollow(l: SportLeague) = SportFollow(SportFollow.Kind.League, l.path, l.path, l.name, null)

    /** A sport's leagues and competitions (its button in Settings → Sports, or typing its name). */
    fun leaguesOf(sport: Sport) = leagues.filter { it.sport == sport }

    /** A league's teams, A–Z (Choose a team, from a league in the search). */
    suspend fun teamsIn(path: String): List<SportFollow> = teamsOf(league(path)).sortedBy { it.name.lowercase() }

    /**
     * Teams, leagues and sports matching [q], from ESPN's own search: every team and competition ESPN covers, not just
     * the ones listed here ("Ligue 2", "Hearts", "LIV golf"). A sport's name ("golf", "soccer", "nfl") also brings the
     * leagues listed here for it first. If ESPN's search can't be reached: the listed leagues and their teams.
     */
    suspend fun search(q: String): List<SportFollow> = coroutineScope {
        val t = q.trim().lowercase()
        if (t.length < 2) return@coroutineScope emptyList()
        val sportLeagues = Sport.named(t)?.let { sp -> leaguesOf(sp).map(::asFollow) }.orEmpty()
        val espn = espnSearch(t)
        val rest = espn ?: run {
            val gate = Semaphore(6)
            val teams = leagues.map { l -> async { gate.withPermit { teamsOf(l) } } }.awaitAll().flatten()
                .filter { it.name.lowercase().contains(t) }
                // One entry per club (a club is in its league and its cups): its league's, the first offered.
                .distinctBy { it.name.lowercase() }
            leagues.filter { it.name.lowercase().contains(t) || it.short.lowercase().contains(t) }.map(::asFollow) +
                teams.sortedBy { if (it.name.lowercase().startsWith(t)) 0 else 1 }
        }
        (sportLeagues + rest).distinctBy { it.shortcutId }.take(60)
    }

    /** ESPN's search (teams and leagues, best match first). Null: it couldn't be reached. */
    private suspend fun espnSearch(q: String): List<SportFollow>? {
        val url = "https://site.web.api.espn.com/apis/common/v3/search?query=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=40&type=team,league"
        val res = withTimeoutOrNull(10_000) { runCatching { http.get(url) }.getOrNull() }?.takeIf { it.ok } ?: return null
        return res.body.array("items").mapNotNull { i ->
            val sport = i.str("sport") ?: return@mapNotNull null
            val slug = i.str("league") ?: i.str("defaultLeagueSlug") ?: return@mapNotNull null
            val path = "$sport/$slug"
            val name = i.str("displayName") ?: return@mapNotNull null
            val logo = i.array("logos").firstOrNull().str("href")
            val women = Regex("(^|\\.)w(\\.|$)|wchampions|women|wnba|nwsl").containsMatchIn(slug)
            when (i.str("type")) {
                "team" -> {
                    val lg = leagues.firstOrNull { it.path == path } ?: found[path]
                    val detail = listOfNotNull(lg?.short ?: Sport.of(path).label.takeIf { Sport.of(path) != Sport.Other }, if (women) "Women" else null).joinToString(" · ")
                    SportFollow(SportFollow.Kind.Team, path, i.str("id") ?: return@mapNotNull null, name, logo, detail = detail.ifEmpty { null })
                }
                "league" -> {
                    val l = leagues.firstOrNull { it.path == path }
                        ?: SportLeague(path, name, i.str("abbreviation") ?: name, Sport.of(path).emoji, hasTeams(path)).also { found[path] = it }
                    asFollow(l).copy(logo = logo, detail = Sport.of(path).label.takeIf { Sport.of(path) != Sport.Other })
                }
                else -> null
            }
        }
    }

    // ---- Games

    private val gamesCache = ConcurrentHashMap<String, Pair<Long, List<SportGame>>>()

    /** A followed team's or league's games: recent results and what's coming (cached for 10 minutes). */
    suspend fun games(f: SportFollow): List<SportGame> {
        val key = f.shortcutId
        gamesCache[key]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        val games = if (f.kind == SportFollow.Kind.Team) teamGames(f) else leagueGames(f.league)
        if (games.isNotEmpty()) gamesCache[key] = System.currentTimeMillis() to games
        return games
    }

    private suspend fun teamGames(f: SportFollow): List<SportGame> = coroutineScope {
        // Fixtures (to come) and results (played). ESPN gives a team's schedule one competition at a time, so a
        // football team's cups and European games are asked for too (a competition it isn't in just comes back empty).
        // Results only from its own league; cups and Europe just their fixtures. A competition the team turned out not to
        // be in is skipped for a day (most of the cups and European ones, most of the time).
        val now = System.currentTimeMillis()
        val cups = cupsFor(f.league).filter { c -> notIn["${f.id}|$c"]?.let { now - it > 24 * 3_600_000L } ?: true }
        val asks = listOf("$base/${f.league}/teams/${f.id}/schedule?fixture=true" to f.league, "$base/${f.league}/teams/${f.id}/schedule" to f.league) +
            cups.map { c -> "$base/$c/teams/${f.id}/schedule?fixture=true" to c }
        asks.map { (u, c) ->
            async {
                val res = withTimeoutOrNull(10_000) { runCatching { http.get(u) }.getOrNull() }
                val games = res?.takeIf { it.ok }?.body.array("events").mapNotNull { game(it, c) }
                // Answered, with nothing for this team: not in it (a failed request isn't remembered).
                if (c != f.league && res != null && (games.isEmpty() || !res.ok)) notIn["${f.id}|$c"] = now
                games
            }
        }.awaitAll().flatten().distinctBy { it.id }.sortedBy { it.start }
    }

    /** "team|competition" → when it was found the team isn't in that competition. */
    private val notIn = ConcurrentHashMap<String, Long>()

    /** A football league's teams' other competitions: their country's cups, and Europe. */
    private fun cupsFor(league: String): List<String> {
        if (!league.startsWith("soccer/")) return emptyList()
        val europe = listOf("soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.europa.conf")
        val country = league.removePrefix("soccer/").substringBefore('.')
        val cups = when (country) {
            "sco" -> listOf("soccer/sco.tennents", "soccer/sco.cis", "soccer/sco.challenge")
            "eng" -> listOf("soccer/eng.fa", "soccer/eng.league_cup")
            "esp" -> listOf("soccer/esp.copa_del_rey")
            "ger" -> listOf("soccer/ger.dfb_pokal")
            "ita" -> listOf("soccer/ita.coppa_italia")
            "fra" -> listOf("soccer/fra.coupe_de_france")
            else -> emptyList()
        }
        return if (league in europe) emptyList() else cups + europe
    }

    /**
     * A league's games: its latest results (the plain scoreboard) and what's coming, fetched a day at a time (ESPN's
     * scoreboard takes one date): the league's next match days from its calendar (leagues: a list of days; cups and
     * European competitions: their rounds, with dates), up to five weeks ahead. A week at a time missed everything
     * over an international break, and a cup's next round weeks away.
     */
    private suspend fun leagueGames(league: String): List<SportGame> = coroutineScope {
        val today = java.time.LocalDate.now()
        val fmt = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        suspend fun board(q: String) = withTimeoutOrNull(10_000) { runCatching { http.get("$base/$league/scoreboard$q") }.getOrNull() }?.takeIf { it.ok }?.body
        val plain = board("")
        fun day(s: String?) = s?.take(10)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val cal = plain.array("leagues").firstOrNull().array("calendar")
        val days = sortedSetOf<java.time.LocalDate>()
        val horizon = today.plusDays(35)
        if (cal.isNotEmpty() && cal.first() is kotlinx.serialization.json.JsonPrimitive) {
            // A list of match days: the next few.
            cal.mapNotNull { day((it as? kotlinx.serialization.json.JsonPrimitive)?.content) }.filter { !it.isBefore(today) && !it.isAfter(horizon) }.take(5).forEach { days += it }
        } else {
            // Rounds / stages with dates: the days of the current or next one, up to four weeks.
            cal.flatMap { it.array("entries").ifEmpty { listOf(it) } }.forEach { r ->
                val start = day(r.str("startDate")) ?: return@forEach
                val end = day(r.str("endDate")) ?: start.plusDays(3)
                if (end.isBefore(today) || start.isAfter(horizon)) return@forEach
                var d = if (start.isBefore(today)) today else start
                val last = minOf(end, d.plusDays(27))
                while (!d.isAfter(last) && days.size < 28) { days += d; d = d.plusDays(1) }
            }
            if (days.isEmpty()) (0..13).forEach { days += today.plusDays(it.toLong()) }
        }
        val gate = Semaphore(6)
        val ahead = days.map { d -> async { gate.withPermit { board("?dates=${d.format(fmt)}").array("events").mapNotNull { game(it, league) } } } }.awaitAll().flatten()
        (plain.array("events").mapNotNull { game(it, league) } + ahead).distinctBy { it.id }.sortedBy { it.start }
    }

    private fun game(e: kotlinx.serialization.json.JsonElement, league: String): SportGame? {
        val id = e.str("id") ?: return null
        val comp = e.array("competitions").firstOrNull()
        // "2026-11-21T15:00Z" (no seconds): the offset parser takes it.
        val start = e.str("date")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: return null
        val sides = comp.array("competitors")
        fun side(which: String) = sides.firstOrNull { it.str("homeAway") == which } ?: sides.getOrNull(if (which == "home") 0 else 1)
        val h = side("home"); val a = side("away")
        fun name(c: kotlinx.serialization.json.JsonElement?) = c.child("team").str("displayName") ?: c.child("athlete").str("displayName")
        val home = name(h); val away = name(a)
        val status = comp.child("status").child("type").takeIf { it != null } ?: e.child("status").child("type")
        val state = status.str("state") ?: "pre"
        fun score(c: kotlinx.serialization.json.JsonElement?) = (c.child("score") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: c.child("score").str("displayValue")
        val sc = if (state != "pre") listOfNotNull(score(h), score(a)).takeIf { it.size == 2 }?.joinToString(" – ") else null
        return SportGame(
            id = id, league = league,
            leagueName = e.child("league").str("name") ?: league(league)?.name ?: "",
            home = home ?: e.str("name") ?: return null, away = away ?: "",
            homeLogo = h.child("team").array("logos").firstOrNull().str("href") ?: h.child("team").str("logo"),
            awayLogo = a.child("team").array("logos").firstOrNull().str("href") ?: a.child("team").str("logo"),
            start = start, state = state, score = sc, detail = status.str("shortDetail"),
        )
    }

    private companion object {
        const val KEY = "sports_follow"
        const val SEP = "\u001F"
        const val REC = "\u001E"
    }
}
