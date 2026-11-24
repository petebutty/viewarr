package io.github.petebutty.viewarr.data.sports

import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which UK channel shows a game, from Sky's own TV guide (the public schedule behind sky.com's TV guide): Sky Sports,
 * TNT Sports, Premier Sports, BBC, ITV, Channel 4 / 5, S4C, BBC Alba, about a week ahead. Viewarr's own source, so
 * nobody needs a plugin for it (2026-11-16: "Viewarr should do most of the work"). Programmes like "Live EFL:
 * Barnsley v Sheff Weds" are matched to a game by [GameMatch] (both teams, around kick-off). Only channel numbers and
 * dates are sent; each day's answer is kept 6 hours.
 */
@Singleton
class SkySchedule @Inject constructor(private val http: HttpJson) {
    /** Sky's channel ids, with the name to look your channel up by. */
    private val channels = linkedMapOf(
        "4002" to "Sky Sports Main Event", "4010" to "Sky Sports Premier League", "3939" to "Sky Sports Football",
        "3940" to "Sky Sports+", "4022" to "Sky Sports Action", "4090" to "Sky Sports Mix", "4081" to "Sky Sports Cricket",
        "4026" to "Sky Sports Golf", "3835" to "Sky Sports F1", "1284" to "Sky Sports Tennis", "4032" to "Sky Sports Racing",
        "1035" to "Sky Sports Box Office",
        "3625" to "TNT Sports 1", "3627" to "TNT Sports 2", "3629" to "TNT Sports 3", "4040" to "TNT Sports 4",
        "1336" to "TNT Sports Ultimate", "1210" to "TNT Sports Box Office",
        "1289" to "Premier Sports 1", "1290" to "Premier Sports 2",
        "3000" to "BBC One", "2082" to "BBC One", "2075" to "BBC Two", "6504" to "ITV1", "4075" to "Channel 4", "4058" to "Channel 5",
        "2296" to "S4C", "3021" to "BBC Alba", "1003" to "MUTV", "3845" to "LFCTV",
    )

    /** A programme: the channel's name, its title and description, start and end. */
    data class Showing(val channel: String, val title: String, val synopsis: String, val start: Long, val end: Long) {
        /** The title normalised once for matching (GameMatch), not again for every game. */
        val matchText: String by lazy { " " + GameMatch.norm(title) + " " }
    }

    /** A game found in Sky's guide: the channels showing it (most often one, sometimes two), and the programme. */
    data class Found(val channels: List<String>, val title: String, val start: Long)

    private val days = ConcurrentHashMap<LocalDate, Pair<Long, List<Showing>>>()
    private val lock = Mutex()
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    /** After Sky didn't answer: left alone for 10 minutes (not asked again for every game). */
    @Volatile private var restUntil = 0L

    /** How far ahead Sky's guide goes (about a week). */
    private val daysAhead = 7

    private suspend fun day(d: LocalDate): List<Showing>? = lock.withLock {
        val now = System.currentTimeMillis()
        days[d]?.takeIf { now - it.first < 6 * 3_600_000L }?.let { return it.second }
        if (now < restUntil) return days[d]?.second
        val out = ArrayList<Showing>()
        // Up to 20 channels a request.
        for (batch in channels.keys.chunked(20)) {
            val r = withTimeoutOrNull(10_000) {
                runCatching { http.get("$BASE/${d.format(fmt)}/${batch.joinToString(",")}", mapOf("X-SkyOTT-Territory" to "GB")) }.getOrNull()
            }
            if (r == null || !r.ok) { restUntil = now + 10 * 60_000L; return days[d]?.second }
            r.body.array("schedule").forEach { s ->
                val name = channels[s.str("sid")] ?: return@forEach
                s.array("events").forEach { e ->
                    val st = e.str("st")?.toLongOrNull() ?: return@forEach
                    val dur = e.str("d")?.toLongOrNull() ?: 0
                    out += Showing(name, e.str("t").orEmpty(), e.str("sy").orEmpty(), st * 1000, (st + dur) * 1000)
                }
            }
        }
        days[d] = now to out
        out
    }

    /**
     * Where a game is on, from Sky's guide: programmes starting from 3 hours before kick-off to 1 hour after that name
     * both teams. Null: not in the guide's range, or Sky didn't answer. An empty list: in range, and not on these channels.
     */
    suspend fun find(g: SportGame): Found? {
        val kick = Instant.ofEpochMilli(g.start).atZone(ZoneOffset.UTC).toLocalDate()
        val today = LocalDate.now(ZoneOffset.UTC)
        if (kick.isBefore(today) || kick.isAfter(today.plusDays(daysAhead.toLong()))) return null
        // Programmes from the evening before count too (a 00:30 kick-off's show starts the day before).
        val shows = listOfNotNull(day(kick), if (kick.isAfter(today)) day(kick.minusDays(1)) else emptyList()).flatten()
        if (shows.isEmpty()) return null
        val hits = shows.filter { s ->
            s.start in (g.start - 3 * 3_600_000L)..(g.start + 3_600_000L) && s.end > g.start &&
                // (Not highlights or a replay of it.)
                !REPLAY.containsMatchIn(s.title) && GameMatch.normTextIsGame(s.matchText, g) == GameMatch.SURE
        }
        if (hits.isEmpty()) return Found(emptyList(), "", 0)
        // Live first, then the nearest to kick-off.
        val best = hits.sortedWith(compareByDescending<Showing> { LIVE.containsMatchIn(it.title) }.thenBy { abs(it.start - g.start) })
        val top = best.first()
        return Found(best.filter { it.title == top.title }.map { it.channel }.distinct(), top.title, top.start)
    }

    private companion object {
        const val BASE = "https://awk.epgsky.com/hawk/linear/schedule"
        val LIVE = Regex("^live\\b", RegexOption.IGNORE_CASE)
        val REPLAY = Regex("\\b(hlts|highlights|replay|rewind|classic|greatest|preview|review|reaction|best of)\\b", RegexOption.IGNORE_CASE)
    }
}
