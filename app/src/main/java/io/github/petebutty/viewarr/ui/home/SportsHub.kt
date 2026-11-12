package io.github.petebutty.viewarr.ui.home

import androidx.compose.runtime.mutableStateMapOf
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.media.ServiceLogo
import io.github.petebutty.viewarr.data.sports.SportFollow
import io.github.petebutty.viewarr.data.sports.SportGame
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.dvr.DvrViewModel
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.player.CardOption
import io.github.petebutty.viewarr.ui.player.LiveShortcut
import io.github.petebutty.viewarr.ui.player.OsdPoster
import io.github.petebutty.viewarr.ui.player.OsdRow
import io.github.petebutty.viewarr.ui.player.ShortcutColours
import io.github.petebutty.viewarr.ui.player.ShortcutLogos
import io.github.petebutty.viewarr.ui.settings.SportsViewModel
import io.github.petebutty.viewarr.ui.theme.Dates
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The Sports hub (Settings → Sports) on live TV: your teams' and leagues' games, which of your channels shows each
 * (from the guide; else the UK listings), and the cards, rows and pinned shortcuts built from them.
 *
 * [watch]: play a channel full screen; [toast]: a short message.
 */
internal class SportsHub(
    private val sportsVm: SportsViewModel,
    private val guide: GuideViewModel,
    private val dvr: DvrViewModel,
    private val watch: (ChannelListItem) -> Unit,
    private val toast: (String) -> Unit,
) {
    /** Each followed team's / league's games, with the channel showing it (from the guide) where one's found. */
    val games = mutableStateMapOf<String, List<Pair<SportGame, Pair<ChannelListItem, GuideProgram>?>>>()
    /** Games your guide doesn't reach yet: the channels showing them (the UK Sport on TV plugin, else TheSportsDB). */
    val tv = mutableStateMapOf<String, List<String>>()

    /** Fetches every followed team's / league's games and where they're on. */
    suspend fun load(follows: List<SportFollow>) = coroutineScope {
        val t0 = System.currentTimeMillis()
        follows.forEach { f ->
            f.logo?.let { ShortcutLogos.map["sport:${f.shortcutId}"] = ServiceLogo(it, square = true) }
            launch {
                val all = sportsVm.games(f)
                // On now, the next few (two months), the last few results; channels looked for in the coming week.
                val shown = all.filter { it.state == "in" } + all.filter { it.state == "pre" && it.start < t0 + 60 * DAY }.take(10) +
                    all.filter { it.state == "post" }.takeLast(5)
                val withCh = shown.map { g ->
                    g to (if (g.state != "post" && g.start < t0 + 7 * DAY) runCatching { guide.channelForGame(g) }.getOrNull() else null)
                }
                games[f.shortcutId] = withCh
                // Not in the guide yet (the next two months): who's showing it. (No answer yet, e.g. TheSportsDB busy:
                // left out, asked again next time.)
                withCh.filter { (g, ch) -> ch == null && g.state == "pre" && g.start < t0 + 60 * DAY && g.id !in tv }
                    .forEach { (g, _) -> guide.ukChannels(g)?.let { tv[g.id] = it } }
            }
        }
    }

    /**
     * A game's card (live, coming up, or a result) and what OK does on it; Sports' rows and a team's rows share it.
     * [f]: the team / league whose row it's in (hold OK: pin or unpin it).
     */
    fun card(g: SportGame, ch: Pair<ChannelListItem, GuideProgram>?, f: SportFollow? = null): OsdPoster {
        val tvRaw = tv[g.id].orEmpty()
        // (The UK listings say it isn't on UK TV, e.g. a Saturday 3pm game.)
        val notOnTv = tvRaw == listOf(GuideViewModel.NOT_ON_UK_TV)
        val on = if (notOnTv) emptyList() else tvRaw
        val started = ch != null && (g.state == "in" || ch.second.startTime <= System.currentTimeMillis())
        // Hold OK: a reminder or a recording (once it's in your guide), and pin / unpin the team or league.
        val options = listOfNotNull(
            if (ch != null && !started) CardOption("Remind me") {
                dvr.remind(ch.first, ch.second); toast("We'll remind you: ${g.title} on ${ch.first.name}")
            } else null,
            if (ch != null && !started) CardOption("Record") {
                dvr.record(ch.first, ch.second); toast("Recording ${g.title} on ${ch.first.name}")
            } else null,
            f?.let { fl ->
                CardOption(if (fl.pinned) "Unpin ${fl.name}" else "Pin ${fl.name}: its own shortcut") {
                    sportsVm.setPinned(fl, !fl.pinned); toast(if (fl.pinned) "${fl.name}: back under Sports" else "${fl.name}: pinned to your shortcuts")
                }
            },
        )
        return OsdPoster(
            key = "sg-${g.id}",
            title = if (g.score != null) "${g.home} ${g.score} ${g.away}" else g.title,
            subtitle = when (g.state) {
                "in" -> "Live" + (g.detail?.let { " $it" } ?: "") + "  ·  " + (ch?.first?.name ?: on.firstOrNull() ?: "not in your guide")
                "post" -> "Full time  ·  " + Dates.dayTime(g.start)
                else -> Dates.dayTime(g.start) + "  ·  " + (ch?.first?.name ?: on.joinToString(", ").ifEmpty { if (notOnTv) "not on UK TV" else "not in your guide yet" })
            },
            image = g.homeLogo, logo = true,
            emoji = if (g.homeLogo == null) sportsVm.league(g.league).emoji else null, tint = ShortcutColours.sports,
            options = options,
        ) {
            when {
                // On now (or its programme has started): watch it.
                started && ch != null -> watch(ch.first)
                ch != null -> toast("${g.title}: ${Dates.dayTime(g.start)} on ${ch.first.name}  ·  hold OK to be reminded or record it")
                g.state == "post" -> toast("${g.home} ${g.score ?: ""} ${g.away}")
                on.isNotEmpty() -> toast("${g.title}: on ${on.joinToString(", ")}, not in your TV guide yet (it lists a few days ahead)")
                notOnTv -> toast("${g.title}: not shown on UK TV (UK TV listings)")
                else -> toast("${g.title}: not in your TV guide yet (the guide lists a few days ahead)")
            }
        }
    }

    /** A pinned team's / league's own rows: On now, Next games, Results. */
    fun gamesRows(key: String, follows: List<SportFollow>): List<OsdRow> {
        val list = games[key].orEmpty()
        val fol = follows.firstOrNull { it.shortcutId == key }
        return listOf(
            OsdRow("On now", list.filter { it.first.state == "in" }.map { (g, c) -> card(g, c, fol) }),
            OsdRow("Next games", list.filter { it.first.state == "pre" }.map { (g, c) -> card(g, c, fol) }),
            OsdRow("Results", list.filter { it.first.state == "post" }.reversed().map { (g, c) -> card(g, c, fol) }),
        ).filter { it.items.isNotEmpty() }
    }

    /**
     * The Sports shortcut's rows (like a network's): what's on now across everything you follow, then a row per team
     * and league: its games on now and coming up, then its latest results.
     */
    fun rows(follows: List<SportFollow>): List<OsdRow> {
        val onNow = follows.flatMap { games[it.shortcutId].orEmpty() }.filter { it.first.state == "in" }.distinctBy { it.first.id }
        return (listOf(OsdRow("On now", onNow.map { (g, c) -> card(g, c) })) +
            follows.map { f ->
                val list = games[f.shortcutId].orEmpty()
                val ordered = list.filter { it.first.state != "post" } + list.filter { it.first.state == "post" }.reversed().take(3)
                OsdRow(f.name, ordered.map { (g, c) -> card(g, c, f) })
            }).filter { it.items.isNotEmpty() }
    }

    /** A followed team's / league's shortcut: OK watches its game on now, else says when the next one is. */
    fun shortcut(f: SportFollow): Pair<LiveShortcut, () -> Unit> =
        LiveShortcut(
            f.shortcutId, f.name, AppIcons.LiveTv,
            emoji = if (f.logo == null) sportsVm.league(f.league).emoji else null,
            logoName = if (f.logo != null) "sport:${f.shortcutId}" else null,
            colors = ShortcutColours.sports,
        ) to {
            val list = games[f.shortcutId].orEmpty()
            val live = list.firstOrNull { it.first.state == "in" && it.second != null }?.second
            if (live != null) watch(live.first)
            else {
                val next = list.firstOrNull { it.first.state == "pre" }?.first
                toast(next?.let { "Next: ${it.title}, " + Dates.dayTime(it.start) } ?: "No games found for ${f.name} yet")
            }
        }

    private companion object {
        const val DAY = 86_400_000L
    }
}
