package io.github.petebutty.viewarr.data.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Sorting and filtering a library (Plex, Jellyfin, Emby, IPTV and, with its own options, Music). Each server says
 * what it can sort and filter by; the choices are remembered per library, under a profile ("default" until Viewarr
 * has profiles), so each person can have their own later.
 */

/** A way a library can be sorted. [descFirst]: the natural direction is newest / highest first. */
@Serializable
data class SortOption(val id: String, val label: String, val descFirst: Boolean = true)

/** One choice inside a filter ("Comedy", "4K", "2010s"). */
@Serializable
data class FilterValue(val id: String, val label: String)

/**
 * Something a library can be filtered by. [toggle]: on or off (Unwatched, HDR); otherwise pick from its values
 * (several at once when [multi]: any of them matches).
 */
data class FilterGroup(val id: String, val label: String, val toggle: Boolean = false, val multi: Boolean = true)

/** What one library offers. [letters]: it can jump to a letter when sorted by title. */
data class LibraryOptions(
    val sorts: List<SortOption>,
    val filters: List<FilterGroup>,
    val letters: Boolean,
    /** The sort that means "by title" (the A–Z strip shows with it). */
    val titleSort: String,
    /** Each sort can be turned round (IPTV lists come one way only). */
    val directions: Boolean = true,
)

/** How a library is being looked at: sort, direction, filters and search. */
@Serializable
data class LibraryQuery(
    val sort: String,
    val desc: Boolean,
    val filters: Map<String, List<FilterValue>> = emptyMap(),
    val toggles: Set<String> = emptySet(),
    val query: String = "",
) {
    /** How many filters are on (for the Filter button's badge). */
    val active: Int get() = filters.values.sumOf { it.size } + toggles.size
    val filtered: Boolean get() = active > 0 || query.isNotBlank()
}

/** Each library's sort and filters, per profile, kept across restarts. */
@Singleton
class LibraryViews @Inject constructor(@ApplicationContext context: Context, private val json: Json) {
    private val prefs = context.getSharedPreferences("library_views", Context.MODE_PRIVATE)
    /** Whose choices these are. One profile for now; profiles can switch this later. */
    var profile: String = "default"

    private fun key(library: String) = "$profile/$library"

    fun load(library: String): LibraryQuery? =
        prefs.getString(key(library), null)?.let { runCatching { json.decodeFromString<LibraryQuery>(it) }.getOrNull() }

    /** Search isn't kept: a library opens showing everything that matches its filters. */
    fun save(library: String, q: LibraryQuery) {
        prefs.edit().putString(key(library), json.encodeToString(LibraryQuery.serializer(), q.copy(query = ""))).apply()
    }
}
