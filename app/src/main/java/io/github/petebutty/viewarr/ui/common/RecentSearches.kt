package io.github.petebutty.viewarr.ui.common

import android.content.Context
import androidx.compose.runtime.mutableStateListOf

/** The last few searches (newest first), for the menu's Search panel. Kept on this TV only. */
object RecentSearches {
    private const val MAX = 6
    val items = mutableStateListOf<String>()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = context.getSharedPreferences("search", Context.MODE_PRIVATE).getString("recent", "").orEmpty()
        items.clear(); items.addAll(saved.split('\n').filter { it.isNotBlank() }.take(MAX))
    }

    fun add(context: Context, query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.length < 2) return
        load(context)
        items.removeAll { it.equals(q, ignoreCase = true) }
        items.add(0, q)
        while (items.size > MAX) items.removeAt(items.lastIndex)
        save(context)
    }

    fun clear(context: Context) { items.clear(); save(context) }

    private fun save(context: Context) =
        context.getSharedPreferences("search", Context.MODE_PRIVATE).edit().putString("recent", items.joinToString("\n")).apply()
}
