package io.github.petebutty.viewarr.ui.music

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.music.Album
import io.github.petebutty.viewarr.data.music.AlbumList
import io.github.petebutty.viewarr.data.music.Art
import io.github.petebutty.viewarr.data.music.Artist
import io.github.petebutty.viewarr.data.music.MusicLibrary
import io.github.petebutty.viewarr.data.music.Playlist
import io.github.petebutty.viewarr.data.music.Song
import io.github.petebutty.viewarr.playback.MusicPlayer
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MusicKind { Album, Playlist, Artist, Genre }

/** An album, playlist, artist or genre on the Music pages. */
data class MusicCard(val kind: MusicKind, val id: String, val title: String, val sub: String, val art: Art?) {
    val key get() = "${kind.name}:$id"
    val cover get() = art?.small
}

data class MusicRow(val title: String, val cards: List<MusicCard>)

data class MusicHome(val rows: List<MusicRow> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** The whole library, one kind at a time (from the side menu). */
enum class MusicBrowseKind(val label: String) { Albums("Albums A–Z"), Artists("Artists"), Playlists("Playlists"), Genres("Genres"), Favourites("Favourites"), Newest("Recently added") }

data class MusicBrowse(
    val kind: MusicBrowseKind,
    val title: String = kind.label,
    val cards: List<MusicCard> = emptyList(),
    val loading: Boolean = true,
    /** Where each source got to, for loading more (-1: no more there). Empty: nothing more to load. */
    val next: Map<String, Int> = emptyMap(),
    /** Albums in this genre (opened from Genres). */
    val genre: String? = null,
    /** Album grids: their sort and filters (null for artists, playlists and the genre list). */
    val query: io.github.petebutty.viewarr.data.media.LibraryQuery? = null,
    val options: io.github.petebutty.viewarr.data.media.LibraryOptions? = null,
    /** Each filter's choices once looked up (null while loading). */
    val values: Map<String, List<io.github.petebutty.viewarr.data.media.FilterValue>?> = emptyMap(),
    /** Changes with the sort and filters: the grid starts again from the top. */
    val version: Int = 0,
    /** A–Z: the position to go to. */
    val jump: Int? = null,
    /** "Search this library": what's typed, and what matches (null: not searching, the whole list shows). */
    val text: String = "",
    val found: List<MusicCard>? = null,
) {
    /** What the grid shows: the matches while searching, else the list. */
    val shown get() = found ?: cards
    val key get() = "browse:${kind.name}:${genre.orEmpty()}:$version"
    val more get() = next.values.any { it >= 0 }
    /** The A–Z strip: albums sorted by title, A first. */
    val byTitle get() = query?.let { it.sort == "title" && !it.desc } == true
    /** Album grids always have the A–Z strip (picking a letter sorts by title). */
    val canLetters get() = kind == MusicBrowseKind.Artists || (query != null && options?.sorts?.any { it.id == "title" } == true)
}

/** An album, playlist or artist opened: its tracks (or, for an artist, albums). */
data class MusicDetail(
    val card: MusicCard,
    val title: String = card.title,
    val sub: String = card.sub,
    val songs: List<Song> = emptyList(),
    val albums: List<MusicCard> = emptyList(),
    val loading: Boolean = true,
)

data class MusicSearch(val query: String = "", val artists: List<MusicCard> = emptyList(), val albums: List<MusicCard> = emptyList(), val songs: List<Song> = emptyList())

/** A music source for the menu's on/off switches. */
data class MusicSourceInfo(val id: String, val name: String, val on: Boolean)

/** Scrobbles outlive the page (a song can finish while you're elsewhere). */
private val scrobbleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val PAGE = 300
private const val ALBUMS_VIEW = "music:albums"

/** '#' for albums starting with a number or symbol, else the first letter (ignoring "The "). */
internal fun firstAlbumLetter(title: String): Char {
    val c = title.trim().removePrefix("The ").removePrefix("the ").uppercase().firstOrNull { !it.isWhitespace() } ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

internal fun albumCard(a: Album) = MusicCard(MusicKind.Album, a.id, a.name, a.artist, a.art)
internal fun artistCard(a: Artist) = MusicCard(MusicKind.Artist, a.id, a.name, if (a.albumCount > 0) "${a.albumCount} album${if (a.albumCount == 1) "" else "s"}" else "", a.art)
internal fun playlistCard(p: Playlist) = MusicCard(MusicKind.Playlist, p.id, p.name, songCount(p.songCount), p.art)

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val library: MusicLibrary,
    store: AccountStore,
    /** The Albums grid's sort and filters, remembered (per profile). */
    private val libraryViews: io.github.petebutty.viewarr.data.media.LibraryViews,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    /** Something to play from: any music source connected (Navidrome, or Plex / Jellyfin / Emby with a music library). */
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected
    /** Music turned on in setup: otherwise it's left out of the menu and search. */
    val enabled: StateFlow<Boolean> = store.accounts.map { it.musicOn }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _sources = MutableStateFlow<List<MusicSourceInfo>>(emptyList())
    val sources: StateFlow<List<MusicSourceInfo>> = _sources
    private val _home = MutableStateFlow(MusicHome())
    val home: StateFlow<MusicHome> = _home
    private val _browse = MutableStateFlow<MusicBrowse?>(null)
    val browse: StateFlow<MusicBrowse?> = _browse
    private val _detail = MutableStateFlow<MusicDetail?>(null)
    val detail: StateFlow<MusicDetail?> = _detail
    private val _search = MutableStateFlow(MusicSearch())
    val search: StateFlow<MusicSearch> = _search
    private val stack = ArrayDeque<MusicDetail>()
    private var browseJob: Job? = null

    init {
        MusicPlayer.onScrobble = { id, submission -> scrobbleScope.launch { library.scrobble(id, submission) } }
        // Accounts change (a source added, removed or switched off): everything reloads.
        viewModelScope.launch {
            store.accounts.map { Triple(it.navidrome to it.musicOn, listOfNotNull(it.plex) + it.plexMore, it.plexOff to it.musicOff + (it.jellyfin?.serverUrl + it.emby?.serverUrl)) }
                .distinctUntilChanged().collect { reload() }
        }
    }

    private suspend fun reload() {
        val names = runCatching { library.names() }.getOrDefault(emptyList())
        _sources.value = names.map { (id, n, on) -> MusicSourceInfo(id, n, on) }
        _connected.value = names.any { it.third }
        stack.clear(); _detail.value = null
        val b = _browse.value
        _browse.value = null
        // A search on screen is run again against the new set of sources.
        val q = _search.value.query
        _search.value = MusicSearch()
        if (_connected.value == true) { load(); b?.let { if (it.genre == null) openBrowse(it.kind) }; if (q.isNotBlank()) search(q) }
    }

    fun setSourceOn(id: String, on: Boolean) = viewModelScope.launch { library.setOn(id, on) }

    fun load() = viewModelScope.launch {
        _home.value = _home.value.copy(loading = true, error = null)
        val recent = async { library.albums(AlbumList.Recent, 24).first }
        val newest = async { library.albums(AlbumList.Newest, 24).first }
        val frequent = async { library.albums(AlbumList.Frequent, 24).first }
        val random = async { library.albums(AlbumList.Random, 24).first }
        val playlists = async { library.playlists() }
        listOf(recent, newest, frequent, random, playlists).awaitAll()
        val rows = listOf(
            MusicRow("Recently played", recent.await().map(::albumCard)),
            MusicRow("Recently added", newest.await().map(::albumCard)),
            MusicRow("Your playlists", playlists.await().map(::playlistCard)),
            MusicRow("Most played", frequent.await().map(::albumCard)),
            MusicRow("Rediscover", random.await().map(::albumCard)),
        ).filter { it.cards.isNotEmpty() }
        _home.value = MusicHome(rows, loading = false, error = if (rows.isEmpty()) "Couldn't load any music from your servers." else null)
    }

    /** Back to the Music home (from the side menu). */
    fun showHome() { stack.clear(); _detail.value = null; _browse.value = null }

    /** The whole library, one kind at a time. */
    fun openBrowse(kind: MusicBrowseKind) {
        stack.clear(); _detail.value = null
        val cur = _browse.value
        if (cur?.kind == kind && cur.genre == null && !cur.loading) return
        // Album grids: sorted and filtered (Albums as you left it; Recently added and Favourites preset).
        val q = when (kind) {
            MusicBrowseKind.Albums -> libraryViews.load(ALBUMS_VIEW) ?: io.github.petebutty.viewarr.data.media.LibraryQuery("title", false)
            MusicBrowseKind.Newest -> io.github.petebutty.viewarr.data.media.LibraryQuery("added", true)
            MusicBrowseKind.Favourites -> io.github.petebutty.viewarr.data.media.LibraryQuery("title", false, toggles = setOf("favourites"))
            else -> null
        }
        if (q != null) { runAlbums(MusicBrowse(kind, query = q)); return }
        _browse.value = MusicBrowse(kind)
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val b = when (kind) {
                MusicBrowseKind.Albums -> library.albums(AlbumList.AZ, PAGE).let { (l, n) -> MusicBrowse(kind, cards = l.map(::albumCard), loading = false, next = n) }
                MusicBrowseKind.Newest -> library.albums(AlbumList.Newest, PAGE).let { (l, n) -> MusicBrowse(kind, cards = l.map(::albumCard), loading = false, next = n) }
                MusicBrowseKind.Favourites -> library.albums(AlbumList.Favourites, 500).let { (l, _) -> MusicBrowse(kind, cards = l.map(::albumCard), loading = false) }
                MusicBrowseKind.Artists -> MusicBrowse(kind, cards = library.artists().map(::artistCard), loading = false)
                MusicBrowseKind.Playlists -> MusicBrowse(kind, cards = library.playlists().map(::playlistCard), loading = false)
                MusicBrowseKind.Genres -> MusicBrowse(kind, cards = library.genres().map { (g, n) -> MusicCard(MusicKind.Genre, g, g, if (n > 0) "$n album${if (n == 1) "" else "s"}" else "", null) }, loading = false)
            }
            if (_browse.value?.kind == kind && _browse.value?.genre == null) _browse.value = b
        }
    }

    private fun openGenre(name: String) {
        val q = io.github.petebutty.viewarr.data.media.LibraryQuery("title", false, filters = mapOf("genre" to listOf(io.github.petebutty.viewarr.data.media.FilterValue(name, name))))
        runAlbums(MusicBrowse(MusicBrowseKind.Genres, title = name, genre = name, query = q))
    }

    private var albumOptions: io.github.petebutty.viewarr.data.media.LibraryOptions? = null
    private var versions = 0

    /** An album grid with its sort and filters: from the top. */
    private fun runAlbums(b: MusicBrowse) {
        val q = b.query ?: return
        val fresh = b.copy(cards = emptyList(), loading = true, next = emptyMap(), version = ++versions, jump = null, options = b.options ?: albumOptions)
        _browse.value = fresh
        if (fresh.kind == MusicBrowseKind.Albums && fresh.genre == null) libraryViews.save(ALBUMS_VIEW, q)
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val options = fresh.options ?: runCatching { library.albumOptions() }.getOrNull()?.also { albumOptions = it }
            val (albums, next) = library.browseAlbums(q, PAGE)
            if (_browse.value?.version == fresh.version) _browse.value = fresh.copy(cards = albums.map(::albumCard), loading = false, next = next, options = options)
        }
    }

    /** Album grids: a sort picked (the same one again turns it round). */
    fun setAlbumSort(id: String) {
        val b = _browse.value ?: return
        val q = b.query ?: return
        val opt = b.options?.sorts?.firstOrNull { it.id == id } ?: return
        runAlbums(b.copy(query = if (q.sort == id) q.copy(desc = !q.desc) else q.copy(sort = id, desc = opt.descFirst)))
    }
    fun setAlbumDesc(desc: Boolean) { val b = _browse.value ?: return; val q = b.query ?: return; if (q.desc != desc) runAlbums(b.copy(query = q.copy(desc = desc))) }
    fun loadAlbumFilter(group: String) {
        val b = _browse.value ?: return
        if (b.values.containsKey(group)) return
        _browse.value = b.copy(values = b.values + (group to null))
        viewModelScope.launch {
            val got = runCatching { library.albumFilterValues(group) }.getOrDefault(emptyList())
            _browse.update { it?.copy(values = it.values + (group to got)) }
        }
    }
    fun toggleAlbumValue(group: String, value: io.github.petebutty.viewarr.data.media.FilterValue) {
        val b = _browse.value ?: return
        val q = b.query ?: return
        val multi = b.options?.filters?.firstOrNull { it.id == group }?.multi != false
        val cur = q.filters[group].orEmpty()
        val next = when { cur.any { it.id == value.id } -> cur.filter { it.id != value.id }; multi -> cur + value; else -> listOf(value) }
        runAlbums(b.copy(query = q.copy(filters = if (next.isEmpty()) q.filters - group else q.filters + (group to next))))
    }
    fun toggleAlbumFlag(id: String) { val b = _browse.value ?: return; val q = b.query ?: return; runAlbums(b.copy(query = q.copy(toggles = if (id in q.toggles) q.toggles - id else q.toggles + id))) }
    fun clearAlbumFilters() { val b = _browse.value ?: return; val q = b.query ?: return; if (q.active > 0) runAlbums(b.copy(query = q.copy(filters = emptyMap(), toggles = emptySet()))) }

    /** A–Z: the first album from [letter] on (loading further until it's there). */
    fun jumpToAlbumLetter(letter: Char) {
        val first = _browse.value ?: return
        viewModelScope.launch {
            var start = first
            // Not sorted by title: sort A→Z (keeping the filters) and wait for it.
            if (!start.byTitle && start.query != null) {
                val q = start.query
                runAlbums(start.copy(query = q.copy(sort = "title", desc = false)))
                val v = _browse.value?.version
                start = kotlinx.coroutines.withTimeoutOrNull(15_000) { _browse.first { it == null || it.version != v || !it.loading } } ?: return@launch
                if (start.version != v) return@launch
            }
            var tries = 0
            while (true) {
                val b = _browse.value ?: return@launch
                if (b.version != start.version) return@launch
                val i = b.cards.indexOfFirst { letter == '#' || firstAlbumLetter(it.title) >= letter }
                if (i >= 0 && (letter == '#' || firstAlbumLetter(b.cards[i].title) != '#')) { _browse.value = b.copy(jump = i); return@launch }
                if (!b.more || tries++ > 20) { _browse.value = b.copy(jump = (b.cards.size - 1).coerceAtLeast(0)); return@launch }
                loadMore(); browseJob?.join()
            }
        }
    }
    fun albumJumpDone() { _browse.update { it?.copy(jump = null) } }

    /** Near the end of a long grid: the next lot from each source that has more. */
    fun loadMore() {
        val b = _browse.value ?: return
        if (!b.more || b.loading || browseJob?.isActive == true) return
        // Finished sources stay in (as -1) so they aren't asked again.
        val offsets = b.next
        browseJob = viewModelScope.launch {
            val (more, next) = when {
                b.query != null -> library.browseAlbums(b.query, PAGE, offsets)
                else -> emptyList<Album>() to emptyMap()
            }
            // Sources that had finished stay finished.
            val merged = b.next.mapValues { (id, v) -> if (v < 0) -1 else next[id] ?: -1 }
            val seen = b.cards.map { MusicLibrary.norm(it.sub) + "|" + MusicLibrary.norm(it.title) }.toHashSet()
            val add = more.filter { seen.add(MusicLibrary.norm(it.artist) + "|" + MusicLibrary.norm(it.name)) }.map(::albumCard)
            if (_browse.value?.key == b.key) _browse.value = b.copy(cards = b.cards + add, next = merged)
        }
    }

    fun open(card: MusicCard) {
        if (card.kind == MusicKind.Genre) { openGenre(card.id); return }
        _detail.value?.let(stack::addLast)
        _detail.value = MusicDetail(card)
        viewModelScope.launch {
            val d = when (card.kind) {
                MusicKind.Album -> library.album(card.id)?.let { (a, songs) ->
                    MusicDetail(card.copy(art = a.art ?: card.art), a.name, listOfNotNull(a.artist, a.year?.toString(), songCount(songs.size), runtime(songs)).joinToString(" · "), songs = songs, loading = false)
                }
                MusicKind.Playlist -> library.playlist(card.id)?.let { (p, songs) ->
                    MusicDetail(card, p.name, listOfNotNull(songCount(songs.size), runtime(songs)).joinToString(" · "), songs = songs, loading = false)
                }
                MusicKind.Artist -> library.artist(card.id)?.let { (ar, albums) ->
                    MusicDetail(
                        card.copy(art = ar.art ?: card.art), ar.name, "${albums.size} album${if (albums.size == 1) "" else "s"}",
                        albums = albums.sortedByDescending { it.year ?: 0 }.map { albumCard(it).copy(sub = it.year?.toString() ?: "") },
                        loading = false,
                    )
                }
                MusicKind.Genre -> null
            } ?: MusicDetail(card, loading = false, sub = "Couldn't load this.")
            if (_detail.value?.card?.key == card.key) _detail.value = d
        }
    }

    /** From search: straight to that album or artist (Back returns to the Music home). */
    fun openFromSearch(card: MusicCard) { stack.clear(); _detail.value = null; _browse.value = null; open(card) }

    /** Back: the album/artist before, then the grid it came from (genres: the genre list), then the Music home. */
    fun back(): Boolean {
        if (_detail.value != null) { _detail.value = stack.removeLastOrNull(); return true }
        val b = _browse.value ?: return false
        if (b.genre != null) openBrowse(MusicBrowseKind.Genres) else _browse.value = null
        return true
    }

    fun play(songs: List<Song>, start: Int = 0, shuffled: Boolean = false) {
        if (songs.isEmpty()) return
        MusicPlayer.play(context, songs, start, shuffled)
        MusicPlayer.nowPlayingOpen = true
    }

    /** An artist's albums, all their songs (newest first), then play. */
    fun playArtist(d: MusicDetail, shuffled: Boolean) = viewModelScope.launch {
        val songs = d.albums.take(20).map { a -> async { library.album(a.id)?.second.orEmpty() } }.awaitAll().flatten()
        play(songs, 0, shuffled)
    }

    fun shuffleLibrary() = viewModelScope.launch { play(library.randomSongs(150), 0, false) }

    /**
     * Search this library (the box on each Music page): albums or artists through the server's search; on lists
     * already loaded whole (Favourites, a genre, Playlists, Genres), a match on the name.
     */
    private var browseSearchJob: Job? = null
    fun setBrowseText(t: String) {
        val b = _browse.value ?: return
        val q = t.trim()
        _browse.value = b.copy(text = t, found = if (q.length < 2) null else b.found)
        browseSearchJob?.cancel()
        if (q.length < 2) return
        browseSearchJob = viewModelScope.launch {
            delay(350)
            // The whole list already here (Favourites, Playlists, Genres, a small genre): match on it. Otherwise the server's search.
            val local = !b.more || b.kind == MusicBrowseKind.Playlists || b.kind == MusicBrowseKind.Genres
            val found = if (local) b.cards.filter { it.title.contains(q, true) || it.sub.contains(q, true) }
            else {
                val r = runCatching { library.search(q) }.getOrNull() ?: return@launch
                if (b.kind == MusicBrowseKind.Artists) r.artists.map(::artistCard) else r.albums.map(::albumCard)
            }
            val now = _browse.value
            if (now?.key == b.key && now.text.trim() == q) _browse.value = now.copy(found = found)
        }
    }

    private var searchJob: Job? = null
    fun search(query: String) {
        val q = query.trim()
        if (q == _search.value.query) return
        searchJob?.cancel()
        if (q.length < 2 || _connected.value != true) { _search.value = MusicSearch(q); return }
        searchJob = viewModelScope.launch {
            delay(350)
            val r = runCatching { library.search(q) }.getOrNull() ?: return@launch
            _search.value = MusicSearch(q, r.artists.map(::artistCard), r.albums.map(::albumCard), r.songs)
        }
    }

    private fun runtime(songs: List<Song>): String? {
        val s = songs.sumOf { it.durationSec }
        if (s <= 0) return null
        return if (s >= 3600) "${s / 3600} hr ${(s % 3600) / 60} min" else "${s / 60} min"
    }
}

/**
 * Remembers, per Music page, which item had focus, so coming back (from an album, Now Playing or the menu) lands on
 * it again rather than wherever focus happens to fall.
 */
private class FocusMemory {
    val last = HashMap<String, String>()
    private val requesters = HashMap<String, FocusRequester>()
    fun of(key: String) = requesters.getOrPut(key) { FocusRequester() }
    fun restore(page: String): Boolean = last[page]?.let { k -> runCatching { requesters[k]?.requestFocus() ?: error("none") }.isSuccess } ?: false
}

/** The Music page: a short home of rows; the whole library from the side menu; an album, playlist or artist over them. */
@Composable
fun MusicScreen(
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    onOpenMenu: () -> Unit,
    onBack: () -> Unit,
    onSetUp: () -> Unit,
    vm: MusicViewModel = hiltViewModel(),
) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    BackHandler(enabled = focusEnabled) { if (!vm.back()) onBack() }
    val mem = remember { FocusMemory() }
    val lists = remember { HashMap<String, LazyListState>() }
    val grids = remember { HashMap<String, LazyGridState>() }
    val page = detail?.card?.key ?: browse?.key ?: "home"
    val ready = when { detail != null -> !detail!!.loading; browse != null -> !browse!!.loading; else -> !home.loading }
    // Coming to a page (or back to it): its remembered item, else its first one.
    LaunchedEffect(page, focusEnabled, ready, connected) {
        if (!focusEnabled) return@LaunchedEffect
        awaitFrame(); awaitFrame()
        if (!mem.restore(page)) runCatching { focusRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        when {
            connected == null -> Text("Loading…", fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(48.dp).focusRequester(focusRequester))
            connected == false -> NotConnected(focusRequester, onSetUp, onOpenMenu)
            detail != null -> MusicDetailPage(detail!!, vm, page, mem, lists.getOrPut(page) { LazyListState() }, focusRequester, onOpenMenu)
            browse != null -> MusicBrowsePage(browse!!, vm, page, mem, grids.getOrPut(page) { LazyGridState() }, focusRequester, onOpenMenu)
            else -> MusicHomePage(home, vm, page, mem, lists.getOrPut(page) { LazyListState() }, { lists.getOrPut("row:$it") { LazyListState() } }, focusRequester, onOpenMenu)
        }
    }
}

@Composable
private fun NotConnected(focusRequester: FocusRequester, onSetUp: () -> Unit, onOpenMenu: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Music", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
        Text(
            "Connect Navidrome, or a Plex, Jellyfin or Emby server with a music library, to play your music here.",
            fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
        )
        Pill("Accounts & sources", Modifier.focusRequester(focusRequester).leftOpens(onOpenMenu), onSetUp)
    }
}

/** The item's focus: remembered for its page. */
private fun Modifier.remembered(mem: FocusMemory, page: String, key: String) =
    focusRequester(mem.of(key)).onFocusChanged { if (it.isFocused) mem.last[page] = key }

@Composable
private fun MusicHomePage(
    home: MusicHome, vm: MusicViewModel, page: String, mem: FocusMemory, list: LazyListState,
    rowState: (String) -> LazyListState, focusRequester: FocusRequester, onOpenMenu: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(), state = list,
        contentPadding = PaddingValues(top = 28.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Music", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Spacer(Modifier.width(28.dp))
                Pill("Shuffle my library", Modifier.focusRequester(focusRequester).leftOpens(onOpenMenu)) { vm.shuffleLibrary() }
                if (MusicPlayer.active) {
                    Spacer(Modifier.width(12.dp))
                    Pill("♪  Now playing") { MusicPlayer.nowPlayingOpen = true }
                }
                Spacer(Modifier.weight(1f))
                Text("Albums, artists and genres: in the menu", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
        }
        if (home.loading && home.rows.isEmpty()) item {
            Text("Loading your music…", fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 48.dp))
        }
        home.error?.let { e -> item { Text(e, fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 48.dp)) } }
        home.rows.forEach { row ->
            item(key = row.title) {
                Column {
                    Text(row.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
                    LazyRow(state = rowState(row.title), contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(row.cards, key = { _, c -> c.key }) { i, c ->
                            MusicCardView(
                                c, 118.dp,
                                Modifier.remembered(mem, page, "home|${row.title}|${c.key}").then(if (i == 0) Modifier.leftOpens(onOpenMenu) else Modifier),
                            ) { vm.open(c) }
                        }
                    }
                }
            }
        }
    }
}

private const val COLUMNS = 7

@Composable
private fun MusicBrowsePage(b: MusicBrowse, vm: MusicViewModel, page: String, mem: FocusMemory, grid: LazyGridState, focusRequester: FocusRequester, onOpenMenu: () -> Unit) {
    val genres = b.kind == MusicBrowseKind.Genres && b.genre == null
    val albums = b.query != null
    var panel by remember { mutableStateOf<String?>(null) }
    val toolbar = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    var railOn by remember { mutableStateOf(false) }
    var railLetter by remember { mutableStateOf('A') }
    val cols = if (genres) 5 else COLUMNS
    // A–Z: the grid moves to the letter; picked with OK, focus goes there too.
    LaunchedEffect(b.jump) {
        val j = b.jump ?: return@LaunchedEffect
        runCatching { grid.scrollToItem(j - j % cols) }
        if (!railOn) { awaitFrame(); awaitFrame(); b.cards.getOrNull(j)?.let { c -> runCatching { mem.of("$page|${c.key}").requestFocus() } } }
        vm.albumJumpDone()
    }
    val current by androidx.compose.runtime.rememberUpdatedState(b)
    val shownLetter by remember(b.version) { androidx.compose.runtime.derivedStateOf { current.cards.getOrNull(grid.firstVisibleItemIndex)?.title?.let(::firstAlbumLetter) } }
    // A panel closed (or a new list arrived while it was open): back onto the albums.
    LaunchedEffect(panel == null, b.version, b.loading) {
        if (panel != null || b.loading || b.cards.isEmpty()) return@LaunchedEffect
        awaitFrame(); awaitFrame()
        if (!mem.restore(page)) runCatching { focusRequester.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(b.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Spacer(Modifier.width(14.dp))
                val sortLabel = b.query?.let { q -> b.options?.sorts?.firstOrNull { it.id == q.sort }?.let { "${it.label} ${if (q.desc) "↓" else "↑"}" } }
                Text(
                    listOfNotNull(if (b.found != null) "${b.found.size} found" else if (b.loading) "Loading…" else "${b.cards.size}${if (b.more) "+" else ""}", sortLabel.takeIf { b.found == null }).joinToString("  ·  "),
                    fontSize = 14.sp, color = AppColors.TextSecondary,
                )
                Spacer(Modifier.weight(1f))
                // As on the Movies and Shows libraries: the mic, then a box to search this page.
                io.github.petebutty.viewarr.ui.media.LibrarySearchBox(b.text, vm::setBrowseText, onFocus = {}, onDone = {
                    b.shown.firstOrNull()?.let { c -> runCatching { mem.of("$page|${c.key}").requestFocus() } }
                })
                if (albums) {
                    io.github.petebutty.viewarr.ui.library.ToolButton(
                        "Sort" + (b.query?.let { q -> b.options?.sorts?.firstOrNull { it.id == q.sort }?.let { ": ${it.label}" } } ?: ""),
                        active = false, modifier = Modifier.focusRequester(toolbar),
                    ) { if (b.options != null) panel = "sort" }
                    if (b.options?.filters?.isNotEmpty() == true) io.github.petebutty.viewarr.ui.library.ToolButton(
                        b.query?.active?.takeIf { it > 0 }?.let { "Filter · $it" } ?: "Filter", active = (b.query?.active ?: 0) > 0, modifier = Modifier.padding(start = 8.dp),
                    ) { panel = "filter" }
                }
            }
            // What's filtered, each removable.
            val q = b.query
            if (q != null && q.active > 0) androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp),
            ) {
                val groups = b.options?.filters.orEmpty()
                items(q.toggles.toList().size) { k -> val t = q.toggles.toList()[k]; io.github.petebutty.viewarr.ui.library.FilterChip(groups.firstOrNull { it.id == t }?.label ?: t) { vm.toggleAlbumFlag(t) } }
                q.filters.forEach { (g, vs) -> items(vs.size) { k -> io.github.petebutty.viewarr.ui.library.FilterChip(vs[k].label) { vm.toggleAlbumValue(g, vs[k]) } } }
                item { io.github.petebutty.viewarr.ui.library.FilterChip("Clear all", clear = true) { vm.clearAlbumFilters() } }
            }
            if (!b.loading && b.shown.isEmpty()) Text(
                if (b.found != null) "Nothing matches \"${b.text.trim()}\"." else if (q?.active ?: 0 > 0) "No albums match these filters." else "Nothing here yet.", fontSize = 15.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp).focusRequester(focusRequester),
            )
            Row(Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols), state = grid,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 48.dp, end = if (b.canLetters) 12.dp else 48.dp, top = 16.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val cards = b.shown
                    items(cards.size, key = { cards[it].key }) { i ->
                        val c = cards[i]
                        if (b.found == null && i >= cards.size - cols * 3) LaunchedEffect(cards.size) { vm.loadMore() }
                        val m = Modifier.remembered(mem, page, "$page|${c.key}")
                            .then(if (i == 0) Modifier.focusRequester(focusRequester) else Modifier)
                            .onPreviewKeyEvent { e ->
                                // Back in an album grid: up to Sort / Filter, keeping your place.
                                if (albums && e.key == Key.Back) {
                                    if (e.type == KeyEventType.KeyUp) runCatching { toolbar.requestFocus() }
                                    return@onPreviewKeyEvent true
                                }
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    e.key == Key.DirectionLeft && i % cols == 0 -> { onOpenMenu(); true }
                                    e.key == Key.DirectionRight && b.canLetters && b.found == null && (i % cols == cols - 1 || i == cards.lastIndex) -> { runCatching { railFocus.requestFocus() }; true }
                                    else -> false
                                }
                            }
                        if (genres) GenreTile(c, m) { vm.open(c) } else MusicCardView(c, null, m) { vm.open(c) }
                    }
                }
                if (b.canLetters && b.found == null) io.github.petebutty.viewarr.ui.library.AlphabetRail(
                    letters = null, shown = shownLetter, focusRequester = railFocus,
                    onFocus = { railOn = it },
                    onMove = { railLetter = it },
                    onLetter = { vm.jumpToAlbumLetter(it) },
                    onPick = { vm.jumpToAlbumLetter(it); railOn = false },
                    onUp = { runCatching { toolbar.requestFocus() } },
                    onLeft = {
                        val first = grid.firstVisibleItemIndex
                        b.cards.getOrNull((first + cols - 1).coerceAtMost(b.cards.lastIndex))?.let { c -> runCatching { mem.of("$page|${c.key}").requestFocus() } }
                    },
                )
            }
        }
        if (railOn) Box(
            Modifier.align(Alignment.Center).size(140.dp).background(AppColors.Panel.copy(alpha = 0.92f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(railLetter.toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent) }
        val o = b.options
        val q = b.query
        if (panel == "sort" && o != null && q != null) io.github.petebutty.viewarr.ui.library.SortPanel(
            o, q, onPick = { id -> val same = id == q.sort; vm.setAlbumSort(id); if (!same) panel = null }, onDesc = vm::setAlbumDesc, onClose = { panel = null },
        )
        if (panel == "filter" && o != null && q != null) io.github.petebutty.viewarr.ui.library.FilterPanel(
            o, q, b.values, onOpenGroup = vm::loadAlbumFilter, onToggle = vm::toggleAlbumFlag, onValue = vm::toggleAlbumValue, onClear = vm::clearAlbumFilters, onClose = { panel = null },
        )
    }
}

@Composable
private fun GenreTile(c: MusicCard, modifier: Modifier, onClick: () -> Unit) {
    // A colour per genre (always the same one), so the grid isn't a wall of grey.
    val hue = (c.title.lowercase().hashCode().toUInt() % 360u).toFloat()
    val tint = Color.hsv(hue, 0.55f, 0.55f)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(76.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = RoundedCornerShape(10.dp))),
        colors = ClickableSurfaceDefaults.colors(containerColor = tint, focusedContainerColor = tint),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(c.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.sub.isNotBlank()) Text(c.sub, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MusicDetailPage(d: MusicDetail, vm: MusicViewModel, page: String, mem: FocusMemory, list: LazyListState, focusRequester: FocusRequester, onOpenMenu: () -> Unit) {
    val playing = MusicPlayer.current?.id
    val playKey = "$page|play"
    val shuffleKey = "$page|shuffle"
    fun trackKey(i: Int) = "$page|t$i"
    Box(Modifier.fillMaxSize()) {
        d.card.art?.let { AsyncImage(it.small, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.18f) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to AppColors.Background.copy(alpha = 0.6f), 1f to AppColors.Background)))
        Row(Modifier.fillMaxSize().padding(start = 56.dp, top = 40.dp, end = 48.dp)) {
            Column(Modifier.width(260.dp)) {
                val round = d.card.kind == MusicKind.Artist
                Box(Modifier.size(240.dp).clip(if (round) CircleShape else RoundedCornerShape(12.dp)).background(AppColors.Panel)) {
                    d.card.art?.let { AsyncImage(it.large, d.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Text(d.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                Text(d.sub, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                val ready = !d.loading && (d.songs.isNotEmpty() || d.albums.isNotEmpty())
                val toTracks = d.card.kind != MusicKind.Artist && d.songs.isNotEmpty()
                Row(Modifier.padding(top = 18.dp)) {
                    Pill(
                        if (d.loading) "Loading…" else "▶  Play",
                        Modifier.focusRequester(focusRequester).remembered(mem, page, playKey).leftOpens(onOpenMenu),
                    ) {
                        if (!ready) return@Pill
                        if (d.card.kind == MusicKind.Artist) vm.playArtist(d, false) else vm.play(d.songs)
                    }
                    Spacer(Modifier.width(10.dp))
                    // Right from the buttons: back onto the track you were on (read now, not when drawn), else the first.
                    Pill("Shuffle", Modifier.remembered(mem, page, shuffleKey).onPreviewKeyEvent { e ->
                        if (!toTracks || e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown) {
                            val k = mem.last["$page|track"] ?: trackKey(0)
                            if (runCatching { mem.of(k).requestFocus() }.isFailure) runCatching { mem.of(trackKey(0)).requestFocus() }
                        }
                        true
                    }) {
                        if (!ready) return@Pill
                        if (d.card.kind == MusicKind.Artist) vm.playArtist(d, true) else vm.play(d.songs, shuffled = true)
                    }
                }
            }
            Spacer(Modifier.width(36.dp))
            if (d.card.kind == MusicKind.Artist) {
                Column(Modifier.weight(1f)) {
                    Text("Albums", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 10.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 110.dp),
                    ) {
                        items(d.albums.size, key = { d.albums[it].key }) { i ->
                            val c = d.albums[i]
                            MusicCardView(c, null, Modifier.remembered(mem, page, "$page|${c.key}")
                                .then(if (i % 5 == 0) Modifier.focusProperties { left = mem.of(shuffleKey) } else Modifier)) { vm.open(c) }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val multiDisc = d.songs.mapNotNull { it.disc }.distinct().size > 1
                    itemsIndexed(d.songs, key = { i, s -> "$i:${s.id}" }) { i, s ->
                        Column {
                            if (multiDisc && (i == 0 || d.songs[i - 1].disc != s.disc)) Text(
                                "Disc ${s.disc}", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else 10.dp, bottom = 4.dp),
                            )
                            TrackRow(
                                number = if (d.card.kind == MusicKind.Album) (s.track ?: (i + 1)).toString() else (i + 1).toString(),
                                song = s, showArtist = d.card.kind == MusicKind.Playlist, nowPlaying = s.id == playing,
                                // Left from any track: back to the buttons (not whatever's nearest on the left); the
                                // ends of the list stop there rather than jumping off the page.
                                modifier = Modifier.remembered(mem, page, trackKey(i))
                                    .onFocusChanged { if (it.isFocused) mem.last["$page|track"] = trackKey(i) }
                                    .focusProperties {
                                        left = mem.of(shuffleKey)
                                        if (i == 0) up = FocusRequester.Cancel
                                        if (i == d.songs.lastIndex) down = FocusRequester.Cancel
                                    },
                            ) { vm.play(d.songs, i) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(number: String, song: Song, showArtist: Boolean, nowPlaying: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (showArtist) 52.dp else 42.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, focusedContainerColor = AppColors.Accent,
            contentColor = AppColors.TextPrimary, focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        val c = androidx.tv.material3.LocalContentColor.current
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (nowPlaying) "♪" else number, fontSize = 14.sp, color = c.copy(alpha = if (nowPlaying) 1f else 0.7f), modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 15.sp, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (nowPlaying) FontWeight.SemiBold else FontWeight.Normal)
                if (showArtist) Text(song.artist, fontSize = 12.sp, color = c.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(clock(song.durationSec), fontSize = 13.sp, color = c.copy(alpha = 0.7f))
        }
    }
}

/** A cover with its name under it. [size] null: as wide as its grid cell. */
@Composable
internal fun MusicCardView(c: MusicCard, size: Dp?, modifier: Modifier, onClick: () -> Unit) {
    val round = c.kind == MusicKind.Artist
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Column(if (size != null) Modifier.width(size) else Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            modifier = modifier.then(if (size != null) Modifier.size(size) else Modifier.fillMaxWidth().aspectRatio(1f)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            shape = ClickableSurfaceDefaults.shape(shape),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = shape)),
            colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Panel, focusedContainerColor = AppColors.Panel),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(c.title.take(1).uppercase(), fontSize = 32.sp, color = AppColors.TextSecondary)
                c.cover?.let { AsyncImage(it, c.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Text(c.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (c.sub.isNotBlank()) Text(c.sub, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(19.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f), focusedContainerColor = AppColors.Accent,
            contentColor = AppColors.TextPrimary, focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
    }
}

/** Left from the first item of a row opens the side menu, as on the other pages. */
internal fun Modifier.leftOpens(onOpenMenu: () -> Unit) = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onOpenMenu(); true } else false
}

internal fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

internal fun clock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
