package io.github.petebutty.viewarr.playback

import android.content.Context
import io.github.petebutty.viewarr.playback.PlaybackViewModel.LiveSource

/**
 * Which of a channel's streams to start on, and how each one has behaved on this box (2026-11-19: "play first
 * the best quality, and if it fails it should remember it failed, so it keeps a best order that is adjusted if a
 * stream fails"). Kept per Live TV server and channel in the "source_rank" preferences; nothing leaves the box.
 *
 * The order, best first:
 * - streams that failed lately (a fail in the last day, or three in the last week) go after every other one;
 * - then quality: Dispatcharr's own check of the stream where it has one, else the tags in the stream's name
 *   (4K / UHD / 2160; FHD / 1080; HD / 720; SD / 576 / 480; no tag counts as full HD: a main provider's untagged
 *   streams usually are, and a backup's "FHD" tag alone shouldn't beat them). 4K goes below everything: this
 *   line can't carry it;
 * - a stream that played well this week sits just above one of the same quality never tried;
 * - ties go to Dispatcharr's own order.
 * As a sum: (failed lately ? −10 : 0) + tier × 2 + (played well this week ? 1 : 0), tier being SD 1, HD 2, FHD 3, 4K ½.
 * Fails older than a week are forgotten.
 */
class SourceRank(context: Context) {
    private val prefs = context.getSharedPreferences("source_rank", Context.MODE_PRIVATE)

    /** One stream's record: when it failed this week, and when it last played for a minute without a break. */
    class Health(val fails: List<Long>, val lastOkAt: Long) {
        fun failedRecently(now: Long = System.currentTimeMillis()) = fails.any { now - it < DAY } || fails.size >= 3
        fun okLately(now: Long = System.currentTimeMillis()) = lastOkAt > 0 && now - lastOkAt < WEEK
    }

    private fun prefKey(key: String, streamId: Int) = "$key#$streamId"

    /** Stored as "t1,t2,…|okAt"; fails older than a week left out. */
    fun health(key: String, streamId: Int, now: Long = System.currentTimeMillis()): Health {
        val v = prefs.getString(prefKey(key, streamId), null) ?: return Health(emptyList(), 0L)
        val fails = v.substringBefore('|').split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < WEEK }
        return Health(fails, v.substringAfter('|', "").toLongOrNull() ?: 0L)
    }

    /** Told after each fail (true) or good play (false) is saved (data/sync/HealthSync shares the record with the plugin). */
    var onChange: ((fail: Boolean) -> Unit)? = null

    private fun save(key: String, streamId: Int, h: Health, fail: Boolean) {
        prefs.edit().putString(prefKey(key, streamId), h.fails.joinToString(",") + "|" + h.lastOkAt).apply()
        onChange?.invoke(fail)
    }

    /**
     * Every stream's record for one Live TV server, by stream id: its fails this week and when it last played well,
     * both in seconds (what HealthSync sends to the Viewarr plugin). A stream behind two channels has one record:
     * their fails together (the last 10), the later good play. (2026-11-19.)
     */
    fun export(serverUrl: String, now: Long = System.currentTimeMillis()): Map<Int, Pair<List<Long>, Long>> {
        val prefix = serverUrl.trimEnd('/') + "|"
        val out = HashMap<Int, Pair<List<Long>, Long>>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith(prefix) || v !is String) continue
            val id = k.substringAfterLast('#').toIntOrNull() ?: continue
            val fails = v.substringBefore('|').split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < WEEK }.map { it / 1000 }
            val ok = (v.substringAfter('|', "").toLongOrNull() ?: 0L).let { if (it > 0) it / 1000 else 0L }
            val old = out[id]
            out[id] = if (old == null) fails to ok
                else (old.first + fails).distinct().sorted().takeLast(10) to maxOf(old.second, ok)
        }
        return out
    }

    /** A stream that didn't start, or stopped and wouldn't come back: noted. Returns its fails this week. */
    fun recordFail(key: String, streamId: Int): Int {
        val now = System.currentTimeMillis()
        val h = health(key, streamId, now)
        val fails = (h.fails + now).takeLast(10)
        save(key, streamId, Health(fails, h.lastOkAt), fail = true)
        return fails.size
    }

    /** A stream that has played for a minute without a break: noted as good. */
    fun recordOk(key: String, streamId: Int) {
        val now = System.currentTimeMillis()
        save(key, streamId, Health(health(key, streamId, now).fails, now), fail = false)
    }

    private fun score(key: String, src: LiveSource, now: Long): Double {
        val h = health(key, src.streamId, now)
        return (if (h.failedRecently(now)) -10.0 else 0.0) + tier(src) * 2 + (if (h.okLately(now)) 1.0 else 0.0)
    }

    /** [sources] best first (see the class note); [dispatcharrOrder] (stream ids as Dispatcharr lists them) breaks ties. */
    fun order(key: String, sources: List<LiveSource>, dispatcharrOrder: List<Int>): List<LiveSource> {
        val now = System.currentTimeMillis()
        return sources.sortedWith(
            compareByDescending<LiveSource> { score(key, it, now) }
                .thenBy { dispatcharrOrder.indexOf(it.streamId).let { i -> if (i < 0) Int.MAX_VALUE else i } },
        )
    }

    /** The best source whose provider isn't [busy] (the caller knows the lines free), else the best of all. */
    fun bestFor(key: String, sources: List<LiveSource>, dispatcharrOrder: List<Int>, busy: (LiveSource) -> Boolean): LiveSource? {
        val ranked = order(key, sources, dispatcharrOrder)
        return ranked.firstOrNull { !busy(it) } ?: ranked.firstOrNull()
    }

    companion object {
        const val DAY = 86_400_000L
        const val WEEK = 7 * DAY

        private val UHD = Regex("""\b(4K|UHD|2160[pi]?)\b""", RegexOption.IGNORE_CASE)
        private val FHD = Regex("""\b(FHD|1080[pi]?)\b""", RegexOption.IGNORE_CASE)
        private val SD = Regex("""\b(SD|576[pi]?|480[pi]?)\b""", RegexOption.IGNORE_CASE)
        private val HD = Regex("""\b(HD|720[pi]?)\b""", RegexOption.IGNORE_CASE)

        /** The picture height Dispatcharr's check gave ("1080p · 25 fps · H264" → 1080, "4K" → 2160), else null. */
        private fun checkedHeight(quality: String?): Int? {
            val q = quality?.substringBefore(" ·")?.trim() ?: return null
            return if (q.equals("4K", true)) 2160 else q.removeSuffix("p").toIntOrNull()
        }

        /** Quality tier: 1 SD, 2 HD, 3 FHD (and no tag at all); 4K counts ½ (below SD: this line can't carry it). */
        fun tier(src: LiveSource): Double {
            val h = checkedHeight(src.quality)
            val t = when {
                h != null -> when { h >= 2000 -> 4; h >= 1000 -> 3; h >= 700 -> 2; else -> 1 }
                UHD.containsMatchIn(src.name) -> 4
                FHD.containsMatchIn(src.name) -> 3
                SD.containsMatchIn(src.name) -> 1
                HD.containsMatchIn(src.name) -> 2
                else -> 3
            }
            return if (t == 4) 0.5 else t.toDouble()
        }

        /** For the log: Dispatcharr's check, else the tier the name gave ("HD (name)"). */
        fun label(src: LiveSource): String = src.quality ?: when (tier(src)) { 3.0 -> "FHD"; 1.0 -> "SD"; 0.5 -> "4K"; else -> "HD" } + " (by name)"
    }
}
