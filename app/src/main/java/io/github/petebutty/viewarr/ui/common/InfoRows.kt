package io.github.petebutty.viewarr.ui.common

import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts

/**
 * The rows under the info bars (Down from the buttons): which ones each player shows and in what order, chosen in
 * Settings → Appearance → Info bar & controls → Rows under the info bar (one list for live TV, one for films &
 * shows). They stack as scrolling preview rows: the one you're on, the next peeking in below; rows above slide away.
 *
 * Kept with the appearance settings like the bar's buttons: the order as ids ("rows_live" / "rows_film"), and each
 * row's on / off as a mark in the "switched off" list ("rows:<player>:<id>" for rows on unless switched off,
 * "rows:<player>:+<id>" for rows off unless switched on).
 */
object InfoRows {
    const val LIVE = "live"
    const val FILM = "film"

    /** One kind of row: which players can have it, and whether it's there without being chosen. */
    enum class Kind(val id: String, val label: String, val about: String, val live: Boolean, val film: Boolean, val onLive: Boolean = false, val onFilm: Boolean = false) {
        NEXT("next", "Next episodes", "The episodes after the one playing (episodes only).", live = false, film = true, onFilm = true),
        CONTINUE("continue", "Continue watching", "Everything you're part way through: your servers, Trakt and IPTV together.", live = true, film = true),
        CONTINUE_IPTV("continue_iptv", "Continue watching (IPTV)", "Films and episodes from IPTV, kept on this box.", live = true, film = true),
        CONTINUE_TRAKT("continue_trakt", "Continue watching (Trakt)", "Shows you've watched lately on Trakt (from any app), at the next episode.", live = true, film = true),
        CONTINUE_SERVERS("continue_servers", "Continue watching (Plex / Jellyfin)", "On deck and resume rows from your own servers.", live = true, film = true),
        RECENT_CHANNELS("recent_channels", "Recent channels", "The channels you watched last, with what's on now.", live = true, film = true, onLive = true),
        FAVOURITES("favourites", "Favourite channels", "Your ★ favourites, with what's on now.", live = true, film = true),
        ON_NOW("on_now", "On now in this group", "What's on the other channels of the group you're watching.", live = true, film = false),
        COMING_UP("coming_up", "Coming up on this channel", "The next programmes on the channel you're watching.", live = true, film = false),
        RECORDINGS("recordings", "Recordings", "Your latest recordings.", live = true, film = true),
        RECENTLY_ADDED("recent_added", "Recently added", "The newest films and shows on your servers.", live = true, film = true),
        WATCHLIST("watchlist", "Your watchlist", "Your Trakt watchlist.", live = true, film = true),
        SIMILAR("similar", "More like this", "Films or shows like the one playing.", live = false, film = true);

        fun allowed(player: String) = if (player == LIVE) live else film
        fun default(player: String) = if (player == LIVE) onLive else onFilm
    }

    fun kinds(player: String) = Kind.entries.filter { it.allowed(player) }

    /** Whether [kind] is shown under [player]'s bar. */
    fun shown(player: String, kind: Kind): Boolean {
        if (!kind.allowed(player)) return false
        // Before this list existed, live TV's recent channels had their own switch (Info bar: recent channels).
        if (player == LIVE && kind == Kind.RECENT_CHANNELS && AppColors.palette.opt(UiOpts.INFOBAR_RECENT) == 1 &&
            AppColors.palette.shows("rows:$player:${kind.id}")) return false
        return if (kind.default(player)) AppColors.palette.shows("rows:$player:${kind.id}") else !AppColors.palette.shows("rows:$player:+${kind.id}")
    }

    /** Switches [kind] on / off for [player]; [setPart] is the appearance store's setTitlePart, [setOpt] its UiOpts setter. */
    fun setShown(player: String, kind: Kind, show: Boolean, setPart: (String, Boolean) -> Unit, setOpt: (String, Int) -> Unit = { _, _ -> }) {
        if (kind.default(player)) setPart("rows:$player:${kind.id}", show) else setPart("rows:$player:+${kind.id}", !show)
        if (player == LIVE && kind == Kind.RECENT_CHANNELS && show) setOpt(UiOpts.INFOBAR_RECENT, 0)
    }

    /** [player]'s rows in your order (every kind it can have, shown or not). */
    fun ordered(player: String): List<Kind> {
        val saved = PendingOrder.get("rows_$player", AppColors.palette.barOrder["rows_$player"].orEmpty())
        val all = kinds(player)
        return saved.mapNotNull { id -> all.firstOrNull { it.id == id } } + all.filter { it.id !in saved }
    }

    /** The rows switched on, in order. */
    fun active(player: String): List<Kind> = ordered(player).filter { shown(player, it) }

    /** The order with [kind] moved one place ([dir] -1 up, 1 down); null at an end. Saved with saveBarOrder("rows_<player>"). */
    fun moved(player: String, kind: Kind, dir: Int): List<String>? {
        val ids = ordered(player).map { it.id }.toMutableList()
        val i = ids.indexOf(kind.id); val j = i + dir
        if (i < 0 || j !in ids.indices) return null
        java.util.Collections.swap(ids, i, j)
        PendingOrder.set("rows_$player", ids)
        return ids
    }
}
