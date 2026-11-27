package io.github.petebutty.viewarr.ui.launcher

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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.common.FocusCard
import io.github.petebutty.viewarr.ui.home.MenuIcons
import io.github.petebutty.viewarr.ui.home.Page
import io.github.petebutty.viewarr.ui.media.Discover
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.media.FadedTrailer
import io.github.petebutty.viewarr.ui.media.metaLine
import io.github.petebutty.viewarr.ui.player.RowSets
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.appBackground
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A tile in the top row: Live TV, Movies, TV Shows, Library, or a streaming service ([icon]: "svc:<name>"). */
private data class Tile(val icon: String, val label: String, val about: String, val open: () -> Unit) {
    /** The background while it's highlighted: a picture of what it opens (LauncherHome sets it). */
    var art: () -> String? = { null }
}

/**
 * The launcher build's home screen (2026-11-24): a featured banner showing whatever is highlighted, a row of tiles
 * (Live TV, Movies, TV Shows, Library, the streaming services), then Play next, Recently added, Upcoming
 * episodes and a row for each service. Everything comes from the rows Viewarr already loads (Discover.rows) and its
 * Continue watching; picking something opens Viewarr's own screens on it (Launcher.open).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun LauncherHome() {
    val movies: MediaViewModel = hiltViewModel(key = "media-Movie")
    val shows: MediaViewModel = hiltViewModel(key = "media-Show")
    // (Continue watching and Trakt's next episodes for Play next: Viewarr's pages asked for them, so before any had
    // opened Play next only had the new-episode cards.)
    LaunchedEffect(Unit) { movies.load(MediaType.Movie); shows.load(MediaType.Show); shows.loadContinue(MediaType.Show); shows.loadTraktContinue(MediaType.Show) }
    val all by Discover.rows.collectAsStateWithLifecycle()
    val cwMovies by movies.continueItems.collectAsStateWithLifecycle(null)
    val cwShows by shows.continueItems.collectAsStateWithLifecycle(null)
    val trakt by shows.traktContinue.collectAsStateWithLifecycle(null)
    val context = androidx.compose.ui.platform.LocalContext.current
    // Previewarr's one remote preset, Classic, set on its first start (the buttons can each be changed after).
    val remoteVm: io.github.petebutty.viewarr.ui.settings.RemoteViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("classic_set", false)) return@LaunchedEffect
        val s = remoteVm.settings.first { st -> st.loaded }
        if (s.presetBase != io.github.petebutty.viewarr.data.settings.ControlPreset.Classic) remoteVm.applyPreset(io.github.petebutty.viewarr.data.settings.ControlPreset.Classic)
        prefs.edit().putBoolean("classic_set", true).apply()
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Back: "Press Back again to exit" first (one press closed the app straight away).
    var exitArmed by remember { mutableLongStateOf(0L) }
    // Links that arrive while home is showing (a Play next card on the Android home screen, Home Assistant): a title
    // opens its page; a channel or a command opens Viewarr's screens, which carry it out.
    val linkTitle by io.github.petebutty.viewarr.data.media.DeepLink.pending.collectAsStateWithLifecycle()
    val linkChannel by io.github.petebutty.viewarr.data.media.DeepLink.pendingChannel.collectAsStateWithLifecycle()
    val linkCommand by io.github.petebutty.viewarr.data.media.DeepLink.pendingCommand.collectAsStateWithLifecycle()
    LaunchedEffect(linkTitle) {
        val item = linkTitle ?: return@LaunchedEffect
        io.github.petebutty.viewarr.data.media.DeepLink.pending.value = null
        val show = item.type == MediaType.Show
        movies.closeTitlePages(); shows.closeTitlePages()
        if (io.github.petebutty.viewarr.data.media.DeepLink.takePlay()) (if (show) shows else movies).openAndPlay(item, item.nextSeason, item.nextEpisode)
        else (if (show) shows else movies).open(item)
        Launcher.openTitle(if (show) Page.Shows else Page.Movies)
    }
    // (Not a search: LauncherVoice opens that over the home screen, so closing it comes back here.)
    LaunchedEffect(linkChannel, linkCommand) { if (linkChannel != null || (linkCommand != null && linkCommand?.kind != "search" && linkCommand?.kind != "voice")) Launcher.open(Page.Tv) }

    // ---- What's on it ----
    // (The rows worked out once per change of what's loaded, not on every press: they were rebuilt as the highlight moved.)
    val both = remember(all) { all[MediaType.Movie].orEmpty() + all[MediaType.Show].orEmpty() }
    val services = remember(both) { both.mapNotNull { RowSets.name(it) }.distinct() }
    // The tiles' order: yours (hold OK on one to move it, Left / Right, OK to put it down), kept on this box.
    val tilePrefs = remember { context.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE) }
    val tileOrder = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(tilePrefs.getString("tiles", "").orEmpty().split('|').filter { it.isNotEmpty() }) } }
    var carrying by remember { mutableStateOf<String?>(null) }
    // Hold OK on a tile: Move or Hide (hidden ones come back in Settings → Home screen tiles).
    var tileMenu by remember { mutableStateOf<Tile?>(null) }
    // (Move picked from that menu: OK's already been let go there, so the next OK puts the tile down.)
    var okLetGoAfterMenu by remember { mutableStateOf(false) }
    var hiddenTiles by remember { mutableStateOf(HomeTiles.hidden(context)) }
    val musicOn by hiltViewModel<io.github.petebutty.viewarr.ui.music.MusicViewModel>().enabled.collectAsStateWithLifecycle()
    val naturalTiles = buildList {
        // Live TV: the TV guide (2026-11-25: it went straight into the last channel full screen; pick one there).
        if (Access.liveTv) add(Tile("Tv", "Live TV", "The TV guide: what's on now and next on every channel.") { Launcher.open(Page.Tv) })
        add(Tile("Movies", "Movies", "Every film from your servers, IPTV and addons.") {
            movies.closeTitlePages(); movies.openLibrary(null, MediaType.Movie); movies.jumpTo(null); Launcher.open(Page.Movies)
        })
        add(Tile("Shows", "TV Shows", "Your shows, next episodes first.") {
            shows.closeTitlePages(); shows.openLibrary(null, MediaType.Show); shows.jumpTo(null); Launcher.open(Page.Shows)
        })
        add(Tile("Library", "Library", "Every library on your servers: go through everything in one, sorted and filtered your way.") { LauncherLibrary.show() })
        // Music, when it's set up (Navidrome, Plex or Jellyfin music: Viewarr's own Music page).
        if (musicOn) add(Tile("Music", "Music", "Your music: albums, artists, playlists and radio.") { Launcher.open(Page.Music) })
        // Sports: the fixtures for your teams and leagues and the channel showing each (Viewarr's Sports page).
        if (Access.liveTv) add(Tile("Sports", "Sports", "Fixtures for your teams and leagues, and the channel showing each.") {
            Launcher.open(Page.Tv, action = { hs -> hs.openSportsPage(true) }, readyWhen = { hs -> hs.sportsOpen })
        })
        // Recordings: recorded, recording now, scheduled and series recordings, as rows of cards (RecordingsWide).
        if (Access.liveTv) add(Tile("Recordings", "Recordings", "What you've recorded, what's recording now and what's coming up.") { Launcher.open(Page.Recordings) })
        services.forEach { name -> add(Tile("svc:$name", name, "New, originals, popular and top rated on $name.") { openServiceHere(name, movies, shows) }) }
    }
    // A film or episode in the mini player: a "Now playing" tile first, OK back to full screen (2026-11-25: from home
    // there was no way back to it).
    val vod = io.github.petebutty.viewarr.ui.media.VodHost
    // A live channel carries on in the corner here too (Back from the guide: home with it still playing; 2026-11-25).
    val playback: io.github.petebutty.viewarr.playback.PlaybackViewModel = hiltViewModel()
    val liveCh by playback.channel.collectAsStateWithLifecycle()
    val liveUp = liveCh != null && !vod.active
    // (A film started from here ends the channel: one picture in the corner.)
    LaunchedEffect(vod.active) { if (vod.active && playback.channel.value != null) playback.stop() }
    val nowItem = if (vod.active && vod.mini) vod.source?.item?.invoke() else null
    val playingTile = if (vod.active && vod.mini) Tile("playing", "Now playing", nowItem?.title?.let { "Back to full screen: $it" } ?: "Back to full screen") { vod.expand() }
        .also { t -> t.art = { nowItem?.backdrop ?: nowItem?.poster } }
    else if (liveUp) Tile("playing", "Now playing", "Back to full screen: ${liveCh?.name.orEmpty()}") {
        Launcher.open(Page.Tv, action = { hs -> hs.fullscreen = true; hs.showBanner() }, readyWhen = { hs -> hs.fullscreen })
    }
    else null
    // A film made full screen from the Now playing tile plays over the home screen: its buttons (Settings, search, the
    // guide, Movies / Shows / Recordings) do the launcher's thing here; Viewarr's screens set their own when they're up.
    // (They pointed at Viewarr's screens that were gone: Settings stopped the film, the rest only shrank it.)
    androidx.compose.runtime.SideEffect {
        val pb = io.github.petebutty.viewarr.ui.player.PlayerBridge
        fun shrink() { vod.behind = false; vod.minimise() }
        vod.openMenu = {}
        vod.openSearch = { shrink(); Launcher.openSearch() }
        vod.afterStop = {}
        pb.openSettings = { shrink(); Launcher.settingsOpen = true }
        pb.openGuide = { shrink(); Launcher.open(Page.Tv) }
        pb.openMenuOnLiveTv = {}
        pb.openPage = { name ->
            shrink()
            when (name) {
                "Movies" -> { movies.closeTitlePages(); movies.openLibrary(null, MediaType.Movie); movies.jumpTo(null); Launcher.open(Page.Movies) }
                "Shows" -> { shows.closeTitlePages(); shows.openLibrary(null, MediaType.Show); shows.jumpTo(null); Launcher.open(Page.Shows) }
                else -> Launcher.open(Page.Recordings)
            }
        }
    }
    // (Every tile it could show, for Settings → Home screen tiles; the hidden ones left out here.)
    LaunchedEffect(naturalTiles.map { it.icon }) { HomeTiles.remember(context, naturalTiles.map { it.icon to it.label }) }
    LaunchedEffect(Launcher.home) { hiddenTiles = HomeTiles.hidden(context) }
    val tiles = listOfNotNull(playingTile) + naturalTiles.filter { it.icon !in hiddenTiles }.sortedBy { t -> tileOrder.indexOf(t.icon).let { if (it < 0) Int.MAX_VALUE else it } }
    fun moveTile(icon: String, by: Int) {
        // (From the order as it is now, not as last drawn: a held button's quick repeats lost moves. And within the
        // whole saved order: tiles not showing just now (Music off, a service not loaded) keep their places.)
        fun rank(t: String) = tileOrder.indexOf(t).let { if (it < 0) Int.MAX_VALUE else it }
        // (Only the tiles you can see: next to a hidden one, a press swapped with it and seemed to do nothing.
        // 2026-11-25. Hidden ones keep their saved place in the full order below.)
        val visible = naturalTiles.map { it.icon }.filter { it !in hiddenTiles }.sortedBy { rank(it) }
        val i = visible.indexOf(icon); val j = i + by
        if (i < 0 || j !in visible.indices) return
        val neighbour = visible[j]
        val full = (tileOrder.toList() + visible).distinct().toMutableList()
        full.remove(icon)
        val n = full.indexOf(neighbour)
        full.add(if (by < 0) n else n + 1, icon)
        tileOrder.clear(); tileOrder.addAll(full)
        // The moved tile kept in view. The row holds on to its first card by key as the order changes, so mostly it
        // already is; scrolling straight away went by the old order and left the tile off the left edge
        // (2026-11-25). Only once the row has been laid out again, and only if the tile isn't on screen.
        Launcher.rowStates["tiles"]?.let { st -> scope.launch {
            awaitFrame(); awaitFrame()
            val seen = st.layoutInfo.visibleItemsInfo.firstOrNull { it.key == icon }
            if (seen == null || seen.offset < 0 || seen.offset + seen.size > st.layoutInfo.viewportEndOffset) {
                // (Its place in the row as drawn: the Now playing tile, when there is one, comes first.)
                val at = (if (playingTile != null) 1 else 0) + naturalTiles.map { it.icon }.filter { it !in hiddenTiles }.sortedBy { rank(it) }.indexOf(icon).coerceAtLeast(0)
                runCatching { st.scrollToItem(at) }
            }
        } }
    }
    fun dropTile() { carrying = null; tilePrefs.edit().putString("tiles", tileOrder.joinToString("|")).apply() }
    // Play next: episodes only (2026-11-25: part-watched films don't belong here): the shows you're part-way through,
    // then new episodes in your library of shows you've been watching or follow (FollowedShows), one card a show.
    val newEps = remember(cwShows, trakt) { shows.newEpisodeCards() }
    // Most recently watched first (2026-11-25): each show's latest time from Plex, Jellyfin / Emby or Trakt; a new
    // episode of a show you aren't part-way through goes by when it arrived.
    // (One card per key too: a show grouped twice, once with its TMDB id and once without, crashed the row.)
    val playNext = remember(cwShows, trakt, newEps) {
        (cwShows.orEmpty() + trakt.orEmpty() + newEps).filter { it.type == MediaType.Show }
            .groupBy { it.tmdbId?.let { id -> "tmdb:$id" } ?: it.title.lowercase() }.values
            .map { g -> g.first() to (g.mapNotNull { it.lastWatchedAt }.maxOrNull() ?: 0L) }
            .sortedByDescending { it.second }.map { it.first }.distinctBy { it.key }.take(20)
    }
    val recent = remember(all) { Discover.pageRows(Discover.NEW, all).firstOrNull { it.id == "disc-new-lib" }?.items.orEmpty().distinctBy { it.key } }
    val upcoming = remember(all) { all[MediaType.Show].orEmpty().firstOrNull { it.id.startsWith("upcoming-") }?.items.orEmpty().distinctBy { it.key } }
    val serviceRows = remember(both, services) {
        services.map { name ->
            val rows = both.filter { RowSets.name(it) == name }
            name to interleave(rows).take(20)
        }.filter { it.second.isNotEmpty() }
    }

    // Each tile's background: a picture of what it opens (the first title with artwork in its rows).
    fun artOf(items: List<MediaItem>?) = items.orEmpty().firstNotNullOfOrNull { it.backdrop }
    fun firstArt(rows: List<MediaRow>?) = rows.orEmpty().firstNotNullOfOrNull { r -> artOf(r.items) }
    tiles.forEach { t ->
        t.art = when {
            // (Live TV: its own picture, a wall of screens with a LIVE badge, tools/launcher/livetv_art.py; a
            // programme on the last channel was a random one.)
            t.icon == "Tv" -> ({ "file:///android_asset/launcher/live_tv.jpg" })
            t.icon == "Movies" -> ({ firstArt(all[MediaType.Movie]) })
            t.icon == "Shows" -> ({ firstArt(all[MediaType.Show]) })
            t.icon == "Library" -> ({ artOf(recent) ?: artOf(playNext) })
            t.icon.startsWith("svc:") -> ({ artOf(serviceRows.firstOrNull { it.first == t.icon.removePrefix("svc:") }?.second) })
            else -> ({ null })
        }
    }

    // ---- The banner: the highlighted title (or tile) ----
    // (Kept as state objects and read only where they're shown (the banner, its trailer, the wallpaper): read here,
    // every press rebuilt the whole home screen.)
    val featuredState = remember { mutableStateOf<MediaItem?>(null) }
    val featuredTileState = remember { mutableStateOf<Tile?>(tiles.firstOrNull()) }
    val firstTile = remember { FocusRequester() }
    // Back home: on the card you left from (the rows where you left them, Launcher.columnState / rowStates); else the first tile.
    val requesters = remember { HashMap<String, FocusRequester>() }
    // Each row's card keys in order (filled as the rows are laid out), for Back.
    val rowItems = remember { HashMap<String, List<String>>() }
    // Rows whose first title isn't their first card (a service's row starts with its tile): where "the front" is.
    val rowLead = remember { HashMap<String, Int>() }
    // Back: in the middle of a row, to its first card; on a row's first card, up to the top (the first tile); on the
    // first tile, "Press Back again to exit" (2026-11-25, as Android TV launchers do).
    BackHandler {
        val fk = Launcher.lastFocus
        val row = fk?.substringBefore('|')
        val keys = row?.let { rowItems[it] }
        val at = keys?.indexOf(fk.substringAfter('|')) ?: -1
        val target: Pair<String, String>? = when {
            row != null && keys != null && at > 0 -> row to keys[0]
            row != null && row != "tiles" -> "tiles" to (rowItems["tiles"]?.firstOrNull() ?: "")
            row == "tiles" && at > 0 -> "tiles" to keys!![0]
            else -> null
        }
        if (target == null) {
            if (System.currentTimeMillis() - exitArmed < 2_500) (context as? android.app.Activity)?.finish()
            else { exitArmed = System.currentTimeMillis(); android.widget.Toast.makeText(context, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show() }
            return@BackHandler
        }
        scope.launch {
            if (target.first == "tiles" && row != "tiles") runCatching { Launcher.columnState.scrollToItem(0) }
            Launcher.rowStates[target.first]?.let { runCatching { it.scrollToItem(rowLead[target.first] ?: 0) } }
            awaitFrame(); awaitFrame()
            val r = requesters["${target.first}|${target.second}"] ?: (if (target.first == "tiles") firstTile else null)
            r?.let { runCatching { it.requestFocus() } }
        }
    }
    LaunchedEffect(Unit) {
        awaitFrame(); awaitFrame()
        // (A film in the mini player or a live channel in the corner: its Now playing tile, first.)
        val nowUp = (vod.active && vod.mini) || liveUp
        val back = if (nowUp) null else Launcher.lastFocus?.let { requesters[it] }
        if (back == null && nowUp) { runCatching { Launcher.columnState.scrollToItem(0) }; Launcher.rowStates["tiles"]?.let { runCatching { it.scrollToItem(0) } }; awaitFrame() }
        if (back == null || runCatching { back.requestFocus() }.isFailure) runCatching { firstTile.requestFocus() }
    }
    // The film coming back to the mini player (from full screen) puts the highlight on its Now playing tile; the tile
    // going (the film closed or ended) moves it to the first tile, rather than leaving nothing highlighted.
    // (The live channel's Now playing tile as the film's: 2026-11-25, stopping it left nothing highlighted.)
    var filmWas by remember { mutableStateOf((vod.active && vod.mini) || liveUp) }
    LaunchedEffect(vod.active, vod.mini, liveUp) {
        val mini = (vod.active && vod.mini) || liveUp
        if (mini == filmWas) return@LaunchedEffect
        filmWas = mini
        awaitFrame(); awaitFrame()
        if (mini) { Launcher.rowStates["tiles"]?.let { runCatching { it.scrollToItem(0) } }; awaitFrame(); runCatching { firstTile.requestFocus() } }
        // (Not while the film is full screen over the home screen: the highlight is the player's then.)
        else if (!vod.active && (Launcher.lastFocus == "tiles|playing" || Launcher.lastFocus == null)) { Launcher.lastFocus = null; runCatching { firstTile.requestFocus() } }
    }
    fun focusKey(row: String, item: String) = "$row|$item"

    // The background: the highlighted title's artwork in the top-right corner, faded in (LauncherLook), once you've
    // stopped on it; a tile shows a picture of what it opens.
    HomeWallpaper(featuredState, featuredTileState, tiles, movies, shows)
    // A film in the mini player over the home screen: hold Back closes it (a tap is Back as usual).
    val backHold = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    var backHeld by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().appBackground(titleArt = true).onPreviewKeyEvent { e ->
        if (e.key != Key.Back) return@onPreviewKeyEvent false
        when {
            // (A fresh press is a new press: a hold's lost release mustn't swallow it.)
            backHeld && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { backHeld = false; false }
            backHeld -> { if (e.type == KeyEventType.KeyUp) backHeld = false; true }
            !(io.github.petebutty.viewarr.ui.media.VodHost.active && io.github.petebutty.viewarr.ui.media.VodHost.mini) && !liveUp -> false
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> {
                backHold[0]?.cancel()
                backHold[0] = scope.launch {
                    delay(500); backHeld = true
                    if (io.github.petebutty.viewarr.ui.media.VodHost.active) io.github.petebutty.viewarr.ui.media.VodHost.close() else playback.stop()
                }
                false
            }
            e.type == KeyEventType.KeyUp -> { backHold[0]?.cancel(); false }
            else -> false
        }
    }) {
        // The highlighted title's trailer, top right over its picture, once you've stopped on it (Settings →
        // Playback → Trailers, as Viewarr's pages), faded into the page at its left and lower edges.
        HomeTrailer(featuredState, carrying == null, movies)
        Banner(featuredState, featuredTileState, movies)
        TopCorner(Modifier.align(Alignment.TopEnd))
        if (liveUp) LiveCorner(playback, Modifier.padding(start = io.github.petebutty.viewarr.ui.theme.CornerPicture.inset.first, top = io.github.petebutty.viewarr.ui.theme.CornerPicture.inset.second))
        tileMenu?.let { t ->
            io.github.petebutty.viewarr.ui.common.OptionsPopup(
                title = t.label,
                options = listOf(
                    io.github.petebutty.viewarr.ui.common.PopupOption("Move") { carrying = t.icon; okLetGoAfterMenu = true },
                    io.github.petebutty.viewarr.ui.common.PopupOption("Hide") {
                        hiddenTiles = HomeTiles.setHidden(context, t.icon, true)
                        scope.launch { awaitFrame(); awaitFrame(); runCatching { firstTile.requestFocus() } }
                    },
                ),
                onClose = { tileMenu = null },
            )
        }
        // (As Viewarr's rows: the row you're on comes up to the top place; the highlight keeps its place in each row
        // and the cards slide under it, RowSlide.)
        val rowTitleRoom = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
        val topRow = remember(rowTitleRoom) { topRow(rowTitleRoom) }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides topRow) {
        LazyColumn(
            state = Launcher.columnState,
            modifier = Modifier.fillMaxSize().padding(top = 240.dp),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "tiles") {
                // Moving a tile: Left / Right move it, OK (on release, so it doesn't open), Back, Up or Down put it down.
                // (The OK held to pick it up keeps sending presses until it's let go: those, and its release, are ignored;
                // only a fresh press of OK puts it down. It went straight back down, 2026-11-25.)
                var dropOnRelease by remember { mutableStateOf(false) }
                var okLetGo by remember { mutableStateOf(false) }
                LaunchedEffect(okLetGoAfterMenu) { if (okLetGoAfterMenu) { okLetGo = true; okLetGoAfterMenu = false } }
                Box(Modifier.onPreviewKeyEvent { e ->
                    val c = carrying ?: return@onPreviewKeyEvent if (dropOnRelease && e.type == KeyEventType.KeyUp) { dropOnRelease = false; true } else false
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    // (The hold's own release goes on to the tile: it ends the tile's long press there, which otherwise
                    // swallowed the next OK on it. It doesn't open it: the tile ignores the release of a held OK.)
                    if (ok && e.type == KeyEventType.KeyUp) { val first = !okLetGo; okLetGo = true; return@onPreviewKeyEvent !first }
                    if (e.type == KeyEventType.KeyDown) when {
                        e.key == Key.DirectionLeft -> moveTile(c, -1)
                        e.key == Key.DirectionRight -> moveTile(c, 1)
                        ok -> if (okLetGo && e.nativeKeyEvent.repeatCount == 0) { dropTile(); okLetGo = false; dropOnRelease = true }
                        else -> { dropTile(); okLetGo = false }
                    }
                    true
                }) {
                rowItems["tiles"] = tiles.map { it.icon }
                SlideRow("tiles", tiles, { it.icon }) { i, t, m ->
                    val fk = focusKey("tiles", t.icon)
                    TileCard(t, movies, lifted = carrying == t.icon, onLongClick = { if (t.icon != "playing") tileMenu = t }, modifier = m.then(if (i == 0) Modifier.focusRequester(firstTile) else Modifier)
                        .focusRequester(requesters.getOrPut(fk) { FocusRequester() })
                        .onFocusChanged { if (it.isFocused) { featuredState.value = null; featuredTileState.value = t; Launcher.lastFocus = fk } })
                }
                }
            }
            // [lead]: a service's row starts with its own tile (its logo), just off the row's left edge: Left from
            // the first title brings it in, so it's clear which service the row is (2026-11-25); OK opens the service.
            fun row(key: String, title: String, items: List<MediaItem>, lead: Tile? = null) {
                if (items.isEmpty()) return
                rowItems[key] = items.map { it.key }
                if (lead != null) rowLead[key] = 1
                item(key = key) {
                    Column {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary,
                            modifier = Modifier.padding(start = 48.dp))
                        SlideRow(key, listOfNotNull<Any>(lead) + items, { if (it is Tile) "lead" else (it as MediaItem).key }, start = if (lead != null) 1 else 0) { _, entry, m ->
                            if (entry is Tile) {
                                val fk = focusKey(key, "lead")
                                LeadCard(entry, movies, m.focusRequester(requesters.getOrPut(fk) { FocusRequester() })
                                    .onFocusChanged { if (it.isFocused) { featuredState.value = null; featuredTileState.value = entry; Launcher.lastFocus = fk } })
                                return@SlideRow
                            }
                            val item = entry as MediaItem
                            val fk = focusKey(key, item.key)
                            TitleCard(item, if (item.type == MediaType.Show) shows else movies, m.focusRequester(requesters.getOrPut(fk) { FocusRequester() })
                                .onFocusChanged { if (it.isFocused) { featuredState.value = item; featuredTileState.value = null; Launcher.lastFocus = fk } }) {
                                // (The title opened first, on the view model Viewarr's screens use, so its page is what
                                // they show at once; Back from it comes home.)
                                val show = item.type == MediaType.Show
                                movies.closeTitlePages(); shows.closeTitlePages()
                                (if (show) shows else movies).open(item)
                                Launcher.openTitle(if (show) Page.Shows else Page.Movies)
                            }
                        }
                    }
                }
            }
            row("next", "Play next", playNext)
            row("recent", "Recently added", recent)
            row("upcoming", "Upcoming episodes", upcoming)
            serviceRows.forEach { (name, items) -> row("svc-$name", name, items, lead = tiles.firstOrNull { it.icon == "svc:$name" }) }
        }
        }
    }
}

/** The row being moved to sits at the top of the rows, its name showing above it ([titleRoom]). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun topRow(titleRoom: Float) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    // (As Viewarr's own rows: Settings → Appearance → List scrolling, Instant by default, the list in the same frame as
    // the highlight; a slow eased slide here felt floaty. Smooth: a short quick slide.)
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float>
        get() = if (io.github.petebutty.viewarr.ui.common.ListScroll.instant) androidx.compose.animation.core.snap()
            else androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - titleRoom
}

/** A row that moves as Viewarr's poster rows do: the highlight stays at the start, the cards slide under it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun <T> SlideRow(rowKey: String, items: List<T>, key: (T) -> Any, start: Int = 0, card: @Composable (Int, T, Modifier) -> Unit) {
    // ([start]: the card it starts on.)
    val state = remember(rowKey) { Launcher.rowStates.getOrPut(rowKey) { androidx.compose.foundation.lazy.LazyListState(start) } }
    val slide = io.github.petebutty.viewarr.ui.media.rememberRowSlide(state) { items.size }
    val firstCard = remember { FocusRequester() }
    val hadFocus = remember { booleanArrayOf(false) }
    // A row whose cards change while it hasn't the highlight (Play next: Trakt's shows arrive after the servers' and
    // sort in ahead of them) goes back to its start: the row keeps its first card in view as the list changes, so it
    // had slid along and coming down into it landed on the third card (2026-11-25).
    val firstKey = items.getOrNull(start)?.let(key)
    LaunchedEffect(firstKey) {
        if (!hadFocus[0] && (state.firstVisibleItemIndex != start || state.firstVisibleItemScrollOffset != 0)) runCatching { state.scrollToItem(start) }
    }
    val rowScope = androidx.compose.runtime.rememberCoroutineScope()
    // (The highlighted card followed by its key: a tile moved along the row keeps the highlight but not its place.)
    val focusedKey = remember { arrayOfNulls<Any>(1) }
    androidx.compose.runtime.SideEffect {
        if (slide.focused >= 0) focusedKey[0]?.let { k -> items.indexOfFirst { key(it) == k }.takeIf { it >= 0 }?.let { slide.focused = it } }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides io.github.petebutty.viewarr.ui.media.NoRowScroll) {
        LazyRow(
            state = state,
            // (Room above and below for the highlighted card's grown size and ring: it was cut off at the top.)
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // (Nothing drawn left of the first card's place but its glow: a service's tile waits there, out of sight.)
            // Every row begins at its first card (2026-11-25): coming down or up into it lands there, and leaving it
            // slides it back to its start ([start]: a service's row starts on its first title, its tile just off the
            // left). (The tiles too, unless one is being moved.)
            modifier = Modifier
                .focusProperties { enter = { if (state.layoutInfo.visibleItemsInfo.any { it.index == start }) firstCard else androidx.compose.ui.focus.FocusRequester.Default } }
                .onFocusChanged {
                    // (Only on leaving: the first call, as the row is laid out, isn't one. It slid the row back before
                    // coming home could put the highlight back on the card you'd opened, which then wasn't there.)
                    if (it.hasFocus) hadFocus[0] = true
                    else if (hadFocus[0]) {
                        hadFocus[0] = false
                        slide.focused = -1
                        if (state.firstVisibleItemIndex != start || state.firstVisibleItemScrollOffset != 0) rowScope.launch { runCatching { state.scrollToItem(start) } }
                    }
                }
                .drawWithContent { clipRect(left = (if (start > 0) 34 else 20).dp.toPx(), top = -size.height, right = size.width * 2, bottom = size.height * 2) { this@drawWithContent.drawContent() } }
                .then(slide.modifier),
        ) {
            itemsIndexed(items, key = { _, it -> key(it) }) { i, it ->
                card(i, it, Modifier.then(if (i == start) Modifier.focusRequester(firstCard) else Modifier)
                    .onFocusChanged { f -> if (f.isFocused) { slide.focused = i; focusedKey[0] = key(it) } })
            }
        }
    }
}

// (Each page set up on the view models Viewarr's screens use before they come up, so they open straight on it:
// opening Movies first and switching after showed the Movies page for a moment. As HomeState.openService /
// openMyLibrary do it.)
private fun openServiceHere(name: String, movies: MediaViewModel, shows: MediaViewModel) {
    val key = "name:$name"
    val show = shows.menuRows.value.rows.any { RowSets.matches(key, it) }
    val type = if (show) MediaType.Show else MediaType.Movie
    val mvm = if (show) shows else movies
    if (mvm.currentRowSet.value != key) { mvm.openLibrary(null, type); mvm.jumpTo(null) }
    mvm.closeTitlePages()
    val (other, otherHidden) = (if (show) movies else shows).orderedRows.value
    val extra = other.filter { RowSets.matches(key, it) && it.id !in otherHidden }
        .map { it.copy(title = it.title + if (show) " (films)" else " (shows)") }
    mvm.showRowSet(key, extra)
    Launcher.open(if (show) Page.Shows else Page.Movies)
}

/** Several rows as one: their titles in turn, each once. */
private fun interleave(rows: List<MediaRow>): List<MediaItem> {
    val all = ArrayList<MediaItem>()
    val longest = rows.maxOfOrNull { it.items.size } ?: 0
    for (i in 0 until longest) for (r in rows) r.items.getOrNull(i)?.let { all += it }
    return io.github.petebutty.viewarr.data.media.sameTitleOnce(all)
}

/**
 * The background: the highlighted title's artwork in the top-right corner, faded in (LauncherLook), once you've
 * stopped on it; a tile shows a picture of what it opens (looked up from the current tiles, so a tile's picture that
 * arrives after you've stopped on it still shows).
 */
@Composable
private fun HomeWallpaper(itemState: androidx.compose.runtime.State<MediaItem?>, tileState: androidx.compose.runtime.State<Tile?>, tiles: List<Tile>, movies: MediaViewModel, shows: MediaViewModel) {
    val f = itemState.value
    val icon = tileState.value?.icon
    val tileArt = if (f == null && icon != null) tiles.firstOrNull { it.icon == icon }?.art?.invoke() else null
    LaunchedEffect(f?.key, icon, tileArt) {
        val art = f?.let { it.backdrop ?: it.poster }
            ?: f?.let { runCatching { (if (it.type == MediaType.Show) shows else movies).publicArt(it)?.first }.getOrNull() }
            ?: tileArt ?: return@LaunchedEffect
        delay(300); io.github.petebutty.viewarr.ui.theme.Wallpaper.title = art
    }
}

/** The highlighted title's trailer, top right over its picture, once you've stopped on it (Settings → Playback →
 *  Trailers, as Viewarr's pages), faded into the page at its left and lower edges. */
@Composable
private fun HomeTrailer(itemState: androidx.compose.runtime.State<MediaItem?>, allowed: Boolean, vm: MediaViewModel) {
    val item = itemState.value
    // (In the corner picture's place, the still fading away under it; down to the tiles: FadedTrailer.)
    Box(Modifier.fillMaxWidth()) {
        FadedTrailer(item, active = item != null && allowed, trailers = vm.trailers, fadeEnd = 300.dp)
    }
}

/** The top of the screen: the highlighted title's picture faded into the background, its name, facts and story. */
@Composable
private fun Banner(itemState: androidx.compose.runtime.State<MediaItem?>, tileState: androidx.compose.runtime.State<Tile?>, vm: MediaViewModel) {
    val item = itemState.value
    val tile = tileState.value
    // (As Viewarr's own banner: the details slide in from the left, easing to a stop, once you've settled on something;
    // the picture fades from one to the next.)
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(item?.key ?: tile?.label) {
        anim.snapTo(0f); delay(120)
        anim.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    }
    Box(Modifier.fillMaxWidth().height(300.dp)) {
        // (The picture itself is the page's background, appBackground: the same corner artwork as every page.)
        Column(Modifier.padding(start = 48.dp, top = 54.dp).width(460.dp).graphicsLayer {
            val t = anim.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
            alpha = (t * 2.5f).coerceAtMost(1f)
            translationX = -(1f - t) * 48.dp.toPx()
        }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (item != null) (if (item.type == MediaType.Show) "Show" else "Film") else "Previewarr", fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, color = AppColors.Accent)
            // A title's own logo where it has one (Viewarr's TitleLogo), else its name.
            if (item != null) io.github.petebutty.viewarr.ui.media.TitleLogo(item, vm::logoFor, height = 64.dp, maxWidth = 380.dp, fontSize = 32)
            // A service: its wordmark (LauncherBrands) rather than its name written out.
            else {
                val svc = tile != null && tile.icon.startsWith("svc:")
                val marked = if (svc) LauncherBrands.BannerMark(tile!!.label, vm, 64.dp) else false
                if (!marked) Text(tile?.label ?: "", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item != null) Text(listOfNotNull(item.subtitle, metaLine(item)).joinToString("  ·  "), fontSize = 13.sp,
                color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item?.overview ?: tile?.about ?: "", fontSize = 14.sp, color = AppColors.TextPrimary.copy(alpha = 0.85f),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Voice search, search, Settings and the clock, top right. */
@Composable
private fun TopCorner(modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    Row(modifier.padding(top = 18.dp, end = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(AppIcons.Mic) { VoiceBar.ask() }
        RoundIcon(AppIcons.Search) { Launcher.openSearch() }
        RoundIcon(AppIcons.Settings) { Launcher.settingsOpen = true }
        Text(Dates.time(now), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.size(36.dp), shape = RoundedCornerShape(50), containerColor = Color.White.copy(alpha = 0.08f)) {
        androidx.compose.material3.Icon(icon, null, tint = AppColors.TextPrimary, modifier = Modifier.align(Alignment.Center).size(18.dp))
    }
}

/** A launcher tile: its icon (a service's own logo) and name on a tinted card. */
@Composable
private fun TileCard(t: Tile, vm: MediaViewModel, lifted: Boolean, onLongClick: () -> Unit, modifier: Modifier) {
    // (The highlight: the rotating glow ring every card has, FocusCard / GlowHighlight.)
    var lit by remember { mutableStateOf(false) }
    Box {
    // (Picked up to move: raised a little, with arrows either side.)
    Box(Modifier.graphicsLayer { translationY = if (lifted) -10.dp.toPx() else 0f }) {
    FocusCard(onClick = t.open, onLongClick = onLongClick, modifier = modifier.width(156.dp).height(88.dp).onFocusChanged { lit = it.isFocused }, shape = RoundedCornerShape(12.dp),
        containerColor = AppColors.Panel, focusedScale = 1.06f) {
        @Composable fun iconAndName(iconSize: androidx.compose.ui.unit.Dp) =
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                MenuIcons.Entry(t.icon, vm, size = iconSize, tint = AppColors.TextPrimary)
                Text(t.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        // A streaming service: its own colours and wordmark filling the tile (LauncherBrands); else its app icon, big.
        if (t.icon.startsWith("svc:")) LauncherBrands.TileFace(t.label, vm) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MenuIcons.Entry(t.icon, vm, size = 54.dp, tint = AppColors.TextPrimary) }
        } else if (t.icon == "playing") NowPlayingFace(t.art())
        else LauncherTiles.Face(t.icon, t.label) { iconAndName(28.dp) }
    }
    if (lifted) {
        Text("‹", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.align(Alignment.CenterStart).offset(x = (-16).dp))
        Text("›", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.align(Alignment.CenterEnd).offset(x = 16.dp))
    }
    }
    }
}

/** The "Now playing" tile: the film's picture, darkened, with a play mark and NOW PLAYING. */
@Composable
private fun NowPlayingFace(art: String?) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        art?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 8.dp)) {
            androidx.compose.material3.Icon(io.github.petebutty.viewarr.ui.common.AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Text("NOW PLAYING", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 1.sp)
        }
    }
}

/** A home card's size: the same as the pages' wide cards (Settings → Row cards → Wide), so home fits as many rows. */
@Composable
private fun cardWidth(): androidx.compose.ui.unit.Dp = AppColors.palette.rowPosterWidth.dp * 16f / 9f * 1.05f

/** A service's tile at the start of its row: card sized, its logo filling it (LauncherBrands); OK opens it. */
@Composable
private fun LeadCard(t: Tile, vm: MediaViewModel, modifier: Modifier) {
    val w = cardWidth()
    FocusCard(onClick = t.open, modifier = modifier.width(w).height(w * 9f / 16f),
        shape = RoundedCornerShape(10.dp), containerColor = AppColors.Panel, focusedScale = 1.05f) {
        LauncherBrands.TileFace(t.label, vm) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MenuIcons.Entry(t.icon, vm, size = 56.dp, tint = AppColors.TextPrimary) }
        }
    }
}

/**
 * A title: its wide picture with its name (and episode) across the foot on a soft shade, and for something
 * part-watched, how far through; the pages' wide card (2026-11-25: names under the cards took the height of a row).
 */
@Composable
private fun TitleCard(item: MediaItem, vm: MediaViewModel, modifier: Modifier, onClick: () -> Unit) {
    // No picture of its own (a new-episode card): the show's from TMDB.
    val ownArt = item.backdrop ?: item.poster
    val art by androidx.compose.runtime.produceState(ownArt, item.key) { if (ownArt == null) value = runCatching { vm.publicArt(item)?.first }.getOrNull() }
    val w = cardWidth()
    FocusCard(onClick = onClick, modifier = modifier.width(w).height(w * 9f / 16f), shape = RoundedCornerShape(10.dp),
        containerColor = AppColors.Panel, focusedScale = 1.05f) {
        Box(Modifier.fillMaxSize()) {
            art?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
                .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = if (item.progress != null) 10.dp else 6.dp)) {
                Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.subtitle?.let { Text(it, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            item.progress?.takeIf { it > 0f }?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp).background(AppColors.Accent))
            }
        }
    }
}

/**
 * The live channel in the corner over the home screen, where films' mini player sits (CornerPicture): the same player
 * live TV uses, which carries on by itself, shown here. (A channel mpv took over isn't shown here; its sound goes on.)
 */
@Composable
internal fun LiveCorner(playback: io.github.petebutty.viewarr.playback.PlaybackViewModel, modifier: Modifier) {
    val version by playback.playerVersion.collectAsStateWithLifecycle()
    val (w, h) = io.github.petebutty.viewarr.ui.theme.CornerPicture.size()
    Box(modifier.size(w, h).background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { view ->
                @Suppress("UNUSED_EXPRESSION") version
                if (view.player !== playback.player) view.player = playback.player
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

