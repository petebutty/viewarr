package io.github.petebutty.viewarr.ui.common

import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts
import java.util.Collections

/**
 * The info bars' buttons as you've arranged them: ONE layout shared by live TV's controls and the film / episode
 * controls, so the two stay the same. Each bar shows the buttons it has, in that order: live TV's extras (Aspect,
 * Source, Record, Multi-view) and the film's own (Play / Pause, Details, Switch player) keep their places in it.
 * Edited by holding OK on a button of either bar: the Info bar buttons list for that bar (BarButtonsEditor, the same
 * as Settings → Appearance → Info bar buttons). (The film bar was edited in place until 2026-11-17 UI review.)
 * Kept with the appearance settings: the order as ids, hidden ones as "bar:all:<id>".
 */
object BarLayout {
    private const val BAR = "all"

    /**
     * Every button either bar can have, in the order they come without changes. Each bar only shows its own (see
     * [liveOnly] and [filmOnly]).
     */
    val defaultOrder = listOf(
        "guide", "playpause", "stop", "details", "audio", "subtitles", "aspect", "source", "info", "switch",
        "record", "multiview", "refreshguide", "favourite", "lastchannel", "prevepisode", "nextepisode", "episodes", "sync", "sleep", "movies", "shows", "recordings",
        "search", "transparency", "barstyle", "tvsettings", "settings",
    )

    /**
     * Extra buttons: not in the bars until added (OK adds one: in the Info bar buttons list they're under Not on the
     * bar).
     */
    val extras = setOf("favourite", "lastchannel", "prevepisode", "nextepisode", "episodes", "sync", "sleep", "movies", "shows", "recordings", "tvsettings", "barstyle")

    /** The two bars: live TV's, and the film and episode player's. */
    enum class Bar { Live, Film }

    /**
     * Each bar's main buttons: always on it (they can be moved, not removed). Live TV: TV guide, Stop, Audio, Subtitles
     * and Source (switching source rescues a stream that won't play); films: Play / Pause, Stop, Audio, Subtitles.
     * (2026-11-17: "main player functions must be tied to the info bar". TV guide and Play / Pause were the only
     * ones, for both bars, and a bar could lose Stop, Audio and Subtitles.)
     */
    val liveFixed = setOf("guide", "stop", "audio", "subtitles", "source")
    val filmFixed = setOf("playpause", "stop", "audio", "subtitles")
    fun locked(id: String, bar: Bar) = id in (if (bar == Bar.Live) liveFixed else filmFixed)
    /** The last of [ids] (a bar as arranged) that's locked on [bar]: the divider goes after it. */
    fun lastLocked(ids: List<String>, bar: Bar): String? = ids.lastOrNull { locked(it, bar) }

    /** The bars [id] can be on. */
    fun barsOf(id: String) = when (id) { in liveOnly -> listOf(Bar.Live); in filmOnly -> listOf(Bar.Film); else -> Bar.entries.toList() }
    /** Locked on every bar it can be on: nothing to switch. */
    fun lockedEverywhere(id: String) = barsOf(id).all { locked(id, it) }
    /** Where [id] is locked, for Settings: "always on the live TV bar"… (null: nowhere). */
    fun lockNote(id: String): String? {
        val on = barsOf(id).filter { locked(id, it) }
        return when {
            on.isEmpty() -> null
            on.size == 2 -> "always on both bars"
            on[0] == Bar.Live -> "always on the live TV bar" + if (barsOf(id).size == 2) " (tick for the film bar)" else ""
            else -> "always on the film bar" + if (barsOf(id).size == 2) " (tick for the live TV bar)" else ""
        }
    }

    /** Each button's name (Settings → Appearance → Info bar buttons, and the editor on the bar). */
    val labels = linkedMapOf(
        "guide" to "TV guide", "playpause" to "Play / Pause", "stop" to "Stop", "details" to "Details", "audio" to "Audio",
        "subtitles" to "Subtitles", "aspect" to "Aspect", "source" to "Source", "info" to "Stream info", "switch" to "Switch player",
        "record" to "Record", "multiview" to "Multi-view", "refreshguide" to "Refresh guide", "favourite" to "Favourite",
        "lastchannel" to "Last channel", "prevepisode" to "Previous episode", "nextepisode" to "Next episode", "episodes" to "Episodes",
        "sync" to "Audio sync", "sleep" to "Sleep timer", "movies" to "Movies", "shows" to "Shows", "recordings" to "Recordings",
        "search" to "Search", "transparency" to "Transparency", "barstyle" to "Info bar style", "tvsettings" to "TV settings",
        "settings" to "Settings",
    )
    /** Buttons only one bar has (the rest are on both): live TV's, and the film and episode player's. */
    val liveOnly = setOf("aspect", "source", "record", "multiview", "refreshguide", "favourite", "lastchannel")
    val filmOnly = setOf("playpause", "details", "switch", "prevepisode", "nextepisode", "episodes")

    /** Every button in the shared order. */
    fun orderedIds(): List<String> = order()

    /** On [bar] (locked there: always); with no bar, the tick (Settings): on unless switched off, or locked everywhere. */
    fun shown(id: String, bar: Bar? = null) = when {
        bar != null && locked(id, bar) -> true
        bar == null && lockedEverywhere(id) -> true
        // (An extra's mark is kept in the same "switched off" list, meaning the opposite: added.)
        id in extras -> !AppColors.palette.shows("bar:$BAR:+$id")
        else -> AppColors.palette.shows("bar:$BAR:$id")
    }

    /** Shows / hides (adds / removes, for an extra) [id] in both bars; [setPart] is the appearance store's setTitlePart. */
    fun setShown(id: String, show: Boolean, setPart: (String, Boolean) -> Unit) {
        if (lockedEverywhere(id)) return
        if (id in extras) setPart("bar:$BAR:+$id", !show) else setPart("bar:$BAR:$id", show)
    }

    private fun order(): List<String> {
        val saved = PendingOrder.get("bar", AppColors.palette.barOrder[BAR].orEmpty())
        return saved + defaultOrder.filter { it !in saved }
    }

    /** [all] (one bar's buttons) in the shared order; hidden ones left out unless [editing]. */
    fun <T> arrange(all: List<T>, id: (T) -> String, editing: Boolean, bar: Bar): List<T> {
        val o = order()
        val sorted = all.withIndex().sortedBy { (i, t) -> o.indexOf(id(t)).let { if (it < 0) o.size + i else it } }.map { it.value }
        // The bar's locked buttons first, then a divider (drawn after [lastLocked]), then the rest; each group in your
        // order. (2026-11-17: "locked buttons can have a line separating them".)
        val (lk, rest) = (if (editing) sorted else sorted.filter { shown(id(it), bar) }).partition { locked(id(it), bar) }
        return lk + rest
    }

    /**
     * The shared order with [id] moved past its neighbour in this bar ([ids]: the bar's buttons as arranged; [dir] -1
     * left, 1 right). Buttons the other bar has in between keep their places. Null at an end.
     * [ids] is put in the latest order first: a held Left / Right can arrive before the bar has redrawn, and moving
     * from the drawn (old) order swapped the wrong neighbours and scrambled the bar.
     * [bar]: as that bar draws them, its locked buttons first (see [arrange]): a move stays within its group (locked or
     * not) and swaps with the neighbour on screen (it swapped with one between them in the shared order, drawn
     * elsewhere: the press seemed to do nothing, or the button jumped). Null: the shared order as it is (Settings).
     */
    fun moved(ids: List<String>, id: String, dir: Int, bar: Bar? = null): List<String>? {
        val o = order()
        val sorted = ids.sortedBy { o.indexOf(it).let { k -> if (k < 0) o.size + ids.indexOf(it) else k } }
        val drawn = if (bar == null) sorted else sorted.partition { locked(it, bar) }.let { (lk, rest) -> lk + rest }
        val i = drawn.indexOf(id); val j = i + dir
        if (i < 0 || j !in drawn.indices) return null
        if (bar != null && locked(id, bar) != locked(drawn[j], bar)) return null
        val bar = drawn
        val full = (o + bar.filter { it !in o }).toMutableList()
        val a = full.indexOf(id); val b = full.indexOf(bar[j])
        Collections.swap(full, a, b)
        PendingOrder.set("bar", full)
        return full
    }

    /** Info bar style button: the next style (each press shows it straight away), and its label. */
    fun nextStyle(set: (String, Int) -> Unit) {
        val n = UiOpts.choices[UiOpts.INFOBAR_STYLE]?.size ?: 6
        set(UiOpts.INFOBAR_STYLE, (AppColors.palette.opt(UiOpts.INFOBAR_STYLE) + 1) % n)
    }
    fun styleLabel() = "Style: " + (UiOpts.choices[UiOpts.INFOBAR_STYLE]
        ?.getOrNull(AppColors.palette.opt(UiOpts.INFOBAR_STYLE)) ?: "Glass")

    /** The film controls' buttons (PlayerButton names) in the shared layout: the name in lower case, but "switch". */
    fun filmId(name: String) = if (name == "SwitchPlayer") "switch" else name.lowercase()
}
