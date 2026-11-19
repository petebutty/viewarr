package io.github.petebutty.viewarr.ui.media

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.media.CatalogRepository
import io.github.petebutty.viewarr.data.media.LibraryCopy
import io.github.petebutty.viewarr.data.media.MediaDetail
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.RowsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import io.github.petebutty.viewarr.data.local.LocalWatchStore
import io.github.petebutty.viewarr.data.media.CastMember
import io.github.petebutty.viewarr.data.media.EpisodeInfo
import io.github.petebutty.viewarr.data.media.FilterValue
import io.github.petebutty.viewarr.data.media.FoundMarkers
import io.github.petebutty.viewarr.data.media.LibKind
import io.github.petebutty.viewarr.data.media.LibraryOptions
import io.github.petebutty.viewarr.data.media.LibraryQuery
import io.github.petebutty.viewarr.data.media.LibraryViews
import io.github.petebutty.viewarr.data.media.MediaLibrary
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.data.media.PROVIDER
import io.github.petebutty.viewarr.data.media.PROVIDER_LIBRARY
import io.github.petebutty.viewarr.data.media.PersonInfo
import io.github.petebutty.viewarr.data.media.PlayerArt
import io.github.petebutty.viewarr.data.media.PlexEpisode
import io.github.petebutty.viewarr.data.media.RatingBadge
import io.github.petebutty.viewarr.data.media.SeasonInfo
import io.github.petebutty.viewarr.data.media.SkipMarker
import io.github.petebutty.viewarr.data.media.StreamOption
import io.github.petebutty.viewarr.data.media.TitleTracks
import io.github.petebutty.viewarr.data.media.VersionPick
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.media.WatchNext
import io.github.petebutty.viewarr.data.media.WatchTarget
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.profiles.Lock
import io.github.petebutty.viewarr.data.remote.services.SeerrService
import io.github.petebutty.viewarr.data.settings.AutoNext
import io.github.petebutty.viewarr.data.settings.CachedOnly
import io.github.petebutty.viewarr.data.settings.PlaybackStore
import io.github.petebutty.viewarr.data.settings.RowLayout
import io.github.petebutty.viewarr.data.settings.RowLayoutStore
import io.github.petebutty.viewarr.data.settings.SkipMode
import io.github.petebutty.viewarr.data.settings.StreamFallback
import io.github.petebutty.viewarr.data.settings.StreamPick
import io.github.petebutty.viewarr.data.settings.ThemeStore
import io.github.petebutty.viewarr.data.trailers.TrailerRepository
import io.github.petebutty.viewarr.playback.FrameRateMatcher
import io.github.petebutty.viewarr.ui.common.PendingOrder
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.TitleParts
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.ui.player.RowSets

/**
 * Where you're up to on a show (season, episode, part-watched): the furthest of the card it was opened from and what
 * all your history says ([DetailUi.nextUp]), so neither a stale card nor one stray play of an earlier episode wins.
 */
fun DetailUi.upTo(): Triple<Int, Int, Boolean>? {
    val item = detail.item
    // (The card's own episode counts too: every source is read now, so whichever row it came from is right.)
    val card = item.nextSeason?.let { s -> item.nextEpisode?.let { e -> Triple(s, e, item.progress != null) } }
    return listOfNotNull(card, nextUp).maxWithOrNull(compareBy({ it.first }, { it.second }))?.let(::realEpisode)
}

/**
 * An episode that exists: "the one after the last you finished" can be a guess (this box's record only knows E9 was
 * finished, so it said E10), which named S1 E10 of The Last of Us, a 9-episode season (Peter, 2026-11-15). Past the
 * end of a season: the next season's first episode; finished episodes after it are skipped; past the last season,
 * nothing (all watched). Seasons whose length isn't known yet are left alone.
 */
private fun DetailUi.realEpisode(t: Triple<Int, Int, Boolean>): Triple<Int, Int, Boolean>? {
    val real = seasons.filter { it.number > 0 }.sortedBy { it.number }
    val known = real.filter { it.episodes > 0 }
    if (known.isEmpty()) return t
    fun watched(k: Pair<Int, Int>) = episodeWatched(k)
    var s = t.first; var e = t.second; var partial = t.third; var moved = false
    repeat(1000) {
        val len = known.firstOrNull { it.number == s }?.episodes ?: return if (moved) Triple(s, e, false) else t
        when {
            // (The next season even if its length isn't known yet: a new one just announced.)
            e > len -> { val next = real.firstOrNull { it.number > s } ?: return null; s = next.number; e = 1; partial = false; moved = true; if (next.episodes <= 0) return Triple(s, e, false) }
            moved && watched(s to e) -> e++
            else -> return Triple(s, e, partial)
        }
    }
    return t
}

data class DetailUi(
    val detail: MediaDetail,
    val loading: Boolean = true,
    /** null while the library check is running. */
    val copies: List<LibraryCopy>? = null,
    val hasTrakt: Boolean = false,
    val watchlisted: Boolean = false,
    /** The watchlist in use (Settings → Watchlist) can take this title: the button shows. */
    val canWatchlist: Boolean = false,
    val message: String? = null,
    val hasAddons: Boolean = false,
    /** null = not searched yet. */
    val streams: List<StreamOption>? = null,
    val searching: Boolean = false,
    /** Shows: the episodes panel. */
    val episodesOpen: Boolean = false,
    /** Set (to when) to put focus on the title page's own episode row (the unified page), instead of [episodesOpen]. */
    val focusEpisodes: Long = 0,
    val seasons: List<SeasonInfo> = emptyList(),
    /** Shows: where Play carries on from (season, episode, part-way through); null = from the first episode. */
    val nextUp: Triple<Int, Int, Boolean>? = null,
    /** The nextUp lookup has finished (nextUp may still be null: not started). */
    val nextUpChecked: Boolean = false,
    val season: Int? = null,
    /** null while loading. */
    val episodes: List<EpisodeInfo>? = null,
    val plexEpisodes: Map<Pair<Int, Int>, PlexEpisode> = emptyMap(),
    /** Jellyfin / Emby series: (season, episode) → episode id, resume point, watched. */
    val serverEpisodes: Map<Pair<Int, Int>, PlexEpisode> = emptyMap(),
    /** Episodes Trakt says you've watched (wherever you watched them). */
    val traktEpisodes: Set<Pair<Int, Int>> = emptySet(),
    /** Episodes watched or started in Viewarr (this box's record, shared through Dispatcharr): LocalWatchStore.episodesOf. */
    val localEpisodes: Map<Pair<Int, Int>, PlexEpisode> = emptyMap(),
    /** Episodes Trakt has a paused point for (from any app): CatalogRepository.traktPlaybackOf. */
    val traktPlayback: Map<Pair<Int, Int>, io.github.petebutty.viewarr.data.sync.TraktPos> = emptyMap(),
    /** IPTV provider series: (season, episode) → stream address. */
    val providerEpisodes: Map<Pair<Int, Int>, String> = emptyMap(),
    /** The episode whose play options are open. */
    val episode: EpisodeInfo? = null,
    /** IMDb / Rotten Tomatoes (from Plex) and Trakt, beside TMDB's. */
    val ratings: List<RatingBadge> = emptyList(),
    /** Films: watched on Trakt, any of your servers, or in Viewarr's record (null while checking). */
    val watched: Boolean? = null,
    /** Films on your own server: their audio / subtitle tracks, and what you picked for this Play (null = usual). */
    val tracks: TitleTracks? = null,
    /** The film's file (picture, soundtracks, subtitles), for the title page's badges. */
    val media: TitleTracks? = null,
    val pickedAudio: Int? = null,
    val pickedSubtitle: Int? = null,
)

private val DetailUi.plexShowKey get() = copies?.firstOrNull { it.source == "Plex" }?.ref

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val store: AccountStore,
    private val playbackStore: PlaybackStore,
    /** YouTube trailers: the poster preview window and the title page's Trailer button. */
    val trailers: TrailerRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val rowLayouts: RowLayoutStore,
    private val watchNext: WatchNext,
    private val seerr: SeerrService,
    /** Settings → Appearance → Title pages / Ratings: parts switched off aren't fetched. */
    private val themeStore: ThemeStore,
    /** Each library's sort and filters (per profile). */
    private val libraryViews: LibraryViews,
    /** IPTV watch progress kept on the box (the provider keeps none). */
    val localWatch: LocalWatchStore,
) : ViewModel() {
    /** Seerr: where this title stands (null = Seerr not set up, or the title has no TMDB id). */
    suspend fun seerrStatus(item: MediaItem): SeerrService.Status? {
        // Locked by an admin for this person: no requests.
        if (!Access.can(Lock.SEERR)) return null
        val a = store.current().seerr ?: return null
        val id = runCatching { repo.tmdbIdFor(item) }.getOrNull() ?: return null
        // Already on your own server (Plex, Jellyfin, Emby): shown as available. Only on your IPTV provider's VOD
        // still offers the request (to keep it on your server).
        fun own(c: List<LibraryCopy>) = c.any { it.source != PROVIDER }
        if (own(runCatching { repo.copiesOf(item) }.getOrDefault(emptyList()))) return SeerrService.Status.Available
        // Library check with the TMDB id known (addon titles only carry an IMDb id).
        if (item.tmdbId == null && own(runCatching { repo.copiesOf(item.copy(tmdbId = id)) }.getOrDefault(emptyList()))) return SeerrService.Status.Available
        return runCatching { seerr.status(a, item.type, id) }.getOrDefault(SeerrService.Status.None)
    }

    /** Which of your servers have this title ("Plex · Jellyfin"), or null: for addon rows that pass on your library. */
    suspend fun librarySources(item: MediaItem): String? = runCatching {
        val known = if (item.tmdbId == null) repo.tmdbIdFor(item)?.let { item.copy(tmdbId = it) } ?: item else item
        repo.copiesOf(known).map { it.source }.distinct().joinToString(" · ").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Request the title on Seerr; the result shows as a notice. */
    fun requestOnSeerr(item: MediaItem) {
        viewModelScope.launch {
            val a = store.current().seerr ?: return@launch
            val id = runCatching { repo.tmdbIdFor(item) }.getOrNull() ?: run {
                _notice.value = "${item.title}: Seerr needs a TMDB match, and none was found."
                return@launch
            }
            val (_, msg) = runCatching { seerr.request(a, item.type, id) }.getOrDefault(false to "Couldn't reach Seerr.")
            _notice.value = "${item.title}: $msg"
            _detail.update { d -> if (d?.detail?.item?.key == item.key) d.copy(message = msg) else d }
        }
    }


    /** "Next episode in 10 s" after an episode ends (Settings → Playback → Play next episode). */
    data class UpNext(val episode: EpisodeInfo)
    private val _upNext = MutableStateFlow<UpNext?>(null)
    val upNext = _upNext.asStateFlow()
    private var endedNearEnd: WatchTarget? = null

    /**
     * Orders addon streams: within the quality limit before above it, cached (instant) before uncached,
     * then higher resolution first. The addon's own order breaks ties. Cached only (Settings → Playback → Streams)
     * leaves the uncached Usenet / torrent ones out.
     */
    private suspend fun rank(all: List<StreamOption>): List<StreamOption> {
        val pb = playbackStore.current()
        val found = if (pb.cachedOnly == CachedOnly.On) all.filterNot(StreamMatch::uncached) else all
        val max = pb.maxResolution.height ?: FrameRateMatcher.maxHeight(appContext)
        // Each stream's resolution and instant mark read once, not again in every comparison.
        class Keyed(val o: StreamOption, val index: Int, val res: Int = StreamMatch.height(o), val cached: Boolean = StreamMatch.cached(o))
        return found.mapIndexed { i, o -> Keyed(o, i) }.sortedWith(
            compareBy<Keyed> { if (it.res > max) 1 else 0 }
                .thenBy { if (it.cached) 0 else 1 }
                .thenByDescending { it.res }
                .thenBy { it.index },
        ).map { it.o }
    }

    /**
     * What Play starts from ranked addon streams: the first matching the Auto-play match (else the first), with the
     * next few behind it when Try the next stream is on (Settings → Playback → Streams).
     */
    private suspend fun autoStream(
        ranked: List<StreamOption>,
        make: (StreamOption) -> VodStream,
    ): VodStream {
        val pb = playbackStore.current()
        val max = pb.maxResolution.height ?: FrameRateMatcher.maxHeight(appContext)
        fun within(o: StreamOption) = StreamMatch.height(o) <= max
        val order = StreamMatch.autoOrder(ranked, StreamMatch.parse(pb.streamRules), ::within)
        val first = order.first()
        var behind: VodStream? = null
        // The next ones behind it: within Best quality allowed (unless the first isn't either: nothing is), and with
        // the same request headers (the player's connection is set up with the first one's).
        if (pb.streamFallback == StreamFallback.On)
            for (o in order.drop(1).filter { (within(it) || !within(first)) && it.headers == first.headers }.take(3).reversed())
                behind = make(o).copy(fallback = behind, nextSource = true)
        return make(first).copy(fallback = behind)
    }

    private suspend fun autoPick() = playbackStore.current().streamPick == StreamPick.Auto

    private val _rows = MutableStateFlow(RowsState(emptyList(), done = false))
    private var lastQuery = ""
    /** Settings → Home page & menu → Combine rows from different servers. */
    val mergeRows = rowLayouts.mergeRows.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    fun setMergeRows(on: Boolean) { viewModelScope.launch { rowLayouts.setMergeRows(on) } }
    /** Shows dropped from Continue watching (hold OK → Drop show), kept out of it and Upcoming episodes. */
    val droppedShows = rowLayouts.droppedShows.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    private fun isDropped(item: MediaItem, dropped: List<Pair<String, String>>) =
        item.type == MediaType.Show && dropped.isNotEmpty() &&
            (dropped.any { it.first == dropKey(item) } || dropped.any { it.second.equals(item.title, ignoreCase = true) })
    /** The rows a dropped show leaves: Continue watching (any server) and Upcoming episodes. */
    private fun dropsFrom(row: MediaRow) =
        row.group == "deck" || row.title.startsWith("Continue watching") || row.id.contains("-resume-") || row.id.startsWith("upcoming-")
    /** Films / Shows: Settings → Home rows decides order and which are shown. Search results are left alone. */
    private val layoutType = MutableStateFlow<MediaType?>(null)
    /** Each server's / library's Continue watching, for Settings → Home page & menu → Continue watching from. */
    val continueSources = _rows.map { r -> r.rows.mapNotNull { ContinueFrom.ofRow(it) } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    /**
     * Films finished here this session (stopped past 80%): kept out of Continue watching straight away, before the
     * rows are next loaded (Peter, 2026-11-18: Chainsaw Man stayed there after the credits, its bar at the start).
     */
    private val finishedHere = MutableStateFlow<Set<String>>(emptySet())
    private fun filmKeys(tmdb: Int?, imdb: String?) = listOfNotNull(tmdb?.let { "tmdb:$it" }, imdb?.takeIf { it.isNotBlank() }?.let { "imdb:$it" })
    /**
     * A film finished: here this session, or (unless [fromTrakt]: Trakt's own Continue watching, where a re-watch
     * Trakt says is under way stays) in Trakt's history. For Continue watching (WatchState.mergeContinue).
     */
    fun finishedFilm(i: MediaItem, fromTrakt: Boolean = false): Boolean = i.type == MediaType.Movie &&
        (filmKeys(i.tmdbId, i.imdbId).any { it in finishedHere.value } || (!fromTrakt && repo.traktWatchedMovie(i)))
    /** Continue watching as one, from every source: each title once, finished ones out (WatchState.mergeContinue). */
    private fun oneContinue(r: RowsState): RowsState = r.copy(rows = mergeContinue(r.rows,
        { row -> dropsFrom(row) && !row.id.startsWith("upcoming-") }, { i, trakt -> finishedFilm(i, trakt) }))
    /** The rows as shown: repeats across servers combined when that's switched on. */
    private val shownRows = kotlinx.coroutines.flow.combine(_rows, mergeRows, droppedShows, localWatch.rowEntries, layoutType) { r000, merge, dropped, local, type ->
        // Continue watching from a server / IPTV switched off (Settings → Home page & menu): that row goes.
        val r00 = r000.copy(rows = r000.rows.filter { row -> ContinueFrom.ofRow(row)?.let { ContinueFrom.on(it) } != false })
        // Continue watching (IPTV): kept on the box, so it joins the page's rows here (Movies / Shows, not search).
        val iptv = type?.takeIf { ContinueFrom.on(ContinueFrom.IPTV) }?.let { t -> localWatch.continueItems(t, local) }.orEmpty()
        val r0 = if (iptv.isEmpty() || type == null || r00.rows.isEmpty()) r00
            else r00.copy(rows = listOf(MediaRow("iptv-resume-${type.name}", "Continue watching on IPTV", iptv, group = "deck")) + r00.rows)
        val r = if (dropped.isEmpty()) r0 else r0.copy(rows = r0.rows.map { row ->
            if (dropsFrom(row)) row.copy(items = row.items.filterNot { isDropped(it, dropped) }) else row
        }.filter { it.items.isNotEmpty() || !dropsFrom(it) })
        r to merge
    }.let { rows ->
        // (Again when a film's finished here or Trakt's history changes; combined across servers after, so the
        // merged row keeps the title from the row that won.)
        kotlinx.coroutines.flow.combine(rows, finishedHere, repo.traktWatchedState) { (r, merge), _, _ ->
            val one = oneContinue(r)
            if (merge) one.copy(rows = io.github.petebutty.viewarr.data.media.mergeRows(one.rows)) else one
        }
            // (Sorting and merging the rows off the main thread: profiled at ~0.35 s of it in 12 s of browsing while
            // rows were arriving, which made the poster slides drop frames. Peter, 2026-11-19.)
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
    }

    /** The key a dropped show is saved under: its TMDB id, else IMDb, else its name. */
    fun dropKey(item: MediaItem) = item.tmdbId?.let { "tmdb:$it" } ?: item.imdbId?.let { "imdb:$it" } ?: "title:" + item.title.lowercase()

    /** Whether [item] is in a Continue watching row (the hold-OK menu offers Drop show there). */
    fun inContinue(item: MediaItem) = item.type == MediaType.Show &&
        (_rows.value.rows.any { r -> dropsFrom(r) && !r.id.startsWith("upcoming-") && r.items.any { it.key == item.key } } || item.progress != null ||
            _continueItems.value.orEmpty().any { it.key == item.key })

    /** Drop show: out of Continue watching and Upcoming episodes here (and on Trakt), until you watch it again. */
    fun dropShow(item: MediaItem) {
        viewModelScope.launch {
            rowLayouts.setDropped(dropKey(item), item.title, true)
            runCatching { repo.setTraktDropped(item, true) }
        }
    }

    /** Back in Continue watching (an episode of it started playing). */
    fun undropShow(key: String, title: String) {
        viewModelScope.launch {
            rowLayouts.setDropped(key, title, false)
            key.removePrefix("tmdb:").toIntOrNull()?.takeIf { key.startsWith("tmdb:") }?.let { id ->
                runCatching { repo.setTraktDropped(MediaItem(key = "tmdb:$id", type = MediaType.Show, title = title, tmdbId = id, origin = "Trakt"), false) }
            }
        }
    }
    /** A row opened from the menu that's switched off on the page: shown on the page anyway until Home is picked. */
    private val openedFromMenu = MutableStateFlow<String?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val layout = layoutType.flatMapLatest { t -> if (t == null) kotlinx.coroutines.flow.flowOf(null) else rowLayouts.layout(t) }
    /** Search: the filter buttons under the box. */
    private val _searchFilter = MutableStateFlow(SearchFilter.All)
    val searchFilter = _searchFilter.asStateFlow()
    fun setSearchFilter(f: SearchFilter) { _searchFilter.value = f }
    /** The filter pill being looked at (not picked): its kind of result moves to the top, the rest stay. */
    private val _searchFocus = MutableStateFlow<SearchFilter?>(null)
    val searchFocus = _searchFocus.asStateFlow()
    fun setSearchFocus(f: SearchFilter?) { _searchFocus.value = f }

    /**
     * A service's page (menu → a service; RowSets: "🇬🇧 UK" = rows named 🇬🇧…, "Plex" = your Plex rows): only those
     * on the page, rows hidden from the normal home page included. Cleared by any other way onto the page.
     */
    private val rowSet = MutableStateFlow<String?>(null)
    val currentRowSet = rowSet.asStateFlow()
    /** The other page's rows of the same set (a network's films on its shows page), shown after this page's. */
    private val rowSetExtra = MutableStateFlow<List<MediaRow>>(emptyList())
    fun showRowSet(key: String?, extra: List<MediaRow> = emptyList()) { rowSetExtra.value = extra; rowSet.value = key }
    // Settings → Home page & menu → Network rows: off the home page unless chosen (an addon like AIOMetadata can add
    // dozens; they're reached through their service pages in the menu).
    private val setRowsHome = androidx.compose.runtime.snapshotFlow {
        AppColors.palette.opt(UiOpts.SET_ROWS_HOME) == 1
    }
    // (The set's rows from the other page count too: changing them redraws the set's page.)
    private val shownWithSet = kotlinx.coroutines.flow.combine(shownRows, rowSet, setRowsHome, rowSetExtra, MyLibrary.state) { r, set, home, _, mine ->
        // My Library's page: your own servers' rows from both pages, in its own order (MyLibrary).
        if (set == MyLibrary.SET) r.copy(rows = mine.pageRows) to set
        else if (set != null || home) r to set
        else {
            val grouped = RowSets.sets(r.rows).filter { it.startsWith("name:") }.toSet()
            r.copy(rows = r.rows.filter { row -> RowSets.name(row)?.let { "name:$it" !in grouped } ?: true }) to null
        }
    }

    val rows: kotlinx.coroutines.flow.StateFlow<RowsState> = kotlinx.coroutines.flow.combine(shownWithSet, layout, _searchFilter, openedFromMenu, _searchFocus) { (r, set), l, f, extra, focus ->
        // A service's page: its shows and films rows together in one fixed order (New shows, New films, Originals, Popular,
        // Top rated: RowSets.kindRank), this page's type before the other's within each kind.
        if (set == MyLibrary.SET) r
        else if (set != null && l != null) r.copy(rows = (l.ordered(r.rows).filter { RowSets.matches(set, it) && !l.off(it.id) }.map { it to 0 } +
            rowSetExtra.value.map { it to 1 }).sortedWith(compareBy({ RowSets.kindRank(it.first) }, { it.second })).map { it.first })
        else if (l != null) r.copy(rows = l.ordered(r.rows).filter { !l.off(it.id) || it.id == extra }) else r.copy(rows = focusFirst(orderSearch(f.apply(r.rows)), focus))
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, RowsState(emptyList(), done = false))
    // My Library's page takes your own servers' rows from both pages, and its saved order / on-off (MyLibrary).


    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(shownRows, layoutType) { r, t -> r to t }.collect { (r, t) ->
                // (Not while a page reloads, empty: My Library would blank for a moment.)
                if (t != null && (r.rows.isNotEmpty() || r.done)) MyLibrary.publish(t, r.rows) { repo.rowOrigin(it.id) }
            }
        }
        viewModelScope.launch { rowLayouts.myLibrary.collect { MyLibrary.setLayout(it) } }
    }
    fun setMyLibraryHidden(key: String, hidden: Boolean) { viewModelScope.launch { rowLayouts.setMyLibraryHidden(key, hidden) } }
    fun setMyLibraryOrder(keys: List<String>) { viewModelScope.launch { rowLayouts.setMyLibraryOrder(keys) } }
    fun resetMyLibrary() { viewModelScope.launch { rowLayouts.resetMyLibrary() } }

    /** Every row in the chosen order with whether it's hidden, for Settings → Home rows. */
    val editableRows: kotlinx.coroutines.flow.StateFlow<Pair<List<Pair<MediaRow, Boolean>>, Boolean>> =
        kotlinx.coroutines.flow.combine(shownRows, layout, setRowsHome) { r, l, home ->
            val lay = l ?: RowLayout()
            // Network rows that are kept off the home page (their service pages open them) aren't listed here either.
            val grouped = if (home) emptySet() else RowSets.sets(r.rows).filter { it.startsWith("name:") }.toSet()
            lay.ordered(r.rows).filter { row -> RowSets.name(row)?.let { "name:$it" !in grouped } ?: true }
                .map { it to lay.off(it.id) } to r.done
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList<Pair<MediaRow, Boolean>>() to false)

    /** The menu's Catalogues list (Movies / Shows → Right): in the page's order, with its own on / off. */
    // (Every row, network rows included: the menu's service entries are built from these.)
    val menuRows: kotlinx.coroutines.flow.StateFlow<RowsState> = kotlinx.coroutines.flow.combine(shownRows, layout) { r, l ->
        val lay = l ?: RowLayout()
        r.copy(rows = lay.ordered(r.rows).filter { it.id !in lay.menuHidden })
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, RowsState(emptyList(), done = false))
    /** The menu's "Jump to a row": exactly the rows on the page, in its order (one on / off for both). */
    val jumpRows: kotlinx.coroutines.flow.StateFlow<RowsState> = kotlinx.coroutines.flow.combine(shownWithSet, layout) { (r, _), l ->
        val lay = l ?: RowLayout()
        r.copy(rows = lay.ordered(r.rows).filter { !lay.off(it.id) })
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, RowsState(emptyList(), done = false))

    /** Where a row comes from, for the settings lists: Plex, Jellyfin, an add-on's name, Trakt… */
    fun rowOrigin(row: MediaRow): String = repo.rowOrigin(row.id)

    fun setRowHidden(id: String, hidden: Boolean) {
        val t = layoutType.value ?: return
        viewModelScope.launch { rowLayouts.setHidden(t, id, hidden) }
    }

    /** Every row in the chosen order, with the ones hidden from the page (the menu's service editor). */
    val orderedRows: kotlinx.coroutines.flow.StateFlow<Pair<List<MediaRow>, Set<String>>> =
        kotlinx.coroutines.flow.combine(shownRows, layout) { r, l ->
            val lay = l ?: RowLayout()
            lay.ordered(r.rows).let { o -> o to o.filter { lay.off(it.id) }.map { it.id }.toSet() }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList<MediaRow>() to emptySet())

    /** Moves a row one place up (-1) or down (1) among the rows matching [among] (a service's), keeping the rest. */
    fun moveRowAmong(id: String, dir: Int, among: (MediaRow) -> Boolean) {
        // From the order as just changed (the shown one is a moment behind: a quick second press moved it back).
        val byId = orderedRows.value.first.associateBy { it.id }
        val saved = byId.keys.toList()
        val key = "rows:" + layoutType.value
        val full = PendingOrder.get(key, saved).takeIf { it.toSet() == saved.toSet() }.orEmpty().ifEmpty { saved }.toMutableList()
        val members = full.filter { r -> byId[r]?.let(among) == true }
        val at = members.indexOf(id)
        val other = members.getOrNull(at + dir) ?: return
        val i = full.indexOf(id); val j = full.indexOf(other)
        if (i < 0 || j < 0) return
        full[i] = other; full[j] = id
        PendingOrder.set(key, full)
        setRowOrder(full)
    }

    fun setRowOrder(ids: List<String>) {
        val t = layoutType.value ?: return
        viewModelScope.launch { rowLayouts.setOrder(t, ids) }
    }

    fun resetRows() {
        val t = layoutType.value ?: return
        viewModelScope.launch { rowLayouts.reset(t) }
    }
    private val _detail = MutableStateFlow<DetailUi?>(null)
    val detail = _detail.asStateFlow()

    // The open show's episodes in Viewarr's own record, kept in step as they change (played, ticked, synced in from
    // another TV): the ticks, bars and Up next read them along with Trakt's and the servers'.
    // (Here, after _detail: an init above it ran its first collect while _detail was still unset and crashed at start.)
    init {
        viewModelScope.launch {
            localWatch.entries.collect {
                _detail.update { d -> if (d == null || d.detail.item.type != MediaType.Show) d else d.copy(localEpisodes = localWatch.episodesOf(d.detail.item)) }
            }
        }
        // Trakt's paused points too (loaded with the rows, moved by each scrobble): the episodes' bars follow.
        viewModelScope.launch {
            repo.traktPlaybackState.collect {
                _detail.update { d -> if (d == null || d.detail.item.type != MediaType.Show) d else d.copy(traktPlayback = repo.traktPlaybackOf(d.detail.item)) }
            }
        }
    }
    /** What's playing. Set through here so the Audio & subtitles picked on a film's page go with its own copy. */
    private inner class Playing {
        val flow = MutableStateFlow<VodStream?>(null)
        var value: VodStream?
            get() = flow.value
            set(v) {
                flow.value = v?.let(::withPicks)?.let(::fromStartIfAsked)
                // No intro / credits markers from its server (addons, IPTV, Plex without detection): ask the community
                // intro databases, wherever it's from (Settings → Playback → Episodes → Skip intros / credits).
                val s = flow.value ?: return
                if (s.markers.isNotEmpty() || FoundMarkers.byUrl.containsKey(s.url)) return
                val w = s.watch ?: return
                if (w.imdbId == null && w.tmdbId == null) return
                viewModelScope.launch {
                    val pb = playbackStore.current()
                    if (pb.skipIntro == SkipMode.Off && pb.skipCredits == SkipMode.Off) return@launch
                    val found = runCatching { repo.communityMarkers(w) }.getOrDefault(emptyList())
                    if (found.isNotEmpty()) FoundMarkers.byUrl[s.url] = found
                }
            }
    }
    private val _playing = Playing()
    val playing = _playing.flow.asStateFlow()
    /**
     * The episode before S[s] E[e]: the one before in the season, else the previous season's last (from the episode
     * lists your servers / IPTV have, else TMDB's season sizes). Null for the very first.
     */
    private fun previousEpisode(s: Int, e: Int): EpisodeInfo? {
        val d = _detail.value
        fun named(season: Int, number: Int) = d?.episodes?.firstOrNull { it.season == season && it.number == number }
            ?: EpisodeInfo(season, number, "Episode $number", null, null, null, null)
        if (e > 1) return named(s, e - 1)
        if (s <= 1 || d == null) return null
        val known = (d.plexEpisodes.keys + d.serverEpisodes.keys + d.providerEpisodes.keys).filter { it.first == s - 1 }.maxOfOrNull { it.second }
        val last = known ?: d.seasons.firstOrNull { it.number == s - 1 }?.episodes
        return last?.takeIf { it > 0 }?.let { named(s - 1, it) }
    }

    /** This one set NextUp (the next episode and the row of what's next) for what it's playing. */
    private var ownsNextUp = false
    /** What it was playing when it last filled NextUp (its lambdas, which hold this ViewModel, are for that). */
    private var nextUpUrl: String? = null

    // Gone: NextUp (an app-wide object) lets go of this one's play lambdas, which held it and its title page, unless
    // another has filled it since (for something else playing).
    override fun onCleared() {
        val nu = NextUp
        if (nextUpUrl != null && (nu.forUrl == null || nu.forUrl == nextUpUrl)) {
            nu.play = {}; nu.playEpisode = {}; nu.playPrevious = {}
            nu.episode = null; nu.upcoming = emptyList(); nu.previous = null
        }
        super.onCleared()
    }
    // An episode starts: find the one after it now, so the player can offer it over the credits (Skip credits plays
    // it; the countdown starts before the end) rather than only after the episode has closed.
    /** The title that's playing (set as playback starts): its page is what reopens if Android clears Viewarr. */
    var playingItem: MediaItem? = null
        private set

    init {
        viewModelScope.launch {
            playing.collect { stream ->
                playingItem = if (stream != null) playingItem ?: _detail.value?.detail?.item else null
                val w = stream?.watch
                val s = w?.season; val e = w?.episode
                if (stream == null || w?.type != MediaType.Show || s == null || e == null) {
                    // Something else playing (a film) clears it; nothing playing clears it only in the one that filled it:
                // Movies, Shows, Search… each watch their own playing, and one starting up (playing nothing) cleared a
                // show's next episodes just after they'd been found on its first play.
                if ((stream != null && NextUp.forUrl != stream.url) || (stream == null && ownsNextUp)) { ownsNextUp = false; NextUp.previous = null; NextUp.episode = null; NextUp.upcoming = emptyList(); NextUp.forUrl = stream?.url }
                    return@collect
                }
                NextUp.forUrl = stream.url
                ownsNextUp = true
                nextUpUrl = stream.url
                // The episode before (the Previous episode button).
                val prev = previousEpisode(s, e)
                NextUp.previous = prev
                NextUp.playPrevious = { prev?.let { playNextOf(it) } }
                NextUp.episode = null
                NextUp.upcoming = emptyList()
                // The row of what's next (Down in the player): found alongside the next episode.
                viewModelScope.launch {
                    val list = runCatching { findUpcoming(s, e) }.getOrDefault(emptyList())
                    if (NextUp.forUrl != stream.url) return@launch
                    NextUp.fallbackArt = _detail.value?.detail?.item?.backdrop
                    NextUp.playEpisode = { ep -> playNextEpisode(ep) }
                    NextUp.current = s to e
                    NextUp.startAt = list.indexOfFirst { it.season == s && it.number == e }
                        .takeIf { it >= 0 } ?: list.indexOfFirst { it.season > s || (it.season == s && it.number > e) }.coerceAtLeast(0)
                    NextUp.upcoming = list
                }
                val next = runCatching { findNext(s, e) }.getOrNull() ?: return@collect
                if (NextUp.forUrl != stream.url) return@collect
                NextUp.play = { playNextEpisode(next) }
                NextUp.episode = next
            }
        }
    }
    // Watching a dropped show again brings it back (here and on Trakt): no list to look after, carrying on is enough.
    init {
        viewModelScope.launch {
            playing.collect { stream ->
                val w = stream?.watch ?: return@collect
                if (w.type != MediaType.Show || droppedShows.value.isEmpty()) return@collect
                val title = _detail.value?.detail?.item?.title
                droppedShows.value.filter { (key, name) ->
                    (w.tmdbId != null && key == "tmdb:${w.tmdbId}") || (w.imdbId != null && key == "imdb:${w.imdbId}") || (title != null && name.equals(title, ignoreCase = true))
                }.forEach { (key, name) -> undropShow(key, name) }
            }
        }
    }

    /** From the start (the page's button): the next film or episode Play starts begins at 0, not where you left off.
     *  Only for a minute after pressing it, so a Play that found nothing doesn't affect a later one. */
    private var fromStartAt = 0L
    private fun fromStartIfAsked(v: VodStream): VodStream {
        if (System.currentTimeMillis() - fromStartAt > 60_000) return v
        fromStartAt = 0L
        return v.copy(startMs = 0L, fallback = v.fallback?.copy(startMs = 0L))
    }

    /** Play from the very beginning: the film, or (shows) the episode Resume would carry on. */
    fun playFromStart() {
        val d = _detail.value ?: return
        fromStartAt = System.currentTimeMillis()
        if (d.detail.item.type == MediaType.Show) playNextUp() else playBest()
    }

    /** The picks apply only to the copy the tracks were read from (another source numbers its tracks differently). */
    private fun withPicks(v: VodStream): VodStream {
        val d = _detail.value ?: return v
        val t = d.tracks ?: return v
        if (d.pickedAudio == null && d.pickedSubtitle == null) return v
        val w = v.watch
        val same = (t.source == "Plex" && w?.plexRatingKey == t.ref) || (t.source != "Plex" && w?.serverItemId == t.ref)
        if (!same) return v
        fun apply(x: VodStream) = x.copy(audioTrack = d.pickedAudio, subtitleTrack = d.pickedSubtitle)
        return apply(v).copy(fallback = v.fallback?.let(::apply))
    }

    /** From a title page: a cast member's films and shows (the title page closes). */
    fun openCastMember(c: CastMember) {
        val id = c.id ?: return
        // Leave every title page (not back to the title before it): the person's page takes over.
        detailStack.clear()
        close()
        // (From a title opened in a library: the library closes too, or the person's page was hidden behind it.)
        _browse.value = null
        open(MediaItem(key = "person:$id", type = MediaType.Movie, title = c.name, poster = c.photo, origin = "TMDB"))
    }

    /** IMDb / Rotten Tomatoes (your Plex) and Trakt ratings for a title, cached for a while. */
    suspend fun ratingsFor(item: MediaItem): List<RatingBadge> = runCatching {
        val known = if (item.tmdbId == null && item.imdbId == null) item else item
        repo.extraRatings(known, repo.copiesOf(known))
    }.getOrDefault(emptyList())

    /** A title's logo (TMDB), cached; null when there isn't one. */
    suspend fun logoFor(item: MediaItem): String? = runCatching { repo.logoFor(item) }.getOrNull()

    /** The playing title's backdrop and logo: the backdrop at once, the logo once TMDB answers. */
    private val _playerArt = MutableStateFlow<PlayerArt?>(null)
    val playerArt = _playerArt.asStateFlow()

    init {
        viewModelScope.launch {
            _playing.flow.map { it?.url }.distinctUntilChanged().collect { url ->
                // (url first: the first, empty value arrives while this class is still being built.)
                if (url == null) { _playerArt.value = null; return@collect }
                val item = _detail.value?.detail?.item ?: run { _playerArt.value = null; return@collect }
                val d = _detail.value
                val genres = d?.detail?.genres.orEmpty()
                // The episode playing (for a show): its name, synopsis and runtime.
                val target = _playing.value?.watch
                val ep = if (item.type == MediaType.Show && target?.season != null && target.episode != null) {
                    d?.episodes?.firstOrNull { it.season == target.season && it.number == target.episode }
                        ?: runCatching { repo.seasonEpisodes(item, target.season, d?.plexShowKey).firstOrNull { it.number == target.episode } }.getOrNull()
                } else null
                val facts = listOfNotNull(
                    item.year?.toString(),
                    item.rating?.takeIf { it > 0 && item.origin !in setOf("Plex", "Jellyfin", "Emby", "IPTV") }?.let { "TMDB %.1f".format(it) },
                    genres.take(3).joinToString(", ").ifBlank { null },
                    (ep?.runtimeMin ?: d?.detail?.runtimeMin)?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                ).joinToString("  ·  ").ifBlank { null }
                val art = PlayerArt(
                    item.backdrop, null,
                    poster = item.poster,
                    episodeLine = ep?.let { "S${it.season} · E${it.number}  ${it.name}" } ?: target?.let { t -> if (t.season != null && t.episode != null) "S${t.season} · E${t.episode}" else null },
                    facts = facts,
                    overview = ep?.overview?.takeIf { it.isNotBlank() } ?: item.overview,
                )
                if (_playing.value?.url != url) return@collect
                _playerArt.value = art
                val logo = runCatching { repo.logoFor(item) }.getOrNull() ?: return@collect
                if (_playing.value?.url == url) _playerArt.value = art.copy(logo = logo)
            }
        }
    }

    /** A row picked from the menu's catalogue list: the screen scrolls to it and focuses its first poster. */
    private val _jump = MutableStateFlow<Pair<String?, Long>?>(null)
    val jump = _jump.asStateFlow()

    /** The row that last had focus: the menu's catalogue list highlights it. */
    private val _currentRow = MutableStateFlow<String?>(null)
    val currentRow = _currentRow.asStateFlow()
    fun setCurrentRow(id: String) { if (_currentRow.value != id) _currentRow.value = id }

    fun jumpTo(rowId: String?) { rowSet.value = null; openedFromMenu.value = rowId; _jump.value = rowId to System.nanoTime() }
    fun jumpDone() { _jump.value = null }
    /** To a row of the set on show (a service's Popular, New…), keeping the set. */
    fun jumpWithin(rowId: String) { openedFromMenu.value = rowId; _jump.value = rowId to System.nanoTime() }

    private var loaded: MediaType? = null
    private var rowsJob: Job? = null
    private var detailJob: Job? = null

    /** Search: the looked-at pill's kind first (your library, or rows that are mostly films / shows); others as they were. */
    private fun focusFirst(rows: List<MediaRow>, focus: SearchFilter?): List<MediaRow> {
        fun mostly(r: MediaRow, t: MediaType) = r.items.isNotEmpty() && r.items.count { it.type == t } * 2 > r.items.size
        val first: (MediaRow) -> Boolean = when (focus) {
            SearchFilter.Library -> { r -> r.id == "search-library" }
            SearchFilter.Films -> { r -> r.id != "search-people" && mostly(r, MediaType.Movie) }
            SearchFilter.Shows -> { r -> r.id != "search-people" && mostly(r, MediaType.Show) }
            else -> return rows
        }
        return rows.sortedBy { if (first(it)) 0 else 1 }
    }

    /**
     * Search results in the order you most likely meant: a person's name puts People first (unless a film or show has
     * that exact title); otherwise your library, then films and shows, as they come.
     */
    private fun orderSearch(rows: List<MediaRow>): List<MediaRow> {
        val q = lastQuery.trim()
        if (q.length < 2) return rows
        val titleHit = rows.filter { it.id != "search-people" }.any { r -> r.items.any { it.title.equals(q, ignoreCase = true) } }
        val personHit = rows.firstOrNull { it.id == "search-people" }?.items?.firstOrNull()?.title?.equals(q, ignoreCase = true) == true
        return if (personHit && !titleHit) rows.sortedBy { if (it.id == "search-people") 0 else 1 } else rows
    }

    /** Search mode: the rows become search results. */
    fun search(query: String) {
        val q = query.trim()
        if (q == lastQuery && q.isNotEmpty() && _person.value == null) return
        lastQuery = q
        _person.value = null
        resultsBeforePerson = null
        loaded = null
        layoutType.value = null
        rowsJob?.cancel()
        if (q.length < 2) {
            _rows.value = RowsState(emptyList(), done = true)
            return
        }
        _rows.value = RowsState(emptyList(), done = false)
        rowsJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500) // typing pause
            repo.search(q).collect { _rows.value = it }
        }
    }

    init {
        // Switching an addon on or off in Settings → Addons reloads the rows, so its catalogues come or go at once.
        viewModelScope.launch {
            store.accounts
                .map { a -> a.activeAddons.map { it.baseUrl } }
                .distinctUntilChanged()
                .drop(1)
                .collect { loaded?.let { type -> load(type, force = true) } }
        }
    }

    fun load(type: MediaType, force: Boolean = false) {
        if (!force && loaded == type) return
        loaded = type
        layoutType.value = type
        rowsJob?.cancel()
        _rows.value = RowsState(emptyList(), done = false)
        rowsJob = viewModelScope.launch {
            // The rows as last loaded (kept on the box: data/media/RowsCache) straight away, then the fresh ones as
            // they come; until those are all in, rows not loaded yet keep their saved copy, so nothing blinks out.
            val profile = runCatching { kotlin.math.abs(store.current().hashCode()).toString(36) }.getOrDefault("x")
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.petebutty.viewarr.data.media.RowsCache.read(appContext, type, profile) }
            if (saved != null && _rows.value.rows.isEmpty()) _rows.value = RowsState(saved, done = false)
            // With saved rows on screen, the full refresh waits (it was the memory burst a few seconds into every
            // start, 2026-11-18): saved under 30 minutes ago, until a minute into the app; older, until 15 seconds in.
            // (A forced reload, e.g. after changing addons or signing in, never waits.)
            if (saved != null && !force) {
                val age = io.github.petebutty.viewarr.data.media.RowsCache.ageMs(appContext, type, profile) ?: Long.MAX_VALUE
                io.github.petebutty.viewarr.ui.common.AppStart.after(if (age < 30 * 60_000L) 60_000 else 15_000)
            }
            repo.rows(type).collect { fresh ->
                _rows.value = if (fresh.done || saved == null) fresh
                    else fresh.copy(rows = fresh.rows + saved.filter { s -> fresh.rows.none { it.id == s.id } })
                if (fresh.done && fresh.rows.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.github.petebutty.viewarr.data.media.RowsCache.write(appContext, type, profile, fresh.rows)
                }
            }
        }
    }

    // ---- Search: a person's films and shows, in place of the results until Back

    private val _person = MutableStateFlow<String?>(null)
    /** The person whose films and shows are showing (search), or null. */
    val person = _person.asStateFlow()
    private var resultsBeforePerson: RowsState? = null

    private val _personInfo = MutableStateFlow<PersonInfo?>(null)
    /** The person whose films are showing: their photo, dates and biography for the header. */
    val personInfo = _personInfo.asStateFlow()

    private fun openPerson(item: MediaItem) {
        val id = item.key.removePrefix("person:")
        val placeholder = PersonInfo(item.title, item.poster, null, null, null, null, null, loading = true)
        _personInfo.value = placeholder
        viewModelScope.launch {
            val info = runCatching { repo.personInfo(id, item.title) }.getOrNull() ?: placeholder.copy(loading = false)
            if (_person.value == item.title) _personInfo.value = info
        }
        if (_person.value == null) resultsBeforePerson = _rows.value
        _person.value = item.title
        rowsJob?.cancel()
        _rows.value = RowsState(emptyList(), done = false)
        rowsJob = viewModelScope.launch {
            val rows = runCatching { repo.personCredits(id, item.title) }.getOrDefault(emptyList())
            _rows.value = RowsState(rows, done = true)
        }
    }

    /** Back from a person: the search results again. */
    fun closePerson() {
        if (_person.value == null) return
        rowsJob?.cancel()
        _person.value = null
        _personInfo.value = null
        _rows.value = resultsBeforePerson ?: RowsState(emptyList(), done = true)
        resultsBeforePerson = null
    }

    /** Quick picks under the search box (titles and names that match what's typed). */
    suspend fun suggestions(q: String): List<String> = if (q.trim().length < 2) emptyList() else runCatching { repo.suggestions(q.trim()) }.getOrDefault(emptyList())

    /** Continue watching for this type (every server), for the rows under the info bars; refreshed after 2 min. */
    private val _continueItems = MutableStateFlow<List<MediaItem>?>(null)
    val continueItems = kotlinx.coroutines.flow.combine(_continueItems, droppedShows) { items, dropped -> items?.filterNot { isDropped(it, dropped) } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    private var continueAt = 0L
    fun loadContinue(type: MediaType, force: Boolean = false) {
        if (!force && _continueItems.value != null && System.currentTimeMillis() - continueAt < 2 * 60_000L) return
        continueAt = System.currentTimeMillis()
        viewModelScope.launch { _continueItems.value = runCatching { repo.continueWatching(type) }.getOrDefault(emptyList()) }
    }

    /** Continue watching from Trakt (shows you've watched lately, at their next episode), for the rows under the info bars; refreshed after 5 min. */
    private val _traktContinue = MutableStateFlow<List<MediaItem>?>(null)
    val traktContinue = kotlinx.coroutines.flow.combine(_traktContinue, droppedShows) { items, dropped -> items?.filterNot { isDropped(it, dropped) } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    private var traktContinueAt = 0L
    fun loadTraktContinue(type: MediaType) {
        if (_traktContinue.value != null && System.currentTimeMillis() - traktContinueAt < 5 * 60_000L) return
        traktContinueAt = System.currentTimeMillis()
        viewModelScope.launch { _traktContinue.value = runCatching { repo.traktContinue(type) }.getOrDefault(emptyList()) }
    }

    /** The title playing's "More like this" (its page's recommendations), for the film player's rows. */
    fun similarToPlaying(): List<MediaItem> = _detail.value?.detail?.similar.orEmpty()

    /** The menu's Movies / Shows panel: newest titles in your libraries (loaded once, refreshed every 10 min). */
    private val _recent = MutableStateFlow<List<MediaItem>?>(null)
    val recent = _recent.asStateFlow()
    private var recentAt = 0L
    fun loadRecent(type: MediaType) {
        if (_recent.value != null && System.currentTimeMillis() - recentAt < 10 * 60_000L) return
        recentAt = System.currentTimeMillis()
        viewModelScope.launch { _recent.value = runCatching { repo.recentlyAdded(type) }.getOrDefault(emptyList()) }
    }

    // ---- Browsing a whole library (menu → Movies / Shows → a library), one page at a time

    /**
     * A library being browsed: one continuous grid of [total] titles, of which only the stretches near where you
     * are ([pages] of [LIBRARY_PAGE]) are held, so a big library stays light on a weak box and A–Z can jump anywhere.
     */
    data class LibraryBrowse(
        val library: MediaLibrary,
        val type: MediaType,
        val query: LibraryQuery,
        val options: LibraryOptions? = null,
        val total: Int = -1,
        val pages: Map<Int, List<MediaItem>> = emptyMap(),
        val failed: Boolean = false,
        /** Where each first letter starts (sorted by title), when the server can say. */
        val letters: Map<Char, Int>? = null,
        /** Each filter's choices once looked up (null while loading). */
        val values: Map<String, List<FilterValue>?> = emptyMap(),
        /** A–Z jump: the position to go to (and focus). */
        val jump: Int? = null,
        /** Changes whenever the sort, filters or search change: the grid starts again from the top. */
        val version: Int = 0,
    ) {
        fun item(i: Int): MediaItem? = pages[i / LIBRARY_PAGE]?.getOrNull(i % LIBRARY_PAGE)
        val items: List<MediaItem> get() = pages.keys.sorted().flatMap { pages.getValue(it) }
        val filtered get() = query.filtered
        /** The A–Z strip: sorted by title, A first, where the server can find a letter. */
        val byTitle get() = options?.let { o -> query.sort == o.titleSort && !query.desc && library.source != PROVIDER_LIBRARY } == true
        /** The A–Z strip is there whatever the sort (picking a letter sorts by title); not for IPTV lists. */
        val canLetters get() = options?.letters == true && library.source != PROVIDER_LIBRARY
    }

    private val _libraries = MutableStateFlow<List<MediaLibrary>?>(null)
    private var librariesFor: MediaType? = null
    private val libType = MutableStateFlow<MediaType?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val libLayout = libType.flatMapLatest { t -> if (t == null) kotlinx.coroutines.flow.flowOf(RowLayout()) else rowLayouts.libraryLayout(t) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val libNames = libType.flatMapLatest { t -> if (t == null) kotlinx.coroutines.flow.flowOf(emptyMap()) else rowLayouts.libraryNames(t) }
    /** The libraries with your own names applied (Settings → Home page & menu → Menu list → hold OK). */
    private val namedLibraries = kotlinx.coroutines.flow.combine(_libraries, libNames) { libs, names ->
        libs?.map { l -> names[l.key]?.let { l.copy(title = it) } ?: l }
    }
    /** Every library in the chosen order, with whether it's hidden (Settings → Home page & menu). */
    val allLibraries: kotlinx.coroutines.flow.StateFlow<List<Pair<MediaLibrary, Boolean>>?> =
        kotlinx.coroutines.flow.combine(namedLibraries, libLayout) { libs, l -> libs?.let { l.orderedBy(it) { x -> x.key }.map { x -> x to (x.key in l.hidden) } } }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    /** The libraries the menu lists (null until loaded). */
    val libraries: kotlinx.coroutines.flow.StateFlow<List<MediaLibrary>?> =
        kotlinx.coroutines.flow.combine(namedLibraries, libLayout) { libs, l -> libs?.let { l.orderedBy(it) { x -> x.key }.filter { x -> x.key !in l.hidden } } }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    fun setLibraryName(key: String, name: String?) { val t = libType.value ?: return; viewModelScope.launch { rowLayouts.setLibraryName(t, key, name) } }
    fun setLibraryHidden(key: String, hidden: Boolean) { val t = libType.value ?: return; viewModelScope.launch { rowLayouts.setLibraryHidden(t, key, hidden) } }
    fun setLibraryOrder(keys: List<String>) { val t = libType.value ?: return; viewModelScope.launch { rowLayouts.setLibraryOrder(t, keys) } }
    fun loadLibraries(type: MediaType, force: Boolean = false) {
        libType.value = type
        if (!force && librariesFor == type && _libraries.value != null) return
        librariesFor = type
        viewModelScope.launch { _libraries.value = runCatching { repo.libraries(type) }.getOrDefault(emptyList()) }
    }

    private val _browse = MutableStateFlow<LibraryBrowse?>(null)
    val browse = _browse.asStateFlow()
    private var browseJob: kotlinx.coroutines.Job? = null
    private val pageJobs = HashMap<Int, kotlinx.coroutines.Job>()
    /** The last title focused in the open library (to come back to it), per [LibraryBrowse.version]. */
    var lastIndex = 0
    private var lastIndexVersion = -1
    fun setLastIndex(version: Int, i: Int) { lastIndex = i; lastIndexVersion = version }
    fun lastIndexFor(version: Int) = if (lastIndexVersion == version) lastIndex else null


    /** The list of collections / playlists a collection was opened from (Back goes there, not out of the library). */
    private var browseParent: MediaLibrary? = null
    /** The list entry a collection was opened from (e.g. "Collections"), for the menu to land on. */
    val browseParentKey: String? get() = browseParent?.key

    /** Back from a collection opened from your list of them: that list again (true); otherwise false. */
    fun libraryBack(type: MediaType): Boolean {
        val up = browseParent ?: return false
        openLibrary(up, type)
        return true
    }

    /** A collection's own row on this page (hold OK on its card): whether it's there, and adding / removing it. */
    fun hasCollectionRow(item: MediaItem): Boolean {
        val id = "col-" + item.key.removePrefix("col:")
        return orderedRows.value.first.any { it.id == id } && id !in orderedRows.value.second
    }
    fun setCollectionRow(item: MediaItem, on: Boolean) {
        val t = layoutType.value ?: return
        viewModelScope.launch {
            rowLayouts.setHidden(t, "col-" + item.key.removePrefix("col:"), !on)
            load(t, force = true)
        }
    }

    fun openLibrary(library: MediaLibrary?, type: MediaType, parent: MediaLibrary? = null) {
        browseJob?.cancel(); queryJob?.cancel(); pageJobs.values.forEach { it.cancel() }; pageJobs.clear()
        browseParent = parent?.takeIf { library != null && it.key != library.key }
        if (library == null) { _browse.value = null; return }
        val saved = libraryViews.load(library.key)
        val start = LibraryBrowse(library, type, saved ?: LibraryQuery("", true), version = nextVersion())
        _browse.value = start
        browseJob = viewModelScope.launch {
            val options = runCatching { repo.libraryOptions(library, type) }.getOrNull()
                ?: LibraryOptions(emptyList(), emptyList(), false, "")
            val ids = options.sorts.map { it.id }
            // A saved sort this library no longer has (or none yet): newest releases first, as before.
            val q = saved?.takeIf { it.sort in ids } ?: run {
                // (Collections and playlists: in their own order.)
                val def = (if (library.kind != LibKind.Library) null else options.sorts.firstOrNull { it.id in setOf("originallyAvailableAt", "PremiereDate", "Released") }) ?: options.sorts.firstOrNull()
                // (Keeping any filters saved with it, e.g. a category picked in the menu before the first visit.)
                LibraryQuery(def?.id ?: "", def?.descFirst ?: true, filters = saved?.filters.orEmpty(), toggles = saved?.toggles.orEmpty())
            }
            if (_browse.value?.library?.key != library.key) return@launch
            reload(start.copy(options = options, query = q))
        }
    }

    private var versions = 0
    private fun nextVersion() = ++versions

    /** The sort, filters or search changed: from the top again. */
    private fun reload(b: LibraryBrowse) {
        pageJobs.values.forEach { it.cancel() }; pageJobs.clear()
        val fresh = b.copy(total = -1, pages = emptyMap(), failed = false, letters = null, jump = null, version = nextVersion())
        _browse.value = fresh
        libraryViews.save(fresh.library.key, fresh.query)
        loadPage(fresh, 0)
        if (fresh.byTitle && fresh.options?.letters == true) viewModelScope.launch {
            val idx = runCatching { repo.letterIndex(fresh.library, fresh.type, fresh.query) }
                .onFailure { android.util.Log.w("Library", "letter index failed", it) }.getOrNull()
            _browse.update { if (it?.version == fresh.version) it.copy(letters = idx) else it }
        }
    }

    private fun loadPage(b: LibraryBrowse, page: Int) {
        if (page in b.pages || pageJobs[page]?.isActive == true) return
        if (b.total >= 0 && page * LIBRARY_PAGE >= b.total) return
        pageJobs[page] = viewModelScope.launch {
            val got = runCatching { repo.libraryPage(b.library, b.type, b.query, page * LIBRARY_PAGE, LIBRARY_PAGE) }.getOrNull()
            _browse.update { now ->
                if (now == null || now.version != b.version) now
                else if (got == null) (if (now.pages.isEmpty()) now.copy(failed = true, total = maxOf(now.total, 0)) else now)
                else now.copy(pages = now.pages + (page to got.first), total = got.second, failed = false)
            }
        }
    }

    /** The grid shows positions [first]..[last]: their stretches are loaded; ones far away are let go. */
    fun ensureRange(first: Int, last: Int) {
        val b = _browse.value ?: return
        val p0 = (first / LIBRARY_PAGE - 1).coerceAtLeast(0)
        val p1 = last / LIBRARY_PAGE + 1
        for (p in p0..p1) loadPage(b, p)
        val far = b.pages.keys.filter { it < p0 - 4 || it > p1 + 4 }
        if (far.isNotEmpty()) _browse.update { if (it?.version == b.version) it.copy(pages = it.pages - far.toSet()) else it }
    }

    /** A sort picked: the same one again turns it round. */
    fun setLibrarySort(id: String) {
        val b = _browse.value ?: return
        val o = b.options ?: return
        val opt = o.sorts.firstOrNull { it.id == id } ?: return
        val q = if (b.query.sort == id) (if (o.directions) b.query.copy(desc = !b.query.desc) else b.query) else b.query.copy(sort = id, desc = opt.descFirst)
        reload(b.copy(query = q))
    }
    fun setLibraryDesc(desc: Boolean) {
        val b = _browse.value ?: return
        if (b.query.desc != desc && b.options?.directions == true) reload(b.copy(query = b.query.copy(desc = desc)))
    }

    /** A filter's choices (looked up the first time it's opened). */
    fun loadFilterValues(group: String) {
        val b = _browse.value ?: return
        if (b.values.containsKey(group)) return
        _browse.update { it?.copy(values = it.values + (group to null)) }
        viewModelScope.launch {
            val got = runCatching { repo.filterValues(b.library, b.type, group) }.getOrDefault(emptyList())
            _browse.update { if (it?.library?.key == b.library.key) it.copy(values = it.values + (group to got)) else it }
        }
    }
    fun toggleFilterValue(group: String, value: FilterValue) {
        val b = _browse.value ?: return
        val multi = b.options?.filters?.firstOrNull { it.id == group }?.multi != false
        val cur = b.query.filters[group].orEmpty()
        val next = when {
            cur.any { it.id == value.id } -> cur.filter { it.id != value.id }
            multi -> cur + value
            else -> listOf(value)
        }
        val filters = if (next.isEmpty()) b.query.filters - group else b.query.filters + (group to next)
        reload(b.copy(query = b.query.copy(filters = filters)))
    }
    fun toggleFlag(id: String) {
        val b = _browse.value ?: return
        val t = b.query.toggles
        reload(b.copy(query = b.query.copy(toggles = if (id in t) t - id else t + id)))
    }
    fun clearFilters() {
        val b = _browse.value ?: return
        if (b.query.active > 0) reload(b.copy(query = b.query.copy(filters = emptyMap(), toggles = emptySet())))
    }

    private var queryJob: kotlinx.coroutines.Job? = null
    /** Search this library, as you type (short pause first). */
    fun setLibraryQuery(q: String) {
        val b = _browse.value ?: return
        if (b.query.query == q) return
        _browse.value = b.copy(query = b.query.copy(query = q))
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            kotlinx.coroutines.delay(450)
            _browse.value?.let { reload(it) }
        }
    }

    /** A–Z strip: to the first title starting with [letter] (sorting by title A→Z first, keeping the filters). */
    fun jumpToLetter(letter: Char) {
        val b0 = _browse.value ?: return
        viewModelScope.launch {
            var b = b0
            if (!b.byTitle) {
                val o = b.options ?: return@launch
                reload(b.copy(query = b.query.copy(sort = o.titleSort, desc = false)))
                val v = _browse.value?.version
                // Wait for the re-sorted list (and, on Plex, where each letter starts).
                b = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    _browse.first { it == null || it.version != v || (it.total >= 0 && (it.letters != null || it.library.server == null)) }
                } ?: return@launch
                if (b.version != v) return@launch
            }
            val known = b.letters
            val at = if (known != null) {
                if (letter == '#') 0 else known.entries.firstOrNull { it.key != '#' && it.key >= letter }?.value ?: (b.total - 1)
            } else runCatching { repo.letterStart(b.library, b.type, b.query, letter) }.getOrNull() ?: return@launch
            val now = _browse.value ?: return@launch
            if (now.version != b.version) return@launch
            val idx = at.coerceIn(0, (now.total - 1).coerceAtLeast(0))
            loadPage(now, idx / LIBRARY_PAGE)
            _browse.value = now.copy(jump = idx)
        }
    }
    fun letterJumpDone() { _browse.update { it?.copy(jump = null) } }

    /** Picks up your other Plex servers (e.g. a second machine) so their titles count as yours. */
    fun refreshPlexServers() { viewModelScope.launch { runCatching { repo.refreshPlexServers() } } }

    /** The TV home screen's Watch Next row (Continue watching), brought up to date. */
    fun refreshWatchNext() { viewModelScope.launch { watchNext.refresh() } }

    /** Titles opened from inside a title page (More like this): Back goes back through them, one at a time. */
    // The title pages underneath (opened from More like this), kept as they were loaded: Back shows the one below
    // as it was, not reloaded with the highlight back on Play (UI review, 2026-11-18).
    private val detailStack = ArrayList<DetailUi>()
    /** The More like this poster Back came from: the restored page puts the highlight there, then clears this. */
    private val _returnToSimilar = androidx.compose.runtime.mutableStateOf<String?>(null)
    var returnToSimilar: String?
        get() = _returnToSimilar.value
        set(v) { _returnToSimilar.value = v }

    /** From the guide's Go to show / film: its page; a show opens on its episodes, at the one you're up to (S1 E1 if none). */
    suspend fun goToTitle(title: String, season: Int?, episode: Int?, year: Int? = null): Boolean = watchFromStart(title, season, episode, year, play = false)

    /**
     * From the guide: a programme you've missed the start of. Finds its film (or the show's episode), opens its page
     * and plays it from the best source, as Play would. False when no match was found (search shows the title).
     */
    suspend fun watchFromStart(title: String, season: Int?, episode: Int?, year: Int? = null, play: Boolean = true): Boolean {
        val item = runCatching { repo.findTitle(title, show = season != null || episode != null, year = year, season = season) }.getOrNull() ?: return false
        open(item)
        val ready = kotlinx.coroutines.withTimeoutOrNull(25_000) {
            _detail.first { it == null || (it.detail.item.key == item.key && it.copies != null && !it.loading) }
        } ?: return true
        if (ready.detail.item.key != item.key) return true
        if (item.type == MediaType.Movie) { if (play) playBest(); return true }
        // Watch from the start: that very episode, from your servers or addons.
        if (play && season != null && episode != null) { playBestEpisode(season, episode); return true }
        // A show: where you're up to (Plex / Jellyfin / Emby / Trakt), once that's known.
        val up = kotlinx.coroutines.withTimeoutOrNull(10_000) { _detail.first { it == null || it.detail.item.key != item.key || it.nextUpChecked } }
            ?.takeIf { it.detail.item.key == item.key }?.upTo()
        val (ns, ne) = up?.let { it.first to it.second } ?: (1 to 1)
        // The guide's episode only if you're up to it (it's your next one, or you've watched past it); otherwise the
        // episodes, at the season you got up to, so you don't skip ahead.
        openEpisodes(); selectSeason(ns)
        return true
    }

    fun open(item: MediaItem) {
        if (item.key.startsWith("person:")) return openPerson(item)
        // A collection / playlist card: its titles, like a library (Back returns to the list it was picked from).
        if (item.key.startsWith("col:")) {
            repo.groupFor(item.key)?.let { g -> openLibrary(g, libType.value ?: layoutType.value ?: item.type, parent = _browse.value?.library) }
            return
        }
        _detail.value?.takeIf { it.detail.item.key != item.key }?.let { detailStack.add(it) }
        showDetail(item)
    }

    /**
     * The one play request being worked on (Play, Play from, an episode, an addon search). A new one replaces it, and
     * leaving the title page drops it: a slow addon search mustn't start a film later over something else.
     */
    private var playJob: Job? = null
    private fun launchPlay(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        playJob?.cancel()
        playJob = viewModelScope.launch(block = block)
    }

    /** Plays [stream] only if this request is still wanted and the page is still that title's. */
    private suspend fun playIfCurrent(key: String, stream: VodStream): Boolean {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (_detail.value?.detail?.item?.key != key) return false
        _playing.value = stream
        return true
    }

    /** A network / parse call that mustn't crash the app: null on failure (cancelling still cancels). */
    private inline fun <T> safely(block: () -> T): T? = try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w("MediaViewModel", "call failed", e); null }

    private fun showDetail(item: MediaItem) {
        detailJob?.cancel()
        playJob?.cancel()
        _detail.value = DetailUi(MediaDetail(item))
        detailJob = viewModelScope.launch {
            // How long each part takes (logcat "TitleLoad"): the page used to wait on each in turn.
            val t0 = android.os.SystemClock.elapsedRealtime()
            fun took(what: String) = android.util.Log.i("TitleLoad", "$what after ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val accounts = store.current()
            val hasTrakt = accounts.trakt != null
            _detail.update { it?.copy(hasAddons = accounts.activeAddons.isNotEmpty()) }
            // Your copies don't need TMDB's details when the title already has its TMDB id: ask for both at once.
            val earlyCopies = if (item.tmdbId != null) async { runCatching { repo.copiesOf(item) }.getOrNull() } else null
            val full = safely { repo.detail(item) } ?: run {
                _detail.update { if (it?.detail?.item?.key == item.key) it.copy(loading = false, message = "Couldn't load this title's details. Try again.") else it }
                return@launch
            }
            took("details")
            val show = full.item.type == MediaType.Show
            _detail.update {
                it?.copy(detail = full, loading = false, hasTrakt = hasTrakt && full.item.tmdbId != null,
                    // A show's seasons (TMDB's) straight away, so the episodes can start loading.
                    seasons = if (show && full.seasons.isNotEmpty()) full.seasons else it.seasons)
            }
            launch {
                val on = runCatching { repo.inWatchlist(full.item) }.getOrNull()
                _detail.update { if (it?.detail?.item?.key == full.item.key) it.copy(watchlisted = on == true, canWatchlist = on != null) else it }
            }
            // Home servers first (then away, then IPTV): the order they're listed in and tried by Play.
            val copies = (earlyCopies?.await() ?: runCatching { repo.copiesOf(full.item) }.getOrDefault(emptyList())).let { list ->
                val home = list.associateWith { c -> runCatching { repo.isHomeCopy(c) }.getOrDefault(false) }
                list.sortedWith(compareBy({ if (home[it] == true) 0 else if (it.source == PROVIDER) 2 else 1 }))
            }
            took("copies (${copies.size})")
            _detail.update { it?.copy(copies = copies) }
            if (full.item.type == MediaType.Movie) launch {
                val w = filmWatchedNow(full.item, copies)
                _detail.update { if (it?.detail?.item?.key == full.item.key) it.copy(watched = w) else it }
            }
            if (full.item.type == MediaType.Movie) launch {
                val all = runCatching { repo.titleTracks(copies) }.getOrNull()
                // Audio & subtitles only when there's a choice; the badges whatever the file has.
                val t = all?.takeIf { it.audio.size > 1 || it.subtitles.isNotEmpty() }
                _detail.update { if (it?.detail?.item?.key == full.item.key) it.copy(tracks = t, media = all) else it }
            }
            val pal = runCatching { themeStore.palette.first() }.getOrNull()
            val hidden = pal?.titleHidden.orEmpty()
            val wantRatings = pal?.lowMemory != true && TitleParts.ratings.any { (src, _) -> src != "TMDB" && TitleParts.rating(src) !in hidden }
            if (wantRatings) launch {
                val ratings = runCatching { repo.extraRatings(full.item, copies) }.getOrDefault(emptyList())
                _detail.update { if (it?.detail?.item?.key == full.item.key) it.copy(ratings = ratings) else it }
            }
            if (TitleParts.REVIEWS !in hidden) launch {
                val user = runCatching { repo.traktReviews(full.item) }.getOrDefault(emptyList())
                if (user.isNotEmpty()) _detail.update { u ->
                    if (u?.detail?.item?.key != full.item.key) u else u.copy(detail = u.detail.copy(reviews = user + u.detail.reviews))
                }
            }
            if (show) {
                fun same(u: DetailUi?) = u?.detail?.item?.key == full.item.key
                _detail.update { if (same(it)) it!!.copy(localEpisodes = localWatch.episodesOf(full.item), traktPlayback = repo.traktPlaybackOf(full.item)) else it }
                launch {
                    // Your Trakt history: episodes watched anywhere get their tick.
                    runCatching { repo.traktWatched() }
                    val seen = repo.traktEpisodesOf(full.item)
                    if (seen.isNotEmpty()) _detail.update { if (same(it)) it!!.copy(traktEpisodes = seen) else it }
                }
                val showKey = copies.firstOrNull { it.source == "Plex" }?.ref
                val plexEps = async { showKey?.let { runCatching { repo.plexEpisodes(it) }.getOrDefault(emptyMap()) }.orEmpty().also { e -> took("plex episodes"); _detail.update { if (same(it)) it!!.copy(plexEpisodes = e) else it } } }
                val providerRef = copies.firstOrNull { it.source == PROVIDER }?.ref
                val providerEps = async { providerRef?.let { runCatching { repo.providerEpisodes(it) }.getOrDefault(emptyMap()) }.orEmpty().also { e -> _detail.update { if (same(it)) it!!.copy(providerEpisodes = e) else it } } }
                val serverCopy = copies.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }
                val serverEps = async { serverCopy?.let { runCatching { repo.serverEpisodes(it.source, it.ref!!) }.getOrDefault(emptyMap()) }.orEmpty().also { e -> _detail.update { if (same(it)) it!!.copy(serverEpisodes = e) else it } } }
                // Where you're up to: what Plex / Jellyfin / Trakt say, else worked out from the ticks (Plex can mark an
                // episode watched yet name nothing "on deck", and Trakt may not know: the next was S1 E1 after E1).
                launch {
                    // From ALL your history (Peter): every server, Trakt and this box's IPTV record, taking the furthest
                    // point reached. What a server calls "on deck" (or this box's last play) only wins when it's further:
                    // one stray play of an earlier episode (S3 E3 after E5) used to drag Up next back to it.
                    val said = runCatching { repo.nextEpisodeFor(full.item, copies) }.getOrNull()
                    val local = localWatch.nextUp(full.item)
                    val trakt = runCatching { repo.traktWatched(); repo.traktEpisodesOf(full.item) }.getOrDefault(emptySet())
                    val worked = workedUpTo(plexEps.await(), serverEps.await(), trakt, localWatch.episodesOf(full.item), repo.traktPlaybackOf(full.item))
                    val next = listOfNotNull(said, local, worked).maxWithOrNull(compareBy({ it.first }, { it.second }))
                    took("up to")
                    _detail.update { if (same(it)) it!!.copy(nextUp = next, nextUpChecked = true) else it }
                }
                // A show TMDB has no seasons for: from Plex, else from the provider's / server's own episode lists.
                if (full.seasons.isEmpty()) {
                    val seasons = showKey?.let { runCatching { repo.plexSeasons(it) }.getOrDefault(emptyList()) }.orEmpty()
                        .ifEmpty {
                            (providerEps.await().keys + serverEps.await().keys).groupBy { it.first }.toSortedMap().map { (n, eps) ->
                                SeasonInfo(n, "Season $n", eps.size, null)
                            }
                        }
                    _detail.update { if (same(it)) it!!.copy(seasons = seasons) else it }
                }
                plexEps.await()
            }
        }
    }

    /** Films: Mark watched / unwatched (your servers and Trakt). */
    /**
     * Marked watched: out of every Continue watching row now, and out of the saved copy of the rows, instead of
     * waiting for the next full refresh (Peter, 2026-11-18: it stayed there).
     */
    private fun dropFromContinue(item: MediaItem) {
        fun same(i: MediaItem) = i.key == item.key || (i.type == item.type && ((item.tmdbId != null && i.tmdbId == item.tmdbId) || (item.imdbId != null && i.imdbId == item.imdbId)))
        // (The info bar's / home's Continue watching list too.)
        _continueItems.value?.let { l -> _continueItems.value = l.filterNot(::same) }
        val cur = _rows.value
        val rows = cur.rows.map { r -> if (dropsFrom(r) && !r.id.startsWith("upcoming-")) r.copy(items = r.items.filterNot(::same)) else r }
        if (rows == cur.rows) return
        _rows.value = cur.copy(rows = rows)
        val type = loaded ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val profile = runCatching { kotlin.math.abs(store.current().hashCode()).toString(36) }.getOrDefault("x")
            if (io.github.petebutty.viewarr.data.media.RowsCache.ageMs(appContext, type, profile) != null)
                io.github.petebutty.viewarr.data.media.RowsCache.write(appContext, type, profile, rows)
        }
    }

    /** A film watched anywhere: Viewarr's record, Trakt, or one of your servers' copies. null: couldn't tell. */
    private suspend fun filmWatchedNow(item: MediaItem, copies: List<LibraryCopy>): Boolean? {
        if (localWatch.isWatched(item)) return true
        return runCatching { repo.filmWatched(item, copies) }.getOrNull()
    }

    /** Where a Mark as watched took: [ok] when anywhere did, and the places to name ("Trakt", "Plex"; not this TV). */
    private class Saved(val ok: Boolean, val where: List<String>)

    /**
     * Mark watched / not watched EVERYWHERE the title is (Peter, 2026-11-19): Trakt (connected, Send plays to Trakt
     * on), every server copy, and Viewarr's record for IPTV titles (or ones it already has, or with nowhere else).
     */
    private suspend fun saveWatched(item: MediaItem, copies: List<LibraryCopy>, to: Boolean, onIptv: Boolean,
                                    season: Int? = null, episode: Int? = null, plexKey: String? = null, serverId: String? = null): Saved {
        val places = runCatching {
            if (season != null && episode != null) repo.setEpisodeWatched(item, copies, season, episode, plexKey, serverId, to)
            else repo.setFilmWatched(item, copies, to)
        }.getOrElse { emptyList() }
        val local = if (onIptv || places == null || localWatch.has(item, season, episode))
            runCatching { localWatch.setWatched(item, to, season, episode); true }.getOrDefault(false) else false
        return Saved(local || !places.isNullOrEmpty(), places.orEmpty())
    }

    fun toggleWatched() {
        val d = _detail.value ?: return
        val to = d.watched != true
        _detail.update { it?.copy(watched = to) }
        viewModelScope.launch {
            val copies = d.copies.orEmpty()
            val saved = saveWatched(d.detail.item, copies, to, onIptv = copies.any { it.source == PROVIDER } || d.detail.item.origin == "IPTV")
            if (saved.ok && to) dropFromContinue(d.detail.item)
            _detail.update {
                if (it?.detail?.item?.key != d.detail.item.key) it
                else if (saved.ok) it.copy(message = markedMessage(to, saved.where))
                else it.copy(watched = !to, message = "Couldn't change it. Try again in a moment.")
            }
        }
    }

    /** Audio & subtitles picked on a film's page, for when Play starts it (null = the usual choice). */
    fun pickTracks(audio: Int?, subtitle: Int?) = _detail.update { it?.copy(pickedAudio = audio, pickedSubtitle = subtitle) }

    /** Every title page closed at once (what's playing carries on: it isn't the page's). A service opening over them. */
    fun closeTitlePages() { detailStack.clear(); close() }

    fun close() {
        detailJob?.cancel()
        playJob?.cancel()
        val closed = _detail.value?.detail?.item?.key
        val previous = detailStack.removeLastOrNull()
        when {
            previous == null -> _detail.value = null
            // (Its loading was cut short when the next page opened: loaded again.)
            previous.copies == null -> { returnToSimilar = closed; showDetail(previous.detail.item) }
            else -> { returnToSimilar = closed; _detail.value = previous }
        }
    }

    // ---------------------------------------------------------------- shows: seasons & episodes

    fun openEpisodes() {
        val d = _detail.value ?: return
        val start = d.upTo()?.first?.takeIf { n -> d.seasons.any { it.number == n } } ?: d.seasons.minOfOrNull { it.number }
        if (start == null) {
            _detail.update { it?.copy(message = if (d.loading || d.copies == null) "Still loading. Try again in a moment." else "No episode list was found for this show.") }
            return
        }
        // The unified title page has the episodes on it: go there (the season you're up to), not the old full-page list.
        // That list is only for anyone who switched the page's episodes row off (Appearance → Title pages).
        if (AppColors.palette.shows(TitleParts.EPISODES)) {
            _detail.update { it?.copy(focusEpisodes = System.nanoTime(), message = null) }
            selectSeason(start)
            return
        }
        _detail.update { it?.copy(episodesOpen = true, message = null) }
        selectSeason(start)
    }

    fun closeEpisodes() = _detail.update { it?.copy(episodesOpen = false, episode = null, streams = null) }

    private var seasonJob: Job? = null
    fun selectSeason(n: Int) {
        val d = _detail.value ?: return
        if (d.season == n && d.episodes != null) return
        _detail.update { it?.copy(season = n, episodes = null) }
        seasonJob?.cancel()
        seasonJob = viewModelScope.launch {
            val eps = runCatching { repo.seasonEpisodes(d.detail.item, n, d.plexShowKey) }.getOrDefault(emptyList())
                .ifEmpty {
                    // Provider-only series: list the provider's episodes for this season.
                    ((_detail.value?.providerEpisodes ?: d.providerEpisodes).keys + (_detail.value?.serverEpisodes ?: d.serverEpisodes).keys).toSet().filter { it.first == n }.sortedBy { it.second }.map { (s, e) ->
                        EpisodeInfo(s, e, "Episode $e", null, null, null, null)
                    }
                }
            _detail.update { if (it?.season == n) it.copy(episodes = eps) else it }
        }
    }

    /** Back from an episode's stream list returns to that episode's options. */
    fun closeEpisodeStreams() = _detail.update { it?.copy(streams = null, message = null) }

    fun pickEpisode(ep: EpisodeInfo?) = _detail.update { it?.copy(episode = ep, message = null) }

    /** Plays one episode from Plex (resuming where you left off). */
    fun playPlexEpisode(season: Int, episode: Int) {
        val d = _detail.value ?: return
        launchPlay {
            val plexEps = d.plexEpisodes.ifEmpty { d.plexShowKey?.let { k -> safely { repo.plexEpisodes(k) } }.orEmpty() }
            val ref = plexEps[season to episode]?.ratingKey
            if (ref == null) {
                _detail.update { it?.copy(message = "S$season · E$episode isn't in your Plex library.") }
                return@launchPlay
            }
            _detail.update { it?.copy(message = "Opening on Plex…") }
            val stream = runCatching { repo.plexStream(ref, watchTarget(d.detail.item, season, episode)) }.getOrNull()
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on Plex.") }
            else {
                _detail.update { it?.copy(message = null, episode = null) }
                playIfCurrent(d.detail.item.key, stream.copy(title = "${d.detail.item.title} · S$season · E$episode"))
            }
        }
    }

    /** The Jellyfin / Emby copy of the open title, if any: (source, item id). */
    private fun serverCopy(d: DetailUi) = d.copies?.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }

    /** One episode from Jellyfin / Emby. */
    fun playServerEpisode(season: Int, episode: Int) {
        val d = _detail.value ?: return
        val source = serverCopy(d)?.source ?: return
        val id = d.serverEpisodes[season to episode]?.ratingKey ?: run {
            _detail.update { it?.copy(message = "S$season · E$episode isn't on $source.") }
            return
        }
        launchPlay {
            val stream = runCatching {
                repo.serverStream(source, id, "${d.detail.item.title} · S$season · E$episode", watchTarget(d.detail.item, season, episode))
            }.getOrNull()
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on $source.") }
            else { _detail.update { it?.copy(message = null, episode = null) }; playIfCurrent(d.detail.item.key, stream) }
        }
    }

    /** A film from the IPTV provider. */
    fun playProvider() {
        val d = _detail.value ?: return
        if (d.detail.item.type == MediaType.Show) return openEpisodes()
        val ref = d.copies?.firstOrNull { it.source == PROVIDER }?.ref ?: return
        launchPlay {
            val stream = safely { repo.providerMovie(ref, d.detail.item.title, iptvTarget(d.detail.item, null, null)) }?.withLocalResume(d.detail.item, null, null)
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this from IPTV.") }
            else { _detail.update { it?.copy(message = null) }; playIfCurrent(d.detail.item.key, stream) }
        }
    }

    /** One episode from the IPTV provider. */
    fun playProviderEpisode(season: Int, episode: Int) {
        val d = _detail.value ?: return
        val url = d.providerEpisodes[season to episode]
        if (url == null) {
            _detail.update { it?.copy(message = "IPTV doesn't have S$season · E$episode.") }
            return
        }
        // (A request still searching elsewhere is dropped; from inside playBestEpisode this ends that request itself.)
        playJob?.cancel()
        _detail.update { it?.copy(message = null, episode = null) }
        _playing.value = VodStream(
            url = url, title = "${d.detail.item.title} · S$season · E$episode", watch = iptvTarget(d.detail.item, season, episode),
            boxPreviews = false, from = "IPTV", resumeMs = otherResumeMs(d.detail.item, season, episode),
        )
    }

    /** Holding OK on a show's Play: the sources found for the episode Play would start (as holding OK on an episode). */
    fun pickNextUp() {
        val d = _detail.value ?: return
        val item = d.detail.item
        val s = d.upTo()?.first ?: d.seasons.map { it.number }.filter { it > 0 }.minOrNull() ?: 1
        val e = d.upTo()?.second ?: 1
        viewModelScope.launch {
            if (d.season != s || d.episodes == null) selectSeason(s)
            val eps = kotlinx.coroutines.withTimeoutOrNull(10_000) { _detail.first { it == null || (it.season == s && it.episodes != null) }?.episodes }
            pickEpisode(eps?.firstOrNull { it.number == e } ?: EpisodeInfo(s, e, "Episode $e", null, null, null, null))
        }
    }

    /** The "Play S2 E4" button on a show from continue watching. */
    fun playNextUp() {
        val d = _detail.value ?: return
        val item = d.detail.item
        // Continue watching's episode, else where your servers / Trakt say you're up to, else the first episode.
        val s = d.upTo()?.first ?: d.seasons.map { it.number }.filter { it > 0 }.minOrNull() ?: 1
        val e = d.upTo()?.second ?: 1
        playBestEpisode(s, e)
    }

    /**
     * One episode, from the best place that has it, the same order as a film's Play: your servers at home, then
     * away, then addons, then IPTV. (Holding OK on an episode lists the sources instead.)
     */
    fun playBestEpisode(season: Int, episode: Int) {
        val d = _detail.value ?: return
        val item = d.detail.item
        val title = "${item.title} · S$season · E$episode"
        val watch = watchTarget(item, season, episode)
        launchPlay {
            data class Try(val label: String, val home: Boolean, val get: suspend () -> VodStream?)
            val tries = ArrayList<Try>()
            // Every Plex server that has the show (home first, as the copies are sorted), each with its own episode list.
            d.copies.orEmpty().filter { it.source == "Plex" && it.ref != null }.forEachIndexed { i, c ->
                val eps = if (i == 0) d.plexEpisodes.ifEmpty { runCatching { repo.plexEpisodes(c.ref!!) }.getOrNull().orEmpty() }
                else runCatching { repo.plexEpisodes(c.ref!!) }.getOrNull().orEmpty()
                eps[season to episode]?.ratingKey?.let { rk ->
                    tries += Try(copyLabel(c), runCatching { repo.isHomeCopy(c) }.getOrDefault(false)) { repo.plexStream(rk, watch)?.copy(title = title) }
                }
            }
            serverCopy(d)?.let { c ->
                d.serverEpisodes[season to episode]?.ratingKey?.let { id ->
                    tries += Try(c.source, runCatching { repo.isHomeCopy(c) }.getOrDefault(false)) { repo.serverStream(c.source, id, title, watch) }
                }
            }
            // Each source in the order set in Settings → Playback → Play order (true: done, playing or a list to pick from).
            suspend fun servers(): Boolean {
                for (t in tries.sortedByDescending { it.home }) {
                    _detail.update { it?.copy(message = "Opening S$season · E$episode on ${t.label}…") }
                    val stream = kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { t.get() }.getOrNull() }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (stream != null) { _detail.update { it?.copy(message = null, episode = null) }; playIfCurrent(d.detail.item.key, stream); return true }
                }
                return false
            }
            suspend fun addons(): Boolean {
                if (!d.hasAddons || (item.imdbId == null && !repo.isAddonOwnTitle(item))) return false
                _detail.update { it?.copy(searching = true, message = "Looking in your addons for S$season · E$episode…") }
                val found = kotlinx.coroutines.withTimeoutOrNull(30_000) { rank(runCatching { repo.addonStreams(item, season, episode) }.getOrDefault(emptyList())) }.orEmpty()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _detail.update { it?.copy(searching = false) }
                if (found.isEmpty()) return false
                if (!autoPick()) {
                    val ep = d.episodes?.firstOrNull { it.season == season && it.number == episode }
                    _detail.update { it?.copy(message = null, streams = found, episode = ep ?: it.episode) }
                    return true
                }
                _detail.update { it?.copy(message = null, episode = null) }
                playIfCurrent(d.detail.item.key, autoStream(found) { opt -> VodStream(opt.url, title, opt.headers, watch = watch, boxPreviews = false, from = opt.addon, resumeMs = otherResumeMs(item, season, episode)) })
                return true
            }
            suspend fun iptv(): Boolean {
                if ((season to episode) !in d.providerEpisodes) return false
                // (The searches above swallow their errors, cancelling included: check this request is still wanted.)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (_detail.value?.detail?.item?.key == item.key) playProviderEpisode(season, episode)
                return true
            }
            for (s in PlayOrder.current()) if (when (s) { PlayOrder.Source.Servers -> servers(); PlayOrder.Source.Iptv -> iptv(); PlayOrder.Source.Addons -> addons() }) return@launchPlay
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            _detail.update { it?.copy(message = "Couldn't play S$season · E$episode from any of your sources. Hold OK on it to pick one.") }
        }
    }

    fun searchEpisode(ep: EpisodeInfo, forceAuto: Boolean = false) {
        val d = _detail.value ?: return
        if (d.detail.item.imdbId == null && !repo.isAddonOwnTitle(d.detail.item)) {
            _detail.update { it?.copy(message = "This show has no IMDb id, so the addons can't look it up.") }
            return
        }
        launchPlay {
            _detail.update { it?.copy(searching = true, streams = null, message = "Searching your addons for S${ep.season} · E${ep.number}…") }
            val found = rank(runCatching { repo.addonStreams(d.detail.item, ep.season, ep.number) }.getOrDefault(emptyList()))
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (found.isNotEmpty() && (autoPick() || forceAuto)) {
                _detail.update { it?.copy(searching = false, message = null) }
                playIfCurrent(d.detail.item.key, autoStream(found) { streamOf(it, ep) })
                return@launchPlay
            }
            _detail.update {
                it?.copy(searching = false, streams = found, message = if (found.isEmpty()) "Your addons didn't find any playable streams for this episode." else null)
            }
        }
    }

    /**
     * The one Play button for a film: tries every way you have of watching it, best first, and plays the first
     * that works.
     *  Your servers (home network first, then far ones), IPTV (your provider's copy through Dispatcharr) and Stremio
     *  addons (the best-ranked stream), in the order set in Settings → Playback → Play order (that order as standard).
     * A source is skipped when its file has gone (Plex checks), it doesn't answer, or it has nothing; the
     * message under the buttons says which one it's on.
     */
    fun playBest() {
        val d = _detail.value ?: return
        if (d.detail.item.type == MediaType.Show) return openEpisodes()
        val item = d.detail.item
        launchPlay {
            val copies = d.copies.orEmpty()
            // Your servers' copies, the best this TV can show first (a home server's before a far one's when they match).
            val serverCopies = rankedCopies(copies).map { it.copy }.filter { it.source != PROVIDER }
            // Each source in the order set in Settings → Playback → Play order (true: done, playing or a list to pick from).
            suspend fun servers(): Boolean {
                for (c in serverCopies) {
                    _detail.update { it?.copy(message = "Opening on ${copyLabel(c)}…") }
                    val stream = kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { streamFor(c, item) }.getOrNull() }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (stream != null) { _detail.update { it?.copy(message = null) }; playIfCurrent(d.detail.item.key, stream); return true }
                }
                return false
            }
            suspend fun addons(): Boolean {
                if (!d.hasAddons || (item.imdbId == null && !repo.isAddonOwnTitle(item))) return false
                _detail.update { it?.copy(searching = true, message = "Looking in your addons…") }
                val found = kotlinx.coroutines.withTimeoutOrNull(30_000) { rank(runCatching { repo.addonStreams(item) }.getOrDefault(emptyList())) }.orEmpty()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _detail.update { it?.copy(searching = false) }
                if (found.isEmpty()) return false
                // Stream pick set to Manual (Settings → Playback): the list to choose from, as before.
                if (!autoPick()) _detail.update { it?.copy(message = null, streams = found) }
                else { _detail.update { it?.copy(message = null) }; playIfCurrent(d.detail.item.key, autoStream(found, ::streamOf)) }
                return true
            }
            suspend fun iptv(): Boolean {
                val ref = copies.firstOrNull { it.source == PROVIDER }?.ref ?: return false
                _detail.update { it?.copy(message = "Opening from IPTV…") }
                val stream = runCatching { repo.providerMovie(ref, item.title, iptvTarget(item, null, null))?.withLocalResume(item, null, null) }.getOrNull() ?: return false
                _detail.update { it?.copy(message = null) }; playIfCurrent(d.detail.item.key, stream); return true
            }
            for (s in PlayOrder.current()) if (when (s) { PlayOrder.Source.Servers -> servers(); PlayOrder.Source.Iptv -> iptv(); PlayOrder.Source.Addons -> addons() }) return@launchPlay
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            _detail.update {
                it?.copy(message = if (copies.isEmpty() && !d.hasAddons) "This isn't in any of your libraries, and no addon is connected."
                else "Couldn't play this from any of your sources. Hold OK on Play to pick one.")
            }
        }
    }

    /** A copy on one of your servers, ready to play (null when it can't be: e.g. Plex says the file has gone). */
    private suspend fun streamFor(c: LibraryCopy, item: MediaItem) =
        if (c.source == "Plex") repo.plexStream(c.ref!!, watchTarget(item, null, null), c.version?.plexMedia)
        else repo.serverStream(c.source, c.ref!!, item.title, watchTarget(item, null, null), c.version?.sourceId)

    fun copyLabel(c: LibraryCopy): String {
        val server = c.detail?.substringAfter(" · ", "")?.takeIf { c.source == "Plex" && it.isNotEmpty() }
        return listOfNotNull(if (server != null) "Plex · $server" else c.source, c.version?.label()?.ifEmpty { null } ?: c.detail.takeIf { server == null })
            .joinToString(" · ")
    }

    /** One copy in the Play from list: [best] is the one Play picks; [note] why another might not suit (above 4K…). */
    data class RankedCopy(val copy: LibraryCopy, val best: Boolean, val note: String?)

    /**
     * Every playable copy, best for this TV first (Settings → Playback → Best quality allowed; Match the TV on a box
     * with little memory means 1080p). IPTV last: it says nothing about its quality.
     */
    suspend fun rankedCopies(copies: List<LibraryCopy>): List<RankedCopy> {
        val set = playbackStore.current().maxResolution.height
        val weak = AppColors.palette.lowMemory ||
            (appContext.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true)
        val maxH = set ?: if (weak) 1080 else FrameRateMatcher.maxHeight(appContext)
        val screen = VersionPick.screen(appContext, maxH)
        val playable = copies.filter { it.ref != null || it.source == PROVIDER }
        val (iptv, servers) = playable.partition { it.source == PROVIDER }
        val scored = servers.map { c ->
            val home = runCatching { repo.isHomeCopy(c) }.getOrDefault(false)
            c to VersionPick.score(c.version, screen, home)
        }.sortedByDescending { it.second }.map { it.first }
        return scored.mapIndexed { i, c -> RankedCopy(c, i == 0 && scored.size > 1, VersionPick.note(c.version, screen, c.source == "Plex")) } +
            iptv.map { RankedCopy(it, false, null) }
    }

    /** Other sources → one particular copy. */
    fun playCopy(c: LibraryCopy) {
        val d = _detail.value ?: return
        if (c.source == PROVIDER) return playProvider()
        launchPlay {
            _detail.update { it?.copy(message = "Opening on ${copyLabel(c)}…") }
            val stream = runCatching { streamFor(c, d.detail.item) }.getOrNull()
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on ${copyLabel(c)}.") }
            else { _detail.update { it?.copy(message = null) }; playIfCurrent(d.detail.item.key, stream) }
        }
    }

    /** The hold-Play list (Play from): the addons' streams for this film, ranked, without playing any (empty: none). */
    suspend fun addonOptions(season: Int? = null, episode: Int? = null): List<StreamOption> {
        val item = _detail.value?.detail?.item ?: return emptyList()
        if (_detail.value?.hasAddons != true || (item.imdbId == null && !repo.isAddonOwnTitle(item))) return emptyList()
        return kotlinx.coroutines.withTimeoutOrNull(30_000) {
            rank(runCatching { if (season != null && episode != null) repo.addonStreams(item, season, episode) else repo.addonStreams(item) }.getOrDefault(emptyList()))
        }.orEmpty()
    }

    fun searchAddons() {
        val d = _detail.value ?: return
        if (d.detail.item.type == MediaType.Show) return openEpisodes()
        if (d.detail.item.imdbId == null && !repo.isAddonOwnTitle(d.detail.item)) {
            _detail.update { it?.copy(message = "This title has no IMDb id, so the addons can't look it up.") }
            return
        }
        launchPlay {
            _detail.update { it?.copy(searching = true, streams = null, message = "Searching your addons…") }
            val found = rank(runCatching { repo.addonStreams(d.detail.item) }.getOrDefault(emptyList()))
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (found.isNotEmpty() && autoPick()) {
                _detail.update { it?.copy(searching = false, message = null) }
                playIfCurrent(d.detail.item.key, autoStream(found, ::streamOf))
                return@launchPlay
            }
            _detail.update {
                it?.copy(searching = false, streams = found, message = if (found.isEmpty()) "Your addons didn't find any playable streams." else null)
            }
        }
    }

    fun playStream(option: StreamOption) { playJob?.cancel(); _playing.value = streamOf(option) }

    /** An addon stream, ready to play, for the page's film (or its open episode). */
    private fun streamOf(option: StreamOption, episode: EpisodeInfo? = null): VodStream {
        val d = _detail.value
        // (The episode searched for, when given: the page's open one can have changed during the search.)
        val ep = episode ?: d?.episode
        val title = listOfNotNull(d?.detail?.item?.title, ep?.let { "S${it.season} · E${it.number}" }).joinToString(" · ").ifEmpty { option.name }
        return VodStream(
            option.url, title, option.headers, from = option.addon,
            // (Kept on the box like IPTV: addons remember nothing, so Continue watching and your other TVs follow.)
            watch = d?.detail?.item?.let { iptvTarget(it, ep?.season, ep?.number) },
            resumeMs = d?.detail?.item?.let { otherResumeMs(it, ep?.season, ep?.number) } ?: 0,
            // Addon links (debrid, AIOStreams) often allow one connection: a second one for seek previews can get the
            // stream refused mid-film.
            boxPreviews = false,
        )
    }

    fun closeStreams() = _detail.update { it?.copy(streams = null, message = null) }

    fun stopPlaying() {
        val ended = endedNearEnd
        endedNearEnd = null
        _playing.value = null
        if (ended?.season != null && ended.episode != null) viewModelScope.launch { prepareUpNext(ended.season, ended.episode) }
    }

    /** Finds the episode after S/E (next in the season, or the first of the next season) and starts the countdown. */
    private suspend fun prepareUpNext(season: Int, episode: Int) {
        if (playbackStore.current().autoNext != AutoNext.On) return
        _upNext.value = UpNext(findNext(season, episode) ?: return)
    }

    /** The episode after S/E on the show's page: next in the season, or the first of the next season. */
    private suspend fun findNext(season: Int, episode: Int): EpisodeInfo? {
        val d = _detail.value ?: return null
        val inSeason = (if (d.season == season) d.episodes else null)
            ?: runCatching { repo.seasonEpisodes(d.detail.item, season, d.plexShowKey) }.getOrDefault(emptyList())
        return inSeason.firstOrNull { it.number == episode + 1 }
            ?: d.seasons.map { it.number }.filter { it > season }.minOrNull()?.let { ns ->
                runCatching { repo.seasonEpisodes(d.detail.item, ns, d.plexShowKey) }.getOrDefault(emptyList()).firstOrNull()
            }
    }

    /** The episodes after S/E: the rest of its season, then the start of the next (a dozen at most). */
    private suspend fun findUpcoming(season: Int, episode: Int): List<EpisodeInfo> {
        val d = _detail.value ?: return emptyList()
        // The whole season (Peter: roll back to one you missed), then the start of the next near the season's end.
        val inSeason = ((if (d.season == season) d.episodes else null)
            ?: runCatching { repo.seasonEpisodes(d.detail.item, season, d.plexShowKey) }.getOrDefault(emptyList()))
            .sortedBy { it.number }
        val after = inSeason.count { it.number > episode }
        if (after >= 6) return inSeason
        val next = d.seasons.map { it.number }.filter { it > season }.minOrNull()?.let { ns ->
            runCatching { repo.seasonEpisodes(d.detail.item, ns, d.plexShowKey) }.getOrDefault(emptyList()).sortedBy { it.number }
        }.orEmpty()
        return inSeason + next.take(6 - after)
    }

    /** Plays [next] straight away (Skip credits → Next episode, or the countdown over the credits ran out). */
    // When the next episode was started from the player: the one it replaced reports "stopped" as it goes, which
    // mustn't count as finishing it (that queued a stale countdown for later).
    private var nextStartedAt = 0L
    private fun playNextEpisode(next: EpisodeInfo) {
        _upNext.value = null
        endedNearEnd = null
        nextStartedAt = System.currentTimeMillis()
        playNextOf(next)
    }

    /**
     * The next episode, from wherever it is: Plex, Jellyfin / Emby, the addons (best stream), else IPTV. Only for the
     * show that was playing (with the film in the corner, the page may be another show's by now); nothing to play it
     * from: the player closes rather than waiting on a black screen.
     */
    private fun playNextOf(next: EpisodeInfo) {
        val d = _detail.value
        val was = _playing.value?.watch
        val sameShow = d != null && (was == null || (was.tmdbId != null && was.tmdbId == d.detail.item.tmdbId) ||
            (was.imdbId != null && was.imdbId == d.detail.item.imdbId) || (was.tmdbId == null && was.imdbId == null))
        if (d == null || !sameShow) { stopPlaying(); return }
        _detail.update { it?.copy(episode = next) }
        val key = next.season to next.number
        when {
            d.plexEpisodes.containsKey(key) -> playPlexEpisode(next.season, next.number)
            d.serverEpisodes.containsKey(key) -> playServerEpisode(next.season, next.number)
            d.hasAddons -> searchEpisode(next, forceAuto = true)
            d.providerEpisodes.containsKey(key) -> playProviderEpisode(next.season, next.number)
            else -> stopPlaying()
        }
    }

    fun cancelUpNext() { _upNext.value = null }

    /** Plays the up-next episode: from Plex if it's there, otherwise the best addon stream. */
    fun playUpNext() {
        val next = _upNext.value?.episode ?: return
        _upNext.value = null
        val d = _detail.value ?: return
        _detail.update { it?.copy(episode = next) }
        val key = next.season to next.number
        when {
            d.plexEpisodes.containsKey(key) -> playPlexEpisode(next.season, next.number)
            d.serverEpisodes.containsKey(key) -> playServerEpisode(next.season, next.number)
            d.hasAddons -> searchEpisode(next, forceAuto = true)
            d.providerEpisodes.containsKey(key) -> playProviderEpisode(next.season, next.number)
        }
    }

    private fun watchTarget(item: MediaItem, season: Int?, episode: Int?) =
        WatchTarget(item.type, tmdbId = item.tmdbId, imdbId = item.imdbId, season = season, episode = episode, item = item)

    /** IPTV: as [watchTarget], plus the title so where you stop is kept on the box. */
    private fun iptvTarget(item: MediaItem, season: Int?, episode: Int?) = watchTarget(item, season, episode).copy(iptv = item)

    /** IPTV films / episodes carry on from where you stopped on this box (the provider doesn't remember). */
    private fun VodStream.withLocalResume(item: MediaItem, season: Int?, episode: Int?) =
        if (resumeMs > 0) this else copy(resumeMs = otherResumeMs(item, season, episode))

    /**
     * Where an IPTV or addon play carries on from (they don't remember themselves): this box's record, else Trakt's
     * paused point from another app (its % of the running time). 0: from the start.
     */
    private fun otherResumeMs(item: MediaItem, season: Int?, episode: Int?): Long {
        localWatch.resumeMs(item, season, episode).takeIf { it > 0 }?.let { return it }
        val d = _detail.value?.takeIf { it.detail.item.key == item.key }
        val ep = if (season != null && episode != null) season to episode else null
        val pos = if (ep != null) d?.traktPlayback?.get(ep) ?: repo.traktPlaybackOf(item)[ep] else repo.traktFilmPos(item)
        pos ?: return 0
        val runtime = (if (ep != null) d?.episodes?.firstOrNull { it.season == season && it.number == episode }?.runtimeMin else null) ?: d?.detail?.runtimeMin
        return runtime?.takeIf { it > 0 }?.let { (pos.pct * it * 60_000L).toLong() } ?: 0
    }

    /** Player progress → Plex / Jellyfin / Emby timeline and Trakt scrobble. Trakt only hears about changes (start/pause/stop). */
    private var lastState = ""
    private var localSavedAt = 0L
    // Skips while playing, for Trakt (Peter, 2026-11-18: skipping to the credits left Trakt's "Now watching" near the
    // start): where the last report was, and when a jump was seen (sent once the skipping settles).
    private var traktLastPos = -1L
    private var traktLastAt = 0L
    private var traktJumpAt = 0L
    /** A show's episodes by TMDB's season counts (when the provider's own list isn't known). */
    private fun DetailUi.seasonEpisodeKeys(): Set<Pair<Int, Int>> =
        seasons.filter { it.number > 0 && it.episodes > 0 }.flatMap { s -> (1..s.episodes).map { s.number to it } }.toSet()
    // A play from IPTV or an addon is reported to the same title on your servers as well (Peter, 2026-11-19: "if I
    // watch an IPTV VOD show, Plex should update, Trakt should update, and my Viewarr boxes should update"): the
    // server copies are looked up once per stream, and the reports go to them as if it were playing from there.
    private var relayUrl: String? = null
    private var relayPlex: String? = null
    private var relayServer: Pair<String, String>? = null
    private var relayJob: kotlinx.coroutines.Job? = null
    private fun relayFor(stream: VodStream, target: WatchTarget) {
        if (relayUrl == stream.url) return
        relayUrl = stream.url; relayPlex = null; relayServer = null
        relayJob?.cancel()
        val item = target.item ?: return
        relayJob = viewModelScope.launch {
            val copies = runCatching { repo.copiesOf(item) }.getOrDefault(emptyList())
            val k = target.season?.let { s -> target.episode?.let { e -> s to e } }
            val plex = copies.firstOrNull { it.source == "Plex" && it.ref != null }?.ref?.let { ref ->
                if (k == null) ref else runCatching { repo.plexEpisodes(ref) }.getOrNull()?.get(k)?.ratingKey
            }
            val server = copies.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }?.let { c ->
                val id = if (k == null) c.ref else runCatching { repo.serverEpisodes(c.source, c.ref!!) }.getOrNull()?.get(k)?.ratingKey
                id?.let { c.source to it }
            }
            if (relayUrl != stream.url) return@launch
            relayPlex = plex; relayServer = server
            val where = listOfNotNull(plex?.let { "Plex" }, server?.first)
            android.util.Log.i("Catalog", "watch relay: ${item.title}${k?.let { " S${it.first} E${it.second}" } ?: ""} is ${if (where.isEmpty()) "not on your servers" else "also on " + where.joinToString(" and ")}")
        }
    }

    fun reportProgress(stream: VodStream, state: String, positionMs: Long, durationMs: Long) {
        val target = stream.watch ?: run { if (state != lastState) { android.util.Log.i("Catalog", "watch report: $state, but this stream has nothing to report to"); lastState = state }; return }
        if (target.plexRatingKey == null && target.serverSource == null) relayFor(stream, target)
        // Set straight away (not in the coroutine): the player closes right after reporting the end.
        if (state == "stopped" && durationMs > 0 && positionMs >= durationMs * 0.95 && System.currentTimeMillis() - nextStartedAt > 5_000) endedNearEnd = target
        // The TV home screen's Watch Next row follows: once the servers have the new position, refresh it.
        if (state == "stopped") viewModelScope.launch { kotlinx.coroutines.delay(5_000); watchNext.refresh() }
        // IPTV: kept on the box when paused or stopped, and every 10 s while playing (in case the box is switched off).
        target.iptv?.let { item ->
            val now = System.currentTimeMillis()
            if (state != "playing" || now - localSavedAt > 9_000) {
                localSavedAt = now
                // The credits (the end, when known) and the show's real episodes (to find the next one).
                val credits = (stream.markers + FoundMarkers.byUrl[stream.url].orEmpty())
                    .firstOrNull { it.kind == SkipMarker.Kind.Credits }?.startMs
                val eps = _detail.value?.takeIf { it.detail.item.key == item.key }?.let { d -> d.providerEpisodes.keys.ifEmpty { d.seasonEpisodeKeys() } }.orEmpty()
                viewModelScope.launch { runCatching { localWatch.record(item, target.season, target.episode, positionMs, durationMs, credits, eps) } }
            }
        }
        viewModelScope.launch {
            // (One rule, no switches. Peter, 2026-11-19: "it should all just work". The server a film plays from always
            // hears everything, as any Plex / Jellyfin player does: its resume point, its tick and its own Trakt link
            // then just work. Trakt connected: told about every play too (repo.scrobbleTrakt checks Send plays to
            // Trakt); Trakt takes a repeat of the same watch from Plex's webhook as the same watch.)
            val nearEnd = durationMs > 0 && positionMs >= durationMs * io.github.petebutty.viewarr.data.sync.WatchSource.WATCHED_AT
            target.plexRatingKey?.let { repo.reportPlex(it, state, positionMs, durationMs, markWatched = true) }
            if (target.serverSource != null && target.serverItemId != null) repo.reportServer(target.serverSource, target.serverItemId, state, positionMs, durationMs)
            // Not from a server: the same title on your servers hears it too (a stop waits for the look-up to finish).
            if (target.plexRatingKey == null && target.serverSource == null) {
                if (state == "stopped") kotlinx.coroutines.withTimeoutOrNull(8_000) { relayJob?.join() }
                if (relayUrl == stream.url) {
                    relayPlex?.let { repo.reportPlex(it, state, positionMs, durationMs, markWatched = true) }
                    relayServer?.let { (src, id) -> repo.reportServer(src, id, state, positionMs, durationMs) }
                }
            }
            // A skip while playing (the position jumped more than 20 s from where it should be): once there's been no
            // jump for 3 s, Trakt is told the new place, the same "start" it gets when playing begins.
            val now = System.currentTimeMillis()
            if (state == "playing" && traktLastPos >= 0) {
                val expected = traktLastPos + (now - traktLastAt)
                if (kotlin.math.abs(positionMs - expected) > 20_000) { traktJumpAt = now; android.util.Log.i("Catalog", "trakt: skip seen (${(positionMs - expected) / 1000} s)") }
                else if (traktJumpAt > 0 && now - traktJumpAt > 3_000 && state == lastState) {
                    traktJumpAt = 0
                    val pct = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                    repo.scrobbleTrakt("start", target, pct)
                }
            }
            // A film finished here (past 80%): out of Continue watching now.
            if (state == "stopped" && nearEnd && target.type == MediaType.Movie) {
                val keys = filmKeys(target.tmdbId, target.imdbId)
                if (keys.isNotEmpty()) finishedHere.value = finishedHere.value + keys
            }
            traktLastPos = if (state == "playing") positionMs else -1L
            traktLastAt = now
            if (state != lastState) {
                android.util.Log.i("Catalog", "watch report: $state at ${positionMs / 1000}s, tmdb=${target.tmdbId} plex=${target.plexRatingKey != null} trakt=${io.github.petebutty.viewarr.data.sync.WatchSource.sendToTrakt}")
                lastState = state
                val action = when (state) { "playing" -> "start"; "paused" -> "pause"; else -> "stop" }
                val pct = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                // (Trakt refuses a pause or stop under 1% (422), so those aren't sent: the sound-wake pause at the start was one.)
                if (action == "start" || pct >= 0.01f) repo.scrobbleTrakt(action, target, pct)
            }
            if (state == "stopped") lastState = ""
        }
    }

    // ---- Quick options (hold OK on a poster)

    /** Whether [item] can be played from IPTV (offers Mark as watched / unwatched, kept on the box). */
    suspend fun hasIptv(item: MediaItem): Boolean = item.origin == "IPTV" ||
        runCatching { repo.copiesOf(item) }.getOrDefault(emptyList()).any { it.source == PROVIDER }

    /** Hold OK on an IPTV film: watched anywhere (for the pop-up's Mark as watched / not watched). */
    suspend fun quickWatched(item: MediaItem): Boolean {
        if (localWatch.isWatched(item)) return true
        val copies = runCatching { repo.copiesOf(item) }.getOrDefault(emptyList())
        return runCatching { repo.filmWatched(item, copies) }.getOrDefault(false)
    }

    /** Mark an IPTV film watched / not watched: everywhere it is (Trakt, your servers' copies, this box's record). */
    fun markIptvWatched(item: MediaItem, watched: Boolean) {
        viewModelScope.launch {
            val copies = runCatching { repo.copiesOf(item) }.getOrDefault(emptyList())
            val saved = saveWatched(item, copies, watched, onIptv = true)
            if (!saved.ok) { _notice.value = "Couldn't change it. Try again in a moment."; return@launch }
            if (watched) dropFromContinue(item)
            _notice.value = markedMessage(watched, saved.where, item.title) + "."
        }
    }

    /**
     * One episode watched / not watched (the title page's episode options): everywhere it is (Trakt, Plex / Jellyfin /
     * Emby, and Viewarr's record when it's on IPTV). Peter, 2026-11-19.
     */
    fun markEpisode(season: Int, episode: Int, watched: Boolean) {
        val d = _detail.value ?: return
        val item = d.detail.item
        val k = season to episode
        val plexKey = d.plexEpisodes[k]?.ratingKey
        val serverId = d.serverEpisodes[k]?.ratingKey
        // Shown straight away; put back if nothing took it.
        fun apply(to: Boolean) = _detail.update {
            if (it?.detail?.item?.key != item.key) it else it.copy(
                plexEpisodes = it.plexEpisodes[k]?.let { e -> it.plexEpisodes + (k to e.copy(watched = to, viewOffsetMs = 0)) } ?: it.plexEpisodes,
                serverEpisodes = it.serverEpisodes[k]?.let { e -> it.serverEpisodes + (k to e.copy(watched = to, viewOffsetMs = 0)) } ?: it.serverEpisodes,
                traktEpisodes = if (to) it.traktEpisodes + k else it.traktEpisodes - k,
                localEpisodes = it.localEpisodes + (k to PlexEpisode("local", 0, to)),
                traktPlayback = it.traktPlayback - k,
            )
        }
        apply(watched)
        viewModelScope.launch {
            val onIptv = k in d.providerEpisodes || k in d.localEpisodes
            val saved = saveWatched(item, d.copies.orEmpty(), watched, onIptv, season, episode, plexKey, serverId)
            if (!saved.ok) apply(!watched)
            _detail.update {
                if (it?.detail?.item?.key != item.key) it
                else it.copy(message = if (!saved.ok) "Couldn't change it. Try again in a moment." else markedMessage(watched, saved.where))
            }
        }
    }

    /** Out of Continue watching on IPTV (played again, it comes back). */
    fun hideIptv(item: MediaItem) {
        viewModelScope.launch { safely { localWatch.hide(item) } ?: return@launch; _notice.value = "Removed ${item.title} from Continue watching." }
    }


    /** A short note shown at the bottom of Movies / Shows (e.g. "Added to your watchlist"). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    fun clearNotice() { _notice.value = null }

    /** Whether Trakt is connected and the title is on the watchlist (null = no Trakt, so no watchlist option). */
    suspend fun watchlistState(item: MediaItem): Boolean? = runCatching { repo.inWatchlist(item) }.getOrNull()

    fun quickWatchlist(item: MediaItem, add: Boolean) {
        viewModelScope.launch {
            val result = safely { repo.setWatchlist(item, add) }
            val name = safely { repo.watchlistName() } ?: "your watchlist"
            _notice.value = when (result) {
                null -> "Couldn't update $name. Try again."
                true -> "Added ${item.title} to $name."
                false -> "Removed ${item.title} from $name."
            }
        }
    }

    /** Play straight from a poster: Plex if it's in your library, otherwise the addon streams. */
    fun quickPlay(item: MediaItem) {
        open(item)
        viewModelScope.launch {
            detailJob?.join()
            val d = _detail.value ?: return@launch
            if (d.copies != null) playBest()
        }
    }

    /** A show from Continue watching, played straight away: the episode you're up to (as its Play S2 E4 button). */
    fun quickNextUp(item: MediaItem) {
        open(item)
        viewModelScope.launch {
            detailJob?.join()
            if (_detail.value != null) playNextUp()
        }
    }

    /** Continue watching → Play from the start: the film, or the episode you're up to, from the beginning. */
    fun quickFromStart(item: MediaItem) {
        open(item)
        viewModelScope.launch {
            detailJob?.join()
            if (_detail.value != null) playFromStart()
        }
    }

    /** Shows: straight to the episode list. */
    fun quickEpisodes(item: MediaItem) {
        open(item)
        viewModelScope.launch {
            detailJob?.join()
            openEpisodes()
        }
    }

    fun toggleWatchlist() {
        val d = _detail.value ?: return
        viewModelScope.launch {
            val result = safely { repo.setWatchlist(d.detail.item, add = !d.watchlisted) }
            val name = safely { repo.watchlistName() } ?: "your watchlist"
            _detail.update {
                when (result) {
                    null -> it?.copy(message = "Couldn't update $name. Try again.")
                    true -> it?.copy(watchlisted = true, message = "Added to $name.")
                    false -> it?.copy(watchlisted = false, message = "Removed from $name.")
                }
            }
        }
    }
}

/** Search filter buttons: what the results show. */
enum class SearchFilter(val label: String) {
    All("All"), Films("Films"), Shows("Shows"), LiveTv("Live TV"), Catchup("Catch-up"), Music("Music"), Library("In my library");

    fun apply(rows: List<MediaRow>): List<MediaRow> = when (this) {
        All -> rows
        LiveTv, Catchup, Music -> emptyList()
        Library -> rows.filter { it.id == "search-library" }
        Films, Shows -> {
            val want = if (this == Films) MediaType.Movie else MediaType.Show
            rows.filter { it.id != "search-people" }
                .map { r -> r.copy(items = r.items.filter { it.type == want }) }
                .filter { it.items.isNotEmpty() }
        }
    }
}

/** Titles per library page: enough to browse, small enough for a weak box to hold. */
const val LIBRARY_PAGE = 60
