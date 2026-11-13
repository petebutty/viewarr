package io.github.petebutty.viewarr.ui.guide

import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** row = selected channel index; focusTime = time anchor (TiviMate keeps the column time when moving up/down). */
data class GuideCursor(val row: Int, val focusTime: Long, val windowStart: Long)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val repo: GuideRepository,
    private val sessions: SessionStore,
    private val programArt: io.github.petebutty.viewarr.data.media.ProgramArt,
    private val plugins: io.github.petebutty.viewarr.data.dvr.PluginsRepository,
    private val sports: io.github.petebutty.viewarr.data.guide.SportsEvents,
) : ViewModel() {
    /** One event for Live now: its channel and guide entry, its tidied name, sport and picture (if found). */
    data class LiveEvent(
        val channel: ChannelListItem,
        val program: GuideProgram,
        val title: String,
        val sport: io.github.petebutty.viewarr.data.guide.SportsEvents.Sport?,
        val picture: String?,
    )

    /** Live now (the Live TV hub's shortcut): event channels with something on, and what's starting soon. */
    data class LiveEvents(
        val onNow: List<LiveEvent>,
        val soon: List<LiveEvent>,
        /** The group most of what's on now is in (OK on the shortcut opens the guide there). */
        val mainGroup: String?,
    )

    // The event / sports groups: the ones Event Channel Managarr (a Dispatcharr plugin) looks after, read once. Without
    // it (or for users Dispatcharr doesn't show plugins to), groups whose names look like sport or events.
    private var eventGroupNames: Set<String>? = null
    private suspend fun eventGroups(): Set<String> {
        eventGroupNames?.let { return it }
        val fromPlugin = runCatching { plugins.installed() }.getOrNull()
            ?.firstOrNull { it.key == "event_channel_managarr" && it.enabled }
            ?.settings?.get("channel_groups")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        return fromPlugin.also { eventGroupNames = it }
    }
    private val eventWords = Regex("\\b(sports?|events?|ppv|pay per view|box office|football|soccer|rugby|ufc|boxing|wwe|nfl|nba|nhl|mlb|f1|motogp|cricket|golf|tennis|darts|racing|premier league|efl|epl|spfl|uefa|matchroom|dazn)\\b", RegexOption.IGNORE_CASE)

    /** What's on the event channels now, and starting in the next three hours (most recent / soonest first). */
    suspend fun liveEvents(): LiveEvents {
        val named = eventGroups()
        val all = allChannelsList()
        val chans = all.filter { c -> c.groupName?.let { g -> if (named.isNotEmpty()) g.trim().lowercase() in named else eventWords.containsMatchIn(g) } == true }.take(400)
        val now = System.currentTimeMillis()
        val onNow = ArrayList<Pair<ChannelListItem, GuideProgram>>()
        val soon = ArrayList<Pair<ChannelListItem, GuideProgram>>()
        // Placeholders ("Ended: …", "No event today") left out: only real events. Every event channel's next three
        // hours in one read (one channel at a time was hundreds of reads a minute).
        val byId = chans.associateBy { it.id }
        val all3h = runCatching { repo.programsForChannels(chans.map { it.id }, now - 1, now + 3 * 3_600_000L) }.getOrNull().orEmpty()
        for ((id, ps0) in all3h.groupBy { it.channelId }) {
            val c = byId[id] ?: continue
            val ps = ps0.filter { sports.clean(it.title) != null }
            ps.firstOrNull { it.startTime <= now && it.endTime > now }?.let { onNow += c to it }
            ps.firstOrNull { it.startTime > now }?.let { soon += c to it }
        }
        // The same event on several channels (feeds, backups): once, on the first.
        fun distinct(l: List<Pair<ChannelListItem, GuideProgram>>) = l.distinctBy { (_, p) -> (sports.clean(p.title) ?: p.title).trim().lowercase() + "|" + p.startTime }
        val nowPairs = distinct(onNow.sortedByDescending { it.second.startTime }).take(30)
        val soonPairs = distinct(soon.sortedBy { it.second.startTime }).take(30)
        // Each one's sport and picture: the guide's own picture, else the match's from TheSportsDB (a few at a time).
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        suspend fun events(l: List<Pair<ChannelListItem, GuideProgram>>) = kotlinx.coroutines.coroutineScope {
            l.map { (c, p) ->
                async {
                    val title = sports.clean(p.title) ?: p.title
                    // (Pictures from TheSportsDB for the first dozen only: it allows very few requests.)
                    val pic = p.image ?: if (l.indexOfFirst { it.second.rowId == p.rowId } < 12) gate.withPermit { runCatching { sports.picture(title) }.getOrNull() } else null
                    LiveEvent(c, p, title, sports.sport("$title ${c.name} ${c.groupName.orEmpty()}"), pic)
                }
            }.map { it.await() }
        }
        return LiveEvents(
            onNow = events(nowPairs),
            soon = events(soonPairs),
            mainGroup = nowPairs.mapNotNull { it.first.groupName }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
        )
    }
    /** The highlighted programme's artwork (TMDB), or null to show the channel logo. */
    suspend fun artFor(title: String): String? = programArt.imageFor(title)


    val sync = repo.sync
    // In your order (hold OK on a group in the menu → move); groups not placed yet keep the guide's order after them.
    val groups = combine(repo.observeGroups(), repo.groupOrder) { g, order ->
        if (order.isEmpty()) g else g.withIndex().sortedBy { (i, x) -> order.indexOf(x.name).let { if (it < 0) order.size + i else it } }.map { it.value }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Moves a channel group one place up ([dir] -1) or down (1) in the menu. */
    fun moveGroup(id: Int, dir: Int) {
        val names = groups.value.map { it.name }.toMutableList()
        val i = groups.value.indexOfFirst { it.id == id }; val j = i + dir
        if (i < 0 || j !in names.indices) return
        java.util.Collections.swap(names, i, j)
        repo.setGroupOrder(names)
    }

    private val _groupId = MutableStateFlow<Int?>(null)
    val groupId = _groupId.asStateFlow()

    /** Favourite channels (uuids); the ★ Favourites group is [FAVOURITES]. */
    val favourites = repo.favourites
    fun toggleFavourite(ch: ChannelListItem): Boolean = repo.toggleFavourite(ch.uuid)

    val channels: StateFlow<List<ChannelListItem>> = combine(_groupId, repo.favourites) { g, f -> g to f }
        .flatMapLatest { (g, f) -> if (g == FAVOURITES) repo.observeChannelsIn(f.toList()) else repo.observeChannels(g) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now = _now.asStateFlow()

    private val _cursor = MutableStateFlow(initialCursor(_now.value))
    val cursor = _cursor.asStateFlow()

    val programs: StateFlow<Map<Int, List<GuideProgram>>> = combine(
        _groupId,
        _cursor.map { it.row / BLOCK }.distinctUntilChanged(),
        _cursor.map { it.windowStart }.distinctUntilChanged(),
        repo.favourites,
    ) { g, block, ws, f -> listOf(g, block, ws, f) }
        .flatMapLatest { l ->
            val g = l[0] as Int?; val block = l[1] as Int; val ws = l[2] as Long
            @Suppress("UNCHECKED_CAST") val f = l[3] as Set<String>
            (if (g == FAVOURITES) repo.observeProgramBlockIn(f.toList(), blockOffset(block), BLOCK * 3, ws - BUFFER_MS, ws + VISIBLE_MS + BUFFER_MS)
            else repo.observeProgramBlock(
                groupId = g,
                offset = blockOffset(block),
                count = BLOCK * 3,
                start = ws - BUFFER_MS,
                end = ws + VISIBLE_MS + BUFFER_MS,
            )).map { rows -> rows.groupBy { it.channelId } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val fetchedPast = HashSet<String>()

    init {
        viewModelScope.launch {
            // collectLatest: a new source (another server, a playlist) cancels the old one's sync, which otherwise
            // held the guide until it finished (its 45 MB guide) and the new one never showed until a rescan.
            // Then checked every 15 minutes while Viewarr is open, so a guide left running is fetched again on time
            // (Settings → Live TV → TV guide refresh).
            sessions.session.filterNotNull().distinctUntilChanged().collectLatest { s ->
                while (true) {
                    repo.syncIfStale(s, io.github.petebutty.viewarr.ui.theme.AppColors.palette.guideRefreshMs)
                    kotlinx.coroutines.delay(15 * 60_000L)
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
        val t = (c.focusTime + deltaMs).coerceIn(now - MAX_PAST_MS, now + maxForwardMs())
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
            if (hit != null && _groupId.value != null) {
                _groupId.value = null
                channels.first { list -> list.any { it.id == hit.id } }
            }
            onResult(hit)
        }
    }

    /** Switching sources while watching: the new source's channels, and its last-watched channel. */
    suspend fun channelsFor(session: io.github.petebutty.viewarr.data.session.Session) = repo.channelsFor(session)
    fun lastChannelOfSource(): Int? = repo.lastChannel()

    suspend fun channelById(id: Int): ChannelListItem? = allChannelsById()[id]

    /** Channels whose name or number matches a search, across all categories. */
    /** A channel's programmes: back as far as its catch-up goes (else just earlier today), and the next day ahead. */
    suspend fun programsFor(ch: ChannelListItem): List<io.github.petebutty.viewarr.data.local.GuideProgram> {
        val now = System.currentTimeMillis()
        // Earlier today at least (from midnight, or 6 hours if that's longer), more for catch-up channels.
        val midnight = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val back = maxOf(now - midnight, 6 * 3_600_000L, ch.catchupDays.coerceAtMost(2) * 86_400_000L)
        val from = now - back
        // Past programmes before the last full sync aren't stored until asked for: fetch them first.
        val syncedFrom = repo.syncedFrom()
        if (from < syncedFrom && ch.guideKey != ch.uuid) {
            sessions.session.first()?.let { s ->
                // Don't keep the panel on "Loading…" if the server is slow: show what's stored after a few seconds.
                // (The fetches carry on in the background, so the guide gets them even if the panel stops waiting.)
                val jobs = ArrayList<kotlinx.coroutines.Job>()
                var chunk = Math.floorDiv(from, CHUNK_MS) * CHUNK_MS
                while (chunk < syncedFrom) {
                    val c = chunk
                    if (fetchedPast.add("${ch.guideKey}|$c")) jobs += viewModelScope.launch { repo.loadPastChunk(s, ch.guideKey, c, c + CHUNK_MS) }
                    chunk += CHUNK_MS
                }
                kotlinx.coroutines.withTimeoutOrNull(8_000) { jobs.forEach { it.join() } }
            }
        }
        return repo.programsFor(ch.id, ch.guideKey, from, now + 30 * 3_600_000L)
    }

    /**
     * Sports hub: the channel showing [game], from the guide: a programme naming both sides (each by its most
     * telling word: "Celtic", "Mirren"), from 3 hours before kick-off to an hour after. Null if none is listed yet.
     */
    // A game's channel, remembered for ten minutes (the Sports rows ask each time the live TV controls open).
    private val gameChannels = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<ChannelListItem, GuideProgram>?>>()
    // Every channel (all groups): one copy, kept up to date by the database (a guide sync, another Live TV source), for
    // the things that look across all of them: search as you type, Live now each minute, a channel by its id. Each
    // used to load the whole list again every time.
    private val allChannels = repo.observeChannels(null).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private suspend fun allChannelsList(): List<ChannelListItem> = allChannels.value.ifEmpty { repo.observeChannels(null).first() }
    private var byIdOf: Pair<List<ChannelListItem>, Map<Int, ChannelListItem>>? = null
    private suspend fun allChannelsById(): Map<Int, ChannelListItem> {
        val list = allChannelsList()
        byIdOf?.takeIf { it.first === list }?.let { return it.second }
        return list.associateBy { it.id }.also { byIdOf = list to it }
    }

    /** A televised UK match from the UK Sport on TV plugin (Dispatcharr): when, who, and which channels. */
    private data class UkMatch(val start: Long, val home: String, val away: String, val channels: List<String>)
    private var ukList: Pair<Long, List<UkMatch>?>? = null

    /**
     * The UK Sport on TV plugin's listings, if it's installed and on (read every 30 minutes). Null: not there (or this
     * profile can't read plugins: only Dispatcharr admins can).
     */
    private suspend fun ukListings(): List<UkMatch>? {
        ukList?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return it.second }
        val list = runCatching {
            val p = plugins.installed()?.firstOrNull { it.key == "uk_sport_on_tv" && it.enabled } ?: return@runCatching null
            val raw = (p.settings["listings"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@runCatching null
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonArray.mapNotNull { e ->
                val a = e.jsonArray
                UkMatch(
                    a[0].jsonPrimitive.long, a[1].jsonPrimitive.content, a[2].jsonPrimitive.content,
                    a[4].jsonArray.map { it.jsonPrimitive.content },
                )
            }
        }.getOrNull()
        ukList = System.currentTimeMillis() to list
        return list
    }

    /**
     * The UK channels showing a game your guide doesn't reach yet: the UK Sport on TV plugin's listings first (football,
     * weeks ahead), else TheSportsDB. Null: no answer yet.
     */
    suspend fun ukChannels(game: io.github.petebutty.viewarr.data.sports.SportGame): List<String>? {
        ukListings()?.let { list ->
            // Same kick-off (within half an hour) and at least one of the teams ("Heart of Midlothian" is "Hearts"
            // there, but Celtic is Celtic).
            val skip = setOf("fc", "afc", "cf", "sc", "united", "city", "town", "athletic", "county", "rovers", "the", "and", "real", "club", "women", "albion")
            fun words(n: String) = n.lowercase().replace("&", " ").split(Regex("[^\\p{L}0-9]+")).filter { it.length > 2 && it !in skip }.toSet()
            fun same(a: String, b: String): Boolean {
                if (a.equals(b, true)) return true
                val wa = words(a); val wb = words(b)
                return wa.isNotEmpty() && wb.isNotEmpty() && (wa.intersect(wb).isNotEmpty() || wa.any { x -> wb.any { y -> x.startsWith(y) || y.startsWith(x) } })
            }
            // (Women's and men's sides kept apart.)
            fun women(n: String) = n.contains("women", true)
            val m = list.firstOrNull { u ->
                kotlin.math.abs(u.start - game.start) <= 30 * 60_000L && women(u.home) == women(game.home) &&
                    (same(u.home, game.home) || same(u.away, game.away) || same(u.home, game.away) || same(u.away, game.home))
            }
            if (m != null) return m.channels
            // Listed, and not there: not on UK TV (a Saturday 3pm game, say). The plugin's listings cover football only,
            // the coming weeks only.
            if (game.league.startsWith("soccer/") && list.any { it.start >= game.start }) return listOf(NOT_ON_UK_TV)
        }
        return runCatching { sports.ukChannels(game.home, game.away, game.start) }.getOrNull()
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

    suspend fun channelForGame(game: io.github.petebutty.viewarr.data.sports.SportGame): Pair<ChannelListItem, GuideProgram>? {
        val t = System.currentTimeMillis()
        gameChannels[game.id + "|" + game.start]?.takeIf { t - it.first < 10 * 60_000L }?.let { return it.second }
        val skip = setOf("fc", "afc", "cf", "sc", "united", "city", "town", "athletic", "county", "rovers", "the", "de", "real", "club")
        fun key(team: String) = team.split(Regex("[^\\p{L}0-9]+")).filter { it.length > 2 && it.lowercase() !in skip }.maxByOrNull { it.length } ?: team
        val a = key(game.home); val b = key(game.away)
        if (a.isBlank()) return null
        // Only the shows around kick-off (starting up to 3 h before to 1 h after; so ending by 7 h after), not the
        // whole guide.
        val channels = allChannelsById()
        val found = runCatching { repo.searchProgramsEnding(a, game.start - 3 * 3_600_000L, game.start + 7 * 3_600_000L) }.getOrNull().orEmpty()
            .mapNotNull { p -> channels[p.channelId]?.let { it to p } }
        val best = found.filter { (_, p) ->
            (b.isBlank() || p.title.contains(b, ignoreCase = true) || p.subTitle?.contains(b, ignoreCase = true) == true) &&
                p.startTime in (game.start - 3 * 3_600_000L)..(game.start + 3_600_000L)
        }.minByOrNull { (_, p) -> kotlin.math.abs(p.startTime - game.start) }
        gameChannels[game.id + "|" + game.start] = t to best
        return best
    }

    /** Search: what's on now and coming up whose title matches, with its channel (one per showing). */
    suspend fun searchPrograms(q: String): List<Pair<ChannelListItem, io.github.petebutty.viewarr.data.local.GuideProgram>> {
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
    suspend fun searchCatchup(q: String): List<Pair<ChannelListItem, io.github.petebutty.viewarr.data.local.GuideProgram>> {
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

    suspend fun searchChannels(q: String): List<ChannelListItem> {
        val t = q.trim()
        if (t.length < 2) return emptyList()
        return allChannelsList().filter { c ->
            c.name.contains(t, ignoreCase = true) || c.number?.let { n -> (if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()) == t } == true
        }.take(30)
    }

    /** Makes a channel tunable from anywhere: switches to All channels if it isn't in the current category. */
    /**
     * Back to the guide from full screen: the channel playing, at now, in the group it was started from ([groupId];
     * All channels when it isn't there), whatever group was browsed since.
     */
    fun returnTo(channelId: Int, groupId: Int?) {
        viewModelScope.launch {
            if (_groupId.value != groupId) {
                _groupId.value = groupId
                kotlinx.coroutines.withTimeoutOrNull(3_000) { channels.first { list -> list.any { it.id == channelId } } }
            }
            if (channels.value.none { it.id == channelId }) {
                _groupId.value = null
                kotlinx.coroutines.withTimeoutOrNull(3_000) { channels.first { list -> list.any { it.id == channelId } } }
            }
            selectChannel(channelId)
        }
    }

    fun reveal(channel: ChannelListItem, onReady: () -> Unit) {
        viewModelScope.launch {
            if (channels.value.none { it.id == channel.id }) {
                _groupId.value = null
                channels.first { list -> list.any { it.id == channel.id } }
            }
            onReady()
        }
    }

    /** Jump back to live TV on the current channel. */
    fun goToNow() {
        val t = System.currentTimeMillis()
        _cursor.update { it.copy(focusTime = t, windowStart = floorToStep(t)) }
    }

    private val pastInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    /** Earlier programmes are being fetched (going back through the guide): Left at the loaded start waits for them. */
    val loadingPast: Boolean get() = pastInFlight.get() > 0

    private fun ensurePast() {
        val c = _cursor.value
        val syncedFrom = repo.syncedFrom()
        val wantFrom = c.windowStart - BUFFER_MS
        if (wantFrom >= syncedFrom) return
        viewModelScope.launch {
            val s = sessions.session.first() ?: return@launch
            val all = channels.value
            val from = maxOf(0, c.row - BLOCK)
            val keys = all.subList(from, minOf(all.size, from + BLOCK * 3))
                .filter { it.guideKey != it.uuid }
                .map { it.guideKey }
                .toSet()
            var chunk = Math.floorDiv(wantFrom, CHUNK_MS) * CHUNK_MS
            while (chunk < syncedFrom) {
                val chunkStart = chunk
                for (key in keys) {
                    if (!fetchedPast.add("$key|$chunkStart")) continue
                    pastInFlight.incrementAndGet()
                    launch { try { repo.loadPastChunk(s, key, chunkStart, chunkStart + CHUNK_MS) } finally { pastInFlight.decrementAndGet() } }
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
        /** [ukChannels]: in the UK listings' range, and not in them (not on UK TV). */
        const val NOT_ON_UK_TV = "\u0000not on UK TV"
        const val STEP_MS = 30 * 60_000L
        const val VISIBLE_MS = 150 * 60_000L
        private const val BUFFER_MS = 90 * 60_000L
        /** A couple of hours short of what's fetched, so the last programmes aren't cut off. */
        private fun maxForwardMs() = io.github.petebutty.viewarr.ui.theme.AppColors.palette.guideForwardMs - 2 * 3_600_000L
        /** The ★ Favourites group's id (real groups are positive). */
        const val FAVOURITES = -2
        private const val MAX_PAST_MS = 3 * 24 * 3_600_000L
        private const val CHUNK_MS = 6 * 3_600_000L
        private const val BLOCK = 10

        fun floorToStep(t: Long) = t - Math.floorMod(t, STEP_MS)

        private fun blockOffset(block: Int) = maxOf(0, (block - 1) * BLOCK)

        private fun initialCursor(now: Long) = GuideCursor(row = 0, focusTime = now, windowStart = floorToStep(now))

        fun programAt(list: List<GuideProgram>, time: Long): GuideProgram? =
            list.firstOrNull { it.startTime <= time && it.endTime > time }
    }
}
