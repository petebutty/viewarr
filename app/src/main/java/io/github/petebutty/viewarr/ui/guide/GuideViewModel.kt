package io.github.petebutty.viewarr.ui.guide

import kotlinx.coroutines.flow.onEach
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.dvr.PluginsRepository
import io.github.petebutty.viewarr.data.guide.LiveSports
import io.github.petebutty.viewarr.data.sports.LiveNowPicks
import io.github.petebutty.viewarr.data.guide.SportsEvents
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GroupWithCount
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.media.ProgramArt
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.data.sports.GameMatch
import io.github.petebutty.viewarr.data.sports.SkySchedule
import io.github.petebutty.viewarr.data.sports.SportGame
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.UiOpts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** row = selected channel index; focusTime = time anchor (classic IPTV apps keeps the column time when moving up/down). */
data class GuideCursor(val row: Int, val focusTime: Long, val windowStart: Long)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val repo: GuideRepository,
    private val sessions: SessionStore,
    private val programArt: ProgramArt,
    private val plugins: PluginsRepository,
    private val sports: SportsEvents,
    private val sky: SkySchedule,
    private val sportsRepo: io.github.petebutty.viewarr.data.sports.SportsRepository,
    /** Settings → Live TV → Live now: the teams, leagues and sports it's limited to (empty: everything). */
    private val liveNowPicks: io.github.petebutty.viewarr.data.sports.LiveNowPicks,
    /** Settings → Live TV → Home screen rows: channel groups as rows on the TV's home screen. */
    private val homeRows: io.github.petebutty.viewarr.data.media.HomeRows,
    private val theme: io.github.petebutty.viewarr.data.settings.ThemeStore,
) : ViewModel() {
    /** Every service's "Just added" rows on the home screen at once, in service order (viewarr://homerows?add=justadded). */
    fun addJustAddedRows() {
        viewModelScope.launch {
            io.github.petebutty.viewarr.data.media.HomeRows.justAddedParts().forEach { theme.saveTitlePart(it, false) }
            kotlinx.coroutines.delay(1_500)
            homeRows.refresh()
        }
    }

    /** A Home screen rows switch was changed: the rows follow (once the setting has been saved). */
    fun refreshHomeRows() { viewModelScope.launch { kotlinx.coroutines.delay(1_500); homeRows.refresh() } }

    init {
        // The home screen's channel cards: what's on now, kept fresh while Viewarr runs (nothing to do with no row on).
        viewModelScope.launch {
            kotlinx.coroutines.delay(30_000)
            while (true) {
                if (io.github.petebutty.viewarr.data.media.HomeRows.anyOn()) homeRows.refresh()
                kotlinx.coroutines.delay(15 * 60_000L)
            }
        }
    }

    /** One event for Live now: its channel and guide entry, its tidied name, sport and picture (if found). */
    data class LiveEvent(
        val channel: ChannelListItem,
        val program: GuideProgram,
        val title: String,
        val sport: SportsEvents.Sport?,
        val picture: String?,
    )

    /** A game from your Sports schedule on now or soon, on the channel the Sports page found for it (its guide entry, if any). */
    data class LiveGame(val game: SportGame, val channel: ChannelListItem, val program: GuideProgram?)

    /** Live now (the Live now group in the guide): event channels with something on, and what's starting soon. */
    data class LiveEvents(
        val onNow: List<LiveEvent>,
        val soon: List<LiveEvent>,
    )

    // The event / sports groups: the ones Event Channel Managarr (a Dispatcharr plugin) looks after, read once. Without
    // it (or for users Dispatcharr doesn't show plugins to), groups whose names look like sport or events.
    private var eventGroupNames: Set<String>? = null
    /**
     * Settings → Live TV → Live now groups (2026-11-17: the user picks the groups searched): a group switched on
     * or off there; untouched, the event feeds' groups (Event Channel Managarr's) and sporty-named ones are searched.
     * Kept as palette title parts: "lngrp:+<name>" switched on, "lngrp:-<name>" switched off.
     */
    fun liveGroupOn(name: String): Boolean {
        val k = name.trim().lowercase()
        val p = io.github.petebutty.viewarr.ui.theme.AppColors.palette
        return when {
            !p.shows("lngrp:+$k") -> true
            !p.shows("lngrp:-$k") -> false
            else -> k in eventGroupNames.orEmpty() || eventWords.containsMatchIn(name)
        }
    }
    /**
     * The last Live now worked out from the server's list, and what it was worked out from: used again while none of
     * that has changed (the list's content, the channels, Settings, the Live now picks, the event groups) and nothing
     * in it has started or ended since. (It was worked out again every minute, a guide lookup per item, the same answer
     * each time: ~13% of a core on a Shield with the guide sitting still. 2026-11-22.)
     */
    private class ServerLive(
        val hash: Int, val all: List<ChannelListItem>, val palette: Any, val picks: List<io.github.petebutty.viewarr.data.sports.LiveNowPick>,
        val groups: Set<String>?, val until: Long, val result: LiveEvents?, val uuids: List<String>?,
    )
    @Volatile private var serverLive: ServerLive? = null

    /** Live now from the server's list ([ServerSports]): null when none of its channels are here (another source). */
    private suspend fun fromServer(srv: io.github.petebutty.viewarr.data.sync.ServerSports.Data, all: List<ChannelListItem>): LiveEvents? {
        val now = System.currentTimeMillis()
        val picks = runCatching { liveNowPicks.current() }.getOrDefault(emptyList())
        val palette = io.github.petebutty.viewarr.ui.theme.AppColors.palette
        serverLive?.takeIf {
            it.hash == srv.hash && it.all === all && it.palette === palette && it.picks == picks && it.groups == eventGroupNames && now < it.until
        }?.let { cached ->
            // (Set again in case the TV's own Live now replaced it meanwhile; the same list changes nothing.)
            cached.uuids?.let { _liveUuids.value = it }
            return cached.result
        }
        val byUuid = all.associateBy { it.uuid }
        val groupOn = HashMap<String, Boolean>()
        // Why each of the server's items was left out here (logged: "Live now from the server"), so a missing game
        // can be explained: its channel isn't on this TV (Choose groups), its group is off, it's over, its sport is off.
        val dropped = HashMap<String, MutableList<String>>()
        fun drop(why: String, what: String) { dropped.getOrPut(why) { mutableListOf() } += what }
        val rankOf = HashMap<String, Int>()
        // First the items whose channel is here, in a group that's on, and not over…
        val kept = srv.live.mapNotNull { l ->
            val c = byUuid[l.uuid] ?: run { drop("channel not on this TV", l.title); return@mapNotNull null }
            if (c.groupName?.let { g -> groupOn.getOrPut(g) { liveGroupOn(g) } } == false) { drop("group off (${c.groupName})", l.title); return@mapNotNull null }
            if (l.end in 1..now) { drop("over", l.title); return@mapNotNull null }
            l to c
        }
        // …then the guide's own entries for all of them in one read (one read per item was dozens a minute).
        val guideRows = if (kept.isEmpty()) emptyMap() else runCatching {
            repo.programsForChannels(kept.map { it.second.id }.distinct(), kept.minOf { it.first.start }, kept.maxOf { it.first.start } + 60_000)
        }.getOrNull().orEmpty().groupBy { it.channelId }
        val events = kept.mapNotNull { (l, c) ->
            val g = l.game?.let { srv.games[it] }
            // The guide's own entry for it (so the group's rows and the info bar line up), else one made from the list.
            val p = guideRows[c.id].orEmpty()
                .firstOrNull { it.startTime <= l.start + 60_000 && it.endTime > l.start }
                ?: GuideProgram(
                    channelId = c.id, rowId = -1L - (l.uuid.hashCode().toLong() and 0x7fffffff), title = l.title, subTitle = null,
                    packedDescription = null, startTime = l.start, endTime = l.end.takeIf { it > l.start } ?: (l.start + 3 * 3_600_000L),
                    season = null, episode = null, isNew = false, isLive = true, isPremiere = false,
                )
            val sport = sports.sport("${l.sport.orEmpty()} ${l.title} ${c.name} ${c.groupName.orEmpty()}")
            // Settings → Live TV → Live now: only the teams, leagues and sports picked there (none picked: everything).
            val rank = if (picks.isEmpty()) 0 else LiveNowPicks.rank(picks, g?.leaguePath, g?.home, g?.away, l.title, sport?.name)
            if (rank >= picks.size && picks.isNotEmpty()) { drop("not in your Live now list", l.title); return@mapNotNull null }
            rankOf[c.uuid] = minOf(rankOf[c.uuid] ?: Int.MAX_VALUE, rank)
            LiveEvent(c, p, l.score?.let { "${l.title} ($it)" } ?: l.title, sport, l.picture ?: p.image)
        }
        // Good until the next item ends or the next one starts (on now / soon / over change then), and at most ten
        // minutes, whatever else.
        val until = minOf(
            now + 10 * 60_000L,
            srv.live.minOfOrNull { l -> if (l.end > now) l.end else Long.MAX_VALUE } ?: Long.MAX_VALUE,
            events.minOfOrNull { e -> if (e.program.startTime > now) e.program.startTime else Long.MAX_VALUE } ?: Long.MAX_VALUE,
        )
        if (events.isEmpty() && srv.live.isNotEmpty() && srv.live.none { byUuid.containsKey(it.uuid) }) {
            serverLive = ServerLive(srv.hash, all, palette, picks, eventGroupNames, until, null, null)
            return null
        }
        val wanted = events
        // The server's list stands: it has already worked out what's on and where (2026-11-17: "the app knows
        // the games that are on from the plugin"), so this TV's copy of the guide isn't asked (tonight's NBA games were
        // left out because it hadn't caught up). Only a team's own channel with no listing anywhere stays cards only
        // (an empty row, as asked before); guide entries the server found, Sky / UK listings and event channels all go in.
        val guided = srv.live.filter { l -> l.game?.let { srv.games[it]?.source } != "club" }.map { it.uuid }.toSet()
        events.filter { it.program.rowId < 0 && it.channel.uuid !in guided }.forEach { drop("no guide entry on this TV (cards only)", it.title) }
        android.util.Log.i("LiveNow", "from the server: ${srv.live.size} items, ${wanted.size} kept" +
            dropped.entries.joinToString("") { (why, t) -> "; $why: ${t.size} (${t.take(3).joinToString(", ") { it.take(30) }})" })
        val onNow = wanted.filter { it.program.startTime <= now }.take(60)
        val soon = wanted.filter { it.program.startTime > now }.take(30)
        // (In the Live now list's order, then by start time.)
        // (A StateFlow: the same list as before emits nothing, so the groups and the Live now rows stay as they are.)
        val uuids = (onNow + soon).filter { it.program.rowId >= 0 || it.channel.uuid in guided }
            .sortedWith(compareBy({ rankOf[it.channel.uuid] ?: 0 }, { it.program.startTime })).map { it.channel.uuid }.distinct()
        _liveUuids.value = uuids
        return LiveEvents(onNow, soon).also { serverLive = ServerLive(srv.hash, all, palette, picks, eventGroupNames, until, it, uuids) }
    }

    /** The plugin's groups, looked up once (for Settings' Live now groups to show the right starting ticks). */
    suspend fun loadLiveGroupDefaults() { runCatching { eventGroups() } }
    /** The channel groups for Settings' Live now groups list (real ones, not Live now or Favourites). */
    val realGroups get() = groups.value.filter { it.id >= 0 }

    private suspend fun eventGroups(): Set<String> {
        eventGroupNames?.let { return it }
        // (Only a real answer is kept: a failed lookup is asked again next time, not stuck on the fallback all session.)
        val installed = runCatching { plugins.installed() }.getOrNull() ?: return emptySet()
        val fromPlugin = installed
            .firstOrNull { it.key == "event_channel_managarr" && it.enabled }
            ?.settings?.get("channel_groups")?.let { (it as? JsonPrimitive)?.content }
            ?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        return fromPlugin.also { eventGroupNames = it }
    }
    /**
     * A guide entry that only repeats its channel's name ("NBA B 09:", "UEFA 05: 14:45 Belgium vs Italy" all day long:
     * a filler source such as EPG & Sports Editor's "Fill") or an empty slot's label ("F1 TV 70:"): not an event on now.
     * (2,120 of 2,238 event channels "on now" were these, mostly empty or long finished. 2026-11-17.)
     */
    private fun fillerFor(channelBare: String, p: GuideProgram): Boolean {
        val t = p.title.trim()
        if (t.endsWith(":") || bare(t) == channelBare) return true
        // A stream's own name standing in for a programme ("US NFL GAME (UHD/4K)" all afternoon: a filler source copies
        // the provider's stream names, not the channel's): its quality mark gives it away, as does an all-capitals name
        // spanning 5 hours or more ("FORMULA 1 ALB-ALBON-WILLIAMS", 6 h; not "WWE RAW", 3 h). Real listings are mixed case. (2026-11-17.)
        if (QUALITY_MARK.containsMatchIn(t)) return true
        val letters = t.filter { it.isLetter() }
        return letters.length >= 4 && letters == letters.uppercase() && p.endTime - p.startTime >= 5 * 3_600_000L
    }
    private val QUALITY_MARK = Regex("(?i)\\((?:[^)]*\\b)?(uhd|4k|fhd|hd|sd|hevc|raw)\\b[^)]*\\)|\\b(uhd|fhd)\\b|\\|")
    /** An event's slot, not a filler block: under 9 hours (golf, cricket and a race weekend's sessions fit). */
    private fun realSlot(p: GuideProgram) = p.endTime - p.startTime in 1..9 * 3_600_000L
    /** The guide says it's live: its flag, or "Live" in the title, subtitle or the synopsis's start. */
    private fun saysLive(p: GuideProgram) = p.isLive || LIVE_MARK.containsMatchIn(p.title) || p.subTitle?.let { LIVE_MARK.containsMatchIn(it) } == true ||
        p.description?.take(80)?.let { LIVE_START.containsMatchIn(it) } == true
    private val LIVE_MARK = Regex("(?i)\\blive\\b|ᴸᶦᵛᵉ|🔴")
    private val LIVE_START = Regex("(?i)^\\W*live\\b|\\blive (coverage|action|from)\\b")
    /** A name for [fillerFor]'s comparison: lower case, no date ending ("… 17-11"), letters and digits only. */
    private fun bare(t: String) = t.lowercase().replace(DATE_SUFFIX, "").replace(NON_WORD, "")
    // (Built once: compiled for each of thousands of guide entries, Live now's filling held up the remote.)
    private val DATE_SUFFIX = Regex("\\s+\\d{1,2}-\\d{1,2}$")
    private val NON_WORD = Regex("[^\\p{L}0-9]+")

    /** A provider's numbered event feed's name: "UEFA 05:", "NFL B 13:", "AMAZON UK EVENT 3", "PPV 12". */
    private val EVENT_FEED = Regex("\\d{1,3}\\s*:|\\bevents?\\s*\\d+|\\bppv\\s*\\d+", RegexOption.IGNORE_CASE)
    private val eventWords = Regex("\\b(sports?|events?|ppv|pay per view|box office|football|soccer|rugby|ufc|boxing|wwe|nfl|nba|nhl|mlb|f1|motogp|cricket|golf|tennis|darts|racing|premier league|efl|epl|spfl|uefa|matchroom|dazn)\\b", RegexOption.IGNORE_CASE)

    /**
     * What's on the event channels now, and starting in the next three hours (most recent / soonest first).
     * [games]: your Sports schedule's games on now or within three hours, each on its channel (shown first, with the
     * score); [known]: every game it knows, finished ones too (a guide entry for a finished game is a dead channel, and
     * one for a game in [games] is already shown). (Live now trusted the guide alone and filled with finished events and
     * empty slots. 2026-11-17.)
     */
    suspend fun liveEvents(games: List<LiveGame> = emptyList(), known: List<SportGame> = emptyList()): LiveEvents =
        // Off the screen's thread: thousands of guide entries sorted and matched (on it, the remote stalled while Live
        // now filled. 2026-11-17).
        withContext(Dispatchers.Default) { liveEventsNow(games, known) }

    private suspend fun liveEventsNow(games: List<LiveGame>, known: List<SportGame>): LiveEvents {
        // Switched off in the Viewarr plugin: Live now is off for the house (no Live now group at all).
        if (io.github.petebutty.viewarr.data.sync.ServerSports.offInPlugin(io.github.petebutty.viewarr.data.sync.ServerSports.F_LIVE)) return LiveEvents(emptyList(), emptyList())
        val named = eventGroups()
        val all = allChannelsList()
        // Worked out on the server for the whole house (the Viewarr plugin on Dispatcharr), while it's recent: this TV
        // only applies its own choices (Live now groups, sports), with no guide scanning of its own.
        io.github.petebutty.viewarr.data.sync.ServerSports.fresh?.let { srv -> fromServer(srv, all)?.let { return it } }
        // Every event channel (not the first 400 by number: a "4K / UHD" group early in the numbers filled the list, and
        // the groups after it, UEFA, NFL, UFC…, were never looked at. 2026-11-17).
        // The groups chosen in Settings → Live TV → Live now groups (untouched: the event feeds' groups and every
        // sports-looking one; Sky Sports' Grand Slam of Darts was missed while only the plugin's were searched).
        val groupOn = HashMap<String, Boolean>()
        val chans = all.filter { c -> c.groupName?.let { g -> groupOn.getOrPut(g) { liveGroupOn(g) } } == true }
        val now = System.currentTimeMillis()
        val onNow = ArrayList<Pair<ChannelListItem, GuideProgram>>()
        val soon = ArrayList<Pair<ChannelListItem, GuideProgram>>()
        // Placeholders ("Ended: …", "No event today") left out: only real events. Every event channel's next three
        // hours in one read (one channel at a time was hundreds of reads a minute).
        val byId = chans.associateBy { it.id }
        val all3h = runCatching { repo.programsForChannels(chans.map { it.id }, now - 1, now + 3 * 3_600_000L) }.getOrNull().orEmpty()
        for ((id, ps0) in all3h.groupBy { it.channelId }) {
            val c = byId[id] ?: continue
            // Real events only: on a provider's event feed ("UEFA 05:", "AMAZON UK EVENT 3") any programme that isn't a
            // placeholder; on an ordinary channel in those groups (US CBS (UHD/4K), Sky Sports+…) only one that's a
            // sport by its title (a match, a race, a fight), not its everyday shows.
            val feed = EVENT_FEED.containsMatchIn(c.name)
            val channelBare = bare(c.name)
            // Real guide data that says it's live (2026-11-17: "look in the EPG for live and actual EPG data"):
            // the live flag, or "Live" in its title / subtitle / synopsis ("Live PL: …", "MNF … ᴸᶦᵛᵉ"). A feed's or
            // channel's name standing in, placeholders ("No Fixtures Today") and all-day blocks never count. Games your
            // Sports schedule knows come in from it on their own (below).
            val ps = ps0.filter { p -> sports.clean(p.title) != null && !fillerFor(channelBare, p) && realSlot(p) && saysLive(p) && (feed || sports.sport(p.title) != null || p.isLive) }
            ps.firstOrNull { it.startTime <= now && it.endTime > now }?.let { onNow += c to it }
            // An event feed only at its event's time: from 30 minutes before its live entry (by the guide: the time in a
            // feed's name can be another time zone, "14:45" for a 19:45 kick-off, and showed them all afternoon). Other
            // channels: anything live in the next three hours. (2026-11-17.)
            ps.firstOrNull { it.startTime > now && (!feed || it.startTime - now <= 30 * 60_000L) }?.let { soon += c to it }
        }
        // The same event on several channels (feeds, backups): once, on the first. A game your Sports schedule knows: left
        // out here (finished: a dead channel; on now or soon: shown from the schedule instead).
        fun scheduled(p: GuideProgram) = known.isNotEmpty() && (" " + GameMatch.norm(p.title) + " ").let { t -> known.any { GameMatch.normTextIsGame(t, it) == GameMatch.SURE } }
        fun distinct(l: List<Pair<ChannelListItem, GuideProgram>>) = l.filter { !scheduled(it.second) }
            .distinctBy { (_, p) -> (sports.clean(p.title) ?: p.title).trim().lowercase() + "|" + p.startTime }
        val nowPairs = distinct(onNow.sortedByDescending { it.second.startTime }).take(60)
        val soonPairs = distinct(soon.sortedBy { it.second.startTime }).take(30)
        // The schedule's games: live ones (score and minute) and the next three hours' kick-offs, once each.
        fun fromSchedule(l: List<LiveGame>) = l.distinctBy { it.game.id }.map { lg ->
            val g = lg.game
            val p = lg.program ?: GuideProgram(
                channelId = lg.channel.id, rowId = -1L - (g.id.hashCode().toLong() and 0x7fffffff), title = g.title, subTitle = g.leagueName,
                packedDescription = null, startTime = g.start, endTime = g.start + 3 * 3_600_000L, season = null, episode = null,
                isNew = false, isLive = true, isPremiere = false,
            )
            val title = if (g.score != null && !g.isEvent) "${g.home} ${g.score} ${g.away}" else g.title
            LiveEvent(lg.channel, p, title, sports.sport("${g.leagueName} ${g.league}"), g.homeLogo)
        }
        // Each one's sport and picture: the guide's own picture, else the match's from TheSportsDB (a few at a time).
        val gate = Semaphore(6)
        suspend fun events(l: List<Pair<ChannelListItem, GuideProgram>>) = coroutineScope {
            l.mapIndexed { i, (c, p) ->
                async {
                    val title = sports.clean(p.title) ?: p.title
                    // (Pictures from TheSportsDB for the first dozen only: it allows very few requests.)
                    val pic = p.image ?: if (i < 12) gate.withPermit { runCatching { sports.picture(title) }.getOrNull() } else null
                    LiveEvent(c, p, title, sports.sport("$title ${c.name} ${c.groupName.orEmpty()}"), pic)
                }
            }.map { it.await() }
        }
        // Settings → Live TV → Live now: only the teams, leagues and sports picked there (none picked: everything).
        val picks = runCatching { liveNowPicks.current() }.getOrDefault(emptyList())
        fun pickedGames(l: List<LiveGame>) = l.filter { lg -> LiveNowPicks.matches(picks, lg.game.league, lg.game.home, lg.game.away.ifBlank { null }, lg.game.title, LiveSports.ofLeague(lg.game.league)) }
        fun wanted(l: List<LiveEvent>) = l.filter { e -> LiveNowPicks.matches(picks, null, null, null, e.title, e.sport?.name) }
        val onNowList = (fromSchedule(pickedGames(games.filter { it.game.state == "in" })) + wanted(events(nowPairs))).take(60)
        val soonList = (fromSchedule(pickedGames(games.filter { it.game.state == "pre" && it.game.start in now..now + 3 * 3_600_000L }.sortedBy { it.game.start })) + wanted(events(soonPairs))).take(30)
        // The Live now group in the guide: only channels whose guide has the event (a team or event channel found from
        // the schedule often has no guide at all, an empty row there. 2026-11-17).
        // In time order (2026-11-17): what started first at the top, then what's coming up, soonest first.
        // (In the Live now list's order, then by start time.)
        _liveUuids.value = (onNowList + soonList).filter { it.program.rowId >= 0 }
            .sortedWith(compareBy({ if (picks.isEmpty()) 0 else LiveNowPicks.rank(picks, null, null, null, it.title, it.sport?.name) }, { it.program.startTime }))
            .map { it.channel.uuid }.distinct()
        return LiveEvents(
            onNow = onNowList,
            soon = soonList,
        )
    }
    /** The highlighted programme's artwork (TMDB), or null to show the channel logo. */
    suspend fun artFor(title: String): String? = programArt.imageFor(title)

    /** The programme's title logo from TMDB (same close-name match as its picture), or null. */

    /** The programme's own picture from Dispatcharr (its guide grid sends none), or null. */
    suspend fun pictureFor(p: GuideProgram): String? = sessions.session.first()?.let { repo.lookupImage(it, p) }

    val sync = repo.sync
    /**
     * The Live now group ([LIVE_NOW]): the channels with something live or starting soon (uuids, from [liveEvents]: the
     * Sports schedule, ESPN and the real guide events; filler and finished ones left out). The menu's Live now opens the
     * guide on it. (It opened a whole channel group, empty event slots and all. 2026-11-17.)
     */
    private val _liveUuids = MutableStateFlow<List<String>>(emptyList())
    // In your order (hold OK on a group in the menu → move); groups not placed yet keep the guide's order after them.
    // (📡 Live now is one of the groups, while anything's on: moved, hidden and shown again like the others, your
    // choice. 2026-11-17. Out of the box it comes first.)
    // (The database's group list re-sent with nothing changed, e.g. after another table's write: nothing to redo.)
    val groups = combine(repo.observeGroups().distinctUntilChanged(), repo.groupOrder, _liveUuids) { g0, order, live ->
        val g = (if (live.isEmpty()) emptyList() else listOf(GroupWithCount(LIVE_NOW, LIVE_NAME, live.size))) + g0
        if (order.isEmpty()) g else g.withIndex().sortedBy { (i, x) ->
            order.indexOf(x.name).let { if (it >= 0) it else if (x.id == LIVE_NOW) -1 else order.size + i }
        }.map { it.value }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The group to show [ch] in when it isn't in the one on screen: All channels, or (with All channels hidden) its own
     * group. (Switching to All channels while it was hidden was undone at once by the home screen, so typed numbers,
     * search and reminders went nowhere.)
     */
    private fun groupToShow(ch: ChannelListItem): Int? =
        if (AppColors.palette.hideAllChannels) groups.value.firstOrNull { it.name == ch.groupName }?.id else null

    /** Groups hidden from the channel groups list (their channels stay). */
    val menuHiddenGroups = repo.menuHiddenGroups
    fun setGroupMenuHidden(name: String, hidden: Boolean) {
        repo.setGroupMenuHidden(name, hidden)
        // The group on show hidden: back to all channels.
        if (hidden && groups.value.firstOrNull { it.id == _groupId.value }?.name == name) selectGroup(null)
    }

    /** Moves a channel group one place up ([dir] -1) or down (1) in the menu. */
    fun moveGroup(id: Int, dir: Int) {
        // From the order as just changed (not the shown list, a moment behind: a quick second press then moved it
        // back, so the group flicked about).
        val order = repo.groupOrder.value
        val shown = groups.value
        val sorted = if (order.isEmpty()) shown else shown.withIndex().sortedBy { (i, g) -> order.indexOf(g.name).let { if (it < 0) order.size + i else it } }.map { it.value }
        val names = sorted.map { it.name }.toMutableList()
        // Past groups hidden from the list (moving over one did nothing you could see).
        val hiddenNow = repo.menuHiddenGroups.value
        val i = sorted.indexOfFirst { it.id == id }
        var j = i + dir
        while (j in sorted.indices && sorted[j].name in hiddenNow) j += dir
        if (i < 0 || j !in names.indices) return
        Collections.swap(names, i, j)
        repo.setGroupOrder(names)
    }

    private val _groupId = MutableStateFlow<Int?>(null)
    val groupId = _groupId.asStateFlow()

    /** Favourite channels (uuids); the ★ Favourites group is [FAVOURITES]. */
    val favourites = repo.favourites
    fun toggleFavourite(ch: ChannelListItem): Boolean = repo.toggleFavourite(ch.uuid)

    // Guide time offset (see GuideRepository): the source's, and a channel's own (by its guide).
    val shiftVersion = repo.shiftVersion
    fun sourceShiftMin(): Int = repo.sourceShiftMin()
    // (Slides the saved programmes: no guide download. 2026-11-17.)
    fun setSourceShiftMin(min: Int) { viewModelScope.launch { repo.setSourceShiftMin(min) } }
    fun channelShiftMin(ch: ChannelListItem): Int = repo.channelShiftMin(ch.guideKey)
    fun setChannelShiftMin(ch: ChannelListItem, min: Int) { viewModelScope.launch { repo.setChannelShiftMin(ch.guideKey, min) } }

    /** A list-made group's channels (Favourites, Live now), else null (a real group). */
    private val listGroups = combine(repo.favourites, _liveUuids) { f, l -> f.toList() to l }
    private fun listFor(g: Int?, lists: Pair<List<String>, List<String>>) = when (g) { FAVOURITES -> lists.first; LIVE_NOW -> lists.second; else -> null }

    /**
     * The group on show and its own list (Favourites, Live now; null for a real group). Live now or Favourites changing
     * while another group is on show leaves this alone: the channels and programmes queries used to start again for
     * it all the same.
     */
    private val shownGroup = combine(_groupId, listGroups) { g, l -> g to listFor(g, l) }.distinctUntilChanged()

    val channels: StateFlow<List<ChannelListItem>> = shownGroup
        // (Live now in its own order: on now first, then starting soon, soonest first. Favourites: channel order.)
        // (Live now keeps an event channel the plugin or the guide put a game on, listed or not; see GuideRepository's
        // hidden event channels.)
        .flatMapLatest { (g, inList) -> inList?.let { repo.observeChannelsIn(it, ordered = g == LIVE_NOW, everyChannel = g == LIVE_NOW) } ?: repo.observeChannels(g) }
        // (The channel list built off the main thread: ~1 s of it in 12 s while the guide was being refreshed.)
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now = _now.asStateFlow()

    private val _cursor = MutableStateFlow(initialCursor(_now.value))
    val cursor = _cursor.asStateFlow()

    val programs: StateFlow<Map<Int, List<GuideProgram>>> = combine(
        shownGroup,
        _cursor.map { it.row / BLOCK }.distinctUntilChanged(),
        _cursor.map { it.windowStart }.distinctUntilChanged(),
        // (A longer timeline in Settings: the programmes for the whole of it.)
        snapshotFlow { VISIBLE_MS }.distinctUntilChanged(),
    ) { shown, block, ws, _ -> Triple(shown, block, ws) }
        .flatMapLatest { (shown, block, ws) ->
            val g = shown.first; val inList = shown.second
            (if (inList != null) repo.observeProgramBlockIn(inList, blockOffset(block), BLOCK * 3, ws - BUFFER_MS, ws + VISIBLE_MS + BUFFER_MS, ordered = g == LIVE_NOW, everyChannel = g == LIVE_NOW)
            else repo.observeProgramBlock(
                groupId = g,
                offset = blockOffset(block),
                count = BLOCK * 3,
                start = ws - BUFFER_MS,
                end = ws + VISIBLE_MS + BUFFER_MS,
            )).map { rows -> rows.groupBy { it.channelId } }
        }
        // A screenful's pictures fetched together, just behind it (Stage 4: they used to come one by one on highlight).
        .onEach { block -> prefetchPictures(block) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var pictureJob: kotlinx.coroutines.Job? = null
    private fun prefetchPictures(block: Map<Int, List<GuideProgram>>) {
        if (block.isEmpty() || !io.github.petebutty.viewarr.ui.theme.AppColors.palette.guideArt) return
        pictureJob?.cancel()
        pictureJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(400) // (still moving fast: wait until the guide settles on a screen)
            io.github.petebutty.viewarr.ui.common.AppStart.after(6_000) // (not while the app is still opening)
            val keys = channels.value.associate { it.id to it.guideKey }
            val now = System.currentTimeMillis()
            val items = block.flatMap { (cid, ps) ->
                val key = keys[cid] ?: return@flatMap emptyList()
                // (What's on now and the next few: the info panel's picture and the rows you'll move along.)
                ps.filter { it.endTime > now - 3_600_000L }.take(6).map { Triple(it.rowId, key, it.startTime) }
            }
            val s = sessions.session.first() ?: return@launch
            runCatching { repo.prefetchImages(s, items) }
        }
    }

    private val fetchedPast = HashSet<String>()

    init {
        // A shorter list (the last favourite removed, a smaller group): the highlight stays on its last channel rather
        // than past the end (nothing highlighted, a blank info panel).
        viewModelScope.launch {
            channels.collect { list -> if (list.isNotEmpty() && _cursor.value.row > list.lastIndex) _cursor.update { it.copy(row = list.lastIndex) } }
        }
        viewModelScope.launch {
            // collectLatest: a new source (another server, a playlist) cancels the old one's sync, which otherwise
            // held the guide until it finished (its 45 MB guide) and the new one never showed until a rescan.
            // Then checked every 15 minutes while Viewarr is open, so a guide left running is fetched again on time
            // (Settings → Live TV → TV guide refresh).
            sessions.session.filterNotNull().distinctUntilChanged().collectLatest { s ->
                while (true) {
                    repo.syncIfStale(s, AppColors.palette.guideRefreshMs)
                    delay(15 * 60_000L)
                }
            }
        }
        viewModelScope.launch {
            repo.generation.collect {
                fetchedPast.clear()
                ensurePast()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val previous = _now.value
                val t = System.currentTimeMillis()
                _now.value = t
                // Follow live TV only if the user was looking at "now"; leave past/future browsing alone.
                _cursor.update { c ->
                    if (c.focusTime in previous..t) c.copy(focusTime = t, windowStart = maxOf(c.windowStart, floorToStep(t - STEP_MS)))
                    else c
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { sessions.session.first()?.let { repo.sync(it) } }
    }

    fun selectGroup(id: Int?) {
        _groupId.value = id
        _cursor.value = initialCursor(System.currentTimeMillis())
    }

    /**
     * Back to [groupId] with the highlight where it was ([cursor], on [channelId]): the menu's Back after moving through
     * the channel groups, which showed each one in the guide behind and left it on the last, at its first channel.
     */
    fun restoreView(groupId: Int?, cursor: GuideCursor, channelId: Int?) {
        if (_groupId.value == groupId) return
        _groupId.value = groupId
        viewModelScope.launch {
            // (The group's channels come back a moment later: the highlight waits for them, else it would be clipped.)
            if (channelId != null) withTimeoutOrNull(3_000) { channels.first { list -> list.any { it.id == channelId } } }
            _cursor.value = cursor
        }
    }

    /**
     * Up / Down a row ([delta] rows; a page with the channel buttons). Past the last channel or above the first
     * (Settings → Live TV → TV guide → End of a list): [ends] 0 loops on round, 1 jumps to the other end on a [fresh] press
     * (holding the button stops at the end), 2 stops.
     */
    fun moveVertical(delta: Int, ends: Int = 2, fresh: Boolean = true): Boolean {
        val count = channels.value.size
        if (count == 0) return false
        val before = _cursor.value.row
        _cursor.update { c ->
            val to = c.row + delta
            val row = when {
                to in 0 until count -> to
                ends == 0 -> ((to % count) + count) % count
                ends == 1 && fresh && (c.row == 0 || c.row == count - 1) -> if (to < 0) count - 1 else 0
                else -> to.coerceIn(0, count - 1)
            }
            c.copy(row = row)
        }
        ensurePast()
        return _cursor.value.row != before
    }

    fun moveRight() {
        val c = _cursor.value
        val list = rowPrograms(c.row)
        val current = programAt(list, c.focusTime)
        val next = if (current != null) list.firstOrNull { it.startTime >= current.endTime }
        else list.firstOrNull { it.startTime > c.focusTime }
        val target = next?.startTime ?: (c.focusTime + STEP_MS)
        if (target > _now.value + maxForwardMs()) return
        var ws = c.windowStart
        while (target >= ws + VISIBLE_MS - STEP_MS) ws += STEP_MS
        _cursor.value = c.copy(focusTime = target, windowStart = ws)
    }

    /** Back one programme. False at the start: the channel's catch-up limit, or its earliest programme without it. */
    fun moveLeft(): Boolean {
        val c = _cursor.value
        val list = rowPrograms(c.row)
        val current = programAt(list, c.focusTime)
        val prev = if (current != null) list.lastOrNull { it.endTime <= current.startTime }
        else list.lastOrNull { it.endTime <= c.focusTime }
        val now = _now.value
        val days = channels.value.getOrNull(c.row)?.catchupDays ?: 0
        val earliest = if (days > 0) maxOf(now - days * 86_400_000L, now - MAX_PAST_MS)
        else minOf(list.firstOrNull()?.startTime ?: now, now).coerceAtLeast(now - MAX_PAST_MS)
        val target = maxOf(prev?.startTime ?: (c.focusTime - STEP_MS), earliest)
        if (target >= c.focusTime) return false
        // Landing where the highlight already is (no programmes listed, or the one highlighted with none before it): an
        // invisible step, so Left seemed to do nothing and the menu opened only on the second press. With catch-up
        // and nothing loaded before this programme the step still happens: it fetches the earlier ones (ensurePast).
        val landing = programAt(list, target)
        val invisible = list.isEmpty() || (landing != null && landing.rowId == current?.rowId)
        if (invisible && !(list.isNotEmpty() && prev == null && days > 0)) return false
        var ws = c.windowStart
        while (target < ws) ws -= STEP_MS
        _cursor.value = c.copy(focusTime = target, windowStart = ws)
        ensurePast()
        return true
    }

    /** Jumps the guide [deltaMs] (a day: the colour buttons), keeping the channel; stops at the guide's limits. */
    fun jumpBy(deltaMs: Long) {
        val c = _cursor.value
        val now = _now.value
        // A whole day: the same time of day on the next / previous date (24 h is an hour off on clock-change days).
        val target = if (deltaMs == DAY_MS || deltaMs == -DAY_MS) {
            val z = Instant.ofEpochMilli(c.focusTime).atZone(ZoneId.systemDefault())
            (if (deltaMs > 0) z.plusDays(1) else z.minusDays(1)).toInstant().toEpochMilli()
        } else c.focusTime + deltaMs
        val t = target.coerceIn(now - MAX_PAST_MS, now + maxForwardMs())
        if (t == c.focusTime) return
        _cursor.value = c.copy(focusTime = t, windowStart = floorToStep(t))
        ensurePast()
    }

    fun isAtNow(): Boolean {
        val c = _cursor.value
        return c.focusTime in _now.value - 60_000.._now.value + 60_000 || programAt(rowPrograms(c.row), c.focusTime)?.let {
            it.startTime <= _now.value && it.endTime > _now.value
        } == true
    }

    /** Moves the guide to a channel (used when zapping in the player), at live time. */
    fun selectChannel(channelId: Int) {
        val index = channels.value.indexOfFirst { it.id == channelId }
        if (index < 0) return
        val t = System.currentTimeMillis()
        _cursor.value = GuideCursor(row = index, focusTime = t, windowStart = floorToStep(t))
    }

    /**
     * Finds a channel by its number (typed on the remote). Looks in the current category first, then in all
     * channels; if it's in another category the guide switches to All channels so it can be shown.
     */
    fun findByNumber(number: Int, onResult: (ChannelListItem?) -> Unit) {
        viewModelScope.launch {
            val wanted = number.toDouble()
            channels.value.firstOrNull { it.number == wanted }?.let { return@launch onResult(it) }
            val hit = repo.observeChannels(null).first().firstOrNull { it.number == wanted }
            if (hit != null && channels.value.none { it.id == hit.id }) {
                _groupId.value = groupToShow(hit)
                withTimeoutOrNull(3_000) { channels.first { list -> list.any { it.id == hit.id } } }
            }
            onResult(hit)
        }
    }

    suspend fun channelById(id: Int): ChannelListItem? = allChannelsById()[id]

    /** A channel's programmes: back as far as its catch-up goes (else just earlier today), and the next day ahead. */
    suspend fun programsFor(ch: ChannelListItem): List<GuideProgram> {
        val now = System.currentTimeMillis()
        // Earlier today at least (from midnight, or 6 hours if that's longer), more for catch-up channels.
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val back = maxOf(now - midnight, 6 * 3_600_000L, ch.catchupDays.coerceAtMost(2) * 86_400_000L)
        val from = now - back
        // Past programmes before the last full sync aren't stored until asked for: fetch them first.
        val syncedFrom = repo.syncedFrom()
        if (from < syncedFrom && ch.guideKey != ch.uuid) {
            sessions.session.first()?.let { s ->
                // Don't keep the panel on "Loading…" if the server is slow: show what's stored after a few seconds.
                // (The fetches carry on in the background, so the guide gets them even if the panel stops waiting.)
                val jobs = ArrayList<Job>()
                var chunk = Math.floorDiv(from, CHUNK_MS) * CHUNK_MS
                while (chunk < syncedFrom) {
                    val c = chunk
                    if (fetchedPast.add("${ch.guideKey}|$c")) jobs += viewModelScope.launch { repo.loadPastChunk(s, ch.guideKey, c, c + CHUNK_MS) }
                    chunk += CHUNK_MS
                }
                withTimeoutOrNull(8_000) { jobs.forEach { it.join() } }
            }
        }
        return repo.programsFor(ch.id, ch.guideKey, from, now + 30 * 3_600_000L)
    }

    // A game's channel, remembered for ten minutes (the Sports rows ask each time the live TV controls open).
    private val gameChannels = ConcurrentHashMap<String, Pair<Long, Pair<ChannelListItem, GuideProgram>?>>()
    // Every channel (all groups): one copy, kept up to date by the database (a guide sync, another Live TV source), for
    // the things that look across all of them: search as you type, Live now each minute, a channel by its id. Each
    // used to load the whole list again every time. Event channels hidden for want of a listing are in it too: a
    // link, reminder, recording or the Sports page finds them by id (the lists don't show them; see
    // GuideRepository.hiddenEventIds).
    private val allChannels = repo.observeAllChannels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private suspend fun allChannelsList(): List<ChannelListItem> = allChannels.value.ifEmpty { repo.observeAllChannels().first() }
    private var byIdOf: Pair<List<ChannelListItem>, Map<Int, ChannelListItem>>? = null
    private suspend fun allChannelsById(): Map<Int, ChannelListItem> {
        val list = allChannelsList()
        byIdOf?.takeIf { it.first === list }?.let { return it.second }
        return list.associateBy { it.id }.also { byIdOf = list to it }
    }

    /**
     * What the UK listings say about a game: the channels, how surely the listing is this game ([GameMatch.SURE]:
     * both teams; POSSIBLE: one by name), and the listing itself ("Celtic v St Mirren, Scottish Premiership").
     */
    data class UkResult(val channels: List<String>, val sureness: Int, val listing: String?, val source: String = "UK TV listings")

    /**
     * Where a game your guide doesn't list is on, when the Viewarr plugin isn't placing games for the house: Sky's TV
     * guide first (about a week ahead), else TheSportsDB. (The old UK Sport on TV plugin's listings were a step here
     * until 2026-11-19: the Viewarr plugin carries those listings itself.) Null: no answer yet.
     */
    suspend fun ukMatch(game: SportGame): UkResult? {
        // 1: Sky's own TV guide (Viewarr's: no plugin needed), about a week ahead: both teams in a programme around
        // kick-off.
        val skyFound = runCatching { sky.find(game) }.getOrNull()
        if (skyFound != null && skyFound.channels.isNotEmpty())
            return UkResult(skyFound.channels, GameMatch.SURE, "${skyFound.title} (${Dates.time(skyFound.start)})", source = "Sky TV guide")
        // In Sky's range and not there: an English / Scottish league game at 3pm on a Saturday is the blackout (never on
        // UK TV); anything else may be on a channel Sky's guide doesn't carry (Amazon, club TV).
        if (skyFound != null && blackout(game)) return UkResult(listOf(NOT_ON_UK_TV), GameMatch.SURE, null, source = "Sky TV guide")
        // 2: TheSportsDB.
        return runCatching { sports.ukChannels(game.home, game.away, game.start) }.getOrNull()?.let { UkResult(it, GameMatch.SURE, "TheSportsDB", source = "TheSportsDB") }
    }

    /** An English / Scottish league game kicking off between 14:45 and 15:15 UK time on a Saturday (not shown on UK TV). */
    private fun blackout(g: SportGame): Boolean {
        val l = g.league
        val league = l.startsWith("soccer/eng.") && l.removePrefix("soccer/eng.").firstOrNull()?.isDigit() == true ||
            l.startsWith("soccer/sco.") && l.removePrefix("soccer/sco.").firstOrNull()?.isDigit() == true
        if (!league) return false
        val t = Instant.ofEpochMilli(g.start).atZone(ZoneId.of("Europe/London"))
        val mins = t.hour * 60 + t.minute
        return t.dayOfWeek == DayOfWeek.SATURDAY && mins in (14 * 60 + 45)..(15 * 60 + 15)
    }

    /** Just the channel list again (viewarr://refresh, or a link to a channel not known yet). */
    suspend fun refreshChannels() { sessions.session.first()?.let { runCatching { repo.syncChannels(it) } } }
    /** The whole guide again (viewarr://refresh?guide=1). */
    suspend fun refreshGuide() { sessions.session.first()?.let { runCatching { repo.sync(it) } } }

    /** The guide fetched again if it's older than [maxAgeMs] (Sports: a game on now whose event channel is newer). */
    suspend fun syncIfOlderThan(maxAgeMs: Long) {
        sessions.session.first()?.let { runCatching { repo.syncIfStale(it, maxAgeMs) } }
        gameChannels.clear()
    }

    /**
     * Sports hub: the channel showing [game], from the guide: a programme naming both sides (GameMatch), from 3 hours
     * before kick-off to an hour after. Null if none is listed yet.
     */
    suspend fun channelForGame(game: SportGame): Pair<ChannelListItem, GuideProgram>? {
        val t = System.currentTimeMillis()
        gameChannels[game.id + "|" + game.start]?.takeIf { t - it.first < 10 * 60_000L }?.let { return it.second }
        // (The matching itself is shared with the background recorder, so both find the same channel and programme.)
        val best = io.github.petebutty.viewarr.data.sports.TvGameRecorder.channelForGame(repo, allChannelsById(), game)
        gameChannels[game.id + "|" + game.start] = t to best
        return best
    }

    /** Search: what's on now and coming up whose title matches, with its channel (one per showing). */
    suspend fun searchPrograms(q: String): List<Pair<ChannelListItem, GuideProgram>> {
        val t = q.trim()
        if (t.length < 2) return emptyList()
        val channels = allChannelsById()
        return repo.searchPrograms(t, System.currentTimeMillis(), 120)
            .mapNotNull { p -> channels[p.channelId]?.let { it to p } }
            // The same showing on two copies of a channel: keep one.
            .distinctBy { (c, p) -> Triple(c.name.lowercase(), p.title, p.startTime) }
            .take(40)
    }

    /**
     * Search → Catch-up: past programmes whose title matches, on channels with catch-up and still inside their
     * catch-up window, newest first (one per showing).
     */
    suspend fun searchCatchup(q: String): List<Pair<ChannelListItem, GuideProgram>> {
        val t = q.trim()
        if (t.length < 2) return emptyList()
        val session = sessions.session.first() ?: return emptyList()
        val all = allChannelsList().filter { it.catchupDays > 0 }
        if (all.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val from = now - all.maxOf { it.catchupDays } * 86_400_000L
        val byKey = all.groupBy { it.guideKey }
        val byId = all.associateBy { it.id }
        return repo.searchPast(session, t, from, now)
            .flatMap { (key, p) ->
                val chs = if (key != null) byKey[key].orEmpty() else listOfNotNull(byId[p.channelId])
                chs.filter { p.startTime >= now - it.catchupDays * 86_400_000L }.map { it to p.copy(channelId = it.id) }
            }
            // The same showing on two copies of a channel: keep one.
            .distinctBy { (c, p) -> Triple(c.name.lowercase(), p.title, p.startTime) }
            .take(40)
    }

    /** Channels whose name or number matches a search, across all categories (not event channels hidden for want of a listing). */
    /**
     * Voice search (2026-11-21: "voice search will play a TV channel automatically"): the channel [said] names,
     * when it clearly names one ("BBC One", "channel 104", "put on Sky Atlantic", "BBC 2"), else null (a normal search).
     * Exact name first; else the one channel whose name starts with it ("BBC One" → BBC One Scotland), HD copies aside.
     */
    suspend fun voiceChannel(said: String): ChannelListItem? {
        val words = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
            "eight" to "8", "nine" to "9", "ten" to "10", "first" to "1", "second" to "2", "and" to "&")
        fun norm(s: String) = s.lowercase().replace("+", " plus ").replace(Regex("[^a-z0-9& ]"), " ").split(' ').filter { it.isNotBlank() }
            .map { words[it] ?: it }.filter { it != "hd" && it != "fhd" && it != "uhd" && it != "4k" }.joinToString(" ").replace(" & ", " and ").replace("&", " and ")
        var t = norm(said)
        t = t.replace(Regex("^(please )?(play|watch|put on|put|switch to|switch|turn on|go to|tune to|change to|show me|open)( the)? "), "")
            .replace(Regex(" (please|now|on (the )?tv)$"), "").replace(Regex(" channel$"), "").trim()
        if (t.length < 2) return null
        // (Old or spoken names first turned into today's: "Sky Movies Premiere" → Sky Cinema Premiere.)
        channelAliases(t).firstOrNull()?.let { t = norm(it) }
        val hidden = repo.hiddenEventIds.value
        val all = allChannelsList().filter { it.id !in hidden }
        Regex("^(channel |number )?(\\d{1,4})$").find(t)?.groupValues?.get(2)?.toDoubleOrNull()?.let { n -> all.firstOrNull { it.number == n }?.let { return it } } // ("Channel 4" with no channel 4: its name, below.)
        val named = all.map { it to norm(it.name) }
        named.filter { it.second == t }.let { if (it.isNotEmpty()) return it.minByOrNull { c -> c.first.number ?: Double.MAX_VALUE }!!.first }
        val starts = named.filter { it.second.startsWith("$t ") }
        // (One channel, or copies of one: the lowest number.) A name like "Sky" matching dozens is a search instead.
        return starts.takeIf { it.isNotEmpty() && it.map { c -> c.second }.distinct().size == 1 }?.minByOrNull { it.first.number ?: Double.MAX_VALUE }?.first
    }

    suspend fun searchChannels(q: String): List<ChannelListItem> {
        val t = q.trim()
        if (t.length < 2) return emptyList()
        val hidden = repo.hiddenEventIds.value
        // (Also what people call them: "Sky Movies" finds Sky Cinema, "BBC 1" BBC One. 2026-11-21.)
        val also = channelAliases(t)
        return allChannelsList().filter { c ->
            c.id !in hidden && (c.name.contains(t, ignoreCase = true) || also.any { c.name.contains(it, ignoreCase = true) } ||
                c.number?.let { n -> (if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()) == t } == true)
        }.take(30)
    }

    /** Other names for a channel search: old or spoken names ("Sky Movies" → "Sky Cinema", "BBC 1" → "BBC One"). */
    private fun channelAliases(q: String): List<String> {
        val l = q.lowercase().trim()
        val swaps = listOf("sky movies" to "sky cinema", "sky movie" to "sky cinema", "sky sport " to "sky sports ", "bbc 1" to "bbc one",
            "bbc 2" to "bbc two", "bbc 3" to "bbc three", "bbc 4" to "bbc four", "itv 1" to "itv1", "itv one" to "itv1", "channel four" to "channel 4",
            "channel five" to "channel 5", "five" to "channel 5", "tnt sport " to "tnt sports ", "bt sport" to "tnt sports", "premier sports" to "premier sports")
        return swaps.mapNotNull { (from, to) -> if (l.startsWith(from) || l == from.trim()) to + l.removePrefix(from.trim()).let { if (from.endsWith(" ")) l.removePrefix(from) else it } else null }
            .map { it.trim() }.filter { it.isNotEmpty() && it != l }.distinct()
    }

    /**
     * Back to the guide from full screen: the channel playing, at now, in the group it was started from ([groupId];
     * All channels when it isn't there), whatever group was browsed since.
     */
    fun returnTo(channelId: Int, groupId: Int?) {
        viewModelScope.launch {
            if (_groupId.value != groupId) {
                _groupId.value = groupId
                withTimeoutOrNull(3_000) { channels.first { list -> list.any { it.id == channelId } } }
            }
            if (channels.value.none { it.id == channelId }) {
                _groupId.value = repo.observeChannels(null).first().firstOrNull { it.id == channelId }?.let(::groupToShow)
                withTimeoutOrNull(3_000) { channels.first { list -> list.any { it.id == channelId } } }
            }
            selectChannel(channelId)
        }
    }

    /** Makes a channel tunable from anywhere: switches to All channels (or its group) if it isn't in the current one. */
    fun reveal(channel: ChannelListItem, onReady: () -> Unit) {
        viewModelScope.launch {
            if (channels.value.none { it.id == channel.id }) {
                _groupId.value = groupToShow(channel)
                withTimeoutOrNull(3_000) { channels.first { list -> list.any { it.id == channel.id } } }
            }
            onReady()
        }
    }

    /** Jump back to live TV on the current channel. */
    fun goToNow() {
        val t = System.currentTimeMillis()
        _cursor.update { it.copy(focusTime = t, windowStart = floorToStep(t)) }
    }

    private fun ensurePast() {
        val c = _cursor.value
        val syncedFrom = repo.syncedFrom()
        val wantFrom = c.windowStart - BUFFER_MS
        if (wantFrom >= syncedFrom) return
        viewModelScope.launch {
            val s = sessions.session.first() ?: return@launch
            val all = channels.value
            // (The cursor can point past a shorter list after a group change: kept inside it.)
            val from = (c.row - BLOCK).coerceIn(0, all.size)
            val keys = all.subList(from, minOf(all.size, from + BLOCK * 3))
                .filter { it.guideKey != it.uuid }
                .map { it.guideKey }
                .toSet()
            var chunk = Math.floorDiv(wantFrom, CHUNK_MS) * CHUNK_MS
            while (chunk < syncedFrom) {
                val chunkStart = chunk
                for (key in keys) {
                    if (!fetchedPast.add("$key|$chunkStart")) continue
                    launch { repo.loadPastChunk(s, key, chunkStart, chunkStart + CHUNK_MS) }
                }
                chunk += CHUNK_MS
            }
        }
    }

    private fun rowPrograms(row: Int): List<GuideProgram> {
        val channel = channels.value.getOrNull(row) ?: return emptyList()
        return programs.value[channel.id].orEmpty()
    }

    companion object {
        /** [ukMatch]: in the UK listings' range, and not in them (not on UK TV). */
        const val NOT_ON_UK_TV = "\u0000not on UK TV"
        const val STEP_MS = 30 * 60_000L
        /** How much of the timeline is on screen: Settings → Appearance → TV guide timeline (2½ hours as standard). */
        val VISIBLE_MS: Long get() = when (AppColors.palette.opt(UiOpts.GUIDE_SPAN)) {
            1 -> 90; 2 -> 120; 3 -> 180; 4 -> 240; 5 -> 360; else -> 150
        } * 60_000L
        private const val BUFFER_MS = 90 * 60_000L
        /** A couple of hours short of what's fetched, so the last programmes aren't cut off. */
        private fun maxForwardMs() = AppColors.palette.guideForwardMs - 2 * 3_600_000L
        /** The ★ Favourites group's id (real groups are positive). */
        const val FAVOURITES = -2
        /** The Live now group: the channels with something live now or soon (see [liveEvents]). */
        const val LIVE_NOW = -3
        const val LIVE_NAME = "📡  Live now"
        private const val MAX_PAST_MS = 3 * 24 * 3_600_000L
        const val DAY_MS = 86_400_000L
        private const val CHUNK_MS = 6 * 3_600_000L
        private const val BLOCK = 10

        fun floorToStep(t: Long) = t - Math.floorMod(t, STEP_MS)

        private fun blockOffset(block: Int) = maxOf(0, (block - 1) * BLOCK)

        private fun initialCursor(now: Long) = GuideCursor(row = 0, focusTime = now, windowStart = floorToStep(now))

        fun programAt(list: List<GuideProgram>, time: Long): GuideProgram? =
            list.firstOrNull { it.startTime <= time && it.endTime > time }
    }
}
