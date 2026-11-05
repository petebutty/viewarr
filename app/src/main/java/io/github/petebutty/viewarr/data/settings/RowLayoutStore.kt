package io.github.petebutty.viewarr.data.settings

import android.content.Context
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

private val Context.rowLayoutStore by preferencesDataStore(name = "row_layout")

/** Settings → Home rows: which rows the Films / Shows pages show, and in what order. */
data class RowLayout(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet()) {
    /** [rows] in the chosen order (rows never placed keep their own order, after the placed ones), hidden ones kept. */
    fun ordered(rows: List<MediaRow>): List<MediaRow> {
        val pos = order.withIndex().associate { it.value to it.index }
        return rows.withIndex()
            .sortedWith(compareBy({ pos[it.value.id] ?: (order.size + it.index) }, { it.index }))
            .map { it.value }
    }

    fun visible(rows: List<MediaRow>) = ordered(rows).filter { it.id !in hidden }
}

@Singleton
class RowLayoutStore @Inject constructor(@ApplicationContext private val context: Context) {

    fun layout(type: MediaType): Flow<RowLayout> = context.rowLayoutStore.data.map { p ->
        RowLayout(
            order = p[orderKey(type)]?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty(),
            hidden = p[hiddenKey(type)].orEmpty(),
        )
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

    suspend fun reset(type: MediaType) {
        context.rowLayoutStore.edit { it.remove(orderKey(type)); it.remove(hiddenKey(type)) }
    }

    private companion object {
        const val SEP = "\u001F"
        fun orderKey(t: MediaType) = stringPreferencesKey("order_${t.name}")
        fun hiddenKey(t: MediaType) = stringSetPreferencesKey("hidden_${t.name}")
    }
}
