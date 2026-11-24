package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.ui.common.PendingOrder
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts

/**
 * Settings → Playback → Streams → Play order: where Play looks, in turn, for a film or episode, entirely your choice
 * (2026-11-24): six places in any order, each on or off. Standard: Plex in your house, your other servers in your
 * house, Plex outside it, your other servers outside it, IPTV, addons. Within a place, the copy that suits this TV
 * best goes first. A place switched off is skipped by Play but still listed when you hold OK on Play.
 *
 * Kept with the appearance settings like the info bars' rows: the order as ids (barOrder "play_order"), and a place
 * switched off as "play:<id>" in the hidden list.
 */
object PlayOrder {
    enum class Source(val id: String, val label: String, val about: String, val server: Boolean = false) {
        HomePlex("home_plex", "Plex in your house", "A Plex server on your home network.", server = true),
        HomeServers("home_servers", "Jellyfin / Emby in your house", "Your Jellyfin or Emby server on your home network.", server = true),
        AwayPlex("away_plex", "Plex outside your house", "A Plex server elsewhere: your own away from home, or a friend's.", server = true),
        AwayServers("away_servers", "Jellyfin / Emby outside your house", "A Jellyfin or Emby server reached over the internet.", server = true),
        Iptv("iptv", "IPTV", "Your provider's films and series, through Dispatcharr."),
        Addons("addons", "Addons", "Streams from your Stremio addons (the best within your quality limit)."),
    }

    const val KEY = "play_order"

    /** The place a server's copy is in. */
    fun placeOf(plex: Boolean, home: Boolean) = when {
        plex && home -> Source.HomePlex; home -> Source.HomeServers; plex -> Source.AwayPlex; else -> Source.AwayServers
    }

    /** Before the list (a three-way choice, UiOpts.PLAY_ORDER): kept to carry an old choice over. */
    private enum class Old { Servers, Iptv, Addons }
    private val oldOrders = listOf(
        listOf(Old.Servers, Old.Iptv, Old.Addons), listOf(Old.Servers, Old.Addons, Old.Iptv),
        listOf(Old.Iptv, Old.Servers, Old.Addons), listOf(Old.Iptv, Old.Addons, Old.Servers),
        listOf(Old.Addons, Old.Servers, Old.Iptv), listOf(Old.Addons, Old.Iptv, Old.Servers),
    )
    /** The old setting's choices as it listed them (UiOpts still names them, so an old choice reads back). */
    val oldLabels = oldOrders.map { o ->
        o.joinToString(", then ") { when (it) { Old.Servers -> "your servers"; Old.Iptv -> "IPTV"; Old.Addons -> "addons" } }.replaceFirstChar { it.uppercase() }
    }
    private fun fromOld(): List<Source> = oldOrders.getOrElse(AppColors.palette.opt(UiOpts.PLAY_ORDER)) { oldOrders[0] }.flatMap {
        when (it) {
            Old.Servers -> listOf(Source.HomePlex, Source.HomeServers, Source.AwayPlex, Source.AwayServers)
            Old.Iptv -> listOf(Source.Iptv)
            Old.Addons -> listOf(Source.Addons)
        }
    }

    /** Every place, in your order (switched off ones too, where you put them). */
    fun ordered(): List<Source> {
        val saved = PendingOrder.get(KEY, AppColors.palette.barOrder[KEY].orEmpty())
        val base = saved.mapNotNull { id -> Source.entries.firstOrNull { it.id == id } }.ifEmpty { fromOld() }
        return base + Source.entries.filter { it !in base }
    }

    fun on(s: Source) = AppColors.palette.shows("play:${s.id}")

    /** Where Play looks, in turn. */
    fun current(): List<Source> = ordered().filter(::on)

    /** The order with [s] moved one place ([dir] -1 up, 1 down); null at an end. Saved with setBarOrder([KEY]). */
    fun moved(s: Source, dir: Int): List<String>? = PendingOrder.move(KEY, ordered().map { it.id }, s.id, dir)

    /** A short line for Settings: the places switched on, in order. */
    fun summary(): String = current().joinToString(" → ") {
        when (it) {
            Source.HomePlex -> "Plex"; Source.HomeServers -> "Jellyfin"; Source.AwayPlex -> "Plex away"
            Source.AwayServers -> "Jellyfin away"; Source.Iptv -> "IPTV"; Source.Addons -> "Addons"
        }
    }.ifEmpty { "Nothing: hold OK on Play" }
}
