package io.github.petebutty.viewarr.ui.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.sports.SportFollow
import io.github.petebutty.viewarr.data.sports.SportGame
import io.github.petebutty.viewarr.ui.dvr.DvrViewModel
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.player.CardOption
import io.github.petebutty.viewarr.ui.player.OsdPoster
import io.github.petebutty.viewarr.ui.player.SportColours
import io.github.petebutty.viewarr.ui.settings.SportsViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.UiOpts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Sports hub (Settings → Sports) on live TV: your teams' and leagues' games, which of your channels shows each
 * (from the guide; else the UK listings), and the cards and rows built from them.
 *
 * [watch]: play a channel full screen; [toast]: a short message.
 */
internal class SportsHub(
    internal val sportsVm: SportsViewModel,
    private val guide: GuideViewModel,
    internal val dvr: DvrViewModel,
    internal val watch: (ChannelListItem) -> Unit,
    internal val toast: (String) -> Unit,
) {
    /** Each followed team's / league's games, with the channel showing it (from the guide) where one's found. */
    val games = mutableStateMapOf<String, List<Pair<SportGame, Pair<ChannelListItem, GuideProgram>?>>>()
    /**
     * Goes up whenever [games] or [tv] actually change: the remember key for what's built from them. (They were copied
     * with toMap() and compared game by game on every redraw, so on every move along the Sports page.)
     */
    var version by mutableIntStateOf(0)
        private set
    private fun setGames(key: String, list: List<Pair<SportGame, Pair<ChannelListItem, GuideProgram>?>>) {
        if (games[key] == list) return
        games[key] = list
        version++
    }
    private fun setTv(id: String, names: List<String>) {
        if (tv[id] == names) return
        tv[id] = names
        version++
    }
    /** The game whose page is open (OK on a game), with its channel if found; null: none. */
    var openGame by mutableStateOf<Pair<SportGame, Pair<ChannelListItem, GuideProgram>?>?>(null)
    /** The channel showing the open game when the guide doesn't list it (GamePlacer: the listings', an event or club channel). */
    var openChannel by mutableStateOf<ChannelListItem?>(null)
    /** Why the open game page's channel is the one it is (the Sports page's reasoning, shown on the page). */
    var openWhy by mutableStateOf("")
    /** Settings → Sports → Game page on: OK on a game opens its page. */
    val gamePages: Boolean get() = AppColors.palette.opt(UiOpts.SPORTS_GAME_PAGE) == 0
    /** Puts focus back on the game the page was opened from (set by the Sports page). */
    var restoreFocus: (() -> Unit)? = null
    /**
     * Closes the game page with focus moved back to its game first: closing it with the focus still inside left a
     * moment with none, the menu's Music entry took it and the page switched to Music (Peter, 2026-11-16).
     */
    fun closeGame() {
        val r = runCatching { restoreFocus?.invoke() }
        Log.i("Sports", "game page closed, focus back on its game: ${r.isSuccess && restoreFocus != null}")
        openGame = null
    }

    /**
     * Reminders / recordings asked for before [g] was in the guide, and "record all of a team's games": done now that
     * its channel is known (each once).
     */
    fun resolvePending(g: SportGame, ch: Pair<ChannelListItem, GuideProgram>) {
        val gw = sportsVm.gameWatch
        if (g.state != "pre" || ch.second.startTime < System.currentTimeMillis()) return
        val remind = gw.wantsReminder(g.id)
        val team = gw.recordsTeam(g.home) || gw.recordsTeam(g.away)
        // Already recording in Dispatcharr (by hand, a series rule, or before): never a second recording.
        val record = (gw.wantsRecording(g.id) || (team && !gw.done(g.id))) && dvr.recordingFor(ch.first.id, ch.second) == null
        if (!remind && !record) { if (gw.wantsRecording(g.id)) gw.resolved(g.id); return }
        if (remind && dvr.reminderFor(ch.first.id, ch.second) == null) { dvr.remind(ch.first, ch.second); toast("Reminder set: ${g.title} on ${ch.first.name}") }
        if (record) { dvr.record(ch.first, ch.second); toast("Recording ${g.title} on ${ch.first.name}") }
        gw.resolved(g.id)
    }

    /**
     * Games your guide doesn't reach yet: the channels showing them (Sky's TV guide, the UK Sport on TV plugin, else
     * TheSportsDB: see GuideViewModel.ukMatch).
     */
    val tv = mutableStateMapOf<String, List<String>>()
    /** How surely each of those listings is the game, and which listing it was (the game page's "why"). */
    val tvMatch = HashMap<String, GuideViewModel.UkResult>()

    /** When each team / league was last fetched (opening Sports again within a few minutes uses what's there). */
    private val loadedAt = HashMap<String, Long>()

    /** Games already looked for in the guide / the listings (so going back to Sports carries on, not starts again). */
    private val lookedUp = HashSet<String>()
    /** When the guide was last fetched again for a game with no channel (step 1b). */
    private var lastFreshen = 0L
    private var running = false

    /**
     * Fetches every followed team's / league's games and where they're on. The games show as soon as they arrive;
     * their channels (a guide search each, and the UK listings) fill in one by one after.
     */
    suspend fun load(follows: List<SportFollow>) {
        if (running) return
        running = true
        try {
            val t0 = System.currentTimeMillis()
            // 1: every team's / league's games (small requests to ESPN), shown as soon as they're in.
            coroutineScope {
                follows.map { f ->
                    async {
                        if (t0 - (loadedAt[f.shortcutId] ?: 0L) < 5 * 60_000L && games.containsKey(f.shortcutId)) return@async
                        val all = runCatching { sportsVm.games(f) }.getOrDefault(emptyList())
                        // On now, the next few (two months), the last few results; one of each game. (20 coming up, not 10: a league's
                        // next 10 were mostly one Saturday's 3pm games, not on UK TV, and the televised ones fell off the row.)
                        val shown = (all.filter { it.state == "in" } + all.filter { it.state == "pre" && it.start < t0 + 60 * DAY }.take(20) +
                            all.filter { it.state == "post" }.takeLast(5)).distinctBy { it.id }
                        val before = games[f.shortcutId].orEmpty().associate { it.first.id to it.second }
                        setGames(f.shortcutId, shown.map { g -> g to before[g.id] })
                        loadedAt[f.shortcutId] = t0
                    }
                }.forEach { it.await() }
            }
            // 1b: a game on now (or within 3 hours) with no channel found, and the guide on this box older than half an
            // hour: event channels are named after the match only shortly before (the EPG & Sports Editor plugin), after
            // the box last fetched the guide. Fetched again (at most every half hour), and those games looked for again.
            val missing = follows.flatMap { games[it.shortcutId].orEmpty() }
                .filter { (g, ch) -> ch == null && (g.state == "in" || (g.state == "pre" && g.start < t0 + 3 * 3_600_000L)) }
                .map { it.first.id }.toSet()
            if (missing.isNotEmpty() && t0 - lastFreshen > 30 * 60_000L) {
                lastFreshen = t0
                guide.syncIfOlderThan(30 * 60_000L)
                lookedUp.removeAll(missing)
            }
            // Games already matched to a channel: any reminder / recording waiting for them.
            follows.flatMap { games[it.shortcutId].orEmpty() }.forEach { (g, ch) -> if (ch != null) resolvePending(g, ch) }
            // 2: where each is on, one game at a time with a pause between (a guide search each, and the UK listings, all
            // at once made a small box stop responding). Soonest first; games already looked for skipped.
            val todo = follows.flatMap { games[it.shortcutId].orEmpty() }
                .filter { (g, ch) -> ch == null && g.state != "post" && g.id !in lookedUp }
                .map { it.first }.distinctBy { it.id }.sortedBy { it.start }
            for (g in todo) {
                lookedUp += g.id
                if (g.start < t0 + 7 * DAY) {
                    val ch = runCatching { guide.channelForGame(g) }.getOrNull()
                    if (ch != null) {
                        resolvePending(g, ch)
                        games.keys.toList().forEach { k ->
                            if (games[k].orEmpty().any { it.first.id == g.id }) setGames(k, games[k].orEmpty().map { if (it.first.id == g.id) g to ch else it })
                        }
                        delay(150)
                        continue
                    }
                }
                // Not in the guide (yet): who's showing it (Sky's TV guide, the UK Sport on TV plugin, else TheSportsDB).
                if (g.state != "post" && g.start < t0 + 60 * DAY && g.id !in tv) {
                    // No answer yet (listings not read, TheSportsDB busy): looked for again next time, not never.
                    val m = runCatching { guide.ukMatch(g) }.getOrNull()
                    if (m != null) { tvMatch[g.id] = m; setTv(g.id, m.channels) } else lookedUp -= g.id
                }
                delay(150)
            }
        } finally {
            running = false
        }
    }

    private var placerFor: Pair<List<ChannelListItem>, GamePlacer>? = null

    /**
     * For Live now: games on now or within three hours, each on the channel the Sports page finds for it (only a sure
     * one), and every game known (finished ones too, whose channels are dead). Your teams' and leagues' games, plus
     * ESPN's headline games in any sport (looked up in your guide and the listings, as Sports does; cached there).
     */
    suspend fun liveGames(follows: List<SportFollow>, channels: List<ChannelListItem>): Pair<List<GuideViewModel.LiveGame>, List<SportGame>> {
        // (The channel matching off the screen's thread: thousands of channels.)
        val placer = placerFor?.takeIf { it.first == channels }?.second
            ?: withContext(Dispatchers.Default) { GamePlacer(channels) }.also { placerFor = channels to it }
        val now = System.currentTimeMillis()
        fun soon(g: SportGame) = g.state == "in" || (g.state == "pre" && g.start < now + 3 * 3_600_000L)
        val followed = follows.flatMap { games[it.shortcutId].orEmpty() }.distinctBy { it.first.id }
        val ids = followed.map { it.first.id }.toSet()
        val extra = sportsVm.headline().filter { it.id !in ids }.map { g ->
            if (!soon(g)) return@map g to null
            val ch = runCatching { guide.channelForGame(g) }.getOrNull()
            if (ch == null && g.id !in tv) runCatching { guide.ukMatch(g) }.getOrNull()?.let { tvMatch[g.id] = it; setTv(g.id, it.channels) }
            g to ch
        }
        val all = followed + extra
        val live = withContext(Dispatchers.Default) {
            all.filter { soon(it.first) }.mapNotNull { (g, ch) ->
                val c = placer.place(this@SportsHub, g, ch)
                c.channel?.takeIf { c.sure }?.let { GuideViewModel.LiveGame(g, it, ch?.second) }
            }
        }
        return live to all.map { it.first }
    }

    /** A reminder is set for [g] (on its channel around kick-off, or waiting for the guide to list it). */
    fun reminded(g: SportGame, channel: ChannelListItem?, reminders: List<io.github.petebutty.viewarr.data.dvr.Reminder>): Boolean =
        sportsVm.gameWatch.wantsReminder(g.id) || reminders.any { r -> (channel == null || r.channelId == channel.id) && kotlin.math.abs(r.start - g.start) <= 60 * 60_000L && r.end > g.start }

    /** Kick-off reminder on [channel] (a game with no guide listing: a listings channel, club stream…): set, or off again. */
    fun toggleKickOffReminder(g: SportGame, channel: ChannelListItem) {
        val r = dvr.reminderAt(channel.id, g.start)
        if (r != null) { dvr.forget(r.id); toast("Reminder cancelled") }
        else { dvr.remindAt(channel, g.start, g.start + 2 * 3_600_000L, g.title); toast("We'll remind you at ${Dates.time(g.start)}: ${g.title} on ${channel.name}") }
    }

    /**
     * A game's card (live, coming up, or a result) and what OK does on it; Sports' rows and a team's rows share it.
     * [f]: the team / league whose row it's in (hold OK: pin or unpin it). [known]: the channel the Sports page found
     * for it without the guide (the UK listings' channel, a club stream, an event channel).
     */
    fun card(g: SportGame, ch: Pair<ChannelListItem, GuideProgram>?, f: SportFollow? = null, known: ChannelListItem? = null): OsdPoster {
        val tvRaw = tv[g.id].orEmpty()
        // (The UK listings say it isn't on UK TV, e.g. a Saturday 3pm game.)
        val notOnTv = tvRaw == listOf(GuideViewModel.NOT_ON_UK_TV)
        val on = if (notOnTv) emptyList() else tvRaw
        val started = ch != null && (g.state == "in" || ch.second.startTime <= System.currentTimeMillis())
        // Hold OK: a reminder or a recording (once it's in your guide), and pin / unpin the team or league.
        val gw = sportsVm.gameWatch
        val pagesOn = gamePages
        val options = listOfNotNull(
            if (pagesOn) CardOption("Game info") { openGame = g to ch } else null,
            // Not in the guide yet: kept, and done once the guide lists it.
            // A channel known without the guide: a reminder at kick-off on it, now. (Waiting for the guide never came for
            // games it doesn't list that way: club streams, listings channels. Peter, 2026-11-16.)
            if (ch == null && g.state == "pre" && known != null) CardOption(if (dvr.reminderAt(known.id, g.start) != null) "Cancel reminder" else "Remind me at kick-off") {
                toggleKickOffReminder(g, known)
            } else null,
            if (ch == null && g.state == "pre" && known == null) CardOption(if (gw.wantsReminder(g.id)) "Cancel reminder" else "Remind me when it's on") {
                val want = !gw.wantsReminder(g.id); gw.setReminder(g.id, want)
                toast(if (want) "We'll remind you of ${g.title} once it's in your TV guide" else "Reminder cancelled")
            } else null,
            if (ch == null && g.state == "pre") CardOption(if (gw.wantsRecording(g.id)) "Don't record" else "Record when it's in the guide") {
                val want = !gw.wantsRecording(g.id); gw.setRecording(g.id, want)
                toast(if (want) "${g.title} will be recorded once it's in your TV guide" else "Won't record ${g.title}")
            } else null,
            // (Shows whether it's set, and takes it off again: Remind me said nothing it had done. Peter, 2026-11-18.)
            if (ch != null && !started) CardOption(if (dvr.reminderFor(ch.first.id, ch.second) != null) "✓ Reminder set: cancel it" else "Remind me") {
                val r = dvr.reminderFor(ch.first.id, ch.second)
                if (r != null) { dvr.forget(r.id); toast("Reminder cancelled: ${g.title}") }
                else { dvr.remind(ch.first, ch.second); toast("🔔 Reminder set: ${g.title} on ${ch.first.name}") }
            } else null,
            if (ch != null && !started) CardOption("Record") {
                dvr.record(ch.first, ch.second); gw.markDone(g.id); toast("Recording ${g.title} on ${ch.first.name}")
            } else null,
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
            emoji = if (g.homeLogo == null) sportsVm.league(g.league).emoji else null, tint = SportColours.sports,
            options = options,
        ) {
            when {
                // On now (or its programme has started): watch it.
                started && ch != null -> watch(ch.first)
                // Settings → Sports → Game page: the game's page (line-ups, form, remind / record).
                pagesOn -> openGame = g to ch
                ch != null -> toast("${g.title}: ${Dates.dayTime(g.start)} on ${ch.first.name}  ·  Hold OK: remind or record")
                g.state == "post" -> toast("${g.home} ${g.score ?: ""} ${g.away}")
                on.isNotEmpty() -> toast("${g.title}: on ${on.joinToString(", ")}, not in your TV guide yet (it lists a few days ahead)")
                notOnTv -> toast("${g.title}: not shown on UK TV (UK TV listings)")
                else -> toast("${g.title}: not in your TV guide yet (the guide lists a few days ahead)")
            }
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
