package io.github.petebutty.viewarr.data.guide

import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live now's event cards (the Live TV hub): event channels' guide entries made readable. Placeholders the event
 * plugins write ("Ended: …", "No event today") are dropped; names are tidied ("EFL EFL Trophy" → "EFL Trophy", a
 * leading start time removed); the sport is recognised for an icon and colour; and a match ("A vs B") gets its
 * picture from TheSportsDB (a free, open sports database: only the two team names are sent).
 */
@Singleton
class SportsEvents @Inject constructor(private val http: HttpJson) {

    /** A sport's look on a card without a picture. */
    data class Sport(val name: String, val emoji: String, val colors: List<Long>)

    /** Null: not a real event (a placeholder, or one that's over). Otherwise the tidied name. */
    fun clean(title: String): String? {
        var t = title.trim()
        if (t.isEmpty()) return null
        if (PLACEHOLDER.containsMatchIn(t)) return null
        // Ranked Matchups' names ("EPL ★6.0 · Leeds United at Chelsea · title race"): the game itself.
        if ('★' in t) t = t.substringAfter(" · ", t).substringBefore(" · ")
        // A start time at the front ("16:55 ATP Tour …"), already shown on the card.
        t = t.replace(Regex("^\\d{1,2}[:.]\\d{2}\\s*(am|pm)?\\s*[-–:|]?\\s*", RegexOption.IGNORE_CASE), "")
        // A competition named twice ("EFL EFL Trophy", "ATP Tour ATP Tennis"): its second, fuller name.
        t = t.replace(Regex("^(\\S+)\\b(?:\\s+\\S+){0,2}?\\s+\\1\\b"), "$1")
        return t.trim().ifEmpty { null }
    }

    fun sport(text: String): Sport? = SPORTS.firstOrNull { (re, _) -> re.containsMatchIn(text) }?.second

    private val thumbs = ConcurrentHashMap<String, String>()

    /** A match's picture ("Barnsley vs Sheffield Wednesday"): the fixture's thumbnail, else the league's badge. */
    suspend fun picture(title: String): String? {
        // "Leeds United at Chelsea" (Ranked Matchups: away at home) is Chelsea vs Leeds United.
        AWAY_AT_HOME.matchEntire(title.trim())?.let { a -> return picture("${a.groupValues[2]} vs ${a.groupValues[1]}") }
        val m = MATCH.find(title) ?: return null
        // The teams: the words either side of "vs", without the competition in front (its last few words).
        val home = m.groupValues[1].trim().split(Regex("\\s+")).takeLast(3).joinToString(" ").trim()
        val away = m.groupValues[2].trim().split(Regex("\\s+|\\(|-")).filter { it.isNotBlank() }.take(3).joinToString(" ").trim()
        if (home.length < 3 || away.length < 3) return null
        val key = "$home|$away".lowercase()
        thumbs[key]?.let { return it.ifEmpty { null } }
        // Shorter team names first matched more often (the guide's "Sheff Wed" vs the database's full name).
        var found: String? = null
        for ((h, a) in listOf(home to away, home.split(" ").last() to away.split(" ").first())) {
            val q = "${h.replace(' ', '_')}_vs_${a.replace(' ', '_')}"
            val events = withTimeoutOrNull(6_000) {
                runCatching { http.get("https://www.thesportsdb.com/api/v1/json/123/searchevents.php?e=${java.net.URLEncoder.encode(q, "UTF-8")}") }.getOrNull()
            }?.takeIf { it.ok }?.body.array("event")
            // The fixture nearest today (the same two teams meet again in other seasons).
            val today = java.time.LocalDate.now()
            val best = events.minByOrNull { e ->
                e.str("dateEvent")?.let { runCatching { kotlin.math.abs(java.time.LocalDate.parse(it).toEpochDay() - today.toEpochDay()) }.getOrNull() } ?: Long.MAX_VALUE
            }
            found = best?.let { it.str("strThumb") ?: it.str("strLeagueBadge") }
            if (found != null) break
        }
        thumbs[key] = found.orEmpty()
        return found
    }

    private companion object {
        val PLACEHOLDER = Regex("^(ended|finished|full time|ft)\\b|^no (event|events|programme|program|game|match)\\b|^(tba|tbc|to be announced|off air|closedown|stand ?by)\\b|^event (ended|finished)", RegexOption.IGNORE_CASE)
        val AWAY_AT_HOME = Regex("([A-Z][\\w&.' -]{2,40}?) at ([A-Z][\\w&.' -]{2,40})")
        val MATCH = Regex("(.+?)\\s+(?:vs?\\.?|@|-v-)\\s+(.+)", RegexOption.IGNORE_CASE)
        fun r(words: String) = Regex("\\b($words)\\b", RegexOption.IGNORE_CASE)
        val SPORTS = listOf(
            r("tennis|atp|wta|wimbledon|roland garros|us open tennis") to Sport("Tennis", "🎾", listOf(0xFF8BC34A, 0xFF33691E)),
            r("rugby|six nations|urc|premiership rugby|super league|nrl") to Sport("Rugby", "🏉", listOf(0xFF8D6E63, 0xFF4E342E)),
            r("f1|formula 1|formula one|grand prix|indycar|nascar") to Sport("Motor racing", "🏎️", listOf(0xFFE53935, 0xFF7F0000)),
            r("motogp|moto gp|superbikes?|wsbk|speedway") to Sport("Motorbikes", "🏍️", listOf(0xFFFF7043, 0xFFBF360C)),
            r("ufc|mma|boxing|matchroom|fight night|bellator|pfl|wwe|aew|wrestling|ppv|pay per view") to Sport("Fight", "🥊", listOf(0xFFD32F2F, 0xFF311B92)),
            r("cricket|ashes|ipl|t20|odi|test match") to Sport("Cricket", "🏏", listOf(0xFF66BB6A, 0xFF1B5E20)),
            r("golf|pga|ryder cup|the open|lpga|dp world") to Sport("Golf", "⛳", listOf(0xFF43A047, 0xFF004D40)),
            r("darts|pdc|premier league darts") to Sport("Darts", "🎯", listOf(0xFFFFB300, 0xFFB71C1C)),
            r("snooker|pool|world matchplay snooker") to Sport("Snooker", "🎱", listOf(0xFF2E7D32, 0xFF1B3A1B)),
            r("nfl|american football|super bowl|ncaaf") to Sport("American football", "🏈", listOf(0xFF6D4C41, 0xFF263238)),
            r("nba|basketball|ncaab|euroleague basketball") to Sport("Basketball", "🏀", listOf(0xFFFF9800, 0xFFE65100)),
            r("nhl|ice hockey|hockey") to Sport("Hockey", "🏒", listOf(0xFF4FC3F7, 0xFF01579B)),
            r("mlb|baseball") to Sport("Baseball", "⚾", listOf(0xFF90A4AE, 0xFF37474F)),
            r("horse racing|racing tv|cheltenham|ascot|grand national") to Sport("Horse racing", "🏇", listOf(0xFF8D6E63, 0xFF3E2723)),
            r("cycling|tour de france|giro|vuelta") to Sport("Cycling", "🚴", listOf(0xFFFFEB3B, 0xFFF57F17)),
            r("efl|epl|spfl|premier league|championship|league one|league two|fa cup|carabao|uefa|champions league|europa|conference league|la liga|serie a|bundesliga|ligue 1|mls|football|soccer|scottish cup|nifl|vs?") to Sport("Football", "⚽", listOf(0xFF26A69A, 0xFF004D40)),
        )
    }
}
