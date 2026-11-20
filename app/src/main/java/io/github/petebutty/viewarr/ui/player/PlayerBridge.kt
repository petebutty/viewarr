package io.github.petebutty.viewarr.ui.player

import io.github.petebutty.viewarr.data.media.MediaRow

/** A card in the live TV controls' poster rows: a title (picture landscape), or a channel ([logo] on a plain tile). */
data class OsdPoster(
    val key: String,
    val title: String,
    /** The caption centred under the picture (the Episodes row: S·E and its date). */
    val centred: Boolean = false,
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
    /** Not out yet (an episode that hasn't aired): the picture drawn darker, with "Not out yet" on it. */
    val unreleased: Boolean = false,
    /** A third line under the card (an episode's release date). */
    val note: String? = null,
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

/** A sport's card colours (a game with no crest; SportsHub). */
object SportColours {
    val sports = listOf(0xFF34C759, 0xFF0B6E3A)
}

/**
 * What the film and show players' buttons do (Settings, the TV guide, Movies / Shows / Recordings, minimise), and
 * their rows under the info bar. Set by the home screen.
 */
object PlayerBridge {
    /** The players' Settings button: the menu on Settings (set by the home screen). */
    var openSettings: () -> Unit = {}
    /** The film player's TV guide button: the guide (the film in the corner). */
    var openGuide: () -> Unit = {}
    /** A film minimised: the menu, on Live TV (main list), with the TV guide behind it. */
    var openMenuOnLiveTv: () -> Unit = {}
    /** The film player's Movies / Shows / Recordings buttons ("Movies", "Shows", "Recordings"): that page. */
    var openPage: (String) -> Unit = {}
    /**
     * The film players' rows under the info bar (Settings → Rows under the info bar), as (row kind's id, row), in order;
     * built by the home screen. "next" (the episodes after this one) isn't here: the player adds its own in its place.
     */
    private val filmRowsState = androidx.compose.runtime.mutableStateOf<List<Pair<String, OsdRow>>>(emptyList())
    var filmRows: List<Pair<String, OsdRow>>
        get() = filmRowsState.value
        set(v) { if (filmRowsState.value != v) filmRowsState.value = v }

    /** The film player's controls are up: the rows that load while live TV's are (Sports, Live now) load for it too. */
    private val filmMenuState = androidx.compose.runtime.mutableStateOf(false)
    var filmMenuOpen: Boolean
        get() = filmMenuState.value
        set(v) { if (filmMenuState.value != v) filmMenuState.value = v }
}

/**
 * Sets of rows that open as an entry of their own (the menu's services): an addon's rows named "<name> · <row>" (two or
 * more with the same name: AIOMetadata's "Netflix · Popular", "Netflix · New"… make a "Netflix" entry), and your Plex rows.
 * Keys: "name:Netflix", "plex".
 */
object RowSets {
    private const val SEP = " · "
    /** The set name an addon row belongs to ("Netflix" for "Netflix · Popular"), or null. */
    fun name(row: MediaRow): String? =
        // (The Viewarr plugin's "Netflix · Just added" rows belong to their service too: they weren't on its page.)
        if (!row.id.startsWith("stremio-") && !row.id.startsWith("justadded-")) null else row.title.substringBefore(SEP, "").trim().takeIf { it.isNotEmpty() }
    fun matches(set: String, row: MediaRow): Boolean = when {
        set == "plex" -> row.id.startsWith("plex-")
        set.startsWith("name:") -> name(row) == set.removePrefix("name:")
        else -> false
    }
    /** A row's own name on its set's page ("Popular"). */
    fun rowTitle(row: MediaRow): String = if (name(row) != null) row.title.substringAfter(SEP) else row.title
    /**
     * A service's rows always in one order, films and shows together: Just added first (what really arrived lately,
     * from the Viewarr plugin: the rest change slowly; Peter, 2026-11-20), then New (shows, then films), Originals,
     * Popular, Top rated, then anything else.
     */
    fun kindRank(row: MediaRow): Int = when (rowTitle(row).trim().lowercase()) {
        "just added" -> 0; "new" -> 1; "originals" -> 2; "popular" -> 3; "top rated" -> 4; else -> 5
    }
    fun sets(rows: List<MediaRow>): List<String> = buildList {
        if (rows.any { it.id.startsWith("plex-") }) add("plex")
        rows.mapNotNull { name(it) }.groupingBy { it }.eachCount().filter { it.value >= 2 }.keys.forEach { add("name:$it") }
    }
}
