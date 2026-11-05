package io.github.petebutty.viewarr.ui.media

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
    val season: Int? = null,
    /** null while loading. */
    val episodes: List<io.github.petebutty.viewarr.data.media.EpisodeInfo>? = null,
    val plexEpisodes: Map<Pair<Int, Int>, io.github.petebutty.viewarr.data.media.PlexEpisode> = emptyMap(),
    /** The episode whose play options are open. */
    val episode: io.github.petebutty.viewarr.data.media.EpisodeInfo? = null,
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
) : ViewModel() {

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
    val rows = _rows.asStateFlow()
    private val _detail = MutableStateFlow<DetailUi?>(null)
    val detail = _detail.asStateFlow()
    private val _playing = MutableStateFlow<io.github.petebutty.viewarr.data.media.VodStream?>(null)
    val playing = _playing.asStateFlow()

    /** A row picked from the menu's catalogue list: the screen scrolls to it and focuses its first poster. */
    private val _jump = MutableStateFlow<Pair<String?, Long>?>(null)
    val jump = _jump.asStateFlow()

    /** The row that last had focus: the menu's catalogue list highlights it. */
    private val _currentRow = MutableStateFlow<String?>(null)
    val currentRow = _currentRow.asStateFlow()
    fun setCurrentRow(id: String) { if (_currentRow.value != id) _currentRow.value = id }

    fun jumpTo(rowId: String?) { _jump.value = rowId to System.nanoTime() }
    fun jumpDone() { _jump.value = null }

    private var loaded: MediaType? = null
    private var rowsJob: Job? = null
    private var detailJob: Job? = null

    private var lastQuery = ""

    /** Search mode: the rows become search results. */
    fun search(query: String) {
        val q = query.trim()
        if (q == lastQuery && q.isNotEmpty()) return
        lastQuery = q
        loaded = null
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
        rowsJob?.cancel()
        _rows.value = RowsState(emptyList(), done = false)
        rowsJob = viewModelScope.launch { repo.rows(type).collect { _rows.value = it } }
    }

    fun open(item: MediaItem) {
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
            val copies = runCatching { repo.copiesOf(full.item) }.getOrDefault(emptyList())
            _detail.update { it?.copy(copies = copies) }
            if (full.item.type == MediaType.Show) {
                val showKey = copies.firstOrNull { it.source == "Plex" }?.ref
                val plexEps = showKey?.let { runCatching { repo.plexEpisodes(it) }.getOrDefault(emptyMap()) }.orEmpty()
                val seasons = full.seasons.ifEmpty { showKey?.let { runCatching { repo.plexSeasons(it) }.getOrDefault(emptyList()) }.orEmpty() }
                _detail.update { it?.copy(plexEpisodes = plexEps, seasons = seasons) }
            }
        }
    }

    fun close() {
        detailJob?.cancel()
        _detail.value = null
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

    /** The "Play S2 E4" button on a show from continue watching. */
    fun playNextUp() {
        val item = _detail.value?.detail?.item ?: return
        val s = item.nextSeason ?: return
        val e = item.nextEpisode ?: return
        playPlexEpisode(s, e)
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
        val ref = d.copies?.firstOrNull { it.source == "Plex" }?.ref ?: return
        viewModelScope.launch {
            _detail.update { it?.copy(message = "Opening on Plex…") }
            val stream = runCatching { repo.plexStream(ref, watchTarget(d.detail.item, null, null)) }.getOrNull()
            if (stream == null) _detail.update { it?.copy(message = "Couldn't open this on Plex.") }
            else {
                _detail.update { it?.copy(message = null) }
                _playing.value = stream
            }
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
        viewModelScope.launch {
            target.plexRatingKey?.let { repo.reportPlex(it, state, positionMs, durationMs) }
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
            when {
                d.copies?.any { it.source == "Plex" && it.ref != null } == true -> playPlex()
                d.hasAddons -> searchAddons()
            }
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
