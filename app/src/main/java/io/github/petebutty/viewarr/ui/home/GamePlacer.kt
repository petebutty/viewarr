package io.github.petebutty.viewarr.ui.home

import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.sports.GameMatch
import io.github.petebutty.viewarr.data.sports.SportGame
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.theme.Dates

/** A game with the channel it's on (your guide's, else the one the listings name…), and why that one. */
internal class GameCard(
    val game: SportGame,
    val inGuide: Pair<ChannelListItem, GuideProgram>?,
    val named: List<String>,
    val channel: ChannelListItem?,
    /** The UK listings cover its day and it isn't in them (a Saturday 3pm game). */
    val notOnUkTv: Boolean = false,
    /** [channel] is a team's own channel (ClubChannels), the game being on no other channel of yours. */
    val club: Boolean = false,
    /** False: [channel] is only a possible match (one team named), shown grey as "possibly". */
    val sure: Boolean = true,
    /** Why this channel (the game page): "From your TV guide: …", "Sky TV guide: …", "Club channel: …". */
    val why: String = "",
)

/**
 * Where each game is on, from one channel list: the Sports page's cards and Live now's games both come from here, so
 * they always agree. Every channel's name is cleaned up once per list (not on every move: thousands of channels, for
 * every panel, made moving along the rows crawl).
 */
internal class GamePlacer(private val channels: List<ChannelListItem>) {
    private val byNorm = channels.map { normChannel(it.name) to it }
    private val exact = byNorm.toMap()
    private val clubs = ClubChannels.index(channels)
    private val byUuid by lazy { channels.associateBy { it.uuid } }
    /** Each channel's name normalised once, for matching games against them all (GameMatch). */
    private val matchNames by lazy { channels.map { " " + GameMatch.norm(it.name) + " " } }

    /**
     * A channel by the name the listings give ("Sky Sports Main Event", "TNT Sports 1", "Celtic TV"…): exact first,
     * then a channel whose name holds it ("UK| SKY SPORTS MAIN EVENT FHD", "SPFL - CELTIC TV"), the shortest (most
     * exactly that channel; not a "+1", not a numbered event channel). "TBC" (not announced yet) isn't guessed at.
     * ("Sky Sports TBC" and "Ultra HDR" used to land on Sky Sports+, and club channels on nothing: only names starting
     * the same way matched. 2026-11-16.)
     */
    fun byName(name: String): ChannelListItem? {
        if (TBC.containsMatchIn(name)) return null
        val n = normChannel(name)
        // A 4K name: only a channel of exactly that name ("Sky Sports Ultra HDR" isn't any one Sky Sports channel).
        if (FOUR_K.containsMatchIn(name)) return exact[n]
        if (n.length < 3) return null
        exact[n]?.let { return it }
        return byNorm.filter { (cn, c) -> cn.contains(n) && !cn.contains("plus1") && !cn.contains(n + "plus") && !EVENT_SLOT.containsMatchIn(c.name) }
            .minByOrNull { (cn, _) -> cn.length }?.second
            ?: exact[n + "plus"] // "Apple TV": Apple TV+
    }

    /**
     * An event channel named after the match (the EPG & Sports Editor plugin's "Home @ Away", or a provider's "UEFA 20:
     * 12:45 KuPS vs Gent"), for a game on now or within a few hours: both teams by name in it (GameMatch).
     */
    private fun eventChannel(g: SportGame): ChannelListItem? {
        if (g.state == "post" || g.start - System.currentTimeMillis() > 6 * 3_600_000L) return null
        val i = matchNames.indexOfFirst { GameMatch.normTextIsGame(it, g) == GameMatch.SURE }
        return channels.getOrNull(i)
    }

    /**
     * Where OK goes, and why: the game in your guide ([ch]: both teams in a programme around kick-off), else the
     * channel the listings name ([SportsHub.tv]: Sky's guide, the UK listings; sure: both teams, possible: one by
     * name), else an event channel named after the match, else the home (or away) team's own channel.
     */
    fun place(hub: SportsHub, g: SportGame, ch: Pair<ChannelListItem, GuideProgram>?): GameCard {
        val raw = hub.tv[g.id].orEmpty()
        val named = raw.filter { it != GuideViewModel.NOT_ON_UK_TV }
        val notOnTv = ch == null && GuideViewModel.NOT_ON_UK_TV in raw
        if (ch != null) return GameCard(g, ch, named, ch.first,
            why = "From your TV guide: \"${ch.second.title}\" on ${ch.first.name}, ${Dates.time(ch.second.startTime)}–${Dates.time(ch.second.endTime)}")
        // Worked out on the server for the whole house (the Viewarr plugin on Dispatcharr), while it's recent.
        io.github.petebutty.viewarr.data.sync.ServerSports.fresh?.games?.get(g.id)?.let { sg ->
            sg.channels.firstNotNullOfOrNull { byUuid[it] }?.let { c ->
                return GameCard(g, null, named, c, club = sg.source == "club", sure = sg.sure >= 2, why = sg.why.ifEmpty { "Your Dispatcharr: ${c.name}" })
            }
        }
        val m = hub.tvMatch[g.id]
        val listed = named.firstNotNullOfOrNull { n -> byName(n)?.let { n to it } }
        if (listed != null) {
            val (said, c) = listed
            val sure = m == null || m.sureness == GameMatch.SURE
            val src = m?.source ?: "UK TV listings"
            val why = buildString {
                append("$src: $said")
                if (m?.source == "Sky TV guide" && m.listing != null) append(", \"${m.listing}\"")
                if (!said.equals(c.name, true)) append(" (your channel ${c.name})")
                if (!sure) append(". Possible match only: the listing is \"${m?.listing}\"")
            }
            return GameCard(g, null, named, c, sure = sure, why = why)
        }
        eventChannel(g)?.let { return GameCard(g, null, named, it, notOnUkTv = notOnTv, why = "An event channel named after the match: ${it.name}") }
        // On none of those: the home (else away) team's own channel (a football club's, an NFL team's…).
        ClubChannels.forGame(clubs, g)?.let { c ->
            val why = (if (notOnTv) "Not on UK TV (the UK listings don't have it). " else if (named.isNotEmpty()) "Listed on ${named.joinToString(", ")}, not one of your channels. " else "Not in your guide or the UK listings. ") +
                "${ClubChannels.word(g).replaceFirstChar { it.uppercase() }} channel: ${c.name}"
            return GameCard(g, null, named, c, notOnUkTv = notOnTv, club = true, why = why)
        }
        val why = when {
            g.state == "post" -> ""
            notOnTv -> "Not on UK TV (a Saturday 3pm game, or not in the UK listings for that day)"
            named.isNotEmpty() -> "Listed on ${named.joinToString(", ")}: not one of your channels"
            else -> "Not in your TV guide or the UK listings yet"
        }
        return GameCard(g, null, named, null, notOnUkTv = notOnTv, why = why)
    }
}

private val NON_ALNUM = Regex("[^a-z0-9]")
/** Not announced yet ("Sky Sports TBC"): no channel guessed. */
private val TBC = Regex("\\bTBC\\b", RegexOption.IGNORE_CASE)
/** A 4K / Ultra HDR channel name. */
private val FOUR_K = Regex("(?i)\\b(ultra\\s*hdr|uhd|4k)\\b")
/** A provider's numbered event channel ("GB-DAZN 001:", "NFL B 13: 20:20 …"): not "DAZN" the channel. */
private val EVENT_SLOT = Regex("\\b\\d{1,3}\\s*:")

/** A channel name for matching: lower case, letters and digits only, "+" kept as "plus" (Sky Sports+ isn't Sky Sports),
 *  HD / FHD / SD marks and a "UK|"-style prefix dropped. */
internal fun normChannel(name: String): String = name.lowercase()
    .replace(CH_PREFIX, "")
    .replace("+1", " plus1 ").replace("+", " plus ")
    .replace(CH_QUALITY, " ")
    .replace(NON_ALNUM, "")
private val CH_PREFIX = Regex("^[a-z]{2,3}\\s*[|:\\-]\\s*")
private val CH_QUALITY = Regex("\\b(fhd|uhd|hd|sd|hevc|h265)\\b")
