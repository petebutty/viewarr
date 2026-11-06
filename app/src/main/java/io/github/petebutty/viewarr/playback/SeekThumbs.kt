package io.github.petebutty.viewarr.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import io.github.petebutty.viewarr.data.media.ThumbSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
        fun forStream(url: String, headers: Map<String, String>, source: ThumbSource?, boxPreviews: Boolean = true): SeekThumbs? {
            val own = if (!boxPreviews || url.contains(".m3u8")) null else RetrieverThumbs(url, headers)
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
 * Pictures taken by this box from the video itself (Stremio / debrid links, Plex files without previews): the
 * keyframe nearest the position, decoded in software (so playback keeps the hardware decoder) and shrunk to 320×180.
 * Android's own MediaMetadataRetriever returns only black frames on Amlogic boxes, hence doing it by hand.
 * Work runs in its own scope, so a picture the seek bar stopped waiting for is still kept for next time.
 */
private class RetrieverThumbs(private val url: String, private val headers: Map<String, String>) : SeekThumbs {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var extractor: android.media.MediaExtractor? = null
    private var codec: android.media.MediaCodec? = null
    /** The stream's setup data (H.264 SPS/PPS, HEVC VPS/SPS/PPS…): a flushed decoder needs it again. */
    private var csd: List<ByteArray> = emptyList()
    @Volatile private var broken = false
    @Volatile private var closed = false
    /** Held while a frame is decoded; close() takes it before releasing the decoder, so they never overlap. */
    private val codecLock = Any()
    private val cache = LruCache<Long, Bitmap>(40)
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<Bitmap?>>()

    override suspend fun at(ms: Long): Bitmap? {
        val bucket = ms / 10_000
        cache.get(bucket)?.let { return it }
        if (broken) return null
        val job = pending.getOrPut(bucket) {
            scope.async {
                try { grab(bucket)?.also { cache.put(bucket, it) } } finally { pending.remove(bucket) }
            }
        }
        return job.await()
    }

    private fun open() {
        if (codec != null) return
        val ex = android.media.MediaExtractor()
        // Our own byte-range reader: Android's HTTP source reports the size as unknown for these links,
        // which leaves the extractor unable to jump anywhere but the start.
        ex.setDataSource(RangeSource(url, headers))
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: error("no video track")
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
        csd = listOf("csd-0", "csd-1", "csd-2").mapNotNull { k ->
            format.getByteBuffer(k)?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
        }
        // A software decoder: the hardware one is busy playing the film.
        val name = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .let { list -> list.firstOrNull { it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.") } ?: list.firstOrNull() }
            ?.name ?: error("no decoder for $mime")
        format.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val c = android.media.MediaCodec.createByCodecName(name)
        c.configure(format, null, null, 0)
        c.start()
        Log.i("SeekThumbs", "box previews: $mime with $name")
        extractor = ex
        codec = c
    }

    private fun grab(bucket: Long): Bitmap? = synchronized(codecLock) { grabLocked(bucket) }

    private fun grabLocked(bucket: Long): Bitmap? {
        cache.get(bucket)?.let { return it }
        if (broken || closed) return null
        return runCatching {
            open()
            val ex = extractor!!
            val c = codec!!
            val t0 = System.currentTimeMillis()
            val want = bucket * 10_000_000L + 5_000_000L
            ex.seekTo(want, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val landed = ex.sampleTime
            // Some extractors (Amlogic's Matroska one) land on any frame: step on to a real keyframe, or decoding
            // starts without the pictures it refers to (green / pink noise).
            var skipped = 0
            while (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC == 0 && skipped < 600) {
                if (!ex.advance()) break
                skipped++
            }
            if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC == 0) {
                Log.i("SeekThumbs", "box frame ${bucket * 10}s: no keyframe nearby")
                return null
            }
            c.flush()
            for (data in csd) {
                val i = c.dequeueInputBuffer(100_000)
                if (i < 0) break
                c.getInputBuffer(i)!!.apply { clear(); put(data) }
                c.queueInputBuffer(i, 0, data.size, 0, android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            val info = android.media.MediaCodec.BufferInfo()
            var fed = 0
            var eos = false
            var out: Bitmap? = null
            while (out == null && !closed && System.currentTimeMillis() - t0 < 8_000) {
                if (!eos) {
                    val i = c.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = if (fed < 40) ex.readSampleData(c.getInputBuffer(i)!!, 0) else -1
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance(); fed++ }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    if (info.size > 0) out = c.getOutputImage(o)?.use { toBitmap(it) }?.takeUnless { looksBroken(it) }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            Log.i("SeekThumbs", "box frame ${bucket * 10}s (landed ${landed / 1_000_000}s): ${if (out == null) "none" else "ok"} after $skipped skipped + $fed samples in ${System.currentTimeMillis() - t0} ms")
            out
        }.onFailure {
            if (closed) return null
            Log.w("SeekThumbs", "box previews unavailable: $it")
            broken = true
        }.getOrNull()
    }

    /** A frame decoded without its references is mostly bright green (all-zero YUV): don't show that. */
    private fun looksBroken(b: Bitmap): Boolean {
        var green = 0; var n = 0
        for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) {
            val p = b.getPixel(x, y)
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val bl = p and 0xff
            if (g > 120 && r < 60 && bl < 60) green++
            n++
        }
        return green * 4 > n
    }

    /** YUV 4:2:0 picture → 320×180 bitmap (nearest-pixel shrink; plenty for a preview). */
    private fun toBitmap(img: android.media.Image): Bitmap {
        val w = 320; val h = 180
        val crop = img.cropRect
        val (yp, up, vp) = img.planes.let { Triple(it[0], it[1], it[2]) }
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val sy = crop.top + y * crop.height() / h
            for (x in 0 until w) {
                val sx = crop.left + x * crop.width() / w
                val yy = (yb.get(sy * yp.rowStride + sx * yp.pixelStride).toInt() and 0xff) - 16
                val ci = (sy / 2) * up.rowStride + (sx / 2) * up.pixelStride
                val u = (ub.get(ci).toInt() and 0xff) - 128
                val v = (vb.get((sy / 2) * vp.rowStride + (sx / 2) * vp.pixelStride).toInt() and 0xff) - 128
                val c = 1192 * yy.coerceAtLeast(0)
                val r = ((c + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((c - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((c + 2066 * u) shr 10).coerceIn(0, 255)
                px[y * w + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        closed = true
        scope.cancel()
        // Off the main thread: waits for a frame being decoded to finish (the loop stops early once closed).
        Thread {
            synchronized(codecLock) {
                val c = codec; val ex = extractor
                codec = null; extractor = null
                runCatching { c?.stop() }; runCatching { c?.release() }; runCatching { ex?.release() }
            }
        }.start()
    }
}

/**
 * Reads a video over HTTP in 256 KB byte ranges (a few kept), for MediaExtractor. Redirects (Stremio addons
 * hand out resolver links) are followed once up front, and the real file size comes from Content-Range.
 */
private class RangeSource(url: String, private val headers: Map<String, String>) : android.media.MediaDataSource() {
    private val block = 256 * 1024
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, ByteArray>?) = size > 12
    }
    private val target: String
    private val length: Long

    init {
        var u = url
        var len = -1L
        for (hop in 0 until 6) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000; readTimeout = 20_000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); continue }
                len = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: c.contentLengthLong
                break
            } finally { c.disconnect() }
        }
        target = u
        length = len
        if (length <= 0) error("size unknown")
    }

    override fun getSize() = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        var done = 0
        while (done < size && position + done < length) {
            val p = position + done
            val start = p / block * block
            val b = blocks[start] ?: fetch(start).also { blocks[start] = it }
            val inBlock = (p - start).toInt()
            if (inBlock >= b.size) break
            val n = minOf(size - done, b.size - inBlock)
            System.arraycopy(b, inBlock, buffer, offset + done, n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    private fun fetch(start: Long): ByteArray {
        val end = minOf(start + block, length) - 1
        val c = (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Range", "bytes=$start-$end")
        }
        try {
            if (c.responseCode != 206) error("range not supported (${c.responseCode})")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    override fun close() { blocks.clear() }
}
