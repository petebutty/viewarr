package io.github.petebutty.viewarr.data.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * This box's internet speed, for choosing a film's copy from a server outside the house (Settings → Playback →
 * Internet copies: Automatic; 2026-11-24: "curated to the box/TV/bandwidth"). Kept on the box only (each box
 * has its own connection: Wi-Fi or cable, another room).
 *
 * Learned from films played from internet servers (the player's own measure of how fast the file came in), and, before
 * there is one, a short download test (a few MB from Cloudflare's speed test, which carries nothing about you).
 */
object BoxSpeed {
    private const val PREFS = "box_speed"
    private const val KBPS = "kbps"
    private const val AT = "at"
    private const val TESTED = "tested"
    /** Used when nothing has been measured and the test failed: a good 1080p or a light 4K. */
    private const val UNKNOWN_KBPS = 25_000

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The speed measured lately (kb/s), or null. */
    fun measured(c: Context): Int? = prefs(c).let { p ->
        p.getInt(KBPS, 0).takeIf { it > 0 && System.currentTimeMillis() - p.getLong(AT, 0) < 14 * 86_400_000L }
    }

    /** A film from an internet server played: how fast it came in (kb/s). Smoothed, so one slow evening doesn't decide. */
    fun record(c: Context, kbps: Long) {
        if (kbps < 500) return
        val old = measured(c)
        val v = if (old == null) kbps.toInt() else (old * 0.6 + kbps * 0.4).toInt()
        prefs(c).edit().putInt(KBPS, v).putLong(AT, System.currentTimeMillis()).apply()
    }

    /**
     * The biggest copy (kb/s) that plays smoothly here: three quarters of the speed (room for the rest of the house).
     * Nothing measured yet: a short test first (at most once a day, up to 4 s).
     */
    suspend fun limitKbps(c: Context): Int {
        measured(c)?.let { return (it * 0.75).toInt() }
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong(TESTED, 0) > 86_400_000L) {
            p.edit().putLong(TESTED, System.currentTimeMillis()).apply()
            test()?.let { record(c, it); return (it * 0.75).toInt() }
        }
        return UNKNOWN_KBPS
    }

    /** As [limitKbps] but never testing: what's known already. */
    fun knownLimitKbps(c: Context): Int = measured(c)?.let { (it * 0.75).toInt() } ?: UNKNOWN_KBPS

    /** A few MB downloaded and timed (kb/s), or null. */
    private suspend fun test(): Long? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(5_000) {
            runCatching {
                val con = URL("https://speed.cloudflare.com/__down?bytes=8000000").openConnection() as HttpURLConnection
                con.connectTimeout = 3_000; con.readTimeout = 3_000
                con.setRequestProperty("User-Agent", "Viewarr")
                try {
                    val buf = ByteArray(64 * 1024)
                    val input = con.inputStream
                    // (Timed from the first bytes: the connection's set-up isn't speed.)
                    var n = input.read(buf).toLong()
                    val start = System.nanoTime()
                    var got = 0L
                    while (n >= 0 && System.nanoTime() - start < 3_000_000_000L) { n = input.read(buf).toLong(); if (n > 0) got += n }
                    val secs = (System.nanoTime() - start) / 1e9
                    if (got < 500_000 || secs <= 0) null else (got * 8 / secs / 1000).toLong()
                } finally { con.disconnect() }
            }.getOrNull()
        }
    }

    /** An address outside the house (not this network's own). */
    fun isInternet(url: String?): Boolean {
        val raw = runCatching { java.net.URI(url ?: return false).host }.getOrNull()?.lowercase() ?: return false
        // (Plex's own addresses carry the server's IP in the name: 192-168-1-245.<id>.plex.direct.)
        val host = if (raw.endsWith(".plex.direct")) raw.substringBefore('.').replace('-', '.') else raw
        return !(host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") ||
            Regex("^(10\\.|127\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.)").containsMatchIn(host))
    }
}
