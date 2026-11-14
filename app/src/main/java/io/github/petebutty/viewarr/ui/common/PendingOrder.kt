package io.github.petebutty.viewarr.ui.common

/**
 * An order just changed (moving an entry in a list), before it's saved and read back: the lists use it at once.
 * Moves are saved in the background, and the list only redrew once that came back, so a second press in the
 * meantime worked from the old order and the entry flicked back and forth ("ghosting" while moving). Kept for a few
 * seconds, by then the saved order says the same.
 */
object PendingOrder {
    private val map = androidx.compose.runtime.mutableStateMapOf<String, Pair<List<String>, Long>>()

    /** The just-made order for [key] if there is one (a few seconds old at most), else [saved]. */
    fun get(key: String, saved: List<String>): List<String> {
        val (ids, at) = map[key] ?: return saved
        return if (System.currentTimeMillis() - at < 4_000) ids else saved
    }

    fun set(key: String, ids: List<String>) { map[key] = ids to System.currentTimeMillis() }
}
