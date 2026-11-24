package io.github.petebutty.viewarr.ui.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.petebutty.viewarr.data.media.StreamOption
import io.github.petebutty.viewarr.data.media.VodStream

/**
 * Settings → Playback → Auto-play match: which addon stream Play starts by itself (2026-11-15).
 *
 * - **Rules**, one thing each (4K, 1080p, HEVC, Atmos…), switched on as you like and sorted by importance: the
 *   streams matching the top rule first, among those the ones matching the next, and so on; the usual order (instant
 *   first, then the highest resolution) settles the rest. Streams above Best quality allowed still come last. Each
 *   rule is a regex (upper / lower case alike) checked against the addon, name, description and address; your own
 *   can be added. It picks: the lists aren't hidden or re-sorted by it.
 * - **Cached only**: addon streams from Usenet or torrents that aren't marked instant (⚡) are left out, of the lists too.
 * - **Try the next stream** if one fails or doesn't start: the following ones in the same order go behind it
 *   ([VodStream.fallback]), see [SourceChain].
 */
object StreamMatch {
    /** One rule: [own] = typed by you (can be removed). */
    data class Rule(val label: String, val pattern: String, val on: Boolean = false, val own: Boolean = false)

    /** The ready-made rules, in their starting order (all off). */
    val builtIn = listOf(
        Rule("4K", "(2160p|\\b4k\\b|\\buhd\\b)"),
        Rule("1080p", "1080p"),
        Rule("720p", "720p"),
        Rule("REMUX", "remux"),
        Rule("BluRay", "(blu-?ray|b[dr]-?rip)"),
        Rule("WEB-DL / WEBRip", "(web[-\\s.]?dl|webrip)"),
        Rule("Dolby Vision", "(\\bdv\\b|dovi|dolby[\\s.]?vision)"),
        Rule("HDR10+", "hdr10(\\+|plus)"),
        Rule("HDR", "\\bhdr(?!ip)"),
        Rule("HEVC / x265", "(hevc|x265|h\\.?265)"),
        Rule("AVC / x264", "(\\bavc\\b|x264|h\\.?264)"),
        Rule("AV1", "\\bav1\\b"),
        Rule("Atmos", "atmos"),
        Rule("TrueHD", "truehd"),
        Rule("DTS-HD / DTS:X", "(dts[-\\s.]?hd|dts[-:\\s.]?x)"),
        Rule("DD+ / E-AC3", "(\\bddp|dd\\+|e-?ac-?3)"),
        Rule("7.1", "(?<![\\d.])7\\.1(?!\\d)"),
        Rule("5.1", "(?<![\\d.])5\\.1(?!\\d)"),
        Rule("English", "(\\beng\\b|english)"),
        Rule("Multi audio", "\\bmulti"),
        Rule("Not CAM / TS", "^(?!.*\\b(cam|hdcam|ts|hdts|telesync)\\b)"),
    )

    /** The pattern as a regex; null when it's empty, has no letters or digits, or isn't valid. */
    fun regex(pattern: String): Regex? {
        val p = pattern.trim()
        if (p.isEmpty() || p.none { it.isLetterOrDigit() }) return null
        return runCatching { Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }.getOrNull()
    }

    /**
     * The rules as saved (one a line: on, own, label, pattern, tab-separated), in your order; ready-made ones not
     * saved yet (new in an update) go on the end, off.
     */
    fun parse(saved: String): List<Rule> {
        val mine = saved.lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4) null else Rule(f[2], f[3], on = f[0] == "1", own = f[1] == "1")
        }
        val known = mine.filter { r -> r.own || builtIn.any { it.label == r.label } }
            .map { r -> if (r.own) r else r.copy(pattern = builtIn.first { it.label == r.label }.pattern) }
        return known + builtIn.filter { b -> known.none { it.label == b.label && !it.own } }
    }

    fun save(rules: List<Rule>): String =
        rules.joinToString("\n") { r -> listOf(if (r.on) "1" else "0", if (r.own) "1" else "0", clean(r.label), clean(r.pattern)).joinToString("\t") }

    /** (No tabs or line breaks: they'd split the saved line.) */
    private fun clean(t: String) = t.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /** The picture height a stream's name says (0: it doesn't say). */
    fun height(o: StreamOption): Int {
        val t = (o.name + " " + o.description).lowercase()
        return when {
            "2160" in t || "4k" in t || "uhd" in t -> 2160
            "1080" in t -> 1080
            "720" in t -> 720
            "480" in t || " sd" in t -> 480
            else -> 0
        }
    }

    // (Not the address: a ".ts" ending or a /ts/ path counted as a TS rip, and hashes in it matched words.)
    private fun text(o: StreamOption) = listOf(o.addon, o.name, o.description).joinToString(" ")

    /** Marked instant: the debrid service (or Usenet provider) already has it, or it's a server's own copy. */
    fun cached(o: StreamOption): Boolean {
        val t = o.name + " " + o.description
        val notCached = t.contains("uncached", ignoreCase = true) || t.contains("not cached", ignoreCase = true)
        return "⚡" in t || "+]" in t || t.contains("cached", ignoreCase = true) && !notCached || plexWord.containsMatchIn(t)
    }

    // (Compiled once: cached() is called for every stream while ranking.)
    private val plexWord = Regex("\\bplex\\b", RegexOption.IGNORE_CASE)

    private val debridKind = Regex("nzb|usenet|easynews|torrent|\\[(rd|pm|tb|ad)[+⚡]?\\]|real-?debrid|premiumize|torbox|alldebrid|⏳|uncached|not cached", RegexOption.IGNORE_CASE)

    /** A Usenet or torrent stream that isn't instant (plain web streams have nothing to cache, so never count). */
    fun uncached(o: StreamOption): Boolean = !cached(o) && debridKind.containsMatchIn(o.name + " " + o.description)

    /**
     * Play's own order from [ranked] (the usual order): those within the quality limit ([within]) first, then by the
     * rules switched on, top rule first; ties keep the usual order.
     */
    fun autoOrder(ranked: List<StreamOption>, rules: List<Rule>, within: (StreamOption) -> Boolean): List<StreamOption> {
        val active = rules.filter { it.on }.mapNotNull { regex(it.pattern) }
        if (active.isEmpty()) return ranked
        val keys = ranked.associateWith { o -> val t = text(o); active.map { re -> re.containsMatchIn(t) } }
        // (Each stream's quality check once, not again in every comparison.)
        val inside = ranked.associateWith(within)
        return ranked.sortedWith { a, b ->
            val wa = inside.getValue(a); val wb = inside.getValue(b)
            if (wa != wb) return@sortedWith if (wa) -1 else 1
            val ka = keys.getValue(a); val kb = keys.getValue(b)
            for (i in ka.indices) if (ka[i] != kb[i]) return@sortedWith if (ka[i]) -1 else 1
            0
        }
    }
}

/**
 * The stream playing and what's behind it ([VodStream.fallback]): Plex's
 * conversion, or the next addon streams ("Try the next stream"). [next] moves on to the one behind, if any.
 */
class SourceChain(first: VodStream) {
    var current = first
        private set

    /** [current] has started playing (once ready, a later wait to load is buffering, not a stream that won't start). */
    var ready = false

    /** How many times it has moved on (the no-start timer counts from the latest). */
    var switches by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** The one behind, or null: [current] becomes it. */
    fun next(): VodStream? = current.fallback?.also { current = it; ready = false; switches++ }

    /** The one behind is another addon stream (not Plex's conversion of this one): a stream that won't start moves on too. */
    val nextIsSource get() = current.fallback?.nextSource == true
}
