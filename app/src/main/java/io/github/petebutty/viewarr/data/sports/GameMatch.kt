package io.github.petebutty.viewarr.data.sports

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a piece of text (a guide programme, a TV listing, a channel name) is about a game: one matcher for every
 * place the Sports page looks, using every name ESPN gives each team (full, short, the club's own: "Manchester
 * United", "Man United") plus the names British TV uses ("Man Utd", "Spurs", "Hibs"). Whole words only, both teams
 * needed to be sure. (Before: a word in common was enough, so "Manchester City v Fulham" took a "Manchester United
 * U18" listing and Feyenoord v Porto took Port Vale. 2026-11-16.)
 *
 * Team scores: 2 = one of its names, as whole words; 1 = a telling word of its name (5+ letters, not "United"…); 0.
 * A game is [SURE] with both teams found and 3+ between them; [POSSIBLE] with one team by name and nothing against.
 * An event (race, tournament, fight card: no two sides) is SURE with its sport named and a telling word of its name.
 */
object GameMatch {
    const val SURE = 2
    const val POSSIBLE = 1
    const val NO = 0

    private val DROP = setOf("fc", "afc", "cf", "sc", "the", "ac", "as", "ssc", "sv", "fk", "sk")
    /** Words in many teams' names: never enough on their own. */
    private val GENERIC = setOf(
        "united", "city", "town", "athletic", "athletico", "county", "rovers", "wanderers", "albion", "real", "club", "sporting",
        "football", "rangers", "saint", "north", "south", "east", "west", "under", "women", "ladies", "academy", "manchester",
        "sheffield", "bristol", "dundee", "milton", "keynes", "queens", "park", "hotspur", "forest", "palace", "villa", "borussia",
    )
    /** The names British TV and listings use for teams, by ESPN's (normalised) name. */
    private val EXTRA = mapOf(
        "manchester united" to listOf("man utd", "man united"), "manchester city" to listOf("man city"),
        "tottenham hotspur" to listOf("spurs", "tottenham"), "wolverhampton wanderers" to listOf("wolves"),
        "brighton and hove albion" to listOf("brighton"), "nottingham forest" to listOf("nottm forest", "notts forest"),
        "queens park rangers" to listOf("qpr"), "sheffield wednesday" to listOf("sheff wed", "sheffield weds"),
        "sheffield united" to listOf("sheff utd", "sheffield utd"), "west bromwich albion" to listOf("west brom"),
        "heart of midlothian" to listOf("hearts"), "hibernian" to listOf("hibs"), "inverness caledonian thistle" to listOf("inverness ct", "inverness"),
        "paris saint germain" to listOf("psg", "paris sg"), "internazionale" to listOf("inter milan", "inter"),
        "bayern munich" to listOf("bayern", "bayern munchen"), "milton keynes dons" to listOf("mk dons"),
        "newcastle united" to listOf("newcastle", "newcastle utd"), "west ham united" to listOf("west ham", "west ham utd"),
        "leeds united" to listOf("leeds", "leeds utd"), "afc bournemouth" to listOf("bournemouth"), "athletic club" to listOf("athletic bilbao"),
        "atletico madrid" to listOf("atletico de madrid"), "borussia dortmund" to listOf("dortmund"), "dundee united" to listOf("dundee utd"),
        "oxford united" to listOf("oxford utd"), "cambridge united" to listOf("cambridge utd"), "peterborough united" to listOf("peterborough"),
        "wycombe wanderers" to listOf("wycombe"), "bolton wanderers" to listOf("bolton"), "blackburn rovers" to listOf("blackburn"),
        "bristol rovers" to listOf("bristol rov"), "plymouth argyle" to listOf("plymouth"), "preston north end" to listOf("preston"),
        "huddersfield town" to listOf("huddersfield"), "ipswich town" to listOf("ipswich"), "luton town" to listOf("luton"),
        "leicester city" to listOf("leicester"), "norwich city" to listOf("norwich"), "stoke city" to listOf("stoke"),
        "swansea city" to listOf("swansea"), "cardiff city" to listOf("cardiff"), "hull city" to listOf("hull"), "coventry city" to listOf("coventry"),
        "birmingham city" to listOf("birmingham"), "derby county" to listOf("derby"), "charlton athletic" to listOf("charlton"),
        "wigan athletic" to listOf("wigan"), "oldham athletic" to listOf("oldham"), "st mirren" to listOf("saint mirren"), "st johnstone" to listOf("saint johnstone"),
        "la clippers" to listOf("los angeles clippers"), "la lakers" to listOf("los angeles lakers"), "la galaxy" to listOf("los angeles galaxy"),
    )
    private val YOUTH = Regex("\\b(u\\s?\\d{2}|under\\s?\\d{2}|youth|academy|reserves?|b team|ii)\\b")
    private val WOMEN = Regex("\\b(women|womens|ladies|w)\\b")

    // (Built once: compiling these on every call, for every channel and programme, froze a Shield opening Sports.)
    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        .replace("&", " and ").replace(NON_ALNUM, " ")
        .split(' ').filter { it.isNotBlank() && it !in DROP }.joinToString(" ")

    /**
     * A game's names and words, worked out once per game (not again for each of thousands of channels and programmes
     * it's checked against: that froze a Shield opening Sports. 2026-11-17).
     */
    private class Prepared(
        val home: Set<String>, val away: Set<String>, val homeTelling: Set<String>, val awayTelling: Set<String>,
        val youth: Boolean, val women: Boolean,
        val eventWords: Set<String>, val eventTelling: List<String>, val sportWords: List<String>,
    )
    private val prepared = ConcurrentHashMap<String, Prepared>()
    private fun prep(g: SportGame): Prepared {
        val key = g.league + "|" + g.id + "|" + g.home + "|" + g.away
        prepared[key]?.let { return it }
        if (prepared.size > 3000) prepared.clear()
        val h = aliases(g.homeNames.ifEmpty { listOf(g.home) })
        val a = aliases(g.awayNames.ifEmpty { listOf(g.away) })
        val gameText = " " + norm(g.home + " " + g.away + " " + g.leagueName) + " "
        val words = h.flatMap { it.split(' ') }.filter { it.length >= 3 }.toSet()
        return Prepared(
            h, a, telling(h), telling(a), YOUTH.containsMatchIn(gameText), WOMEN.containsMatchIn(gameText) || g.league.contains(".w."),
            words, words.filter { it.length >= 4 && it !in EVENT_GENERIC && !it.all(Char::isDigit) }, sportWords(g.league).map { norm(it) },
        ).also { prepared[key] = it }
    }

    /** Every name for a team (ESPN's, normalised, plus the ones TV uses). */
    fun aliases(names: List<String>): Set<String> {
        val base = names.map(::norm).filter { it.length >= 3 }.toSet()
        return base + base.flatMap { EXTRA[it].orEmpty() }
    }

    private fun telling(aliases: Set<String>) = aliases.flatMap { it.split(' ') }.filter { it.length >= 5 && it !in GENERIC }.toSet()

    /** How surely [text] (already normalised, padded with spaces) names the team. */
    private fun teamIn(text: String, aliases: Set<String>, telling: Set<String>): Int = when {
        aliases.any { " $it " in text } -> 2
        telling.any { " $it " in text } -> 1
        else -> 0
    }

    /** A youth or women's side in the text that the game isn't (or the other way round): not this game. */
    private fun sideMismatch(text: String, p: Prepared): Boolean =
        YOUTH.containsMatchIn(text) != p.youth || (WOMEN.containsMatchIn(text) && !p.women)

    /** Words that say which sport a programme is (an event's name alone, "Las Vegas", could be anything). */
    private fun sportWords(league: String): List<String> = when {
        league.startsWith("racing/f1") -> listOf("f1", "formula 1", "formula one", "grand prix", "gp")
        league.startsWith("racing/irl") -> listOf("indycar", "indy")
        league.startsWith("racing/nascar") -> listOf("nascar")
        league.startsWith("racing/") -> listOf("racing", "grand prix", "gp", "motogp", "motorsport")
        league.startsWith("mma/") -> listOf("ufc", "mma", "fight night")
        league.startsWith("golf/") -> listOf("golf", "pga", "lpga", "dp world", "liv")
        league.startsWith("tennis/") -> listOf("tennis", "atp", "wta")
        league.startsWith("boxing") -> listOf("boxing", "fight")
        else -> emptyList()
    }
    /** Words in many events' names (and sponsors' "presented by"): not telling on their own. */
    private val EVENT_GENERIC = setOf(
        "grand", "prix", "championship", "championships", "open", "tour", "fight", "night", "presented", "pres", "cup", "classic",
        "invitational", "series", "race", "round", "final", "finals", "tournament", "international", "world", "masters", "event",
        "ufc", "golf", "tennis", "formula", "nascar", "indycar", "atp", "wta", "pga", "lpga", "day", "week", "main", "card",
    )

    /**
     * An event (a race, a tournament, a fight card) in a text: the sport named ("F1", "UFC", "Golf"…) and a telling word
     * of the event's name ("Vegas", "Procházka", "Austin"); for a name of only common words ("ATP Finals"), all of them.
     */
    private fun eventIn(text: String, p: Prepared): Int {
        val sport = p.sportWords.any { " $it " in text }
        val telling = p.eventTelling
        val named = if (telling.isNotEmpty()) telling.any { " $it " in text } else p.eventWords.isNotEmpty() && p.eventWords.all { " $it " in text }
        return when { sport && named -> SURE; named && telling.count { " $it " in text } >= 2 -> POSSIBLE; else -> NO }
    }

    /** How surely a free text (programme title, channel name) is about the game: [SURE], [POSSIBLE] or [NO]. */
    fun textIsGame(raw: String, g: SportGame): Int = normTextIsGame(" " + norm(raw) + " ", g)

    /**
     * [textIsGame] for a text already normalised ([norm]) and padded with a space each side: for checking one game
     * against many texts (a channel list normalised once).
     */
    fun normTextIsGame(text: String, g: SportGame): Int {
        val p = prep(g)
        if (g.isEvent) return eventIn(text, p)
        if (sideMismatch(text, p)) return NO
        return verdict(teamIn(text, p.home, p.homeTelling), teamIn(text, p.away, p.awayTelling))
    }

    /** How surely a listing's two team names are the game's (either way round). */
    fun teamsAreGame(home: String, away: String, g: SportGame): Int {
        if (g.isEvent) return textIsGame("$home $away", g)
        val text = " " + norm("$home $away") + " "
        val p = prep(g)
        if (sideMismatch(text, p)) return NO
        // (A name that is one of the aliases is found whole in itself: 2.)
        val h = " " + norm(home) + " "; val a = " " + norm(away) + " "
        val straight = verdict(teamIn(h, p.home, p.homeTelling), teamIn(a, p.away, p.awayTelling))
        val swapped = verdict(teamIn(h, p.away, p.awayTelling), teamIn(a, p.home, p.homeTelling))
        return maxOf(straight, swapped)
    }

    private fun verdict(h: Int, a: Int) = when {
        h >= 1 && a >= 1 && h + a >= 3 -> SURE
        (h == 2 && a == 0) || (a == 2 && h == 0) -> POSSIBLE
        else -> NO
    }

    /** The words to look a game up by in the guide: each team's most telling word. */
    fun searchWords(g: SportGame): List<String> {
        val p = prep(g)
        return if (g.isEvent)
            p.home.flatMap { it.split(' ') }.filter { it.length >= 4 && it !in EVENT_GENERIC }.distinct().take(4)
                .ifEmpty { sportWords(g.league).take(2) }
        else listOf(p.homeTelling to p.home, p.awayTelling to p.away).flatMap { (tell, al) ->
            tell.maxByOrNull { it.length }?.let { listOf(it) } ?: al.toList()
        }.distinct().take(8)
    }
}
