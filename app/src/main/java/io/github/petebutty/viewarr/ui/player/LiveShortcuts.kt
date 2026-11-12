package io.github.petebutty.viewarr.ui.player

import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import io.github.petebutty.viewarr.data.media.MediaLibrary
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.ui.common.AppIcons

/** One shortcut that can sit under the live TV controls. */
/** Live TV playing in a corner over another page (Live TV hub, step 2). Trailer previews wait while it is. */
object LiveCorner {
    private val state = androidx.compose.runtime.mutableStateOf(false)
    var on: Boolean
        get() = state.value
        set(v) { state.value = v }
}

/** A card in the live TV controls' poster rows: a title (picture landscape), or a channel ([logo] on a plain tile). */
data class OsdPoster(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val logo: Boolean = false,
    val progress: Float? = null,
    /** No picture: this instead, big, on a [tint] gradient (Live now's sport icon). */
    val emoji: String? = null,
    val tint: List<Long>? = null,
    /** Hold OK: a menu of these (Continue watching: Go to show, Go to season, Play from the start). */
    val options: List<CardOption> = emptyList(),
    /** OK plays it (Continue watching): the film playing makes way for it rather than going to the corner. */
    val plays: Boolean = false,
    /** Shown instead when [image] can't be loaded (an episode's still missing: the show's picture). */
    val fallbackImage: String? = null,
    val onClick: () -> Unit,
)

/** One choice in a row card's hold-OK menu. [plays]: it starts playback (the film playing isn't sent to the corner). */
data class CardOption(val label: String, val plays: Boolean = false, val run: () -> Unit)

/** The row card whose hold-OK menu is open (live TV's rows and the film player's share it). */
object CardMenu {
    private val state = androidx.compose.runtime.mutableStateOf<OsdPoster?>(null)
    var open: OsdPoster?
        get() = state.value
        set(v) { state.value = v }
}

data class OsdRow(val title: String, val items: List<OsdPoster>)

/** [emoji]: shown instead of [icon]; [logoName]: a network / service whose logo replaces the icon once found. */
data class LiveShortcut(
    val id: String, val label: String, val icon: ImageVector, val emoji: String? = null, val logoName: String? = null,
    /** Shortcuts without a logo: a coloured disc (two-colour gradient, ARGB) behind their white icon. */
    val colors: List<Long>? = null,
)

/** The coloured discs for Viewarr's own shortcuts, so they sit with the services' logos. */
object ShortcutColours {
    val guide = listOf(0xFF00B4D8, 0xFF0077B6)
    val movies = listOf(0xFFFF7A3D, 0xFFE53950)
    val shows = listOf(0xFF7B5CFF, 0xFF3A6BFF)
    val music = listOf(0xFFFF4FA3, 0xFFB03AFF)
    val recordings = listOf(0xFFFF5A5A, 0xFFC2185B)
    val search = listOf(0xFF2ED6A3, 0xFF14A0B8)
    val settings = listOf(0xFF8A97AD, 0xFF4E5A70)
    val live = listOf(0xFFFF4D4D, 0xFFB3122E)
    val sports = listOf(0xFF34C759, 0xFF0B6E3A)
    val library = listOf(0xFFFFB347, 0xFFFF7043)
    val row = listOf(0xFF5EC8F2, 0xFF6A5CFF)
}

/**
 * The live TV shortcuts (and what each does), for the film and show players' controls: the same row, so the players
 * work alike. Set by the home screen.
 */
object LiveShortcutsBridge {
    private val state = androidx.compose.runtime.mutableStateOf<List<Pair<LiveShortcut, () -> Unit>>>(emptyList())
    var items: List<Pair<LiveShortcut, () -> Unit>>
        get() = state.value
        set(v) { state.value = v }
    /** The players' Settings button: the menu on Settings (set by the home screen). */
    var openSettings: () -> Unit = {}
    /** The film player's TV guide button: the guide (the film in the corner). */
    var openGuide: () -> Unit = {}
    /**
     * What holding OK on a shortcut offers (Move / Refresh / Hide), the same for live TV and the film player: both call
     * these, so a change from either is the one row everywhere (set by the home screen).
     */
    var hide: (LiveShortcut) -> Unit = {}
    var move: (LiveShortcut, Int) -> Unit = { _, _ -> }
    var refresh: (LiveShortcut) -> Unit = {}
    /** A shortcut's rows (as on live TV), else the default ones (Continue watching…). */
    var previewFor: (String) -> List<OsdRow>? = { null }
    var defaultRows: List<OsdRow> = emptyList()
    /** Hold OK → Choose rows: each shortcut's chosen rows by name, in order (set by the home screen). */
    private val chosenState = androidx.compose.runtime.mutableStateOf<Map<String, List<String>>>(emptyMap())
    var chosen: Map<String, List<String>>
        get() = chosenState.value
        set(v) { if (chosenState.value != v) chosenState.value = v }
    var setRows: (String, List<String>?) -> Unit = { _, _ -> }

    /** The film player's controls are up: the rows that load while live TV's are (Sports, Live now) load for it too. */
    private val filmMenuState = androidx.compose.runtime.mutableStateOf(false)
    var filmMenuOpen: Boolean
        get() = filmMenuState.value
        set(v) { if (filmMenuState.value != v) filmMenuState.value = v }

    /** Choose rows saved: shown straight away, then kept (null: back to its usual rows). */
    fun chooseRows(id: String, rows: List<String>?) {
        chosen = if (rows == null) chosen - id else chosen + (id to rows)
        setRows(id, rows)
    }

    /** Every row the shortcut [id] can show (Choose rows lists them), before any choice or limit. */
    fun allRowsFor(id: String): List<OsdRow> = (previewFor(id)?.takeIf { it.isNotEmpty() } ?: defaultRows).filter { it.items.isNotEmpty() }

    /**
     * The rows Down reaches under the shortcut at [i]: the ones chosen for it (hold OK → Choose rows), in that order,
     * else its first few (Settings → Rows under the shortcuts).
     */
    fun rowsFor(i: Int): List<OsdRow> = items.getOrNull(i)?.first?.id?.let(::rowsForId).orEmpty()

    /** [rowsFor] by the shortcut's id. */
    fun rowsForId(id: String): List<OsdRow> {
        val max = when (io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.OSD_ROWS)) { 1 -> 2; 2 -> 5; 3 -> Int.MAX_VALUE; else -> 3 }
        val all = allRowsFor(id)
        chosen[id]?.let { picked -> return picked.mapNotNull { t -> all.firstOrNull { it.title == t } } }
        // Sports: a row for every team and league you follow (they're its whole point, not a preview of a page).
        val cap = if (id == "page:Sports") maxOf(max, 12) else max
        return all.take(cap)
    }
}

/** Settings → Live TV shortcuts → Shortcut style. */
enum class ShortcutStyle { Round, Square, Wide }

fun shortcutStyle(): ShortcutStyle = ShortcutStyle.entries[io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.SHORTCUT_STYLE).coerceIn(0, 2)]

/** The shortcut's size (Settings → Shortcut size): width and height for its style, from a round one's [base]. */
fun shortcutBox(base: androidx.compose.ui.unit.Dp, style: ShortcutStyle = shortcutStyle()): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> =
    if (style == ShortcutStyle.Wide) (base * 1.7f) to base else base to base

fun shortcutShape(style: ShortcutStyle = shortcutStyle()): androidx.compose.ui.graphics.Shape = when (style) {
    ShortcutStyle.Round -> androidx.compose.foundation.shape.CircleShape
    ShortcutStyle.Square -> androidx.compose.foundation.shape.RoundedCornerShape(22)
    ShortcutStyle.Wide -> androidx.compose.foundation.shape.RoundedCornerShape(16)
}

/**
 * What a shortcut shows inside its shape: a service's app icon, a channel's logo on white, a wide wordmark (wide
 * tiles), or for Viewarr's own a coloured background with its icon (and, on a wide tile, its name).
 */
@androidx.compose.runtime.Composable
fun ShortcutFace(sc: LiveShortcut, style: ShortcutStyle, h: androidx.compose.ui.unit.Dp) {
    val logo = sc.logoName?.let { ShortcutLogos.map[it] }
    val wide = sc.logoName?.let { ShortcutLogos.wide[it] ?: logo?.takeIf { l -> !l.square } }
    val shape = shortcutShape(style)
    val fill = androidx.compose.ui.Modifier.fillMaxSize().clip(shape)
    val white = androidx.compose.ui.graphics.Color.White
    when {
        style == ShortcutStyle.Wide && wide != null -> androidx.compose.foundation.layout.Box(fill.background(white), contentAlignment = androidx.compose.ui.Alignment.Center) {
            coil.compose.AsyncImage(wide.url, sc.label, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(horizontal = h / 6, vertical = h / 7))
        }
        style == ShortcutStyle.Wide && logo != null && logo.square -> androidx.compose.foundation.layout.Box(fill.background(androidx.compose.ui.graphics.Color(0xFF1C2230)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            coil.compose.AsyncImage(logo.url, sc.label, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = androidx.compose.ui.Modifier.size(h * 0.72f).clip(androidx.compose.foundation.shape.RoundedCornerShape(22)))
        }
        logo != null && logo.square -> coil.compose.AsyncImage(logo.url, sc.label, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = fill)
        logo != null -> androidx.compose.foundation.layout.Box(fill.background(white), contentAlignment = androidx.compose.ui.Alignment.Center) {
            coil.compose.AsyncImage(logo.url, sc.label, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(h / 10))
        }
        sc.colors != null -> androidx.compose.foundation.layout.Box(fill.background(androidx.compose.ui.graphics.Brush.linearGradient(sc.colors.map { androidx.compose.ui.graphics.Color(it) })), contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (style == ShortcutStyle.Wide) androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Icon(sc.icon, null, tint = white, modifier = androidx.compose.ui.Modifier.size(h * 0.36f))
                androidx.tv.material3.Text(sc.label, color = white, maxLines = 1, fontSize = (h.value * 0.2f).sp,
                    modifier = androidx.compose.ui.Modifier.padding(start = h / 12))
            } else androidx.compose.material3.Icon(sc.icon, sc.label, tint = white, modifier = androidx.compose.ui.Modifier.size(h / 2))
        }
        sc.emoji != null -> androidx.tv.material3.Text(sc.emoji)
        else -> androidx.compose.foundation.layout.Box(fill, contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Icon(sc.icon, sc.label, tint = white, modifier = androidx.compose.ui.Modifier.size(h / 2))
        }
    }
}

/** A shortcut (in its style), for screens that track the highlight themselves (the film controls). */
@androidx.compose.runtime.Composable
fun ShortcutDisc(sc: LiveShortcut, highlighted: Boolean, size: androidx.compose.ui.unit.Dp) {
    val style = shortcutStyle()
    val (w, h) = shortcutBox(size, style)
    // The same gentle grow as live TV's highlighted shortcut.
    val grow = androidx.compose.animation.core.animateFloatAsState(if (highlighted) 1.2f else 1f, label = "shortcut").value
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.size(w, h)
            .then(androidx.compose.ui.Modifier.scale(grow))
            .background(if (highlighted) io.github.petebutty.viewarr.ui.theme.AppColors.Accent else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f), shortcutShape(style))
            .padding(if (highlighted) h / 20 else androidx.compose.ui.unit.Dp(0f)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { ShortcutFace(sc, style, h) }
}

/** Logos found for row-set shortcuts (TMDB), by network / service name. */
object ShortcutLogos {
    /** Wide wordmark logos (wide tiles), by the same names. */
    val wide = androidx.compose.runtime.mutableStateMapOf<String, io.github.petebutty.viewarr.data.media.ServiceLogo>()
    val askedWide: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    val map = androidx.compose.runtime.mutableStateMapOf<String, io.github.petebutty.viewarr.data.media.ServiceLogo>(
        // Not on TMDB: Viewarr's own icons in their colours (Plex's comes from TMDB).
        "Trakt" to res(io.github.petebutty.viewarr.R.drawable.logo_trakt),
        "Jellyfin" to res(io.github.petebutty.viewarr.R.drawable.logo_jellyfin),
        "Emby" to res(io.github.petebutty.viewarr.R.drawable.logo_emby),
        "Navidrome" to res(io.github.petebutty.viewarr.R.drawable.logo_navidrome),
    )
    private fun res(id: Int) = io.github.petebutty.viewarr.data.media.ServiceLogo("android.resource://io.github.petebutty.viewarr/$id", square = true)

    /** The logo name for a server or music source's name ("Plex · Living room" → "Plex"). */
    fun serverLogo(source: String): String? = listOf("Plex", "Jellyfin", "Emby", "Navidrome").firstOrNull { source.startsWith(it, ignoreCase = true) }
    val asked: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
}

/**
 * Sets of rows a shortcut can open on their own: an addon's rows named "<name> · <row>" (two or more with the same
 * name: AIOMetadata's "Netflix · Popular", "Netflix · New"… make a "Netflix" shortcut), and your Plex rows.
 * Keys: "name:Netflix", "plex".
 */
object RowSets {
    private const val SEP = " · "
    /** The set name an addon row belongs to ("Netflix" for "Netflix · Popular"), or null. */
    fun name(row: MediaRow): String? =
        if (!row.id.startsWith("stremio-")) null else row.title.substringBefore(SEP, "").trim().takeIf { it.isNotEmpty() }
    fun matches(set: String, row: MediaRow): Boolean = when {
        set == "plex" -> row.id.startsWith("plex-")
        set.startsWith("name:") -> name(row) == set.removePrefix("name:")
        else -> false
    }
    /** A row's own name on its set's page ("Popular"). */
    fun rowTitle(row: MediaRow): String = if (name(row) != null) row.title.substringAfter(SEP) else row.title
    fun sets(rows: List<MediaRow>): List<String> = buildList {
        if (rows.any { it.id.startsWith("plex-") }) add("plex")
        rows.mapNotNull { name(it) }.groupingBy { it }.eachCount().filter { it.value >= 2 }.keys.forEach { add("name:$it") }
    }
}

/**
 * The shortcuts under the live TV controls (OK, then Down): ids saved in RemoteSettings.liveShortcuts in order,
 * "page:Guide", "home:Movie", "lib:Movie:<library key>", "row:Show:<row id>", "page:Settings"… Unset: [defaults].
 */
object LiveShortcuts {
    val defaults = listOf("page:Guide", "page:Live", "page:Sports", "home:Movie", "home:Show", "page:Music", "page:Recordings", "page:Search", "page:Settings")

    /** Everything that can be a shortcut, in the order offered, each with a familiar icon. */
    fun candidates(
        movieLibs: List<MediaLibrary>, showLibs: List<MediaLibrary>,
        movieRows: List<MediaRow>, showRows: List<MediaRow>, musicOn: Boolean,
        /** The music service in use ("Navidrome", "Plex · …"), for the music shortcuts' logo. */
        musicSource: String? = null,
    ): List<LiveShortcut> = buildList {
        add(LiveShortcut("page:Guide", "TV guide", AppIcons.Guide, colors = ShortcutColours.guide))
        // Live now: what's on the event / sports channels (Event Channel Managarr's groups), with what's starting soon.
        add(LiveShortcut("page:Live", "Live now", AppIcons.LiveTv, colors = ShortcutColours.live))
        // Sports: your teams and leagues (Settings → Sports), a row of games each under it (Down); OK: the guide.
        add(LiveShortcut("page:Sports", "Sports", AppIcons.LiveTv, emoji = "🏆", colors = ShortcutColours.sports))
        add(LiveShortcut("home:Movie", "Movies", AppIcons.Movie, colors = ShortcutColours.movies))
        add(LiveShortcut("home:Show", "Shows", AppIcons.Tv, colors = ShortcutColours.shows))
        if (musicOn) add(LiveShortcut("page:Music", "Music", AppIcons.MusicNote, colors = ShortcutColours.music))
        // Straight into your music: Albums, Artists, Playlists… with your music service's logo.
        if (musicOn) {
            val logo = musicSource?.let { ShortcutLogos.serverLogo(it) }
            listOf("Albums" to "Albums", "Artists" to "Artists", "Playlists" to "Playlists", "Newest" to "New music", "Favourites" to "Favourite music").forEach { (kind, label) ->
                add(LiveShortcut("music:$kind", label, AppIcons.MusicNote, logoName = logo, colors = ShortcutColours.music))
            }
        }
        add(LiveShortcut("page:Recordings", "Recordings", AppIcons.Record, colors = ShortcutColours.recordings))
        add(LiveShortcut("page:Search", "Search", AppIcons.Search, colors = ShortcutColours.search))
        add(LiveShortcut("page:Settings", "Settings", AppIcons.Settings, colors = ShortcutColours.settings))
        val sources = (movieLibs + showLibs).map { it.source }.distinct().size > 1
        // Plex libraries and rows carry Plex's logo (looked up like the networks').
        // Libraries carry their server's logo: Plex, Jellyfin or Emby.
        fun plexLogo(l: MediaLibrary) = ShortcutLogos.serverLogo(l.source)
        movieLibs.forEach { add(LiveShortcut("lib:Movie:${it.key}", libName(it, sources), AppIcons.Library, logoName = plexLogo(it), colors = ShortcutColours.library)) }
        showLibs.forEach { add(LiveShortcut("lib:Show:${it.key}", libName(it, sources), AppIcons.Library, logoName = plexLogo(it), colors = ShortcutColours.library)) }
        // Row sets: one shortcut per network or service (AIOMetadata's "Netflix · …" rows), opening its show rows and
        // then its film rows on one page; a network with only films opens on Movies. Plex: one per page.
        val showSets = RowSets.sets(showRows); val movieSets = RowSets.sets(movieRows)
        (showSets + movieSets).filter { it.startsWith("name:") }.distinct().forEach { key ->
            val t = if (key in showSets) "Show" else "Movie"
            add(LiveShortcut("set:$t:$key", key.removePrefix("name:"), if (t == "Show") AppIcons.Tv else AppIcons.Movie, logoName = key.removePrefix("name:")))
        }
        if ("plex" in showSets) add(LiveShortcut("set:Show:plex", "Plex (shows)", AppIcons.Library, logoName = "Plex"))
        if ("plex" in movieSets) add(LiveShortcut("set:Movie:plex", "Plex (films)", AppIcons.Library, logoName = "Plex"))
        fun rowLogo(r: MediaRow) = when {
            r.id.startsWith("plex-") || r.id.startsWith("merged:") -> "Plex"
            r.id.startsWith("trakt-") -> "Trakt"
            r.id.startsWith("Jellyfin-") -> "Jellyfin"
            r.id.startsWith("Emby-") -> "Emby"
            else -> null
        }
        // A network's own rows ("Netflix · Popular"…) aren't offered one by one: its shortcut opens them all.
        val grouped = (RowSets.sets(movieRows) + RowSets.sets(showRows)).filter { it.startsWith("name:") }.map { it.removePrefix("name:") }.toSet()
        fun inSet(r: MediaRow) = RowSets.name(r)?.let { it in grouped } == true
        movieRows.filterNot(::inSet).forEach { add(LiveShortcut("row:Movie:${it.id}", "${it.title} (films)", AppIcons.Layers, logoName = rowLogo(it), colors = ShortcutColours.row)) }
        showRows.filterNot(::inSet).forEach { add(LiveShortcut("row:Show:${it.id}", "${it.title} (shows)", AppIcons.Layers, logoName = rowLogo(it), colors = ShortcutColours.row)) }
    }

    /**
     * The shortcut a saved id stands for now: itself, or (ids saved by older versions) a network's films-only
     * shortcut or one of a network's own rows, which are now its one shortcut ("set:Show:name:Netflix").
     */
    fun resolve(id: String, all: Map<String, LiveShortcut>, rows: List<MediaRow>): LiveShortcut? {
        all[id]?.let { return it }
        all[id.replace("set:Movie:name:", "set:Show:name:")]?.let { return it }
        if (id.startsWith("row:")) {
            val rowId = id.split(":", limit = 3).getOrNull(2) ?: return null
            val name = rows.firstOrNull { it.id == rowId }?.let { RowSets.name(it) } ?: return null
            return all["set:Show:name:$name"] ?: all["set:Movie:name:$name"]
        }
        return null
    }

    private fun libName(l: MediaLibrary, sources: Boolean) = if (sources) "${l.title} · ${l.source.removePrefix("Plex · ")}" else l.title
}
