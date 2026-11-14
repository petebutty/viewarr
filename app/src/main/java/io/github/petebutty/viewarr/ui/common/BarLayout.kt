package io.github.petebutty.viewarr.ui.common

import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * The info bars' buttons as you've arranged them: ONE layout shared by live TV's controls and the film / episode
 * controls, so the two stay the same. Each bar shows the buttons it has, in that order: live TV's extras (Aspect,
 * Source, Record, Multi-view) and the film's own (Play / Pause, Details, Switch player) keep their places in it.
 * Edited in either bar (hold OK on a button: OK shows / hides, hold OK picks it up and Left / Right move it). Kept
 * with the appearance settings: the order as ids, hidden ones as "bar:all:<id>".
 */
object BarLayout {
    private const val BAR = "all"

    /** Every button either bar can have, in the order they come without changes. */
    val defaultOrder = listOf(
        "guide", "playpause", "stop", "details", "audio", "subtitles", "aspect", "source", "info", "switch",
        "record", "multiview", "favourite", "lastchannel", "prevepisode", "nextepisode", "episodes", "sync", "sleep", "movies", "shows", "recordings",
        "search", "transparency", "barstyle", "tvsettings", "settings",
    )

    /** Extra buttons: not in the bars until added (in edit mode they show dimmed, "not added"; OK adds one). */
    val extras = setOf("favourite", "lastchannel", "prevepisode", "nextepisode", "episodes", "sync", "sleep", "movies", "shows", "recordings", "tvsettings", "barstyle")

    /** Always there (so a bar can't be emptied, and its editor is always reachable). */
    val fixed = setOf("guide", "playpause")

    fun shown(id: String) = when (id) {
        in fixed -> true
        // (An extra's mark is kept in the same "switched off" list, meaning the opposite: added.)
        in extras -> !AppColors.palette.shows("bar:$BAR:+$id")
        else -> AppColors.palette.shows("bar:$BAR:$id")
    }

    /** Shows / hides (adds / removes, for an extra) [id] in both bars; [setPart] is the appearance store's setTitlePart. */
    fun setShown(id: String, show: Boolean, setPart: (String, Boolean) -> Unit) {
        if (id in fixed) return
        if (id in extras) setPart("bar:$BAR:+$id", !show) else setPart("bar:$BAR:$id", show)
    }

    private fun order(): List<String> {
        val saved = PendingOrder.get("bar", AppColors.palette.barOrder[BAR].orEmpty())
        return saved + defaultOrder.filter { it !in saved }
    }

    /** [all] (one bar's buttons) in the shared order; hidden ones left out unless [editing]. */
    fun <T> arrange(all: List<T>, id: (T) -> String, editing: Boolean): List<T> {
        val o = order()
        val sorted = all.withIndex().sortedBy { (i, t) -> o.indexOf(id(t)).let { if (it < 0) o.size + i else it } }.map { it.value }
        return if (editing) sorted else sorted.filter { shown(id(it)) }
    }

    /**
     * The shared order with [id] moved past its neighbour in this bar ([ids]: the bar's buttons as arranged; [dir] -1
     * left, 1 right). Buttons the other bar has in between keep their places. Null at an end.
     */
    fun moved(ids: List<String>, id: String, dir: Int): List<String>? {
        val i = ids.indexOf(id); val j = i + dir
        if (i < 0 || j !in ids.indices) return null
        val full = (order() + ids.filter { it !in order() }).toMutableList()
        val a = full.indexOf(id); val b = full.indexOf(ids[j])
        java.util.Collections.swap(full, a, b)
        PendingOrder.set("bar", full)
        return full
    }

    /** Info bar style button: the next style (each press shows it straight away), and its label. */
    fun nextStyle(set: (String, Int) -> Unit) {
        val n = io.github.petebutty.viewarr.ui.theme.UiOpts.choices[io.github.petebutty.viewarr.ui.theme.UiOpts.INFOBAR_STYLE]?.size ?: 6
        set(io.github.petebutty.viewarr.ui.theme.UiOpts.INFOBAR_STYLE, (AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.INFOBAR_STYLE) + 1) % n)
    }
    fun styleLabel() = "Style: " + (io.github.petebutty.viewarr.ui.theme.UiOpts.choices[io.github.petebutty.viewarr.ui.theme.UiOpts.INFOBAR_STYLE]
        ?.getOrNull(AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.INFOBAR_STYLE)) ?: "Glass")

    /** The film controls' buttons in the shared layout. */
    fun filmId(name: String) = when (name) {
        "Guide" -> "guide"; "PlayPause" -> "playpause"; "Stop" -> "stop"; "Details" -> "details"
        "Audio" -> "audio"; "Subtitles" -> "subtitles"; "Info" -> "info"; "SwitchPlayer" -> "switch"
        "Search" -> "search"; "Transparency" -> "transparency"; "Settings" -> "settings"
        else -> name.lowercase()
    }
}
