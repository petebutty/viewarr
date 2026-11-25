package io.github.petebutty.viewarr.ui.launcher

import android.content.Context

/**
 * The home screen's tiles that are hidden (hold OK on a tile → Hide, or Settings → Home screen tiles), and every tile
 * the home screen has had (for that Settings page, which can't work them out itself), kept on this box.
 */
internal object HomeTiles {
    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun hidden(c: Context): Set<String> = prefs(c).getStringSet("hidden_tiles", emptySet()).orEmpty()

    /** Hides or shows [icon]; the new hidden set. */
    fun setHidden(c: Context, icon: String, hide: Boolean): Set<String> {
        val now = hidden(c).toMutableSet().apply { if (hide) add(icon) else remove(icon) }
        prefs(c).edit().putStringSet("hidden_tiles", now).apply()
        return now
    }

    /** Every tile the home screen showed (or could show) last: icon and name, in its order. */
    fun known(c: Context): List<Pair<String, String>> =
        prefs(c).getString("known_tiles", "").orEmpty().split('\n').mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }

    fun remember(c: Context, tiles: List<Pair<String, String>>) {
        val text = tiles.joinToString("\n") { "${it.first}\t${it.second}" }
        if (prefs(c).getString("known_tiles", "") != text) prefs(c).edit().putString("known_tiles", text).apply()
    }
}
