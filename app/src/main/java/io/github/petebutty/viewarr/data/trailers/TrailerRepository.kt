package io.github.petebutty.viewarr.data.trailers

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import io.github.petebutty.viewarr.data.media.CatalogRepository
import io.github.petebutty.viewarr.data.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Trailers"

/** Desktop Firefox, as NewPipe uses: YouTube serves the web player's formats to it. */
internal const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

/** A resolved trailer: one video-only stream plus one audio stream (YouTube keeps them apart above 360p). */
class TrailerStreams(val video: VideoStream, val audio: AudioStream?, val durationSec: Long, val youtubeId: String)

/**
 * Trailers: TMDB says which YouTube video is the trailer; NewPipe Extractor (GPL-3.0) finds the playable
 * streams behind it, and [mediaSource] builds an ExoPlayer source that fetches them the way YouTube expects.
 */
@Singleton
class TrailerRepository @Inject constructor(
    private val catalog: CatalogRepository,
    private val client: OkHttpClient,
) {
    private val initLock = Mutex()
    @Volatile private var ready = false
    /** Stream links last about six hours; keep them for two. */
    private val infoCache = ConcurrentHashMap<String, Pair<Long, StreamInfo>>()
    /** Videos that failed to resolve (removed, age-restricted, region-blocked): don't try again this session. */
    private val dead = ConcurrentHashMap.newKeySet<String>()

    suspend fun youtubeId(item: MediaItem): String? = runCatching { catalog.trailerKey(item) }.getOrNull()

    /** Best streams no taller than [maxHeight]. Prefers H.264 (every box decodes it in hardware), then VP9. */
    suspend fun resolve(youtubeId: String, maxHeight: Int): TrailerStreams? = withContext(Dispatchers.IO) {
        if (youtubeId in dead) return@withContext null
        val info = info(youtubeId) ?: return@withContext null
        val videos = info.videoOnlyStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.itagItem != null }
        fun pick(list: List<VideoStream>) = list.filter { it.height in 1..maxHeight }.maxWithOrNull(compareBy({ it.height }, { it.fps }))
        val video = pick(videos.filter { it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc") })
            ?: pick(videos)
            ?: videos.minByOrNull { it.height }
            ?: return@withContext null.also { Log.w(TAG, "$youtubeId: no usable video stream") }
        val playable = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.itagItem != null }
        // The original soundtrack (not a dub); YouTube doesn't always label it, so fall back to any track.
        val audios = playable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { playable }
        val audio = audios.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: audios.maxByOrNull { it.averageBitrate }
        Log.i(TAG, "$youtubeId: ${info.audioStreams.size} audio streams (${playable.size} usable: ${playable.joinToString { "${it.format}/${it.audioTrackType}/${it.deliveryMethod}" }}), using ${audio?.format ?: "none"}")
        TrailerStreams(video, audio, info.duration, youtubeId)
    }

    private suspend fun info(id: String): StreamInfo? {
        infoCache[id]?.let { (at, info) -> if (System.currentTimeMillis() - at < 2 * 3600_000L) return info }
        init()
        return runCatching { StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id") }
            .onFailure { Log.w(TAG, "$id: ${it.javaClass.simpleName}: ${it.message}"); dead += id }
            .getOrNull()
            ?.also { infoCache[id] = System.currentTimeMillis() to it }
    }

    private suspend fun init() {
        if (ready) return
        initLock.withLock {
            if (ready) return
            NewPipe.init(OkHttpDownloader(client))
            ready = true
        }
    }

    /** Video (and audio unless [withAudio] is false) as DASH, built from YouTube's single-file streams. */
    @OptIn(UnstableApi::class)
    fun mediaSource(t: TrailerStreams, withAudio: Boolean): MediaSource? = runCatching {
        val factory = YoutubeDataSource.Factory()
        fun dash(content: String, itag: org.schabi.newpipe.extractor.services.youtube.ItagItem): MediaSource {
            val mpd = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(content, itag, t.durationSec)
            val uri = Uri.parse(content)
            val manifest = DashManifestParser().parse(uri, ByteArrayInputStream(mpd.toByteArray()))
            return DashMediaSource.Factory(DefaultDashChunkSource.Factory(factory), factory)
                .createMediaSource(manifest, ExoItem.Builder().setUri(uri).setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD).build())
        }
        val video = dash(t.video.content, t.video.itagItem!!)
        val audio = t.audio?.takeIf { withAudio }?.let { dash(it.content, it.itagItem!!) }
        if (audio == null) video else MergingMediaSource(true, video, audio)
    }.onFailure { Log.w(TAG, "${t.youtubeId}: couldn't build source: ${it.message}") }.getOrNull()

    fun markDead(youtubeId: String) { dead += youtubeId; infoCache.remove(youtubeId) }
}

/** NewPipe Extractor's web requests, over the app's OkHttp. */
private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val b = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body).header("User-Agent", BROWSER_UA)
        request.headers().forEach { (name, values) ->
            b.removeHeader(name)
            values.forEach { b.addHeader(name, it) }
        }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

/**
 * Fetches YouTube "videoplayback" links the way YouTube's own players do (after NewPipe's YoutubeHttpDataSource):
 * the byte range goes in a `range=` parameter instead of a header, each request is numbered (`rn=`), and the
 * request is a POST with a tiny fixed body. Plain range-header GETs get throttled or refused.
 */
@OptIn(UnstableApi::class)
private class YoutubeDataSource(private val upstream: DataSource) : DataSource by upstream {
    private var requestNumber = 0

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        if (dataSpec.uri.path?.startsWith("/videoplayback") != true) return upstream.open(dataSpec)
        val sb = StringBuilder(url)
        if (!url.contains("&rn=")) sb.append("&rn=").append(requestNumber++)
        val pos = dataSpec.position
        val len = dataSpec.length
        if (pos != 0L || len != C.LENGTH_UNSET.toLong()) {
            sb.append("&range=").append(pos).append('-')
            if (len != C.LENGTH_UNSET.toLong()) sb.append(pos + len - 1)
        }
        val headers = HashMap(dataSpec.httpRequestHeaders)
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com"
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        }
        headers["TE"] = "trailers"
        headers["User-Agent"] = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else BROWSER_UA
        val spec = dataSpec.buildUpon()
            .setUri(Uri.parse(sb.toString()))
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(byteArrayOf(0x78, 0))
            .setHttpRequestHeaders(headers)
            .build()
        val opened = upstream.open(spec)
        return if (len != C.LENGTH_UNSET.toLong()) len else opened
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    class Factory : DataSource.Factory {
        private val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        override fun createDataSource(): DataSource = YoutubeDataSource(http.createDataSource())
    }
}
