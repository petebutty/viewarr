package io.github.petebutty.viewarr.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.player.RowSets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Curation (2026-11-23): the menu's Trending and What's new pages, made from rows the Movies and Shows pages
 * already load (films and shows together, on the Movies page like My Library), and the Movies / Shows pages' main
 * rows only (8 each: the rest are on Trending, What's new, the services' pages and My Library).
 */
object Discover {
    const val TRENDING = "trending"
    const val NEW = "whatsnew"
    /** The main menu's entries (SideMenu's live entries), by name. */
    const val TRENDING_ENTRY = "Trending"
    const val NEW_ENTRY = "What's new"
    fun isSet(set: String?) = set == TRENDING || set == NEW
    fun setOf(entry: String) = when (entry) { TRENDING_ENTRY -> TRENDING; NEW_ENTRY -> NEW; else -> null }
    fun entryOf(set: String?) = when (set) { TRENDING -> TRENDING_ENTRY; NEW -> NEW_ENTRY; else -> null }

    /** Every row of both pages as loaded (MediaViewModel publishes them). */
    private val _rows = MutableStateFlow<Map<MediaType, List<MediaRow>>>(emptyMap())
    val rows = _rows.asStateFlow()
    fun publish(type: MediaType, list: List<MediaRow>) { if (_rows.value[type] != list) _rows.update { it + (type to list) } }

    // ---- What each row is (by the ids and names Viewarr gives its own rows) ----
    private fun isService(r: MediaRow) = RowSets.name(r) != null
    private fun isJustAdded(r: MediaRow) = r.id.startsWith("justadded-")
    private fun isRecent(r: MediaRow) = !r.id.startsWith("stremio-") && !isJustAdded(r) && r.title.startsWith("Recently added")
    private fun isDeck(r: MediaRow) = r.group == "deck" || r.title.startsWith("Continue watching")
    private fun isLatest(r: MediaRow) = r.id.startsWith("stremio-") && !isService(r) && Regex("(?i)\\blatest\\b").containsMatchIn(r.title)
    private fun isMdbList(r: MediaRow) = r.id.startsWith("stremio-") && r.title.contains("MDBList", ignoreCase = true)
    /** Trending-page rows (kept off Movies / Shows). */
    private fun isTrending(r: MediaRow) = r.id.startsWith("tmdb-trending-") || r.id.startsWith("tmdb-popular-") ||
        r.id.startsWith("tmdb-top-") || isMdbList(r)
    /** Libraries that aren't films or shows to watch (a Plex "Pre-rolls" library, adult). */
    private val NOT_TITLES = Regex("(?i)pre[ .-]?rolls?|trailers?|\\bxxx\\b|adult")

    /** Several rows as one: their titles taken in turn (newest of each first), each title once. */
    private fun interleave(id: String, title: String, rows: List<MediaRow>): MediaRow? {
        if (rows.isEmpty()) return null
        val seen = HashSet<String>(); val out = ArrayList<MediaItem>()
        val longest = rows.maxOf { it.items.size }
        for (i in 0 until longest) for (r in rows) {
            val item = r.items.getOrNull(i) ?: continue
            if (seen.add(itemKey(item))) out += item
        }
        return MediaRow(id, title, out).takeIf { out.isNotEmpty() }
    }
    private fun itemKey(i: MediaItem) = i.tmdbId?.let { "${i.type}:$it" } ?: i.imdbId ?: i.key

    /**
     * The Movies / Shows page's main rows: Continue watching, Recently added (every library as one), your watchlist,
     * Recommended, In cinemas / On TV this week, new IPTV, an addon's Latest, your collections; filled up to 8 with any
     * other row that isn't on another page. [rows]: the page's rows, in its order, the ones switched off already out.
     */
    fun homeRows(type: MediaType, rows: List<MediaRow>): List<MediaRow> {
        val t = type.name
        val recent = interleave("disc-recent-$t", "Recently added",
            rows.filter { isRecent(it) && !NOT_TITLES.containsMatchIn(it.title) })
        fun id(x: String) = rows.firstOrNull { it.id == x }
        val picks = if (type == MediaType.Movie) listOf(
            rows.firstOrNull(::isDeck), recent, id("trakt-watchlist-$t"), id("trakt-reco-$t"), id("tmdb-cinema-$t"),
            id("xc-new-$t"), rows.firstOrNull(::isLatest), id("groups-col-$t"),
        ) else listOf(
            rows.firstOrNull(::isDeck), id("upcoming-$t"), recent, id("trakt-watchlist-$t"), id("trakt-reco-$t"),
            id("tmdb-airing-$t"), id("xc-new-$t"), rows.firstOrNull(::isLatest),
        )
        val chosen = picks.filterNotNull().toMutableList()
        if (chosen.size < 8) rows.filter { r ->
            r !in chosen && !isDeck(r) && !isRecent(r) && !isService(r) && !isTrending(r) && !isJustAdded(r) &&
                !r.id.startsWith("stremio-") && !r.id.startsWith("xc-top-") && !NOT_TITLES.containsMatchIn(r.title)
        }.take(8 - chosen.size).let { chosen += it }
        return chosen.take(8)
    }

    /** A page's rows: films and shows together. */
    fun pageRows(set: String, all: Map<MediaType, List<MediaRow>>): List<MediaRow> {
        val m = all[MediaType.Movie].orEmpty(); val s = all[MediaType.Show].orEmpty()
        fun pair(id: String, title: String, f: (MediaRow) -> Boolean) = interleave(id, title, m.filter(f) + s.filter(f))
        fun one(list: List<MediaRow>, id: String, title: String) = list.firstOrNull { it.id == id }?.copy(id = "disc-$id", title = title)
        return if (set == TRENDING) {
            // Each title once on the page: a title in a row higher up isn't repeated lower down.
            val rows = listOfNotNull(
                // (The first 20 only: all of them would leave Trending films / shows below with nothing of their own.)
                pair("disc-trend", "Trending this week") { it.id.startsWith("tmdb-trending-") }?.let { it.copy(items = it.items.take(20)) },
                one(m, "tmdb-trending-Movie", "Trending films"), one(s, "tmdb-trending-Show", "Trending shows"),
                pair("disc-popular", "Popular right now") { it.id.startsWith("tmdb-popular-") },
                pair("disc-mdb", "Most watched") { isMdbList(it) },
                pair("disc-top", "Top rated") { it.id.startsWith("tmdb-top-") },
                one(m, "tmdb-cinema-Movie", "In cinemas"), one(s, "tmdb-airing-Show", "On TV this week"),
            )
            val seen = HashSet<String>()
            rows.mapNotNull { r -> r.copy(items = r.items.filter { seen.add(itemKey(it)) }).takeIf { it.items.size >= 5 } }
        } else {
            // Each service's Just added (films and shows as one), in the plugin's order; then your own and the rest.
            val services = (m + s).filter(::isJustAdded).groupBy { RowSets.name(it) ?: it.title }
                .mapNotNull { (name, rows) -> interleave("disc-new-$name", "New on $name", rows) }
            services + listOfNotNull(
                pair("disc-new-lib", "Recently added to your library") { isRecent(it) && !NOT_TITLES.containsMatchIn(it.title) },
                one(s, "upcoming-Show", "Upcoming episodes"),
                pair("disc-new-iptv", "New on IPTV") { it.id.startsWith("xc-new-") },
                pair("disc-new-latest", "Latest from your addons") { isLatest(it) },
            )
        }
    }

}

/**
 * Trending's first row turning by itself (2026-11-24: no swiping banner with dots; the row itself carousels):
 * while the highlight isn't on the page (e.g. still in the menu), the row moves on one poster every few seconds and
 * the header shows that poster's title. Going into the rows ends it: the row goes back to its start and the header
 * follows the highlight as usual. Returns the title the header shows (null: the carousel isn't running).
 */
@Composable
fun RowCarousel(on: Boolean, row: MediaRow?, state: androidx.compose.foundation.lazy.LazyListState?): MediaItem? {
    var index by remember { mutableIntStateOf(-1) }
    val items = row?.items.orEmpty()
    LaunchedEffect(on, row?.id, items.size, state) {
        if (!on || state == null || items.size < 2) {
            if (index > 0) runCatching { state?.scrollToItem(0) }
            index = -1
            return@LaunchedEffect
        }
        index = 0
        while (true) {
            delay(5_000)
            index = (index + 1) % items.size
            runCatching { if (index == 0) state.scrollToItem(0) else state.animateScrollToItem(index) }
        }
    }
    return if (on) items.getOrNull(index) else null
}
