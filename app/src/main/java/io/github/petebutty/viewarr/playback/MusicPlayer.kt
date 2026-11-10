@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.petebutty.viewarr.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.petebutty.viewarr.data.music.Song
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The music player: one queue, above every page, so music carries on while you browse (the now-playing bar).
 * Starting a film, an episode or a Live TV channel stops it. Its sound is also analysed for the visualiser.
 */
object MusicPlayer {
    private var player: ExoPlayer? = null
    var queue by mutableStateOf<List<Song>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    /** Player.REPEAT_MODE_OFF / ALL / ONE. */
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var error by mutableStateOf<String?>(null)
    /** The Now Playing screen is up (full screen); otherwise the small bar shows while something plays. */
    var nowPlayingOpen by mutableStateOf(false)
    /** Held Back opened Now Playing: it ignores the rest of that press (its release would close it again). */
    var eatBackRelease = false
    /** For the visualiser (and nothing else): the latest frequency bands, 0..1, read every frame. */
    val analyser = Analyser()

    var onScrobble: (songId: String, submission: Boolean) -> Unit = { _, _ -> }

    val current: Song? get() = queue.getOrNull(index)
    val active: Boolean get() = queue.isNotEmpty()
    val positionMs: Long get() = player?.currentPosition ?: 0
    val durationMs: Long get() = player?.duration?.takeIf { it > 0 } ?: ((current?.durationSec ?: 0) * 1000L)

    private var failures = 0
    private var scrobbledId: String? = null

    private fun ensure(context: Context): ExoPlayer = player ?: run {
        val app = context.applicationContext
        val tee = TeeAudioProcessor(analyser)
        val http = DefaultHttpDataSource.Factory().setUserAgent("Viewarr").setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
        ExoPlayer.Builder(app)
            .setRenderersFactory(object : DefaultRenderersFactory(app) {
                override fun buildAudioSink(ctx: Context, floatOutput: Boolean, audioTrackPlaybackParams: Boolean) =
                    DefaultAudioSink.Builder(ctx).setAudioProcessors(arrayOf(tee)).setEnableFloatOutput(false).build()
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
            .setMediaSourceFactory(DefaultMediaSourceFactory(io.github.petebutty.viewarr.data.net.reachOf(http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; analyser.playing = playing }
                    override fun onPlaybackStateChanged(state: Int) {
                        buffering = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_READY) failures = 0
                        if (state == Player.STATE_ENDED) isPlaying = false
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        index = p.currentMediaItemIndex
                        current?.let { onScrobble(it.id, false) }
                        scrobbledId = null
                    }
                    override fun onShuffleModeEnabledChanged(on: Boolean) { shuffle = on }
                    override fun onRepeatModeChanged(mode: Int) { repeat = mode }
                    override fun onPlayerError(e: PlaybackException) {
                        // One bad file shouldn't stop the album: skip it, but give up after a few in a row.
                        failures++
                        if (failures <= 3 && p.hasNextMediaItem()) {
                            error = "Couldn't play ${current?.title ?: "that track"}: skipped"
                            p.seekToNextMediaItem(); p.prepare(); p.play()
                        } else {
                            error = "Couldn't play ${current?.title ?: "that track"}"
                        }
                    }
                })
                player = p
            }
    }

    /** Plays [songs] from [start]; [shuffled] turns shuffle on (starting from a random track). */
    fun play(context: Context, songs: List<Song>, start: Int = 0, shuffled: Boolean = false) {
        if (songs.isEmpty()) return
        io.github.petebutty.viewarr.ui.media.VodHost.close()
        val p = ensure(context)
        error = null
        failures = 0
        queue = songs
        val items = songs.map { s ->
            MediaItem.Builder().setMediaId(s.id).setUri(s.streamUrl).setMediaMetadata(
                MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).build(),
            ).build()
        }
        p.shuffleModeEnabled = shuffled
        val first = if (shuffled) songs.indices.random() else start.coerceIn(0, songs.lastIndex)
        p.setMediaItems(items, first, 0)
        index = first
        p.prepare()
        p.play()
        songs.getOrNull(first)?.let { onScrobble(it.id, false) }
        scrobbledId = null
    }

    /** After a failed track the player stops (idle): the next press gets it going again. */
    private fun ready(p: ExoPlayer) { if (p.playbackState == Player.STATE_IDLE) { error = null; failures = 0; p.prepare() } }
    fun toggle() { player?.let { ready(it); if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0, 0); it.play() } } }
    fun next() { player?.let { if (it.hasNextMediaItem()) { ready(it); it.seekToNextMediaItem(); it.play() } } }
    fun previous() { player?.let { ready(it); if (it.currentPosition > 4_000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem(); it.play() } }
    fun seekBy(ms: Long) { player?.let { it.seekTo((it.currentPosition + ms).coerceIn(0, max(0, it.duration))) } }
    fun jumpTo(i: Int) { player?.let { if (i in queue.indices) { ready(it); it.seekTo(i, 0); it.play() } } }
    fun toggleShuffle() { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        player?.let {
            it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        }
    }
    fun pause() { player?.pause() }

    /** The next few tracks in play order (shuffle included). */
    fun upNext(n: Int): List<Song> {
        val p = player ?: return emptyList()
        val out = ArrayList<Song>()
        var i = p.currentMediaItemIndex
        val tl = p.currentTimeline
        if (tl.isEmpty) return out
        repeat(n) {
            i = tl.getNextWindowIndex(i, if (p.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else p.repeatMode, p.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) return out
            queue.getOrNull(i)?.let(out::add)
        }
        return out
    }

    fun cover(song: Song?, size: Int = 400): String? = song?.art?.let { if (size > 300) it.large else it.small }

    /** Called about once a second while playing: counts a listen at half way (or 4 minutes), as scrobblers do. */
    fun tick() {
        val s = current ?: return
        val p = player ?: return
        if (scrobbledId == s.id || !p.isPlaying) return
        val d = durationMs
        if (d > 30_000 && (p.currentPosition >= d / 2 || p.currentPosition >= 240_000)) {
            scrobbledId = s.id
            onScrobble(s.id, true)
        }
    }

    fun stop() {
        player?.let { it.stop(); it.clearMediaItems() }
        queue = emptyList()
        index = 0
        isPlaying = false
        nowPlayingOpen = false
        analyser.playing = false
    }

    /**
     * Listens to the decoded sound on its way to the speakers and keeps [bands] (bass on the left, treble on the
     * right), smoothed so the bars rise fast and fall gently. The sound reaches the analyser a little before it's
     * heard (the speaker's buffer), so each result is held back by about that long before it's shown.
     */
    class Analyser : TeeAudioProcessor.AudioBufferSink {
        companion object { const val BANDS = 48; private const val N = 2048; private const val LEAD_MS = 280L }
        @Volatile var playing = false
        private var sampleRate = 44_100
        private var channels = 2
        private var encoding = C.ENCODING_PCM_16BIT
        private val mono = FloatArray(N)
        private var filled = 0
        private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
        private val re = FloatArray(N)
        private val im = FloatArray(N)
        // Results waiting to be shown: (time to show, bands).
        private val pending = ArrayDeque<Pair<Long, FloatArray>>()
        private var clock = 0L
        private val lock = Any()
        /** What's on screen now; the visualiser reads it every frame. */
        val bands = FloatArray(BANDS)
        private val peakLevel = FloatArray(BANDS)

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            sampleRate = sampleRateHz; channels = max(1, channelCount); this.encoding = encoding
            filled = 0
            synchronized(lock) { pending.clear(); clock = 0L }
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            val frameBytes = bytesPerSample * channels
            while (b.remaining() >= frameBytes) {
                var sum = 0f
                for (c in 0 until channels) sum += if (bytesPerSample == 4) b.float else b.short / 32768f
                mono[filled++] = sum / channels
                if (filled == N) { analyse(); filled = 0 }
            }
        }

        private fun analyse() {
            for (i in 0 until N) { re[i] = mono[i] * window[i]; im[i] = 0f }
            fft(re, im)
            val out = FloatArray(BANDS)
            val nyquist = sampleRate / 2f
            val lo = 40f; val hi = minOf(16_000f, nyquist)
            for (k in 0 until BANDS) {
                val f0 = lo * (hi / lo).pow(k / BANDS.toFloat())
                val f1 = lo * (hi / lo).pow((k + 1) / BANDS.toFloat())
                val i0 = (f0 / nyquist * (N / 2)).toInt().coerceIn(1, N / 2 - 1)
                val i1 = (f1 / nyquist * (N / 2)).toInt().coerceIn(i0 + 1, N / 2)
                var m = 0f
                for (i in i0 until i1) m = max(m, hypot(re[i], im[i]))
                // To decibels, then 0..1 (about a 44 dB range, so quiet bands sit low), with a gentle lift for the treble.
                val db = 20f * (ln(m / (N / 4f) + 1e-9f) / ln(10f))
                out[k] = ((db + 50f + k * 0.2f) / 44f).coerceIn(0f, 1f)
            }
            val now = System.currentTimeMillis()
            val blockMs = N * 1000L / sampleRate
            synchronized(lock) {
                // Keep a steady clock of when each block will be heard; re-anchor when it drifts (start, after a pause).
                clock = if (clock == 0L || clock < now || clock > now + 2_000) now + LEAD_MS else clock + blockMs
                pending.addLast(clock to out)
                while (pending.size > 40) pending.removeFirst()
            }
        }

        /** Called every frame by the visualiser: moves the bars towards what's being heard now. */
        fun step(dtMs: Long) {
            val now = System.currentTimeMillis()
            var target: FloatArray? = null
            synchronized(lock) {
                while (pending.isNotEmpty() && pending.first().first <= now) target = pending.removeFirst().second
            }
            val fall = (dtMs / 1000f) * 1.6f
            for (k in 0 until BANDS) {
                val t = if (playing) (target?.get(k) ?: -1f) else 0f
                bands[k] = when {
                    t >= bands[k] -> bands[k] + (t - bands[k]) * 0.7f
                    t >= 0f -> max(t, bands[k] - fall)
                    else -> max(0f, bands[k] - fall * 0.5f)
                }
                peakLevel[k] = if (bands[k] > peakLevel[k]) bands[k] else max(0f, peakLevel[k] - fall * 0.35f)
            }
        }
        fun peak(k: Int) = peakLevel[k]
        /** Paused and every bar has come to rest: nothing to draw until it plays again. */
        val idle: Boolean get() = !playing && bands.all { it == 0f } && peakLevel.all { it == 0f }

        private fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f; var ci = 0f
                    for (k in 0 until len / 2) {
                        val ar = re[i + k]; val ai = im[i + k]
                        val br = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val bi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ar + br; im[i + k] = ai + bi
                        re[i + k + len / 2] = ar - br; im[i + k + len / 2] = ai - bi
                        val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
