package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaRow
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.PlexEpisode
import io.github.petebutty.viewarr.data.sync.TraktPos

/*
 * Watched ticks, progress bars, Continue watching and "where you're up to", read from everything connected at once
 * (Peter, 2026-11-19: "it should all just work"): Trakt, Plex / Jellyfin / Emby, and Viewarr's own record of IPTV
 * plays (shared between TVs through Dispatcharr). Watched anywhere → ticked. Part-way → the newest saved place wins.
 */

private typealias Ep = Pair<Int, Int>

/** One episode watched: by Trakt's history, a server's own flag, or Viewarr's record ([local]). */
fun watchedAny(k: Ep, plex: Map<Ep, PlexEpisode>, server: Map<Ep, PlexEpisode>, trakt: Set<Ep>, local: Map<Ep, PlexEpisode>): Boolean =
    plex[k]?.watched == true || server[k]?.watched == true || k in trakt || local[k]?.watched == true

/** A part-way point: [ms] into it, or (Trakt) [pct] of the way through; [at] when saved (0 = not known). */
data class WatchPos(val ms: Long, val pct: Float?, val at: Long)

/**
 * Where you stopped part-way through an episode, for its bar and Up next; null when it's watched anywhere or not
 * started. The newest saved place wins; without times, the server's, then Viewarr's, then Trakt's.
 */
fun positionOf(k: Ep, plex: Map<Ep, PlexEpisode>, server: Map<Ep, PlexEpisode>, trakt: Set<Ep>, local: Map<Ep, PlexEpisode>,
               traktPos: Map<Ep, TraktPos>): WatchPos? {
    if (watchedAny(k, plex, server, trakt, local)) return null
    // In order of preference when times are missing (sortedWith is stable).
    val all = listOfNotNull(
        plex[k]?.takeIf { it.viewOffsetMs > 0 }?.let { WatchPos(it.viewOffsetMs, null, it.at) },
        server[k]?.takeIf { it.viewOffsetMs > 0 }?.let { WatchPos(it.viewOffsetMs, null, it.at) },
        local[k]?.takeIf { it.viewOffsetMs > 0 }?.let { WatchPos(it.viewOffsetMs, null, it.at) },
        traktPos[k]?.takeIf { it.pct > 0f }?.let { WatchPos(0, it.pct, it.at) },
    )
    return all.sortedByDescending { it.at }.firstOrNull()
}

/** How much of a bar a saved place fills: Trakt's own %, else the time against the running time (half if unknown). */
fun WatchPos.part(runtimeMin: Int?): Float = pct?.coerceIn(0.05f, 0.95f)
    ?: runtimeMin?.takeIf { it > 0 }?.let { (ms / (it * 60_000f)).coerceIn(0.05f, 0.95f) } ?: 0.5f

/** This show's episode [k] watched, anywhere. [local]: this box's record (defaults to the page's copy). */
fun DetailUi.episodeWatched(k: Ep, local: Map<Ep, PlexEpisode> = localEpisodes) =
    watchedAny(k, plexEpisodes, serverEpisodes, traktEpisodes, local)

/** This show's episode [k]: how much of its bar to fill (0: watched or not started). */
fun DetailUi.episodeBar(k: Ep, runtimeMin: Int?, local: Map<Ep, PlexEpisode> = localEpisodes): Float =
    positionOf(k, plexEpisodes, serverEpisodes, traktEpisodes, local, traktPlayback)?.part(runtimeMin) ?: 0f

/**
 * Where you're up to, worked out from every source's ticks (a server can mark an episode watched yet name nothing
 * "on deck", and Trakt may not know yet): the furthest episode started but not finished past the last one finished,
 * else the first unwatched one after the last finished. null: nothing watched anywhere yet.
 */
fun workedUpTo(plex: Map<Ep, PlexEpisode>, server: Map<Ep, PlexEpisode>, trakt: Set<Ep>, local: Map<Ep, PlexEpisode>,
               traktPos: Map<Ep, TraktPos> = emptyMap()): Triple<Int, Int, Boolean>? {
    val order = compareBy<Ep>({ it.first }, { it.second })
    val all = (plex.keys + server.keys + trakt + local.keys + traktPos.keys).filter { it.first > 0 }.distinct().sortedWith(order)
    fun watched(k: Ep) = watchedAny(k, plex, server, trakt, local)
    fun started(k: Ep) = positionOf(k, plex, server, trakt, local, traktPos) != null
    val last = all.lastOrNull(::watched)
    val partway = all.lastOrNull { !watched(it) && started(it) }?.takeIf { s -> last == null || order.compare(s, last) > 0 }
    if (partway != null) return Triple(partway.first, partway.second, true)
    last ?: return null
    val after = all.firstOrNull { order.compare(it, last) > 0 && !watched(it) } ?: return null
    return Triple(after.first, after.second, started(after))
}

/** A title's one key across sources (TMDB, else IMDb), with its type; null: neither id known. */
private fun titleKey(i: MediaItem): String? = (i.tmdbId?.let { "tmdb:$it" } ?: i.imdbId?.takeIf { it.isNotBlank() }?.let { "imdb:$it" })?.let { "${i.type}:$it" }

/** Of two cards for one show, the one further on (later season / episode); else the first. */
private fun furtherOn(a: MediaItem, b: MediaItem): Boolean {
    if (a.type != MediaType.Show) return false
    val sa = a.nextSeason ?: return false; val ea = a.nextEpisode ?: 0
    val sb = b.nextSeason ?: return true; val eb = b.nextEpisode ?: 0
    return sa > sb || (sa == sb && ea > eb)
}

/**
 * Continue watching as one: each title once across the rows (Trakt's, each server's, IPTV's), in the row whose card is
 * furthest on (shows), else first in that order; [finished] titles (watched anywhere, or finished here) taken out of
 * them all. [fromTrakt]: the card is Trakt's own (a re-watch Trakt says is under way stays). Rows left empty go.
 */
fun mergeContinue(rows: List<MediaRow>, isContinue: (MediaRow) -> Boolean, finished: (MediaItem, fromTrakt: Boolean) -> Boolean): List<MediaRow> {
    fun rank(r: MediaRow) = when { r.id.startsWith("trakt-resume-") -> 0; r.id.startsWith("iptv-resume-") -> 2; else -> 1 }
    val best = HashMap<String, MediaItem>()
    for (row in rows.filter(isContinue).sortedBy(::rank)) {
        val trakt = rank(row) == 0
        for (i in row.items) {
            if (finished(i, trakt)) continue
            val k = titleKey(i) ?: continue
            val cur = best[k]
            if (cur == null || furtherOn(i, cur)) best[k] = i
        }
    }
    return rows.mapNotNull { row ->
        if (!isContinue(row)) return@mapNotNull row
        val trakt = rank(row) == 0
        val kept = row.items.filter { i -> !finished(i, trakt) && titleKey(i)?.let { best[it] === i } != false }
        if (kept.isEmpty()) null else row.copy(items = kept)
    }
}

/**
 * The combined Continue watching row (under the info bars): Trakt's, then the servers', then IPTV's, each title once
 * (each still switched on or off in Settings → Home page & menu → Continue watching from).
 */
fun continueInOrder(servers: List<MediaItem>, iptv: List<MediaItem>, trakt: List<MediaItem>, finished: (MediaItem) -> Boolean = { false }): List<MediaItem> =
    trakt + (servers + iptv).filterNot(finished)

/** "Marked as watched on Trakt and Plex" (or just "Marked as watched" when only this TV's record took it). */
fun markedMessage(watched: Boolean, where: List<String>, title: String? = null): String {
    val what = if (title != null) "Marked $title as" else "Marked as"
    val places = when (where.size) { 0 -> ""; 1 -> " on " + where[0]; else -> " on " + where.dropLast(1).joinToString(", ") + " and " + where.last() }
    return "$what ${if (watched) "watched" else "not watched"}$places"
}
