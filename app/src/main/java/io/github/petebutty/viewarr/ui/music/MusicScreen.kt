package io.github.petebutty.viewarr.ui.music

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.music.Album
import io.github.petebutty.viewarr.data.music.Art
import io.github.petebutty.viewarr.data.music.Artist
import io.github.petebutty.viewarr.data.music.MusicLibrary
import io.github.petebutty.viewarr.data.music.Playlist
import io.github.petebutty.viewarr.data.music.Song
import io.github.petebutty.viewarr.data.music.Taste
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
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.sync.Semaphore
import io.github.petebutty.viewarr.data.media.FilterValue
import io.github.petebutty.viewarr.data.media.LibraryOptions
import io.github.petebutty.viewarr.data.media.LibraryQuery
import io.github.petebutty.viewarr.data.media.LibraryViews
import io.github.petebutty.viewarr.data.music.Lyrics
import io.github.petebutty.viewarr.ui.common.LiveBehind
import io.github.petebutty.viewarr.ui.library.AlphabetRail
import io.github.petebutty.viewarr.ui.library.FilterChip
import io.github.petebutty.viewarr.ui.library.FilterPanel
import io.github.petebutty.viewarr.ui.library.SortPanel
import io.github.petebutty.viewarr.ui.library.ToolButton
import io.github.petebutty.viewarr.ui.media.LibrarySearchBox
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.ui.common.AppPill
import io.github.petebutty.viewarr.ui.common.CardAspect
import io.github.petebutty.viewarr.ui.common.FocusCard
import io.github.petebutty.viewarr.ui.common.leftOpens
import io.github.petebutty.viewarr.ui.media.RowTitle
import io.github.petebutty.viewarr.ui.theme.NoScale
import io.github.petebutty.viewarr.ui.theme.Tokens

/** [Mix]: a made-up list of songs (Your Mix, a daily mix, an artist's top songs): OK plays it. */
enum class MusicKind { Album, Playlist, Artist, Genre, Decade, Mix }

/**
 * An album, playlist, artist, genre or decade on the Music pages. [mosaic]: up to four covers for a card with no
 * picture of its own (a genre's or decade's first albums, a playlist's first songs). [meta]: the home hero's line
 * ("artist · year · 12 songs"); [sub] is the shorter line under a card.
 */
data class MusicCard(val kind: MusicKind, val id: String, val title: String, val sub: String, val art: Art?, val mosaic: List<String> = emptyList(), val meta: String = "") {
    val key get() = "${kind.name}:$id"
    val cover get() = art?.small
}

/** How a home row draws its cards: square covers (artists round), or wide picture tiles (genres, decades). */
enum class RowLook { Cards, Tiles }

data class MusicRow(val id: String, val title: String, val cards: List<MusicCard>, val look: RowLook = RowLook.Cards)

data class MusicHome(val rows: List<MusicRow> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** The whole library, one kind at a time (from the side menu; Decade: the home's decade tiles). */
enum class MusicBrowseKind(val label: String) { Albums("Albums A–Z"), Artists("Artists"), Playlists("Playlists"), Genres("Genres"), Favourites("Favourites"), Newest("Recently added"), Decade("Decade") }

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
    val query: LibraryQuery? = null,
    val options: LibraryOptions? = null,
    /** Each filter's choices once looked up (null while loading). */
    val values: Map<String, List<FilterValue>?> = emptyMap(),
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
    /** Artists: their biography, most-played songs and similar artists (where the server has them). */
    val bio: String? = null,
    val topSongs: List<Song> = emptyList(),
    val similar: List<MusicCard> = emptyList(),
    /** Albums: their artist, to go to from the album's page. */
    val artist: MusicCard? = null,
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
internal fun artistCard(a: Artist) = MusicCard(MusicKind.Artist, a.id, a.name, if (a.albumCount > 0) albumsText(a.albumCount) else "", a.art)
internal fun playlistCard(p: Playlist) = MusicCard(MusicKind.Playlist, p.id, p.name, songCount(p.songCount), p.art)
internal fun albumsText(n: Int) = if (n == 1) "1 album" else "$n albums"

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val library: MusicLibrary,
    store: AccountStore,
    /** The Albums grid's sort and filters, remembered (per profile). */
    private val libraryViews: LibraryViews,
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
    /** Starred songs, albums and artists (hold OK to star or unstar; shown with ★). */
    private val _starred = MutableStateFlow<Set<String>>(emptySet())
    val starred: StateFlow<Set<String>> = _starred
    /** The playing song's lyrics (null: none found / not looked yet). */
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics
    private var lyricsFor: String? = null
    /** Keeps a radio queue going (MusicRadio.kt). */
    private val radioFeeder = RadioFeeder(library, context)

    /** Looks up [song]'s lyrics once (Now Playing asks as the song changes). */
    fun loadLyrics(song: Song?) {
        if (song == null || song.id == lyricsFor) return
        lyricsFor = song.id
        _lyrics.value = null
        val lrclib = AppColors.palette.opt(UiOpts.MUSIC_LRCLIB) == 1
        viewModelScope.launch {
            val l = runCatching { library.lyrics(song, lrclib) }.getOrNull()
            if (lyricsFor == song.id) _lyrics.value = l
        }
    }

    /** Song radio: endless music like [song] (its server's similar songs, else more by the artist and a mix). */
    fun radio(song: Song) = viewModelScope.launch {
        Toast.makeText(context, "Starting radio from ${song.title}…", Toast.LENGTH_SHORT).show()
        startRadio(song)
    }
    /** Radio from an artist's top song (or any of theirs). */
    fun artistRadio(d: MusicDetail) = viewModelScope.launch {
        val seed = d.topSongs.firstOrNull() ?: d.albums.firstOrNull()?.let { library.album(it.id)?.second?.firstOrNull() } ?: return@launch
        startRadio(seed)
    }
    /** Radio from a card (an album, artist, playlist, mix or genre): nothing happens if no song can be found to start from. */
    fun radioFrom(card: MusicCard) = viewModelScope.launch { radioSeed(card)?.let { startRadio(it) } }

    private suspend fun radioSeed(card: MusicCard): Song? = runCatching {
        when (card.kind) {
            MusicKind.Album -> library.album(card.id)?.second?.firstOrNull()
            MusicKind.Playlist -> library.playlist(card.id)?.second?.randomOrNull()
            MusicKind.Mix -> mixSongs[card.id]?.firstOrNull()
            MusicKind.Artist -> artistSeed(card.id)
            MusicKind.Genre -> library.albumsInGenre(card.id, 20).first.randomOrNull()?.let { library.album(it.id)?.second?.randomOrNull() }
            MusicKind.Decade -> null
        }
    }.getOrNull()

    /** An artist's best-known song (their server's top songs), else the first song of one of their albums. */
    private suspend fun artistSeed(id: String): Song? {
        val (ar, albums) = library.artist(id) ?: return null
        library.artistInfo(ar)?.topSongs?.firstOrNull()?.let { return it }
        return albums.randomOrNull()?.let { library.album(it.id)?.second?.firstOrNull() }
    }

    /** The radio queue: songs like [seed] now, and more of them as it runs low (MusicRadio.kt). */
    private suspend fun startRadio(seed: Song) {
        val songs = library.similarSongs(seed, 60)
        if (songs.isEmpty()) return
        radioFeeder.started(songs)
        MusicPlayer.play(context, songs, 0, radio = true)
        MusicPlayer.nowPlayingOpen = true
    }

    fun playNext(songs: List<Song>) { MusicPlayer.playNext(context, songs); Toast.makeText(context, if (songs.size == 1) "Plays next: ${songs[0].title}" else "${songs.size} songs play next", Toast.LENGTH_SHORT).show() }
    fun addToQueue(songs: List<Song>) { MusicPlayer.addToQueue(context, songs); Toast.makeText(context, if (songs.size == 1) "Added to the queue: ${songs[0].title}" else "${songs.size} songs added to the queue", Toast.LENGTH_SHORT).show() }
    /** An album's or playlist's songs, for Play next / Add to queue from its card. */
    fun songsOf(card: MusicCard, then: (List<Song>) -> Unit) = viewModelScope.launch {
        val songs = when (card.kind) {
            MusicKind.Album -> library.album(card.id)?.second
            MusicKind.Playlist -> library.playlist(card.id)?.second
            MusicKind.Mix -> mixSongs[card.id]
            else -> null
        }.orEmpty()
        then(songs)
    }

    /** Stars or unstars a song, album or artist on its server (Navidrome); says so if the server can't. */
    fun toggleStar(id: String, kind: String) = viewModelScope.launch {
        val on = id !in _starred.value
        if (library.setStarred(id, kind, on)) {
            _starred.value = if (on) _starred.value + id else _starred.value - id
            Toast.makeText(context, if (on) "★ Added to your favourites" else "Removed from your favourites", Toast.LENGTH_SHORT).show()
        } else Toast.makeText(context, "Favourites aren't available on this server", Toast.LENGTH_SHORT).show()
    }

    init {
        MusicPlayer.onScrobble = { id, submission -> scrobbleScope.launch { library.scrobble(id, submission) } }
        MusicPlayer.onRadioLow = radioFeeder::low
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
        if (_connected.value == true) { load(force = true); b?.let { if (it.genre == null && it.kind != MusicBrowseKind.Decade) openBrowse(it.kind) }; if (q.isNotBlank()) search(q) }
    }

    fun setSourceOn(id: String, on: Boolean) = viewModelScope.launch { library.setOn(id, on) }

    /** When the home's rows were last built (ms): they're kept for the session and built again after a while. */
    private var homeBuiltAt = 0L
    private var homeJob: Job? = null
    private var personalJob: Job? = null
    /** The cheap rows, and the personal ones (kept for the session, made again behind them after a while). */
    private var baseRows: List<MusicRow> = emptyList()
    private val personal = LinkedHashMap<String, RowOut>()
    /** The songs behind each mix tile (Your Mix, daily mixes, top songs) by card id; mixes are made once a day. */
    private val mixSongs = HashMap<String, List<Song>>()
    private var mixDay = 0L

    /**
     * The home's rows (MusicHomeRows.kt), all at once; the rows already showing stay up while new ones are made.
     * The personal rows (MusicPersonalRows.kt) start as soon as what's played is known and fill in as they're ready.
     * [force]: the sources changed, so a build under way starts again.
     */
    fun load(force: Boolean = false) {
        if (!force && homeJob?.isActive == true) return
        homeJob?.cancel(); personalJob?.cancel()
        if (force) { personal.clear(); mixSongs.clear(); mixDay = 0L }
        _home.value = _home.value.copy(loading = true, error = null)
        // A few small look-ups at a time (shared by the cheap rows and the personal ones), so a server isn't swamped.
        val gentle = Semaphore(6)
        homeJob = viewModelScope.launch {
            val built = buildHomeRows(library, gentle) { taste -> personalJob = viewModelScope.launch { loadPersonal(taste, gentle) } }
            baseRows = built.rows
            _starred.value = built.starred
            homeBuiltAt = System.currentTimeMillis()
            val rows = orderedRows(baseRows, personal.values)
            _home.value = MusicHome(rows, loading = false, error = if (rows.isEmpty()) NO_MUSIC else null)
        }
    }

    /** The personal rows, each shown as it arrives; what the new pass didn't make is dropped at the end. */
    private suspend fun loadPersonal(taste: Taste, gentle: Semaphore) {
        val day = LocalDate.now().toEpochDay()
        val withMixes = mixDay != day || personal["mix"] == null
        val fresh = HashSet<String>()
        buildPersonalRows(library, taste, day, withMixes, gentle) { id, out ->
            if (out == null) return@buildPersonalRows
            fresh += id; personal[id] = out; mixSongs.putAll(out.songs)
            if (id == "mix") mixDay = day
            publishRows()
        }
        if (!withMixes) fresh += "mix"
        personal.keys.retainAll(fresh)
        mixSongs.keys.retainAll(personal.values.flatMap { it.songs.keys }.toSet())
        publishRows()
    }

    private fun publishRows() {
        val rows = orderedRows(baseRows, personal.values)
        _home.update { it.copy(rows = rows) }
    }

    /** The home on screen again: rows older than ten minutes are built again behind the ones showing. */
    fun homeShown() { if (homeBuiltAt > 0 && System.currentTimeMillis() - homeBuiltAt > 10 * 60_000) load() }

    /** A mix's cover (its first song's), for the home's background. */
    fun mixArt(id: String): Art? = mixSongs[id]?.firstNotNullOfOrNull { it.art }

    private val artistArt = HashMap<String, Art?>()
    /** An artist without a picture of their own: one of their album covers (the home's hero and background). */
    suspend fun artistArt(id: String): Art? = artistArt.getOrPut(id) { library.artist(id)?.second?.firstNotNullOfOrNull { it.art } }

    /** Back to the Music home (from the side menu). */
    fun showHome() { stack.clear(); _detail.value = null; _browse.value = null }

    /** The whole library, one kind at a time. */
    fun openBrowse(kind: MusicBrowseKind) {
        stack.clear(); _detail.value = null
        val cur = _browse.value
        if (cur?.kind == kind && cur.genre == null && !cur.loading) return
        // Album grids: sorted and filtered (Albums as you left it; Recently added and Favourites preset).
        val q = when (kind) {
            MusicBrowseKind.Albums -> libraryViews.load(ALBUMS_VIEW) ?: LibraryQuery("title", false)
            MusicBrowseKind.Newest -> LibraryQuery("added", true)
            MusicBrowseKind.Favourites -> LibraryQuery("title", false, toggles = setOf("favourites"))
            else -> null
        }
        if (q != null) { runAlbums(MusicBrowse(kind, query = q)); return }
        _browse.value = MusicBrowse(kind)
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val b = when (kind) {
                // (The album grids, Albums / Recently added / Favourites, went through runAlbums above.)
                MusicBrowseKind.Albums, MusicBrowseKind.Newest, MusicBrowseKind.Favourites -> return@launch
                MusicBrowseKind.Artists -> MusicBrowse(kind, cards = library.artists().map(::artistCard), loading = false)
                MusicBrowseKind.Playlists -> MusicBrowse(kind, cards = library.playlists().map(::playlistCard), loading = false)
                MusicBrowseKind.Genres -> MusicBrowse(kind, cards = library.genres().map { (g, n) -> MusicCard(MusicKind.Genre, g, g, if (n > 0) albumsText(n) else "", null) }, loading = false)
                // (A decade's albums come from the home's tiles only: openDecade.)
                MusicBrowseKind.Decade -> MusicBrowse(kind, loading = false)
            }
            if (_browse.value?.kind == kind && _browse.value?.genre == null) _browse.value = b
        }
    }

    private fun openGenre(name: String) {
        val q = LibraryQuery("title", false, filters = mapOf("genre" to listOf(FilterValue(name, name))))
        runAlbums(MusicBrowse(MusicBrowseKind.Genres, title = name, genre = name, query = q))
    }

    /** A decade's albums (from the home's tiles), newest first; Back goes to the home. */
    private fun openDecade(id: String, label: String) {
        stack.clear(); _detail.value = null
        val q = LibraryQuery("year", true, filters = mapOf("decade" to listOf(FilterValue(id, label))))
        runAlbums(MusicBrowse(MusicBrowseKind.Decade, title = label, query = q))
    }

    private var albumOptions: LibraryOptions? = null
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
    fun toggleAlbumValue(group: String, value: FilterValue) {
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
        if (card.kind == MusicKind.Decade) { openDecade(card.id, card.title); return }
        // A mix: plays at once (no page of its own).
        if (card.kind == MusicKind.Mix) { mixSongs[card.id]?.let { play(it, 0) }; return }
        _detail.value?.let(stack::addLast)
        _detail.value = MusicDetail(card)
        viewModelScope.launch {
            val d = when (card.kind) {
                MusicKind.Album -> library.album(card.id)?.let { (a, songs) ->
                    MusicDetail(card.copy(art = a.art ?: card.art), a.name, listOfNotNull(a.artist, a.year?.toString(), songCount(songs.size), runtime(songs)).joinToString(" · "), songs = songs, loading = false,
                        artist = a.artistId?.let { MusicCard(MusicKind.Artist, it, a.artist, "", null) })
                }
                MusicKind.Playlist -> library.playlist(card.id)?.let { (p, songs) ->
                    MusicDetail(card, p.name, listOfNotNull(songCount(songs.size), runtime(songs)).joinToString(" · "), songs = songs, loading = false)
                }
                MusicKind.Artist -> library.artist(card.id)?.let { (ar, albums) ->
                    val info = runCatching { library.artistInfo(ar) }.getOrNull()
                    MusicDetail(
                        card.copy(art = ar.art ?: card.art), ar.name, "${albums.size} album${if (albums.size == 1) "" else "s"}",
                        albums = albums.sortedByDescending { it.year ?: 0 }.map { albumCard(it).copy(sub = it.year?.toString() ?: "") },
                        loading = false,
                        bio = info?.bio, topSongs = info?.topSongs.orEmpty(), similar = info?.similar.orEmpty().map(::artistCard),
                    )
                }
                MusicKind.Genre, MusicKind.Decade, MusicKind.Mix -> null
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
internal class FocusMemory {
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
    // Hold OK on a song, album, playlist or artist: play next, add to queue, radio, favourite.
    MusicOptionsHost(vm)
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

    // Live TV in the guide's preview place (top left): the highlighted album, artist or playlist beside it, the
    // music below.
    val besidePreview = LiveBehind.preview
    // (The app's wallpaper shows through, as under the menu beside it: one background across both. 2026-11-17.)
    Column(Modifier.fillMaxSize()) {
    if (besidePreview) Row(Modifier.fillMaxWidth().height(200.dp)) {
        Spacer(Modifier.width(Tokens.BesideWindowStart))
        Column(Modifier.padding(top = 26.dp, end = Tokens.Gutter)) {
            // An album / artist / playlist open: its name and details here (the page below then leaves them out, so
            // they aren't shown twice); otherwise the highlighted card's. (2026-11-17.)
            val d = detail
            val c = MusicHover.card
            Text("Music", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(d?.title ?: c?.title ?: "Your music", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            (if (d != null) d.sub else c?.sub)?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 15.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (d == null) c?.let { Spacer(Modifier.height(4.dp)); Text(it.kind.name, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
        when {
            connected == null -> Text("Loading…", fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(48.dp).focusRequester(focusRequester))
            connected == false -> NotConnected(focusRequester, onSetUp, onOpenMenu)
            detail != null -> MusicDetailPage(detail!!, vm, page, mem, lists.getOrPut(page) { LazyListState() }, focusRequester, onOpenMenu)
            browse != null -> MusicBrowsePage(browse!!, vm, page, mem, grids.getOrPut(page) { LazyGridState() }, focusRequester, onOpenMenu)
            else -> MusicHomePage(home, vm, page, mem, lists.getOrPut(page) { LazyListState() }, { lists.getOrPut("row:$it") { LazyListState() } }, focusRequester, onOpenMenu)
        }
    }
    }
}

/** The music card highlighted last (shown beside the live TV preview). */
internal object MusicHover {
    var card by androidx.compose.runtime.mutableStateOf<MusicCard?>(null)
}

@Composable
private fun NotConnected(focusRequester: FocusRequester, onSetUp: () -> Unit, onOpenMenu: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Music", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "Connect Navidrome, or a Plex, Jellyfin or Emby server with a music library, to play your music here.",
            fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
        )
        Pill("Accounts & sources", Modifier.focusRequester(focusRequester).leftOpens(onMenu = onOpenMenu), onSetUp)
    }
}

/** The item's focus: remembered for its page. */
internal fun Modifier.remembered(mem: FocusMemory, page: String, key: String) =
    focusRequester(mem.of(key)).onFocusChanged { if (it.isFocused) mem.last[page] = key }

// (The home page itself: MusicHome.kt.)

private const val COLUMNS = 7

/** A Music grid with nothing in it: say what's missing. */
private fun emptyText(b: MusicBrowse): String = when {
    b.genre != null -> "No albums in this genre yet."
    b.kind == MusicBrowseKind.Artists -> "No artists here yet."
    b.kind == MusicBrowseKind.Playlists -> "No playlists yet."
    b.kind == MusicBrowseKind.Genres -> "No genres here yet."
    b.kind == MusicBrowseKind.Favourites -> "No favourites yet. Hold OK on an album or a song to add one."
    else -> "No albums here yet."
}

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
                // (Never so wide that the search box and the buttons after it are pushed off the row.)
                Text(b.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp))
                Spacer(Modifier.width(14.dp))
                val sortLabel = b.query?.let { q -> b.options?.sorts?.firstOrNull { it.id == q.sort }?.let { "${it.label} ${if (q.desc) "↓" else "↑"}" } }
                Text(
                    listOfNotNull(if (b.found != null) "${b.found.size} found" else if (b.loading) "Loading…" else "${b.cards.size}${if (b.more) "+" else ""}", sortLabel.takeIf { b.found == null }).joinToString("  ·  "),
                    fontSize = 14.sp, color = AppColors.TextSecondary,
                    maxLines = 1, softWrap = false,
                )
                Spacer(Modifier.weight(1f))
                // As on the Movies and Shows libraries: the mic, then a box to search this page.
                // (Nothing to show: the page's highlight goes to the Sort button, else the search box. It was put on
                // the "Nothing matches" note, which can't take it.)
                val empty = !b.loading && b.shown.isEmpty()
                Box(if (empty && !albums) Modifier.focusRequester(focusRequester) else Modifier) {
                    LibrarySearchBox(b.text, vm::setBrowseText, onFocus = {}, onDone = {
                        b.shown.firstOrNull()?.let { c -> runCatching { mem.of("$page|${c.key}").requestFocus() } }
                    })
                }
                if (albums) {
                    ToolButton(
                        "Sort" + (b.query?.let { q -> b.options?.sorts?.firstOrNull { it.id == q.sort }?.let { ": ${it.label}" } } ?: ""),
                        active = false, modifier = Modifier.focusRequester(toolbar).then(if (empty) Modifier.focusRequester(focusRequester) else Modifier),
                    ) { if (b.options != null) panel = "sort" }
                    if (b.options?.filters?.isNotEmpty() == true) ToolButton(
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
                items(q.toggles.toList().size) { k -> val t = q.toggles.toList()[k]; FilterChip(groups.firstOrNull { it.id == t }?.label ?: t) { vm.toggleAlbumFlag(t) } }
                q.filters.forEach { (g, vs) -> items(vs.size) { k -> FilterChip(vs[k].label) { vm.toggleAlbumValue(g, vs[k]) } } }
                item { FilterChip("Clear all", clear = true) { vm.clearAlbumFilters() } }
            }
            if (!b.loading && b.shown.isEmpty()) Text(
                if (b.found != null) "Nothing matches \"${b.text.trim()}\"." else if (q?.active ?: 0 > 0) "No albums match these filters." else emptyText(b), fontSize = 15.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
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
                            // Left on the first column: the menu, once per press (the shared leftOpens).
                            .then(if (i % cols == 0) Modifier.leftOpens(onMenu = onOpenMenu) else Modifier)
                            .onPreviewKeyEvent { e ->
                                // (Back leaves the album grid in one press: it went up to Sort / Filter first.)
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    e.key == Key.DirectionRight && b.canLetters && b.found == null && (i % cols == cols - 1 || i == cards.lastIndex) -> { runCatching { railFocus.requestFocus() }; true }
                                    else -> false
                                }
                            }
                        if (genres) GenreTile(c, m) { vm.open(c) } else MusicCardView(c, null, m) { vm.open(c) }
                    }
                }
                if (b.canLetters && b.found == null) AlphabetRail(
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
        ) { Text(railLetter.toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, maxLines = 1, softWrap = false) }
        val o = b.options
        val q = b.query
        if (panel == "sort" && o != null && q != null) SortPanel(
            o, q, onPick = { id -> val same = id == q.sort; vm.setAlbumSort(id); if (!same) panel = null }, onDesc = vm::setAlbumDesc, onClose = { panel = null },
        )
        if (panel == "filter" && o != null && q != null) FilterPanel(
            o, q, b.values, onOpenGroup = vm::loadAlbumFilter, onToggle = vm::toggleAlbumFlag, onValue = vm::toggleAlbumValue, onClear = vm::clearAlbumFilters, onClose = { panel = null },
        )
    }
}

@Composable
private fun GenreTile(c: MusicCard, modifier: Modifier, onClick: () -> Unit) {
    // A colour per genre (always the same one), so the grid isn't a wall of grey.
    val hue = (c.title.lowercase().hashCode().toUInt() % 360u).toFloat()
    val tint = Color.hsv(hue, 0.55f, 0.55f)
    // The shared card (3 dp ring, no growing, no press shrink).
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(76.dp), containerColor = tint) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(c.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.sub.isNotBlank()) Text(c.sub, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MusicDetailPage(d: MusicDetail, vm: MusicViewModel, page: String, mem: FocusMemory, list: LazyListState, focusRequester: FocusRequester, onOpenMenu: () -> Unit) {
    val playing = MusicPlayer.current?.id
    val starred by vm.starred.collectAsStateWithLifecycle()
    val playKey = "$page|play"
    val shuffleKey = "$page|shuffle"
    fun trackKey(i: Int) = "$page|t$i"
    // Beside the corner window (only 340 dp of height left under the header): a smaller cover, and the name and
    // details left to the header above (they were shown twice and pushed Play / Shuffle off the bottom).
    val beside = LiveBehind.preview
    Box(Modifier.fillMaxSize()) {
        d.card.art?.let { AsyncImage(it.small, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.18f) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to AppColors.Background.copy(alpha = 0.6f), 1f to AppColors.Background)))
        Row(Modifier.fillMaxSize().padding(start = Tokens.Gutter, top = if (beside) 8.dp else 40.dp, end = Tokens.Gutter)) {
            Column(Modifier.width(260.dp)) {
                val round = d.card.kind == MusicKind.Artist
                Box(Modifier.size(if (beside) 112.dp else 240.dp).clip(if (round) Tokens.RoundShape else Tokens.CardShape).background(AppColors.Panel)) {
                    d.card.art?.let { AsyncImage(it.large, d.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                if (!beside) {
                    Text(d.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                    Text(d.sub, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                }
                val ready = !d.loading && (d.songs.isNotEmpty() || d.albums.isNotEmpty())
                val toTracks = d.card.kind != MusicKind.Artist && d.songs.isNotEmpty()
                Row(Modifier.padding(top = if (beside) 14.dp else 18.dp)) {
                    Pill(
                        if (d.loading) "Loading…" else "▶  Play",
                        Modifier.focusRequester(focusRequester).remembered(mem, page, playKey).leftOpens(onMenu = onOpenMenu),
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
                // Artists: a radio of music like theirs; albums and artists: a favourite (★) on the server.
                // (Left on the first of them: the menu, as from Play above; it did nothing there.)
                Row(Modifier.padding(top = 10.dp)) {
                    val radio = d.card.kind == MusicKind.Artist
                    if (radio) { Pill("Radio", Modifier.leftOpens(onMenu = onOpenMenu)) { if (ready) vm.artistRadio(d) }; Spacer(Modifier.width(10.dp)) }
                    // An album: on to its artist's page (Back comes back here).
                    d.artist?.let { ar -> Pill("Artist ›", Modifier.leftOpens(enabled = !radio, onMenu = onOpenMenu)) { vm.open(ar) }; Spacer(Modifier.width(10.dp)) }
                    if (d.card.kind != MusicKind.Playlist) Pill(if (d.card.id in starred) "★ Favourite" else "☆ Favourite", Modifier.leftOpens(enabled = !radio && d.artist == null, onMenu = onOpenMenu)) {
                        vm.toggleStar(d.card.id, if (d.card.kind == MusicKind.Artist) "artist" else "album")
                    }
                }
            }
            Spacer(Modifier.width(36.dp))
            if (d.card.kind == MusicKind.Artist) {
                // An artist: their story, most-played songs, albums, and artists like them (Navidrome, where it knows).
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "hint") { HoldHint() }
                    d.bio?.let { bio -> item(key = "bio") {
                        Text(bio, fontSize = 13.sp, lineHeight = 19.sp, color = AppColors.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
                    } }
                    if (d.topSongs.isNotEmpty()) {
                        item(key = "h-top") { RowTitle("Top songs") }
                        itemsIndexed(d.topSongs.take(5), key = { i, t -> "top$i:${t.id}" }) { i, t ->
                            TrackRow((i + 1).toString(), t, showArtist = false, nowPlaying = t.id == playing, starred = t.id in starred,
                                modifier = Modifier.remembered(mem, page, "$page|top$i").focusProperties { left = mem.of(shuffleKey) }) { vm.play(d.topSongs, i) }
                        }
                    }
                    item(key = "h-alb") { RowTitle("Albums", Modifier.padding(top = 10.dp)) }
                    val rowsOf = d.albums.chunked(5)
                    itemsIndexed(rowsOf, key = { i, _ -> "alb$i" }) { _, chunk ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            chunk.forEachIndexed { j, c ->
                                Box(Modifier.weight(1f)) {
                                    MusicCardView(c, null, Modifier.remembered(mem, page, "$page|${c.key}")
                                        .then(if (j == 0) Modifier.focusProperties { left = mem.of(shuffleKey) } else Modifier)) { vm.open(c) }
                                }
                            }
                            repeat(5 - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (d.similar.isNotEmpty()) {
                        item(key = "h-sim") { RowTitle("Similar artists", Modifier.padding(top = 10.dp)) }
                        item(key = "sim") {
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(d.similar.size, key = { "sim" + d.similar[it].key }) { i ->
                                    val c = d.similar[i]
                                    MusicCardView(c, 120.dp, Modifier.remembered(mem, page, "$page|sim${c.key}")
                                        .then(if (i == 0) Modifier.focusProperties { left = mem.of(shuffleKey) } else Modifier)) { vm.open(c) }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item(key = "hint") { HoldHint(Modifier.padding(start = 12.dp, bottom = 4.dp)) }
                    val multiDisc = d.songs.mapNotNull { it.disc }.distinct().size > 1
                    itemsIndexed(d.songs, key = { i, s -> "$i:${s.id}" }) { i, s ->
                        Column {
                            if (multiDisc && (i == 0 || d.songs[i - 1].disc != s.disc)) Text(
                                "Disc ${s.disc}", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else 10.dp, bottom = 4.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            TrackRow(
                                number = if (d.card.kind == MusicKind.Album) (s.track ?: (i + 1)).toString() else (i + 1).toString(),
                                song = s, showArtist = d.card.kind == MusicKind.Playlist, nowPlaying = s.id == playing, starred = s.id in starred,
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

/** What the remote does on the songs: over an album's, a playlist's or an artist's list. */
@Composable
private fun HoldHint(modifier: Modifier = Modifier) {
    Text("OK: play  ·  Hold OK: options", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TrackRow(number: String, song: Song, showArtist: Boolean, nowPlaying: Boolean, modifier: Modifier, starred: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        // Hold OK: play next, add to queue, song radio, favourite.
        onLongClick = { MusicOptions.song = song },
        modifier = modifier.fillMaxWidth().height(if (showArtist) 52.dp else 42.dp),
        scale = NoScale,
        shape = ClickableSurfaceDefaults.shape(Tokens.RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, focusedContainerColor = AppColors.Accent,
            contentColor = AppColors.TextPrimary, focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        val c = androidx.tv.material3.LocalContentColor.current
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (nowPlaying) "♪" else number, fontSize = 14.sp, color = c.copy(alpha = if (nowPlaying) 1f else 0.7f), modifier = Modifier.width(36.dp), maxLines = 1, softWrap = false)
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 15.sp, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (nowPlaying) FontWeight.SemiBold else FontWeight.Normal)
                if (showArtist) Text(song.artist, fontSize = 12.sp, color = c.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (starred) Text("★  ", fontSize = 13.sp, color = c.copy(alpha = 0.85f), maxLines = 1, softWrap = false)
            Text(clock(song.durationSec), fontSize = 13.sp, color = c.copy(alpha = 0.7f), maxLines = 1, softWrap = false)
        }
    }
}

/** A cover with its name under it. [size] null: as wide as its grid cell. */
@Composable
internal fun MusicCardView(c: MusicCard, size: Dp?, modifier: Modifier, onClick: () -> Unit) {
    val round = c.kind == MusicKind.Artist
    Column(if (size != null) Modifier.width(size) else Modifier.fillMaxWidth()) {
        // The shared card (3 dp ring, no growing, no press shrink); artists round.
        FocusCard(
            onClick = onClick,
            // Hold OK: play next, add to queue, favourite (not genres or decades).
            onLongClick = if (c.kind == MusicKind.Genre || c.kind == MusicKind.Decade) null else ({ MusicOptions.card = c }),
            modifier = modifier.onFocusChanged { if (it.isFocused) MusicHover.card = c }.then(if (size != null) Modifier.size(size) else Modifier.fillMaxWidth()),
            shape = if (round) Tokens.RoundShape else Tokens.CardShape,
            aspect = CardAspect.Square,
        ) {
            CardFace(c)
        }
        Text(c.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (c.sub.isNotBlank()) Text(c.sub, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A button on the Music pages (Play, Shuffle, Radio…): the shared pill (no growing, no press shrink). */
@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppPill(text = text, selected = false, onClick = onClick, modifier = modifier, height = 38.dp)
}

internal fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

internal fun clock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
