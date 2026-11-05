package io.github.petebutty.viewarr.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import io.github.petebutty.viewarr.data.media.ThumbSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Preview pictures for the seek bar: the frame nearest a position in the video. */
interface SeekThumbs {
    suspend fun at(ms: Long): Bitmap?
    fun close() {}

    companion object {
        private const val TAG = "SeekThumbs"

        /**
         * The best source for a stream: the server's own previews (Plex / Emby BIF, Jellyfin trickplay) when it has them,
         * else pictures this box takes from the video itself. HLS (Plex converting) has no file to take them from.
         */
        fun forStream(url: String, headers: Map<String, String>, source: ThumbSource?): SeekThumbs? {
            val own = if (url.contains(".m3u8")) null else RetrieverThumbs(url, headers)
            val server = when (source) {
                is ThumbSource.Bif -> BifThumbs(source.url, source.headers)
                is ThumbSource.Trickplay -> TrickplayThumbs(source)
                null -> null
            }
            return when {
                server != null && own != null -> FirstThatWorks(server, own)
                else -> server ?: own
            }.also { Log.i(TAG, "thumbnails: ${it?.javaClass?.simpleName ?: "none"}") }
        }

        internal fun download(url: String, headers: Map<String, String>): ByteArray? = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try {
                if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }.onFailure { Log.w(TAG, "download failed: ${it.message}") }.getOrNull()
    }
}

/** Uses [first] until it turns out to have nothing (e.g. the server never made previews), then [second]. */
private class FirstThatWorks(private val first: SeekThumbs, private val second: SeekThumbs) : SeekThumbs {
    @Volatile private var firstFailed = false
    override suspend fun at(ms: Long): Bitmap? {
        if (!firstFailed) {
            first.at(ms)?.let { return it }
            firstFailed = true
        }
        return second.at(ms)
    }
    override fun close() { first.close(); second.close() }
}

/**
 * Roku-style BIF index (Plex, Emby): one file holding every preview JPEG.
 * Layout: 8-byte magic, version, count, interval ms (0 = 1000) at 12..16, then (timestamp, offset) pairs from byte 64.
 */
private class BifThumbs(private val url: String, private val headers: Map<String, String>) : SeekThumbs {
    private val lock = Mutex()
    private var loaded = false
    private var data: ByteArray? = null
    private var times = LongArray(0)
    private var offsets = IntArray(0)
    private val cache = LruCache<Int, Bitmap>(12)

    private suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        loaded = true
        val b = withContext(Dispatchers.IO) { SeekThumbs.download(url, headers) } ?: return@withLock
        if (b.size < 64 || b[0] != 0x89.toByte() || b[1] != 'B'.code.toByte() || b[2] != 'I'.code.toByte() || b[3] != 'F'.code.toByte()) return@withLock
        fun u32(at: Int) = (b[at].toLong() and 0xff) or ((b[at + 1].toLong() and 0xff) shl 8) or
            ((b[at + 2].toLong() and 0xff) shl 16) or ((b[at + 3].toLong() and 0xff) shl 24)
        val count = u32(12).toInt()
        val step = u32(16).takeIf { it > 0 } ?: 1000L
        val t = ArrayList<Long>(count)
        val o = ArrayList<Int>(count + 1)
        var at = 64
        while (at + 8 <= b.size) {
            val ts = u32(at)
            val off = u32(at + 4).toInt()
            o.add(off)
            if (ts == 0xffffffffL) break
            t.add(ts * step)
            at += 8
        }
        if (t.isEmpty() || o.size < t.size + 1) return@withLock
        times = t.toLongArray()
        offsets = o.toIntArray()
        data = b
    }

    override suspend fun at(ms: Long): Bitmap? {
        load()
        val b = data ?: return null
        var i = times.binarySearch(ms)
        if (i < 0) i = (-i - 2).coerceAtLeast(0)
        cache.get(i)?.let { return it }
        val start = offsets[i]
        val len = offsets[i + 1] - start
        if (start < 0 || len <= 0 || start + len > b.size) return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, start, len) }?.also { cache.put(i, it) }
    }
}

/** Jellyfin trickplay: sheets of TileWidth × TileHeight previews, one every Interval ms. */
private class TrickplayThumbs(private val s: ThumbSource.Trickplay) : SeekThumbs {
    private val sheets = LruCache<Int, Bitmap>(3)
    private val failed = HashSet<Int>()
    private val lock = Mutex()

    override suspend fun at(ms: Long): Bitmap? {
        val n = (ms / s.intervalMs).toInt().coerceIn(0, (s.count - 1).coerceAtLeast(0))
        val perSheet = s.tileWidth * s.tileHeight
        val sheetNo = n / perSheet
        val sheet = lock.withLock {
            sheets.get(sheetNo) ?: if (sheetNo in failed) null else withContext(Dispatchers.IO) {
                SeekThumbs.download(s.sheetUrl(sheetNo), s.headers)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }.also { if (it != null) sheets.put(sheetNo, it) else failed.add(sheetNo) }
        } ?: return null
        val cell = n % perSheet
        val x = (cell % s.tileWidth) * s.width
        val y = (cell / s.tileWidth) * s.height
        if (x + s.width > sheet.width || y + s.height > sheet.height) return null
        return Bitmap.createBitmap(sheet, x, y, s.width, s.height)
    }
}

/**
 * Pictures taken by this box from the video itself (Stremio / debrid links, Plex files without previews). Each one
 * is the keyframe nearest the position, so it's quick; one at a time, small, and remembered in 10-second steps.
 */
private class RetrieverThumbs(private val url: String, private val headers: Map<String, String>) : SeekThumbs {
    private val lock = Mutex()
    private var mmr: MediaMetadataRetriever? = null
    private var broken = false
    private val cache = LruCache<Long, Bitmap>(30)

    override suspend fun at(ms: Long): Bitmap? {
        val bucket = ms / 10_000
        cache.get(bucket)?.let { return it }
        return lock.withLock {
            cache.get(bucket) ?: withContext(Dispatchers.IO) {
                if (broken) return@withContext null
                runCatching {
                    val r = mmr ?: MediaMetadataRetriever().also { it.setDataSource(url, headers); mmr = it }
                    r.getScaledFrameAtTime(bucket * 10_000_000L + 5_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                }.onFailure {
                    Log.w("SeekThumbs", "box previews unavailable: ${it.message}")
                    broken = true
                }.getOrNull()
            }?.also { cache.put(bucket, it) }
        }
    }

    override fun close() {
        // release() can block while a frame is being read; keep it off the main thread.
        val r = mmr ?: return
        mmr = null
        Thread { runCatching { r.release() } }.start()
    }
}
