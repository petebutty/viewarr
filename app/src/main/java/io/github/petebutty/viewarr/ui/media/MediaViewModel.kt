package io.github.petebutty.viewarr.ui.media

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
    val season: Int? = null,
    /** null while loading. */
    val episodes: List<io.github.petebutty.viewarr.data.media.EpisodeInfo>? = null,
    val plexEpisodes: Map<Pair<Int, Int>, io.github.petebutty.viewarr.data.media.PlexEpisode> = emptyMap(),
    /** Jellyfin / Emby series: (season, episode) → episode id, resume point, watched. */
    val serverEpisodes: Map<Pair<Int, Int>, io.github.petebutty.viewarr.data.media.PlexEpisode> = emptyMap(),
    /** IPTV provider series: (season, episode) → stream address. */
    val providerEpisodes: Map<Pair<Int, Int>, String> = emptyMap(),
    /** The episode whose play options are open. */
    val episode: io.github.petebutty.viewarr.data.media.EpisodeInfo? = null,
    /** IMDb / Rotten Tomatoes (from Plex) and Trakt, beside TMDB's. */
    val ratings: List<io.github.petebutty.viewarr.data.media.RatingBadge> = emptyList(),
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
) : ViewModel() {
    /** Seerr: where this title stands (null = Seerr not set up, or the title has no TMDB id). */
    suspend fun seerrStatus(item: MediaItem): io.github.petebutty.viewarr.data.remote.services.SeerrService.Status? {
        val a = store.current().seerr ?: return null
        val id = runCatching { repo.tmdbIdFor(item) }.getOrNull() ?: return null
        // Already in one of your libraries: nothing to request.
        if (runCatching { repo.copiesOf(item) }.getOrDefault(emptyList()).isNotEmpty()) return null
        // Library check with the TMDB id known (addon titles only carry an IMDb id).
        if (item.tmdbId == null && runCatching { repo.copiesOf(item.copy(tmdbId = id)) }.getOrDefault(emptyList()).isNotEmpty()) return null
        val st = runCatching { seerr.status(a, item.type, id) }.getOrDefault(io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.None)
        return st.takeIf { it != io.github.petebutty.viewarr.data.remote.services.SeerrService.Status.Available }
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

    val rows: kotlinx.coroutines.flow.StateFlow<RowsState> = kotlinx.coroutines.flow.combine(shownRows, layout, _searchFilter, openedFromMenu) { r, l, f, extra ->
        if (l != null) r.copy(rows = l.ordered(r.rows).filter { it.id !in l.hidden || it.id == extra }) else r.copy(rows = f.apply(r.rows))
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
    private val _playing = MutableStateFlow<io.github.petebutty.viewarr.data.media.VodStream?>(null)
    val playing = _playing.asStateFlow()

    /** From a title page: a cast member's films and shows (the title page closes). */
    fun openCastMember(c: io.github.petebutty.viewarr.data.media.CastMember) {
        val id = c.id ?: return
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
            _playing.map { it?.url }.distinctUntilChanged().collect { url ->
                // (url first: the first, empty value arrives while this class is still being built.)
                if (url == null) { _playerArt.value = null; return@collect }
                val item = _detail.value?.detail?.item ?: run { _playerArt.value = null; return@collect }
                val art = io.github.petebutty.viewarr.data.media.PlayerArt(item.backdrop, null)
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

    private var lastQuery = ""

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
        _personInfo.value = io.github.petebutty.viewarr.data.media.PersonInfo(item.title, item.poster, null, null, null, null, null)
        viewModelScope.launch {
            runCatching { repo.personInfo(id, item.title) }.getOrNull()?.let { info -> if (_person.value == item.title) _personInfo.value = info }
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

    /** A library being browsed: one page of it (so the box only ever holds [LIBRARY_PAGE] titles), sorted. */
    data class LibraryBrowse(
        val library: io.github.petebutty.viewarr.data.media.MediaLibrary,
        val type: MediaType,
        val sort: io.github.petebutty.viewarr.data.media.LibrarySort,
        val page: Int = 0,
        val items: List<MediaItem> = emptyList(),
        val total: Int = -1,
        val loading: Boolean = true,
        val failed: Boolean = false,
        /** Search within the library ("" = everything). */
        val query: String = "",
        /** Genre filter (server id to name), and unwatched only. */
        val genre: Pair<String, String>? = null,
        val unwatched: Boolean = false,
    ) {
        val filtered get() = query.isNotBlank() || genre != null || unwatched
        val pages get() = if (total <= 0) 1 else (total + LIBRARY_PAGE - 1) / LIBRARY_PAGE
        val hasNext get() = page + 1 < pages
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

    /** Opens a library on its first page, or (null) goes back to the page's rows. */
    fun openLibrary(library: io.github.petebutty.viewarr.data.media.MediaLibrary?, type: MediaType) {
        browseJob?.cancel()
        queryJob?.cancel()
        if (library == null) { _browse.value = null; return }
        val sort = _browse.value?.takeIf { it.library.key == library.key }?.sort ?: io.github.petebutty.viewarr.data.media.LibrarySort.Released
        showLibraryPage(LibraryBrowse(library, type, sort), 0)
    }

    fun setLibrarySort(sort: io.github.petebutty.viewarr.data.media.LibrarySort) {
        val b = _browse.value ?: return
        if (b.sort != sort) showLibraryPage(b.copy(sort = sort, total = -1), 0)
    }

    /** The open library's genres (null while loading). */
    private val _genres = MutableStateFlow<List<Pair<String, String>>?>(null)
    val genres = _genres.asStateFlow()
    private var genresFor: String? = null
    fun loadGenres() {
        val b = _browse.value ?: return
        if (genresFor == b.library.key && _genres.value != null) return
        genresFor = b.library.key
        _genres.value = null
        viewModelScope.launch {
            val got = runCatching { repo.libraryGenres(b.library, b.type) }.getOrDefault(emptyList())
            if (genresFor == b.library.key) _genres.value = got
        }
    }
    fun setLibraryGenre(g: Pair<String, String>?) {
        val b = _browse.value ?: return
        if (b.genre != g) showLibraryPage(b.copy(genre = g, total = -1), 0)
    }
    fun toggleUnwatched() {
        val b = _browse.value ?: return
        showLibraryPage(b.copy(unwatched = !b.unwatched, total = -1), 0)
    }

    private var queryJob: kotlinx.coroutines.Job? = null
    /** Search this library: results a page at a time, as you type (short pause first). */
    fun setLibraryQuery(q: String) {
        val b = _browse.value ?: return
        if (b.query == q) return
        _browse.value = b.copy(query = q)
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            kotlinx.coroutines.delay(450)
            _browse.value?.let { showLibraryPage(it.copy(total = -1), 0) }
        }
    }

    fun libraryPage(delta: Int) {
        val b = _browse.value ?: return
        val to = (b.page + delta).coerceIn(0, b.pages - 1)
        if (to != b.page) showLibraryPage(b, to)
    }

    private fun showLibraryPage(b: LibraryBrowse, page: Int) {
        browseJob?.cancel()
        _browse.value = b.copy(page = page, items = emptyList(), loading = true, failed = false)
        browseJob = viewModelScope.launch {
            val got = runCatching { repo.libraryPage(b.library, b.type, b.sort, page * LIBRARY_PAGE, LIBRARY_PAGE, b.query, b.genre?.first, b.unwatched) }.getOrNull()
            _browse.update { now ->
                if (now == null || now.library.key != b.library.key || now.sort != b.sort || now.page != page || now.query != b.query || now.genre != b.genre || now.unwatched != b.unwatched) now
                else if (got == null) now.copy(loading = false, failed = true)
                else now.copy(items = got.first.distinctBy { it.key }, total = got.second, loading = false)
            }
        }
    }

    /** Picks up your other Plex servers (e.g. a second machine) so their titles count as yours. */
    fun refreshPlexServers() { viewModelScope.launch { runCatching { repo.refreshPlexServers() } } }

    /** The TV home screen's Watch Next row (Continue watching), brought up to date. */
    fun refreshWatchNext() { viewModelScope.launch { watchNext.refresh() } }

    /** Titles opened from inside a title page (More like this): Back goes back through them, one at a time. */
    private val detailStack = ArrayList<MediaItem>()

    fun open(item: MediaItem) {
        if (item.key.startsWith("person:")) return openPerson(item)
        _detail.value?.detail?.item?.takeIf { it.key != item.key }?.let { detailStack.add(it) }
        showDetail(item)
    }

    private fun showDetail(item: MediaItem) {
        detailJob?.cancel()
        _detail.value = DetailUi(MediaDetail(item))
        detailJob = viewModelScope.launch {
            val accounts = store.current()
            val hasTrakt = accounts.trakt != null
            _detail.update { it?.copy(hasAddons = accounts.activeAddons.isNotEmpty()) }
            val full = repo.detail(item)
            _detail.update {
                it?.copy(detail = full, loading = false, hasTrakt = hasTrakt && full.item.tmdbId != null, watchlisted = repo.inTraktWatchlist(full.item))
            }
            // Home servers first (then away, then IPTV): the order they're listed in and tried by Play.
            val copies = runCatching { repo.copiesOf(full.item) }.getOrDefault(emptyList()).let { list ->
                val home = list.associateWith { c -> runCatching { repo.isHomeCopy(c) }.getOrDefault(false) }
                list.sortedWith(compareBy({ if (home[it] == true) 0 else if (it.source == io.github.petebutty.viewarr.data.media.PROVIDER) 2 else 1 }))
            }
            _detail.update { it?.copy(copies = copies) }
            launch {
                val ratings = runCatching { repo.extraRatings(full.item, copies) }.getOrDefault(emptyList())
                _detail.update { if (it?.detail?.item?.key == full.item.key) it.copy(ratings = ratings) else it }
            }
            launch {
                val user = runCatching { repo.traktReviews(full.item) }.getOrDefault(emptyList())
                if (user.isNotEmpty()) _detail.update { u ->
                    if (u?.detail?.item?.key != full.item.key) u else u.copy(detail = u.detail.copy(reviews = user + u.detail.reviews))
                }
            }
            if (full.item.type == MediaType.Show) {
                val showKey = copies.firstOrNull { it.source == "Plex" }?.ref
                val plexEps = showKey?.let { runCatching { repo.plexEpisodes(it) }.getOrDefault(emptyMap()) }.orEmpty()
                val providerRef = copies.firstOrNull { it.source == io.github.petebutty.viewarr.data.media.PROVIDER }?.ref
                val providerEps = providerRef?.let { repo.providerEpisodes(it) }.orEmpty()
                val serverCopy = copies.firstOrNull { (it.source == "Jellyfin" || it.source == "Emby") && it.ref != null }
                val serverEps = serverCopy?.let { repo.serverEpisodes(it.source, it.ref!!) }.orEmpty()
                // A provider-only series (no TMDB match) gets its seasons from the provider's own episode list.
                val seasons = full.seasons
                    .ifEmpty { showKey?.let { runCatching { repo.plexSeasons(it) }.getOrDefault(emptyList()) }.orEmpty() }
                    .ifEmpty {
                        (providerEps.keys + serverEps.keys).groupBy { it.first }.toSortedMap().map { (n, eps) ->
                            io.github.petebutty.viewarr.data.media.SeasonInfo(n, "Season $n", eps.size, null)
                        }
                    }
                _detail.update { it?.copy(plexEpisodes = plexEps, providerEpisodes = providerEps, serverEpisodes = serverEps, seasons = seasons) }
                val next = runCatching { repo.nextEpisodeFor(full.item, copies) }.getOrNull()
                _detail.update { if (it?.detail?.item?.key == full.item.key) it.copy(nextUp = next) else it }
            }
        }
    }

    fun close() {
        detailJob?.cancel()
        val previous = detailStack.removeLastOrNull()
        if (previous != null) showDetail(previous) else _detail.value = null
    }

    // ---------------------------------------------------------------- shows: seasons & episodes

    fun openEpisodes() {
        val d = _detail.value ?: return
        val start = d.detail.item.nextSeason?.takeIf { n -> d.seasons.any { it.number == n } } ?: d.seasons.firstOrNull()?.number
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
                _detail.update { it?.copy(message = "S$season E$episode isn't in your Plex library.") }
                return@launch
            }
            _detail.update { it?.copy(message = "Opening on Plex…") }
            val stream = runCatching { repo.plexStream(ref, watchTarget(d.detail.item, season, episode)) }.getOrNull()
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on Plex.") }
            else {
                _detail.update { it?.copy(message = null, episode = null) }
                _playing.value = stream.copy(title = "${d.detail.item.title} · S$season E$episode")
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
            _detail.update { it?.copy(message = "S$season E$episode isn't on $source.") }
            return
        }
        viewModelScope.launch {
            val stream = runCatching {
                repo.serverStream(source, id, "${d.detail.item.title} · S$season E$episode", watchTarget(d.detail.item, season, episode))
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
            _detail.update { it?.copy(message = "IPTV doesn't have S$season E$episode.") }
            return
        }
        _detail.update { it?.copy(message = null, episode = null) }
        _playing.value = io.github.petebutty.viewarr.data.media.VodStream(
            url = url, title = "${d.detail.item.title} · S$season E$episode", watch = watchTarget(d.detail.item, season, episode),
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
        val title = "${item.title} · S$season E$episode"
        val watch = watchTarget(item, season, episode)
        viewModelScope.launch {
            data class Try(val label: String, val home: Boolean, val get: suspend () -> io.github.petebutty.viewarr.data.media.VodStream?)
            val tries = ArrayList<Try>()
            val plexEps = d.plexEpisodes.ifEmpty { d.plexShowKey?.let { runCatching { repo.plexEpisodes(it) }.getOrNull() }.orEmpty() }
            plexEps[season to episode]?.ratingKey?.let { rk ->
                val c = d.copies?.firstOrNull { it.source == "Plex" }
                tries += Try("Plex", c != null && runCatching { repo.isHomeCopy(c) }.getOrDefault(false)) { repo.plexStream(rk, watch)?.copy(title = title) }
            }
            serverCopy(d)?.let { c ->
                d.serverEpisodes[season to episode]?.ratingKey?.let { id ->
                    tries += Try(c.source, runCatching { repo.isHomeCopy(c) }.getOrDefault(false)) { repo.serverStream(c.source, id, title, watch) }
                }
            }
            for (t in tries.sortedByDescending { it.home }) {
                _detail.update { it?.copy(message = "Opening S$season E$episode on ${t.label}…") }
                val stream = kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { t.get() }.getOrNull() }
                if (stream != null) { _detail.update { it?.copy(message = null, episode = null) }; _playing.value = stream; return@launch }
            }
            if (d.hasAddons && item.imdbId != null) {
                _detail.update { it?.copy(searching = true, message = "Looking in your addons for S$season E$episode…") }
                val found = kotlinx.coroutines.withTimeoutOrNull(30_000) { rank(runCatching { repo.addonStreams(item, season, episode) }.getOrDefault(emptyList())) }.orEmpty()
                _detail.update { it?.copy(searching = false) }
                if (found.isNotEmpty()) {
                    val opt = found.first()
                    _detail.update { it?.copy(message = null, episode = null) }
                    _playing.value = io.github.petebutty.viewarr.data.media.VodStream(opt.url, title, opt.headers, watch = watch, boxPreviews = false)
                    return@launch
                }
            }
            d.providerEpisodes[season to episode]?.let { return@launch playProviderEpisode(season, episode) }
            _detail.update { it?.copy(message = "Couldn't play S$season E$episode from any of your sources. Hold OK on it to pick one.") }
        }
    }

    fun searchEpisode(ep: io.github.petebutty.viewarr.data.media.EpisodeInfo, forceAuto: Boolean = false) {
        val d = _detail.value ?: return
        if (d.detail.item.imdbId == null) {
            _detail.update { it?.copy(message = "This show has no IMDb id, so the addons can't look it up.") }
            return
        }
        viewModelScope.launch {
            _detail.update { it?.copy(searching = true, streams = null, message = "Searching your addons for S${ep.season} E${ep.number}…") }
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
        val title = listOfNotNull(d?.detail?.item?.title, d?.episode?.let { "S${it.season} E${it.number}" }).joinToString(" · ").ifEmpty { option.name }
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
    suspend fun watchlistState(item: MediaItem): Boolean? =
        if (store.current().trakt == null || item.tmdbId == null) null else repo.inTraktWatchlist(item)

    fun quickWatchlist(item: MediaItem, add: Boolean) {
        viewModelScope.launch {
            _notice.value = when (repo.setTraktWatchlist(item, add)) {
                null -> "Couldn't update your Trakt watchlist. Try again."
                true -> "Added ${item.title} to your watchlist."
                false -> "Removed ${item.title} from your watchlist."
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
            val result = repo.setTraktWatchlist(d.detail.item, add = !d.watchlisted)
            _detail.update {
                when (result) {
                    null -> it?.copy(message = "Couldn't update your Trakt watchlist. Try again.")
                    true -> it?.copy(watchlisted = true, message = "Added to your Trakt watchlist.")
                    false -> it?.copy(watchlisted = false, message = "Removed from your Trakt watchlist.")
                }
            }
        }
    }
}

/** Search filter buttons: what the results show. */
enum class SearchFilter(val label: String) {
    All("All"), Films("Films"), Shows("Shows"), LiveTv("Live TV"), Library("In my library");

    fun apply(rows: List<io.github.petebutty.viewarr.data.media.MediaRow>): List<io.github.petebutty.viewarr.data.media.MediaRow> = when (this) {
        All -> rows
        LiveTv -> emptyList()
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
