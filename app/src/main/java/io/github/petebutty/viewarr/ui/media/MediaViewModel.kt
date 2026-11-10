package io.github.petebutty.viewarr.ui.media

import kotlinx.coroutines.flow.first
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
import javax.inject.Inject

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
    val streams: List<io.github.petebutty.viewarr.data.media.StreamOption>? = null,
    val searching: Boolean = false,
    /** Shows: the episodes panel. */
    val episodesOpen: Boolean = false,
    val seasons: List<io.github.petebutty.viewarr.data.media.SeasonInfo> = emptyList(),
    /** Shows: where Play carries on from (season, episode, part-way through); null = from the first episode. */
    val nextUp: Triple<Int, Int, Boolean>? = null,
    /** The nextUp lookup has finished (nextUp may still be null: not started). */
    val nextUpChecked: Boolean = false,
    val season: Int? = null,
    /** null while loading. */
    val episodes: List<io.github.petebutty.viewarr.data.media.EpisodeInfo>? = null,
    val plexEpisodes: Map<Pair<Int, Int>, io.github.petebutty.viewarr.data.media.PlexEpisode> = emptyMap(),
    /** Jellyfin / Emby series: (season, episode) → episode id, resume point, watched. */
    val serverEpisodes: Map<Pair<Int, Int>, io.github.petebutty.viewarr.data.media.PlexEpisode> = emptyMap(),
    /** Episodes Trakt says you've watched (wherever you watched them). */
    val traktEpisodes: Set<Pair<Int, Int>> = emptySet(),
    /** IPTV provider series: (season, episode) → stream address. */
    val providerEpisodes: Map<Pair<Int, Int>, String> = emptyMap(),
    /** The episode whose play options are open. */
    val episode: io.github.petebutty.viewarr.data.media.EpisodeInfo? = null,
    /** IMDb / Rotten Tomatoes (from Plex) and Trakt, beside TMDB's. */
    val ratings: List<io.github.petebutty.viewarr.data.media.RatingBadge> = emptyList(),
    /** Films: watched on any of your servers or Trakt (null while checking). */
    val watched: Boolean? = null,
    /** Films on your own server: their audio / subtitle tracks, and what you picked for this Play (null = usual). */
    val tracks: io.github.petebutty.viewarr.data.media.TitleTracks? = null,
    /** The film's file (picture, soundtracks, subtitles), for the title page's badges. */
    val media: io.github.petebutty.viewarr.data.media.TitleTracks? = null,
    val pickedAudio: Int? = null,
    val pickedSubtitle: Int? = null,
)

private val DetailUi.plexShowKey get() = copies?.firstOrNull { it.source == "Plex" }?.ref

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val store: AccountStore,
    private val playbackStore: io.github.petebutty.viewarr.data.settings.PlaybackStore,
    /** YouTube trailers: the poster preview window and the title page's Trailer button. */
    val trailers: io.github.petebutty.viewarr.data.trailers.TrailerRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val rowLayouts: io.github.petebutty.viewarr.data.settings.RowLayoutStore,
    private val watchNext: io.github.petebutty.viewarr.data.media.WatchNext,
    private val seerr: io.github.petebutty.viewarr.data.remote.services.SeerrService,
    /** Settings → Appearance → Title pages / Ratings: parts switched off aren't fetched. */
    private val themeStore: io.github.petebutty.viewarr.data.settings.ThemeStore,
    /** Each library's sort and filters (per profile). */
    private val libraryViews: io.github.petebutty.viewarr.data.media.LibraryViews,
) : ViewModel() {
    /** Seerr: where this title stands (null = Seerr not set up, or the title has no TMDB id). */
    suspend fun seerrStatus(item: MediaItem): io.github.petebutty.viewarr.data.remote.services.SeerrService.Status? {
        // Locked by an admin for this person: no requests.
        if (!io.github.petebutty.viewarr.data.profiles.Access.can(io.github.petebutty.viewarr.data.profiles.Lock.SEERR)) return null
        val a = store.current().seerr ?: return null
        val id = runCatching { repo.tmdbIdFor(item) }.getOrNull() ?: return null
        // Already on your own server (Plex, Jellyfin, Emby): shown as available. Only on your IPTV provider's VOD
        // still offers the request (to keep it on your server).
        fun own(c: List<io.github.petebutty.viewarr.data.media.LibraryCopy>) = c.any { it.source != io.github.petebutty.viewarr.data.media.PROVIDER }
        if (own(runCatching { repo.copiesOf(item) }.getOrDefault(emptyList()))) return io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.Available
        // Library check with the TMDB id known (addon titles only carry an IMDb id).
        if (item.tmdbId == null && own(runCatching { repo.copiesOf(item.copy(tmdbId = id)) }.getOrDefault(emptyList()))) return io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.Available
        return runCatching { seerr.status(a, item.type, id) }.getOrDefault(io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.None)
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
    data class UpNext(val episode: io.github.petebutty.viewarr.data.media.EpisodeInfo)
    private val _upNext = MutableStateFlow<UpNext?>(null)
    val upNext = _upNext.asStateFlow()
    private var endedNearEnd: io.github.petebutty.viewarr.data.media.WatchTarget? = null

    /**
     * Orders addon streams: within the quality limit before above it, cached (instant) before uncached,
     * then higher resolution first. The addon's own order breaks ties.
     */
    private suspend fun rank(found: List<io.github.petebutty.viewarr.data.media.StreamOption>): List<io.github.petebutty.viewarr.data.media.StreamOption> {
        val max = playbackStore.current().maxResolution.height ?: io.github.petebutty.viewarr.playback.FrameRateMatcher.maxHeight(appContext)
        fun res(o: io.github.petebutty.viewarr.data.media.StreamOption): Int {
            val t = (o.name + " " + o.description).lowercase()
            return when {
                "2160" in t || "4k" in t || "uhd" in t -> 2160
                "1080" in t -> 1080
                "720" in t -> 720
                "480" in t || " sd" in t -> 480
                else -> 0
            }
        }
        fun cached(o: io.github.petebutty.viewarr.data.media.StreamOption): Boolean {
            val t = o.name + " " + o.description
            return "⚡" in t || "+]" in t || t.contains("cached", ignoreCase = true) || t.contains("plex", ignoreCase = true)
        }
        return found.withIndex().sortedWith(
            compareBy<IndexedValue<io.github.petebutty.viewarr.data.media.StreamOption>> { if (res(it.value) > max) 1 else 0 }
                .thenBy { if (cached(it.value)) 0 else 1 }
                .thenByDescending { res(it.value) }
                .thenBy { it.index },
        ).map { it.value }
    }

    private suspend fun autoPick() = playbackStore.current().streamPick == io.github.petebutty.viewarr.data.settings.StreamPick.Auto

    private val _rows = MutableStateFlow(RowsState(emptyList(), done = false))
    private var lastQuery = ""
    /** Settings → Home page & menu → Combine rows from different servers. */
    val mergeRows = rowLayouts.mergeRows.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    fun setMergeRows(on: Boolean) { viewModelScope.launch { rowLayouts.setMergeRows(on) } }
    /** The rows as shown: repeats across servers combined when that's switched on. */
    private val shownRows = kotlinx.coroutines.flow.combine(_rows, mergeRows) { r, merge ->
        if (merge) r.copy(rows = io.github.petebutty.viewarr.data.media.mergeRows(r.rows)) else r
    }
    /** A row opened from the menu that's switched off on the page: shown on the page anyway until Home is picked. */
    private val openedFromMenu = MutableStateFlow<String?>(null)
    /** Films / Shows: Settings → Home rows decides order and which are shown. Search results are left alone. */
    private val layoutType = MutableStateFlow<MediaType?>(null)
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

    val rows: kotlinx.coroutines.flow.StateFlow<RowsState> = kotlinx.coroutines.flow.combine(shownRows, layout, _searchFilter, openedFromMenu, _searchFocus) { r, l, f, extra, focus ->
        if (l != null) r.copy(rows = l.ordered(r.rows).filter { it.id !in l.hidden || it.id == extra }) else r.copy(rows = focusFirst(orderSearch(f.apply(r.rows)), focus))
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, RowsState(emptyList(), done = false))
    /** Every row in the chosen order with whether it's hidden, for Settings → Home rows. */
    val editableRows: kotlinx.coroutines.flow.StateFlow<Pair<List<Pair<io.github.petebutty.viewarr.data.media.MediaRow, Boolean>>, Boolean>> =
        kotlinx.coroutines.flow.combine(shownRows, layout) { r, l ->
            val lay = l ?: io.github.petebutty.viewarr.data.settings.RowLayout()
            lay.ordered(r.rows).map { it to (it.id in lay.hidden) } to r.done
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList<Pair<io.github.petebutty.viewarr.data.media.MediaRow, Boolean>>() to false)

    /** The menu's Catalogues list (Movies / Shows → Right): in the page's order, with its own on / off. */
    val menuRows: kotlinx.coroutines.flow.StateFlow<RowsState> = kotlinx.coroutines.flow.combine(shownRows, layout) { r, l ->
        val lay = l ?: io.github.petebutty.viewarr.data.settings.RowLayout()
        r.copy(rows = lay.ordered(r.rows).filter { it.id !in lay.menuHidden })
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, RowsState(emptyList(), done = false))
    /** Every row with whether it's left out of the menu, for Settings → Menu list. */
    val editableMenuRows: kotlinx.coroutines.flow.StateFlow<List<Pair<io.github.petebutty.viewarr.data.media.MediaRow, Boolean>>> =
        kotlinx.coroutines.flow.combine(shownRows, layout) { r, l ->
            val lay = l ?: io.github.petebutty.viewarr.data.settings.RowLayout()
            lay.ordered(r.rows).map { it to (it.id in lay.menuHidden) }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun setMenuRowHidden(id: String, hidden: Boolean) {
        val t = layoutType.value ?: return
        viewModelScope.launch { rowLayouts.setMenuHidden(t, id, hidden) }
    }

    fun setRowHidden(id: String, hidden: Boolean) {
        val t = layoutType.value ?: return
        viewModelScope.launch { rowLayouts.setHidden(t, id, hidden) }
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
    /** What's playing. Set through here so the Audio & subtitles picked on a film's page go with its own copy. */
    private inner class Playing {
        val flow = MutableStateFlow<io.github.petebutty.viewarr.data.media.VodStream?>(null)
        var value: io.github.petebutty.viewarr.data.media.VodStream?
            get() = flow.value
            set(v) { flow.value = v?.let(::withPicks)?.let(::fromStartIfAsked) }
    }
    private val _playing = Playing()
    val playing = _playing.flow.asStateFlow()

    /** From the start (the page's button): the next film or episode Play starts begins at 0, not where you left off.
     *  Only for a minute after pressing it, so a Play that found nothing doesn't affect a later one. */
    private var fromStartAt = 0L
    private fun fromStartIfAsked(v: io.github.petebutty.viewarr.data.media.VodStream): io.github.petebutty.viewarr.data.media.VodStream {
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
    private fun withPicks(v: io.github.petebutty.viewarr.data.media.VodStream): io.github.petebutty.viewarr.data.media.VodStream {
        val d = _detail.value ?: return v
        val t = d.tracks ?: return v
        if (d.pickedAudio == null && d.pickedSubtitle == null) return v
        val w = v.watch
        val same = (t.source == "Plex" && w?.plexRatingKey == t.ref) || (t.source != "Plex" && w?.serverItemId == t.ref)
        if (!same) return v
        fun apply(x: io.github.petebutty.viewarr.data.media.VodStream) = x.copy(audioTrack = d.pickedAudio, subtitleTrack = d.pickedSubtitle)
        return apply(v).copy(fallback = v.fallback?.let(::apply))
    }

    /** From a title page: a cast member's films and shows (the title page closes). */
    fun openCastMember(c: io.github.petebutty.viewarr.data.media.CastMember) {
        val id = c.id ?: return
        // Leave every title page (not back to the title before it): the person's page takes over.
        detailStack.clear()
        close()
        open(MediaItem(key = "person:$id", type = MediaType.Movie, title = c.name, poster = c.photo, origin = "TMDB"))
    }

    /** IMDb / Rotten Tomatoes (your Plex) and Trakt ratings for a title, cached for a while. */
    suspend fun ratingsFor(item: MediaItem): List<io.github.petebutty.viewarr.data.media.RatingBadge> = runCatching {
        val known = if (item.tmdbId == null && item.imdbId == null) item else item
        repo.extraRatings(known, repo.copiesOf(known))
    }.getOrDefault(emptyList())

    /** A title's logo (TMDB), cached; null when there isn't one. */
    suspend fun logoFor(item: MediaItem): String? = runCatching { repo.logoFor(item) }.getOrNull()

    /** The playing title's backdrop and logo: the backdrop at once, the logo once TMDB answers. */
    private val _playerArt = MutableStateFlow<io.github.petebutty.viewarr.data.media.PlayerArt?>(null)
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
                val art = io.github.petebutty.viewarr.data.media.PlayerArt(
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

    fun jumpTo(rowId: String?) { openedFromMenu.value = rowId; _jump.value = rowId to System.nanoTime() }
    fun jumpDone() { _jump.value = null }

    private var loaded: MediaType? = null
    private var rowsJob: Job? = null
    private var detailJob: Job? = null

    /**
     * Search results in the order you most likely meant: a person's name puts People first (unless a film or show has
     * that exact title); otherwise your library, then films and shows, as they come.
     */
    /** Search: the looked-at pill's kind first (your library, or rows that are mostly films / shows); others as they were. */
    private fun focusFirst(rows: List<io.github.petebutty.viewarr.data.media.MediaRow>, focus: SearchFilter?): List<io.github.petebutty.viewarr.data.media.MediaRow> {
        fun mostly(r: io.github.petebutty.viewarr.data.media.MediaRow, t: MediaType) = r.items.isNotEmpty() && r.items.count { it.type == t } * 2 > r.items.size
        val first: (io.github.petebutty.viewarr.data.media.MediaRow) -> Boolean = when (focus) {
            SearchFilter.Library -> { r -> r.id == "search-library" }
            SearchFilter.Films -> { r -> r.id != "search-people" && mostly(r, MediaType.Movie) }
            SearchFilter.Shows -> { r -> r.id != "search-people" && mostly(r, MediaType.Show) }
            else -> return rows
        }
        return rows.sortedBy { if (first(it)) 0 else 1 }
    }

    private fun orderSearch(rows: List<io.github.petebutty.viewarr.data.media.MediaRow>): List<io.github.petebutty.viewarr.data.media.MediaRow> {
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
        rowsJob = viewModelScope.launch { repo.rows(type).collect { _rows.value = it } }
    }

    // ---- Search: a person's films and shows, in place of the results until Back

    private val _person = MutableStateFlow<String?>(null)
    /** The person whose films and shows are showing (search), or null. */
    val person = _person.asStateFlow()
    private var resultsBeforePerson: RowsState? = null

    private val _personInfo = MutableStateFlow<io.github.petebutty.viewarr.data.media.PersonInfo?>(null)
    /** The person whose films are showing: their photo, dates and biography for the header. */
    val personInfo = _personInfo.asStateFlow()

    private fun openPerson(item: MediaItem) {
        val id = item.key.removePrefix("person:")
        val placeholder = io.github.petebutty.viewarr.data.media.PersonInfo(item.title, item.poster, null, null, null, null, null, loading = true)
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
        val library: io.github.petebutty.viewarr.data.media.MediaLibrary,
        val type: MediaType,
        val query: io.github.petebutty.viewarr.data.media.LibraryQuery,
        val options: io.github.petebutty.viewarr.data.media.LibraryOptions? = null,
        val total: Int = -1,
        val pages: Map<Int, List<MediaItem>> = emptyMap(),
        val failed: Boolean = false,
        /** Where each first letter starts (sorted by title), when the server can say. */
        val letters: Map<Char, Int>? = null,
        /** Each filter's choices once looked up (null while loading). */
        val values: Map<String, List<io.github.petebutty.viewarr.data.media.FilterValue>?> = emptyMap(),
        /** A–Z jump: the position to go to (and focus). */
        val jump: Int? = null,
        /** Changes whenever the sort, filters or search change: the grid starts again from the top. */
        val version: Int = 0,
    ) {
        fun item(i: Int): MediaItem? = pages[i / LIBRARY_PAGE]?.getOrNull(i % LIBRARY_PAGE)
        val items: List<MediaItem> get() = pages.keys.sorted().flatMap { pages.getValue(it) }
        val filtered get() = query.filtered
        /** The A–Z strip: sorted by title, A first, where the server can find a letter. */
        val byTitle get() = options?.let { o -> query.sort == o.titleSort && !query.desc && library.source != io.github.petebutty.viewarr.data.media.PROVIDER_LIBRARY } == true
        /** The A–Z strip is there whatever the sort (picking a letter sorts by title); not for IPTV lists. */
        val canLetters get() = options?.letters == true && library.source != io.github.petebutty.viewarr.data.media.PROVIDER_LIBRARY
    }

    private val _libraries = MutableStateFlow<List<io.github.petebutty.viewarr.data.media.MediaLibrary>?>(null)
    private var librariesFor: MediaType? = null
    private val libType = MutableStateFlow<MediaType?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val libLayout = libType.flatMapLatest { t -> if (t == null) kotlinx.coroutines.flow.flowOf(io.github.petebutty.viewarr.data.settings.RowLayout()) else rowLayouts.libraryLayout(t) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val libNames = libType.flatMapLatest { t -> if (t == null) kotlinx.coroutines.flow.flowOf(emptyMap()) else rowLayouts.libraryNames(t) }
    /** The libraries with your own names applied (Settings → Home page & menu → Menu list → hold OK). */
    private val namedLibraries = kotlinx.coroutines.flow.combine(_libraries, libNames) { libs, names ->
        libs?.map { l -> names[l.key]?.let { l.copy(title = it) } ?: l }
    }
    /** Every library in the chosen order, with whether it's hidden (Settings → Home page & menu). */
    val allLibraries: kotlinx.coroutines.flow.StateFlow<List<Pair<io.github.petebutty.viewarr.data.media.MediaLibrary, Boolean>>?> =
        kotlinx.coroutines.flow.combine(namedLibraries, libLayout) { libs, l -> libs?.let { l.orderedBy(it) { x -> x.key }.map { x -> x to (x.key in l.hidden) } } }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    /** The libraries the menu lists (null until loaded). */
    val libraries: kotlinx.coroutines.flow.StateFlow<List<io.github.petebutty.viewarr.data.media.MediaLibrary>?> =
        kotlinx.coroutines.flow.combine(namedLibraries, libLayout) { libs, l -> libs?.let { l.orderedBy(it) { x -> x.key }.filter { x -> x.key !in l.hidden } } }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    fun setLibraryName(key: String, name: String?) { val t = libType.value ?: return; viewModelScope.launch { rowLayouts.setLibraryName(t, key, name) } }
    fun setLibraryHidden(key: String, hidden: Boolean) { val t = libType.value ?: return; viewModelScope.launch { rowLayouts.setLibraryHidden(t, key, hidden) } }
    fun setLibraryOrder(keys: List<String>) { val t = libType.value ?: return; viewModelScope.launch { rowLayouts.setLibraryOrder(t, keys) } }
    fun loadLibraries(type: MediaType) {
        libType.value = type
        if (librariesFor == type && _libraries.value != null) return
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

    /** Opens a library as you last left it (its sort and filters), or (null) goes back to the page's rows. */
    fun openLibrary(library: io.github.petebutty.viewarr.data.media.MediaLibrary?, type: MediaType) {
        browseJob?.cancel(); queryJob?.cancel(); pageJobs.values.forEach { it.cancel() }; pageJobs.clear()
        if (library == null) { _browse.value = null; return }
        val saved = libraryViews.load(library.key)
        val start = LibraryBrowse(library, type, saved ?: io.github.petebutty.viewarr.data.media.LibraryQuery("", true), version = nextVersion())
        _browse.value = start
        browseJob = viewModelScope.launch {
            val options = runCatching { repo.libraryOptions(library, type) }.getOrNull()
                ?: io.github.petebutty.viewarr.data.media.LibraryOptions(emptyList(), emptyList(), false, "")
            val ids = options.sorts.map { it.id }
            // A saved sort this library no longer has (or none yet): newest releases first, as before.
            val q = saved?.takeIf { it.sort in ids } ?: run {
                val def = options.sorts.firstOrNull { it.id in setOf("originallyAvailableAt", "PremiereDate", "Released") } ?: options.sorts.firstOrNull()
                io.github.petebutty.viewarr.data.media.LibraryQuery(def?.id ?: "", def?.descFirst ?: true)
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
    fun toggleFilterValue(group: String, value: io.github.petebutty.viewarr.data.media.FilterValue) {
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
    private val detailStack = ArrayList<MediaItem>()

    /**
     * From the guide: a programme you've missed the start of. Finds its film (or the show's episode), opens its page
     * and plays it from the best source, as Play would. False when no match was found (search shows the title).
     */
    /** From the guide's Go to show / film: its page; a show opens on its episodes, at the one you're up to (S1 E1 if none). */
    suspend fun goToTitle(title: String, season: Int?, episode: Int?, year: Int? = null): Boolean = watchFromStart(title, season, episode, year, play = false)

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
            ?.takeIf { it.detail.item.key == item.key }?.nextUp
        val (ns, ne) = up?.let { it.first to it.second } ?: (1 to 1)
        // The guide's episode only if you're up to it (it's your next one, or you've watched past it); otherwise the
        // episodes, at the season you got up to, so you don't skip ahead.
        openEpisodes(); selectSeason(ns)
        return true
    }

    fun open(item: MediaItem) {
        if (item.key.startsWith("person:")) return openPerson(item)
        _detail.value?.detail?.item?.takeIf { it.key != item.key }?.let { detailStack.add(it) }
        showDetail(item)
    }

    private fun showDetail(item: MediaItem) {
        detailJob?.cancel()
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
            val full = repo.detail(item)
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
                list.sortedWith(compareBy({ if (home[it] == true) 0 else if (it.source == io.github.petebutty.viewarr.data.media.PROVIDER) 2 else 1 }))
            }
            took("copies (${copies.size})")
            _detail.update { it?.copy(copies = copies) }
            if (full.item.type == MediaType.Movie) launch {
                val w = runCatching { repo.filmWatched(full.item, copies) }.getOrNull()
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
            val wantRatings = pal?.lowMemory != true && io.github.petebutty.viewarr.ui.theme.TitleParts.ratings.any { (src, _) -> src != "TMDB" && io.github.petebutty.viewarr.ui.theme.TitleParts.rating(src) !in hidden }
            if (wantRatings) launch {
                val ratings = runCatching { repo.extraRatings(full.item, copies) }.getOrDefault(emptyList())
                _detail.update { if (it?.detail?.item?.key == full.item.key) it.copy(ratings = ratings) else it }
            }
            if (io.github.petebutty.viewarr.ui.theme.TitleParts.REVIEWS !in hidden) launch {
                val user = runCatching { repo.traktReviews(full.item) }.getOrDefault(emptyList())
                if (user.isNotEmpty()) _detail.update { u ->
                    if (u?.detail?.item?.key != full.item.key) u else u.copy(detail = u.detail.copy(reviews = user + u.detail.reviews))
                }
            }
            if (show) {
                fun same(u: DetailUi?) = u?.detail?.item?.key == full.item.key
                launch {
                    // Your Trakt history: episodes watched anywhere get their tick.
                    runCatching { repo.traktWatched() }
                    val seen = repo.traktEpisodesOf(full.item)
                    if (seen.isNotEmpty()) _detail.update { if (same(it)) it!!.copy(traktEpisodes = seen) else it }
                }
                val showKey = copies.firstOrNull { it.source == "Plex" }?.ref
                val plexEps = async { showKey?.let { runCatching { repo.plexEpisodes(it) }.getOrDefault(emptyMap()) }.orEmpty().also { e -> took("plex episodes"); _detail.update { if (same(it)) it!!.copy(plexEpisodes = e) else it } } }
                val providerRef = copies.firstOrNull { it.source == io.github.petebutty.viewarr.data.media.PROVIDER }?.ref
                val providerEps = async { providerRef?.let { runCatching { repo.providerEpisodes(it) }.getOrDefault(emptyMap()) }.orEmpty().also { e -> _detail.update { if (same(it)) it!!.copy(providerEpisodes = e) else it } } }
                val serverCopy = copies.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }
                val serverEps = async { serverCopy?.let { runCatching { repo.serverEpisodes(it.source, it.ref!!) }.getOrDefault(emptyMap()) }.orEmpty().also { e -> _detail.update { if (same(it)) it!!.copy(serverEpisodes = e) else it } } }
                // Where you're up to: what Plex / Jellyfin / Trakt say, else worked out from the ticks (Plex can mark an
                // episode watched yet name nothing "on deck", and Trakt may not know: the next was S1 E1 after E1).
                launch {
                    val said = runCatching { repo.nextEpisodeFor(full.item, copies) }.getOrNull()
                    val next = said ?: run {
                        val plex = plexEps.await(); val server = serverEps.await()
                        val trakt = runCatching { repo.traktWatched(); repo.traktEpisodesOf(full.item) }.getOrDefault(emptySet())
                        val all = (plex.keys + server.keys).filter { it.first > 0 }.sortedWith(compareBy({ it.first }, { it.second }))
                        fun watched(k: Pair<Int, Int>) = plex[k]?.watched == true || server[k]?.watched == true || k in trakt
                        val last = all.lastOrNull(::watched) ?: return@run null
                        val after = all.firstOrNull { compareValuesBy(it, last, { p -> p.first }, { p -> p.second }) > 0 && !watched(it) } ?: return@run null
                        Triple(after.first, after.second, ((plex[after] ?: server[after])?.viewOffsetMs ?: 0) > 0)
                    }
                    took("up to")
                    _detail.update { if (same(it)) it!!.copy(nextUp = next, nextUpChecked = true) else it }
                }
                // A show TMDB has no seasons for: from Plex, else from the provider's / server's own episode lists.
                if (full.seasons.isEmpty()) {
                    val seasons = showKey?.let { runCatching { repo.plexSeasons(it) }.getOrDefault(emptyList()) }.orEmpty()
                        .ifEmpty {
                            (providerEps.await().keys + serverEps.await().keys).groupBy { it.first }.toSortedMap().map { (n, eps) ->
                                io.github.petebutty.viewarr.data.media.SeasonInfo(n, "Season $n", eps.size, null)
                            }
                        }
                    _detail.update { if (same(it)) it!!.copy(seasons = seasons) else it }
                }
                plexEps.await()
            }
        }
    }

    /** Films: Mark watched / unwatched (your servers and Trakt). */
    fun toggleWatched() {
        val d = _detail.value ?: return
        val to = d.watched != true
        _detail.update { it?.copy(watched = to) }
        viewModelScope.launch {
            val ok = runCatching { repo.setFilmWatched(d.detail.item, d.copies.orEmpty(), to) }.getOrDefault(false)
            _detail.update {
                if (it?.detail?.item?.key != d.detail.item.key) it
                else if (ok) it.copy(message = if (to) "Marked as watched" else "Marked as unwatched")
                else it.copy(watched = !to, message = "Couldn't change it on your servers or Trakt. Try again in a moment.")
            }
        }
    }

    /** Audio & subtitles picked on a film's page, for when Play starts it (null = the usual choice). */
    fun pickTracks(audio: Int?, subtitle: Int?) = _detail.update { it?.copy(pickedAudio = audio, pickedSubtitle = subtitle) }

    fun close() {
        detailJob?.cancel()
        val previous = detailStack.removeLastOrNull()
        if (previous != null) showDetail(previous) else _detail.value = null
    }

    // ---------------------------------------------------------------- shows: seasons & episodes

    fun openEpisodes() {
        val d = _detail.value ?: return
        val start = d.detail.item.nextSeason?.takeIf { n -> d.seasons.any { it.number == n } } ?: d.seasons.minOfOrNull { it.number }
        if (start == null) {
            _detail.update { it?.copy(message = if (d.loading || d.copies == null) "Still loading. Try again in a moment." else "No episode list was found for this show.") }
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
                        io.github.petebutty.viewarr.data.media.EpisodeInfo(s, e, "Episode $e", null, null, null, null)
                    }
                }
            _detail.update { if (it?.season == n) it.copy(episodes = eps) else it }
        }
    }

    /** Back from an episode's stream list returns to that episode's options. */
    fun closeEpisodeStreams() = _detail.update { it?.copy(streams = null, message = null) }

    fun pickEpisode(ep: io.github.petebutty.viewarr.data.media.EpisodeInfo?) = _detail.update { it?.copy(episode = ep, message = null) }

    /** Plays one episode from Plex (resuming where you left off). */
    fun playPlexEpisode(season: Int, episode: Int) {
        val d = _detail.value ?: return
        viewModelScope.launch {
            val plexEps = d.plexEpisodes.ifEmpty { d.plexShowKey?.let { repo.plexEpisodes(it) }.orEmpty() }
            val ref = plexEps[season to episode]?.ratingKey
            if (ref == null) {
                _detail.update { it?.copy(message = "S$season · E$episode isn't in your Plex library.") }
                return@launch
            }
            _detail.update { it?.copy(message = "Opening on Plex…") }
            val stream = runCatching { repo.plexStream(ref, watchTarget(d.detail.item, season, episode)) }.getOrNull()
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on Plex.") }
            else {
                _detail.update { it?.copy(message = null, episode = null) }
                _playing.value = stream.copy(title = "${d.detail.item.title} · S$season · E$episode")
            }
        }
    }

    /** The Jellyfin / Emby copy of the open title, if any: (source, item id). */
    private fun serverCopy(d: DetailUi) = d.copies?.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }

    /** A film from Jellyfin / Emby (resuming where you left off). */
    fun playServer() {
        val d = _detail.value ?: return
        if (d.detail.item.type == MediaType.Show) return openEpisodes()
        val copy = serverCopy(d) ?: return
        viewModelScope.launch {
            _detail.update { it?.copy(message = "Opening on ${copy.source}…") }
            val stream = runCatching { repo.serverStream(copy.source, copy.ref!!, d.detail.item.title, watchTarget(d.detail.item, null, null)) }.getOrNull()
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on ${copy.source}.") }
            else { _detail.update { it?.copy(message = null) }; _playing.value = stream }
        }
    }

    /** One episode from Jellyfin / Emby. */
    fun playServerEpisode(season: Int, episode: Int) {
        val d = _detail.value ?: return
        val source = serverCopy(d)?.source ?: return
        val id = d.serverEpisodes[season to episode]?.ratingKey ?: run {
            _detail.update { it?.copy(message = "S$season · E$episode isn't on $source.") }
            return
        }
        viewModelScope.launch {
            val stream = runCatching {
                repo.serverStream(source, id, "${d.detail.item.title} · S$season · E$episode", watchTarget(d.detail.item, season, episode))
            }.getOrNull()
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on $source.") }
            else { _detail.update { it?.copy(message = null, episode = null) }; _playing.value = stream }
        }
    }

    /** A film from the IPTV provider. */
    fun playProvider() {
        val d = _detail.value ?: return
        if (d.detail.item.type == MediaType.Show) return openEpisodes()
        val ref = d.copies?.firstOrNull { it.source == io.github.petebutty.viewarr.data.media.PROVIDER }?.ref ?: return
        viewModelScope.launch {
            val stream = repo.providerMovie(ref, d.detail.item.title, watchTarget(d.detail.item, null, null))
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this from IPTV.") }
            else { _detail.update { it?.copy(message = null) }; _playing.value = stream }
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
        _detail.update { it?.copy(message = null, episode = null) }
        _playing.value = io.github.petebutty.viewarr.data.media.VodStream(
            url = url, title = "${d.detail.item.title} · S$season · E$episode", watch = watchTarget(d.detail.item, season, episode),
            boxPreviews = false,
        )
    }

    /** The "Play S2 E4" button on a show from continue watching. */
    fun playNextUp() {
        val d = _detail.value ?: return
        val item = d.detail.item
        // Continue watching's episode, else where your servers / Trakt say you're up to, else the first episode.
        val s = item.nextSeason ?: d.nextUp?.first ?: d.seasons.map { it.number }.filter { it > 0 }.minOrNull() ?: 1
        val e = (if (item.nextSeason != null) item.nextEpisode else d.nextUp?.second) ?: 1
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
        viewModelScope.launch {
            data class Try(val label: String, val home: Boolean, val get: suspend () -> io.github.petebutty.viewarr.data.media.VodStream?)
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
            for (t in tries.sortedByDescending { it.home }) {
                _detail.update { it?.copy(message = "Opening S$season · E$episode on ${t.label}…") }
                val stream = kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { t.get() }.getOrNull() }
                if (stream != null) { _detail.update { it?.copy(message = null, episode = null) }; _playing.value = stream; return@launch }
            }
            if (d.hasAddons && item.imdbId != null) {
                _detail.update { it?.copy(searching = true, message = "Looking in your addons for S$season · E$episode…") }
                val found = kotlinx.coroutines.withTimeoutOrNull(30_000) { rank(runCatching { repo.addonStreams(item, season, episode) }.getOrDefault(emptyList())) }.orEmpty()
                _detail.update { it?.copy(searching = false) }
                if (found.isNotEmpty() && !autoPick()) {
                    val ep = d.episodes?.firstOrNull { it.season == season && it.number == episode }
                    _detail.update { it?.copy(message = null, streams = found, episode = ep ?: it.episode) }
                    return@launch
                }
                if (found.isNotEmpty()) {
                    val opt = found.first()
                    _detail.update { it?.copy(message = null, episode = null) }
                    _playing.value = io.github.petebutty.viewarr.data.media.VodStream(opt.url, title, opt.headers, watch = watch, boxPreviews = false)
                    return@launch
                }
            }
            d.providerEpisodes[season to episode]?.let { return@launch playProviderEpisode(season, episode) }
            _detail.update { it?.copy(message = "Couldn't play S$season · E$episode from any of your sources. Hold OK on it to pick one.") }
        }
    }

    fun searchEpisode(ep: io.github.petebutty.viewarr.data.media.EpisodeInfo, forceAuto: Boolean = false) {
        val d = _detail.value ?: return
        if (d.detail.item.imdbId == null) {
            _detail.update { it?.copy(message = "This show has no IMDb id, so the addons can't look it up.") }
            return
        }
        viewModelScope.launch {
            _detail.update { it?.copy(searching = true, streams = null, message = "Searching your addons for S${ep.season} · E${ep.number}…") }
            val found = rank(runCatching { repo.addonStreams(d.detail.item, ep.season, ep.number) }.getOrDefault(emptyList()))
            if (found.isNotEmpty() && (autoPick() || forceAuto)) {
                _detail.update { it?.copy(searching = false, message = null) }
                playStream(found.first())
                return@launch
            }
            _detail.update {
                it?.copy(searching = false, streams = found, message = if (found.isEmpty()) "Your addons didn't find any playable streams for this episode." else null)
            }
        }
    }

    fun playPlex() {
        val d = _detail.value ?: return
        if (d.detail.item.type == MediaType.Show) return openEpisodes()
        // Every Plex server that has it, in turn: one server's file can be missing while another's is fine.
        val refs = d.copies?.filter { it.source == "Plex" }?.mapNotNull { it.ref }.orEmpty()
        if (refs.isEmpty()) return
        viewModelScope.launch {
            _detail.update { it?.copy(message = "Opening on Plex…") }
            var stream: io.github.petebutty.viewarr.data.media.VodStream? = null
            for (ref in refs) {
                stream = runCatching { repo.plexStream(ref, watchTarget(d.detail.item, null, null)) }.getOrNull()
                if (stream != null) break
            }
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on Plex: the file seems to be missing on your Plex server${if (refs.size > 1) "s" else ""}.") }
            else {
                _detail.update { it?.copy(message = null) }
                _playing.value = stream
            }
        }
    }

    /**
     * The one Play button for a film: tries every way you have of watching it, best first, and plays the first
     * that works.
     *  1. Your servers on the home network (Plex, Jellyfin, Emby): full quality, no internet needed.
     *  2. Your servers away from home (another house, a remote Plex).
     *  3. Stremio addons: the best-ranked stream (the stream ranking in Settings → Playback).
     *  4. IPTV (your provider's copy through Dispatcharr).
     * A source is skipped when its file has gone (Plex checks), it doesn't answer, or it has nothing; the
     * message under the buttons says which one it's on.
     */
    fun playBest() {
        val d = _detail.value ?: return
        if (d.detail.item.type == MediaType.Show) return openEpisodes()
        val item = d.detail.item
        viewModelScope.launch {
            val copies = d.copies.orEmpty()
            val servers = copies.filter { it.ref != null && (it.source == "Plex" || it.source == "Jellyfin" || it.source == "Emby") }
            val home = servers.filter { runCatching { repo.isHomeCopy(it) }.getOrDefault(false) }
            for (c in home + (servers - home.toSet())) {
                _detail.update { it?.copy(message = "Opening on ${copyLabel(c)}…") }
                val stream = kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { streamFor(c, item) }.getOrNull() }
                if (stream != null) { _detail.update { it?.copy(message = null) }; _playing.value = stream; return@launch }
            }
            if (d.hasAddons && item.imdbId != null) {
                _detail.update { it?.copy(searching = true, message = "Looking in your addons…") }
                val found = kotlinx.coroutines.withTimeoutOrNull(30_000) { rank(runCatching { repo.addonStreams(item) }.getOrDefault(emptyList())) }.orEmpty()
                _detail.update { it?.copy(searching = false) }
                // Stream pick set to Manual (Settings → Playback): the list to choose from, as before.
                if (found.isNotEmpty() && !autoPick()) { _detail.update { it?.copy(message = null, streams = found) }; return@launch }
                if (found.isNotEmpty()) { _detail.update { it?.copy(message = null) }; playStream(found.first()); return@launch }
            }
            copies.firstOrNull { it.source == io.github.petebutty.viewarr.data.media.PROVIDER }?.ref?.let { ref ->
                _detail.update { it?.copy(message = "Opening from IPTV…") }
                val stream = runCatching { repo.providerMovie(ref, item.title, watchTarget(item, null, null)) }.getOrNull()
                if (stream != null) { _detail.update { it?.copy(message = null) }; _playing.value = stream; return@launch }
            }
            _detail.update {
                it?.copy(message = if (copies.isEmpty() && !d.hasAddons) "This isn't in any of your libraries, and no addon is connected."
                else "Couldn't play this from any of your sources. Hold OK on Play to pick one.")
            }
        }
    }

    /** A copy on one of your servers, ready to play (null when it can't be: e.g. Plex says the file has gone). */
    private suspend fun streamFor(c: io.github.petebutty.viewarr.data.media.LibraryCopy, item: io.github.petebutty.viewarr.data.media.MediaItem) =
        if (c.source == "Plex") repo.plexStream(c.ref!!, watchTarget(item, null, null))
        else repo.serverStream(c.source, c.ref!!, item.title, watchTarget(item, null, null))

    fun copyLabel(c: io.github.petebutty.viewarr.data.media.LibraryCopy) = listOfNotNull(c.source, c.detail).joinToString(" · ")

    /** Other sources → one particular copy. */
    fun playCopy(c: io.github.petebutty.viewarr.data.media.LibraryCopy) {
        val d = _detail.value ?: return
        if (c.source == io.github.petebutty.viewarr.data.media.PROVIDER) return playProvider()
        viewModelScope.launch {
            _detail.update { it?.copy(message = "Opening on ${copyLabel(c)}…") }
            val stream = runCatching { streamFor(c, d.detail.item) }.getOrNull()
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on ${copyLabel(c)}.") }
            else { _detail.update { it?.copy(message = null) }; _playing.value = stream }
        }
    }

    fun searchAddons() {
        val d = _detail.value ?: return
        if (d.detail.item.type == MediaType.Show) return openEpisodes()
        if (d.detail.item.imdbId == null) {
            _detail.update { it?.copy(message = "This title has no IMDb id, so the addons can't look it up.") }
            return
        }
        viewModelScope.launch {
            _detail.update { it?.copy(searching = true, streams = null, message = "Searching your addons…") }
            val found = rank(runCatching { repo.addonStreams(d.detail.item) }.getOrDefault(emptyList()))
            if (found.isNotEmpty() && autoPick()) {
                _detail.update { it?.copy(searching = false, message = null) }
                playStream(found.first())
                return@launch
            }
            _detail.update {
                it?.copy(searching = false, streams = found, message = if (found.isEmpty()) "Your addons didn't find any playable streams." else null)
            }
        }
    }

    fun playStream(option: io.github.petebutty.viewarr.data.media.StreamOption) {
        val d = _detail.value
        val title = listOfNotNull(d?.detail?.item?.title, d?.episode?.let { "S${it.season} · E${it.number}" }).joinToString(" · ").ifEmpty { option.name }
        val ep = d?.episode
        _playing.value = io.github.petebutty.viewarr.data.media.VodStream(
            option.url, title, option.headers,
            watch = d?.detail?.item?.let { watchTarget(it, ep?.season, ep?.number) },
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
        if (playbackStore.current().autoNext != io.github.petebutty.viewarr.data.settings.AutoNext.On) return
        val d = _detail.value ?: return
        val inSeason = (if (d.season == season) d.episodes else null)
            ?: runCatching { repo.seasonEpisodes(d.detail.item, season, d.plexShowKey) }.getOrDefault(emptyList())
        val next = inSeason.firstOrNull { it.number == episode + 1 }
            ?: d.seasons.map { it.number }.filter { it > season }.minOrNull()?.let { ns ->
                runCatching { repo.seasonEpisodes(d.detail.item, ns, d.plexShowKey) }.getOrDefault(emptyList()).firstOrNull()
            }
            ?: return
        _upNext.value = UpNext(next)
    }

    fun cancelUpNext() { _upNext.value = null }

    /** Plays the up-next episode: from Plex if it's there, otherwise the best addon stream. */
    fun playUpNext() {
        val next = _upNext.value?.episode ?: return
        _upNext.value = null
        val d = _detail.value ?: return
        _detail.update { it?.copy(episode = next) }
        if (d.plexEpisodes.containsKey(next.season to next.number)) playPlexEpisode(next.season, next.number)
        else if (d.hasAddons) searchEpisode(next, forceAuto = true)
    }

    private fun watchTarget(item: MediaItem, season: Int?, episode: Int?) =
        io.github.petebutty.viewarr.data.media.WatchTarget(item.type, tmdbId = item.tmdbId, imdbId = item.imdbId, season = season, episode = episode)

    /** Player progress → Plex timeline and Trakt scrobble. Trakt only hears about changes (start/pause/stop). */
    private var lastState = ""
    fun reportProgress(state: String, positionMs: Long, durationMs: Long) {
        val target = _playing.value?.watch ?: return
        // Set straight away (not in the coroutine): the player closes right after reporting the end.
        if (state == "stopped" && durationMs > 0 && positionMs >= durationMs * 0.95) endedNearEnd = target
        // The TV home screen's Watch Next row follows: once the servers have the new position, refresh it.
        if (state == "stopped") viewModelScope.launch { kotlinx.coroutines.delay(5_000); watchNext.refresh() }
        viewModelScope.launch {
            target.plexRatingKey?.let { repo.reportPlex(it, state, positionMs, durationMs) }
            if (target.serverSource != null && target.serverItemId != null) repo.reportServer(target.serverSource, target.serverItemId, state, positionMs)
            if (state != lastState) {
                lastState = state
                val action = when (state) { "playing" -> "start"; "paused" -> "pause"; else -> "stop" }
                val pct = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                repo.scrobbleTrakt(action, target, pct)
            }
            if (state == "stopped") lastState = ""
        }
    }

    // ---- Quick options (hold OK on a poster)

    /** A short note shown at the bottom of Movies / Shows (e.g. "Added to your watchlist"). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    fun clearNotice() { _notice.value = null }

    /** Whether Trakt is connected and the title is on the watchlist (null = no Trakt, so no watchlist option). */
    suspend fun watchlistState(item: MediaItem): Boolean? = runCatching { repo.inWatchlist(item) }.getOrNull()

    fun quickWatchlist(item: MediaItem, add: Boolean) {
        viewModelScope.launch {
            val result = repo.setWatchlist(item, add)
            val name = repo.watchlistName()
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
            val result = repo.setWatchlist(d.detail.item, add = !d.watchlisted)
            val name = repo.watchlistName()
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

    fun apply(rows: List<io.github.petebutty.viewarr.data.media.MediaRow>): List<io.github.petebutty.viewarr.data.media.MediaRow> = when (this) {
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
