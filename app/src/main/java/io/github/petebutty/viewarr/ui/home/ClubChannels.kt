package io.github.petebutty.viewarr.ui.home

import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.sports.GameMatch
import io.github.petebutty.viewarr.data.sports.SportGame

/**
 * Teams' own channels, for any sport: a football club's ("BOLTON WANDERERS", "SPFL - HIBS FC", "EPL - LFC TV"), an
 * NFL team's ("NFL ARIZONA CARDINALS"), any league's ("NBA LAKERS", "SERIE A - MILAN TV"). Providers' team channels
 * carry a team's games, the ones not on TV too (a Saturday 3pm game abroad). For a game with no other channel: the home
 * team's channel, else the away team's. Teams are found by every name ESPN gives them plus the names TV uses
 * ([GameMatch.aliases]), whole names only. (2026-11-16: every club had a channel in his Dispatcharr and the
 * Sports page never used them; then "any user, any sport".)
 */
internal object ClubChannels {
    /** A sports group (not films, news…). */
    private val GROUP = Regex("(?i)\\b(efl|epl|spfl|scottish|football|soccer|club|sport|sports|nfl|nba|nhl|mlb|mls|rugby|hockey|basketball|baseball|serie a|laliga|la liga|ligue|bundesliga|eredivisie|afl|nrl|cricket)")
    /** TV channels and event feeds, not a team's: "Sky Sports Football", "US NFL NETWORK", "NFL B 13:". */
    private val NOT_TEAM = Regex("(?i)\\b(sky|tnt|premier sports|bbc|itv|stv|amazon|prime|dazn|bt sport|s4c|alba|espn|fox|nbc|cbs|abc|peacock|paramount|network|redzone|red zone|zone|channel|league|cup|news|highlights|replay|pass|ticket|event|events|ppv|multi)\\b")
    private val EVENT_SLOT = Regex("\\d{1,3}\\s*:")
    /** "UK| ", "US ", then a league's name ("SPFL - ", "NFL ", "SERIE A - "), then a number ("SPFL 4 "). */
    private val PREFIX = Regex(
        "^((uk|us|ca|au|ie)\\s*\\|?\\s+)?((spfl|epl|efl|nifl|wsl|swpl|nfl|nba|nhl|mlb|mls|wnba|afl|nrl|serie a|laliga|la liga|ligue 1|bundesliga|eredivisie)\\s*[-|:]?\\s*)?(\\d+\\s+)?",
    )
    /** Words a team channel's name adds to the team's: "LFC TV", "CELTIC TV", "BURNLEY FC", "Official". */
    private val DROP = setOf("tv", "official", "hd", "fhd", "uhd", "sd", "live")
    /** What may follow a channel's (shorter) team name in the team's full name: "WEST HAM" for West Ham United. */
    private val SUFFIX = setOf("united", "city", "town", "albion", "and", "hove", "athletic", "rovers", "wanderers", "county", "hotspur", "argyle", "alexandra", "stanley")
    /** Nicknames that are a channel's whole name ("LFC TV", "HIBS FC"): mapped to the team's. */
    private val CHANNEL_NICKNAMES = mapOf("lfc" to "liverpool", "mufc" to "manchester united", "mcfc" to "manchester city", "utd" to "united", "st" to "saint")

    /** A channel's team name, normalised: "SPFL - HIBS FC" → "hibs", "NFL ARIZONA CARDINALS" → "arizona cardinals". */
    private fun key(name: String): String {
        var s = name.lowercase().replace("&", " and ").replace(".", " ").trim()
        repeat(2) { s = s.replace(PREFIX, "").trim() }
        val words = GameMatch.norm(s).split(' ').filter { it.isNotBlank() && it !in DROP }.flatMap { (CHANNEL_NICKNAMES[it] ?: it).split(' ') }
        return words.joinToString(" ")
    }

    /**
     * A channel's sport, from its name and group ("NFL ARIZONA CARDINALS" in "US NFL": American football; "SPFL - RANGERS
     * TV": football), as ESPN's sport path starts ("soccer", "football"…). Null: can't tell.
     */
    private fun sportOf(text: String): String? {
        val t = text.lowercase()
        return SPORT_WORDS.firstOrNull { (re, _) -> re.containsMatchIn(t) }?.second
    }
    // (Built once: one pattern per word per channel, for thousands of channels, froze a Shield opening Sports.)
    private val SPORT_WORDS = listOf(
        listOf("nfl", "american football", "ncaaf", "cfl") to "football",
        listOf("nba", "wnba", "basketball", "ncaab") to "basketball",
        listOf("nhl", "hockey") to "hockey",
        listOf("mlb", "baseball") to "baseball",
        listOf("rugby", "nrl", "super league") to "rugby",
        listOf("afl", "aussie rules") to "australian-football",
        listOf("cricket") to "cricket",
        listOf("efl", "epl", "spfl", "nifl", "wsl", "swpl", "soccer", "football", "club", "scottish", "serie a", "laliga", "la liga", "ligue", "bundesliga", "eredivisie", "mls") to "soccer",
    ).map { (words, sport) -> Regex("\\b(" + words.joinToString("|") + ")\\b") to sport }

    /**
     * Team channels by sport, then their team's name (the lowest number when a team has two). (By sport: the NHL's New
     * York Rangers, "Rangers" for short, took SPFL - RANGERS TV.)
     */
    fun index(channels: List<ChannelListItem>): Map<String, Map<String, ChannelListItem>> = channels
        .filter { c -> GROUP.containsMatchIn(c.groupName.orEmpty()) && !EVENT_SLOT.containsMatchIn(c.name) && !NOT_TEAM.containsMatchIn(c.name) }
        .mapNotNull { c -> sportOf(c.name + " " + c.groupName.orEmpty())?.let { it to c } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, list) -> list.sortedBy { it.number ?: Double.MAX_VALUE }.groupBy { key(it.name) }.filterKeys { it.length >= 3 }.mapValues { it.value.first() } }

    /** A team's channel: one of its names exactly, else a channel named by the start of it ("BRIGHTON", "WEST HAM"). */
    private fun find(index: Map<String, ChannelListItem>, names: List<String>): ChannelListItem? {
        val aliases = GameMatch.aliases(names)
        aliases.firstNotNullOfOrNull { index[it] }?.let { return it }
        // "SAINT MIRREN" / "ST MIRREN" and the like: a nickname table entry the other way round.
        aliases.firstNotNullOfOrNull { a -> index[a.split(' ').joinToString(" ") { w -> CHANNEL_NICKNAMES[w] ?: w }] }?.let { return it }
        val full = aliases.maxByOrNull { it.length } ?: return null
        return index.entries.filter { (ck, _) ->
            full.startsWith("$ck ") && full.removePrefix("$ck ").split(' ').all { it in SUFFIX }
        }.maxByOrNull { it.key.length }?.value
    }

    /**
     * Team games a team's own channel may carry: not events (races, tournaments), not women's or youth sides (a club's
     * channel is its men's team's), not international competitions (clubs' channels don't carry Europe or national teams).
     */
    private val INTERNATIONAL = listOf("uefa.", "fifa.", "concacaf.", "conmebol.", "afc.", "caf.")
    private fun covers(g: SportGame): Boolean {
        if (g.isEvent) return false
        val l = g.league.substringAfter('/')
        if (INTERNATIONAL.any { l.startsWith(it) }) return false
        if (l.contains(".w.") || l.startsWith("womens-")) return false
        return !g.home.contains("women", true) && !g.away.contains("women", true)
    }

    /** A game's team channel (home, else away), for a game one may carry and that isn't over. */
    fun forGame(index: Map<String, Map<String, ChannelListItem>>, g: SportGame): ChannelListItem? {
        if (!covers(g) || g.state == "post") return null
        val sport = index[g.league.substringBefore('/')] ?: return null
        return find(sport, g.homeNames.ifEmpty { listOf(g.home) }) ?: find(sport, g.awayNames.ifEmpty { listOf(g.away) })
    }

    /** "club" for football (a club's own stream), "team" otherwise. */
    fun word(g: SportGame) = if (g.league.startsWith("soccer/")) "club" else "team"
}
