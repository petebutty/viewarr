package io.github.petebutty.viewarr.data.sports

import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * Score alerts' switches (Settings → Sports → Score alerts; 2026-11-18: "we need to be able to turn them off").
 * Kept as palette title parts, all on to start: "alerts:on" (any at all), "alerts:kind:<goal|kickoff|ht|ft>" (which
 * kinds), "alerts:banner" / "alerts:ticker" (where), "alerts:t:…" (the teams and leagues, off until chosen).
 */
object ScoreAlerts {
    const val ON = "alerts:on"
    const val BANNER = "alerts:banner"
    const val TICKER = "alerts:ticker"
    val kinds = listOf("goal" to "Goals and scores", "kickoff" to "Kick-off / tip-off", "ht" to "Half-time", "ft" to "Full time / final")
    fun kindKey(k: String) = "alerts:kind:$k"

    // Which teams / leagues: none to start, each one switched on by hand, followed or not (2026-11-18: "should
    // be auto off for everything… people will get flooded"). Kept as a title part that's present while ON (the
    // opposite way round from the others, so nothing is on by default): "alerts:t:<kind>|<league>|<id>|<name>".
    private const val TEAM = "alerts:t:"
    fun teamKey(f: SportFollow) = TEAM + listOf(f.kind.name, f.league, f.id, f.name).joinToString("|") { it.replace("|", "/") }
    /** The teams and leagues alerts are on for. */
    fun teams(): List<SportFollow> = AppColors.palette.titleHidden.filter { it.startsWith(TEAM) }.mapNotNull { k ->
        val p = k.removePrefix(TEAM).split("|")
        if (p.size < 4) null
        else SportFollow(kind = runCatching { SportFollow.Kind.valueOf(p[0]) }.getOrNull() ?: return@mapNotNull null,
            league = p[1], id = p[2], name = p.drop(3).joinToString("|"), logo = null)
    }.sortedBy { it.name }
    /** Alerts on for [f] (a title part present = on, hence false to [AppearanceViewModel.setTitlePart]). */
    fun teamOn(f: SportFollow) = teamKey(f) in AppColors.palette.titleHidden

    private fun shows(k: String) = AppColors.palette.shows(k)
    /** Alerts at all: at least one team on, not paused, and notifications on (Settings → Menu & navigation). */
    val on get() = shows(ON) && io.github.petebutty.viewarr.ui.common.Notify.on && teams().isNotEmpty()
    /** The master choice itself (Off / where), whatever the teams. */
    val enabled get() = shows(ON) && io.github.petebutty.viewarr.ui.common.Notify.on
    fun kindOn(kind: String?) = kind == null || shows(kindKey(kind))
    val banner get() = shows(BANNER)
    val ticker get() = shows(TICKER)

    /** The one "Score alerts" choice: off, or where they show. */
    val modes = listOf("Off", "Banner", "News ticker", "Banner and ticker")
    val mode: String get() = when {
        !enabled -> modes[0]
        banner && ticker -> modes[3]
        ticker -> modes[2]
        banner -> modes[1]
        else -> modes[0]
    }
    /** The title parts to set for [m]: (part, shown). */
    fun partsFor(m: String): List<Pair<String, Boolean>> = when (m) {
        modes[1] -> listOf(ON to true, BANNER to true, TICKER to false)
        modes[2] -> listOf(ON to true, BANNER to false, TICKER to true)
        modes[3] -> listOf(ON to true, BANNER to true, TICKER to true)
        else -> listOf(ON to false)
    }
}
