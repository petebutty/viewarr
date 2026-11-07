package io.github.petebutty.viewarr.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.data.media.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.rowLayoutStore by preferencesDataStore(name = "row_layout")

/** Settings → Home rows: which rows the Films / Shows pages show, and in what order. */
data class RowLayout(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** Rows left out of the menu's Catalogues list (Movies / Shows → Right); separate from [hidden] (the page). */
    val menuHidden: Set<String> = emptySet(),
) {
    /**
     * [rows] in the chosen order. A row never placed (new since the order was saved, e.g. a new feature's row) goes
     * right after the row it follows by default, rather than to the bottom. Hidden ones are kept.
     */
    fun ordered(rows: List<MediaRow>): List<MediaRow> {
        val pos = order.withIndex().associate { it.value to it.index }
        val result = rows.filter { it.id in pos }.sortedBy { pos[it.id] }.toMutableList()
        rows.forEachIndexed { i, row ->
            if (row.id in pos) return@forEachIndexed
            val before = (i - 1 downTo 0).map { rows[it] }.firstOrNull { it in result }
            result.add(if (before == null) 0 else result.indexOf(before) + 1, row)
        }
        return result
    }

    fun visible(rows: List<MediaRow>) = ordered(rows).filter { it.id !in hidden }

    /** Any list in this order (by [id]); ones never placed keep their own order after the placed ones. */
    fun <T> orderedBy(list: List<T>, id: (T) -> String): List<T> {
        val pos = order.withIndex().associate { it.value to it.index }
        return list.withIndex().sortedWith(compareBy({ pos[id(it.value)] ?: (order.size + it.index) }, { it.index })).map { it.value }
    }
}

@Singleton
class RowLayoutStore @Inject constructor(@ApplicationContext private val context: Context) {

    fun layout(type: MediaType): Flow<RowLayout> = context.rowLayoutStore.data.map { p ->
        RowLayout(
            order = p[orderKey(type)]?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty(),
            hidden = p[hiddenKey(type)].orEmpty(),
            menuHidden = p[menuHiddenKey(type)].orEmpty(),
        )
    }

    suspend fun setMenuHidden(type: MediaType, id: String, hidden: Boolean) {
        context.rowLayoutStore.edit { p ->
            val now = p[menuHiddenKey(type)].orEmpty()
            p[menuHiddenKey(type)] = if (hidden) now + id else now - id
        }
    }

    suspend fun setHidden(type: MediaType, id: String, hidden: Boolean) {
        context.rowLayoutStore.edit { p ->
            val now = p[hiddenKey(type)].orEmpty()
            p[hiddenKey(type)] = if (hidden) now + id else now - id
        }
    }

    suspend fun setOrder(type: MediaType, ids: List<String>) {
        // Rows that didn't load this time (a server offline) keep their place at the end rather than being forgotten.
        context.rowLayoutStore.edit { p ->
            val before = p[orderKey(type)]?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty()
            p[orderKey(type)] = (ids + before.filter { it !in ids }).joinToString(SEP)
        }
    }

    /** The menu's library list: order and hidden ones (Settings → Home rows → Libraries). */
    fun libraryLayout(type: MediaType): Flow<RowLayout> = context.rowLayoutStore.data.map { p ->
        RowLayout(
            order = p[libOrderKey(type)]?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty(),
            hidden = p[libHiddenKey(type)].orEmpty(),
        )
    }

    /** Combine rows that repeat across servers (continue watching, a library's recently added). On by default. */
    val mergeRows: Flow<Boolean> = context.rowLayoutStore.data.map { it[MERGE_ROWS] ?: true }

    suspend fun setMergeRows(on: Boolean) { context.rowLayoutStore.edit { it[MERGE_ROWS] = on } }

    /** Your own names for libraries (Settings → Home page & menu → Menu list → hold OK), by library key. */
    fun libraryNames(type: MediaType): Flow<Map<String, String>> = context.rowLayoutStore.data.map { p ->
        p[libNamesKey(type)].orEmpty().split(REC).mapNotNull { r -> r.split(SEP).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    suspend fun setLibraryName(type: MediaType, key: String, name: String?) {
        context.rowLayoutStore.edit { p ->
            val now = p[libNamesKey(type)].orEmpty().split(REC).mapNotNull { r -> r.split(SEP).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val next = if (name.isNullOrBlank()) now - key else now + (key to name.trim())
            p[libNamesKey(type)] = next.entries.joinToString(REC) { it.key + SEP + it.value }
        }
    }

    suspend fun setLibraryHidden(type: MediaType, key: String, hidden: Boolean) {
        context.rowLayoutStore.edit { p ->
            val now = p[libHiddenKey(type)].orEmpty()
            p[libHiddenKey(type)] = if (hidden) now + key else now - key
        }
    }

    suspend fun setLibraryOrder(type: MediaType, keys: List<String>) {
        context.rowLayoutStore.edit { p ->
            val before = p[libOrderKey(type)]?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty()
            p[libOrderKey(type)] = (keys + before.filter { it !in keys }).joinToString(SEP)
        }
    }

    suspend fun reset(type: MediaType) {
        context.rowLayoutStore.edit { it.remove(orderKey(type)); it.remove(hiddenKey(type)); it.remove(libOrderKey(type)); it.remove(libHiddenKey(type)); it.remove(menuHiddenKey(type)) }
    // (Library names are kept on reset: they're names, not layout.)
    }

    private companion object {
        const val SEP = "\u001F"
        const val REC = "\u001E"
        fun orderKey(t: MediaType) = stringPreferencesKey("order_${t.name}")
        fun hiddenKey(t: MediaType) = stringSetPreferencesKey("hidden_${t.name}")
        fun libOrderKey(t: MediaType) = stringPreferencesKey("lib_order_${t.name}")
        fun libHiddenKey(t: MediaType) = stringSetPreferencesKey("lib_hidden_${t.name}")
        fun menuHiddenKey(t: MediaType) = stringSetPreferencesKey("menu_hidden_${t.name}")
        fun libNamesKey(t: MediaType) = stringPreferencesKey("lib_names_${t.name}")
        val MERGE_ROWS = booleanPreferencesKey("merge_rows")
    }
}
